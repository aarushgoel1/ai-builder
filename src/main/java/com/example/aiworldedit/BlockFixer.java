package com.example.aiworldedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks every block id in a WorldEdit pattern against the game's real block list and
 * fixes the usual AI mistakes (stone_brick -> stone_bricks, oak_plank -> oak_planks,
 * renamed blocks, invented names), so WorldEdit doesn't reject the command.
 */
public final class BlockFixer {
    private BlockFixer() {}

    /** Common wrong names models use, mapped to real ids (checked against the registry before use). */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("grass", "short_grass"), Map.entry("chain", "iron_chain"), Map.entry("log", "oak_log"),
        Map.entry("wood", "oak_planks"), Map.entry("planks", "oak_planks"), Map.entry("plank", "oak_planks"),
        Map.entry("wooden_planks", "oak_planks"), Map.entry("wood_planks", "oak_planks"),
        Map.entry("brick", "bricks"), Map.entry("stonebrick", "stone_bricks"), Map.entry("stone_brick", "stone_bricks"),
        Map.entry("cobble", "cobblestone"), Map.entry("mossy_cobble", "mossy_cobblestone"),
        Map.entry("glass_window", "glass_pane"), Map.entry("window", "glass_pane"), Map.entry("lamp", "lantern"),
        Map.entry("leaves", "oak_leaves"), Map.entry("fence", "oak_fence"), Map.entry("door", "oak_door"),
        Map.entry("stairs", "oak_stairs"), Map.entry("slab", "oak_slab"), Map.entry("concrete", "white_concrete"),
        Map.entry("wool", "white_wool"), Map.entry("terracotta_white", "white_terracotta"),
        Map.entry("quartz", "quartz_block"), Map.entry("purpur", "purpur_block"), Map.entry("snow_layer", "snow"),
        Map.entry("water_source", "water"), Map.entry("lava_source", "lava"), Map.entry("path", "dirt_path"),
        Map.entry("grass_path", "dirt_path"), Map.entry("magma", "magma_block"), Map.entry("hay", "hay_block"),
        Map.entry("slime", "slime_block"), Map.entry("honey", "honey_block"), Map.entry("nether_brick", "nether_bricks"),
        Map.entry("end_stone_brick", "end_stone_bricks"), Map.entry("mud_brick", "mud_bricks"),
        Map.entry("deepslate_brick", "deepslate_bricks"), Map.entry("deepslate_tile", "deepslate_tiles"),
        Map.entry("stone_tiles", "stone_bricks"), Map.entry("sea_lantern_block", "sea_lantern"));

    /** Result of fixing one pattern. */
    public record Fixed(String pattern, Map<String, String> changes) {}

    /**
     * Fixes a pattern or mask such as "70%stone_brick,30%cobblestone" or "oak_stairs[facing=north]".
     * Unknown ids get the closest real block; an entry that can't be matched is dropped from a
     * weighted mix (or replaced with stone if it was the only block).
     */
    public static Fixed fix(String pattern, Set<String> known) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (known == null || known.isEmpty() || pattern == null) return new Fixed(pattern, changes);
        List<String> parts = VoxelGrid.splitPattern(pattern);
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String weight = "";
            int pct = p.indexOf('%');
            int br = p.indexOf('[');
            if (pct > 0 && (br < 0 || pct < br)) {
                weight = p.substring(0, pct + 1);
                p = p.substring(pct + 1);
            }
            String prefix = "";
            if (p.startsWith("!")) { prefix = "!"; p = p.substring(1); }
            // Tags (#...), random-state (*...) and other WorldEdit specials are left alone.
            if (p.startsWith("#") || p.startsWith("*") || p.startsWith("^") || p.startsWith("$")) {
                out.add(weight + prefix + p);
                continue;
            }
            String states = "";
            br = p.indexOf('[');
            if (br >= 0) { states = p.substring(br); p = p.substring(0, br); }
            String id = p.toLowerCase(Locale.ROOT);
            if (id.startsWith("minecraft:")) id = id.substring(10);

            String real = resolve(id, known);
            if (real == null) {
                changes.put(id, "(removed)");
                continue;
            }
            if (!real.equals(id)) {
                changes.put(id, real);
                // Keep states for simple spelling fixes (spruce_stair -> spruce_stairs), drop them otherwise.
                if (!(real.startsWith(id) || id.startsWith(real))) states = "";
            }
            out.add(weight + prefix + real + states);
        }
        if (out.isEmpty()) {
            changes.put(pattern, "stone");
            out.add("stone");
        }
        // A mix that lost all its weights except one still works; WorldEdit treats missing weights as 1.
        return new Fixed(String.join(",", out), changes);
    }

    /** The real block id for `id`, or null if nothing reasonable matches. */
    static String resolve(String id, Set<String> known) {
        if (known.contains(id)) return id;
        String alias = ALIASES.get(id);
        if (alias != null && known.contains(alias)) return alias;
        // Plural / singular slips: stone_brick -> stone_bricks, oak_plank -> oak_planks, tiles -> tile...
        for (String c : new String[]{id + "s", id + "es", id.endsWith("s") ? id.substring(0, id.length() - 1) : null,
                id.replace("brick_", "bricks_"), id.replace("bricks_", "brick_"), id.replace("plank_", "planks_"),
                id.replace("_block", ""), id + "_block", id.replace("wooden_", "oak_"), id.replace("wood_", "oak_")}) {
            if (c != null && known.contains(c)) return c;
        }
        // Closest real id: most shared words, then smallest edit distance.
        String[] words = id.split("_");
        String best = null;
        double bestScore = 0;
        int bestDist = Integer.MAX_VALUE;
        for (String k : known) {
            double overlap = overlap(words, k.split("_"));
            int dist = overlap > 0 || Math.abs(k.length() - id.length()) <= 3 ? levenshtein(id, k) : Integer.MAX_VALUE;
            if (overlap > bestScore || (overlap == bestScore && overlap > 0 && dist < bestDist)
                    || (overlap == 0 && bestScore == 0 && dist <= 2 && dist < bestDist)) {
                best = k;
                bestScore = overlap;
                bestDist = dist;
            }
        }
        if (best == null) return null;
        if (bestScore >= 0.5 || bestDist <= 2) return best;
        return null;
    }

    /** Fraction of shared words (Jaccard). */
    private static double overlap(String[] a, String[] b) {
        int shared = 0;
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y) || (x.length() > 3 && (x.equals(y + "s") || y.equals(x + "s")))) { shared++; break; }
            }
        }
        int union = a.length + b.length - shared;
        return union == 0 ? 0 : (double) shared / union;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
