package com.example.aiworldedit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a block grid into something a language model can "see": text drawings of the
 * build from the front, the side, the top and floor by floor (in the plan's local
 * coordinates), plus automatic checks for common mistakes.
 */
public final class BuildInspector {
    private BuildInspector() {}

    /** Natural terrain, drawn as '.' so the build stands out. */
    private static final Set<String> TERRAIN = Set.of(
        "grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "stone", "deepslate",
        "granite", "diorite", "andesite", "tuff", "sand", "red_sand", "gravel", "clay", "snow", "snow_block",
        "water", "lava", "short_grass", "tall_grass", "fern", "large_fern", "dandelion", "poppy",
        "cornflower", "oxeye_daisy", "azure_bluet", "sandstone", "terracotta", "moss_block", "moss_carpet",
        "sugar_cane", "dead_bush", "seagrass", "kelp", "kelp_plant", "bedrock", "pink_petals", "bush",
        "leaf_litter", "firefly_bush", "short_dry_grass", "tall_dry_grass");

    /** Small attached decorations that are fine without solid support around them. */
    private static final Set<String> ATTACHED = Set.of(
        "lantern", "soul_lantern", "chain", "iron_chain", "torch", "wall_torch", "soul_torch", "end_rod",
        "amethyst_cluster", "vine", "glow_lichen", "bell");

    private static final int MAX_VIEW = 72; // max characters per row before views are skipped

    public record Diff(int missing, int extra, String exampleMissing) {}

    /**
     * @param grid   blocks to inspect (simulated preview or a real world scan)
     * @param frame  the plan's local frame
     * @param world  true when the grid is a scan of the real world (not a simulation)
     * @param diff   optional: planned-vs-actual comparison (post-build only)
     */
    public static String report(VoxelGrid grid, BuildPlanner.Frame frame, boolean world, Diff diff) {
        int[] lb = localBounds(grid, frame, world);
        StringBuilder out = new StringBuilder();
        if (lb == null) return "INSPECTION: the area is empty; nothing was built.\n";

        int lx0 = lb[0], ly0 = lb[1], lz0 = lb[2], lx1 = lb[3], ly1 = lb[4], lz1 = lb[5];
        out.append(world ? "INSPECTION OF WHAT IS ACTUALLY IN THE WORLD" : "SIMULATED PREVIEW OF YOUR PLAN")
            .append(" (local coordinates: x right, z forward, y up; y=0 is the anchor level)\n");
        out.append("Build spans x ").append(lx0).append("..").append(lx1)
            .append(", y ").append(ly0).append("..").append(ly1)
            .append(", z ").append(lz0).append("..").append(lz1)
            .append(" (").append(lx1 - lx0 + 1).append(" wide, ").append(ly1 - ly0 + 1).append(" tall, ")
            .append(lz1 - lz0 + 1).append(" deep).\n");

        Map<String, Character> legend = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int x = lx0; x <= lx1; x++)
            for (int y = ly0; y <= ly1; y++)
                for (int z = lz0; z <= lz1; z++) {
                    String b = at(grid, frame, x, y, z);
                    if (!VoxelGrid.isAir(b) && isBuild(grid, frame, x, y, z, b, world)) counts.merge(b, 1, Integer::sum);
                }
        counts.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue())
            .forEach(e -> legend.put(e.getKey(), pickChar(e.getKey(), legend)));

        boolean drawable = lx1 - lx0 + 1 <= MAX_VIEW && lz1 - lz0 + 1 <= MAX_VIEW && ly1 - ly0 + 1 <= 80;
        if (drawable) {
            out.append("\nLegend: ' '=air  '.'=natural terrain");
            int shown = 0;
            for (Map.Entry<String, Character> e : legend.entrySet()) {
                if (shown++ >= 40) break;
                out.append("  ").append(e.getValue()).append('=').append(e.getKey());
            }
            out.append("\n");

            out.append("\nFRONT VIEW (as the player sees it; columns x=").append(lx0).append("..").append(lx1)
                .append(" left->right, top row is y=").append(ly1).append(")\n");
            for (int y = ly1; y >= ly0; y--) {
                StringBuilder row = new StringBuilder();
                for (int x = lx0; x <= lx1; x++) {
                    char ch = ' ';
                    for (int z = lz0; z <= lz1; z++) {
                        String b = at(grid, frame, x, y, z);
                        if (!VoxelGrid.isAir(b)) { ch = charFor(grid, frame, x, y, z, b, legend, world); break; }
                    }
                    row.append(ch);
                }
                out.append(String.format("y=%-4d|", y)).append(row).append("|\n");
            }

            out.append("\nSIDE VIEW (from the left side; columns z=").append(lz0).append("..").append(lz1)
                .append(" left->right = near->far)\n");
            for (int y = ly1; y >= ly0; y--) {
                StringBuilder row = new StringBuilder();
                for (int z = lz0; z <= lz1; z++) {
                    char ch = ' ';
                    for (int x = lx0; x <= lx1; x++) {
                        String b = at(grid, frame, x, y, z);
                        if (!VoxelGrid.isAir(b)) { ch = charFor(grid, frame, x, y, z, b, legend, world); break; }
                    }
                    row.append(ch);
                }
                out.append(String.format("y=%-4d|", y)).append(row).append("|\n");
            }

            out.append("\nTOP VIEW (highest block; columns x=").append(lx0).append("..").append(lx1)
                .append(", top row is the far side z=").append(lz1).append(", bottom row z=").append(lz0).append(")\n");
            for (int z = lz1; z >= lz0; z--) {
                StringBuilder row = new StringBuilder();
                for (int x = lx0; x <= lx1; x++) {
                    char ch = ' ';
                    for (int y = ly1; y >= ly0; y--) {
                        String b = at(grid, frame, x, y, z);
                        if (!VoxelGrid.isAir(b)) { ch = charFor(grid, frame, x, y, z, b, legend, world); break; }
                    }
                    row.append(ch);
                }
                out.append(String.format("z=%-4d|", z)).append(row).append("|\n");
            }

            // Floor plans: horizontal slices through the build.
            int base = Math.max(ly0, 0);
            int height = ly1 - base + 1;
            int step = Math.max(2, (int) Math.ceil(height / 5.0));
            for (int y = base + 1; y <= ly1; y += step) {
                out.append("\nFLOOR PLAN at y=").append(y).append(" (same layout as the top view)\n");
                for (int z = lz1; z >= lz0; z--) {
                    StringBuilder row = new StringBuilder();
                    for (int x = lx0; x <= lx1; x++) {
                        String b = at(grid, frame, x, y, z);
                        row.append(VoxelGrid.isAir(b) ? ' ' : charFor(grid, frame, x, y, z, b, legend, world));
                    }
                    out.append(String.format("z=%-4d|", z)).append(row).append("|\n");
                }
            }
        } else {
            out.append("(Build is too large to draw; only the checks below are included.)\n");
        }

        out.append("\nBLOCK COUNTS: ");
        counts.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(12)
            .forEach(e -> out.append(e.getKey()).append(' ').append(e.getValue()).append(", "));
        out.append("\n\nAUTOMATIC CHECKS\n");
        List<String> issues = checks(grid, frame, lb, world);
        if (diff != null && diff.missing() > 0) {
            issues.add(0, diff.missing() + " planned blocks are MISSING from the world (WorldEdit may have rejected "
                + "some commands, e.g. an invalid block id)" + (diff.exampleMissing() == null ? "" : "; example: "
                + diff.exampleMissing()));
        }
        if (issues.isEmpty()) out.append("- no problems found\n");
        for (String s : issues) out.append("- ").append(s).append('\n');
        return out.toString();
    }

    // ---------------- checks ----------------

    private static List<String> checks(VoxelGrid g, BuildPlanner.Frame f, int[] lb, boolean world) {
        List<String> issues = new ArrayList<>();

        // 1. Floating parts: build blocks not connected to the ground (bottom of the grid).
        int n = g.sx * g.sy * g.sz;
        boolean[] seen = new boolean[n];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = g.minX; x < g.minX + g.sx; x++)
            for (int z = g.minZ; z < g.minZ + g.sz; z++)
                if (!VoxelGrid.isAir(g.get(x, g.minY, z))) {
                    seen[index(g, x, g.minY, z)] = true;
                    q.add(new int[]{x, g.minY, z});
                }
        flood(g, q, seen);
        List<String> floating = new ArrayList<>();
        int floatingBlocks = 0;
        for (int x = g.minX; x < g.minX + g.sx; x++)
            for (int y = g.minY; y < g.minY + g.sy; y++)
                for (int z = g.minZ; z < g.minZ + g.sz; z++) {
                    int i = index(g, x, y, z);
                    String b = g.get(x, y, z);
                    if (seen[i] || VoxelGrid.isAir(b)) continue;
                    // new floating cluster
                    List<int[]> cluster = new ArrayList<>();
                    ArrayDeque<int[]> cq = new ArrayDeque<>();
                    seen[i] = true;
                    cq.add(new int[]{x, y, z});
                    while (!cq.isEmpty()) {
                        int[] c = cq.poll();
                        cluster.add(c);
                        for (int[] d : DIRS) {
                            int ax = c[0] + d[0], ay = c[1] + d[1], az = c[2] + d[2];
                            if (!g.inside(ax, ay, az)) continue;
                            int j = index(g, ax, ay, az);
                            if (!seen[j] && !VoxelGrid.isAir(g.get(ax, ay, az))) { seen[j] = true; cq.add(new int[]{ax, ay, az}); }
                        }
                    }
                    boolean decorative = cluster.size() <= 3 && cluster.stream()
                        .allMatch(c -> ATTACHED.contains(g.get(c[0], c[1], c[2])) || g.get(c[0], c[1], c[2]).endsWith("_leaves"));
                    if (decorative) continue;
                    floatingBlocks += cluster.size();
                    if (floating.size() < 5) {
                        int[] c = cluster.get(0);
                        int[] l = toLocal(f, c[0], c[1], c[2]);
                        floating.add(cluster.size() + " x " + g.get(c[0], c[1], c[2]) + " near local ("
                            + l[0] + "," + l[1] + "," + l[2] + ")");
                    }
                }
        if (floatingBlocks > 0) {
            issues.add("FLOATING: " + floatingBlocks + " blocks are not connected to the ground or the rest of the "
                + "build: " + String.join("; ", floating));
        }

        // 2. Doors that are blocked on both sides.
        int doors = 0;
        List<String> blocked = new ArrayList<>();
        for (int x = g.minX; x < g.minX + g.sx; x++)
            for (int y = g.minY; y < g.minY + g.sy - 1; y++)
                for (int z = g.minZ; z < g.minZ + g.sz; z++) {
                    String b = g.get(x, y, z);
                    if (!b.endsWith("_door") || g.get(x, y - 1, z).endsWith("_door")) continue; // lower half only
                    doors++;
                    boolean ns = open(g, x, y, z - 1) && open(g, x, y, z + 1);
                    boolean ew = open(g, x - 1, y, z) && open(g, x + 1, y, z);
                    if (!ns && !ew && blocked.size() < 4) {
                        int[] l = toLocal(f, x, y, z);
                        blocked.add("(" + l[0] + "," + l[1] + "," + l[2] + ")");
                    }
                    if (!g.get(x, y + 1, z).endsWith("_door")) {
                        int[] l = toLocal(f, x, y, z);
                        issues.add("DOOR at local (" + l[0] + "," + l[1] + "," + l[2] + ") is missing its upper half");
                    }
                }
        if (!blocked.isEmpty()) issues.add("BLOCKED DOORS (no free space on either side): " + String.join(" ", blocked));
        if (doors == 0) issues.add("No doors found (fine for towers with open arches, otherwise add an entrance)");

        // 3. Very flat or very thin builds.
        int w = lb[3] - lb[0] + 1, h = lb[4] - lb[1] + 1, d = lb[5] - lb[2] + 1;
        if (h <= 3 && (w > 8 || d > 8)) issues.add("The build is very flat (" + h + " tall) for its footprint");
        return issues;
    }

    private static boolean open(VoxelGrid g, int x, int y, int z) {
        String b = g.get(x, y, z);
        return VoxelGrid.isAir(b) || b.endsWith("_carpet") || b.contains("grass") || b.contains("flower")
            || b.endsWith("_pressure_plate") || b.equals("snow") || b.endsWith("_sign") || b.equals("lantern");
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static void flood(VoxelGrid g, ArrayDeque<int[]> q, boolean[] seen) {
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : DIRS) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (!g.inside(x, y, z)) continue;
                int i = index(g, x, y, z);
                if (seen[i] || VoxelGrid.isAir(g.get(x, y, z))) continue;
                seen[i] = true;
                q.add(new int[]{x, y, z});
            }
        }
    }

    private static int index(VoxelGrid g, int x, int y, int z) {
        return ((x - g.minX) * g.sy + (y - g.minY)) * g.sz + (z - g.minZ);
    }

    // ---------------- coordinates & drawing ----------------

    static int[] toLocal(BuildPlanner.Frame f, int wx, int wy, int wz) {
        int dx = wx - f.anchor.getX(), dz = wz - f.anchor.getZ();
        return new int[]{dx * f.rx + dz * f.rz, wy - f.anchor.getY(), dx * f.fx + dz * f.fz};
    }

    private static String at(VoxelGrid g, BuildPlanner.Frame f, int x, int y, int z) {
        return g.get(f.wx(x, z), f.wy(y), f.wz(x, z));
    }

    private static boolean isBuild(VoxelGrid g, BuildPlanner.Frame f, int x, int y, int z, String b, boolean world) {
        if (!world && g.isChanged(f.wx(x, z), f.wy(y), f.wz(x, z))) return true;
        return !TERRAIN.contains(b);
    }

    private static char charFor(VoxelGrid g, BuildPlanner.Frame f, int x, int y, int z, String b,
                                Map<String, Character> legend, boolean world) {
        if (!isBuild(g, f, x, y, z, b, world)) return '.';
        Character c = legend.get(b);
        return c == null ? '?' : c;
    }

    /** Local bounding box of the build (non-terrain or simulated blocks), or null if empty. */
    private static int[] localBounds(VoxelGrid g, BuildPlanner.Frame f, boolean world) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        boolean any = false;
        for (int x = g.minX; x < g.minX + g.sx; x++)
            for (int y = g.minY; y < g.minY + g.sy; y++)
                for (int z = g.minZ; z < g.minZ + g.sz; z++) {
                    String s = g.get(x, y, z);
                    boolean build = world ? !VoxelGrid.isAir(s) && !TERRAIN.contains(s) : g.isChanged(x, y, z) && !VoxelGrid.isAir(s);
                    if (!build) continue;
                    int[] l = toLocal(f, x, y, z);
                    for (int i = 0; i < 3; i++) {
                        b[i] = Math.min(b[i], l[i]);
                        b[i + 3] = Math.max(b[i + 3], l[i]);
                    }
                    any = true;
                }
        if (!any) return null;
        b[1] = b[1] - 1; // show one row of ground below
        return b;
    }

    private static final String POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#@$&*+=%<>^~";

    private static char pickChar(String block, Map<String, Character> used) {
        String core = block.replace("stripped_", "").replace("polished_", "");
        String[] parts = core.split("_");
        List<Character> wanted = new ArrayList<>();
        if (block.endsWith("_stairs")) wanted.add('^');
        if (block.endsWith("_slab")) wanted.add('_');
        if (block.endsWith("_door")) wanted.add('D');
        if (block.contains("glass")) wanted.add('o');
        if (block.contains("lantern") || block.contains("torch")) wanted.add('L');
        if (block.endsWith("_fence")) wanted.add('f');
        if (block.endsWith("_leaves")) wanted.add('*');
        if (block.endsWith("_log") || block.endsWith("_wood")) wanted.add('|');
        for (String p : parts) if (!p.isEmpty()) wanted.add(Character.toUpperCase(p.charAt(0)));
        for (String p : parts) if (!p.isEmpty()) wanted.add(Character.toLowerCase(p.charAt(0)));
        for (char c : wanted) if (!used.containsValue(c)) return c;
        for (char c : POOL.toCharArray()) if (!used.containsValue(c)) return c;
        return '?';
    }
}
