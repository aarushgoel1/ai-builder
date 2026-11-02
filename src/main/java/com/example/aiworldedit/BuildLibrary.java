package com.example.aiworldedit;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Saved builds the AI (and /ailib) can place whole: .litematic and .schem files in
 * config/aiworldedit/library. Placing one writes a ready-rotated .schem into WorldEdit's
 * schematics folder and pastes it with "//schem load" + "//paste -o -a", so a 10,000-block
 * build is one WorldEdit edit (undoable with /aiundo).
 */
public final class BuildLibrary {
    private BuildLibrary() {}

    /** Minecraft 1.21.11 data version, written into the .schem files for WorldEdit. */
    public static int dataVersion = 4671;
    /** The game's blocks and their state properties (set by the mod at startup); null = don't check. */
    public static volatile java.util.function.Supplier<Map<String, Map<String, java.util.Set<String>>>> stateTableSource = null;
    private static Map<String, Map<String, java.util.Set<String>>> stateTable = null;
    /** Blocks that were renamed or dropped by the last placement, for the chat report. */
    public static final Map<String, String> lastFixes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Overrides the config folder (tests). */
    public static Path configDirOverride = null;

    private static final String[] SIDES = {"north", "east", "south", "west"}; // clockwise

    /** One saved build, blocks stored as palette indexes, index = (y * d + z) * w + x. */
    public static final class Entry {
        public String name, file, title = "", author = "", description = "";
        /** From the subfolders it sits in, e.g. ["medieval", "house"]. */
        public List<String> tags = List.of();
        public int w, h, d;
        public String[] palette;
        public int[] cells;
        public boolean[] isAir;
        /** Side of the build that faces the street (schematic directions). */
        public String front = "south";
        /** Schematic layer that sits at ground level (it replaces the top terrain block). */
        public int groundLayer = 0;
        public int blockCount;
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public int dataVersion;

        int at(int x, int y, int z) { return cells[(y * d + z) * w + x]; }

        /**
         * Part of a big, simpler "crowd" set (in a folder named crowd, e.g. the CraftAssist houses): used as an
         * extra example only when its parts match the request, and never turned into a palette.
         */
        public boolean crowd() { return tags.contains("crowd"); }

        /** Footprint as the player will see it (width across the front, depth away from it). */
        public int[] localSize(int extraQuarterTurns) {
            String f = SIDES[Math.floorMod(sideIndex(front) + extraQuarterTurns, 4)];
            boolean turned = f.equals("east") || f.equals("west");
            return turned ? new int[]{d, h, w} : new int[]{w, h, d};
        }

        /** One line for the AI and /ailib. */
        public String summary() {
            int[] s = localSize(0);
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(tags.isEmpty() ? "" : " [" + String.join(" ", tags) + "]").append(": ").append(s[0]).append(" wide x ").append(s[2]).append(" deep x ")
                .append(s[1]).append(" tall, ").append(blockCount).append(" blocks");
            String about = description.isBlank() ? title : description;
            if (!about.isBlank()) sb.append(" - ").append(about.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").trim());
            sb.append(". Materials: ").append(materials(8));
            return sb.toString();
        }

        /** Most used blocks with shares, e.g. "spruce_planks 24%, tuff_bricks 8%". */
        public String materials(int n) {
            List<String> out = new ArrayList<>();
            counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
                .forEach(en -> out.add(en.getKey() + " " + Math.round(100.0 * en.getValue() / Math.max(1, blockCount)) + "%"));
            return String.join(", ", out);
        }
    }

    private static final Map<String, Entry> ENTRIES = new TreeMap<>();

    public static Path configDir() {
        return configDirOverride != null ? configDirOverride : FabricLoader.getInstance().getConfigDir();
    }

    public static Path folder() {
        String custom = Config.get().libraryFolder;
        if (custom != null && !custom.isBlank()) {
            String c = custom.trim();
            if (c.startsWith("~")) c = System.getProperty("user.home") + c.substring(1);
            return Path.of(c);
        }
        return configDir().resolve("aiworldedit").resolve("library");
    }

    /** WorldEdit's schematics folder (singleplayer / the integrated server). */
    public static Path worldEditFolder() {
        return configDir().resolve("worldedit").resolve("schematics");
    }

    public static synchronized List<Entry> all() {
        return new ArrayList<>(ENTRIES.values());
    }

    /** Finds a build by name: exact, then prefix, then containing. */
    public static synchronized Entry get(String name) {
        if (name == null) return null;
        String n = slug(name);
        if (ENTRIES.containsKey(n)) return ENTRIES.get(n);
        for (Entry e : ENTRIES.values()) if (e.name.startsWith(n)) return e;
        for (Entry e : ENTRIES.values()) if (e.name.contains(n) || n.contains(e.name)) return e;
        return null;
    }

    /** (Re)loads every file in the library folder. Returns messages about what loaded or failed. */
    public static synchronized List<String> load() {
        List<String> msgs = new ArrayList<>();
        ENTRIES.clear();
        Path dir = folder();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            msgs.add("Can't create " + dir + ": " + e.getMessage());
            return msgs;
        }
        try (Stream<Path> files = Files.walk(dir, 4)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                String fn = p.getFileName().toString();
                String lower = fn.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic"))) continue;
                Path rel = dir.relativize(p);
                List<String> tags = new ArrayList<>();
                boolean skip = false;
                for (int i = 0; i < rel.getNameCount() - 1; i++) {
                    String part = rel.getName(i).toString();
                    if (part.startsWith("_") || part.startsWith(".")) skip = true;
                    tags.add(part.toLowerCase(Locale.ROOT));
                }
                if (skip) continue; // _set-aside and similar folders
                try (InputStream in = Files.newInputStream(p)) {
                    Entry e = read(in, fn);
                    e.tags = List.copyOf(tags);
                    if (ENTRIES.containsKey(e.name) && !tags.isEmpty()) e.name = e.name + "-" + slug(String.join("-", tags));
                    ENTRIES.put(e.name, e);
                    msgs.add("Loaded " + e.name + " (" + e.localSize(0)[0] + "x" + e.localSize(0)[2] + ", " + e.blockCount + " blocks)");
                } catch (Exception ex) {
                    msgs.add("Skipped " + fn + ": " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            msgs.add("Can't read " + dir + ": " + e.getMessage());
        }
        // Learn from them: measurements for the AI and a palette for the style presets.
        BuildStudy.clearCache();
        List<BuildStudy.Digest> digests = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (e.crowd()) continue; // big crowd-built sets: studied only when picked, never turned into palettes
            try {
                digests.add(BuildStudy.digest(e));
            } catch (Exception ex) {
                msgs.add("Couldn't study " + e.name + ": " + ex);
            }
        }
        StylePresets.setLearned(digests);
        return msgs;
    }

    /** Adds a build directly (tests). */
    public static synchronized void put(Entry e) {
        ENTRIES.put(e.name, e);
    }

    // ---------------- reading ----------------

    public static Entry read(InputStream in, String fileName) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        Entry e = new Entry();
        e.file = fileName;
        e.name = slug(fileName.replaceAll("\\.[A-Za-z]+$", ""));
        if (root.containsKey("Regions")) readLitematic(root, e);
        else readSponge(root, e);
        finish(e);
        return e;
    }

    private static void readLitematic(Map<String, Object> root, Entry e) throws IOException {
        Map<String, Object> meta = Nbt.map(root.get("Metadata"));
        e.title = String.valueOf(meta.getOrDefault("Name", ""));
        e.author = String.valueOf(meta.getOrDefault("Author", ""));
        e.description = String.valueOf(meta.getOrDefault("Description", ""));
        e.dataVersion = Nbt.num(root.get("MinecraftDataVersion"), 0);
        Map<String, Object> regions = Nbt.map(root.get("Regions"));
        if (regions.isEmpty()) throw new IOException("no regions");
        // Bounding box of all regions (sizes may be negative: the region grows from Position the other way).
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        List<int[]> boxes = new ArrayList<>();
        for (Object ro : regions.values()) {
            Map<String, Object> r = Nbt.map(ro);
            Map<String, Object> pos = Nbt.map(r.get("Position")), size = Nbt.map(r.get("Size"));
            int[] p = {Nbt.num(pos.get("x"), 0), Nbt.num(pos.get("y"), 0), Nbt.num(pos.get("z"), 0)};
            int[] s = {Nbt.num(size.get("x"), 0), Nbt.num(size.get("y"), 0), Nbt.num(size.get("z"), 0)};
            int[] b = new int[6];
            for (int a = 0; a < 3; a++) {
                int lo = s[a] >= 0 ? p[a] : p[a] + s[a] + 1;
                b[a] = lo;
                b[a + 3] = Math.abs(s[a]);
            }
            boxes.add(b);
            minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]); minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[0] + b[3] - 1); maxY = Math.max(maxY, b[1] + b[4] - 1); maxZ = Math.max(maxZ, b[2] + b[5] - 1);
        }
        e.w = maxX - minX + 1; e.h = maxY - minY + 1; e.d = maxZ - minZ + 1;
        checkSize(e);
        Map<String, Integer> pal = new LinkedHashMap<>();
        pal.put("air", 0);
        e.cells = new int[e.w * e.h * e.d];
        int ri = 0;
        for (Object ro : regions.values()) {
            Map<String, Object> r = Nbt.map(ro);
            int[] b = boxes.get(ri++);
            List<Object> rp = Nbt.list(r.get("BlockStatePalette"));
            int[] remap = new int[rp.size()];
            for (int i = 0; i < rp.size(); i++) remap[i] = pal.computeIfAbsent(stateString(Nbt.map(rp.get(i))), k -> pal.size());
            Object bs = r.get("BlockStates");
            if (!(bs instanceof long[] longs)) continue;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, rp.size() - 1)));
            long mask = (1L << bits) - 1;
            int sx = b[3], sy = b[4], sz = b[5];
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        long i = ((long) y * sz + z) * sx + x;
                        long startBit = i * bits;
                        int startLong = (int) (startBit >>> 6), endLong = (int) ((startBit + bits - 1) >>> 6);
                        int off = (int) (startBit & 63);
                        if (endLong >= longs.length) continue;
                        long v = startLong == endLong ? (longs[startLong] >>> off) & mask
                            : ((longs[startLong] >>> off) | (longs[endLong] << (64 - off))) & mask;
                        if (v >= remap.length) continue;
                        int idx = remap[(int) v];
                        if (idx == 0) continue;
                        int gx = b[0] - minX + x, gy = b[1] - minY + y, gz = b[2] - minZ + z;
                        e.cells[(gy * e.d + gz) * e.w + gx] = idx;
                    }
                }
            }
        }
        e.palette = pal.keySet().toArray(new String[0]);
    }

    private static void readSponge(Map<String, Object> root, Entry e) throws IOException {
        Map<String, Object> s = root.containsKey("Schematic") ? Nbt.map(root.get("Schematic")) : root;
        if (s.containsKey("Blocks") && !(s.get("Blocks") instanceof byte[])) {
            // Sponge v3
            Map<String, Object> blocks = Nbt.map(s.get("Blocks"));
            readSpongeBlocks(s, e, Nbt.map(blocks.get("Palette")), blocks.get("Data"));
        } else if (s.containsKey("Palette")) {
            readSpongeBlocks(s, e, Nbt.map(s.get("Palette")), s.get("BlockData")); // v1/v2
        } else {
            throw new IOException("old MCEdit .schematic files (number block ids) aren't supported; re-save it as .schem or .litematic");
        }
        e.dataVersion = Nbt.num(s.get("DataVersion"), 0);
        Map<String, Object> meta = Nbt.map(s.get("Metadata"));
        if (meta.containsKey("Name")) e.title = String.valueOf(meta.get("Name"));
        if (meta.containsKey("Author")) e.author = String.valueOf(meta.get("Author"));
        if (meta.containsKey("Description")) e.description = String.valueOf(meta.get("Description"));
    }

    private static void readSpongeBlocks(Map<String, Object> s, Entry e, Map<String, Object> palette, Object data) throws IOException {
        e.w = Nbt.num(s.get("Width"), 0) & 0xFFFF;
        e.h = Nbt.num(s.get("Height"), 0) & 0xFFFF;
        e.d = Nbt.num(s.get("Length"), 0) & 0xFFFF;
        checkSize(e);
        if (!(data instanceof byte[] bytes)) throw new IOException("no block data");
        int maxIdx = 0;
        for (Object v : palette.values()) maxIdx = Math.max(maxIdx, Nbt.num(v, 0));
        String[] src = new String[maxIdx + 1];
        for (Map.Entry<String, Object> en : palette.entrySet()) src[Nbt.num(en.getValue(), 0)] = clean(en.getKey());
        Map<String, Integer> pal = new LinkedHashMap<>();
        pal.put("air", 0);
        int[] remap = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            String st = src[i] == null ? "air" : src[i];
            remap[i] = VoxelGrid.isAir(VoxelGrid.baseId(st)) ? 0 : pal.computeIfAbsent(st, k -> pal.size());
        }
        int[] raw = Nbt.readVarints(bytes, e.w * e.h * e.d);
        e.cells = new int[raw.length];
        for (int i = 0; i < raw.length; i++) e.cells[i] = raw[i] < remap.length ? remap[raw[i]] : 0;
        // Sponge order is (y * length + z) * width + x, the same as ours.
        e.palette = pal.keySet().toArray(new String[0]);
    }

    private static void checkSize(Entry e) throws IOException {
        long vol = (long) e.w * e.h * e.d;
        if (e.w <= 0 || e.h <= 0 || e.d <= 0) throw new IOException("empty build");
        if (vol > 4_000_000) throw new IOException("too big (" + e.w + "x" + e.h + "x" + e.d + ")");
    }

    private static String stateString(Map<String, Object> c) {
        String name = clean(String.valueOf(c.getOrDefault("Name", "minecraft:air")));
        Map<String, Object> props = Nbt.map(c.get("Properties"));
        if (props.isEmpty()) return VoxelGrid.isAir(name) ? "air" : name;
        TreeMap<String, String> sorted = new TreeMap<>();
        props.forEach((k, v) -> sorted.put(k, String.valueOf(v)));
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (var en : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append(en.getKey()).append('=').append(en.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String clean(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return t.isEmpty() ? "build" : t;
    }

    /** Counts, air flags, front side and ground layer. */
    private static void finish(Entry e) {
        e.isAir = new boolean[e.palette.length];
        Map<String, Integer> counts = new HashMap<>();
        int[] perPal = new int[e.palette.length];
        for (int c : e.cells) perPal[c]++;
        for (int i = 0; i < e.palette.length; i++) {
            String base = VoxelGrid.baseId(e.palette[i]);
            e.isAir[i] = VoxelGrid.isAir(base) || base.equals("structure_void");
            if (!e.isAir[i]) {
                counts.merge(base, perPal[i], Integer::sum);
                e.blockCount += perPal[i];
            }
        }
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(en -> e.counts.put(en.getKey(), en.getValue()));

        // Doors: the side nearest a door is the front; the floor under the lowest door is ground level.
        int bestDist = Integer.MAX_VALUE, doorY = -1;
        String bestSide = null;
        for (int y = 0; y < e.h; y++) {
            for (int z = 0; z < e.d; z++) {
                for (int x = 0; x < e.w; x++) {
                    String st = e.palette[e.at(x, y, z)];
                    if (!st.contains("_door[") || !st.contains("half=lower")) continue;
                    if (doorY < 0 || y < doorY) doorY = y;
                    int[] dist = {z, e.w - 1 - x, e.d - 1 - z, x}; // north, east, south, west
                    for (int k = 0; k < 4; k++) {
                        if (dist[k] < bestDist || (dist[k] == bestDist && k == 2)) { bestDist = dist[k]; bestSide = SIDES[k]; }
                    }
                }
            }
        }
        if (bestSide != null && bestDist <= Math.min(e.w, e.d) / 3) e.front = bestSide;
        else e.front = e.w >= e.d ? "south" : "east"; // no clear entrance: show the long side
        if (doorY > 0) {
            e.groundLayer = doorY - 1;
        } else {
            int area = e.w * e.d;
            for (int y = 0; y < e.h; y++) {
                int filled = 0;
                for (int z = 0; z < e.d; z++) for (int x = 0; x < e.w; x++) if (!e.isAir[e.at(x, y, z)]) filled++;
                if (filled >= area * 0.35) { e.groundLayer = y; break; }
            }
        }
    }

    // ---------------- placing ----------------

    /** A prepared placement: the .schem file name to load and every non-air block in world coordinates. */
    public record Placement(String schemName, List<int[]> positions, List<String> blocks, int[] box) {}

    /** Placements prepared this session, by schematic name (used by the simulator and the validator). */
    public static final Map<String, Placement> PLACED = new java.util.concurrent.ConcurrentHashMap<>();
    private static int placeCounter = 0;

    static int sideIndex(String s) {
        for (int i = 0; i < 4; i++) if (SIDES[i].equals(s)) return i;
        return 2;
    }

    /**
     * Places entry e with its front toward the player: local x from x1 (left) across the front,
     * local z from z1 (front) away from the player, its ground layer at local y = groundY.
     * extraTurns rotates it a further 90 degrees clockwise per step. Writes the .schem for WorldEdit.
     */
    public static Placement prepare(Entry e, BuildPlanner.Frame frame, int x1, int z1, int groundY, int extraTurns) {
        String front = SIDES[Math.floorMod(sideIndex(e.front) + extraTurns, 4)];
        int[] ls = e.localSize(extraTurns);
        int lw = ls[0], ld = ls[2];
        // Schematic (sx, sz) -> local offset (u, v): read the schematic as if standing in front of it,
        // facing opposite to its front side (local x = that viewer's right, z = forward).
        int[] fv = switch (front) { case "north" -> new int[]{0, 1}; case "east" -> new int[]{-1, 0};
            case "west" -> new int[]{1, 0}; default -> new int[]{0, -1}; };
        int fx = fv[0], fz = fv[1], rx = -fz, rz = fx;
        int uOff = rx < 0 ? e.w - 1 : rz < 0 ? e.d - 1 : 0, vOff = fx < 0 ? e.w - 1 : fz < 0 ? e.d - 1 : 0;
        java.util.function.IntBinaryOperator U = (sx, sz) -> sx * rx + sz * rz + uOff;
        java.util.function.IntBinaryOperator V = (sx, sz) -> sx * fx + sz * fz + vOff;
        // Where each schematic direction ends up in the world.
        Map<String, String> dirMap = new HashMap<>();
        int[][] vec = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // north east south west as (x, z)
        for (int k = 0; k < 4; k++) {
            int du = U.applyAsInt(vec[k][0], vec[k][1]) - U.applyAsInt(0, 0);
            int dv = V.applyAsInt(vec[k][0], vec[k][1]) - V.applyAsInt(0, 0);
            String local = du > 0 ? "+x" : du < 0 ? "-x" : dv > 0 ? "+z" : "-z";
            dirMap.put(SIDES[k], frame.dir(local));
        }
        int quarter = Math.floorMod(sideIndex(dirMap.get("north")) - 0, 4); // clockwise turns schematic -> world
        Map<String, String> stateCache = new HashMap<>();

        List<int[]> pos = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int y = 0; y < e.h; y++) {
            int wy = frame.wy(groundY + y - e.groundLayer);
            for (int sz = 0; sz < e.d; sz++) {
                for (int sx = 0; sx < e.w; sx++) {
                    int idx = e.at(sx, y, sz);
                    if (e.isAir[idx]) continue;
                    int lx = x1 + U.applyAsInt(sx, sz), lz = z1 + V.applyAsInt(sx, sz);
                    int wx = frame.wx(lx, lz), wz = frame.wz(lx, lz);
                    pos.add(new int[]{wx, wy, wz});
                    String st0 = stateCache.computeIfAbsent(e.palette[idx], st -> sanitize(rotateState(st, dirMap, quarter)));
                    if (st0.equals("air")) { pos.remove(pos.size() - 1); continue; }
                    blocks.add(st0);
                    box[0] = Math.min(box[0], wx); box[1] = Math.min(box[1], wy); box[2] = Math.min(box[2], wz);
                    box[3] = Math.max(box[3], wx); box[4] = Math.max(box[4], wy); box[5] = Math.max(box[5], wz);
                }
            }
        }
        if (pos.isEmpty()) box = new int[]{frame.anchor.getX(), frame.anchor.getY(), frame.anchor.getZ(),
            frame.anchor.getX(), frame.anchor.getY(), frame.anchor.getZ()};
        String schemName;
        synchronized (BuildLibrary.class) {
            schemName = "aiwe_" + e.name.replace('-', '_') + "_" + (++placeCounter);
        }
        Placement p = new Placement(schemName, pos, blocks, box);
        PLACED.put(schemName, p);
        try {
            writeSchem(p);
        } catch (Exception ex) {
            System.err.println("[AI WorldEdit] Couldn't write " + schemName + ": " + ex);
        }
        return p;
    }

    /**
     * Makes a block state valid for this game version: unknown blocks become the closest real block
     * (or are left out), unknown properties or values are dropped (WorldEdit would reject the file).
     */
    static String sanitize(String st) {
        Map<String, Map<String, java.util.Set<String>>> table;
        synchronized (BuildLibrary.class) {
            if (stateTable == null && stateTableSource != null) {
                try { stateTable = stateTableSource.get(); } catch (Throwable t) { stateTable = Map.of(); }
            }
            table = stateTable;
        }
        if (table == null || table.isEmpty()) return st;
        int br = st.indexOf('[');
        String id = br < 0 ? st : st.substring(0, br);
        Map<String, java.util.Set<String>> props = table.get(id);
        if (props == null) {
            String real = BlockFixer.resolve(id, table.keySet());
            lastFixes.put(id, real == null ? "(left out)" : real);
            if (real == null) return "air";
            id = real;
            props = table.get(real);
        }
        if (br < 0 || props == null || props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean any = false;
        for (String p : st.substring(br + 1, st.length() - 1).split(",")) {
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            java.util.Set<String> allowed = props.get(p.substring(0, eq));
            if (allowed == null || !allowed.contains(p.substring(eq + 1))) continue;
            if (any) sb.append(',');
            sb.append(p);
            any = true;
        }
        return any ? sb.append(']').toString() : id;
    }

    /** Rotates facing / axis / rotation / side-connection states. */
    static String rotateState(String st, Map<String, String> dirMap, int quarter) {
        int br = st.indexOf('[');
        if (br < 0) return st;
        String id = st.substring(0, br);
        String[] props = st.substring(br + 1, st.length() - 1).split(",");
        TreeMap<String, String> out = new TreeMap<>();
        for (String p : props) {
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            String k = p.substring(0, eq), v = p.substring(eq + 1);
            if (dirMap.containsKey(k)) { out.put(dirMap.get(k), v); continue; }        // fences, walls, vines...
            switch (k) {
                case "facing", "horizontal_facing" -> v = dirMap.getOrDefault(v, v);
                case "axis" -> { if (quarter % 2 == 1 && (v.equals("x") || v.equals("z"))) v = v.equals("x") ? "z" : "x"; }
                case "rotation" -> { try { v = String.valueOf(Math.floorMod(Integer.parseInt(v) + 4 * quarter, 16)); } catch (NumberFormatException ignored) { } }
                case "orientation" -> { String[] a = v.split("_"); if (a.length == 2) v = dirMap.getOrDefault(a[0], a[0]) + "_" + dirMap.getOrDefault(a[1], a[1]); }
                case "shape" -> { if (id.contains("rail")) v = railShape(v, dirMap); }
                default -> { }
            }
            out.put(k, v);
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var en : out.entrySet()) {
            if (!first) sb.append(',');
            sb.append(en.getKey()).append('=').append(en.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String railShape(String v, Map<String, String> dirMap) {
        if (v.startsWith("ascending_")) return "ascending_" + dirMap.getOrDefault(v.substring(10), v.substring(10));
        String[] a = v.split("_");
        if (a.length != 2) return v;
        String p = dirMap.getOrDefault(a[0], a[0]), q = dirMap.getOrDefault(a[1], a[1]);
        boolean ns = (p.equals("north") || p.equals("south")) && (q.equals("north") || q.equals("south"));
        boolean ew = (p.equals("east") || p.equals("west")) && (q.equals("east") || q.equals("west"));
        if (ns) return "north_south";
        if (ew) return "east_west";
        String nsPart = p.equals("north") || p.equals("south") ? p : q, ewPart = nsPart.equals(p) ? q : p;
        return nsPart + "_" + ewPart;
    }

    /** Sponge v3 .schem with absolute coordinates, so "//paste -o" puts it exactly in place. */
    static void writeSchem(Placement p) throws IOException {
        int[] b = p.box();
        int w = b[3] - b[0] + 1, h = b[4] - b[1] + 1, l = b[5] - b[2] + 1;
        Map<String, Integer> pal = new LinkedHashMap<>();
        pal.put("minecraft:air", 0);
        int[] data = new int[w * h * l];
        for (int i = 0; i < p.positions().size(); i++) {
            int[] q = p.positions().get(i);
            int id = pal.computeIfAbsent("minecraft:" + p.blocks().get(i), k -> pal.size());
            data[((q[1] - b[1]) * l + (q[2] - b[2])) * w + (q[0] - b[0])] = id;
        }
        Map<String, Object> palette = new LinkedHashMap<>();
        pal.forEach((k, v) -> palette.put(k, v));
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        blocks.put("Data", Nbt.varints(data));
        Map<String, Object> we = new LinkedHashMap<>();
        we.put("Origin", new int[]{b[0], b[1], b[2]});
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("WorldEdit", we);
        meta.put("Name", p.schemName());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Version", 3);
        s.put("DataVersion", dataVersion);
        s.put("Metadata", meta);
        s.put("Width", Nbt.shortTag(w));
        s.put("Height", Nbt.shortTag(h));
        s.put("Length", Nbt.shortTag(l));
        s.put("Offset", new int[]{0, 0, 0});
        s.put("Blocks", blocks);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", s);
        Path dir = worldEditFolder();
        Files.createDirectories(dir);
        try (OutputStream out = Files.newOutputStream(dir.resolve(p.schemName() + ".schem"))) {
            Nbt.write(out, root);
        }
    }

    /** Lines for the AI prompt (empty if the library is empty). */
    public static String promptSection(int max) {
        List<Entry> es = all();
        if (es.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("SAVED BUILDS you can place whole (component {\"type\":\"library\",\"name\":\"<name>\","
            + "\"x\":<center>,\"z\":<front>,\"rotate\":0|90|180|270}; sizes are as placed, front toward the player). "
            + "Use one when it fits the request; plan paths, gardens and other buildings around it. "
            + "Their materials are also good style references:\n");
        int n = 0;
        for (Entry e : es) {
            if (n++ >= max) break;
            sb.append("- ").append(e.summary()).append('\n');
        }
        return sb.toString();
    }
}
