package com.example.aiworldedit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minecraft block physics the plans must respect, because WorldEdit places blocks with neighbour updates on:
 * sand, gravel and concrete powder fall when there is air under them, and attached blocks (ladders, lanterns,
 * vines, flowers) pop off as items when a neighbour changes and nothing holds them.
 */
final class BuildPhysics {
    private BuildPhysics() {}

    static String base(String b) {
        String s = b.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        int i = s.indexOf('[');
        return i >= 0 ? s.substring(0, i) : s;
    }

    static String prop(String b, String key, String def) {
        int i = b.indexOf('[');
        if (i < 0) return def;
        for (String kv : b.substring(i + 1, b.length() - 1).split(",")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).trim().equals(key)) return kv.substring(e + 1).trim();
        }
        return def;
    }

    static boolean isGravity(String block) {
        String n = base(block);
        return n.equals("sand") || n.equals("red_sand") || n.equals("gravel") || n.endsWith("concrete_powder")
            || n.endsWith("anvil") || n.equals("suspicious_sand") || n.equals("suspicious_gravel") || n.equals("dragon_egg");
    }

    /** Things a falling block drops through. */
    static boolean fallsThrough(String block) {
        String n = block == null ? "air" : base(block);
        return n.equals("air") || n.equals("cave_air") || n.equals("water") || n.equals("lava") || n.equals("fire")
            || n.equals("short_grass") || n.equals("fern") || n.equals("vine") || n.equals("snow");
    }

    /** A look-alike that doesn't fall, for patterns made only of falling blocks. */
    static String steady(String block) {
        String n = base(block);
        if (n.endsWith("_concrete_powder")) return n.replace("_concrete_powder", "_concrete");
        return switch (n) {
            case "sand", "suspicious_sand" -> "sandstone";
            case "red_sand" -> "red_sandstone";
            case "gravel", "suspicious_gravel" -> "tuff";
            default -> "stone";
        };
    }

    /** The pattern without its falling blocks (weights kept for the rest); a look-alike if nothing else is left. */
    static String withoutGravity(String pattern) {
        List<String> parts = VoxelGrid.splitPattern(pattern);
        List<String> keep = new ArrayList<>();
        boolean any = false;
        for (String p : parts) {
            String b = p.replaceFirst("^\\d+(\\.\\d+)?%", "");
            if (isGravity(b)) any = true; else keep.add(p);
        }
        if (!any) return pattern;
        if (keep.isEmpty()) {
            String b = parts.get(0).replaceFirst("^\\d+(\\.\\d+)?%", "");
            return steady(b);
        }
        if (keep.size() == 1) return keep.get(0).replaceFirst("^\\d+(\\.\\d+)?%", "");
        return String.join(",", keep); // WorldEdit normalizes the remaining weights
    }

    static boolean hasGravity(String pattern) {
        for (String p : VoxelGrid.splitPattern(pattern)) if (isGravity(p.replaceFirst("^\\d+(\\.\\d+)?%", ""))) return true;
        return false;
    }

    private static final String[] NOT_FULL = {"_stairs", "_slab", "_fence", "_wall", "_pane", "iron_bars", "_door", "trapdoor",
        "lantern", "chain", "ladder", "vine", "torch", "_sign", "banner", "potted_", "flower_pot", "campfire", "decorated_pot",
        "carpet", "candle", "lightning_rod", "bell", "snow", "poppy", "short_grass", "fern", "rose_bush", "tulip", "dandelion",
        "cornflower", "allium", "oxeye", "button", "lever", "rail", "pressure_plate", "end_rod", "scaffolding", "cobweb",
        "sapling", "_head", "skull", "pointed_dripstone", "hanging_roots", "glow_lichen", "dripleaf", "azalea", "grindstone",
        "lectern", "anvil", "brewing_stand", "cauldron", "composter", "hopper", "enchanting_table", "stonecutter", "_bed",
        "chest", "_leaves", "glass_pane", "flower", "bush"};

    /** Is the face of `block` that points toward `face` (north/south/east/west/up/down) a full square? */
    static boolean fullFace(String block, String face) {
        if (block == null) return false;
        String n = base(block);
        if (n.equals("snow_block")) return true;
        if (n.equals("air") || n.equals("cave_air") || n.equals("bamboo")) return false;
        if (n.endsWith("_slab")) {
            String t = prop(block, "type", "bottom");
            return t.equals("double") || (t.equals("top") && face.equals("up")) || (t.equals("bottom") && face.equals("down"));
        }
        if (n.endsWith("_stairs")) {
            String h = prop(block, "half", "bottom");
            if (face.equals("up")) return h.equals("top");
            if (face.equals("down")) return h.equals("bottom");
            return prop(block, "facing", "north").equals(face) && prop(block, "shape", "straight").equals("straight");
        }
        if (n.endsWith("trapdoor")) {
            if (prop(block, "open", "false").equals("true")) return opposite(prop(block, "facing", "north")).equals(face);
            return face.equals(prop(block, "half", "bottom").equals("bottom") ? "down" : "up");
        }
        for (String s : NOT_FULL) if (n.contains(s)) return false;
        return true;
    }

    /** Enough to hold a lantern, torch or candle (fence posts, walls, chains count). */
    static boolean smallSquare(String block, String face) {
        if (fullFace(block, face)) return true;
        if (block == null || !(face.equals("up") || face.equals("down"))) return false;
        String n = base(block);
        return n.endsWith("_fence") || n.endsWith("_wall") || n.equals("iron_bars") || n.endsWith("_pane") || n.contains("chain")
            || n.equals("lightning_rod") || n.equals("end_rod");
    }

    static String opposite(String d) {
        return switch (d) {
            case "north" -> "south"; case "south" -> "north"; case "east" -> "west"; case "west" -> "east";
            case "up" -> "down"; default -> "up";
        };
    }

    // ------------------------------------------------------------------ whole builds

    /**
     * Rewrites box commands whose falling blocks would land on nothing: the plan is replayed on the real terrain
     * and any //set, //walls or //faces whose bottom row has air under it loses its sand/gravel/powder.
     * Returns the number of commands changed.
     */
    static int gravitySafe(List<String> commands, VoxelGrid grid) {
        int changed = 0;
        String p1 = null, p2 = null;
        for (int i = 0; i < commands.size(); i++) {
            String c = commands.get(i);
            String[] t = c.trim().substring(2).split(" ");
            String n = t[0].toLowerCase(Locale.ROOT);
            if (n.equals("pos1")) { p1 = c; continue; }
            if (n.equals("pos2")) { p2 = c; continue; }
            if ((n.equals("set") || n.equals("walls") || n.equals("faces") || n.equals("outline")) && t.length >= 2
                && p1 != null && p2 != null && hasGravity(t[1])) {
                int[] a = coords(p1), b = coords(p2);
                if (a != null && b != null && unsupportedBottom(grid, a, b, !n.equals("set") && !n.equals("faces"))) {
                    c = "//" + t[0] + " " + withoutGravity(t[1]);
                    commands.set(i, c);
                    changed++;
                }
            }
            if (p1 != null && p2 != null) grid.simulate(List.of(p1, p2, c));
            else grid.simulate(List.of(c));
        }
        return changed;
    }

    private static boolean unsupportedBottom(VoxelGrid g, int[] a, int[] b, boolean wallsOnly) {
        int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]), z1 = Math.min(a[2], b[2]), z2 = Math.max(a[2], b[2]);
        int y = Math.min(a[1], b[1]) - 1;
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                if (wallsOnly && x != x1 && x != x2 && z != z1 && z != z2) continue;
                if (!g.inside(x, y, z)) continue;
                if (fallsThrough(g.get(x, y, z))) return true;
            }
        return false;
    }

    private static int[] coords(String posCmd) {
        String[] t = posCmd.trim().split(" ");
        if (t.length < 2) return null;
        String[] v = t[1].split(",");
        if (v.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
