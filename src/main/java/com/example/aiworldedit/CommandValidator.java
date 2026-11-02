package com.example.aiworldedit;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client-side guardrail. Uses an allowlist of WorldEdit commands and simulates
 * the selection (pos1/pos2) so it can reject huge or far-away edits before
 * anything reaches the server.
 */
public final class CommandValidator {
    private CommandValidator() {}

    /** Commands that need a full pos1+pos2 selection. */
    private static final Set<String> REGION = Set.of(
        "set", "replace", "walls", "faces", "outline", "overlay", "naturalize",
        "smooth", "line", "center", "middle", "hollow", "flora", "stack", "move");

    /** Shape commands, centered on pos1 when //placement pos1 is active. */
    private static final Set<String> SHAPES = Set.of(
        "sphere", "hsphere", "cyl", "hcyl", "pyramid", "hpyramid");

    private static final Set<String> SELECTION = Set.of("pos1", "pos2");

    /** Commands that create a WorldEdit history entry (used by /aiundo). */
    private static final Set<String> EDITS;
    static {
        java.util.HashSet<String> s = new java.util.HashSet<>(REGION);
        s.addAll(SHAPES);
        s.add("paste");
        EDITS = Set.copyOf(s);
    }

    private static final Map<String, String> ALIASES = Map.of(
        "re", "replace", "rep", "replace", "middle", "center");

    private static final Set<String> DIRECTIONS = Set.of(
        "north", "south", "east", "west", "up", "down");

    public static final class Box {
        public int minX, minY, minZ, maxX, maxY, maxZ;
        private boolean empty = true;

        void include(int x1, int y1, int z1, int x2, int y2, int z2) {
            int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
            int ay = Math.min(y1, y2), by = Math.max(y1, y2);
            int az = Math.min(z1, z2), bz = Math.max(z1, z2);
            if (empty) {
                minX = ax; minY = ay; minZ = az; maxX = bx; maxY = by; maxZ = bz;
                empty = false;
            } else {
                minX = Math.min(minX, ax); minY = Math.min(minY, ay); minZ = Math.min(minZ, az);
                maxX = Math.max(maxX, bx); maxY = Math.max(maxY, by); maxZ = Math.max(maxZ, bz);
            }
        }

        public boolean isEmpty() { return empty; }

        /** {minX,minY,minZ,maxX,maxY,maxZ} grown by `pad` blocks sideways and `padY` vertically. */
        public int[] padded(int pad, int padY) {
            return new int[]{minX - pad, minY - padY, minZ - pad, maxX + pad, maxY + padY, maxZ + pad};
        }
        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }

        @Override
        public String toString() {
            if (empty) return "(empty)";
            return minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ
                + " (" + sizeX() + "x" + sizeY() + "x" + sizeZ() + ")";
        }
    }

    public record Result(List<String> commands, List<String> rejected, Box bounds, int editCount,
                         Map<String, String> blockFixes) {}

    /** All real block ids in the game (set on the client thread); null = don't check. */
    public static volatile Set<String> knownBlocks = null;

    /** Commands whose arguments include block patterns. */
    private static final Set<String> PATTERN_COMMANDS = Set.of(
        "set", "walls", "faces", "outline", "overlay", "center", "middle", "line", "hollow", "replace",
        "cyl", "hcyl", "sphere", "hsphere", "pyramid", "hpyramid");

    public static Result validate(List<String> input, BlockPos anchor) {
        Config cfg = Config.get();
        List<String> out = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Box bounds = new Box();
        int edits = 0;
        int[] pos1 = null, pos2 = null;
        String loaded = null; // library schematic loaded with //schem load

        Map<String, String> fixes = new java.util.LinkedHashMap<>();
        if (input == null) return new Result(out, rejected, bounds, 0, fixes);

        for (String raw : input) {
            if (out.size() >= cfg.maxCommands) {
                rejected.add("command limit (" + cfg.maxCommands + ") reached");
                break;
            }
            if (raw == null) continue;
            String c = raw.trim().replaceAll("\\s+", " ");
            if (c.isEmpty()) continue;

            String reason = null;
            String name = null;
            String[] tokens = null;

            if (c.length() > cfg.maxCommandLength) reason = "too long";
            else if (!c.startsWith("//") || c.startsWith("///")) reason = "not a // WorldEdit command";
            else {
                tokens = c.substring(2).split(" ");
                name = tokens[0].toLowerCase(Locale.ROOT);
                name = ALIASES.getOrDefault(name, name);
                String lower = c.toLowerCase(Locale.ROOT);
                if (lower.contains("<") || lower.contains("your_")) {
                    reason = "placeholder values";
                } else if (name.equals("schem") || name.equals("schematic")) {
                    // Only the mod's own prepared library placements may be loaded.
                    if (tokens.length != 3 || !tokens[1].equalsIgnoreCase("load") || !BuildLibrary.PLACED.containsKey(tokens[2])) {
                        reason = "only saved library builds can be loaded";
                    } else {
                        loaded = tokens[2];
                    }
                } else if (name.equals("paste")) {
                    BuildLibrary.Placement pl = loaded == null ? null : BuildLibrary.PLACED.get(loaded);
                    if (pl == null) reason = "nothing loaded to paste";
                    else if (!c.contains("-o")) reason = "library pastes must use -o";
                    else {
                        int[] b = pl.box();
                        long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
                        if (vol > cfg.maxSelectionVolume * 4L) reason = "saved build too big (" + vol + " blocks)";
                        else if (tooFar(new int[]{b[0], b[1], b[2]}, anchor, cfg) || tooFar(new int[]{b[3], b[4], b[5]}, anchor, cfg)) {
                            reason = "saved build reaches too far from the anchor";
                        } else bounds.include(b[0], b[1], b[2], b[3], b[4], b[5]);
                    }
                } else if (!SELECTION.contains(name) && !REGION.contains(name) && !SHAPES.contains(name)) {
                    reason = "command not allowed";
                }
            }

            if (reason == null && SELECTION.contains(name)) {
                int[] p = tokens.length >= 2 ? parseCoords(tokens[1]) : null;
                if (p == null) reason = "needs absolute X,Y,Z coordinates";
                else if (tooFar(p, anchor, cfg)) reason = "too far from the build anchor";
                else if (name.equals("pos1")) pos1 = p;
                else pos2 = p;
            } else if (reason == null && REGION.contains(name)) {
                if (pos1 == null || pos2 == null) {
                    reason = "no selection set before it";
                } else {
                    long vol = volume(pos1, pos2);
                    int factor = 1;
                    if (name.equals("stack") || name.equals("move")) {
                        factor = Math.max(1, firstInt(tokens, 1));
                        if (!hasDirection(tokens)) reason = "needs an explicit direction";
                        else if (name.equals("move") && factor > cfg.maxBuildDistance) reason = "moves too far";
                    }
                    if (name.equals("smooth") && firstInt(tokens, 1) > 10) reason = "too many smooth iterations";
                    if (reason == null && vol * factor > cfg.maxSelectionVolume) {
                        reason = "affects " + (vol * factor) + " blocks (limit " + cfg.maxSelectionVolume + ")";
                    }
                    if (reason == null) {
                        bounds.include(pos1[0], pos1[1], pos1[2], pos2[0], pos2[1], pos2[2]);
                        if (name.equals("stack")) includeStack(bounds, pos1, pos2, tokens, factor);
                    }
                }
            } else if (reason == null && SHAPES.contains(name)) {
                if (cfg.usePlacementPos1 && pos1 == null) {
                    reason = "shape has no //pos1 center";
                } else {
                    int[] dims = shapeDims(name, tokens);
                    if (dims == null) reason = "could not read shape size";
                    else if (dims[0] > cfg.maxShapeRadius || dims[2] > cfg.maxShapeRadius) reason = "radius too large";
                    else if (dims[1] > cfg.maxShapeHeight) reason = "too tall";
                    else if (pos1 != null) {
                        // Spheres extend below their center; cylinders and pyramids grow upward.
                        boolean sphere = name.endsWith("sphere");
                        int bottom = sphere ? pos1[1] - dims[1] : pos1[1];
                        int top = pos1[1] + dims[1];
                        bounds.include(pos1[0] - dims[0], bottom, pos1[2] - dims[2],
                            pos1[0] + dims[0], top, pos1[2] + dims[2]);
                    }
                }
            }

            if (reason != null) {
                rejected.add(c + "  [" + reason + "]");
                continue;
            }
            if (knownBlocks != null && PATTERN_COMMANDS.contains(name)) {
                tokens = fixBlocks(name, tokens, fixes);
                c = "//" + String.join(" ", tokens);
            }
            out.add(persistentLeaves(c, name, tokens));
            if (EDITS.contains(name)) edits++;
        }
        return new Result(out, rejected, bounds, edits, fixes);
    }

    // ---------- helpers ----------

    /** Replaces unknown block ids in the pattern/mask arguments with real ones. */
    private static String[] fixBlocks(String name, String[] tokens, Map<String, String> fixes) {
        String[] t = tokens.clone();
        int patternArgs = name.equals("replace") ? 2 : 1;
        int done = 0;
        for (int i = 1; i < t.length && done < patternArgs; i++) {
            String a = t[i];
            if (a.startsWith("-")) continue;                       // flags
            if (a.matches("-?[0-9.]+(,-?[0-9.]+)*")) {             // numbers (radius, height, thickness)
                if (name.equals("hollow") || name.equals("line")) continue;
                if (done > 0) break;
                continue;
            }
            BlockFixer.Fixed f = BlockFixer.fix(a, knownBlocks);
            t[i] = f.pattern();
            fixes.putAll(f.changes());
            done++;
        }
        return t;
    }

    private static final java.util.regex.Pattern LEAVES =
        java.util.regex.Pattern.compile("([a-z0-9_:]*_leaves)(?!\\[)");

    /**
     * Leaves placed by WorldEdit decay unless persistent=true. Adds it to the PATTERN arguments
     * (not to a //replace mask, which must still match natural leaves).
     */
    private static String persistentLeaves(String c, String name, String[] tokens) {
        if (tokens == null || !c.contains("_leaves")) return c;
        StringBuilder sb = new StringBuilder("//").append(tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            boolean isMask = name.equals("replace") && i == 1 && tokens.length >= 3;
            String t = isMask ? tokens[i] : LEAVES.matcher(tokens[i]).replaceAll("$1[persistent=true]");
            sb.append(' ').append(t);
        }
        return sb.toString();
    }

    private static int[] parseCoords(String s) {
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new int[]{
                (int) Math.floor(Double.parseDouble(p[0])),
                (int) Math.floor(Double.parseDouble(p[1])),
                (int) Math.floor(Double.parseDouble(p[2]))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean tooFar(int[] p, BlockPos a, Config cfg) {
        int d = cfg.maxBuildDistance;
        return Math.abs(p[0] - a.getX()) > d || Math.abs(p[1] - a.getY()) > d
            || Math.abs(p[2] - a.getZ()) > d || p[1] < -64 || p[1] > 320;
    }

    private static long volume(int[] a, int[] b) {
        return (long) (Math.abs(a[0] - b[0]) + 1) * (Math.abs(a[1] - b[1]) + 1) * (Math.abs(a[2] - b[2]) + 1);
    }

    /** First integer argument at or after index `from`, ignoring flags. 1 if none. */
    private static int firstInt(String[] tokens, int from) {
        for (int i = from; i < tokens.length; i++) {
            if (tokens[i].startsWith("-")) continue;
            try {
                return Integer.parseInt(tokens[i]);
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        return 1;
    }

    private static boolean hasDirection(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            if (DIRECTIONS.contains(tokens[i].toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static void includeStack(Box b, int[] p1, int[] p2, String[] tokens, int count) {
        int sx = Math.abs(p1[0] - p2[0]) + 1, sy = Math.abs(p1[1] - p2[1]) + 1, sz = Math.abs(p1[2] - p2[2]) + 1;
        String dir = "";
        for (int i = 1; i < tokens.length; i++) {
            if (DIRECTIONS.contains(tokens[i].toLowerCase(Locale.ROOT))) dir = tokens[i].toLowerCase(Locale.ROOT);
        }
        int dx = 0, dy = 0, dz = 0;
        switch (dir) {
            case "east" -> dx = sx * count;
            case "west" -> dx = -sx * count;
            case "south" -> dz = sz * count;
            case "north" -> dz = -sz * count;
            case "up" -> dy = sy * count;
            case "down" -> dy = -sy * count;
            default -> { }
        }
        b.include(p1[0] + dx, p1[1] + dy, p1[2] + dz, p2[0] + dx, p2[1] + dy, p2[2] + dz);
    }

    /**
     * Returns {radiusX, height(or radiusY), radiusZ} for a shape command, or null.
     * //sphere <pattern> <r>[,ry,rz]   //cyl <pattern> <r>[,rz] [height]   //pyramid <pattern> <size>
     */
    private static int[] shapeDims(String name, String[] tokens) {
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) args.add(tokens[i]);
        }
        if (args.size() < 2) return null; // pattern + size
        try {
            String[] r = args.get(1).split(",");
            double r0 = Double.parseDouble(r[0]);
            if (name.endsWith("sphere")) {
                double ry = r.length >= 3 ? Double.parseDouble(r[1]) : r0;
                double rz = r.length >= 3 ? Double.parseDouble(r[2]) : r0;
                return new int[]{(int) Math.ceil(r0), (int) Math.ceil(ry), (int) Math.ceil(rz)};
            }
            if (name.endsWith("cyl")) {
                double rz = r.length >= 2 ? Double.parseDouble(r[1]) : r0;
                int h = args.size() >= 3 ? Integer.parseInt(args.get(2)) : 1;
                return new int[]{(int) Math.ceil(r0), Math.abs(h), (int) Math.ceil(rz)};
            }
            int size = (int) Math.ceil(r0); // pyramid
            return new int[]{size, size, size};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isEdit(String commandWithSlashes) {
        String c = commandWithSlashes.startsWith("//") ? commandWithSlashes.substring(2) : commandWithSlashes;
        String name = c.split(" ")[0].toLowerCase(Locale.ROOT);
        return EDITS.contains(ALIASES.getOrDefault(name, name));
    }
}
