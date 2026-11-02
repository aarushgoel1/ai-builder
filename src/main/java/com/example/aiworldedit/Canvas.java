package com.example.aiworldedit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A block canvas in local build coordinates: houses are designed block by block here (like a builder working by
 * hand), then compiled into few WorldEdit commands. Every cell holds a block or a weighted pattern
 * ("80%red_terracotta,20%red_concrete_powder"); the compiler merges equal neighbours into boxes, so a detailed
 * design costs about as many commands as a plain one.
 */
final class Canvas {
    private final Map<Long, String> cells = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 26) | ((long) (y + 2048) << 13) | (z + 4096);
    }

    private static int kx(long k) { return (int) (k >>> 26) - 4096; }
    private static int ky(long k) { return (int) ((k >>> 13) & 8191) - 2048; }
    private static int kz(long k) { return (int) (k & 8191) - 4096; }

    void put(int x, int y, int z, String b) {
        if (b == null) return;
        cells.put(key(x, y, z), b);
    }

    String get(int x, int y, int z) { return cells.get(key(x, y, z)); }

    boolean has(int x, int y, int z) {
        String b = cells.get(key(x, y, z));
        return b != null && !b.equals("air");
    }

    void remove(int x, int y, int z) { cells.remove(key(x, y, z)); }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        if (b == null) return;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) put(x, y, z, b);
    }

    /** Only where nothing is yet. */
    void fillEmpty(int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) if (get(x, y, z) == null) put(x, y, z, b);
    }

    void walls(int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) { put(x, y, z1, b); put(x, y, z2, b); }
            for (int z = z1; z <= z2; z++) { put(x1, y, z, b); put(x2, y, z, b); }
        }
    }

    int size() { return cells.size(); }

    /** {minX,minY,minZ,maxX,maxY,maxZ} of solid cells, or null. */
    int[] bounds() {
        int[] b = null;
        for (Map.Entry<Long, String> en : cells.entrySet()) {
            if (en.getValue().equals("air")) continue;
            long k = en.getKey();
            int x = kx(k), y = ky(k), z = kz(k);
            if (b == null) b = new int[]{x, y, z, x, y, z};
            else { b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z);
                   b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z); }
        }
        return b;
    }

    /** 0 = clear (air), 1 = full blocks, 2 = shaped blocks (stairs, slabs, fences), 3 = things that hang on others. */
    static int stage(String pattern) {
        if (pattern.equals("air")) return 0;
        String p = pattern.toLowerCase(java.util.Locale.ROOT);
        for (String s : new String[]{"vine", "lantern", "_door", "ladder", "trapdoor", "chain", "lightning_rod", "torch", "_sign",
                "banner", "potted_", "flower_pot", "campfire", "decorated_pot", "carpet", "poppy", "short_grass", "fern", "candle",
                "rose_bush", "tulip", "dandelion", "bell", "cornflower", "allium", "oxeye"}) if (p.contains(s)) return 3;
        for (String s : new String[]{"_stairs", "_slab", "_fence", "_wall", "_pane", "iron_bars"}) if (p.contains(s)) return 2;
        return 1;
    }

    /**
     * WorldEdit commands: equal cells merged into boxes (grown along x, then z, then y), cleared space first,
     * then full blocks, shaped blocks and finally the blocks that hang on them, each bottom-up.
     */
    List<String> compile(BuildPlanner.Frame f) {
        settle(f);
        Map<String, Set<Long>> groups = new HashMap<>();
        for (Map.Entry<Long, String> en : cells.entrySet()) groups.computeIfAbsent(en.getValue(), k -> new HashSet<>()).add(en.getKey());
        List<Object[]> boxes = new ArrayList<>(); // {pattern, x1,y1,z1,x2,y2,z2}
        for (Map.Entry<String, Set<Long>> g : groups.entrySet()) {
            Set<Long> left = new HashSet<>(g.getValue());
            List<Long> order = new ArrayList<>(g.getValue());
            order.sort((a, b) -> {
                int c = Integer.compare(ky(a), ky(b));
                if (c != 0) return c;
                c = Integer.compare(kz(a), kz(b));
                return c != 0 ? c : Integer.compare(kx(a), kx(b));
            });
            for (long k : order) {
                if (!left.contains(k)) continue;
                int x0 = kx(k), y0 = ky(k), z0 = kz(k), x1 = x0, y1 = y0, z1 = z0;
                while (left.contains(key(x1 + 1, y0, z0))) x1++;
                grow:
                while (true) {
                    for (int x = x0; x <= x1; x++) if (!left.contains(key(x, y0, z1 + 1))) break grow;
                    z1++;
                }
                grow2:
                while (true) {
                    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) if (!left.contains(key(x, y1 + 1, z))) break grow2;
                    y1++;
                }
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) left.remove(key(x, y, z));
                boxes.add(new Object[]{g.getKey(), x0, y0, z0, x1, y1, z1});
            }
        }
        boxes.sort((a, b) -> {
            int c = Integer.compare(stage((String) a[0]), stage((String) b[0]));
            if (c != 0) return c;
            c = Integer.compare((int) a[2], (int) b[2]);
            if (c != 0) return c;
            c = Integer.compare((int) a[3], (int) b[3]);
            return c != 0 ? c : Integer.compare((int) a[1], (int) b[1]);
        });
        List<String> out = new ArrayList<>();
        String last1 = null, last2 = null;
        for (Object[] bx : boxes) {
            String p1 = f.pos((int) bx[1], (int) bx[2], (int) bx[3]), p2 = f.pos((int) bx[4], (int) bx[5], (int) bx[6]);
            if (!p1.equals(last1)) out.add("//pos1 " + p1);
            if (!p2.equals(last2)) out.add("//pos2 " + p2);
            last1 = p1; last2 = p2;
            out.add("//set " + bx[0]);
        }
        return out;
    }

    // ------------------------------------------------------------------ physics

    private static final String[] PLANTS = {"poppy", "short_grass", "fern", "tulip", "dandelion", "cornflower", "allium",
        "oxeye_daisy", "azure_bluet", "lily_of_the_valley", "blue_orchid", "sweet_berry_bush", "_sapling", "torchflower"};
    private static final String[] TALL = {"rose_bush", "lilac", "peony", "sunflower", "tall_grass", "large_fern"};
    private static final String[] SOIL = {"grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "moss_block", "mud",
        "farmland", "mycelium", "muddy_mangrove_roots", "dirt_path"};

    private static boolean any(String b, String[] names, boolean suffix) {
        String n = BuildPhysics.base(b);
        for (String s : names) if (suffix ? n.endsWith(s) : n.equals(s)) return true;
        return false;
    }

    /** Every part of a (possibly mixed) pattern passes the test. */
    private static boolean all(String pattern, java.util.function.Predicate<String> t) {
        if (pattern == null) return false;
        for (String p : VoxelGrid.splitPattern(pattern)) if (!t.test(p.replaceFirst("^\\d+(\\.\\d+)?%", ""))) return false;
        return true;
    }

    private boolean nothingBelow(int x, int y, int z) {
        for (Map.Entry<Long, String> en : cells.entrySet()) {
            long k = en.getKey();
            if (kx(k) == x && kz(k) == z && ky(k) < y) return false;
        }
        return true;
    }

    private static boolean solidCell(String b) {
        return b != null && stage(b) == 1 && !b.contains("air");
    }

    /**
     * Makes the design survive Minecraft's block physics, since WorldEdit places with neighbour updates on:
     * falling blocks keep only to cells with a solid block placed under them first, ladders get a full block behind,
     * hanging lanterns get a chain up to the ceiling, vines keep only supported sides, flowers need soil.
     * Returns how many cells were changed.
     */
    int settle(BuildPlanner.Frame f) {
        Map<String, int[]> off = new HashMap<>();
        off.put(f.dir("+x"), new int[]{1, 0, 0}); off.put(f.dir("-x"), new int[]{-1, 0, 0});
        off.put(f.dir("+z"), new int[]{0, 0, 1}); off.put(f.dir("-z"), new int[]{0, 0, -1});
        off.put("up", new int[]{0, 1, 0}); off.put("down", new int[]{0, -1, 0});
        int changed = 0;
        List<Long> keys = new ArrayList<>(cells.keySet());
        keys.sort((a, b) -> Integer.compare(ky(a), ky(b)));
        // 1. falling blocks (bottom-up, so a column checks against the already fixed cell under it)
        for (long k : keys) {
            String b = cells.get(k);
            if (b == null || !BuildPhysics.hasGravity(b)) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            String below = get(x, y - 1, z);
            boolean ok = below == null ? nothingBelow(x, y, z) : solidCell(below);
            if (!ok) { put(x, y, z, BuildPhysics.withoutGravity(b)); changed++; }
        }
        // 2. ladders need a full block behind them; doors need a full block under the lower half
        for (long k : keys) {
            String b = cells.get(k);
            if (b == null) continue;
            String n = BuildPhysics.base(b);
            int x = kx(k), y = ky(k), z = kz(k);
            if (n.equals("ladder")) {
                String fa = BuildPhysics.prop(b, "facing", "north");
                int[] o = off.get(BuildPhysics.opposite(fa));
                if (o == null) continue;
                int sx = x + o[0], sz = z + o[2];
                String sup = get(sx, y, sz);
                if (all(sup, p -> BuildPhysics.fullFace(p, fa))) continue;
                String fill = null;
                if (sup != null && BuildPhysics.base(sup).endsWith("glass_pane")) fill = BuildPhysics.base(sup).replace("_pane", "");
                else if (sup != null && !sup.equals("air") && !BuildPhysics.base(sup).endsWith("_door")) fill = nearFull(sx, y, sz);
                if (fill != null) put(sx, y, sz, fill); else remove(x, y, z);
                changed++;
            } else if (n.endsWith("_door") && BuildPhysics.prop(b, "half", "lower").equals("lower")) {
                String below = get(x, y - 1, z);
                if (below == null ? nothingBelow(x, y, z) : all(below, p -> BuildPhysics.fullFace(p, "up"))) continue;
                String fill = nearFull(x, y - 1, z);
                if (fill != null) { put(x, y - 1, z, fill); changed++; }
            }
        }
        // 3. lanterns: hanging ones need something above (a chain is added up to a ceiling nearby), standing ones below
        for (long k : keys) {
            String b = cells.get(k);
            if (b == null || !BuildPhysics.base(b).endsWith("lantern")) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            boolean hanging = BuildPhysics.prop(b, "hanging", "false").equals("true");
            String up = get(x, y + 1, z), down = get(x, y - 1, z);
            if (hanging ? all(up, p -> BuildPhysics.smallSquare(p, "down")) : (down == null ? nothingBelow(x, y, z)
                : all(down, p -> BuildPhysics.smallSquare(p, "up")))) continue;
            changed++;
            if (hanging) {
                int top = -1;
                for (int d = 1; d <= 4; d++) {
                    String c = get(x, y + d, z);
                    if (c == null || c.equals("air")) continue;
                    if (all(c, p -> BuildPhysics.smallSquare(p, "down"))) top = y + d;
                    break;
                }
                if (top > 0) { fill(x, y + 1, z, x, top - 1, z, "iron_chain[axis=y]"); continue; }
            }
            String base = BuildPhysics.base(b);
            if (down != null && all(down, p -> BuildPhysics.smallSquare(p, "up"))) put(x, y, z, base + "[hanging=false]");
            else if (up != null && all(up, p -> BuildPhysics.smallSquare(p, "down"))) put(x, y, z, base + "[hanging=true]");
            else remove(x, y, z);
        }
        // 4. vines (top-down): a side holds if a full block is there, or the vine above has the same side
        List<Long> down = new ArrayList<>(keys);
        java.util.Collections.reverse(down);
        for (long k : down) {
            String b = cells.get(k);
            if (b == null || !BuildPhysics.base(b).equals("vine")) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            String above = get(x, y + 1, z);
            List<String> keep = new ArrayList<>();
            int had = 0;
            for (String d : new String[]{"north", "south", "east", "west", "up"}) {
                if (!BuildPhysics.prop(b, d, "false").equals("true")) continue;
                had++;
                int[] o = off.get(d);
                String s = get(x + o[0], y + o[1], z + o[2]);
                boolean ok = s != null && (all(s, p -> BuildPhysics.fullFace(p, BuildPhysics.opposite(d)))
                    || BuildPhysics.base(s).endsWith("_leaves"));
                if (!ok && !d.equals("up") && above != null && BuildPhysics.base(above).equals("vine")
                    && BuildPhysics.prop(above, d, "false").equals("true")) ok = true;
                if (ok) keep.add(d + "=true");
            }
            if (keep.size() == had) continue;
            changed++;
            if (keep.isEmpty()) remove(x, y, z); else put(x, y, z, "vine[" + String.join(",", keep) + "]");
        }
        // 5. flowers and grass need soil; tall plants need both halves
        for (long k : keys) {
            String b = cells.get(k);
            if (b == null) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            boolean tall = any(b, TALL, false), small = !tall && any(b, PLANTS, true) && !BuildPhysics.base(b).startsWith("potted_");
            if (!tall && !small) continue;
            if (tall && BuildPhysics.prop(b, "half", "lower").equals("upper")) {
                String lo = get(x, y - 1, z);
                if (lo == null || !BuildPhysics.base(lo).equals(BuildPhysics.base(b))) { remove(x, y, z); changed++; }
                continue;
            }
            String below = get(x, y - 1, z);
            boolean ok = below == null ? nothingBelow(x, y, z) : any(below, SOIL, false);
            if (tall) {
                String up = get(x, y + 1, z);
                ok &= up != null && BuildPhysics.base(up).equals(BuildPhysics.base(b));
            }
            if (!ok) {
                remove(x, y, z);
                if (tall && get(x, y + 1, z) != null && BuildPhysics.base(get(x, y + 1, z)).equals(BuildPhysics.base(b))) remove(x, y + 1, z);
                changed++;
            }
        }
        return changed;
    }

    /** A full block from the same wall (nearest above or below), or null. */
    private String nearFull(int x, int y, int z) {
        for (int d = 1; d <= 4; d++)
            for (int s : new int[]{-1, 1}) {
                String c = get(x, y + s * d, z);
                if (c != null && stage(c) == 1 && !c.contains("air") && all(c, p -> BuildPhysics.fullFace(p, "north")
                    && BuildPhysics.fullFace(p, "up") && !BuildPhysics.isGravity(p))) return c;
            }
        return null;
    }
}
