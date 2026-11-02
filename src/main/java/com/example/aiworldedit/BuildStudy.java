package com.example.aiworldedit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Learning from saved builds: measures each one (proportions, how the walls are layered, roof shape,
 * materials by role), turns that into a material palette the style presets can use, and makes text
 * drawings plus a picture the AI can study before it designs its own build. Nothing is copied into the world.
 */
public final class BuildStudy {
    private BuildStudy() {}

    /** Block colours (RGB) by id, set by the mod from the game's map colours; null = guessed colours. */
    public static volatile java.util.function.Supplier<Map<String, Integer>> colorSource = null;
    private static Map<String, Integer> colors = null;

    /** What was learned from one build. */
    public record Digest(String name, int width, int depth, int height, String text, Map<String, String> palette,
                         String views) {}

    private static final Map<String, Digest> CACHE = new HashMap<>();

    public static synchronized Digest digest(BuildLibrary.Entry e) {
        return CACHE.computeIfAbsent(e.name + "#" + e.blockCount, k -> analyse(e));
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    // ---------------- block classes ----------------

    /** Natural ground, plants and crops some schematics include around the build (ignored when learning). */
    static boolean terrain(String b) {
        return b.equals("grass_block") || b.equals("dirt") || b.equals("coarse_dirt") || b.equals("podzol") || b.equals("rooted_dirt")
            || b.equals("mud") || b.equals("bedrock") || b.equals("gravel") || b.equals("sand") || b.equals("red_sand") || b.equals("clay")
            || b.equals("farmland") || b.equals("dirt_path") || b.equals("mycelium") || b.equals("water") || b.equals("lava")
            || b.equals("short_grass") || b.equals("tall_grass") || b.contains("fern") || b.equals("dead_bush") || b.equals("sugar_cane")
            || b.equals("wheat") || b.equals("carrots") || b.equals("potatoes") || b.equals("beetroots") || b.equals("seagrass")
            || b.contains("tulip") || b.equals("allium") || b.equals("poppy") || b.equals("dandelion") || b.equals("cornflower")
            || b.contains("orchid") || b.contains("daisy") || b.contains("bluet") || b.equals("lily_of_the_valley") || b.contains("rose_bush")
            || b.equals("peony") || b.equals("lilac") || b.equals("sunflower") || b.contains("petals") || b.equals("sweet_berry_bush")
            || b.contains("fungus") || b.contains("mushroom") && !b.contains("block") || b.contains("roots") || b.contains("sapling");
    }

    static boolean decor(String b) {
        return terrain(b) || b.contains("composter") || b.contains("anvil") || b.equals("campfire") || b.equals("bamboo")
            || b.contains("chest") || b.equals("barrel") || b.contains("furnace") || b.equals("ladder") || b.equals("hopper")
            || b.equals("observer") || b.contains("cauldron") || b.contains("_bed") || b.equals("crafting_table") || b.equals("smoker")
            || b.contains("leaves") || b.contains("vine") || b.contains("carpet") || b.contains("flower") || b.contains("azalea")
            || b.contains("grass") || b.contains("fern") || b.contains("lantern") || b.contains("torch") || b.contains("chain")
            || b.contains("pot") || b.contains("sign") || b.contains("banner") || b.contains("button") || b.contains("pressure_plate")
            || b.contains("candle") || b.equals("bell") || b.contains("lightning_rod") || b.contains("spore") || b.contains("bud")
            || b.equals("moss_block") || b.contains("snow") || b.contains("cobweb") || b.contains("lichen") || b.contains("dripleaf");
    }

    static boolean stone(String b) {
        if (b.contains("calcite") || b.contains("terracotta") || b.contains("concrete")) return false;
        return b.contains("stone") || b.contains("brick") || b.contains("tuff") || b.contains("deepslate") || b.contains("andesite")
            || b.contains("diorite") || b.contains("granite") || b.contains("cobble") || b.contains("basalt") || b.contains("blackstone")
            || b.contains("prismarine") || b.contains("quartz") || b.equals("stone");
    }

    static boolean log(String b) {
        return b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_stem") || b.endsWith("_hyphae");
    }

    static boolean glass(String b) {
        return b.contains("glass") || b.equals("iron_bars");
    }

    /** Small or see-through blocks that are never a wall material (heads, string, pickles, rails, rods...). */
    static boolean tiny(String b) {
        return b.contains("head") || b.contains("skull") || b.contains("tripwire") || b.equals("string") || b.contains("pickle")
            || b.contains("rail") || b.contains("_rod") || b.contains("bars") || b.contains("button") || b.contains("lever")
            || b.contains("frame") || b.contains("painting") || b.contains("scaffolding") || b.contains("bamboo") || b.contains("coral_fan")
            || b.contains("brewing") || b.contains("egg") || b.contains("shelf") || b.contains("litter") || b.contains("candle") || b.contains("amethyst_cluster") || b.contains("pointed_dripstone")
            || b.contains("cobweb") || b.contains("_plate") || b.contains("hook") || b.contains("repeater") || b.contains("comparator");
    }

    static boolean partial(String b) {
        return b.endsWith("_stairs") || b.endsWith("_slab") || b.endsWith("_trapdoor") || b.endsWith("_fence")
            || b.endsWith("_wall") || b.endsWith("_door") || b.endsWith("_gate");
    }

    // ---------------- local grid (front toward the viewer) ----------------

    /** Base ids in local order: x across the front (viewer's left to right), z from the front back, y up. */
    static final class Grid {
        final int w, h, d;
        final String[] b;
        final int ground;

        Grid(int w, int h, int d, int ground) {
            this.w = w; this.h = h; this.d = d; this.ground = ground;
            b = new String[w * h * d];
        }

        String at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) return null;
            return b[(y * d + z) * w + x];
        }
    }

    static Grid grid(BuildLibrary.Entry e) {
        int[] ls = e.localSize(0);
        Grid g = new Grid(ls[0], e.h, ls[2], e.groundLayer);
        int[] fv = switch (e.front) { case "north" -> new int[]{0, 1}; case "east" -> new int[]{-1, 0};
            case "west" -> new int[]{1, 0}; default -> new int[]{0, -1}; };
        int fx = fv[0], fz = fv[1], rx = -fz, rz = fx;
        int uOff = rx < 0 ? e.w - 1 : rz < 0 ? e.d - 1 : 0, vOff = fx < 0 ? e.w - 1 : fz < 0 ? e.d - 1 : 0;
        String[] base = new String[e.palette.length];
        for (int i = 0; i < base.length; i++) {
            base[i] = e.isAir[i] ? null : VoxelGrid.baseId(e.palette[i]);
            if (base[i] != null && terrain(base[i])) base[i] = null; // learn from the build, not the ground it sits on
        }
        for (int y = 0; y < e.h; y++) {
            for (int sz = 0; sz < e.d; sz++) {
                for (int sx = 0; sx < e.w; sx++) {
                    String id = base[e.at(sx, y, sz)];
                    if (id == null) continue;
                    int u = sx * rx + sz * rz + uOff, v = sx * fx + sz * fz + vOff;
                    g.b[(y * g.d + v) * g.w + u] = id;
                }
            }
        }
        return g;
    }

    // ---------------- analysis ----------------

    private static Digest analyse(BuildLibrary.Entry e) {
        Grid g = grid(e);
        int top = 0;
        for (int i = 0; i < g.b.length; i++) if (g.b[i] != null) top = Math.max(top, i / (g.w * g.d));

        // Footprint: what stands on the ground floor (layer above the ground layer).
        int foot = 0, fx1 = g.w, fx2 = -1, fz1 = g.d, fz2 = -1;
        for (int z = 0; z < g.d; z++) for (int x = 0; x < g.w; x++) {
            boolean any = false;
            for (int y = g.ground + 1; y <= Math.min(g.h - 1, g.ground + 3) && !any; y++) {
                String s = g.at(x, y, z);
                any = s != null && !decor(s);
            }
            if (any) { foot++; fx1 = Math.min(fx1, x); fx2 = Math.max(fx2, x); fz1 = Math.min(fz1, z); fz2 = Math.max(fz2, z); }
        }
        int fw = Math.max(1, fx2 - fx1 + 1), fd = Math.max(1, fz2 - fz1 + 1);
        double fill = foot / (double) (fw * fd);

        // Roof: the highest structural block in every column.
        Map<String, Integer> roofTop = new HashMap<>(), roofEdge = new HashMap<>();
        Map<String, Long> roofHeightSum = new HashMap<>();
        int[][] topY = new int[g.w][g.d];
        int rx1 = g.w, rx2 = -1, rz1 = g.d, rz2 = -1, peak = 0;
        for (int x = 0; x < g.w; x++) for (int z = 0; z < g.d; z++) {
            topY[x][z] = -1;
            for (int y = top; y > g.ground; y--) {
                String s = g.at(x, y, z);
                if (s == null || decor(s)) continue;
                topY[x][z] = y;
                break;
            }
        }
        // Roof columns: the top is stairs/slab well above the ground. The eave is where the lowest
        // tenth of them are (ignores porch roofs and steps), the peak is the highest.
        List<Integer> roofYs = new ArrayList<>();
        for (int x = 0; x < g.w; x++) for (int z = 0; z < g.d; z++) {
            int y = topY[x][z];
            if (y < g.ground + 3) continue;
            String s = g.at(x, y, z);
            if (s.endsWith("_stairs") || s.endsWith("_slab")) roofYs.add(y);
        }
        java.util.Collections.sort(roofYs);
        int eave = roofYs.isEmpty() ? top : roofYs.get(roofYs.size() / 10);
        for (int x = 0; x < g.w; x++) for (int z = 0; z < g.d; z++) {
            int y = topY[x][z];
            if (y < eave) continue;
            String s = g.at(x, y, z);
            if (!(s.endsWith("_stairs") || s.endsWith("_slab") || s.endsWith("_planks"))) continue;
            boolean edge = false;
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + dd[0], nz = z + dd[1];
                if (nx < 0 || nz < 0 || nx >= g.w || nz >= g.d || topY[nx][nz] < eave) edge = true;
            }
            String st = asStairs(s);
            if (edge) roofEdge.merge(st, 1, Integer::sum);
            else { roofTop.merge(st, 1, Integer::sum); roofHeightSum.merge(st, (long) y, Long::sum); }
            rx1 = Math.min(rx1, x); rx2 = Math.max(rx2, x); rz1 = Math.min(rz1, z); rz2 = Math.max(rz2, z);
            peak = Math.max(peak, y);
        }
        int overhang = rx2 < 0 ? 0 : Math.max(0, Math.round(((fx1 - rx1) + (rx2 - fx2) + (fz1 - rz1) + (rz2 - fz2)) / 4f));
        int wallH = Math.max(1, eave - g.ground);
        int roofH = Math.max(0, peak - eave);

        // Walls: the outer shell, sampled from all four sides, layer by layer.
        List<Map<String, Integer>> shell = new ArrayList<>();
        for (int y = 0; y < g.h; y++) shell.add(new HashMap<>());
        for (int y = g.ground + 1; y < Math.min(top + 1, eave); y++) {
            Map<String, Integer> m = shell.get(y);
            for (int x = 0; x < g.w; x++) { sample(g, m, x, y, 0, 0, 1); sample(g, m, x, y, g.d - 1, 0, -1); }
            for (int z = 0; z < g.d; z++) { sampleX(g, m, z, y, 0, 1); sampleX(g, m, z, y, g.w - 1, -1); }
        }
        int baseTop = g.ground;
        for (int y = g.ground + 1; y < eave; y++) {
            Map<String, Integer> m = shell.get(y);
            int st = count(m, BuildStudy::stone), all = count(m, s -> !glass(s) && !s.endsWith("_door"));
            if (all > 0 && st * 2 >= all) baseTop = y; else break;
        }
        Map<String, Integer> baseMat = new HashMap<>(), upperFill = new HashMap<>(), upperLog = new HashMap<>();
        int glassN = 0, shellN = 0;
        Map<String, Integer> trap = new HashMap<>(), fence = new HashMap<>(), doors = new HashMap<>();
        for (int y = g.ground + 1; y < eave; y++) {
            for (Map.Entry<String, Integer> en : shell.get(y).entrySet()) {
                String s = en.getKey();
                int n = en.getValue();
                shellN += n;
                if (glass(s)) glassN += n;
                if (s.endsWith("_trapdoor")) trap.merge(s, n, Integer::sum);
                if (y <= baseTop && stone(s) && !partial(s) && !tiny(s)) baseMat.merge(s, n, Integer::sum);
                if (y > baseTop) {
                    if (log(s)) upperLog.merge(s, n, Integer::sum);
                    else if (!glass(s) && !partial(s) && !decor(s) && !tiny(s)) upperFill.merge(s, n, Integer::sum);
                }
            }
        }
        for (int i = 0; i < g.b.length; i++) {
            String s = g.b[i];
            if (s == null) continue;
            if (s.endsWith("_fence")) fence.merge(s, 1, Integer::sum);
            if (s.endsWith("_door")) doors.merge(s, 1, Integer::sum);
        }
        Map<String, Integer> floorMat = new HashMap<>();
        for (int z = 1; z < g.d - 1; z++) for (int x = 1; x < g.w - 1; x++) {
            String s = g.at(x, g.ground, z);
            if (s != null && (s.endsWith("_planks") || stone(s)) && !partial(s)) floorMat.merge(s, 1, Integer::sum);
        }
        Map<String, Integer> foundation = new HashMap<>();
        for (int z = 0; z < g.d; z++) for (int x = 0; x < g.w; x++) {
            String s = g.at(x, g.ground, z);
            if (s != null && stone(s) && !partial(s)) foundation.merge(s, 1, Integer::sum);
        }
        int green = 0;
        for (Map.Entry<String, Integer> en : e.counts.entrySet()) {
            String s = en.getKey();
            if (s.contains("leaves") || s.contains("vine") || s.contains("moss") || s.contains("azalea") || s.contains("lichen")) green += en.getValue();
        }

        // Palette for the style presets (only what was clearly found).
        Map<String, String> pal = new LinkedHashMap<>();
        put(pal, "lowerWall", cleanMix(baseMat, 2));
        Map<String, Integer> softFill = new HashMap<>(upperFill);
        softFill.keySet().removeIf(BuildStudy::stone); // plaster/planks between the beams, not stone towers
        put(pal, "wall", cleanMix(softFill.isEmpty() ? upperFill : softFill, 2));
        String frame = first(upperLog);
        String wallMat = pal.get("wall");
        if (frame != null && wallMat != null && woodOf(frame) != null && wallMat.contains(woodOf(frame))) {
            // Beams the same wood as the walls wouldn't show: take the build's main log of another wood.
            String wf = woodOf(frame);
            frame = e.counts.entrySet().stream().filter(en -> log(en.getKey()) && !en.getKey().contains(wf))
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(frame);
        }
        put(pal, "frame", frame);
        put(pal, "foundation", mix(foundation, 3));
        put(pal, "floor", first(floorMat.entrySet().stream().filter(en -> en.getKey().endsWith("_planks"))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        // Trim: the stairs that outline the roof. Gradient: the main roof stairs (without the trim),
        // from the lowest average height to the highest.
        String trim = first(roofEdge.entrySet().stream().filter(en -> en.getKey().endsWith("_stairs"))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        List<String> roofKinds = roofTop.entrySet().stream().filter(en -> en.getKey().endsWith("_stairs"))
            .sorted((a, b) -> b.getValue() - a.getValue()).limit(4).map(Map.Entry::getKey).toList();
        List<String> grad = new ArrayList<>(roofKinds);
        if (grad.size() > 1 && trim != null) grad.remove(trim);
        if (grad.size() > 3) grad = new ArrayList<>(grad.subList(0, 3));
        grad.sort(Comparator.comparingDouble(k -> roofHeightSum.getOrDefault(k, 0L) / (double) Math.max(1, roofTop.get(k))));
        if (!grad.isEmpty()) pal.put("roofGradient", String.join("|", grad));
        put(pal, "roofTrim", trim);
        put(pal, "shutters", first(trap));
        put(pal, "finial", first(fence));
        put(pal, "door", first(doors));
        String chisel = baseMat.keySet().stream().filter(s -> s.startsWith("chiseled_") || s.startsWith("polished_")).findFirst().orElse(null);
        put(pal, "cornerBlock", chisel != null ? chisel : first(baseMat));
        put(pal, "chimneyBlock", mix(baseMat, 3));
        if (green * 100 / Math.max(1, e.blockCount) >= 4) { pal.put("roofMoss", "true"); pal.put("overgrowth", "true"); }

        String shape = fill > 0.85 ? "rectangular" : fill > 0.6 ? "L- or T-shaped" : "cross-shaped or clustered";
        double pitch = roofH / Math.max(1.0, Math.min(rx2 - rx1 + 1, rz2 - rz1 + 1) / 2.0);
        String text = (e.tags.isEmpty() ? "" : "[" + String.join(" ", e.tags) + "] ") + String.format(Locale.ROOT,
            "%s - %d wide x %d deep x %d tall; footprint %s (%d%% of its box filled). "
                + "Walls %d high (about %d floors): stone base %d layers (%s), above it %s with %s framing, %d%% of the wall is glass. "
                + "Roof %d high = %d%% of the total height, pitch %.1f (%s), overhang %d; roof %s, edges %s. %s",
            e.name, g.w, g.d, top - g.ground, shape, Math.round(fill * 100),
            wallH, Math.max(1, Math.round(wallH / 4.5f)), Math.max(0, baseTop - g.ground), or(mix(baseMat, 3), "none"),
            or(pal.get("wall"), "-"), or(pal.get("frame"), "no"), Math.round(100.0 * glassN / Math.max(1, shellN)),
            roofH, Math.round(100.0 * roofH / Math.max(1, top - g.ground)), pitch,
            pitch >= 1.6 ? "very steep" : pitch >= 1.0 ? "steep" : "low", overhang,
            or(String.join(" -> ", grad), "-"), or(pal.get("roofTrim"), "-"),
            green * 100 / Math.max(1, e.blockCount) >= 4 ? "Overgrown: " + green * 100 / Math.max(1, e.blockCount) + "% leaves/moss/vines." : "");
        return new Digest(e.name, g.w, g.d, top - g.ground, text, pal, views(g, top));
    }

    private static void sample(Grid g, Map<String, Integer> m, int x, int y, int z0, int dz0, int step) {
        for (int z = z0; z >= 0 && z < g.d; z += step) {
            String s = g.at(x, y, z);
            if (s == null || tiny(s) || decor(s)) continue; // look past heads, string, ivy and plants to the wall
            m.merge(s, 1, Integer::sum);
            return;
        }
    }

    private static void sampleX(Grid g, Map<String, Integer> m, int z, int y, int x0, int step) {
        for (int x = x0; x >= 0 && x < g.w; x += step) {
            String s = g.at(x, y, z);
            if (s == null || tiny(s) || decor(s)) continue; // look past heads, string, ivy and plants to the wall
            m.merge(s, 1, Integer::sum);
            return;
        }
    }

    private static int count(Map<String, Integer> m, Predicate<String> p) {
        int n = 0;
        for (Map.Entry<String, Integer> en : m.entrySet()) if (p.test(en.getKey())) n += en.getValue();
        return n;
    }

    private static String woodOf(String b) {
        for (String w : new String[]{"dark_oak", "pale_oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped", "oak"}) {
            if (b.contains(w)) return w;
        }
        return null;
    }

    static String asStairs(String s) {
        if (s.endsWith("_slab")) return s.substring(0, s.length() - 5) + "_stairs";
        if (s.endsWith("_planks")) return s.substring(0, s.length() - 7) + "_stairs";
        return s;
    }

    private static String first(Map<String, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Weighted mix of the top n blocks, e.g. "50%tuff_bricks,30%tuff,20%polished_tuff". */
    private static String mix(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> all = m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).toList();
        if (all.isEmpty()) return null;
        int sum = all.stream().mapToInt(Map.Entry::getValue).sum();
        // stray blocks (under 8%) are noise, not part of the material mix
        List<Map.Entry<String, Integer>> top = new ArrayList<>();
        for (Map.Entry<String, Integer> en : all) if (top.isEmpty() || en.getValue() * 100 >= sum * 8) top.add(en);
        if (top.size() == 1) return top.get(0).getKey();
        int total = top.stream().mapToInt(Map.Entry::getValue).sum();
        int[] pct = new int[top.size()];
        int used = 0;
        for (int i = 0; i < top.size(); i++) { pct[i] = Math.max(5, Math.round(100f * top.get(i).getValue() / total)); used += pct[i]; }
        pct[0] += 100 - used; // rounding remainder goes to the main block
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) parts.add(pct[i] + "%" + top.get(i).getKey());
        return String.join(",", parts);
    }

    /**
     * A clean material for a surface: the main block alone when it clearly dominates (60%+), otherwise the top
     * few. Learned palettes use this so a house never gets speckled walls from stray blocks in the example.
     */
    private static String cleanMix(Map<String, Integer> m, int n) {
        int total = m.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return null;
        Map.Entry<String, Integer> top = m.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (top != null && top.getValue() * 10 >= total * 6) return top.getKey();
        return mix(m, n);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String or(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    // ---------------- drawings for the AI ----------------

    /** Front elevation and top view, one letter per block type. Big builds are drawn at half scale. */
    private static String views(Grid g, int top) {
        int step = Math.max(g.w, Math.max(g.d, top - g.ground + 1)) > 48 ? 2 : 1;
        Map<String, Character> legend = new LinkedHashMap<>();
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.function.Function<String, Character> ch = s -> {
            if (s == null) return ' ';
            Character c = legend.get(s);
            if (c == null) {
                c = legend.size() < letters.length() ? letters.charAt(legend.size()) : '?';
                legend.put(s, c);
            }
            return c;
        };
        StringBuilder front = new StringBuilder("FRONT VIEW (nearest block; left to right, top row first):\n");
        for (int y = top; y >= g.ground; y -= step) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < g.w; x += step) {
                String s = null;
                for (int z = 0; z < g.d && s == null; z++) s = g.at(x, y, z);
                row.append(ch.apply(s));
            }
            front.append(String.format(Locale.ROOT, "%3d |", y - g.ground)).append(row).append("|\n");
        }
        StringBuilder topV = new StringBuilder("TOP VIEW (highest block; top row = back, bottom row = front):\n");
        for (int z = g.d - 1; z >= 0; z -= step) {
            StringBuilder row = new StringBuilder("    |");
            for (int x = 0; x < g.w; x += step) {
                String s = null;
                for (int y = top; y >= g.ground && s == null; y--) s = g.at(x, y, z);
                row.append(ch.apply(s));
            }
            topV.append(row).append("|\n");
        }
        StringBuilder leg = new StringBuilder("Legend: ");
        legend.forEach((k, v) -> leg.append(v).append('=').append(k).append("  "));
        return (step > 1 ? "(drawn at half scale)\n" : "") + front + topV + leg.toString().trim();
    }

    // ---------------- picture ----------------

    /** An isometric picture of the build from the front-left (PNG), for models that can see images. */
    public static byte[] render(BuildLibrary.Entry e, int maxPx) {
        Grid g = grid(e);
        synchronized (BuildStudy.class) {
            if (colors == null && colorSource != null) {
                try { colors = colorSource.get(); } catch (Throwable t) { colors = Map.of(); }
            }
        }
        int s = Math.max(2, Math.min(10, maxPx / Math.max(1, (g.w + g.d) * 2)));
        double hy = s * 1.2;
        // Screen position of block (x,y,z): viewer at the front-left corner (-x, -z).
        int W = (g.w + g.d + 4) * s, H = (int) ((g.h * 1.2 + (g.w + g.d) / 2.0 + 6) * s);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        gr.setColor(new Color(200, 225, 245));
        gr.fillRect(0, 0, W, H);
        double ox = (g.d + 2) * s, oy = (g.h * 1.2 + (g.w + g.d) / 2.0 + 3) * s;
        // The viewer is at the front-left (-x, -z): draw far blocks (large x+z) first, bottom up.
        for (int sum = g.w + g.d - 2; sum >= 0; sum--) {
            for (int y = 0; y < g.h; y++) {
                for (int x = Math.max(0, sum - g.d + 1); x <= Math.min(g.w - 1, sum); x++) {
                    int z = sum - x;
                    String b = g.at(x, y, z);
                    if (b == null) continue;
                    int rgb = colorOf(b);
                    double px = ox + (x - z) * s, py = oy - y * hy - (x + z) * s / 2.0;
                    double hh = partial(b) || decor(b) ? hy * 0.5 : hy;
                    Polygon topP = poly(px, py - hh - s / 2.0, px + s, py - hh, px, py - hh + s / 2.0, px - s, py - hh);
                    Polygon left = poly(px - s, py - hh, px, py - hh + s / 2.0, px, py + s / 2.0, px - s, py);
                    Polygon right = poly(px, py - hh + s / 2.0, px + s, py - hh, px + s, py, px, py + s / 2.0);
                    gr.setColor(shade(rgb, 0.82)); gr.fillPolygon(left);
                    gr.setColor(shade(rgb, 0.64)); gr.fillPolygon(right);
                    gr.setColor(shade(rgb, 1.0)); gr.fillPolygon(topP);
                }
            }
        }
        gr.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Polygon poly(double... p) {
        Polygon pg = new Polygon();
        for (int i = 0; i < p.length; i += 2) pg.addPoint((int) Math.round(p[i]), (int) Math.round(p[i + 1]));
        return pg;
    }

    private static Color shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), g = (int) Math.min(255, ((rgb >> 8) & 255) * f), b = (int) Math.min(255, (rgb & 255) * f);
        return new Color(r, g, b);
    }

    private static int colorOf(String b) {
        Map<String, Integer> c = colors;
        if (c != null) {
            Integer v = c.get(b);
            if (v != null && v != 0) return v;
            v = c.get(b.replace("_stairs", "_planks").replace("_slab", "_planks"));
            if (v != null && v != 0) return v;
        }
        // Guess from the name when the game's colours aren't available.
        String[][] guess = {{"spruce", "735531"}, {"dark_oak", "422B14"}, {"pale_oak", "E4DAD4"}, {"oak", "A2824E"},
            {"birch", "C4B37B"}, {"jungle", "A07350"}, {"acacia", "A85A32"}, {"mangrove", "75362F"}, {"cherry", "E3B2AC"},
            {"tuff", "6C6D66"}, {"deepslate", "4A4A4C"}, {"cobble", "767676"}, {"stone", "7A797A"}, {"brick", "966153"},
            {"sandstone", "D8CB9B"}, {"calcite", "E1E2DD"}, {"terracotta", "D2B2A1"}, {"glass", "AAD2E6"}, {"leaves", "5F7832"},
            {"moss", "5A782D"}, {"vine", "50702A"}, {"lantern", "F0C85A"}, {"andesite", "888889"}, {"quartz", "EBE5DE"}};
        for (String[] gs : guess) if (b.contains(gs[0])) return Integer.parseInt(gs[1], 16);
        return 0x9A8F80;
    }

    // ---------------- picking examples for a request ----------------

    private static int rotate = 0;

    /** Up to n saved builds that best match the request (word overlap), rotating through the rest. */
    public static List<BuildLibrary.Entry> pick(String request, int n) {
        List<BuildLibrary.Entry> all = BuildLibrary.all();
        if (all.isEmpty() || n <= 0) return List.of();
        String r = request == null ? "" : request.toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String w : r.split("[^a-z]+")) if (w.length() >= 4) words.add(w);
        java.util.Set<String> direct = new java.util.HashSet<>(words); // words actually in the request
        // Words that mean the same kind of build as a folder tag.
        String[][] syn = {{"house", "home", "cottage", "cabin", "hut", "shop", "inn"}, {"tavern", "inn", "pub", "bar"},
            {"castle", "fort", "fortress", "keep", "citadel", "palace"}, {"tower", "wizard", "spire", "watchtower", "lighthouse"},
            {"mill", "windmill", "watermill"}, {"hall", "guild", "market", "trading", "barracks"},
            {"fantasy", "magic", "wizard", "elven", "fairy", "enchanted"}, {"gothic", "cathedral", "church", "dark"},
            {"blacksmith", "smith", "smithy", "forge", "weaponsmith", "toolsmith", "armory", "armourer"},
            {"bakery", "baker", "bread"}, {"fisherman", "fishing", "fisher", "dock", "harbor", "harbour", "pier"},
            {"farm", "farmhouse", "barn", "farmer"}, {"trixy", "trixyblox"}};
        for (String[] group : syn) {
            for (String w : group) {
                if (r.contains(w)) { for (String g : group) if (!words.contains(g)) words.add(g); break; }
            }
        }
        List<BuildLibrary.Entry> scored = new ArrayList<>(all);
        int start;
        synchronized (BuildStudy.class) { start = rotate++; }
        Map<BuildLibrary.Entry, Integer> score = new HashMap<>();
        // Tags few builds share (a builder's name, a specific kind of building) count extra.
        Map<String, Integer> tagCount = new HashMap<>();
        for (BuildLibrary.Entry e : scored) for (String t : e.tags) tagCount.merge(t, 1, Integer::sum);
        for (int i = 0; i < scored.size(); i++) {
            BuildLibrary.Entry e = scored.get(i);
            String hay = (e.name + " " + e.title + " " + e.description).toLowerCase(Locale.ROOT);
            int sc = 0;
            for (String w : words) {
                String tag = null;
                for (String t : e.tags) if (t.equals(w) || (w.length() >= 5 && (t.startsWith(w) || w.startsWith(t)))) tag = t;
                if (tag != null) { // folder tags are the strongest signal
                    sc += 30;
                    if (tagCount.getOrDefault(tag, 0) * 4 < scored.size()) sc += 30;
                } else if (hay.contains(w)) sc += e.crowd() ? (direct.contains(w) ? 25 : 0) : 10; // crowd builds list their labelled parts
            }
            if (e.crowd()) sc -= 25; // simple crowd builds only win when their parts match the request well
            sc += Math.floorMod(i - start, scored.size()) == 0 ? 1 : 0; // nudge a different one each time on ties
            score.put(e, sc * 1000 - Math.floorMod(i - start, scored.size()));
        }
        scored.sort((a, b) -> score.get(b) - score.get(a));
        // At most one crowd build, and only when it actually matched something.
        List<BuildLibrary.Entry> out = new ArrayList<>();
        boolean crowdUsed = false;
        for (BuildLibrary.Entry e : scored) {
            if (out.size() >= n) break;
            if (e.crowd()) {
                if (crowdUsed || score.get(e) < 5 * 1000) continue;
                crowdUsed = true;
            }
            out.add(e);
        }
        return out;
    }

    /** The STUDY section for a build request. */
    public static String promptSection(List<BuildLibrary.Entry> examples, boolean withViews) {
        if (examples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("STUDY EXAMPLES - good builds by experienced builders. Do NOT copy them; learn how they\n"
            + "are proportioned and layered (base, walls, roof, details, materials) and design your own build to the same standard.\n"
            + "Use their measurements as guidance: roof share of the height, overhang, glass share, how big the stone base is.\n");
        for (BuildLibrary.Entry e : examples) {
            Digest d = digest(e);
            sb.append("\n* ");
            if (e.crowd()) sb.append("(simple crowd-built house, shown for its parts/layout only - "
                + e.description.replace("Simple crowd-built house. ", "") + ") ");
            sb.append(d.text()).append('\n');
            if (withViews) sb.append(d.views()).append('\n');
        }
        return sb.toString();
    }
}
