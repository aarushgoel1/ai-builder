package com.example.aiworldedit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A block grid over a world-space box. It can be filled from the real world (see
 * WorldScanner.toGrid) and can SIMULATE the WorldEdit commands this mod produces,
 * so the AI can see what a plan will look like before anything is placed.
 * Block ids are stored without the "minecraft:" prefix and without block states.
 */
public final class VoxelGrid {
    public final int minX, minY, minZ, sx, sy, sz;
    private final short[] cells;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Short> index = new HashMap<>();
    /** Cells changed by simulation (so the build can be told apart from terrain). */
    private final boolean[] changed;
    /** Cells set by a pattern that mixes in air: WorldEdit randomizes them, so skip them in diffs. */
    private final boolean[] fuzzy;

    public VoxelGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.sx = maxX - minX + 1; this.sy = maxY - minY + 1; this.sz = maxZ - minZ + 1;
        cells = new short[sx * sy * sz];
        changed = new boolean[cells.length];
        fuzzy = new boolean[cells.length];
        id("air"); // palette 0 = air
    }

    /** A copy with the same contents and no cells marked as changed. */
    public VoxelGrid copy() {
        VoxelGrid g = new VoxelGrid(minX, minY, minZ, minX + sx - 1, minY + sy - 1, minZ + sz - 1);
        for (int i = 0; i < cells.length; i++) g.cells[i] = g.id(palette.get(cells[i]));
        return g;
    }

    /** Compares this (expected) grid with an actual scan: counts solid-vs-air mismatches. */
    public BuildInspector.Diff diff(VoxelGrid actual, BuildPlanner.Frame frame) {
        int missing = 0, extra = 0;
        String example = null;
        for (int x = minX; x < minX + sx; x++)
            for (int y = minY; y < minY + sy; y++)
                for (int z = minZ; z < minZ + sz; z++) {
                    if (!isChanged(x, y, z) || fuzzy[idx(x, y, z)]) continue;
                    boolean want = !isAir(get(x, y, z)), have = !isAir(actual.get(x, y, z));
                    if (want && !have) {
                        missing++;
                        if (example == null) {
                            int[] l = BuildInspector.toLocal(frame, x, y, z);
                            example = get(x, y, z) + " at local (" + l[0] + "," + l[1] + "," + l[2] + ")";
                        }
                    } else if (!want && have) {
                        extra++;
                    }
                }
        return new BuildInspector.Diff(missing, extra, example);
    }

    public static long volume(int[] box) {
        return (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
    }

    // ---------------- access ----------------

    private short id(String block) {
        Short s = index.get(block);
        if (s != null) return s;
        short n = (short) palette.size();
        palette.add(block);
        index.put(block, n);
        return n;
    }

    public boolean inside(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ && x < minX + sx && y < minY + sy && z < minZ + sz;
    }

    private int idx(int x, int y, int z) {
        return ((x - minX) * sy + (y - minY)) * sz + (z - minZ);
    }

    public String get(int x, int y, int z) {
        return inside(x, y, z) ? palette.get(cells[idx(x, y, z)]) : "air";
    }

    /** Raw set from a world scan (not marked as changed). */
    public void load(int x, int y, int z, String block) {
        if (inside(x, y, z)) cells[idx(x, y, z)] = id(baseId(block));
    }

    private void put(int x, int y, int z, String block) {
        put(x, y, z, block, false);
    }

    private void put(int x, int y, int z, String block, boolean isFuzzy) {
        if (!inside(x, y, z)) return;
        int i = idx(x, y, z);
        cells[i] = id(block);
        changed[i] = true;
        fuzzy[i] = isFuzzy;
    }

    public boolean isChanged(int x, int y, int z) {
        return inside(x, y, z) && changed[idx(x, y, z)];
    }

    public static boolean isAir(String b) {
        return b.equals("air") || b.equals("cave_air") || b.equals("void_air");
    }

    static String baseId(String b) {
        String s = b.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        int br = s.indexOf('[');
        return br >= 0 ? s.substring(0, br) : s;
    }

    // ---------------- simulation ----------------

    /** Simulates validated commands (with //placement pos1 semantics for shapes). */
    public void simulate(List<String> commands) {
        int[] p1 = null, p2 = null;
        String clip = null; // schematic loaded with //schem load (library builds)
        for (String raw : commands) {
            String[] t = raw.trim().substring(2).split(" ");
            String n = t[0].toLowerCase(Locale.ROOT);
            try {
                switch (n) {
                    case "pos1" -> p1 = coords(t[1]);
                    case "pos2" -> p2 = coords(t[1]);
                    case "set" -> region(p1, p2, (x, y, z) -> true, t[1]);
                    case "walls" -> {
                        int[] a = p1, b = p2;
                        region(a, b, (x, y, z) -> x == Math.min(a[0], b[0]) || x == Math.max(a[0], b[0])
                            || z == Math.min(a[2], b[2]) || z == Math.max(a[2], b[2]), t[1]);
                    }
                    case "faces", "outline" -> {
                        int[] a = p1, b = p2;
                        region(a, b, (x, y, z) -> x == Math.min(a[0], b[0]) || x == Math.max(a[0], b[0])
                            || y == Math.min(a[1], b[1]) || y == Math.max(a[1], b[1])
                            || z == Math.min(a[2], b[2]) || z == Math.max(a[2], b[2]), t[1]);
                    }
                    case "replace", "re", "rep" -> {
                        if (t.length >= 3) {
                            List<String> mask = splitPattern(t[1]);
                            region(p1, p2, (x, y, z) -> matches(get(x, y, z), mask), t[2]);
                        } else {
                            region(p1, p2, (x, y, z) -> !isAir(get(x, y, z)), t[1]);
                        }
                    }
                    case "overlay" -> overlay(p1, p2, t[1]);
                    case "center", "middle" -> center(p1, p2, t[1]);
                    case "line" -> line(p1, p2, t[1]);
                    case "stack" -> stack(p1, p2, t);
                    case "move" -> move(p1, p2, t);
                    case "cyl", "hcyl" -> cylinder(p1, t, n.equals("hcyl"));
                    case "sphere", "hsphere" -> sphere(p1, t, n.equals("hsphere"));
                    case "pyramid", "hpyramid" -> pyramid(p1, t, n.equals("hpyramid"));
                    case "schem", "schematic" -> { if (t.length >= 3 && t[1].equalsIgnoreCase("load")) clip = t[2]; }
                    case "paste" -> {
                        BuildLibrary.Placement pl = clip == null ? null : BuildLibrary.PLACED.get(clip);
                        if (pl != null) {
                            for (int i = 0; i < pl.positions().size(); i++) {
                                int[] q = pl.positions().get(i);
                                put(q[0], q[1], q[2], baseId(pl.blocks().get(i)));
                            }
                        }
                    }
                    default -> { } // smooth/naturalize/flora/hollow: not simulated
                }
            } catch (RuntimeException ignored) {
                // a malformed command just does nothing in the preview (as in WorldEdit)
            }
        }
    }

    private interface Where { boolean test(int x, int y, int z); }

    private void region(int[] a, int[] b, Where where, String pattern) {
        if (a == null || b == null) return;
        Pattern p = new Pattern(pattern);
        for (int x = Math.max(minX, Math.min(a[0], b[0])); x <= Math.min(minX + sx - 1, Math.max(a[0], b[0])); x++)
            for (int y = Math.max(minY, Math.min(a[1], b[1])); y <= Math.min(minY + sy - 1, Math.max(a[1], b[1])); y++)
                for (int z = Math.max(minZ, Math.min(a[2], b[2])); z <= Math.min(minZ + sz - 1, Math.max(a[2], b[2])); z++)
                    if (where.test(x, y, z)) put(x, y, z, p.at(x, y, z), p.mixesAir);
    }

    private void overlay(int[] a, int[] b, String pattern) {
        if (a == null || b == null) return;
        Pattern p = new Pattern(pattern);
        for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++)
            for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++)
                for (int y = Math.max(a[1], b[1]); y >= Math.min(a[1], b[1]); y--)
                    if (!isAir(get(x, y, z))) { put(x, y + 1, z, p.at(x, y + 1, z)); break; }
    }

    private void center(int[] a, int[] b, String pattern) {
        if (a == null || b == null) return;
        Pattern p = new Pattern(pattern);
        int cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2, cz = (a[2] + b[2]) / 2;
        put(cx, cy, cz, p.at(cx, cy, cz));
    }

    private void line(int[] a, int[] b, String pattern) {
        if (a == null || b == null) return;
        Pattern p = new Pattern(pattern);
        int n = Math.max(Math.abs(b[0] - a[0]), Math.max(Math.abs(b[1] - a[1]), Math.abs(b[2] - a[2])));
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * t);
            int y = (int) Math.round(a[1] + (b[1] - a[1]) * t);
            int z = (int) Math.round(a[2] + (b[2] - a[2]) * t);
            put(x, y, z, p.at(x, y, z));
        }
    }

    private static int[] dirVec(String[] t) {
        for (int i = 1; i < t.length; i++) {
            switch (t[i].toLowerCase(Locale.ROOT)) {
                case "north": return new int[]{0, 0, -1};
                case "south": return new int[]{0, 0, 1};
                case "east": return new int[]{1, 0, 0};
                case "west": return new int[]{-1, 0, 0};
                case "up": return new int[]{0, 1, 0};
                case "down": return new int[]{0, -1, 0};
                default: break;
            }
        }
        return null;
    }

    private static int count(String[] t) {
        for (int i = 1; i < t.length; i++) {
            if (t[i].startsWith("-")) continue;
            try { return Integer.parseInt(t[i]); } catch (NumberFormatException ignored) { }
        }
        return 1;
    }

    private void stack(int[] a, int[] b, String[] t) {
        int[] d = dirVec(t);
        if (a == null || b == null || d == null) return;
        boolean skipAir = String.join(" ", t).contains("-a");
        int x0 = Math.min(a[0], b[0]), y0 = Math.min(a[1], b[1]), z0 = Math.min(a[2], b[2]);
        int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]), z1 = Math.max(a[2], b[2]);
        int ox = d[0] * (x1 - x0 + 1), oy = d[1] * (y1 - y0 + 1), oz = d[2] * (z1 - z0 + 1);
        int n = count(t);
        for (int k = 1; k <= n; k++)
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) {
                        String s = get(x, y, z);
                        if (skipAir && isAir(s)) continue;
                        put(x + ox * k, y + oy * k, z + oz * k, s);
                    }
    }

    private void move(int[] a, int[] b, String[] t) {
        int[] d = dirVec(t);
        if (a == null || b == null || d == null) return;
        int n = count(t);
        int x0 = Math.min(a[0], b[0]), y0 = Math.min(a[1], b[1]), z0 = Math.min(a[2], b[2]);
        int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]), z1 = Math.max(a[2], b[2]);
        Map<int[], String> copy = new HashMap<>();
        List<int[]> keys = new ArrayList<>();
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    int[] k = {x, y, z};
                    keys.add(k);
                    copy.put(k, get(x, y, z));
                    put(x, y, z, "air");
                }
        for (int[] k : keys) put(k[0] + d[0] * n, k[1] + d[1] * n, k[2] + d[2] * n, copy.get(k));
    }

    private static List<String> args(String[] t) {
        List<String> a = new ArrayList<>();
        for (int i = 1; i < t.length; i++) if (!t[i].startsWith("-")) a.add(t[i]);
        return a;
    }

    private void cylinder(int[] c, String[] t, boolean hollow) {
        if (c == null) return;
        List<String> a = args(t);
        Pattern p = new Pattern(a.get(0));
        String[] r = a.get(1).split(",");
        double rx = Double.parseDouble(r[0]), rz = r.length > 1 ? Double.parseDouble(r[1]) : rx;
        int h = a.size() > 2 ? Integer.parseInt(a.get(2)) : 1;
        for (int dx = -(int) rx - 1; dx <= rx + 1; dx++)
            for (int dz = -(int) rz - 1; dz <= rz + 1; dz++) {
                double o = sq(dx / (rx + 0.5)) + sq(dz / (rz + 0.5));
                if (o > 1) continue;
                if (hollow && rx > 1 && rz > 1 && sq(dx / (rx - 0.5)) + sq(dz / (rz - 0.5)) <= 1) continue;
                for (int y = 0; y < h; y++) put(c[0] + dx, c[1] + y, c[2] + dz, p.at(c[0] + dx, c[1] + y, c[2] + dz), p.mixesAir);
            }
    }

    private void sphere(int[] c, String[] t, boolean hollow) {
        if (c == null) return;
        List<String> a = args(t);
        Pattern p = new Pattern(a.get(0));
        String[] r = a.get(1).split(",");
        double rx = Double.parseDouble(r[0]);
        double ry = r.length >= 3 ? Double.parseDouble(r[1]) : rx, rz = r.length >= 3 ? Double.parseDouble(r[2]) : rx;
        for (int dx = -(int) rx - 1; dx <= rx + 1; dx++)
            for (int dy = -(int) ry - 1; dy <= ry + 1; dy++)
                for (int dz = -(int) rz - 1; dz <= rz + 1; dz++) {
                    double o = sq(dx / (rx + 0.5)) + sq(dy / (ry + 0.5)) + sq(dz / (rz + 0.5));
                    if (o > 1) continue;
                    if (hollow && rx > 1 && sq(dx / (rx - 0.5)) + sq(dy / (ry - 0.5)) + sq(dz / (rz - 0.5)) <= 1) continue;
                    put(c[0] + dx, c[1] + dy, c[2] + dz, p.at(c[0] + dx, c[1] + dy, c[2] + dz), p.mixesAir);
                }
    }

    private void pyramid(int[] c, String[] t, boolean hollow) {
        if (c == null) return;
        List<String> a = args(t);
        Pattern p = new Pattern(a.get(0));
        int size = (int) Double.parseDouble(a.get(1));
        for (int y = 0; y < size; y++) {
            int r = size - y - 1;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (hollow && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    put(c[0] + dx, c[1] + y, c[2] + dz, p.at(c[0] + dx, c[1] + y, c[2] + dz), p.mixesAir);
                }
        }
    }

    private static double sq(double v) { return v * v; }

    private static int[] coords(String s) {
        String[] p = s.split(",");
        return new int[]{(int) Math.floor(Double.parseDouble(p[0])),
            (int) Math.floor(Double.parseDouble(p[1])), (int) Math.floor(Double.parseDouble(p[2]))};
    }

    /** Splits a pattern/mask on commas that are not inside [block states]. */
    static List<String> splitPattern(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') depth--;
            else if (ch == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private static boolean matches(String current, List<String> mask) {
        for (String m : mask) {
            String b = baseId(m);
            if (b.equals("#existing") ? !isAir(current) : b.equals(current)) return true;
        }
        return false;
    }

    /** Weighted pattern like 70%stone_bricks,30%cobblestone; picks deterministically per position. */
    private static final class Pattern {
        final List<String> blocks = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();
        int total = 0;
        boolean mixesAir = false;

        Pattern(String s) {
            for (String part : splitPattern(s)) {
                int w = 1;
                String b = part;
                int pct = part.indexOf('%');
                if (pct > 0 && (part.indexOf('[') < 0 || part.indexOf('[') > pct)) {
                    try {
                        w = Math.max(1, (int) Math.round(Double.parseDouble(part.substring(0, pct))));
                        b = part.substring(pct + 1);
                    } catch (NumberFormatException ignored) { }
                }
                blocks.add(baseId(b));
                weights.add(w);
                total += w;
            }
            mixesAir = blocks.size() > 1 && blocks.contains("air");
        }

        String at(int x, int y, int z) {
            if (blocks.size() == 1) return blocks.get(0);
            int h = Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791, total);
            for (int i = 0; i < blocks.size(); i++) {
                h -= weights.get(i);
                if (h < 0) return blocks.get(i);
            }
            return blocks.get(blocks.size() - 1);
        }
    }
}
