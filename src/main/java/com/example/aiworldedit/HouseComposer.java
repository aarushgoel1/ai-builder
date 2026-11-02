package com.example.aiworldedit;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Molded medieval houses (v21). Learned from rebuilding a reference survival house by hand: it is not more
 * blocks or more commands that make a build look sculpted, it is how the parts are shaped and fitted together.
 * So a house is designed block by block on a {@link Canvas}, the way a builder works, and compiled into WorldEdit
 * boxes afterwards (a detailed house costs about as many commands as the old box houses).
 *
 * The parts, each sized from the house (nothing is a fixed template):
 *  - a tall stone ground storey (base course, quoins, recessed panels, framed door)
 *  - upper storeys with columns standing proud of recessed infill and stepped corbels between them; timber-framed
 *    sides with shuttered windows
 *  - a heavy beam across the front with square end blocks, a composed gable above it (attic balcony and door, an
 *    oak band, a lit window, an ornament at the peak)
 *  - a big flared roof: shallow at the eaves and steeper toward the ridge, thick edges, a ragged front verge, and
 *    a long sweep down over a low annex
 *  - a low annex on a stone platform with a hipped end and a gablet, a stone-to-light chimney with an open cap
 *  - a tall pilaster tower: oak corners and bands, a corbelled ledge, a jettied top room, a flared spire, maybe a
 *    crane with a hanging basket
 *  - a deep deck on big posts with stepped joists, or a covered porch, or an oriel
 * Every one of these is switched by the house's settings and seed, so neighbours differ.
 */
final class HouseComposer {
    private final JsonObject c;
    private final BuildPlanner.Frame f;
    private final BuildPlanner.Ground ground;
    private final List<int[]> avoid;
    private final Canvas cv = new Canvas();
    private final int seed;

    private int x1, x2, z1, z2, w, d, cx, by, F, top, o, peakY;
    private int[] sAt, hAt;
    private boolean stoneGround, ridgeZ;
    private String wall, stone, base, log, wood, planks, stairs, slab, fence, trap, door, glass, lit, trim, chim;
    private List<String> grad;
    private int[] roofRowY; // height of each roof row from the eave in
    private int roofRows, roofLowX, roofHighX, zf, zb;

    private HouseComposer(JsonObject c, BuildPlanner.Frame f, BuildPlanner.Ground ground, List<int[]> avoid, int salt) {
        this.c = c; this.f = f; this.ground = ground; this.avoid = avoid;
        int h0 = num("x", 0) * 73856093 ^ num("z", 0) * 19349663 ^ num("width", 9) * 83492791 ^ salt * 0x9E3779B1;
        h0 ^= h0 >>> 15; h0 *= 0x2C1B3C6D; h0 ^= h0 >>> 12;
        this.seed = Math.floorMod(h0, 1_000_003);
    }

    /** WorldEdit commands for one building component, or null if it can't be composed. */
    static List<String> compose(JsonObject c, BuildPlanner.Frame f, BuildPlanner.Ground g, List<int[]> avoid, int salt, List<int[]> claimed) {
        HouseComposer hc = new HouseComposer(c, f, g, avoid, salt);
        hc.build();
        int[] bb = hc.cv.bounds();
        if (bb != null && claimed != null) claimed.add(new int[]{bb[0], bb[3], bb[2], bb[5]}); // the whole house, roofs included
        List<String> out = hc.cv.compile(f);
        int[] b = hc.cv.bounds();
        if (b != null && hc.bool("snowRoof", false)) { // a thin layer of snow over everything
            out.add("//pos1 " + f.pos(b[0], b[1], b[2]));
            out.add("//pos2 " + f.pos(b[3], b[4] + 1, b[5]));
            out.add("//overlay snow");
        }
        return out;
    }

    /** True when the composer should build this component (medieval-styled house without hand-made wings). */
    static boolean wants(JsonObject c) {
        if (!c.has("archetype") || c.has("wings") || c.has("wing")) return false;
        if (c.has("composer") && c.get("composer").isJsonPrimitive() && !c.get("composer").getAsBoolean()) return false;
        return !c.has("_attach");
    }

    // ------------------------------------------------------------------ settings

    private int num(String k, int def) {
        try { return c.has(k) ? (int) Math.round(c.get(k).getAsDouble()) : def; } catch (Exception e) { return def; }
    }

    private String str(String k, String def) {
        return c.has(k) && c.get(k).isJsonPrimitive() ? c.get(k).getAsString() : def;
    }

    private boolean bool(String k, boolean def) {
        try { return c.has(k) ? c.get(k).getAsBoolean() : def; } catch (Exception e) { return def; }
    }

    private int rnd(int salt, int n) { return Math.floorMod(seed / 7 + salt * 7919 + (seed % 97) * salt, n); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String first(String pattern) {
        String p = VoxelGrid.splitPattern(pattern).get(0);
        int i = p.indexOf('%');
        return VoxelGrid.baseId(i >= 0 ? p.substring(i + 1) : p);
    }

    private static String species(String block) {
        String b = block == null ? "" : first(block).replace("stripped_", "");
        for (String s : new String[]{"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "crimson", "warped", "bamboo"})
            if (b.startsWith(s + "_")) return s;
        return null;
    }

    // ------------------------------------------------------------------ block helpers (local directions -> world states)

    private String st(String block, String localDir, boolean top) {
        if (block.contains(",") || block.contains("%")) { // weighted mix: state on every part
            List<String> out = new ArrayList<>();
            for (String part : VoxelGrid.splitPattern(block)) {
                int i = part.indexOf('%');
                out.add((i >= 0 ? part.substring(0, i + 1) : "") + st(i >= 0 ? part.substring(i + 1) : part, localDir, top));
            }
            return String.join(",", out);
        }
        String b = VoxelGrid.baseId(block);
        if (!b.endsWith("_stairs")) b = BuildPlanner.stairsOf(b);
        return b + "[facing=" + f.dir(localDir) + ",half=" + (top ? "top" : "bottom") + "]";
    }

    private String slabOf(String block, boolean top) {
        if (block.contains(",") || block.contains("%")) {
            List<String> out = new ArrayList<>();
            for (String part : VoxelGrid.splitPattern(block)) {
                int i = part.indexOf('%');
                out.add((i >= 0 ? part.substring(0, i + 1) : "") + slabOf(i >= 0 ? part.substring(i + 1) : part, top));
            }
            return String.join(",", out);
        }
        String b = VoxelGrid.baseId(block);
        String s = b.endsWith("_stairs") ? b.substring(0, b.length() - 7) + "_slab"
            : b.endsWith("_planks") ? b.substring(0, b.length() - 7) + "_slab"
            : b.endsWith("bricks") || b.endsWith("tiles") ? b.substring(0, b.length() - 1) + "_slab"
            : b.endsWith("_slab") ? b : b + "_slab";
        if (s.equals("calcite_slab")) s = "polished_diorite_slab";
        if (s.equals("cobblestone_slab") || s.contains("cracked_") || s.contains("chiseled_")) s = s.replace("cracked_", "").replace("chiseled_", "");
        return s + "[type=" + (top ? "top" : "bottom") + "]";
    }

    private String solid(String block) { return BuildPlanner.solidOf(block); }

    private String ax(String b, char localAxis) {
        String base0 = VoxelGrid.baseId(b);
        if (!(base0.endsWith("_log") || base0.endsWith("_wood") || base0.endsWith("_stem") || base0.endsWith("_hyphae") || base0.equals("bamboo_block"))) return b;
        return base0 + "[axis=" + f.axis(localAxis) + "]";
    }

    /** Open trapdoor hanging on the block on its `towardDir` side. */
    private String hang(String trapdoor, String towardDir) {
        String away = switch (towardDir) { case "+x" -> "-x"; case "-x" -> "+x"; case "+z" -> "-z"; default -> "+z"; };
        return trapdoor + "[facing=" + f.dir(away) + ",open=true,half=bottom]";
    }

    private void doorAt(int x, int y, int z, String inward) {
        String fa = f.dir(inward);
        cv.put(x, y, z, door + "[facing=" + fa + ",half=lower,hinge=left]");
        cv.put(x, y + 1, z, door + "[facing=" + fa + ",half=upper,hinge=left]");
    }

    private boolean clear(int ax1, int ax2, int az1, int az2) {
        for (int[] b : avoid) if (b[0] <= ax2 + 1 && b[1] >= ax1 - 1 && b[2] <= az2 + 1 && b[3] >= az1 - 1) return false;
        return true;
    }

    private int groundAt(int ax1, int az1, int ax2, int az2, boolean low) {
        if (ground == null) return low ? -1 : -1;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = Math.min(ax1, ax2); x <= Math.max(ax1, ax2); x += Math.max(1, Math.abs(ax2 - ax1) / 12)) {
            for (int z = Math.min(az1, az2); z <= Math.max(az1, az2); z += Math.max(1, Math.abs(az2 - az1) / 12)) {
                int y = ground.surfaceY(f.wx(x, z), f.wz(x, z));
                if (y == Integer.MIN_VALUE) continue;
                lo = Math.min(lo, y); hi = Math.max(hi, y);
            }
        }
        if (lo == Integer.MAX_VALUE) return -1;
        return (low ? lo : hi) - f.anchor.getY();
    }

    // ------------------------------------------------------------------ the house

    private void build() {
        w = clamp(num("width", 11), 7, 31);
        d = clamp(num("depth", 9), 7, 31);
        x1 = num("x", 0) - (w - 1) / 2; x2 = x1 + w - 1; cx = x1 + (w - 1) / 2;
        z1 = num("z", 2); z2 = z1 + d - 1;
        F = clamp(num("floors", 2), 1, 4);
        int h = clamp(num("floorHeight", 4), 3, 8);
        int gHi = groundAt(x1, z1, x2, z2, false), gLo = groundAt(x1, z1, x2, z2, true);
        by = c.has("_baseY") ? num("_baseY", 0) : gHi + 1 + num("y", 0);
        int fb = Math.max(by - 16, Math.min(by - 1, gLo));
        String arch = str("archetype", "townhouse");

        // materials
        wall = str("wall", "calcite");
        String lw = str("lowerWall", "none");
        stone = lw.equals("none") ? str("foundation", "stone_bricks") : lw;
        base = str("foundation", "cobblestone");
        log = str("frame", "stripped_spruce_log");
        if (log.equals("none")) log = "stripped_spruce_log";
        wood = species(log);
        if (wood == null) wood = species(str("floor", "spruce_planks"));
        if (wood == null) wood = "spruce";
        planks = wood + "_planks"; stairs = wood + "_stairs"; slab = wood + "_slab"; fence = wood + "_fence";
        trap = wood + "_trapdoor"; door = str("door", wood + "_door");
        if (!door.endsWith("_door")) door = wood + "_door";
        glass = str("window", "glass_pane");
        lit = "shroomlight";
        grad = new ArrayList<>();
        for (String p : str("roofGradient", "dark_oak_stairs").split("\\|")) if (!p.isBlank()) grad.add(p.trim());
        if (grad.isEmpty()) grad.add("dark_oak_stairs");
        trim = str("roofTrim", grad.get(0));
        chim = str("chimneyBlock", stone);

        // storeys: a tall ground storey, then upper storeys
        stoneGround = F >= 2 && !lw.equals("none");
        int H0 = F >= 2 ? clamp(h + 2, 5, 8) : clamp(h + 1, 5, 7), H1 = clamp(h + 1, 5, 7);
        sAt = new int[F]; hAt = new int[F];
        int s = by;
        for (int i = 0; i < F; i++) { sAt[i] = s; hAt[i] = i == 0 ? H0 : H1; s += hAt[i]; }
        top = s - 1;
        ridgeZ = w <= d + 2 || arch.equals("townhouse");
        o = Math.max(w, d) >= 13 ? 4 : w >= 9 ? 3 : 2;
        if (!clear(x1 - o - 1, x2 + o + 1, z1 - 4, z2 + 4)) o = 2;

        // what this house gets (from the style's choices and the seed)
        String plan = str("plan", "simple").toLowerCase(Locale.ROOT);
        boolean wantTower = plan.contains("tower") || plan.contains("turret");
        boolean wantAnnex = plan.equals("l") || plan.contains("l+") || plan.equals("stepped") || plan.equals("cross")
            || plan.equals("u") || plan.equals("t") || bool("leanTo", false) || (wantTower && w >= 9);
        if (arch.equals("farmhouse") && !plan.equals("simple")) wantAnnex = true;
        int annexSide = rnd(1, 2) == 0 ? -1 : 1;            // -1 left, +1 right
        boolean deck = (bool("frontDeck", false) || bool("frontBalcony", false)) && F >= 2;
        boolean porch = bool("frontPorch", false) && !deck;
        boolean oriel = bool("oriel", false) && F >= 2 && !deck;

        // carve the rooms first (clears terrain inside), then foundations
        cv.fill(x1 + 1, by, z1 + 1, x2 - 1, top + 12, z2 - 1, "air");
        cv.fill(x1, fb, z1, x2, by - 1, z2, base);

        int[] annex = wantAnnex ? annexRect(annexSide) : null;
        if (annex == null && wantAnnex) { annexSide = -annexSide; annex = annexRect(annexSide); }
        int[] tower = wantTower ? towerRect(annexSide, annex) : null;

        groundStorey(deck);
        for (int i = (stoneGround ? 1 : 0); i < F; i++) upperStorey(i);
        interior();
        mainRoof(annex != null ? annexSide : 0);
        frontGable();
        if (d >= 11 && ridgeZ) dormer(annex != null ? annexSide : (rnd(3, 2) == 0 ? -1 : 1));
        if (rnd(4, 2) == 0) roofChimney();
        if (deck) deck(bool("frontBalcony", false) && !bool("frontDeck", false) ? 2 : (hAt[0] >= 7 ? 4 : 3));
        if (porch) porch();
        if (oriel) oriel();
        if (annex != null) annex(annex, annexSide, tower);
        else sideChimney();
        if (tower != null) tower(tower, annexSide, annex);
        yard(deck);
    }

    // ------------------------------------------------------------------ storeys

    private void groundStorey(boolean deck) {
        int s = sAt[0], t = s + hAt[0] - 1;
        if (!stoneGround) return;
        cv.walls(x1, s, z1, x2, t, z2, stone);
        String baseC = str("baseCourse", null), band = str("bandCourse", null);
        if (baseC != null && !baseC.equals("none")) cv.walls(x1, s, z1, x2, s, z2, baseC);
        if (band != null && !band.equals("none")) cv.walls(x1, t, z1, x2, t, z2, band);
        String q = str("cornerBlock", null);
        if (q != null && !q.equals("none")) {
            int[][] k = {{x1, z1, 1, 1}, {x2, z1, -1, 1}, {x1, z2, 1, -1}, {x2, z2, -1, -1}};
            for (int[] a : k) for (int y = s; y < t; y++) {
                cv.put(a[0], y, a[1], q);
                if ((y - s) % 2 == 0) cv.put(a[0] + a[2], y, a[1], q); else cv.put(a[0], y, a[1] + a[3], q);
            }
        }
        // front: framed door in the middle, recessed panels either side
        cv.fill(cx - 1, s, z1, cx + 1, s + 2, z1, ax(log, 'y'));
        cv.put(cx, s + 2, z1, ax(log, 'x'));
        cv.put(cx, s, z1 + 1, "air"); cv.put(cx, s + 1, z1 + 1, "air");
        doorAt(cx, s, z1, "+z");
        int pw = w >= 11 ? 2 : 1, ph = Math.min(hAt[0] - 3, 5);
        for (int sgn : new int[]{-1, 1}) {
            int a = cx + sgn * (w >= 11 ? 3 : 2), b = a + sgn * (pw - 1);
            int lo = Math.min(a, b), hi = Math.max(a, b);
            if (lo <= x1 + 1 || hi >= x2 - 1) continue;
            cv.fill(lo, s + 1, z1, hi, s + ph, z1, "air");
            cv.fill(lo, s, z1 + 1, hi, s + ph, z1 + 1, stone);
            cv.fill(lo, s + ph + 1, z1, hi, s + ph + 1, z1, st(first(stone), "+z", true));
            if (!deck) cv.fill(lo, s + 2, z1 + 1, hi, s + 3, z1 + 1, glass);
        }
        // sides and back: small windows
        for (int z = z1 + 2; z <= z2 - 2; z += 4) { cv.fill(x1, s + 2, z, x1, s + 3, z, glass); cv.fill(x2, s + 2, z, x2, s + 3, z, glass); }
        for (int x = x1 + 2; x <= x2 - 2; x += 4) cv.fill(x, s + 2, z2, x, s + 3, z2, glass);
    }

    private void upperStorey(int i) {
        int s = sAt[i], t = s + hAt[i] - 1;
        // timber-framed sides and back
        cv.walls(x1, s, z1, x2, t, z2, wall);
        for (int z = z1; z <= z2; z++) {
            cv.put(x1, s, z, ax(log, 'z')); cv.put(x2, s, z, ax(log, 'z'));
            cv.put(x1, t, z, ax(log, 'z')); cv.put(x2, t, z, ax(log, 'z'));
        }
        for (int x = x1; x <= x2; x++) { cv.put(x, s, z2, ax(log, 'x')); cv.put(x, t, z2, ax(log, 'x')); }
        List<Integer> pz = posts(z1, z2), px = posts(x1, x2);
        for (int z : pz) { cv.fill(x1, s, z, x1, t, z, ax(log, 'y')); cv.fill(x2, s, z, x2, t, z, ax(log, 'y')); }
        for (int x : px) cv.fill(x, s, z2, x, t, z2, ax(log, 'y'));
        int wy1 = s + 2, wy2 = Math.min(s + 3, t - 2);
        for (int k = 0; k + 1 < pz.size(); k++) {
            if ((k + i) % 2 == 1) continue;
            int z = (pz.get(k) + pz.get(k + 1)) / 2;
            for (int[] side : new int[][]{{x1, -1}, {x2, 1}}) {
                cv.fill(side[0], wy1, z, side[0], wy2, z, k == 0 && i == F - 1 ? lit : glass);
                String toward = side[1] < 0 ? "+x" : "-x";
                for (int dz : new int[]{-1, 1}) cv.fill(side[0] + side[1], wy1, z + dz, side[0] + side[1], wy2, z + dz, hang(trap, toward));
            }
        }
        for (int k = 0; k + 1 < px.size(); k++) {
            int x = (px.get(k) + px.get(k + 1)) / 2;
            cv.fill(x, wy1, z2, x, wy2, z2, glass);
        }
        // front: columns proud of recessed infill, stepped corbels, lit centre window
        cv.fill(x1, s, z1, x2, t, z1, wall);
        List<Integer> cols = new ArrayList<>(List.of(x1));
        if (w >= 9) { cols.add(cx - 2); cols.add(cx + 2); }
        cols.add(x2);
        for (int col : cols) {
            cv.fill(col, s, z1 - 1, col, t - 1, z1 - 1, ax(log, 'y'));
            cv.put(col, s, z1, ax(log, 'y')); cv.put(col, t, z1, ax(log, 'y'));
            if (col != x1 && col != x2) cv.put(col, s, z1 - 2, st(stairs, "+z", false));
        }
        for (int k = 0; k + 1 < cols.size(); k++) {
            int a = cols.get(k) + 1, b = cols.get(k + 1) - 1;
            if (a > b) continue;
            for (int x = a; x <= b; x++) cv.put(x, t, z1 - 1, planks);
            cv.put(a, t - 1, z1 - 1, planks); cv.put(b, t - 1, z1 - 1, planks);
            if (b - a >= 2) for (int x = a + 1; x < b; x++) cv.put(x, t - 1, z1 - 1, slabOf(planks, true));
            if (b > a) { cv.put(a, t - 2, z1 - 1, st(stairs, "-x", true)); cv.put(b, t - 2, z1 - 1, st(stairs, "+x", true)); }
            else cv.put(a, t - 2, z1 - 1, slabOf(planks, true));
        }
        if (w >= 9) {
            int wt = Math.min(s + 4, t - 3);
            cv.fill(cx - 1, s + 1, z1, cx + 1, wt, z1, wood.equals("spruce") ? "dark_oak_planks" : "spruce_planks");
            for (int y = s + 2; y < wt; y++) { cv.put(cx - 1, y, z1, lit); cv.put(cx + 1, y, z1, lit); }
            for (int sgn : new int[]{-1, 1}) {
                int bx = cx + sgn * ((w >= 13) ? 4 : 3);
                if (bx > x1 && bx < x2 && !cols.contains(bx)) cv.fill(bx, s + 2, z1, bx, Math.min(s + 4, t - 3), z1, glass);
            }
        } else {
            cv.fill(cx, s + 2, z1, cx, s + 3, z1, lit);
        }
        // the floor
        cv.fill(x1 + 1, s - 1, z1 + 1, x2 - 1, s - 1, z2 - 1, planks);
    }

    private List<Integer> posts(int a, int b) {
        List<Integer> out = new ArrayList<>();
        int n = Math.max(1, Math.round((b - a) / 4f));
        for (int k = 0; k <= n; k++) out.add(a + Math.round(k * (b - a) / (float) n));
        return out;
    }

    private void interior() {
        // a ladder up the back corner, a lantern and a little furniture on every floor
        int lx = x1 + 1, lz = z2 - 1;
        cv.fill(lx, by, lz, lx, top, lz, "ladder[facing=" + f.dir("-z") + "]");
        for (int i = 0; i < F; i++) {
            int s = sAt[i], t = s + hAt[i] - 1;
            cv.put((x1 + x2) / 2, t - 1, (z1 + z2) / 2 + 1, "lantern[hanging=true]");
            int tx = x2 - 2, tz = z1 + 2;
            if (i == 0) {
                cv.put(tx, s, tz, fence); cv.put(tx, s + 1, tz, wood + "_pressure_plate");
                cv.put(tx - 1, s, tz, st(stairs, "+x", false)); cv.put(tx + 1, s, tz, st(stairs, "-x", false));
                cv.put(x2 - 1, s, z2 - 1, "crafting_table"); cv.put(x2 - 2, s, z2 - 1, "furnace[facing=" + f.dir("-z") + "]");
                cv.put(x2 - 3, s, z2 - 1, "barrel[facing=up]");
            } else {
                String fd = f.dir("+z");
                cv.put(x2 - 1, s, z2 - 2, "red_bed[part=foot,facing=" + fd + "]");
                cv.put(x2 - 1, s, z2 - 1, "red_bed[part=head,facing=" + fd + "]");
                cv.put(x2 - 2, s, z2 - 1, "chest[facing=" + f.dir("-z") + "]");
                cv.put(x1 + 2, s, z2 - 1, "bookshelf"); cv.put(x1 + 3, s, z2 - 1, "bookshelf");
            }
        }
        cv.fill(x1 + 1, top + 1, z1 + 1, x2 - 1, top + 1, z2 - 1, planks); // attic floor
        cv.put(lx, top + 1, lz, "ladder[facing=" + f.dir("-z") + "]");
    }

    // ------------------------------------------------------------------ roof

    /** Row heights from the eave in: shallow (one up per row) to just past the wall, then two up per row. */
    private int[] profile(int rows, int overhang, int y0) {
        int[] ys = new int[rows];
        int y = y0;
        for (int i = 0; i < rows; i++) {
            if (i > 0) y += i <= overhang + 1 ? 1 : 2;
            ys[i] = y;
        }
        return ys;
    }

    private String band(int i, int n) {
        String g = grad.get(Math.min(grad.size() - 1, i * grad.size() / Math.max(1, n)));
        return g;
    }

    private void mainRoof(int sweepSide) {
        int half = ridgeZ ? (w - 1) / 2 : (d - 1) / 2;
        roofRows = o + half + 1;
        int y0 = top + 4 - o;
        roofRowY = profile(roofRows, o, y0);
        if (ridgeZ) { roofLowX = x1 - o; roofHighX = x2 + o; zf = z1 - 3; zb = z2 + 3; }
        else { roofLowX = z1 - o; roofHighX = z2 + o; zf = x1 - 3; zb = x2 + 3; }
        // knee and gable walls under the roof
        for (int i = 0; i < roofRows; i++) {
            int a = roofLowX + i, b = roofHighX - i, y = roofRowY[i];
            for (int u : new int[]{a, b}) {
                if (ridgeZ ? (u < x1 || u > x2) : (u < z1 || u > z2)) continue;
                for (int yy = top + 1; yy <= y - 2; yy++) {
                    if (ridgeZ) { cv.put(u, yy, z1, wall); cv.put(u, yy, z2, wall); }
                    else { cv.put(x1, yy, u, wall); cv.put(x2, yy, u, wall); }
                }
            }
            if (ridgeZ && (a == x1 || b == x2)) for (int z = z1; z <= z2; z++) for (int yy = top + 1; yy <= y - 2; yy++) { cv.put(x1, yy, z, wall); cv.put(x2, yy, z, wall); }
            if (!ridgeZ && (a == z1 || b == z2)) for (int x = x1; x <= x2; x++) for (int yy = top + 1; yy <= y - 2; yy++) { cv.put(x, yy, z1, wall); cv.put(x, yy, z2, wall); }
        }
        // the rows
        for (int i = 0; i < roofRows; i++) {
            int a = roofLowX + i, b = roofHighX - i, y = roofRowY[i];
            if (a > b) break;
            String bnd = i == 0 ? trim : band(i, roofRows);
            String so = solid(bnd);
            for (int v = zf; v <= zb; v++) {
                if (a == b || a + 1 == b) {
                    for (int u : new int[]{a, b}) { put2(u, y, v, so); put2(u, y - 1, v, so); put2(u, y + 1, v, slabOf(bnd, false)); }
                    continue;
                }
                put2(a, y, v, st(bnd, ridgeZ ? "+x" : "+z", false));
                put2(b, y, v, st(bnd, ridgeZ ? "-x" : "-z", false));
                int prev = i > 0 ? roofRowY[i - 1] : y;
                for (int yy = prev + 1; yy < y; yy++) { put2(a, yy, v, so); put2(b, yy, v, so); }
                if (i >= 1) { put2(a, y - 1, v, so); put2(b, y - 1, v, so); }
                else { put2(a, y - 1, v, slabOf(bnd, true)); put2(b, y - 1, v, slabOf(bnd, true)); }
            }
            if (a == b || a + 1 == b) { peakY = y; break; }
        }
        // ragged front verge that sweeps forward at the bottom; ragged back edge
        for (int i = 0; i < roofRows; i++) {
            int a = roofLowX + i, b = roofHighX - i, y = roofRowY[i];
            if (a >= b - 1) break;
            String bnd = i == 0 ? trim : band(i, roofRows);
            if (i % 2 == 1 || i <= 2) {
                put2(a, y, zf - 1, st(bnd, ridgeZ ? "+x" : "+z", false)); put2(b, y, zf - 1, st(bnd, ridgeZ ? "-x" : "-z", false));
                if (i >= 1) { put2(a, y - 1, zf - 1, solid(bnd)); put2(b, y - 1, zf - 1, solid(bnd)); }
            }
            if (i <= 1) {
                put2(a, y, zf - 2, st(bnd, ridgeZ ? "+x" : "+z", false)); put2(b, y, zf - 2, st(bnd, ridgeZ ? "-x" : "-z", false));
                put2(a - 1, y, zf - 1, st(bnd, ridgeZ ? "+x" : "+z", false)); put2(b + 1, y, zf - 1, st(bnd, ridgeZ ? "-x" : "-z", false));
            }
            if (i % 2 == 0 && i >= 4) { put2(a, y, zb + 1, st(bnd, ridgeZ ? "+x" : "+z", false)); put2(b, y, zb + 1, st(bnd, ridgeZ ? "-x" : "-z", false)); }
        }
        // the long sweep down over the annex side
        if (sweepSide != 0 && ridgeZ) {
            int y0s = roofRowY[0];
            int[] ys = {y0s - 3, y0s - 2, y0s - 1, y0s - 1};
            for (int k = 0; k < ys.length; k++) {
                int u = sweepSide < 0 ? roofLowX - 4 + k : roofHighX + 4 - k;
                for (int v = zf + 2; v <= zb - 2; v++) {
                    put2(u, ys[k], v, st(trim, sweepSide < 0 ? "+x" : "-x", false));
                    put2(u, ys[k] - 1, v, k == 0 ? slabOf(trim, true) : solid(trim));
                }
            }
        }
        // brackets under the eaves at every post
        if (ridgeZ) {
            for (int z : posts(z1, z2)) {
                cv.put(x1 - 1, top, z, st(stairs, "+x", true)); cv.put(x2 + 1, top, z, st(stairs, "-x", true));
            }
        } else {
            for (int x : posts(x1, x2)) cv.put(x, top, z2 + 1, st(stairs, "-z", true));
        }
    }

    /** Puts with u along the roof's cross-section and v along its ridge. */
    private void put2(int u, int y, int v, String b) {
        if (ridgeZ) cv.put(u, y, v, b); else cv.put(v, y, u, b);
    }

    private int roofyAt(int x) { // roof height over the front wall line at x (ridge-z houses)
        int i = Math.min(x - roofLowX, roofHighX - x);
        if (i < 0) return -999;
        return roofRowY[Math.min(i, roofRowY.length - 1)];
    }

    private void frontGable() {
        if (!ridgeZ) { crossGable(); return; }
        gableFace(x1, x2, z1, this::roofyAt, zf);
    }

    /** The composed gable face: heavy beam with end blocks, attic balcony and door, an oak band, a lit window, an ornament. */
    private void gableFace(int gx1, int gx2, int gz, java.util.function.IntUnaryOperator roofy, int verge) {
        int gcx = (gx1 + gx2) / 2;
        // the beam across the top of the storeys
        for (int x = gx1 - 1; x <= gx2 + 1; x++) { cv.put(x, top, gz - 1, ax(log, 'x')); cv.put(x, top + 1, gz - 1, planks); cv.put(x, top + 1, gz, planks); }
        for (int x : new int[]{gx1 - 1, gcx - 2, gcx + 2, gx2 + 1}) {
            if (x < gx1 - 1 || x > gx2 + 1) continue;
            cv.put(x, top, gz - 1, VoxelGrid.baseId(log).endsWith("_log") ? VoxelGrid.baseId(log).replace("_log", "_wood") : log);
            cv.put(x, top, gz - 2, st(stairs, "+z", true));
        }
        cv.put(gx1 - 1, top - 1, gz - 1, st(stairs, "+z", true)); cv.put(gx2 + 1, top - 1, gz - 1, st(stairs, "+z", true));
        // gable wall with two posts
        for (int x = gx1; x <= gx2; x++) for (int y = top + 1; y <= roofy.applyAsInt(x) - 2; y++) cv.put(x, y, gz, wall);
        for (int x : new int[]{gx1 + 2, gx2 - 2}) if (x > gx1 && x < gx2) for (int y = top + 2; y <= roofy.applyAsInt(x) - 2; y++) cv.put(x, y, gz, ax(log, 'y'));
        int room = roofy.applyAsInt(gcx) - 2 - (top + 1);
        // attic balcony and door
        if (room >= 5) {
            int hb = Math.min(3, (gx2 - gx1) / 2 - 1);
            for (int x = gcx - hb; x <= gcx + hb; x++) {
                cv.put(x, top + 1, gz - 2, slabOf(planks, true));
                cv.put(x, top + 2, gz - 2, fence);
                if (x != gcx - hb && x != gcx + hb) cv.put(x, top, gz - 2, st(stairs, "+z", true));
            }
            cv.put(gcx - hb, top + 2, gz - 1, fence); cv.put(gcx + hb, top + 2, gz - 1, fence);
            cv.fill(gcx - 1, top + 2, gz, gcx - 1, top + 4, gz, ax(log, 'y')); cv.fill(gcx + 1, top + 2, gz, gcx + 1, top + 4, gz, ax(log, 'y'));
            cv.put(gcx, top + 4, gz, ax(log, 'x'));
            doorAt(gcx, top + 2, gz, "+z");
            cv.put(gcx, top + 2, gz + 1, "air"); cv.put(gcx, top + 3, gz + 1, "air");
            if (gcx - 2 > gx1) cv.put(gcx - 2, top + 3, gz, glass);
            if (gcx + 2 < gx2) cv.put(gcx + 2, top + 3, gz, glass);
        }
        // oak band and the lit window above it
        int bandY = top + (room >= 9 ? 6 : 4);
        for (int x = gx1 + 1; x < gx2; x++) {
            if (roofy.applyAsInt(x) - 1 > bandY) { cv.put(x, bandY, gz - 1, ax(log, 'x')); cv.put(x, bandY, gz, ax(log, 'x')); }
            if (roofy.applyAsInt(x) - 1 > bandY + 1) cv.put(x, bandY + 1, gz - 1, slabOf(planks, false));
        }
        int wy = bandY + 2;
        if (roofy.applyAsInt(gcx) - 2 >= wy + 2) {
            cv.fill(gcx - 1, wy, gz, gcx + 1, wy + 2, gz, wood.equals("spruce") ? "dark_oak_planks" : "spruce_planks");
            cv.put(gcx, wy, gz, lit); cv.put(gcx, wy + 1, gz, lit); cv.put(gcx, wy + 2, gz, lit);
            cv.put(gcx - 1, wy + 1, gz, lit); cv.put(gcx + 1, wy + 1, gz, lit);
            if (roofy.applyAsInt(gcx) - 2 >= wy + 3) cv.put(gcx, wy + 3, gz, planks);
        } else if (roofy.applyAsInt(gcx) - 2 >= wy) {
            cv.put(gcx, wy, gz, lit);
        }
        // ornament at the peak: a curled beam, a finial post, or nothing
        int pk = roofy.applyAsInt(gcx);
        int orn = rnd(9, 10);
        if (orn < 5) {
            cv.put(gcx, pk - 1, verge - 1, st(stairs, "+z", true));
            cv.put(gcx, pk, verge - 1, ax(log, 'z')); cv.put(gcx, pk, verge - 2, ax(log, 'z'));
            cv.put(gcx, pk + 1, verge - 3, ax(log, 'z')); cv.put(gcx, pk + 1, verge - 4, st(stairs, "-z", false));
            cv.put(gcx, pk, verge - 3, st(stairs, "-z", true)); cv.put(gcx, pk + 2, verge - 4, slabOf(planks, false));
        } else if (orn < 8) {
            cv.put(gcx, pk + 1, verge, ax(log, 'y')); cv.put(gcx, pk + 2, verge, fence); cv.put(gcx, pk + 3, verge, fence);
        }
    }

    /** Ridge-along-x houses: a big central gable standing out of the front slope, carrying the composed face. */
    private void crossGable() {
        int hw = clamp((w - 1) / 2 - 2, 3, (d - 1) / 2);
        int gx1 = cx - hw, gx2 = cx + hw;
        int rows = o + hw + 1;
        int[] ys = profile(rows, o, top + 4 - o);
        int lowX = gx1 - o, highX = gx2 + o;
        int back = (z1 + z2) / 2;
        java.util.function.IntUnaryOperator ry = x -> {
            int i = Math.min(x - lowX, highX - x);
            return i < 0 ? -999 : ys[Math.min(i, ys.length - 1)];
        };
        // cut the main roof's front slope away where the gable stands
        for (int x = lowX; x <= highX; x++) {
            int yTop = ry.applyAsInt(x);
            for (int z = z1 - 4; z <= back; z++) for (int y = top + 1; y < yTop; y++) {
                String b = cv.get(x, y, z);
                if (b != null && !b.contains("ladder") && !b.contains("planks") || (b != null && y > top + 1 && b.contains("planks"))) cv.remove(x, y, z);
            }
        }
        for (int i = 0; i < rows; i++) {
            int a = lowX + i, b = highX - i, y = ys[i];
            if (a > b) break;
            String bnd = i == 0 ? trim : band(i, rows);
            for (int z = z1 - 3; z <= back; z++) {
                if (a == b) { cv.put(a, y, z, solid(bnd)); cv.put(a, y - 1, z, solid(bnd)); cv.put(a, y + 1, z, slabOf(bnd, false)); continue; }
                cv.put(a, y, z, st(bnd, "+x", false)); cv.put(b, y, z, st(bnd, "-x", false));
                int prev = i > 0 ? ys[i - 1] : y;
                for (int yy = prev + 1; yy < y; yy++) { cv.put(a, yy, z, solid(bnd)); cv.put(b, yy, z, solid(bnd)); }
                if (i >= 1) { cv.put(a, y - 1, z, solid(bnd)); cv.put(b, y - 1, z, solid(bnd)); }
            }
            if (a == b) break;
            if (i % 2 == 1 || i <= 2) { cv.put(a, y, z1 - 4, st(bnd, "+x", false)); cv.put(b, y, z1 - 4, st(bnd, "-x", false)); }
        }
        // clear the attic inside it
        for (int x = gx1; x <= gx2; x++) for (int y = top + 2; y <= ry.applyAsInt(x) - 2; y++) for (int z = z1 + 1; z < back; z++) cv.put(x, y, z, "air");
        gableFace(gx1, gx2, z1, ry, z1 - 3);
    }

    private void dormer(int side) {
        int dz = z1 + 3, ux = side < 0 ? x1 : x2, sg = side < 0 ? 1 : -1; // sg points into the house
        int a1 = ux - sg * 3, a2 = ux + sg * 1;
        int lo = Math.min(a1, a2), hi = Math.max(a1, a2), mid = (lo + hi) / 2;
        for (int x = Math.min(ux - sg * 4, ux + sg * 2); x <= Math.max(ux - sg * 4, ux + sg * 2); x++)
            for (int z = dz; z <= dz + 7; z++) for (int y = top + 2; y <= top + 9; y++) {
                String b = cv.get(x, y, z);
                if (b != null && !b.contains("ladder") && (x - ux) * sg < 2) cv.remove(x, y, z);
            }
        int b0 = top + 1;
        cv.fill(lo, b0, dz, hi, b0 + 4, dz, wall);
        cv.fill(lo, b0, dz, lo, b0 + 4, dz, ax(log, 'y')); cv.fill(hi, b0, dz, hi, b0 + 4, dz, ax(log, 'y'));
        for (int x = lo; x <= hi; x++) cv.put(x, b0, dz, ax(log, 'x'));
        cv.fill(mid, b0 + 2, dz, mid, b0 + 3, dz, lit); cv.put(mid - 1, b0 + 2, dz, glass); cv.put(mid + 1, b0 + 2, dz, glass);
        for (int z = dz; z <= dz + 7; z++) for (int y = b0; y <= b0 + 4; y++) { cv.put(lo, y, z, wall); cv.put(hi, y, z, wall); }
        cv.fill(lo + 1, b0 + 5, dz, hi - 1, b0 + 5, dz, wall); cv.put(mid, b0 + 6, dz, wall);
        int[][] rows = {{lo - 1, b0 + 4}, {hi + 1, b0 + 4}, {lo, b0 + 5}, {hi, b0 + 5}, {lo + 1, b0 + 6}, {hi - 1, b0 + 6}};
        for (int k = 0; k < rows.length; k++) {
            String dir = k % 2 == 0 ? "+x" : "-x";
            for (int z = dz - 1; z <= dz + 8; z++) {
                cv.put(rows[k][0], rows[k][1], z, st(band(k / 2, 4), dir, false));
                if (k >= 2) cv.put(rows[k][0], rows[k][1] - 1, z, solid(band(k / 2, 4)));
            }
        }
        for (int z = dz - 1; z <= dz + 8; z++) { cv.put(mid, b0 + 7, z, solid(trim)); cv.put(mid, b0 + 8, z, slabOf(trim, false)); cv.put(mid, b0 + 6, z, solid(trim)); }
        for (int x = lo; x <= hi; x++) { cv.put(x, b0, dz - 1, slabOf(planks, true)); cv.put(x, b0 + 1, dz - 1, fence); }
    }

    private void roofChimney() {
        if (!ridgeZ) return;
        int x = cx - 2, z = z1 + (d * 3) / 5;
        String cal = "calcite".equals(first(wall)) || "white_terracotta".equals(first(wall)) ? first(chim) : "calcite";
        cv.fill(x, top + 1, z, x + 1, peakY + 2, z + 1, cal.equals("calcite") ? "60%calcite,40%diorite" : chim);
        chimneyCap(x, z, peakY + 3, 2, cal.equals("calcite") ? "calcite" : first(chim));
    }

    private void chimneyCap(int x, int z, int y, int size, String capB) {
        cv.fill(x - 1, y, z - 1, x + size, y, z + size, capB);
        cv.put(x - 1, y + 1, z - 1, fence); cv.put(x + size, y + 1, z - 1, fence);
        cv.put(x - 1, y + 1, z + size, fence); cv.put(x + size, y + 1, z + size, fence);
        cv.put(x, y + 1, z, "campfire[lit=true]");
        cv.fill(x - 1, y + 2, z - 1, x + size, y + 2, z + size, slabOf(capB, false));
        cv.fill(x, y + 2, z, x + size - 1, y + 2, z + size - 1, solid(capB));
        cv.fill(x, y + 3, z, x + size - 1, y + 3, z + size - 1, slabOf(capB, false));
    }

    private void sideChimney() {
        if (!bool("chimney", true)) return;
        int sgn = rnd(5, 2) == 0 ? -1 : 1;
        int x = sgn < 0 ? x1 - 2 : x2 + 1, z = z2 - 3;
        if (!clear(Math.min(x, x + 1), Math.max(x, x + 1), z, z + 1)) return;
        int gy = groundAt(x, z, x + 1, z + 1, true);
        cv.fill(x, Math.min(by - 1, gy), z, x + 1, peakY + 1, z + 1, chim);
        chimneyCap(x, z, peakY + 2, 2, first(chim));
    }

    // ------------------------------------------------------------------ deck, porch, oriel

    private void deck(int depth) {
        int y = sAt[1] - 1, dx1 = x1 - 2, dx2 = x2 + 2;
        if (!clear(dx1, dx2, z1 - depth - 1, z1 - 1)) { dx1 = x1; dx2 = x2; }
        cv.fill(dx1, y, z1 - depth, dx2, y, z1 - 1, planks);
        for (int x = dx1; x <= dx2; x++) cv.put(x, y, z1 - depth - 1, hang(trap, "+z"));
        for (int z = z1 - depth; z <= z1 - 1; z++) { cv.put(dx1 - 1, y, z, hang(trap, "+x")); cv.put(dx2 + 1, y, z, hang(trap, "-x")); }
        for (int x = dx1 + 1; x < dx2; x += 2) {
            cv.fill(x, y - 1, z1 - depth, x, y - 1, z1 - 1, ax(log, 'z'));
            cv.put(x, y - 1, z1 - depth - 1, st(stairs, "+z", true));
            cv.put(x, y - 2, z1 - depth, st(stairs, "+z", true));
        }
        for (int px : new int[]{x1, x2}) {                                   // big posts to the ground
            int pz = z1 - depth + 1;
            int gy = groundAt(px, pz, px, pz, true);
            cv.put(px, Math.min(by - 1, gy), pz, first(stone));
            cv.fill(px, Math.min(by - 1, gy) + 1, pz, px, y - 1, pz, ax(log, 'y'));
            cv.put(px - 1, y - 1, pz, st(stairs, "+x", true)); cv.put(px + 1, y - 1, pz, st(stairs, "-x", true));
            cv.put(px, y - 1, pz - 1, st(stairs, "+z", true));
        }
        for (int x = dx1; x <= dx2; x++) if (x <= x1 + 1 || x >= x2 - 1 || depth <= 2) cv.put(x, y + 1, z1 - depth, fence);
        for (int z = z1 - depth; z <= z1 - 1; z++) { cv.put(dx1, y + 1, z, fence); cv.put(dx2, y + 1, z, fence); }
        cv.put(cx + 1, y + 1, z1 - 1, "decorated_pot"); cv.put(cx, y + 1, z1 - 1, "potted_fern");
        if (rnd(6, 10) < 6) for (int px : new int[]{x1, x2}) {          // ivy growing on the posts (vines need a block behind)
            if (rnd(px + 40, 3) == 0) continue;
            int pz = z1 - depth + 1, len = 2 + rnd(px, 3), sg = px == x1 ? -1 : 1;
            for (int yy = y - 1 - len; yy <= y - 2; yy++) {
                if (cv.get(px, yy, pz - 1) == null) cv.put(px, yy, pz - 1, "vine[" + f.dir("+z") + "=true]");
                if (yy >= y - len && cv.get(px + sg, yy, pz) == null) cv.put(px + sg, yy, pz, "vine[" + f.dir(sg < 0 ? "+x" : "-x") + "=true]");
            }
        }
    }

    private void porch() {
        int pd = 3, pw = 2, s = by;
        for (int x = cx - pw; x <= cx + pw; x++) for (int z = z1 - pd; z <= z1 - 1; z++) { cv.put(x, s - 1, z, planks); for (int y = s; y <= s + 3; y++) cv.put(x, y, z, "air"); }
        for (int px : new int[]{cx - pw, cx + pw}) cv.fill(px, s, z1 - pd, px, s + 2, z1 - pd, ax(log, 'y'));
        for (int x = cx - pw - 1; x <= cx + pw + 1; x++) {
            cv.put(x, s + 3, z1 - pd - 1, st(trim, "+z", false));
            cv.put(x, s + 3, z1 - pd, slabOf(trim, true));
            for (int z = z1 - pd + 1; z <= z1 - 1; z++) cv.put(x, s + 4, z, st(trim, "+z", false));
        }
        for (int z = z1 - pd; z <= z1 - 2; z++) { cv.put(cx - pw, s, z, fence); cv.put(cx + pw, s, z, fence); }
        cv.put(cx, s + 2, z1 - 2, "lantern[hanging=true]");
        cv.fill(cx - 1, s - 1, z1 - pd - 1, cx + 1, s - 1, z1 - pd - 1, st(stairs, "+z", false));
    }

    private void oriel() {
        int s = sAt[1], t = s + hAt[1] - 1;
        cv.fill(cx - 1, s + 1, z1 - 2, cx + 1, t - 2, z1 - 2, glass);
        cv.fill(cx - 2, s, z1 - 2, cx + 2, s, z1 - 2, planks);
        cv.fill(cx - 2, s + 1, z1 - 2, cx - 2, t - 2, z1 - 2, ax(log, 'y')); cv.fill(cx + 2, s + 1, z1 - 2, cx + 2, t - 2, z1 - 2, ax(log, 'y'));
        for (int x = cx - 1; x <= cx + 1; x++) for (int y = s + 1; y <= t - 2; y++) cv.put(x, y, z1 - 1, "air");
        cv.fill(cx - 2, s - 1, z1 - 2, cx + 2, s - 1, z1 - 2, st(stairs, "+z", true));
        cv.fill(cx - 2, t - 1, z1 - 2, cx + 2, t - 1, z1 - 2, st(trim, "+z", false));
    }

    // ------------------------------------------------------------------ annex, chimney, tower

    private int[] annexRect(int side) {
        int aw = clamp(w + 3, 9, 18), ad = clamp(Math.round(d * 0.5f), 5, 8);
        int ax1 = side < 0 ? x1 - aw : x2 + 1, ax2 = ax1 + aw - 1;
        int az1 = z1, az2 = z1 + ad - 1;
        int[] r = {ax1, ax2, az1, az2};
        return clear(ax1 - 3, ax2 + 3, az1 - 4, az2 + 3) ? r : null;
    }

    private void annex(int[] r, int side, int[] tower) {
        int ax1 = r[0], ax2 = r[1], az1 = r[2], az2 = r[3];
        int pb = by + 2;                                           // annex floor stands on a two-high stone platform
        int px1 = Math.min(ax1, tower != null ? tower[0] : ax1) - 2, px2 = Math.max(ax2, tower != null ? tower[1] : ax2) + 2;
        if (side < 0) px2 = x1 - 1; else px1 = x2 + 1;
        int pz2 = tower != null ? tower[3] + 2 : az2 + 2;
        int gy = groundAt(px1, az1 - 3, px2, pz2, true);
        cv.fill(px1, Math.min(by - 1, gy), az1 - 3, px2, by + 1, pz2, "70%stone_bricks,15%cracked_stone_bricks,15%mossy_stone_bricks");
        for (int x = px1; x <= px2; x++) cv.put(x, by, az1 - 4, st("stone_brick_stairs", "+z", false));
        int sideX = side < 0 ? px1 - 1 : px2 + 1;
        for (int z = az1 - 3; z <= pz2; z++) cv.put(sideX, by, z, st("stone_brick_stairs", side < 0 ? "+x" : "-x", false));
        int at = pb + 4;
        cv.fill(ax1 + 1, pb, az1 + 1, ax2 - 1, at + 6, az2 - 1, "air");
        cv.walls(ax1, pb, az1, ax2, at, az2, wall);
        for (int x = ax1; x <= ax2; x++) { cv.put(x, at, az1, ax(log, 'x')); cv.put(x, pb, az1, ax(log, 'x')); cv.put(x, at, az2, ax(log, 'x')); }
        for (int z = az1; z <= az2; z++) { cv.put(ax1, at, z, ax(log, 'z')); cv.put(ax2, at, z, ax(log, 'z')); }
        for (int x : posts(ax1, ax2)) { cv.fill(x, pb, az1, x, at, az1, ax(log, 'y')); cv.fill(x, pb, az2, x, at, az2, ax(log, 'y')); }
        cv.fill(ax1 + 1, pb - 1, az1 + 1, ax2 - 1, pb - 1, az2 - 1, planks);
        List<Integer> ps = posts(ax1, ax2);
        for (int k = 0; k + 1 < ps.size(); k++) {
            int x = (ps.get(k) + ps.get(k + 1)) / 2;
            cv.fill(x, pb + 1, az1, x, pb + 2, az1, k == 1 ? lit : glass);
            if (x + 1 < ps.get(k + 1)) cv.fill(x + 1, pb + 1, az1, x + 1, pb + 2, az1, glass);
        }
        int dx = side < 0 ? ax2 - 2 : ax1 + 2;
        doorAt(dx, pb, az1, "+z"); cv.put(dx, pb, az1 + 1, "air"); cv.put(dx, pb + 1, az1 + 1, "air");
        for (int x = Math.min(ax1, ax2) + 1; x <= Math.max(ax1, ax2) - 1; x++) if (Math.abs(x - dx) > 1 && Math.abs(x - dx) < 7) cv.put(x, pb, az1 - 1, fence);
        // roof: ridge along x, flared, with a hip at the far end and a small gablet on it
        int ad = az2 - az1 + 1;
        int rows = (ad + 4 + 1) / 2;
        int[] ys = profile(rows, 2, at - 1);
        int farX = side < 0 ? ax1 : ax2;
        for (int i = 0; i < rows; i++) {
            int y = ys[i];
            String bnd = i == 0 ? trim : band(i, rows);
            int za = az1 - 2 + i, zb2 = az2 + 2 - i;
            int hipU = farX - side * (-3 + i);                     // hip row position at the far end
            int xs = side < 0 ? hipU : (side < 0 ? 0 : x2 + 1), xe = side < 0 ? x1 - 1 : hipU;
            for (int x = Math.min(xs, xe); x <= Math.max(xs, xe); x++) {
                if (za >= zb2) { cv.put(x, y, za, solid(bnd)); cv.put(x, y - 1, za, solid(bnd)); cv.put(x, y + 1, za, slabOf(bnd, false)); if (za + 1 == zb2) { cv.put(x, y, zb2, solid(bnd)); cv.put(x, y + 1, zb2, slabOf(bnd, false)); } continue; }
                cv.put(x, y, za, st(bnd, "+z", false)); cv.put(x, y, zb2, st(bnd, "-z", false));
                if (i >= 1) { cv.put(x, y - 1, za, solid(bnd)); cv.put(x, y - 1, zb2, solid(bnd)); }
                else { cv.put(x, y - 1, za, slabOf(bnd, true)); cv.put(x, y - 1, zb2, slabOf(bnd, true)); }
                int prev = i > 0 ? ys[i - 1] : y;
                for (int yy = prev + 1; yy < y; yy++) { cv.put(x, yy, za, solid(bnd)); cv.put(x, yy, zb2, solid(bnd)); }
            }
            if (za < zb2) for (int z = za; z <= zb2; z++) {
                cv.put(hipU, y, z, st(bnd, side < 0 ? "+x" : "-x", false));
                if (i >= 1) cv.put(hipU, y - 1, z, solid(bnd));
            }
            if (za >= zb2 - 1) break;
        }
        // red gablet with a lit window on the hip end
        int gx = farX - side * 2, gzc = (az1 + az2) / 2;
        for (int z = gzc - 2; z <= gzc + 2; z++) cv.put(gx, at + 3, z, wall);
        for (int z = gzc - 1; z <= gzc + 1; z++) cv.put(gx, at + 4, z, wall);
        cv.put(gx, at + 5, gzc, wall); cv.put(gx, at + 3, gzc, lit);
        int[][] gr = {{gzc - 3, at + 3}, {gzc + 3, at + 3}, {gzc - 2, at + 4}, {gzc + 2, at + 4}, {gzc - 1, at + 5}, {gzc + 1, at + 5}};
        for (int k = 0; k < gr.length; k++) {
            String dir = k % 2 == 0 ? "+z" : "-z";
            for (int u = 0; u < 4; u++) cv.put(gx + (side < 0 ? u - 1 : 1 - u), gr[k][1], gr[k][0], st(trim, dir, false));
        }
        for (int u = 0; u < 4; u++) { int xx = gx + (side < 0 ? u - 1 : 1 - u); cv.put(xx, at + 6, gzc, solid(trim)); cv.put(xx, at + 7, gzc, slabOf(trim, false)); }
        // the chimney at the corner where annex and house meet: stone at the bottom, lighter stone at the top
        if (bool("chimney", true)) {
            int cxA = side < 0 ? x1 - 7 : x2 + 5, czA = az1 - 2;
            int capY = ys[ys.length - 1] + 1;
            for (int y = pb; y < capY; y++) {
                String b = y < pb + 3 ? "70%stone_bricks,15%cracked_stone_bricks,15%mossy_stone_bricks"
                    : y < pb + 6 ? "40%cobblestone,35%stone_bricks,25%andesite" : "55%calcite,30%diorite,15%stone_bricks";
                cv.fill(cxA, y, czA, cxA + 2, y, czA + 1, b);
            }
            cv.fill(cxA - 1, pb, czA - 1, cxA + 3, pb, czA - 1, slabOf("stone_bricks", false));
            int cy = capY;
            cv.fill(cxA - 1, cy, czA - 1, cxA + 3, cy, czA + 2, "calcite");
            for (int[] k : new int[][]{{cxA - 1, czA - 1}, {cxA + 3, czA - 1}, {cxA - 1, czA + 2}, {cxA + 3, czA + 2}}) cv.put(k[0], cy + 1, k[1], fence);
            cv.put(cxA + 1, cy + 1, czA, "campfire[lit=true]");
            cv.fill(cxA - 1, cy + 2, czA - 1, cxA + 3, cy + 2, czA + 2, "polished_diorite_slab[type=bottom]");
            cv.fill(cxA, cy + 2, czA, cxA + 2, cy + 2, czA + 1, "polished_diorite");
            cv.fill(cxA, cy + 3, czA, cxA + 2, cy + 3, czA + 1, "polished_diorite_slab[type=bottom]");
        }
    }

    private int[] towerRect(int side, int[] annex) {
        int tb = Math.max(w, d) >= 13 ? 7 : 5;
        int tx2, tx1, tz1;
        if (side < 0) { tx2 = x1 - o - 5; tx1 = tx2 - tb + 1; } else { tx1 = x2 + o + 5; tx2 = tx1 + tb - 1; }
        tz1 = annex != null ? annex[3] + 1 : z2 - tb + 1;
        if (annex == null) { if (side < 0) { tx2 = x1 - o - 2; tx1 = tx2 - tb + 1; } else { tx1 = x2 + o + 2; tx2 = tx1 + tb - 1; } }
        int[] r = {tx1, tx2, tz1, tz1 + tb - 1};
        return clear(tx1 - 4, tx2 + 4, tz1 - 4, tz1 + tb + 3) ? r : null;
    }

    private void tower(int[] r, int side, int[] annex) {
        int tx1 = r[0], tx2 = r[1], tz1 = r[2], tz2 = r[3];
        int tcx = (tx1 + tx2) / 2, tcz = (tz1 + tz2) / 2, half = (tx2 - tx1) / 2;
        int b0 = annex != null ? by + 2 : by;
        int gy = groundAt(tx1, tz1, tx2, tz2, true);
        cv.fill(tx1, Math.min(by - 1, gy), tz1, tx2, b0 - 1, tz2, "70%stone_bricks,15%cracked_stone_bricks,15%mossy_stone_bricks");
        int ledge = Math.max(top + 6, peakY - 5), ta = ledge + 1, tb = ta + 4;
        cv.fill(tx1 + 1, b0, tz1 + 1, tx2 - 1, tb, tz2 - 1, "air");
        cv.walls(tx1, b0, tz1, tx2, ledge - 1, tz2, wall);
        for (int[] k : new int[][]{{tx1, tz1}, {tx2, tz1}, {tx1, tz2}, {tx2, tz2}}) {
            cv.fill(k[0], b0, k[1], k[0], ledge - 1, k[1], ax(log, 'y'));
            int dx = k[0] == tx1 ? 1 : -1;
            for (int y = b0 + 3; y < ledge - 1; y += 6) cv.put(k[0] + dx, y, k[1], st(stairs, dx > 0 ? "-x" : "+x", false));
        }
        int body = ledge - b0;
        for (int yb : new int[]{b0 + body / 3, b0 + (2 * body) / 3}) {
            for (int x = tx1; x <= tx2; x++) {
                cv.put(x, yb, tz1, ax(log, 'x')); cv.put(x, yb, tz2, ax(log, 'x'));
                cv.put(x, yb, tz1 - 1, st(stairs, "+z", true)); cv.put(x, yb, tz2 + 1, st(stairs, "-z", true));
            }
            for (int z = tz1; z <= tz2; z++) {
                cv.put(tx1, yb, z, ax(log, 'z')); cv.put(tx2, yb, z, ax(log, 'z'));
                cv.put(tx1 - 1, yb, z, st(stairs, "+x", true)); cv.put(tx2 + 1, yb, z, st(stairs, "-x", true));
            }
            for (int[] k : new int[][]{{tx1 - 1, tz1 - 1}, {tx2 + 1, tz1 - 1}, {tx1 - 1, tz2 + 1}, {tx2 + 1, tz2 + 1}}) cv.put(k[0], yb, k[1], slabOf(planks, true));
        }
        int outX = side < 0 ? tx1 : tx2;
        cv.fill(tcx, b0 + body / 3 + 2, tz1, tcx, b0 + body / 3 + 3, tz1, lit);
        cv.put(tcx, b0 + body / 3 + 4, tz1, st(stairs, "+z", false));
        cv.fill(outX, b0 + body / 3 + 2, tcz, outX, b0 + body / 3 + 3, tcz, glass);
        cv.fill(tcx, b0 + (2 * body) / 3 + 2, tz1, tcx, b0 + (2 * body) / 3 + 3, tz1, glass);
        cv.fill(outX, b0 + (2 * body) / 3 + 2, tcz, outX, b0 + (2 * body) / 3 + 3, tcz, lit);
        if (annex != null) { cv.put(tcx, b0, tz1, "air"); cv.put(tcx, b0 + 1, tz1, "air"); cv.put(tcx, b0, annex[3], "air"); cv.put(tcx, b0 + 1, annex[3], "air"); }
        else { doorAt(tcx, b0, tz1, "+z"); cv.put(tcx, b0, tz1 + 1, "air"); cv.put(tcx, b0 + 1, tz1 + 1, "air"); }
        cv.fill(tx1 + 1, b0, tz2 - 1, tx1 + 1, ledge, tz2 - 1, "ladder[facing=" + f.dir("-z") + "]");
        // corbels under the ledge, the ledge, the jettied top room
        for (int x = tx1; x <= tx2; x += 2) {
            cv.put(x, ledge - 1, tz1 - 1, st(stairs, "+z", true)); cv.put(x, ledge - 1, tz2 + 1, st(stairs, "-z", true));
            if (x == tx1 || x == tcx || x == tx2) { cv.put(x, ledge - 2, tz1 - 1, st(stairs, "+z", true)); cv.put(x, ledge - 3, tz1 - 1, st(stairs, "+z", true)); }
        }
        for (int z = tz1; z <= tz2; z += 2) {
            cv.put(tx1 - 1, ledge - 1, z, st(stairs, "+x", true)); cv.put(tx2 + 1, ledge - 1, z, st(stairs, "-x", true));
            if (z == tz1 || z == tcz || z == tz2) { cv.put(outX + (side < 0 ? -1 : 1), ledge - 2, z, st(stairs, side < 0 ? "+x" : "-x", true)); }
        }
        cv.fill(tx1 - 2, ledge, tz1 - 2, tx2 + 2, ledge, tz2 + 2, planks);
        for (int x = tx1 - 3; x <= tx2 + 3; x++) { cv.put(x, ledge, tz1 - 3, slabOf(planks, true)); cv.put(x, ledge, tz2 + 3, slabOf(planks, true)); }
        for (int z = tz1 - 2; z <= tz2 + 2; z++) { cv.put(tx1 - 3, ledge, z, slabOf(planks, true)); cv.put(tx2 + 3, ledge, z, slabOf(planks, true)); }
        int ux1 = tx1 - 1, ux2 = tx2 + 1, uz1 = tz1 - 1, uz2 = tz2 + 1;
        cv.fill(ux1 + 1, ta, uz1 + 1, ux2 - 1, tb, uz2 - 1, "air");
        cv.walls(ux1, ta, uz1, ux2, tb, uz2, wall);
        for (int[] k : new int[][]{{ux1, uz1}, {ux2, uz1}, {ux1, uz2}, {ux2, uz2}}) cv.fill(k[0], ta, k[1], k[0], tb, k[1], ax(log, 'y'));
        for (int x = ux1; x <= ux2; x++) { cv.put(x, ta, uz1, ax(log, 'x')); cv.put(x, tb, uz1, ax(log, 'x')); cv.put(x, ta, uz2, ax(log, 'x')); cv.put(x, tb, uz2, ax(log, 'x')); }
        for (int z = uz1; z <= uz2; z++) { cv.put(ux1, ta, z, ax(log, 'z')); cv.put(ux1, tb, z, ax(log, 'z')); cv.put(ux2, ta, z, ax(log, 'z')); cv.put(ux2, tb, z, ax(log, 'z')); }
        for (int x : new int[]{tcx - 3, tcx + 3}) if (x > ux1 && x < ux2) cv.fill(x, ta, uz1, x, tb, uz1, ax(log, 'y'));
        cv.fill(tcx - 1, ta + 1, uz1, tcx + 1, ta + 2, uz1, lit); cv.put(tcx, ta + 3, uz1, lit);
        cv.put(tcx - 1, ta + 3, uz1, st(stairs, "+x", true)); cv.put(tcx + 1, ta + 3, uz1, st(stairs, "-x", true));
        int oX = side < 0 ? ux1 : ux2;
        cv.fill(oX, ta + 1, tcz - 1, oX, ta + 3, tcz + 1, wood.equals("spruce") ? "dark_oak_planks" : "spruce_planks");
        cv.fill(oX, ta + 1, tcz, oX, ta + 2, tcz, glass);
        int iX = side < 0 ? ux2 : ux1;
        cv.fill(iX, ta + 1, tcz, iX, ta + 2, tcz, lit);
        // brackets, eave ring and a fringe of boards, then the flared spire
        for (int x = ux1; x <= ux2; x += 2) { cv.put(x, tb, uz1 - 1, st(stairs, "+z", true)); cv.put(x, tb, uz2 + 1, st(stairs, "-z", true)); }
        for (int z = uz1; z <= uz2; z += 2) { cv.put(ux1 - 1, tb, z, st(stairs, "+x", true)); cv.put(ux2 + 1, tb, z, st(stairs, "-x", true)); }
        cv.fill(ux1 - 1, tb + 1, uz1 - 1, ux2 + 1, tb + 1, uz2 + 1, planks);
        for (int x = ux1 - 2; x <= ux2 + 2; x++) { cv.put(x, tb + 1, uz1 - 2, hang(trap, "+z")); cv.put(x, tb + 1, uz2 + 2, hang(trap, "-z")); }
        for (int z = uz1 - 1; z <= uz2 + 1; z++) { cv.put(ux1 - 2, tb + 1, z, hang(trap, "+x")); cv.put(ux2 + 2, tb + 1, z, hang(trap, "-x")); }
        int R = half + 1 + 2 + 2, B = tb + 2;
        int[] ry = new int[R + 1];
        for (int rr = 0, hh = 0; rr < R; rr++) { hh += rr < 2 ? 0 : rr < 5 ? 1 : 2; ry[R - rr] = B + hh; }
        for (int x = tcx - R; x <= tcx + R; x++) for (int z = tcz - R; z <= tcz + R; z++) {
            int rr = Math.max(Math.abs(x - tcx), Math.abs(z - tcz));
            if (rr == 0) continue;
            int y = ry[rr], dx = x - tcx, dz = z - tcz;
            String bnd = band(R - rr, R);
            String toward = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "-x" : "+x") : (dz > 0 ? "-z" : "+z");
            if (rr == R) { if ((x + z) % 2 == 0 && Math.abs(dx) != Math.abs(dz)) cv.put(x, y, z, slabOf(bnd, false)); continue; }
            cv.put(x, y, z, st(bnd, toward, false));
            if (rr <= half + 2) { for (int yy = B; yy < y; yy++) cv.put(x, yy, z, solid(bnd)); }
            else if (rr == R - 1) cv.put(x, y - 1, z, slabOf(bnd, true));
            else if (y - 1 >= B) cv.put(x, y - 1, z, solid(bnd));
        }
        int pk = ry[1] + 2;
        cv.fill(tcx, B, tcz, tcx, pk, tcz, solid(grad.get(grad.size() - 1)));
        for (String dd : new String[]{"+x", "-x", "+z", "-z"}) {
            int ox = dd.equals("+x") ? 1 : dd.equals("-x") ? -1 : 0, oz = dd.equals("+z") ? 1 : dd.equals("-z") ? -1 : 0;
            cv.put(tcx + ox, pk - 1, tcz + oz, hang(trap, switch (dd) { case "+x" -> "-x"; case "-x" -> "+x"; case "+z" -> "-z"; default -> "+z"; }));
        }
        cv.put(tcx, pk + 1, tcz, fence); cv.put(tcx, pk + 2, tcz, fence);
        cv.fill(tcx, pk + 3, tcz, tcx, pk + 4, tcz, "lightning_rod");
        // sometimes a crane arm with a hanging basket of moss and flowers
        if (rnd(11, 10) < 4) {
            int ay = b0 + (2 * body) / 3 + 3, sg = side < 0 ? -1 : 1, edge = side < 0 ? tx1 - 1 : tx2 + 1;
            int end = edge + sg * 8;
            for (int k = 0; k <= 8; k++) cv.put(edge + sg * k, ay, tcz, ax(log, 'x'));
            for (int k = 0; k <= 4; k++) cv.put(edge + sg * k, ay + 1, tcz, planks);
            for (int k = 5; k <= 7; k++) cv.put(edge + sg * k, ay + 1, tcz, st(stairs, sg < 0 ? "+x" : "-x", false));
            cv.put(edge, ay - 1, tcz, planks); cv.put(edge + sg, ay - 1, tcz, st(stairs, sg < 0 ? "+x" : "-x", true)); cv.put(edge, ay - 2, tcz, st(stairs, sg < 0 ? "+x" : "-x", true));
            cv.put(end + sg, ay, tcz, planks); cv.put(end + sg, ay + 1, tcz, st(stairs, sg < 0 ? "+x" : "-x", false));
            cv.put(end + sg, ay - 1, tcz, planks); cv.put(end + sg, ay - 2, tcz, st(stairs, sg < 0 ? "-x" : "+x", true));
            for (int k : new int[]{2, 6}) cv.fill(edge + sg * k, ay - 3, tcz, edge + sg * k, ay - 1, tcz, "iron_chain[axis=y]");
            int bx1 = edge + sg * 2, bx2 = edge + sg * 6;
            cv.fill(bx1, ay - 6, tcz - 1, bx2, ay - 4, tcz + 1, "40%bamboo_mosaic,22%moss_block,23%flowering_azalea_leaves[persistent=true],15%hay_block");
            cv.fill(bx1 + sg, ay - 7, tcz - 1, bx2 - sg, ay - 7, tcz, "60%bamboo_mosaic,40%moss_block");
            cv.put((bx1 + bx2) / 2, ay - 8, tcz, "bamboo_mosaic");
        }
    }

    // ------------------------------------------------------------------ yard, snow

    private void yard(boolean deck) {
        int lz = z1 - (deck ? 6 : 4);
        int lx = cx + 3;
        if (clear(lx, lx, lz, lz)) {
            int gy = groundAt(lx, lz, lx, lz, false) + 1;
            cv.fill(lx, gy, lz, lx, gy + 2, lz, fence); cv.put(lx, gy + 3, lz, "lantern[hanging=false]");
        }
        for (int k = 0; k < 6; k++) {
            int x = x1 - 1 + rnd(20 + k, w + 2), z = z1 - 2 - rnd(30 + k, 2);
            if (Math.abs(x - cx) <= 1) continue;
            int gy = groundAt(x, z, x, z, false) + 1;
            if (cv.get(x, gy, z) == null) cv.put(x, gy, z, k % 3 == 0 ? "rose_bush[half=lower]" : "poppy");
            if (k % 3 == 0 && cv.get(x, gy, z) != null && cv.get(x, gy, z).startsWith("rose_bush")) cv.put(x, gy + 1, z, "rose_bush[half=upper]");
        }
    }

}
