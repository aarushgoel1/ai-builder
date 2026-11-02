package com.example.aiworldedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the AI's high-level plan ("components" such as building, roof, tower,
 * wall...) into exact WorldEdit commands. The AI decides WHAT goes WHERE;
 * this class does all the geometry, block states and terrain fitting.
 *
 * Coordinates in a plan are LOCAL to the anchor and the player's facing:
 *   x = right (+) / left (-),  z = forward (+, away from the player),  y = up.
 */
public final class BuildPlanner {
    private BuildPlanner() {}

    /** Ground height lookup: Y of the top ground block at a world column, or Integer.MIN_VALUE if unknown. */
    @FunctionalInterface
    public interface Ground {
        int surfaceY(int worldX, int worldZ);
    }

    public record Plan(List<String> commands, List<String> problems) {}

    public static final Set<String> TYPES = Set.of(
        "building", "roof", "tower", "wall", "path", "tree", "lamp_post",
        "box", "clear", "texture", "sphere", "cylinder", "block",
        "porch", "balcony", "chimney", "arch", "stairs", "fountain", "pond", "garden", "fence", "bridge", "column",
        "well", "stall", "woodpile", "library");

    private static final Set<String> TREE_KINDS = Set.of(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove");

    private static final Set<String> WOODS = Set.of(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
        "bamboo", "crimson", "warped", "pale_oak");

    private static final String PATH_MASK =
        "grass_block,dirt,coarse_dirt,podzol,mycelium,rooted_dirt,sand,gravel,dirt_path,moss_block";
    private static final String PLANTS =
        "short_grass,tall_grass,fern,large_fern,dandelion,poppy,cornflower,oxeye_daisy,azure_bluet";

    /** Local -> world coordinate frame. */
    public static final class Frame {
        public final BlockPos anchor;
        public final String facing;
        final int fx, fz, rx, rz;

        public Frame(BlockPos anchor, String facing) {
            this.anchor = anchor;
            String f = facing == null ? "north" : facing.toLowerCase(Locale.ROOT);
            this.facing = f;
            switch (f) {
                case "south" -> { fx = 0; fz = 1; }
                case "east" -> { fx = 1; fz = 0; }
                case "west" -> { fx = -1; fz = 0; }
                default -> { fx = 0; fz = -1; }
            }
            rx = -fz; // right-hand side = forward rotated clockwise
            rz = fx;
        }

        public int wx(int x, int z) { return anchor.getX() + x * rx + z * fx; }
        public int wz(int x, int z) { return anchor.getZ() + x * rz + z * fz; }
        public int wy(int y) { return anchor.getY() + y; }

        public String pos(int x, int y, int z) {
            return wx(x, z) + "," + wy(y) + "," + wz(x, z);
        }

        /** World direction name for a local direction: "+x", "-x", "+z", "-z". */
        public String dir(String local) {
            int dx, dz;
            switch (local) {
                case "+x" -> { dx = rx; dz = rz; }
                case "-x" -> { dx = -rx; dz = -rz; }
                case "+z" -> { dx = fx; dz = fz; }
                default -> { dx = -fx; dz = -fz; }
            }
            if (dx == 1) return "east";
            if (dx == -1) return "west";
            if (dz == 1) return "south";
            return "north";
        }

        /** World axis ("x" or "z") that a local axis maps to. */
        String axis(char local) {
            if (local == 'y') return "y";
            if (local == 'x') return rx != 0 ? "x" : "z";
            return fx != 0 ? "x" : "z";
        }
    }

    // =====================================================================

    /** v21: medieval houses are designed block by block by the {@link HouseComposer} (false = the older box builder). */
    public static volatile boolean useComposer = true;

    /** Footprints {x1,x2,z1,z2} of the other parts of the plan a composed house must keep clear of (paths excepted). */
    private static List<int[]> avoidFor(JsonObject self, JsonArray comps) {
        List<int[]> out = new ArrayList<>();
        int sx = num(self, "x", 0), sz = num(self, "z", 0);
        for (JsonElement el : comps) {
            if (!el.isJsonObject()) continue;
            JsonObject o = normalize(el.getAsJsonObject());
            String t = str(o, "type", "");
            if (t.equals("path") || t.equals("texture") || t.equals("clear")) continue;
            if (t.equals("building") && num(o, "x", 0) == sx && num(o, "z", 0) == sz) continue;
            if (o.has("_attach")) continue;
            int[] fp = footprint(o);
            if (fp == null) continue;
            if (t.equals("building")) fp = new int[]{fp[0] - 1, fp[1] + 1, fp[2] - 1, fp[3] + 1}; // their eaves
            out.add(fp);
        }
        return out;
    }

    /** Set when the player asked for identical/matching houses: repeated house settings are then kept as they are. */
    public static volatile boolean allowIdentical = false;

    private static final String[] SHAPE_KEYS = {"width", "depth", "floors", "archetype", "plan", "wing", "wings", "roof", "ridge",
        "roofProfile", "crossGables", "dormers", "frontPorch", "bayWindow", "oriel", "leanTo", "frontBalcony", "frontDeck", "jetty",
        "jettySides", "floorHeight", "pitch", "overhang"};
    private static final String[] LAYOUT_KEYS = {"archetype", "plan", "wing", "wings", "roof", "ridge", "roofProfile", "crossGables",
        "dormers", "frontPorch", "bayWindow", "oriel", "leanTo", "frontBalcony", "frontDeck", "jettySides", "floorHeight", "jetty"};

    /**
     * Villages: when the AI gives several houses exactly the same shape settings (a common shortcut), every repeat
     * after the first loses its layout settings so the style picks a different layout, and gets a slightly different
     * size and storey count. Materials stay as given. Skipped when the player asked for identical houses.
     */
    static void varyRepeats(JsonArray comps) {
        if (allowIdentical || comps == null) return;
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        int[] dw = {0, -2, 0, -4, -2, 0, -2}, dd = {-2, 0, -2, 0, -4, 2, 0}, df = {1, -1, 0, 1, -1, 0, 1};
        for (JsonElement el : comps) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            if (!"building".equals(str(c, "type", ""))) continue;
            StringBuilder sig = new StringBuilder();
            for (String k : SHAPE_KEYS) sig.append(k).append('=').append(c.has(k) ? c.get(k).toString() : "-").append(';');
            int k = seen.merge(sig.toString(), 1, Integer::sum) - 1;
            if (k == 0) continue;
            for (String key : LAYOUT_KEYS) c.remove(key);
            int i = (k - 1) % dw.length;
            int w = num(c, "width", 11), d = num(c, "depth", 9);
            c.addProperty("width", Math.max(7, w + dw[i]));
            c.addProperty("depth", Math.max(7, d + dd[i]));
            if (c.has("floors")) c.addProperty("floors", clamp(num(c, "floors", 2) + df[i], 1, 3));
        }
    }

    /** Test hook: sees the final component list (after plans and wings). */
    public static volatile java.util.function.Consumer<JsonArray> layoutDebug = null;

    public static Plan expand(JsonArray components, Frame frame, Ground ground) {
        return expand(components, frame, ground, null);
    }

    public static Plan expand(JsonArray components, Frame frame, Ground ground, String defaultStyle) {
        return expand(components, frame, ground, defaultStyle, null);
    }

    /**
     * @param defaultStyle style for components that don't name one (e.g. "medieval"), or null
     * @param climate "desert", "snowy" or null: adjusts style materials to the surroundings
     */
    public static Plan expand(JsonArray components, Frame frame, Ground ground, String defaultStyle, String climate) {
        // Variation depends on where the build goes too, so the same plan in a new spot can come out different.
        int salt = frame.anchor.getX() * 7919 + frame.anchor.getZ() * 104729;
        if (components != null) {
            // Buildings first (each picks its palette), then everything else matches the nearest building,
            // so a tower or chimney shares the materials of the house it stands beside.
            JsonArray normed = new JsonArray();
            for (JsonElement el : components) normed.add(el.isJsonObject() ? normalize(el.getAsJsonObject()) : el);
            varyRepeats(normed);
            components = normed;
            List<JsonElement> items = new ArrayList<>();
            List<int[]> bPos = new ArrayList<>();
            List<String> bPal = new ArrayList<>();
            for (JsonElement el : components) {
                if (!el.isJsonObject()) { items.add(el); continue; }
                JsonObject c = normalize(el.getAsJsonObject());
                if ("building".equals(str(c, "type", ""))) {
                    StylePresets.apply(c, defaultStyle, climate, salt);
                    if (c.has("palette")) {
                        bPos.add(new int[]{num(c, "x", 0), num(c, "z", 2) + num(c, "depth", 7) / 2});
                        bPal.add(str(c, "palette", ""));
                    }
                }
                items.add(c);
            }
            JsonArray styled = new JsonArray();
            for (JsonElement el : items) {
                if (el.isJsonObject() && !"building".equals(str(el.getAsJsonObject(), "type", ""))) {
                    JsonObject c = el.getAsJsonObject();
                    if (!c.has("palette") && !bPos.isEmpty()) {
                        int x = num(c, "x", 0), z = num(c, "z", 0), best = 0;
                        for (int i = 1; i < bPos.size(); i++) {
                            if (Math.hypot(bPos.get(i)[0] - x, bPos.get(i)[1] - z) < Math.hypot(bPos.get(best)[0] - x, bPos.get(best)[1] - z)) best = i;
                        }
                        c.addProperty("palette", bPal.get(best));
                    }
                    StylePresets.apply(c, defaultStyle, climate, salt);
                }
                styled.add(el);
            }
            components = styled;
        }
        List<String> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (components == null) return new Plan(out, problems);
        components = withLayouts(components, frame, ground, defaultStyle, climate, salt);
        components = withWings(components, frame, ground);
        if (layoutDebug != null) layoutDebug.accept(components);

        // Pre-pass: where every building will be, so porches, balconies and chimneys line up with them.
        List<BuildingInfo> buildings = new ArrayList<>();
        for (JsonElement el : components) {
            if (!el.isJsonObject()) continue;
            JsonObject c = normalize(el.getAsJsonObject());
            if (!"building".equals(str(c, "type", ""))) continue;
            try {
                buildings.add(buildingInfo(c, new Emitter(frame, ground)));
            } catch (RuntimeException ignored) {
                // reported in the main pass
            }
        }
        List<int[]> towers = new ArrayList<>();
        for (JsonElement el : components) {
            if (!el.isJsonObject()) continue;
            JsonObject c = normalize(el.getAsJsonObject());
            if (!"tower".equals(str(c, "type", ""))) continue;
            int tr = clamp(num(c, "radius", 3), 2, 16);
            towers.add(new int[]{num(c, "x", 0), num(c, "z", tr + 2), tr + clamp(num(c, "roofOverhang", 1), 0, 3)});
        }
        // Doors added by balconies/porches: keep the floor inside them clear of furniture.
        Emitter probe = new Emitter(frame, ground);
        probe.buildings = buildings;
        for (JsonElement el : components) {
            if (!el.isJsonObject()) continue;
            JsonObject c = normalize(el.getAsJsonObject());
            String t = str(c, "type", "");
            if (!t.equals("balcony") && !t.equals("porch")) continue;
            int x0 = num(c, "x", 0), z0 = num(c, "z", 2);
            BuildingInfo b = buildingAt(probe, x0, z0);
            if (b == null) continue;
            Side sd = sideFor(c, probe, x0, z0, b);
            if (t.equals("porch")) {
                b.porchSides.add(sd.name());
                if (sd.name().equals("front")) b.doorX = Math.max(b.x1 + 1, Math.min(b.x2 - 1, sd.x0())); // door under the porch
            }
            boolean hasDoor = t.equals("balcony") ? !"none".equals(str(c, "door", "x")) : !"none".equals(str(c, "door", "none"));
            if (!hasDoor) continue;
            for (int dv = 1; dv <= 2; dv++) b.keepClear.add(cell(sd.x(0, -dv), sd.z(0, -dv)));
        }

        int index = 0;
        List<int[]> claimed = new ArrayList<>();
        for (JsonElement el : components) {
            index++;
            if (!el.isJsonObject()) continue;
            JsonObject c = normalize(el.getAsJsonObject());
            String type = str(c, "type", "").toLowerCase(Locale.ROOT);
            Emitter e = new Emitter(frame, ground);
            e.buildings = buildings;
            e.towers = towers;
            try {
                switch (type) {
                    case "building" -> {
                        if (useComposer && HouseComposer.wants(c)) {
                            List<int[]> av = avoidFor(c, components);
                            av.addAll(claimed);                       // annexes and towers of houses built before this one
                            e.cmds.addAll(HouseComposer.compose(c, frame, ground, av, salt, claimed));
                        }
                        else building(c, e);
                    }
                    case "roof" -> roof(c, e, null);
                    case "tower" -> tower(c, e);
                    case "wall" -> wall(c, e);
                    case "path" -> path(c, e);
                    case "tree" -> tree(c, e);
                    case "lamp_post" -> lampPost(c, e);
                    case "box" -> box(c, e, false);
                    case "clear" -> box(c, e, true);
                    case "texture" -> texture(c, e);
                    case "sphere" -> sphere(c, e);
                    case "cylinder" -> cylinder(c, e);
                    case "block" -> singleBlock(c, e);
                    case "porch" -> porch(c, e);
                    case "balcony" -> balcony(c, e);
                    case "chimney" -> chimney(c, e);
                    case "arch" -> arch(c, e);
                    case "stairs" -> staircase(c, e);
                    case "fountain" -> fountain(c, e);
                    case "pond" -> pond(c, e);
                    case "garden" -> garden(c, e);
                    case "fence" -> fence(c, e);
                    case "bridge" -> bridge(c, e);
                    case "column" -> column(c, e);
                    case "well" -> well(c, e);
                    case "stall" -> stall(c, e);
                    case "woodpile" -> woodpile(c, e);
                    case "library" -> library(c, e);
                    default -> throw new PlanException("unknown type '" + type + "'");
                }
                out.addAll(e.cmds);
            } catch (PlanException ex) {
                problems.add("component #" + index + " (" + type + "): " + ex.getMessage());
            }
        }
        return new Plan(out, problems);
    }

    // ============================ components =============================

    /**
     * Rectangular building. x = center (left/right), z = front wall, width along x, depth along z.
     * Foundation to the terrain, walls (optionally stone ground floor + timber upper floors), jettied upper
     * floors with brackets, timber frame, framed windows with sills/shutters, framed entrance, furniture,
     * ladder, sloped stone base, yard props, chimney and roof (gradient, trim, dormers, finials).
     */
    private static final String[] SIDES = {"front", "back", "left", "right"};

    private static void building(JsonObject c, Emitter e) {
        BuildingInfo fresh = buildingInfo(c, e);
        BuildingInfo info = e.buildings.stream()
            .filter(b -> b.x1 == fresh.x1 && b.z1 == fresh.z1 && b.x2 == fresh.x2 && b.z2 == fresh.z2 && b.by == fresh.by)
            .findFirst().orElse(fresh);
        int x1 = info.x1, x2 = info.x2, z1 = info.z1, z2 = info.z2, by = info.by, h = info.h, floors = info.floors;
        String wall = info.wall;
        String lowerWall = optBlock(c, "lowerWall");
        String frame = info.frame;
        String foundation = info.foundation;
        String floor = info.floor;
        String window = block(c, "window", "glass_pane");
        int winH = clamp(num(c, "windowHeight", 2), 1, h - 2);
        int bay = clamp(num(c, "windowSpacing", 4), 3, 8);
        String door = str(c, "door", "oak_door");
        boolean hasDoor = door != null && !door.equals("none");
        String sill = optBlock(c, "sill");
        String shutters = optBlock(c, "shutters");
        if (shutters != null && !shutters.endsWith("_trapdoor")) shutters = null;
        String windowFrame = optBlock(c, "windowFrame");
        boolean lights = bool(c, "lights", true);
        int top = info.top;
        int[] g = e.groundRange(x1, z1, x2, z2);
        int fb = g == null ? by - 1 : Math.max(by - 16, Math.min(by - 1, g[0]));
        int doorX = info.doorX;
        boolean framedEntrance = "framed".equals(str(c, "entrance", "plain"));
        hoodBlock = bool(c, "windowHoods", false) && windowFrame != null ? stairsOf(windowFrame) : null;
        String hoodAll = hoodBlock;
        // Calm: small details on the front face only ("focal": "front"), outside lights only by the door.
        boolean focal = "front".equals(str(c, "focal", "all"));
        boolean lightsAll = info.lightsAll;

        // Wings: the wall shared with the main building, and the part of the main wall the wing covers.
        String attach = str(c, "_attach", null);
        List<int[]> wingHooks = info.wings; // {side index, a, b, floors}
        java.util.function.Predicate<String> openSide = sd -> !sd.equals(attach) && !info.hasWing(sd);

        // Horizontal beams may be a second timber tone ("beam"), like the references' mixed logs.
        String beamMat = frame != null ? block(c, "beam", frame) : null;
        String beamX = frame != null ? axisState(beamMat, e.frame.axis('x'), e) : wall;
        String beamZ = frame != null ? axisState(beamMat, e.frame.axis('z'), e) : wall;
        String braceX = frame != null ? axisState(frame, e.frame.axis('x'), e) : wall;
        String braceZ = frame != null ? axisState(frame, e.frame.axis('z'), e) : wall;
        String vertical = frame != null ? axisState(frame, "y", e) : null;
        boolean recess = info.recess;
        boolean braces = bool(c, "braces", false) && frame != null;
        int parity = clamp(num(c, "windowParity", 0), 0, 1);
        boolean boxes = bool(c, "windowBoxes", false);
        boolean sparse = bool(c, "sparseWindows", false);
        String awning = bool(c, "awnings", false) ? firstStairs(optBlock(c, "roofTrim"), info.wood + "_stairs") : null;

        // Foundation and ground floor.
        e.set(x1, fb, z1, x2, by - 1, z2, foundation);
        e.set(x1 + 1, by - 1, z1 + 1, x2 - 1, by - 1, z2 - 1, floor);

        String paneX = paneState(window, e.frame.axis('x'));
        String paneZ = paneState(window, e.frame.axis('z'));
        String[] flowers = {"potted_red_tulip", "potted_azure_bluet", "potted_oxeye_daisy", "potted_cornflower", "potted_allium"};
        int flowerK = 0;
        for (int f = 0; f < floors; f++) {
            int[] r = info.rect(f);
            int rx1 = r[0], rx2 = r[1], rz1 = r[2], rz2 = r[3];
            int sf = by + f * h, ef = sf + h - 1;
            boolean stone = f == 0 && lowerWall != null;
            String wf = stone ? lowerWall : wall;

            e.set(rx1, sf, rz1, rx2, ef, rz2, "air");
            if (f > 0) { // floor plate, reaching out over the jetty
                int py = sf - 1;
                int[] below = info.rect(f - 1);
                e.set(rx1, py, rz1, rx2, py, rz2, floor);
                ring(e, rx1, rx2, rz1, rz2, py, beamX, beamZ);
                ring(e, below[0], below[1], below[2], below[3], py, beamX, beamZ);
                // Brackets under the overhang (upside-down stairs against the wall below).
                if (info.jetty > 0) {
                    String bracket = info.wood + "_stairs";
                    List<Integer> bp = posts(below[0], below[1], bay, true);
                    for (int px : bp) {
                        if (info.jF) e.set(px, py - 1, below[2] - 1, px, py - 1, below[2] - 1, bracket + "[facing=" + e.frame.dir("+z") + ",half=top]");
                        if (info.jF && f == 1 && lights && lightsAll && (px == bp.get(0) || px == bp.get(bp.size() - 1)) && openSide.test("front")
                                && info.wingSpans("front").stream().noneMatch(sp -> px >= sp[0] - 1 && px <= sp[1] + 1))
                            e.set(px, py - 2, below[2] - 1, px, py - 2, below[2] - 1, "lantern[hanging=true]");
                        if (info.jB) e.set(px, py - 1, below[3] + 1, px, py - 1, below[3] + 1, bracket + "[facing=" + e.frame.dir("-z") + ",half=top]");
                    }
                    for (int pz : posts(below[2], below[3], bay, false)) {
                        if (info.jL) e.set(below[0] - 1, py - 1, pz, below[0] - 1, py - 1, pz, bracket + "[facing=" + e.frame.dir("+x") + ",half=top]");
                        if (info.jR) e.set(below[1] + 1, py - 1, pz, below[1] + 1, py - 1, pz, bracket + "[facing=" + e.frame.dir("-x") + ",half=top]");
                    }
                }
            }
            e.region(rx1, sf, rz1, rx2, ef, rz2, "//walls " + wf);
            if (f == 0 && !stone) e.region(rx1, sf, rz1, rx2, sf, rz2, "//walls " + foundation); // plinth row
            if (stone) { // stone laid in courses: a rough base row, an even body, a clean band at the top
                String baseC = optBlock(c, "baseCourse"), bandC = optBlock(c, "bandCourse");
                if (baseC != null) e.region(rx1, sf, rz1, rx2, sf, rz2, "//walls " + baseC);
                int bandY = frame != null ? ef - 1 : ef;
                if (bandC != null && bandY > sf + 2 && (frame == null || h >= 5)) e.region(rx1, bandY, rz1, rx2, bandY, rz2, "//walls " + bandC);
            }

            // Odd bay count on the ground floor keeps the door centred in a bay; upper floors may have a
            // post in the middle (wider bays, more plaster).
            List<Integer> postsX = posts(rx1, rx2, bay, f == 0);
            List<Integer> postsZ = posts(rz1, rz2, bay, false);
            if (vertical != null && !stone) {
                for (int px : postsX) {
                    e.set(px, sf, rz1, px, ef, rz1, vertical);
                    e.set(px, sf, rz2, px, ef, rz2, vertical);
                }
                for (int pz : postsZ) {
                    if (pz == rz1 || pz == rz2) continue;
                    e.set(rx1, sf, pz, rx1, ef, pz, vertical);
                    e.set(rx2, sf, pz, rx2, ef, pz, vertical);
                }
            }
            if (frame != null) ring(e, rx1, rx2, rz1, rz2, ef, beamX, beamZ); // beam at the top of each floor
            // Log-cabin corners: horizontal logs stacked crosswise at each corner, ends sticking out (TrixyBlox style).
            if (frame != null && !stone && bool(c, "cornerStack", false)) {
                int[][] cs = {{rx1, rz1, -1, -1}, {rx2, rz1, 1, -1}, {rx1, rz2, -1, 1}, {rx2, rz2, 1, 1}};
                String lx = axisState(beamMat, e.frame.axis('x'), e), lz = axisState(beamMat, e.frame.axis('z'), e);
                for (int[] k : cs) {
                    if (attach != null && cornerOn(new int[]{k[0], k[1]}, attach, rx1, rx2, rz1, rz2)) continue;
                    boolean wingNear = false;
                    String fbs = k[3] < 0 ? "front" : "back", lr = k[2] < 0 ? "left" : "right";
                    for (int[] sp : info.wingSpans(fbs)) if (k[0] >= sp[0] - 1 && k[0] <= sp[1] + 1) wingNear = true;
                    for (int[] sp : info.wingSpans(lr)) if (k[1] >= sp[0] - 1 && k[1] <= sp[1] + 1) wingNear = true;
                    if (wingNear) continue;
                    for (int y = f == 0 ? sf + 1 : sf; y <= ef; y++) {
                        boolean alongXLog = (y - sf) % 2 == 0;
                        e.set(k[0], y, k[1], k[0], y, k[1], alongXLog ? lx : lz);
                        if (alongXLog) e.set(k[0] + k[2], y, k[1], k[0] + k[2], y, k[1], lx);
                        else e.set(k[0], y, k[1] + k[3], k[0], y, k[1] + k[3], lz);
                    }
                }
            }

            // Each wall: bays between posts hold a window, a cross brace (timber floors) or plain wall.
            // Windows and braces alternate outward from the middle so every wall stays symmetric.
            boolean timber = !stone && frame != null;
            int sy = sf + 1, ty = Math.min(sy + winH - 1, ef - 1);
            int ry1 = (f == 0 && !stone) ? sf + 1 : sf, ry2 = ef - 1;
            for (String side : SIDES) {
                if (side.equals(attach)) continue; // wall shared with the main building
                boolean alongX = side.equals("front") || side.equals("back");
                int plane = switch (side) { case "front" -> rz1; case "back" -> rz2; case "left" -> rx1; default -> rx2; };
                String out = switch (side) { case "front" -> "-z"; case "back" -> "+z"; case "left" -> "-x"; default -> "+x"; };
                String pane = alongX ? paneX : paneZ;
                boolean detailHere = !focal || side.equals("front");
                hoodBlock = detailHere ? hoodAll : null;
                List<int[]> bs = bays(alongX ? postsX : postsZ);
                int n = bs.size();
                boolean[] skip = new boolean[n], doorBay = new boolean[n];
                boolean front0 = f == 0 && side.equals("front") && hasDoor;
                for (int i = 0; i < n; i++) {
                    int[] sp = bs.get(i);
                    for (int[] wh : wingHooks) {
                        if (SIDES[wh[0]].equals(side) && f <= wh[3] && sp[1] >= wh[1] && sp[0] <= wh[2]) skip[i] = true;
                    }
                    doorBay[i] = front0 && doorX >= sp[0] - 1 && doorX <= sp[1] + 1;
                }
                // Timber floors alternate windows with cross braces; stone floors (sparseWindows) leave every
                // other bay as plain wall, like the reference houses (small, few windows).
                boolean alternate = (timber && braces) || (!timber && sparse && n >= 3);
                int par = parity;
                if (alternate) { // make sure the wall keeps at least one window
                    boolean any = false;
                    for (int i = 0; i < n; i++) if (!skip[i] && !doorBay[i] && (Math.abs(2 * i - (n - 1)) / 2 + par) % 2 == 0) any = true;
                    if (!any) par ^= 1;
                }
                for (int i = 0; i < n; i++) {
                    if (skip[i]) continue;
                    int[] span = bs.get(i);
                    int ww = windowWidth(span);
                    if (sparse && ww > 2) ww = ww % 2 == 1 ? 1 : 2; // small windows, still centred in the bay
                    int a = span[0] + (span[1] - span[0] + 1 - ww) / 2, b = a + ww - 1;
                    boolean win = !alternate || (Math.abs(2 * i - (n - 1)) / 2 + par) % 2 == 0;
                    if (!timber && sparse && n == 2 && !alongX && f == 0) win = i == 0; // short stone side wall: one window, toward the front
                    boolean blank = !win; // left plain by the window pattern (not by the door)
                    int keep = framedEntrance ? 3 : 1; // room beside the door for wall lanterns
                    if (front0 && doorX >= a - keep && doorX <= b + keep) { win = false; blank = false; }
                    if (timber && recess && !doorBay[i]) recessBay(e, alongX, plane, out, span[0], span[1], ry1, ry2, wf);
                    if (win) {
                        int in = out.startsWith("-") ? 1 : -1, o1 = -in;
                        String inDir = (out.startsWith("-") ? "+" : "-") + out.charAt(1);
                        int boxV; // where flower pots go (in front of the glass, on the sill)
                        if (timber && recess) {
                            // Glass set back with the plaster; the beams frame it, a sill ledge sits in the recess.
                            if (alongX) e.set(a, sy, plane + in, b, ty, plane + in, pane);
                            else e.set(plane + in, sy, a, plane + in, ty, b, pane);
                            if (f == 0 && !stone) { // ground floor: the recessed panel stands on the plinth, not on air
                                if (alongX) e.set(a, sy - 1, plane + in, b, sy - 1, plane + in, foundation);
                                else e.set(plane + in, sy - 1, a, plane + in, sy - 1, b, foundation);
                            }
                            if (sill != null) {
                                if (alongX) e.set(a, sy - 1, plane, b, sy - 1, plane, sillState(sill, inDir, e));
                                else e.set(plane, sy - 1, a, plane, sy - 1, b, sillState(sill, inDir, e));
                            }
                            if (shutters != null && a - 1 >= span[0] && b + 1 <= span[1]) { // shutters in the recess
                                if (alongX) { shutter(e, shutters, a - 1, sy, plane, ty, out); shutter(e, shutters, b + 1, sy, plane, ty, out); }
                                else { shutter(e, shutters, plane, sy, a - 1, ty, out); shutter(e, shutters, plane, sy, b + 1, ty, out); }
                            }
                            boxV = 0;
                        } else {
                            windowAt(e, a, b, sy, ty, plane, alongX, out, pane, windowFrame, sill, shutters,
                                span[0], span[1], ry1, ry2);
                            boxV = o1;
                        }
                        if (boxes && (f == 1 || (floors == 1 && timber)) && (focal ? side.equals("front") : alongX) && sill != null) { // flower pots on the sills
                            for (int u = a; u <= b; u++) {
                                String fl = flowers[flowerK++ % flowers.length];
                                e.set(u, sy, plane + boxV, u, sy, plane + boxV, fl);
                            }
                        }
                        if (awning != null && f == 0 && side.equals("front") && !info.jF && ty + 1 <= ef) {
                            e.set(a - 1, ty + 1, plane - 1, b + 1, ty + 1, plane - 1, stairPat(awning, "+z", false, e));
                        }
                    } else if (alternate && timber && !doorBay[i]) {
                        brace(e, alongX, plane, span[0], span[1], ry1, ry2, alongX ? braceX : braceZ);
                    } else if (!timber && blank && !doorBay[i] && shutters != null && detailHere && bool(c, "blindShutters", false)) {
                        // A shuttered window on the plain stone bays: closed shutters flat on the wall over a sill.
                        int oxs = out.equals("-x") ? -1 : out.equals("+x") ? 1 : 0, ozs = out.equals("-z") ? -1 : out.equals("+z") ? 1 : 0;
                        String inD = (out.startsWith("-") ? "+" : "-") + out.charAt(1);
                        if (alongX) {
                            for (int u = a; u <= b; u++) shutter(e, shutters, u, sy, plane + ozs, ty, out);
                            if (sill != null) e.set(a, sy - 1, plane + ozs, b, sy - 1, plane + ozs, sillState(sill, inD, e));
                        } else {
                            for (int u = a; u <= b; u++) shutter(e, shutters, plane + oxs, sy, u, ty, out);
                            if (sill != null) e.set(plane + oxs, sy - 1, a, plane + oxs, sy - 1, b, sillState(sill, inD, e));
                        }
                    }
                }
            }
            // Recessed plaster sits one block inside the posts: fill in behind every post and beam too, so the
            // walls are flat from inside (no niches) and the room keeps a clean rectangle.
            if (timber && recess && bool(c, "flatInside", true)) {
                String inner = "//replace air " + wf;
                if (!"front".equals(attach)) e.region(rx1 + 1, ry1, rz1 + 1, rx2 - 1, ef - 1, rz1 + 1, inner);
                if (!"back".equals(attach)) e.region(rx1 + 1, ry1, rz2 - 1, rx2 - 1, ef - 1, rz2 - 1, inner);
                if (!"left".equals(attach)) e.region(rx1 + 1, ry1, rz1 + 1, rx1 + 1, ef - 1, rz2 - 1, inner);
                if (!"right".equals(attach)) e.region(rx2 - 1, ry1, rz1 + 1, rx2 - 1, ef - 1, rz2 - 1, inner);
            }
            if (lights) { // hanging lanterns along the middle of each floor, about every 4 blocks
                int ly = ef - 1, cx = (rx1 + rx2) / 2, cz = (rz1 + rz2) / 2;
                boolean longX = rx2 - rx1 >= rz2 - rz1;
                int len = longX ? rx2 - rx1 - 1 : rz2 - rz1 - 1, n = Math.max(1, Math.round(len / 5f));
                for (int k = 0; k < n; k++) {
                    int off = n == 1 ? 0 : Math.round((k - (n - 1) / 2f) * (len / (float) n));
                    int lx = longX ? cx + off : cx, lz = longX ? cz : cz + off;
                    e.set(lx, ly, lz, lx, ly, lz, "lantern[hanging=true]");
                }
            }
        }

        hoodBlock = null;

        // Stone ground floor details: corner quoins, weathering, buttresses and vines.
        boolean stoneGround = lowerWall != null;
        int qy2 = by + h - 1 - (frame != null ? 1 : 0);
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        if (stoneGround) {
            String quoin = optBlock(c, "cornerBlock");
            if (quoin != null) {
                int[][] corners = {{x1, z1, 1, 1}, {x2, z1, -1, 1}, {x1, z2, 1, -1}, {x2, z2, -1, -1}};
                for (int[] k : corners) {
                    if (attach != null && cornerOn(k, attach, x1, x2, z1, z2)) continue;
                    e.set(k[0], by, k[1], k[0], qy2, k[1], quoin);
                    for (int y = by; y <= qy2; y++) {
                        if ((y - by) % 2 == 0) e.set(k[0] + k[2], y, k[1], k[0] + k[2], y, k[1], quoin);
                        else e.set(k[0], y, k[1] + k[3], k[0], y, k[1] + k[3], quoin);
                    }
                }
            }
            if (bool(c, "weathering", false)) { // moss and cracks gather low on the wall
                int ax1 = x1 - 1, ax2 = x2 + 1, az1 = z1 - 1, az2 = z2 + 1;
                e.region(ax1, fb, az1, ax2, by, az2, "//replace stone_bricks 55%stone_bricks,45%mossy_stone_bricks");
                e.region(ax1, fb, az1, ax2, by, az2, "//replace cobblestone 50%cobblestone,50%mossy_cobblestone");
                e.region(ax1, by + 1, az1, ax2, qy2, az2, "//replace stone_bricks 92%stone_bricks,8%cracked_stone_bricks");
                e.region(ax1, fb, az1, ax2, qy2, az2, "//replace deepslate_bricks 72%deepslate_bricks,28%cracked_deepslate_bricks");
            }
            if (bool(c, "buttresses", false) && z2 - z1 + 1 >= 9) {
                String bs = stairsOf(lowerWall), solid = lowerWall;
                for (String side : new String[]{"left", "right"}) {
                    if (!openSide.test(side) || info.porchSides.contains(side)) continue;
                    int sgn = side.equals("left") ? -1 : 1, wx = side.equals("left") ? x1 : x2;
                    String toWall = side.equals("left") ? "+x" : "-x";
                    List<Integer> pz = posts(z1, z2, bay, false);
                    for (int k = 1; k + 1 < pz.size(); k++) {
                        int z = pz.get(k);
                        int bx1 = wx + sgn, bx2 = wx + 2 * sgn;
                        int[] gb = e.groundRange(Math.min(bx1, bx2), z, Math.max(bx1, bx2), z);
                        int gy = gb == null ? by - 1 : Math.max(by - 12, gb[0]);
                        e.set(bx1, gy, z, bx1, by + 1, z, solid);
                        e.set(bx1, by + 2, z, bx1, by + 2, z, bs + "[facing=" + e.frame.dir(toWall) + ",half=bottom]");
                        e.set(bx2, gy, z, bx2, by - 1, z, solid);
                        e.set(bx2, by, z, bx2, by, z, bs + "[facing=" + e.frame.dir(toWall) + ",half=bottom]");
                        occupied.add(cell(bx1, z));
                        occupied.add(cell(bx2, z));
                    }
                }
            }
            if (bool(c, "vines", false)) { // a little ivy creeping up the sides and back
                for (String side : new String[]{"back", "left", "right"}) {
                    if (!openSide.test(side)) continue;
                    String v = "18%vine[" + e.frame.dir(switch (side) { case "back" -> "-z"; case "left" -> "+x"; default -> "-x"; })
                        + "=true],82%air";
                    switch (side) {
                        case "back" -> e.region(x1 + 1, by, z2 + 1, x2 - 1, qy2, z2 + 1, "//replace air " + v);
                        case "left" -> e.region(x1 - 1, by, z1 + 1, x1 - 1, qy2, z2 - 1, "//replace air " + v);
                        default -> e.region(x2 + 1, by, z1 + 1, x2 + 1, qy2, z2 - 1, "//replace air " + v);
                    }
                }
            }
        }

        // Overgrowth: leafy bushes and moss hugging the foot of the walls (kept clear of the door).
        if (bool(c, "overgrowth", false)) {
            String green = "14%azalea_leaves[persistent=true],8%flowering_azalea_leaves[persistent=true],6%moss_block,72%air";
            if (openSide.test("back")) e.region(x1 - 1, by, z2 + 1, x2 + 1, by, z2 + 1, "//replace air " + green);
            if (openSide.test("left")) e.region(x1 - 1, by, z1, x1 - 1, by, z2, "//replace air " + green);
            if (openSide.test("right")) e.region(x2 + 1, by, z1, x2 + 1, by, z2, "//replace air " + green);
            if (doorX - 3 >= x1 - 1) e.region(x1 - 1, by, z1 - 1, doorX - 3, by, z1 - 1, "//replace air " + green);
            if (doorX + 3 <= x2 + 1) e.region(doorX + 3, by, z1 - 1, x2 + 1, by, z1 - 1, "//replace air " + green);
        }

        // Door and entrance.
        if (hasDoor) {
            String d0 = safeBlock(door);
            String inward = e.frame.dir("+z");
            if (framedEntrance) {
                String ef0 = windowFrame != null ? windowFrame : (frame != null ? vertical : info.wood + "_planks");
                e.set(doorX - 1, by, z1, doorX - 1, by + 2, z1, ef0);
                e.set(doorX + 1, by, z1, doorX + 1, by + 2, z1, ef0);
                e.set(doorX, by + 2, z1, doorX, by + 2, z1, ef0);
                if (!info.porchSides.contains("front")) {
                    // Wall lanterns on brackets either side of the door; optionally a hanging sign on one side.
                    String toWall = e.frame.dir("+z"), away = e.frame.dir("-z");
                    String fence = info.wood + "_fence";
                    boolean sign = bool(c, "sign", false);
                    int[] du = {-2, 2};
                    for (int k = 0; k < 2; k++) {
                        int lx = doorX + du[k];
                        if (lx <= x1 || lx >= x2) continue;
                        if (lowerWall == null && recess) e.set(lx, by, z1, lx, by + h - 2, z1, vertical); // a post to hang it on
                        if (sign && k == 1) {
                            e.set(lx, by + 2, z1 - 1, lx, by + 2, z1 - 1, fence + "[" + toWall + "=true," + away + "=true]");
                            e.set(lx, by + 2, z1 - 2, lx, by + 2, z1 - 2, fence + "[" + toWall + "=true]");
                            e.set(lx, by + 1, z1 - 2, lx, by + 1, z1 - 2,
                                info.wood + "_hanging_sign[rotation=" + rotation(e.frame.dir("+x")) + ",attached=true]");
                        } else {
                            e.set(lx, by + 2, z1 - 1, lx, by + 2, z1 - 1, fence + "[" + toWall + "=true]");
                            e.set(lx, by + 1, z1 - 1, lx, by + 1, z1 - 1, "lantern[hanging=true]");
                        }
                    }
                }
            }
            if (recess && !stoneGround && frame != null) e.set(doorX, by, z1 + 1, doorX, by + 1, z1 + 1, "air"); // behind the door
            if (d0.endsWith("_door")) {
                e.set(doorX, by, z1, doorX, by, z1, d0 + "[facing=" + inward + ",half=lower,hinge=left]");
                e.set(doorX, by + 1, z1, doorX, by + 1, z1, d0 + "[facing=" + inward + ",half=upper,hinge=left]");
            } else {
                e.set(doorX, by, z1, doorX, by + 1, z1, "air");
            }
        }

        // Pentice: a narrow pent roof along the top of a stone ground floor (sides that don't jut out above),
        // a hood over the door otherwise, and timber beam ends poking out under each floor line and the eaves.
        String penticeB = firstStairs(optBlock(c, "roofTrim"), info.wood + "_stairs");
        java.util.Set<String> penticed = new java.util.HashSet<>();
        if (bool(c, "pentice", false) && stoneGround && floors >= 2) {
            int py = by + h - 1;
            for (String sd : SIDES) {
                boolean jet = switch (sd) { case "front" -> info.jF; case "back" -> info.jB; case "left" -> info.jL; default -> info.jR; };
                if (jet || !openSide.test(sd) || info.porchSides.contains(sd)) continue;
                penticed.add(sd);
                List<int[]> spans = info.wingSpans(sd);
                switch (sd) {
                    case "front" -> plinthRun(spans, x1 - 1, x2 + 1, true, (u1, u2) -> e.set(u1, py, z1 - 1, u2, py, z1 - 1, stairPat(penticeB, "+z", false, e)));
                    case "back" -> plinthRun(spans, x1 - 1, x2 + 1, true, (u1, u2) -> e.set(u1, py, z2 + 1, u2, py, z2 + 1, stairPat(penticeB, "-z", false, e)));
                    case "left" -> plinthRun(spans, z1, z2, true, (u1, u2) -> e.set(x1 - 1, py, u1, x1 - 1, py, u2, stairPat(penticeB, "+x", false, e)));
                    default -> plinthRun(spans, z1, z2, true, (u1, u2) -> e.set(x2 + 1, py, u1, x2 + 1, py, u2, stairPat(penticeB, "-x", false, e)));
                }
            }
        }
        if (hasDoor && bool(c, "doorHood", false) && !penticed.contains("front") && !info.porchSides.contains("front")
                && (!info.jF || h >= 5 || floors == 1)) {
            int hy = by + 3;
            e.set(doorX - 1, hy, z1 - 1, doorX + 1, hy, z1 - 1, stairPat(penticeB, "+z", false, e));
            e.set(doorX - 1, hy - 1, z1 - 1, doorX - 1, hy - 1, z1 - 1, stairPat(penticeB, "+x", true, e)); // brackets
            e.set(doorX + 1, hy - 1, z1 - 1, doorX + 1, hy - 1, z1 - 1, stairPat(penticeB, "-x", true, e));
        }
        if (bool(c, "beamEnds", false) && frame != null) {
            boolean oriel = bool(c, "oriel", false) && floors >= 2 && attach == null;
            for (int f = 0; f < floors; f++) {
                if (f == 0 && stoneGround) continue;
                int[] rr = info.rect(f);
                int ey = by + (f + 1) * h - 1;
                List<Integer> px = posts(rr[0], rr[1], bay, f == 0), pz = posts(rr[2], rr[3], bay, false);
                for (String sd : SIDES) {
                    if (!openSide.test(sd) || (focal && !sd.equals("front"))) continue;
                    boolean jet = switch (sd) { case "front" -> info.jF; case "back" -> info.jB; case "left" -> info.jL; default -> info.jR; };
                    if (jet && f < floors - 1) continue;          // brackets of the floor above are there
                    if (f == 0 && penticed.contains(sd)) continue;
                    if (sd.equals("front") && oriel && f == 1) continue;
                    if (sd.equals("front") && f == 0 && hasDoor && floors == 1 && bool(c, "doorHood", false)) continue;
                    boolean alongX = sd.equals("front") || sd.equals("back");
                    String beamOut = axisState(beamMat, e.frame.axis(alongX ? 'z' : 'x'), e);
                    List<Integer> us = alongX ? px : pz;
                    for (int u : us) {
                        if (!alongX && (u == rr[2] || u == rr[3])) continue;
                        boolean lamp = lights && lightsAll && f == floors - 1 && sd.equals("front") && (u == us.get(0) || u == us.get(us.size() - 1));
                        if (lamp && !info.wingSpans(sd).stream().anyMatch(sp -> u >= sp[0] - 1 && u <= sp[1] + 1))
                            e.set(u, ey - 1, rr[2] - 1, u, ey - 1, rr[2] - 1, "lantern[hanging=true]");
                        if (info.wingSpans(sd).stream().anyMatch(sp -> u >= sp[0] - 1 && u <= sp[1] + 1)) continue;
                        switch (sd) {
                            case "front" -> e.set(u, ey, rr[2] - 1, u, ey, rr[2] - 1, beamOut);
                            case "back" -> e.set(u, ey, rr[3] + 1, u, ey, rr[3] + 1, beamOut);
                            case "left" -> e.set(rr[0] - 1, ey, u, rr[0] - 1, ey, u, beamOut);
                            default -> e.set(rr[1] + 1, ey, u, rr[1] + 1, ey, u, beamOut);
                        }
                    }
                }
            }
        }

        // Bay window: a little glazed box sticking out of the ground floor, with its own small roof.
        String baySide = null;
        if (bool(c, "bayWindow", false) && hasDoor) {
            List<String> cand = new ArrayList<>();
            if (!info.jF && !info.porchSides.contains("front") && floors >= 1) cand.add("front");
            if (!info.jR) cand.add("right");
            if (!info.jL) cand.add("left");
            int bseed = Math.floorMod(x1 * 31 + z1 * 17 + (x2 - x1) * 7, 97);
            if (bseed % 2 == 1) java.util.Collections.reverse(cand);
            for (String sd : cand) {
                if (!openSide.test(sd) || info.porchSides.contains(sd)) continue;
                boolean fr = sd.equals("front");
                List<Integer> pp = fr ? posts(x1, x2, bay, true) : posts(z1, z2, bay, false);
                int u0 = Integer.MIN_VALUE;
                for (int k = 0; k + 1 < pp.size(); k++) {
                    int a0 = pp.get(k) + 1, b0 = pp.get(k + 1) - 1;
                    if (b0 - a0 != 2) continue; // a bay exactly three wide
                    int mid = a0 + 1;
                    if (fr && Math.abs(mid - doorX) < 4) continue;
                    if (!fr && (mid - z1 < 3 || z2 - mid < 2)) continue;
                    boolean wingHere = false;
                    for (int[] sp : info.wingSpans(sd)) if (mid + 2 >= sp[0] && mid - 2 <= sp[1]) wingHere = true;
                    if (wingHere) continue;
                    u0 = mid;
                    if (!fr) break; // sides: the first good bay from the front
                }
                if (u0 == Integer.MIN_VALUE) continue;
                Side bs = switch (sd) {
                    case "front" -> new Side("front", u0, z1);
                    case "left" -> new Side("left", x1, u0);
                    default -> new Side("right", x2, u0);
                };
                // local u for the Side record: front u = x - x0; left u = z - z0; right u = z0 - z
                String postB = frame != null ? vertical : (lowerWall != null ? lowerWall : wall);
                String baseB = lowerWall != null ? lowerWall : foundation;
                String pane = paneState(window, e.frame.axis(bs.alongAxis()));
                String sidePane = paneState(window, e.frame.axis(bs.outAxis()));
                String bayRoof = firstStairs(optBlock(c, "roofTrim"), info.wood + "_stairs");
                int[] gb = groundUV(e, bs, -1, 1, 1, 2);
                if (gb != null && gb[0] < by - 1) setUV(e, bs, -1, 1, 1, 2, Math.max(by - 12, gb[0]), by - 2, foundation);
                setUV(e, bs, -1, 1, 1, 2, by - 1, by - 1, floor);               // floor of the bay
                boolean thick = recess && lowerWall == null && frame != null;  // recessed plaster behind the posts
                setUV(e, bs, -1, thick ? -1 : 0, 1, 2, by, by + 2, "air");     // open the wall into the room
                for (int du = -1; du <= 1; du++) for (int dv = -3; dv <= -1; dv++) info.keepClear.add(cell(bs.x(du, dv), bs.z(du, dv)));
                setUV(e, bs, -1, 2, -1, 2, by, by + 2, postB);                 // corner posts
                setUV(e, bs, 1, 2, 1, 2, by, by + 2, postB);
                setUV(e, bs, 0, 2, 0, 2, by, by, baseB);                       // front: low wall + glass
                setUV(e, bs, 0, 2, 0, 2, by + 1, by + 2, pane);
                setUV(e, bs, -1, 1, -1, 1, by, by, baseB);                     // sides: low wall + glass
                setUV(e, bs, 1, 1, 1, 1, by, by, baseB);
                setUV(e, bs, -1, 1, -1, 1, by + 1, by + 2, sidePane);
                setUV(e, bs, 1, 1, 1, 1, by + 1, by + 2, sidePane);
                int ry = by + 3;                                               // its own little hipped roof
                setUV(e, bs, -1, 2, 1, 2, ry, ry, stairPat(bayRoof, bs.in(), false, e));
                setUV(e, bs, -2, 1, -2, 2, ry, ry, stairPat(bayRoof, bs.alongDir(), false, e));
                setUV(e, bs, 2, 1, 2, 2, ry, ry, stairPat(bayRoof, bs.alongDirOpposite(), false, e));
                setUV(e, bs, -1, 1, 1, 1, ry, ry, slabOf(bayRoof).replace("type=bottom", "type=top"));
                setUV(e, bs, -1, 0, 1, 0, by + 3, by + 3, frame != null ? (bs.alongAxis() == 'x' ? beamX : beamZ) : wall); // lintel
                if (sill != null) setUV(e, bs, 0, 3, 0, 3, by, by, sillState(sill, bs.in(), e));
                for (int du = -2; du <= 2; du++) for (int dv = 1; dv <= 3; dv++) occupied.add(cell(bs.x(du, dv), bs.z(du, dv)));
                baySide = sd;
                break;
            }
        }

        // Banners hanging from the posts either side of the middle of the first upper floor.
        String banner = optBlock(c, "banners");
        if (banner != null && floors >= 2 && frame != null) {
            String bb = banner.endsWith("_wall_banner") ? banner
                : banner.endsWith("_banner") ? banner.replace("_banner", "_wall_banner") : banner + "_wall_banner";
            int[] r1 = info.rect(1);
            List<Integer> p1 = posts(r1[0], r1[1], bay, false);
            int y = by + 2 * h - 2;
            java.util.Set<Integer> at = new java.util.LinkedHashSet<>();
            if (p1.size() >= 4) { at.add(p1.get(p1.size() / 2 - 1)); at.add(p1.get(p1.size() / 2)); }
            else if (p1.size() == 3) at.add(p1.get(1));
            for (int px : at) {
                if (px <= r1[0] || px >= r1[1]) continue;
                e.set(px, y, r1[2] - 1, px, y, r1[2] - 1, bb + "[facing=" + e.frame.dir("-z") + "]");
            }
        }

        // Oriel: a glazed bay window sticking out of the middle of the first upper floor.
        if (bool(c, "oriel", false) && floors >= 2 && attach == null) {
            int sf = by + h, ef = sf + h - 1;
            int[] r1 = info.rect(1);
            int pz = r1[2], oz = pz - 1, cx = (x1 + x2) / 2;
            int hw = clamp((num(c, "orielWidth", 5) - 1) / 2, 1, Math.max(1, (x2 - x1) / 2 - 2));
            String og = paneState(block(c, "orielGlass", window), e.frame.axis('x'));
            String ledge = windowFrame != null ? windowFrame : info.wood + "_planks";
            String post = vertical != null ? vertical : axisState(info.wood + "_log", "y", e);
            String br = info.wood + "_stairs";
            e.set(cx - hw + 1, sf + 1, pz, cx + hw - 1, ef - 1, pz + (recess ? 1 : 0), "air"); // open into the room
            e.set(cx - hw, sf, oz, cx + hw, sf, oz, ledge);
            e.set(cx - hw + 1, sf + 1, oz, cx + hw - 1, ef - 1, oz, og);
            e.set(cx - hw, sf + 1, oz, cx - hw, ef - 1, oz, post);
            e.set(cx + hw, sf + 1, oz, cx + hw, ef - 1, oz, post);
            e.set(cx - hw, sf - 1, oz, cx + hw, sf - 1, oz, br + "[facing=" + e.frame.dir("+z") + ",half=top]"); // brackets
            if (!(info.jF && floors > 2)) { // little roof unless the floor above juts over it
                e.set(cx - hw, ef, oz, cx + hw, ef, oz, stairPat(firstStairs(optBlock(c, "roofTrim"), br), "+z", false, e));
            }
        }

        // Wing: open a doorway through the shared wall.
        if (c.has("_doorX")) {
            int dx = num(c, "_doorX", 0), dz = num(c, "_doorZ", 0);
            boolean nx = "left".equals(attach) || "right".equals(attach); // the wall's normal runs along x
            if (nx) e.set(dx - 1, by, dz, dx + 1, by + 1, dz, "air"); // through the wall and any plaster behind it
            else e.set(dx, by, dz - 1, dx, by + 1, dz + 1, "air");
        }

        // Sloped stone base around the foundation (not along a wall shared with a wing).
        if (bool(c, "plinth", false)) {
            String st = stairsOf(foundation);
            int y = by - 1;
            plinthRun(info.wingSpans("front"), x1, x2, !"front".equals(attach),
                (u1, u2) -> e.set(u1, y, z1 - 1, u2, y, z1 - 1, st + "[facing=" + e.frame.dir("+z") + ",half=bottom]"));
            plinthRun(info.wingSpans("back"), x1, x2, !"back".equals(attach),
                (u1, u2) -> e.set(u1, y, z2 + 1, u2, y, z2 + 1, st + "[facing=" + e.frame.dir("-z") + ",half=bottom]"));
            plinthRun(info.wingSpans("left"), z1, z2, !"left".equals(attach),
                (u1, u2) -> e.set(x1 - 1, y, u1, x1 - 1, y, u2, st + "[facing=" + e.frame.dir("+x") + ",half=bottom]"));
            plinthRun(info.wingSpans("right"), z1, z2, !"right".equals(attach),
                (u1, u2) -> e.set(x2 + 1, y, u1, x2 + 1, y, u2, st + "[facing=" + e.frame.dir("-x") + ",half=bottom]"));
        }

        // Furniture and a ladder between floors.
        String interior = str(c, "interior", "home").toLowerCase(Locale.ROOT);
        furnish(e, info, interior, vertical != null ? vertical : wall);

        // Roof over the top floor.
        int[] tr = info.rect(floors - 1);
        String roofStyle = str(c, "roof", "gable");
        if (!roofStyle.equals("none")) {
            JsonObject r = new JsonObject();
            r.addProperty("style", roofStyle);
            r.addProperty("x1", tr[0]); r.addProperty("x2", tr[1]);
            r.addProperty("z1", tr[2]); r.addProperty("z2", tr[3]);
            r.addProperty("baseY", top + 1);
            for (String k : new String[]{"roofBlock", "overhang", "pitch", "roofGradient", "roofTrim", "ridge",
                    "dormers", "finial", "finialPost", "finialCap", "finialAt", "gableLanterns", "snowRoof", "roofMoss", "fascia", "roofProfile",
                    "roofStripes", "ridgeCap", "thickRoof", "eaveBlock", "window", "kick", "eaveUnderside", "_inner", "_innerExtend", "_dormerSkip", "bigRoof",
                    "crossGables", "crossGableWidth", "crossGableSides", "frame"}) {
                if (c.has(k)) r.add(k, c.get(k));
            }
            List<String> ss = new ArrayList<>();
            for (int[] wh : info.wings) ss.add(SIDES[wh[0]]);
            for (String ps : info.porchSides) ss.add(ps);
            r.addProperty("_skirtSkip", String.join(",", ss));
            if (attach != null && c.has("_clip")) { // a wing's roof runs into the main roof but never into its rooms
                JsonArray k = c.getAsJsonArray("_clip");
                e.clips.add(new int[]{k.get(0).getAsInt(), k.get(1).getAsInt(), k.get(2).getAsInt(), k.get(3).getAsInt(), k.get(4).getAsInt()});
            }
            roof(r, e, wall);
            e.clips.clear();
            // Brackets under the eaves at every post: the shadow line where the roof steps out over the walls.
            if (bool(c, "eaveBrackets", false) && clamp(num(c, "overhang", 1), 0, 3) >= 1 && !roofStyle.equals("flat") && !roofStyle.equals("shed")) {
                String rdg = str(c, "ridge", "auto").toLowerCase(Locale.ROOT);
                boolean rx = rdg.equals("x") || (!rdg.equals("z") && (tr[1] - tr[0]) >= (tr[3] - tr[2]));
                String brk = info.wood + "_stairs";
                for (String sd : SIDES) {
                    boolean onX = sd.equals("front") || sd.equals("back");
                    if (!roofStyle.equals("hip") && onX != rx) continue;   // gable ends have no eave
                    if (!openSide.test(sd) || sd.equals(attach)) continue;
                    if (sd.equals("front") && (bool(c, "oriel", false) || info.porchSides.contains("front"))) continue;
                    List<Integer> us = onX ? posts(tr[0], tr[1], bay, false) : posts(tr[2], tr[3], bay, false);
                    for (int u : us) {
                        switch (sd) {
                            case "front" -> e.set(u, top, tr[2] - 1, u, top, tr[2] - 1, stairPat(brk, "+z", true, e));
                            case "back" -> e.set(u, top, tr[3] + 1, u, top, tr[3] + 1, stairPat(brk, "-z", true, e));
                            case "left" -> e.set(tr[0] - 1, top, u, tr[0] - 1, top, u, stairPat(brk, "+x", true, e));
                            default -> e.set(tr[1] + 1, top, u, tr[1] + 1, top, u, stairPat(brk, "-x", true, e));
                        }
                    }
                }
            }
        }

        // Chimney on the outside of a side wall (no clash with furniture, jettied fronts, wings or towers).
        if (bool(c, "chimney", false)) {
            int czMid = Math.max(z1 + 1, z2 - 3);
            int[][] options = {{x2 + 1, czMid, info.jR || !openSide.test("right") || "right".equals(baySide) ? 1 : 0},
                {x1 - 2, czMid, info.jL || !openSide.test("left") || "left".equals(baySide) ? 1 : 0},
                {Math.max(x1 + 1, x2 - 3), z2 + 1, info.jB || !openSide.test("back") ? 1 : 0}};
            int[] pick = null;
            for (int[] opt : options) {
                if (opt[2] == 1) continue;
                boolean clear = true;
                for (int[] t : e.towers) {
                    double dist = Math.hypot(opt[0] + 0.5 - t[0], opt[1] + 0.5 - t[1]);
                    if (dist < t[2] + 3) clear = false;
                }
                for (BuildingInfo ob : e.buildings) { // keep out of a neighbour's walls and eaves
                    if (ob == info) continue;
                    int[] orc = ob.rect(ob.floors - 1);
                    if (opt[0] + 1 >= orc[0] - 2 && opt[0] <= orc[1] + 2 && opt[1] + 1 >= orc[2] - 2 && opt[1] <= orc[3] + 2) clear = false;
                }
                if (clear) { pick = opt; break; }
            }
            if (pick != null) {
                int cxA = pick[0], czA = pick[1];
                int[] gc = e.groundRange(cxA, czA, cxA + 1, czA + 1);
                int bottom = gc == null ? by - 1 : Math.min(by - 1, gc[0]);
                int tc = info.roofTop + 2;
                String cb = block(c, "chimneyBlock", "60%stone_bricks,25%andesite,15%cobblestone");
                e.set(cxA, bottom, czA, cxA + 1, tc, czA + 1, cb);
                e.set(cxA - 1, tc - 1, czA - 1, cxA + 2, tc - 1, czA + 2, cb); // cap
                e.set(cxA, tc + 1, czA, cxA, tc + 1, czA, "campfire[lit=true]");
                String pot = slabOf(stairsOf(cb));
                e.set(cxA + 1, tc + 1, czA + 1, cxA + 1, tc + 1, czA + 1, pot); // chimney crown: a pot and a slab
                e.set(cxA + 1, tc + 1, czA, cxA + 1, tc + 1, czA, pot);
                for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) occupied.add(cell(cxA + dx, czA + dz));
            }
        }

        // Yard props: bushes, potted flowers, barrels, a wood pile and a stone apron by the door.
        if (bool(c, "yard", false)) yard(e, info, openSide, occupied);
    }

    /** Runs fn over the parts of u1..u2 not covered by wings (spans {a,b}). */
    private static void plinthRun(List<int[]> spans, int u1, int u2, boolean enabled, java.util.function.BiConsumer<Integer, Integer> fn) {
        if (!enabled) return;
        List<int[]> sorted = new ArrayList<>(spans);
        sorted.sort(java.util.Comparator.comparingInt(a -> a[0]));
        int start = u1;
        for (int[] sp : sorted) {
            if (sp[0] - 1 >= start) fn.accept(start, Math.min(u2, sp[0] - 1));
            start = Math.max(start, sp[1] + 1);
        }
        if (start <= u2) fn.accept(start, u2);
    }

    private static boolean cornerOn(int[] k, String side, int x1, int x2, int z1, int z2) {
        return switch (side) {
            case "front" -> k[1] == z1;
            case "back" -> k[1] == z2;
            case "left" -> k[0] == x1;
            default -> k[0] == x2;
        };
    }

    /** Hanging-sign rotation (0-15) whose face points in a world direction. */
    private static int rotation(String worldDir) {
        return switch (worldDir) { case "west" -> 4; case "north" -> 8; case "east" -> 12; default -> 0; };
    }

    /** First stairs block in a pattern, or the fallback. */
    private static String firstStairs(String pattern, String def) {
        if (pattern == null) return def;
        for (String p : VoxelGrid.splitPattern(pattern.replace('|', ','))) {
            String b = p.contains("%") ? p.substring(p.indexOf('%') + 1) : p;
            b = VoxelGrid.baseId(b);
            if (b.endsWith("_stairs")) return b;
        }
        return def;
    }

    /** Sets a bay of a timber wall back one block, so the beams stand proud of the plaster. */
    private static void recessBay(Emitter e, boolean alongX, int plane, String out, int u1, int u2, int y1, int y2, String plaster) {
        int in = out.startsWith("-") ? 1 : -1;
        if (alongX) {
            e.set(u1, y1, plane, u2, y2, plane, "air");
            e.set(u1, y1, plane + in, u2, y2, plane + in, plaster);
        } else {
            e.set(plane, y1, u1, plane, y2, u2, "air");
            e.set(plane + in, y1, u1, plane + in, y2, u2, plaster);
        }
    }

    /** Diagonal timber braces across a bay: an X in wide bays, a single diagonal in narrow ones. */
    private static void brace(Emitter e, boolean alongX, int plane, int u1, int u2, int y1, int y2, String beam) {
        int w = u2 - u1 + 1, hh = y2 - y1 + 1;
        if (w < 2 || hh < 2) return;
        if (alongX) {
            e.line(u1, y1, plane, u2, y2, plane, beam);
            if (w >= 3) e.line(u1, y2, plane, u2, y1, plane, beam);
        } else {
            e.line(plane, y1, u1, plane, y2, u2, beam);
            if (w >= 3) e.line(plane, y2, u1, plane, y1, u2, beam);
        }
    }

    /** One window with optional frame, sill (outside, below) and shutters (outside, both sides). */
    private static void windowAt(Emitter e, int a, int b, int sy, int ty, int plane, boolean alongX, String out,
                                 String pane, String frame, String sill, String shutters) {
        windowAt(e, a, b, sy, ty, plane, alongX, out, pane, frame, sill, shutters, a - 1, b + 1, sy - 1, ty + 1);
    }

    /** Stairs for a little drip hood over each framed window (set per building; null = none). */
    private static String hoodBlock = null;

    /** Same, with the frame kept inside the bay (uLo..uHi, yLo..yHi) so it never covers posts or beams. */
    private static void windowAt(Emitter e, int a, int b, int sy, int ty, int plane, boolean alongX, String out,
                                 String pane, String frame, String sill, String shutters, int uLo, int uHi, int yLo, int yHi) {
        int fa = Math.max(a - 1, uLo), fb = Math.min(b + 1, uHi), fy1 = Math.max(sy - 1, yLo), fy2 = Math.min(ty + 1, yHi);
        int ox = out.equals("-x") ? -1 : out.equals("+x") ? 1 : 0, oz = out.equals("-z") ? -1 : out.equals("+z") ? 1 : 0;
        String in = out.startsWith("-") ? "+" + out.charAt(1) : "-" + out.charAt(1);
        String hood = hoodBlock;
        if (alongX) {
            if (frame != null) e.set(fa, fy1, plane, fb, fy2, plane, frame);
            if (hood != null && fy2 == ty + 1) e.set(fa, fy2, plane + oz, fb, fy2, plane + oz, stairPat(hood, in, false, e));
            e.set(a, sy, plane, b, ty, plane, pane);
            if (sill != null) e.set(a, sy - 1, plane + oz, b, sy - 1, plane + oz, sillState(sill, in, e));
            if (shutters != null) {
                shutter(e, shutters, a - 1, sy, plane + oz, ty, out);
                shutter(e, shutters, b + 1, sy, plane + oz, ty, out);
            }
        } else {
            if (frame != null) e.set(plane, fy1, fa, plane, fy2, fb, frame);
            if (hood != null && fy2 == ty + 1) e.set(plane + ox, fy2, fa, plane + ox, fy2, fb, stairPat(hood, in, false, e));
            e.set(plane, sy, a, plane, ty, b, pane);
            if (sill != null) e.set(plane + ox, sy - 1, a, plane + ox, sy - 1, b, sillState(sill, in, e));
            if (shutters != null) {
                shutter(e, shutters, plane + ox, sy, a - 1, ty, out);
                shutter(e, shutters, plane + ox, sy, b + 1, ty, out);
            }
        }
    }

    /** Beam ring around a rectangle at height y (beams oriented along each wall). */
    private static void ring(Emitter e, int x1, int x2, int z1, int z2, int y, String alongX, String alongZ) {
        e.set(x1, y, z1, x2, y, z1, alongX);
        e.set(x1, y, z2, x2, y, z2, alongX);
        e.set(x1, y, z1 + 1, x1, y, z2 - 1, alongZ);
        e.set(x2, y, z1 + 1, x2, y, z2 - 1, alongZ);
    }

    /** Stairs block matching a material (first block of a pattern), e.g. stone_bricks -> stone_brick_stairs. */
    static String stairsOf(String pattern) {
        String b = VoxelGrid.splitPattern(pattern).get(0);
        int pct = b.indexOf('%');
        if (pct >= 0) b = b.substring(pct + 1);
        b = VoxelGrid.baseId(b);
        if (b.endsWith("_stairs")) return b;
        // There are no chiseled or cracked stairs: use the plain block's stairs.
        if (b.startsWith("chiseled_")) b = b.substring(9);
        if (b.startsWith("cracked_")) b = b.substring(8);
        if (b.endsWith("_planks")) return b.substring(0, b.length() - 7) + "_stairs";
        if (b.equals("quartz_block")) return "quartz_stairs";
        if (b.endsWith("bricks") || b.endsWith("tiles")) return b.substring(0, b.length() - 1) + "_stairs";
        return b + "_stairs";
    }

    /** Small decorations around the outside of a building (kept off walls shared with a wing). */
    private static void yard(Emitter e, BuildingInfo b, java.util.function.Predicate<String> open, java.util.Set<Long> occupied) {
        String[] front = b.quiet ? new String[]{"azalea", "flowering_azalea"}
            : new String[]{"flowering_azalea", "potted_red_tulip", "azalea", "potted_azure_bluet", "flowering_azalea", "potted_oxeye_daisy"};
        int k = 0;
        if (!b.porchSides.contains("front")) {
            for (int x = b.x1; x <= b.x2; x += b.quiet ? 4 : 2) {
                if (Math.abs(x - b.doorX) <= 2) continue;
                boolean underWing = false;
                for (int[] sp : b.wingSpans("front")) if (x >= sp[0] - 1 && x <= sp[1] + 1) underWing = true;
                if (underWing) continue;
                prop(e, x, b.z1 - 2, front[k++ % front.length]);
            }
        }
        // Barrels and hay by the front corners of the sides, bushes along the back.
        if (open.test("left")) {
            prop(e, b.x1 - 2, b.z1 + 1, "barrel[facing=up]");
            prop(e, b.x1 - 2, b.z1 + 2, "barrel[facing=up]");
            if (b.lightsAll) propOnTop(e, b.x1 - 2, b.z1 + 2, "lantern[hanging=false]");
        }
        if (open.test("right")) {
            prop(e, b.x2 + 2, b.z1 + 1, "hay_block");
            if (b.lightsAll) propOnTop(e, b.x2 + 2, b.z1 + 1, "lantern[hanging=false]");
        }
        if (open.test("back")) {
            for (int x = b.x1 + 1; x <= b.x2 - 1; x += 3) prop(e, x, b.z2 + 2, k++ % 2 == 0 ? "azalea" : "oak_leaves[persistent=true]");
        }
        // A covered stack of firewood against a side wall near the back.
        int[][] spots = {{b.x1 - 2, -1}, {b.x2 + 2, 1}};
        for (int[] sp : spots) {
            String side = sp[1] < 0 ? "left" : "right";
            if (!open.test(side) || b.porchSides.contains(side)) continue;
            int z2 = b.z2 - 1, z1 = z2 - 1;
            if (occupied.contains(cell(sp[0], z1)) || occupied.contains(cell(sp[0], z2))
                || occupied.contains(cell(sp[0] - sp[1], z1)) || occupied.contains(cell(sp[0] - sp[1], z2))) continue;
            woodpileAt(e, sp[0], z1, sp[0], z2, 2, b.wood + "_log", b.wood + "_slab", 'z');
            break;
        }
        // Stone apron in front of the door.
        int[] g = e.groundRange(b.doorX - 1, b.z1 - 3, b.doorX + 1, b.z1 - 1);
        if (g != null) e.region(b.doorX - 1, g[0], b.z1 - 3, b.doorX + 1, g[1], b.z1 - 1,
            "//replace " + PATH_MASK + " 50%cobblestone,25%mossy_cobblestone,25%gravel");
    }

    /** A block on top of the prop standing at x,z (e.g. a lantern on a barrel). */
    private static void propOnTop(Emitter e, int x, int z, String block) {
        int[] g = e.groundRange(x, z, x, z);
        int y = (g == null ? -1 : g[1]) + 2;
        e.set(x, y, z, x, y, z, block);
    }

    private static void prop(Emitter e, int x, int z, String block) {
        int[] g = e.groundRange(x, z, x, z);
        int y = (g == null ? -1 : g[1]) + 1;
        e.set(x, y, z, x, y, z, block);
    }

    /** Logs stacked along a local axis on the ground, with a slab cover. */
    private static void woodpileAt(Emitter e, int x1, int z1, int x2, int z2, int height, String log, String cover, char axis) {
        int[] g = e.groundRange(x1, z1, x2, z2);
        int y = (g == null ? -1 : g[1]) + 1;
        if (g != null && g[0] < y - 1) e.set(x1, g[0] + 1, z1, x2, y - 1, z2, "coarse_dirt");
        e.set(x1, y, z1, x2, y + height - 1, z2, axisState(log, e.frame.axis(axis), e));
        if (cover != null) e.set(x1, y + height, z1, x2, y + height, z2, slabBottom(cover));
    }

    /**
     * Roof over a rectangle. Standalone form: x (center), z (front), width, depth, y (base height
     * relative to anchor). Styles: gable, hip, flat. Options: pitch, kick (shallow layers at the eaves),
     * roofGradient (stairs from eave to ridge; layers split by "|" may be mixes), roofTrim (stairs for eaves,
     * gable edges and ridge), eaveUnderside, ridge (x|z|auto), dormers (per slope), finial.
     */
    private static void roof(JsonObject c, Emitter e, String gableFill) {
        int x1, x2, z1, z2, yb;
        if (c.has("x1")) { // internal call from building
            x1 = c.get("x1").getAsInt(); x2 = c.get("x2").getAsInt();
            z1 = c.get("z1").getAsInt(); z2 = c.get("z2").getAsInt();
            yb = c.get("baseY").getAsInt();
        } else {
            int w = clamp(num(c, "width", 9), 3, 64), d = clamp(num(c, "depth", 7), 3, 64);
            x1 = num(c, "x", 0) - (w - 1) / 2; x2 = x1 + w - 1;
            z1 = num(c, "z", 2); z2 = z1 + d - 1;
            yb = num(c, "y", 5);
        }
        String style = str(c, "style", str(c, "roof", "gable")).toLowerCase(Locale.ROOT);
        String stairs = block(c, "roofBlock", "dark_oak_stairs");
        List<String> grad = gradient(str(c, "roofGradient", stairs));
        if (grad.isEmpty()) grad.add(stairs);
        String trim = optBlock(c, "roofTrim");
        String fill = gableFill != null ? gableFill : block(c, "wall", solidOf(stairs));
        int o = clamp(num(c, "overhang", 1), 0, 3);
        int ex1 = x1 - o, ex2 = x2 + o, ez1 = z1 - o, ez2 = z2 + o;
        int p = clamp(num(c, "pitch", 1), 1, 2); // 2 = steep: each step rises two blocks
        int kick = p > 1 ? clamp(num(c, "kick", 0), 0, 2) : 0; // first layers rise one block: flared eaves
        boolean underside = bool(c, "eaveUnderside", false);
        Fin roofFin = fin(c, e); // finials are drawn last, after moss or snow
        // Where finials go: "all" (every gable, hip end, cross gable and dormer), "main" (the main roof only),
        // "front" (only a gable end facing the front) or "none".
        String finAt = str(c, "finialAt", "all").toLowerCase(Locale.ROOT);
        Fin smallFin = finAt.equals("all") ? roofFin : null;
        stripes = bool(c, "roofStripes", false);
        boolean thick = bool(c, "thickRoof", false);   // a second layer under each roof row (TrixyBlox style)
        String eaveLip = optBlock(c, "eaveBlock");     // a block lip under the lowest row
        String ridgeCap = optBlock(c, "ridgeCap");
        String fascia0 = optBlock(c, "fascia");
        String fascia = fascia0 != null && VoxelGrid.baseId(fascia0).endsWith("_trapdoor") ? VoxelGrid.baseId(fascia0) : null;
        // A wing's roof joins the main roof on one end: no overhang, gable wall, trim or finial there.
        String inner = str(c, "_inner", "");
        int innerExt = clamp(num(c, "_innerExtend", 0), -1, 32); // -1: stop one short (against a gable wall)
        // Big roof: a lower skirt row one block further out under the eaves, and ragged gable edges.
        boolean big = bool(c, "bigRoof", false) && eaveLip == null;
        java.util.Set<String> skirtSkip = new java.util.HashSet<>(java.util.Arrays.asList(str(c, "_skirtSkip", "").split(",")));
        if (!inner.isEmpty()) skirtSkip.add(inner);
        switch (inner) {
            case "left" -> ex1 = x1 - innerExt;
            case "right" -> ex2 = x2 + innerExt;
            case "front" -> ez1 = z1 - innerExt;
            case "back" -> ez2 = z2 + innerExt;
            default -> { }
        }
        // Roof profile: how much each layer rises from the eave up (steep, classic 45 degrees, rounded, flared, mixed).
        String prof = str(c, "roofProfile", "steep").toLowerCase(Locale.ROOT);
        boolean rax0 = str(c, "ridge", "auto").equalsIgnoreCase("x") || (!str(c, "ridge", "auto").equalsIgnoreCase("z") && (x2 - x1) >= (z2 - z1));
        int layersL = style.equals("hip") ? (Math.min(ex2 - ex1, ez2 - ez1) + 2) / 2 : ((rax0 ? ez2 - ez1 + 1 : ex2 - ex1 + 1) + 1) / 2;
        int[] tops = new int[80]; // top Y of each layer, counted from the eave
        for (int i = 0, cy = yb; i < tops.length; i++) { int pi = layerRise(prof, i, layersL, p, kick); tops[i] = cy + pi - 1; cy += pi; }

        switch (style) {
            case "flat" -> {
                e.set(x1, yb, z1, x2, yb, z2, solidOf(grad.get(0)));
                e.region(x1, yb + 1, z1, x2, yb + 1, z2, "//walls " + fill);
            }
            case "shed" -> { // lean-to: one slope rising toward the "_inner" side (the wall it leans on)
                String hi = inner.isEmpty() ? str(c, "high", "back") : inner;
                boolean rowsAlongZ = hi.equals("left") || hi.equals("right"); // each row is a line along z
                String toward = switch (hi) { case "left" -> "-x"; case "right" -> "+x"; case "front" -> "-z"; default -> "+z"; };
                int n = rowsAlongZ ? ex2 - ex1 + 1 : ez2 - ez1 + 1;
                for (int k = 0; k < n; k++) {
                    int y = yb + k;
                    String st = k == 0 && trim != null ? trim : gradPick(grad, k, n);
                    String so = solidOf(st);
                    int rx = hi.equals("left") ? ex2 - k : ex1 + k, rz = hi.equals("front") ? ez2 - k : ez1 + k;
                    if (rowsAlongZ) {
                        e.set(rx, y, ez1, rx, y, ez2, stair(st, true, so, toward, e));
                        if (k > 0) e.set(rx, y - 1, ez1, rx, y - 1, ez2, so);
                        if (trim != null) { // verge boards on both open ends
                            e.set(rx, y, ez1, rx, y, ez1, stair(trim, true, solidOf(trim), "+z", e));
                            e.set(rx, y, ez2, rx, y, ez2, stair(trim, true, solidOf(trim), "-z", e));
                        }
                        if (rx >= x1 && rx <= x2 && y - 1 >= yb) { e.set(rx, yb, z1, rx, y - 1, z1, fill); e.set(rx, yb, z2, rx, y - 1, z2, fill); }
                        if (k == 0 && fascia != null) {
                            int fx = hi.equals("left") ? rx + 1 : rx - 1;
                            e.set(fx, y, ez1, fx, y, ez2, fascia + "[facing=" + e.frame.dir(hi.equals("left") ? "+x" : "-x") + ",open=true,half=bottom]");
                        }
                    } else {
                        e.set(ex1, y, rz, ex2, y, rz, stair(st, true, so, toward, e));
                        if (k > 0) e.set(ex1, y - 1, rz, ex2, y - 1, rz, so);
                        if (trim != null) {
                            e.set(ex1, y, rz, ex1, y, rz, stair(trim, true, solidOf(trim), "+x", e));
                            e.set(ex2, y, rz, ex2, y, rz, stair(trim, true, solidOf(trim), "-x", e));
                        }
                        if (rz >= z1 && rz <= z2 && y - 1 >= yb) { e.set(x1, yb, rz, x1, y - 1, rz, fill); e.set(x2, yb, rz, x2, y - 1, rz, fill); }
                        if (k == 0 && fascia != null) {
                            int fz = hi.equals("front") ? rz + 1 : rz - 1;
                            e.set(ex1, y, fz, ex2, y, fz, fascia + "[facing=" + e.frame.dir(hi.equals("front") ? "+z" : "-z") + ",open=true,half=bottom]");
                        }
                    }
                }
            }
            case "hip" -> {
                int layers = (Math.min(ex2 - ex1, ez2 - ez1) + 2) / 2;
                for (int i = 0; ; i++) {
                    int ax = ex1 + i, bx = ex2 - i, az = ez1 + i, bz = ez2 - i;
                    int y = tops[Math.min(i, tops.length - 1)], y0 = i == 0 ? yb : tops[i - 1] + 1;
                    String st = i == 0 && trim != null ? trim : gradPick(grad, i, layers);
                    String so = solidOf(st);
                    if (ax > bx || az > bz) break;
                    // Ridge (with a block under it so it rests on the layer below).
                    if (az == bz) { e.set(ax, y0 - 1, az, bx, y0 - 1, az, so); e.set(ax, y0, az, bx, y, az, trim != null ? solidOf(trim) : so); hipFinials(finAt.equals("all") || finAt.equals("main") ? roofFin : null, e, ax, az, bx, bz, y + 1); ridgeTeeth(e, ridgeCap, ax, az, bx, bz, y + 1); break; }
                    if (ax == bx) { e.set(ax, y0 - 1, az, ax, y0 - 1, bz, so); e.set(ax, y0, az, ax, y, bz, trim != null ? solidOf(trim) : so); hipFinials(finAt.equals("all") || finAt.equals("main") ? roofFin : null, e, ax, az, bx, bz, y + 1); ridgeTeeth(e, ridgeCap, ax, az, bx, bz, y + 1); break; }
                    // Solid backing under each ring (hidden in the attic) so every layer rests on the one below.
                    int yl = i == 0 ? y0 : i == 1 ? yb : y0 - 1; // the ring over the walls reaches down to them
                    if (y > yl) {
                        e.set(ax, yl, az, bx, y - 1, az, so);
                        e.set(ax, yl, bz, bx, y - 1, bz, so);
                        e.set(ax, yl, az + 1, ax, y - 1, bz - 1, so);
                        e.set(bx, yl, az + 1, bx, y - 1, bz - 1, so);
                    }
                    e.set(ax, y, az, bx, y, az, stair(st, true, so, "+z", e));
                    e.set(ax, y, bz, bx, y, bz, stair(st, true, so, "-z", e));
                    if (i == 0 && eaveLip != null) {
                        e.set(ax, y - 1, az, bx, y - 1, az, eaveLip); e.set(ax, y - 1, bz, bx, y - 1, bz, eaveLip);
                        e.set(ax, y - 1, az, ax, y - 1, bz, eaveLip); e.set(bx, y - 1, az, bx, y - 1, bz, eaveLip);
                    }
                    if (i == 0 && big) { // skirt: a lower row one further out all round, hung on a soffit under the eave
                        int sy = y - 1;
                        String sof = slabOf(st).replace("type=bottom", "type=top");
                        e.set(ax, sy, az, bx, sy, az, sof); e.set(ax, sy, bz, bx, sy, bz, sof);
                        e.set(ax, sy, az, ax, sy, bz, sof); e.set(bx, sy, az, bx, sy, bz, sof);
                        if (!skirtSkip.contains("front")) e.set(ax - 1, sy, az - 1, bx + 1, sy, az - 1, stair(st, true, so, "+z", e));
                        if (!skirtSkip.contains("back")) e.set(ax - 1, sy, bz + 1, bx + 1, sy, bz + 1, stair(st, true, so, "-z", e));
                        if (!skirtSkip.contains("left")) e.set(ax - 1, sy, az, ax - 1, sy, bz, stair(st, true, so, "+x", e));
                        if (!skirtSkip.contains("right")) e.set(bx + 1, sy, az, bx + 1, sy, bz, stair(st, true, so, "-x", e));
                    } else if (i == 0 && fascia != null) {
                        e.set(ax, y, az - 1, bx, y, az - 1, fascia + "[facing=" + e.frame.dir("-z") + ",open=true,half=bottom]");
                        e.set(ax, y, bz + 1, bx, y, bz + 1, fascia + "[facing=" + e.frame.dir("+z") + ",open=true,half=bottom]");
                        e.set(ax - 1, y, az, ax - 1, y, bz, fascia + "[facing=" + e.frame.dir("-x") + ",open=true,half=bottom]");
                        e.set(bx + 1, y, az, bx + 1, y, bz, fascia + "[facing=" + e.frame.dir("+x") + ",open=true,half=bottom]");
                    }
                    if (az + 1 <= bz - 1) {
                        e.set(ax, y, az + 1, ax, y, bz - 1, stair(st, true, so, "+x", e));
                        e.set(bx, y, az + 1, bx, y, bz - 1, stair(st, true, so, "-x", e));
                    }
                }
                crossGables(c, e, (x2 - x1) >= (z2 - z1), x1, x2, z1, z2, yb, o, tops, grad, trim, fill, smallFin);
            }
            default -> { // gable
                String ridge = str(c, "ridge", "auto").toLowerCase(Locale.ROOT);
                boolean ridgeAlongX = ridge.equals("x") || (!ridge.equals("z") && (x2 - x1) >= (z2 - z1));
                int span = ridgeAlongX ? (ez2 - ez1 + 1) : (ex2 - ex1 + 1);
                int layers = (span + 1) / 2;
                int peakY = yb;
                // Gable ends: which ones are real (a wing's inner end is hidden in the main roof).
                boolean endA = ridgeAlongX ? !inner.equals("left") : !inner.equals("front");
                boolean endB = ridgeAlongX ? !inner.equals("right") : !inner.equals("back");
                for (int i = 0; ; i++) {
                    int y = tops[Math.min(i, tops.length - 1)], y0 = i == 0 ? yb : tops[i - 1] + 1;
                    String st = i == 0 && trim != null ? trim : gradPick(grad, i, layers);
                    String so = solidOf(st);
                    String edgeA = trim != null ? trim : st, ridgeSolid = trim != null ? solidOf(trim) : so;
                    if (ridgeAlongX) {
                        int a = ez1 + i, b = ez2 - i;
                        if (a > b) break;
                        if (a == b) {
                            e.set(ex1, y0, a, ex2, y, a, ridgeSolid);
                            for (int yy = y0; yy <= y; yy++) fillGable(e, x1, x2, z1, z2, yy, a, a, true, fill, endA, endB);
                            peakY = y;
                            break;
                        }
                        if (y > y0) { e.set(ex1, y0, a, ex2, y0, a, so); e.set(ex1, y0, b, ex2, y0, b, so); }
                        if (i == 1 && y0 > yb) { e.set(x1, yb, a, x2, y0 - 1, a, so); e.set(x1, yb, b, x2, y0 - 1, b, so); } // eave row rests on the wall line
                        e.set(ex1, y, a, ex2, y, a, stair(st, true, so, "+z", e));
                        e.set(ex1, y, b, ex2, y, b, stair(st, true, so, "-z", e));
                        if (thick && i > 0 && y == y0) { e.set(ex1, y - 1, a, ex2, y - 1, a, so); e.set(ex1, y - 1, b, ex2, y - 1, b, so); }
                        if (i == 0 && eaveLip != null) { e.set(ex1, y - 1, a, ex2, y - 1, a, eaveLip); e.set(ex1, y - 1, b, ex2, y - 1, b, eaveLip); }
                        if (i == 0 && big) { // skirt row under the eave, one block further out
                            if (!underside) { String sof = slabOf(st).replace("type=bottom", "type=top"); e.set(ex1, y - 1, a, ex2, y - 1, a, sof); e.set(ex1, y - 1, b, ex2, y - 1, b, sof); }
                            if (!skirtSkip.contains("front")) e.set(ex1, y - 1, a - 1, ex2, y - 1, a - 1, stair(st, true, so, "+z", e));
                            if (!skirtSkip.contains("back")) e.set(ex1, y - 1, b + 1, ex2, y - 1, b + 1, stair(st, true, so, "-z", e));
                        } else if (i == 0 && fascia != null) { // boards hanging on the edge of the eave
                            e.set(ex1, y, a - 1, ex2, y, a - 1, fascia + "[facing=" + e.frame.dir("-z") + ",open=true,half=bottom]");
                            e.set(ex1, y, b + 1, ex2, y, b + 1, fascia + "[facing=" + e.frame.dir("+z") + ",open=true,half=bottom]");
                        }
                        if (big && (i % 2 == 1 || i == 0)) { // ragged gable edge: every other row pokes out one more
                            if (endA) { e.set(ex1 - 1, y, a, ex1 - 1, y, a, stair(st, true, so, "+z", e)); e.set(ex1 - 1, y, b, ex1 - 1, y, b, stair(st, true, so, "-z", e)); }
                            if (endB) { e.set(ex2 + 1, y, a, ex2 + 1, y, a, stair(st, true, so, "+z", e)); e.set(ex2 + 1, y, b, ex2 + 1, y, b, stair(st, true, so, "-z", e)); }
                        }
                        if (trim != null) { // dark boards along the gable edges
                            for (int ex : new int[]{ex1, ex2}) {
                                if ((ex == ex1 && !endA) || (ex == ex2 && !endB)) continue;
                                e.set(ex, y, a, ex, y, a, stair(edgeA, true, solidOf(edgeA), "+z", e));
                                e.set(ex, y, b, ex, y, b, stair(edgeA, true, solidOf(edgeA), "-z", e));
                            }
                        }
                        if (underside && i < o) { // carved boards under the overhang
                            String us = stairPat(trim != null ? trim : st, "+z", true, e), us2 = stairPat(trim != null ? trim : st, "-z", true, e);
                            e.set(ex1, y0 - 1, a, ex2, y0 - 1, a, us);
                            e.set(ex1, y0 - 1, b, ex2, y0 - 1, b, us2);
                        }
                        for (int yy = y0; yy <= y; yy++) fillGable(e, x1, x2, z1, z2, yy, a + 1, b - 1, true, fill, endA, endB);
                        if (a + 1 == b) { // even span: ridge cap over the two top rows
                            e.set(ex1, y + 1, a, ex2, y + 1, b, slabOf(trim != null ? trim : st));
                            peakY = y + 1;
                            break;
                        }
                    } else {
                        int a = ex1 + i, b = ex2 - i;
                        if (a > b) break;
                        if (a == b) {
                            e.set(a, y0, ez1, a, y, ez2, ridgeSolid);
                            for (int yy = y0; yy <= y; yy++) fillGable(e, x1, x2, z1, z2, yy, a, a, false, fill, endA, endB);
                            peakY = y;
                            break;
                        }
                        if (y > y0) { e.set(a, y0, ez1, a, y0, ez2, so); e.set(b, y0, ez1, b, y0, ez2, so); }
                        if (i == 1 && y0 > yb) { e.set(a, yb, z1, a, y0 - 1, z2, so); e.set(b, yb, z1, b, y0 - 1, z2, so); }
                        e.set(a, y, ez1, a, y, ez2, stair(st, true, so, "+x", e));
                        e.set(b, y, ez1, b, y, ez2, stair(st, true, so, "-x", e));
                        if (thick && i > 0 && y == y0) { e.set(a, y - 1, ez1, a, y - 1, ez2, so); e.set(b, y - 1, ez1, b, y - 1, ez2, so); }
                        if (i == 0 && eaveLip != null) { e.set(a, y - 1, ez1, a, y - 1, ez2, eaveLip); e.set(b, y - 1, ez1, b, y - 1, ez2, eaveLip); }
                        if (i == 0 && big) {
                            if (!underside) { String sof = slabOf(st).replace("type=bottom", "type=top"); e.set(a, y - 1, ez1, a, y - 1, ez2, sof); e.set(b, y - 1, ez1, b, y - 1, ez2, sof); }
                            if (!skirtSkip.contains("left")) e.set(a - 1, y - 1, ez1, a - 1, y - 1, ez2, stair(st, true, so, "+x", e));
                            if (!skirtSkip.contains("right")) e.set(b + 1, y - 1, ez1, b + 1, y - 1, ez2, stair(st, true, so, "-x", e));
                        } else if (i == 0 && fascia != null) {
                            e.set(a - 1, y, ez1, a - 1, y, ez2, fascia + "[facing=" + e.frame.dir("-x") + ",open=true,half=bottom]");
                            e.set(b + 1, y, ez1, b + 1, y, ez2, fascia + "[facing=" + e.frame.dir("+x") + ",open=true,half=bottom]");
                        }
                        if (big && (i % 2 == 1 || i == 0)) {
                            if (endA) { e.set(a, y, ez1 - 1, a, y, ez1 - 1, stair(st, true, so, "+x", e)); e.set(b, y, ez1 - 1, b, y, ez1 - 1, stair(st, true, so, "-x", e)); }
                            if (endB) { e.set(a, y, ez2 + 1, a, y, ez2 + 1, stair(st, true, so, "+x", e)); e.set(b, y, ez2 + 1, b, y, ez2 + 1, stair(st, true, so, "-x", e)); }
                        }
                        if (trim != null) {
                            for (int ez : new int[]{ez1, ez2}) {
                                if ((ez == ez1 && !endA) || (ez == ez2 && !endB)) continue;
                                e.set(a, y, ez, a, y, ez, stair(edgeA, true, solidOf(edgeA), "+x", e));
                                e.set(b, y, ez, b, y, ez, stair(edgeA, true, solidOf(edgeA), "-x", e));
                            }
                        }
                        if (underside && i < o) {
                            String us = stairPat(trim != null ? trim : st, "+x", true, e), us2 = stairPat(trim != null ? trim : st, "-x", true, e);
                            e.set(a, y0 - 1, ez1, a, y0 - 1, ez2, us);
                            e.set(b, y0 - 1, ez1, b, y0 - 1, ez2, us2);
                        }
                        for (int yy = y0; yy <= y; yy++) fillGable(e, x1, x2, z1, z2, yy, a + 1, b - 1, false, fill, endA, endB);
                        if (a + 1 == b) {
                            e.set(a, y + 1, ez1, b, y + 1, ez2, slabOf(trim != null ? trim : st));
                            peakY = y + 1;
                            break;
                        }
                    }
                }
                // Finials (timber post + spire) on the gable peaks, and the ridge beam poking out under them.
                Fin fn = roofFin;
                if (fn != null) {
                    int mid = ridgeAlongX ? (ez1 + ez2) / 2 : (ex1 + ex2) / 2;
                    int[] ends = ridgeAlongX ? new int[]{ex1, ex2} : new int[]{ez1, ez2};
                    for (int k = 0; k < 2; k++) {
                        if ((k == 0 && !endA) || (k == 1 && !endB)) continue;
                        int fx = ridgeAlongX ? ends[k] : mid, fz = ridgeAlongX ? mid : ends[k];
                        boolean finHere = finAt.equals("all") || finAt.equals("main") || (finAt.equals("front") && !ridgeAlongX && k == 0);
                        if (finHere) fn.at(e, fx, peakY + 1, fz);
                        if (fn.post() != null && fn.beam() != null && o >= 1) { // beam end one block past the verge
                            int out = k == 0 ? -1 : 1;
                            int bx = ridgeAlongX ? fx + out : fx, bz = ridgeAlongX ? fz : fz + out;
                            e.set(bx, peakY, bz, bx, peakY, bz, axisState(fn.beam(), e.frame.axis(ridgeAlongX ? 'x' : 'z'), e));
                            if (finHere && bool(c, "gableLanterns", false)) { // a lantern on a short chain under the beam end
                                e.set(bx, peakY - 1, bz, bx, peakY - 1, bz, "iron_chain[axis=y]");
                                e.set(bx, peakY - 2, bz, bx, peakY - 2, bz, "lantern[hanging=true]");
                            }
                        }
                    }
                }
                if (ridgeCap != null) { // toothed ridge: a cap block on every other ridge block
                    int mid = ridgeAlongX ? (ez1 + ez2) / 2 : (ex1 + ex2) / 2;
                    if (ridgeAlongX) ridgeTeeth(e, ridgeCap, ex1, mid, ex2, mid, peakY + 1);
                    else ridgeTeeth(e, ridgeCap, mid, ez1, mid, ez2, peakY + 1);
                }
                // Cross gables on the long slopes, then dormers where there are none.
                java.util.Set<String> crossed = crossGables(c, e, ridgeAlongX, x1, x2, z1, z2, yb, o, tops, grad, trim, fill, smallFin);
                int n = clamp(num(c, "dormers", 0), 0, 4);
                String skipSide = str(c, "_dormerSkip", "");
                if (n > 0) {
                    String win = paneState(block(c, "window", "glass_pane"), e.frame.axis(ridgeAlongX ? 'x' : 'z'));
                    String dRoof = trim != null ? trim : grad.get(0);
                    int dd = 1; // depth into the roof until the dormer meets the slope
                    while (o + dd < tops.length - 1 && tops[o + dd] < yb + 5) dd++;
                    int lo = ridgeAlongX ? x1 : z1, hi = ridgeAlongX ? x2 : z2;
                    if (hi - lo >= 6) {
                        for (int k = 1; k <= n; k++) {
                            int pos = lo + (int) Math.round(k * (hi - lo) / (double) (n + 1));
                            pos = Math.max(lo + 2, Math.min(hi - 2, pos));
                            Side[] sides = ridgeAlongX
                                ? new Side[]{new Side("front", pos, z1), new Side("back", pos, z2)}
                                : new Side[]{new Side("left", x1, pos), new Side("right", x2, pos)};
                            for (Side sd : sides) {
                                if (java.util.Arrays.asList(skipSide.split(",")).contains(sd.name()) || crossed.contains(sd.name())) continue;
                                dormer(e, sd, yb, o, dd, fill, win, dRoof);
                                String tipD = optBlock(c, "finial");
                                if (tipD != null && smallFin != null) smallFin.at(e, sd.x(0, 1), yb + 6, sd.z(0, 1));
                            }
                        }
                    }
                }
            }
        }
        // Moss or snow on the slopes, then the finials on top (so nothing lands on the spires).
        int ov0 = Math.max(o, 1);
        if (bool(c, "snowRoof", false)) {
            e.region(x1 - ov0, yb, z1 - ov0, x2 + ov0, yb + 40, z2 + ov0, "//overlay snow");
        } else if (bool(c, "roofMoss", false)) {
            e.region(x1 - ov0, yb, z1 - ov0, x2 + ov0, yb + 40, z2 + ov0, "//overlay 9%moss_carpet,91%air");
        }
        if (roofFin != null) roofFin.flush(e);
        stripes = false;
    }

    /**
     * Gable finial: a timber post standing through the peak (post), a thin spire above it (tip) and an
     * optional top piece (cap: a lantern or slab). Null when the roof has no finials.
     */
    private record Fin(String post, String tip, String cap, String beam, List<int[]> pending) {
        /** Remembers a finial (y = first block above the roof peak); drawn by flush() after moss/snow. */
        void at(Emitter e, int x, int y, int z) {
            pending.add(new int[]{x, y, z});
        }
        void flush(Emitter e) {
            for (int[] p : pending) {
                int x = p[0], y = p[1], z = p[2];
                if (post != null) e.set(x, y - 1, z, x, y, z, post);
                int ty = post != null ? y + 1 : y;
                if (tip != null) e.set(x, ty, z, x, ty, z, tip);
                if (cap != null) e.set(x, ty + 1, z, x, ty + 1, z, cap);
            }
            pending.clear();
        }
    }

    /** Cap blocks on every other block of a ridge line (ends left free for finials). */
    private static void ridgeTeeth(Emitter e, String cap, int ax, int az, int bx, int bz, int y) {
        if (cap == null) return;
        if (az == bz) for (int x = ax + 1; x < bx; x += 2) e.set(x, y, az, x, y, az, cap);
        else if (ax == bx) for (int z = az + 1; z < bz; z += 2) e.set(ax, y, z, ax, y, z, cap);
    }

    /** Finials on both ends of a hip roof's ridge (only when the ridge is a line, not a point). */
    private static void hipFinials(Fin fn, Emitter e, int ax, int az, int bx, int bz, int y) {
        if (fn == null) return;
        fn.at(e, ax, y, az);
        if (ax != bx || az != bz) fn.at(e, bx, y, bz);
    }

    private static Fin fin(JsonObject c, Emitter e) {
        String tip = optBlock(c, "finial");
        boolean post = bool(c, "finialPost", false);
        String frame = optBlock(c, "frame");
        String postB = post && frame != null ? axisState(frame, "y", e) : null;
        if (tip == null && postB == null) return null;
        String cap = optBlock(c, "finialCap");
        return new Fin(postB, tip, cap, frame, new ArrayList<>());
    }

    /** Cross gables on the long (eave) sides of a roof; returns the sides that got them. */
    private static java.util.Set<String> crossGables(JsonObject c, Emitter e, boolean longAlongX, int x1, int x2, int z1, int z2,
                                                     int yb, int o, int[] tops, List<String> grad, String trim, String fill, Fin fn) {
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        int n0 = clamp(num(c, "crossGables", 0), 0, 3);
        if (n0 == 0) return done;
        String sidesStr = str(c, "crossGableSides", longAlongX ? "front" : "left,right").toLowerCase(Locale.ROOT);
        int layersMain = ((longAlongX ? z2 - z1 : x2 - x1) + 2 * o + 2) / 2;
        int gw = num(c, "crossGableWidth", 5);
        int h = clamp((gw - 1) / 2, 1, Math.max(1, layersMain - o - 2)); // never taller than the main ridge
        String beam = optBlock(c, "frame");
        for (String side : new String[]{"front", "back", "left", "right"}) {
            if (!sidesStr.contains(side)) continue;
            boolean onX = side.equals("front") || side.equals("back");
            if (onX != longAlongX) continue; // only on slopes that face that way
            int lo = onX ? x1 : z1, hi = onX ? x2 : z2;
            int first = lo + h + 1, last = hi - h - 1, n = n0;
            while (n > 1 && (last - first) < (n - 1) * 2 * (h + o)) n--; // neighbours may share a valley
            if (last < first) continue;
            for (int k = 0; k < n; k++) {
                int pos = n == 1 ? (lo + hi) / 2 : first + (int) Math.round(k * (last - first) / (double) (n - 1));
                Side s = switch (side) {
                    case "front" -> new Side("front", pos, z1);
                    case "back" -> new Side("back", pos, z2);
                    case "left" -> new Side("left", x1, pos);
                    default -> new Side("right", x2, pos);
                };
                crossGable(e, s, h, o, yb, tops, layersMain, grad, trim, fill, beam, block(c, "window", "glass_pane"), fn);
            }
            done.add(side);
        }
        return done;
    }

    /**
     * One cross gable: the wall rises into a gable on the eave side, its own little roof runs back
     * until it disappears into the main slope, with a window and a king post in the gable face.
     */
    private static void crossGable(Emitter e, Side s, int h, int o, int yb, int[] tops, int layersMain, List<String> grad,
                                   String trim, String fill, String beam, String window, Fin fn) {
        int hw = h + o; // half width including the overhang
        int apex = yb;
        int peak = tops[Math.min(tops.length - 1, hw)] + 1;
        setUV(e, s, -hw, 1, hw, o + 1, yb, peak + 1, "air"); // break the main eave (and its fascia) in front of the gable
        for (int j = 0; j <= hw; j++) {
            int a = -hw + j, b = hw - j;
            if (a > b) break;
            int y = tops[j], y0 = j == 0 ? yb : tops[j - 1] + 1;
            int i = j + 1; // how far back: until the main roof is above this layer
            while (i < tops.length - 1 && tops[i] < y + 1) i++;
            int vEnd = o - i;
            String st = j == 0 && trim != null ? trim : gradPick(grad, j, layersMain);
            String so = solidOf(st), ridgeSolid = trim != null ? solidOf(trim) : so;
            if (a == b) {
                setUV(e, s, a, o, a, vEnd, y0, y, ridgeSolid);
                apex = y;
                break;
            }
            if (y > y0) { setUV(e, s, a, o, a, vEnd, y0, y0, so); setUV(e, s, b, o, b, vEnd, y0, y0, so); }
            setUV(e, s, a, o, a, vEnd, y, y, stair(st, true, so, s.alongDir(), e));
            setUV(e, s, b, o, b, vEnd, y, y, stair(st, true, so, s.alongDirOpposite(), e));
            if (trim != null) { // dark boards along the gable edge
                setUV(e, s, a, o, a, o, y, y, stair(trim, true, solidOf(trim), s.alongDir(), e));
                setUV(e, s, b, o, b, o, y, y, stair(trim, true, solidOf(trim), s.alongDirOpposite(), e));
            }
            int fa = Math.max(a + 1, -h), fz = Math.min(b - 1, h);
            if (fa <= fz) setUV(e, s, fa, 0, fz, 0, y0, y, fill); // gable wall on the wall line
            if (a + 1 == b) {
                setUV(e, s, a, o, b, vEnd, y + 1, y + 1, slabOf(trim != null ? trim : st));
                apex = y + 1;
                break;
            }
        }
        if (fn != null && apex > yb) fn.at(e, s.x(0, o), apex + 1, s.z(0, o));
        // Window and timber in the gable face.
        String axis = e.frame.axis(s.alongAxis());
        if (peak - yb >= 4) {
            int ww = 0; // one narrow window (the references keep glass small)
            setUV(e, s, -ww, 0, ww, 0, yb + 1, yb + 2, paneState(window, axis));
            if (beam != null) {
                setUV(e, s, -h, 0, h, 0, yb, yb, axisState(beam, axis, e));               // tie beam
                if (peak - 2 >= yb + 3) setUV(e, s, 0, 0, 0, 0, yb + 3, peak - 2, axisState(beam, "y", e)); // king post
            }
        }
    }

    /** Roof gradient layers: "a|b|c" (each layer may be a weighted mix) or the older "a,b,c". */
    private static List<String> gradient(String s) {
        List<String> out = new ArrayList<>();
        List<String> parts = s.contains("|") ? java.util.Arrays.asList(s.split("\\|")) : VoxelGrid.splitPattern(s);
        for (String s0 : parts) {
            String s1 = s0.trim();
            if (s1.isEmpty()) continue;
            out.add(safeBlock(s1));
        }
        return out;
    }

    /** A small gabled window standing out of a roof slope. s: on the wall plane, v points out of the slope. */
    private static void dormer(Emitter e, Side s, int yb, int o, int dd0, String wall, String window, String roofStairs) {
        int dd = Math.max(1, dd0);
        if (o > 0) setUV(e, s, -1, 1, 1, o + 1, yb, yb + 2, "air");   // open the eave (and fascia) in front of it
        setUV(e, s, -1, -dd, -1, 0, yb, yb + 2, wall);              // cheeks
        setUV(e, s, 1, -dd, 1, 0, yb, yb + 2, wall);
        setUV(e, s, -1, 0, 1, 0, yb, yb + 3, wall);                 // face
        setUV(e, s, 0, 0, 0, 0, yb + 4, yb + 4, wall);
        setUV(e, s, 0, 0, 0, 0, yb, yb + 1, window);
        setUV(e, s, 0, -dd, 0, -1, yb, yb + 1, "air");              // opening into the attic
        String plus = s.alongDir(), minus = s.alongDirOpposite();
        String so = solidOf(roofStairs);
        setUV(e, s, -2, -dd, -2, 1, yb + 3, yb + 3, stair(roofStairs, true, so, plus, e));
        setUV(e, s, 2, -dd, 2, 1, yb + 3, yb + 3, stair(roofStairs, true, so, minus, e));
        setUV(e, s, -1, -dd, -1, 1, yb + 4, yb + 4, stair(roofStairs, true, so, plus, e));
        setUV(e, s, 1, -dd, 1, 1, yb + 4, yb + 4, stair(roofStairs, true, so, minus, e));
        setUV(e, s, 0, -dd, 0, 1, yb + 5, yb + 5, so);
    }

    private static String slabOf(String stairs) {
        if (stairs.contains(",")) { // weighted mix: convert each part
            List<String> out = new ArrayList<>();
            for (String part : VoxelGrid.splitPattern(stairs)) {
                int pct = part.indexOf('%');
                out.add((pct >= 0 ? part.substring(0, pct + 1) : "") + slabOf(pct >= 0 ? part.substring(pct + 1) : part));
            }
            return String.join(",", out);
        }
        String b = VoxelGrid.baseId(stairs);
        if (b.endsWith("_stairs")) return b.substring(0, b.length() - 7) + "_slab[type=bottom]";
        return solidOf(b);
    }

    /** Fills the triangular gable ends between the two stair rows of one roof layer. */
    private static void fillGable(Emitter e, int x1, int x2, int z1, int z2, int y,
                                  int from, int to, boolean ridgeAlongX, String fill, boolean endA, boolean endB) {
        if (ridgeAlongX) {
            int a = Math.max(from, z1), b = Math.min(to, z2);
            if (a > b) return;
            if (endA) e.set(x1, y, a, x1, y, b, fill);
            if (endB) e.set(x2, y, a, x2, y, b, fill);
        } else {
            int a = Math.max(from, x1), b = Math.min(to, x2);
            if (a > b) return;
            if (endA) e.set(a, y, z1, b, y, z1, fill);
            if (endB) e.set(a, y, z2, b, y, z2, fill);
        }
    }

    /** Round tower. x,z = center. roof: cone | crenellated | flat | none. */
    private static void tower(JsonObject c, Emitter e) {
        if ("square".equalsIgnoreCase(str(c, "shape", ""))) { squareTower(c, e); return; }
        int r = clamp(num(c, "radius", 3), 2, 16);
        int h = clamp(num(c, "height", Math.max(12, r * 4)), 4, 64);
        int cx = num(c, "x", 0), cz = num(c, "z", r + 2);
        String wall = block(c, "wall", "stone_bricks");
        String foundation = block(c, "foundation", wall);
        String floor = block(c, "floor", "spruce_planks");
        String roofBlock = block(c, "roofBlock", "dark_oak_planks");
        String style = str(c, "roof", "cone");
        String window = block(c, "window", "air");
        int floorH = clamp(num(c, "floorHeight", 5), 3, 10);

        int[] g = e.groundRange(cx - r, cz - r, cx + r, cz + r);
        int by = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        int fb = g == null ? by - 1 : Math.max(by - 16, Math.min(by - 1, g[0]));

        e.shape(cx, fb, cz, "//cyl " + foundation + " " + r + " " + (by - fb));
        e.shape(cx, by, cz, "//cyl air " + (r - 1) + " " + h);
        e.shape(cx, by, cz, "//hcyl " + wall + " " + r + " " + h);
        e.shape(cx, by - 1, cz, "//cyl " + floor + " " + (r - 1) + " 1");
        for (int y = by + floorH; y < by + h - 2; y += floorH) {
            e.shape(cx, y, cz, "//cyl " + floor + " " + (r - 1) + " 1");
        }
        // Upper section in a different material (e.g. timber-framed plaster over a stone base).
        String upperWall = optBlock(c, "upperWall");
        String tFrame = optBlock(c, "frame");
        if (upperWall != null) {
            int uf = by + clamp(num(c, "upperFrom", h / 2), 1, h - 1);
            e.shape(cx, uf, cz, "//hcyl " + upperWall + " " + r + " " + (by + h - uf));
            if (tFrame != null) {
                String vpost = axisState(tFrame, "y", e);
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI / 4 * k;
                    int px = cx + (int) Math.round(r * Math.cos(ang)), pz = cz + (int) Math.round(r * Math.sin(ang));
                    e.set(px, uf, pz, px, by + h - 1, pz, vpost);
                }
                String beam = VoxelGrid.baseId(tFrame).contains("log") ? VoxelGrid.baseId(tFrame).replace("stripped_", "").replace("_log", "_planks") : tFrame;
                e.shape(cx, uf, cz, "//hcyl " + beam + " " + r + " 1");
                e.shape(cx, by + h - 1, cz, "//hcyl " + beam + " " + r + " 1");
            }
        }
        // Decorative rings of a contrasting block, e.g. every 6 blocks.
        String trim = optBlock(c, "trim");
        if (trim != null) {
            int every = clamp(num(c, "trimSpacing", 6), 3, 16);
            for (int y = by + every; y < by + h - 1; y += every) {
                e.shape(cx, y, cz, "//hcyl " + trim + " " + r + " 1");
            }
            e.shape(cx, by + h - 1, cz, "//hcyl " + trim + " " + r + " 1"); // top ring
        }
        if (bool(c, "slits", true)) {
            int winH = clamp(num(c, "windowHeight", 2), 1, 4);
            for (int y = by + 2; y + winH - 1 < by + h - 1; y += Math.max(4, winH + 2)) {
                int t = y + winH - 1;
                e.set(cx + r, y, cz, cx + r, t, cz, window);
                e.set(cx - r, y, cz, cx - r, t, cz, window);
                e.set(cx, y, cz + r, cx, t, cz + r, window);
                e.set(cx, y, cz - r, cx, t, cz - r, window);
            }
        }
        // Wraparound balcony at a height above the base, with a railing.
        int balconyY = num(c, "balconyY", 0);
        if (balconyY > 1 && balconyY < h - 1) {
            int y = by + balconyY;
            String deck = block(c, "balconyBlock", floor);
            String rail = block(c, "railing", "dark_oak_fence");
            e.shape(cx, y, cz, "//hcyl " + deck + " " + (r + 1) + " 1");
            e.shape(cx, y, cz, "//hcyl " + deck + " " + (r + 2) + " 1");
            e.shape(cx, y + 1, cz, "//hcyl " + rail + " " + (r + 2) + " 1");
            e.set(cx, y + 1, cz - r, cx, y + 2, cz - r, "air"); // doorway out to the balcony (front)
        }
        // Ladder up the inside back wall, through every floor (and to the roof for crenellated towers).
        if (!"none".equals(str(c, "access", "ladder")) && r >= 2) {
            int lastFloor = by - 1;
            for (int y = by + floorH; y < by + h - 2; y += floorH) lastFloor = y;
            if (style.equals("crenellated")) lastFloor = by + h - 1;
            if (lastFloor >= by) {
                e.set(cx, by, cz + r - 1, cx, lastFloor, cz + r - 1, "ladder[facing=" + e.frame.dir("-z") + "]");
            }
        }
        // Light inside the top room.
        if (bool(c, "lights", true)) {
            e.set(cx, by + h - 2, cz, cx, by + h - 2, cz, block(c, "light", "lantern[hanging=true]"));
        }
        // Front door at ground level.
        String door = str(c, "door", "air");
        if (!door.equals("none")) {
            String d0 = safeBlock(door);
            String inward = e.frame.dir("+z");
            if (d0.endsWith("_door")) {
                e.set(cx, by, cz - r, cx, by, cz - r, d0 + "[facing=" + inward + ",half=lower,hinge=left]");
                e.set(cx, by + 1, cz - r, cx, by + 1, cz - r, d0 + "[facing=" + inward + ",half=upper,hinge=left]");
            } else {
                e.set(cx, by, cz - r, cx, by + 1, cz - r, "air");
            }
        }
        int ty = by + h;
        switch (style) {
            case "crenellated" -> {
                e.shape(cx, ty - 1, cz, "//cyl " + floor + " " + (r - 1) + " 1");
                e.shape(cx, ty, cz, "//hcyl " + wall + " " + r + " 1");
                int n = Math.max(8, (int) Math.round(2 * Math.PI * r / 2));
                for (int k = 0; k < n; k += 2) {
                    double a = 2 * Math.PI * k / n;
                    int mx = cx + (int) Math.round(r * Math.cos(a)), mz = cz + (int) Math.round(r * Math.sin(a));
                    e.set(mx, ty + 1, mz, mx, ty + 1, mz, wall);
                }
            }
            case "flat" -> e.shape(cx, ty, cz, "//cyl " + solidOf(roofBlock) + " " + r + " 1");
            case "none" -> { }
            case "dome" -> {
                int k = 0;
                for (; k <= r; k++) {
                    int rr = (int) Math.round(Math.sqrt(sq(r + 0.5) - sq(k)));
                    if (rr < 1) break;
                    e.shape(cx, ty + k, cz, "//cyl " + roofBlock + " " + rr + " 1");
                }
                String tip = optBlock(c, "tip");
                if (tip != null) e.set(cx, ty + k, cz, cx, ty + k, cz, tip);
            }
            case "onion" -> { // fantasy / eastern onion dome: swells out, then tapers to a spire
                List<Integer> radii = new ArrayList<>();
                radii.add(r); radii.add(r + 1); radii.add(r + 1); radii.add(r);
                for (int rr = r - 1; rr >= 1; rr--) radii.add(rr);
                int k = 0;
                for (int rr : radii) e.shape(cx, ty + k++, cz, "//cyl " + roofBlock + " " + rr + " 1");
                String tip = block(c, "tip", "lightning_rod");
                e.set(cx, ty + k, cz, cx, ty + k + 1, cz, solidOf(roofBlock));
                e.set(cx, ty + k + 2, cz, cx, ty + k + 2, cz, tip);
            }
            default -> { // cone, optionally flared out past the walls and shaded bottom -> top
                int steep = clamp(num(c, "roofSteepness", 2), 1, 3);
                int flare = clamp(num(c, "roofOverhang", 1), 0, 3);
                List<String> grad = gradient(str(c, "roofGradient", roofBlock));
                if (grad.isEmpty()) grad.add(roofBlock);
                int layersN = r + flare;
                int k = 0, yc = ty;
                for (int rr = r + flare; rr >= 1; rr--, k++) {
                    String layer = solidOf(grad.get(Math.min(grad.size() - 1, k * grad.size() / Math.max(1, layersN)))); // full blocks for a cone
                    int hgt = rr > r ? 1 : steep; // flared skirt: thin rings sticking out past the wall
                    e.shape(cx, yc, cz, "//cyl " + layer + " " + rr + " " + hgt);
                    yc += hgt;
                }
                int tip = yc;
                e.set(cx, tip, cz, cx, tip, cz, solidOf(roofBlock));
            }
        }
    }

    /** Straight castle/garden wall between (x,z) and (x2,z2). Must be axis-aligned. */
    private static void wall(JsonObject c, Emitter e) {
        int ax = num(c, "x", 0), az = num(c, "z", 0);
        // Missing end point: run `length` blocks (default 12) to the right.
        int bx = num(c, "x2", c.has("z2") ? ax : ax + num(c, "length", 12) - 1);
        int bz = num(c, "z2", az);
        if (ax != bx && az != bz) throw new PlanException("walls must be straight along x or z; use two walls");
        int h = clamp(num(c, "height", 5), 1, 32);
        int t = clamp(num(c, "thickness", 1), 1, 5);
        String block = block(c, "wall", block(c, "block", "stone_bricks"));
        boolean along = az == bz; // runs along local x
        int lo = -(t - 1) / 2, hi = t / 2;

        int x1 = Math.min(ax, bx), x2 = Math.max(ax, bx), z1 = Math.min(az, bz), z2 = Math.max(az, bz);
        int rx1 = along ? x1 : x1 + lo, rx2 = along ? x2 : x1 + hi;
        int rz1 = along ? z1 + lo : z1, rz2 = along ? z1 + hi : z2;
        int[] g = e.groundRange(rx1, rz1, rx2, rz2);
        int bottom = g == null ? 0 : Math.max(g[1] - 16, g[0]);
        int top = (g == null ? 0 : g[1]) + h + num(c, "y", 0); // level top above the highest ground
        e.set(rx1, bottom, rz1, rx2, top, rz2, block);

        if (bool(c, "crenellations", true)) {
            int len = along ? (x2 - x1 + 1) : (z2 - z1 + 1);
            int pairs = len / 2 - 1;
            String dir = e.frame.dir(along ? "+x" : "+z");
            int[] edges = t == 1 ? new int[]{lo} : new int[]{lo, hi};
            for (int p : edges) {
                int sx = along ? x1 : x1 + p, sz = along ? z1 + p : z1;
                e.set(sx, top + 1, sz, sx, top + 1, sz, block);
                if (pairs > 0) {
                    int nx = along ? sx + 1 : sx, nz = along ? sz : sz + 1;
                    e.region(sx, top + 1, sz, nx, top + 1, nz, "//stack " + pairs + " " + dir);
                }
            }
        }
    }

    /** Terrain-following path from (x,z) to (x2,z2): goes along x first, then along z. */
    private static void path(JsonObject c, Emitter e) {
        // Missing end point: lead from the start back to the player (origin).
        int ax = num(c, "x", 0), az = num(c, "z", 0), bx = num(c, "x2", 0), bz = num(c, "z2", 0);
        int w = clamp(num(c, "width", 3), 1, 7);
        String block = block(c, "block", "60%dirt_path,25%gravel,15%coarse_dirt");
        int lo = -(w - 1) / 2, hi = w / 2;
        if (ax != bx) pathSegment(e, Math.min(ax, bx), az + lo, Math.max(ax, bx), az + hi, block);
        if (az != bz) pathSegment(e, bx + lo, Math.min(az, bz), bx + hi, Math.max(az, bz), block);
        if (ax == bx && az == bz) throw new PlanException("path start and end are the same");
    }

    private static void pathSegment(Emitter e, int x1, int z1, int x2, int z2, String block) {
        int[] g = e.groundRange(x1, z1, x2, z2);
        int lo = g == null ? -1 : g[0], hi = g == null ? -1 : g[1];
        e.region(x1, lo, z1, x2, hi, z2, "//replace " + PATH_MASK + " " + block);
        e.region(x1, lo + 1, z1, x2, hi + 2, z2, "//replace " + PLANTS + " air");
    }

    /** A tree at (x,z). kind: oak/spruce/birch/...; size: small/medium/large. */
    private static void tree(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 0);
        String kind = str(c, "kind", "oak").toLowerCase(Locale.ROOT);
        if (!TREE_KINDS.contains(kind)) kind = "oak";
        String size = str(c, "size", "medium").toLowerCase(Locale.ROOT);
        int th = size.equals("small") ? 4 : size.equals("large") ? 8 : 6;
        int lr = size.equals("small") ? 2 : size.equals("large") ? 4 : 3;
        String log = kind + "_log";
        String leaves = "85%" + kind + "_leaves[persistent=true],15%air";

        int[] g = e.groundRange(x, z, x, z);
        int base = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        int topY = base + th - 1;
        if (kind.equals("spruce")) {
            int y = base + 2;
            for (int rr = lr; rr >= 1; rr--) {
                e.shape(x, y, z, "//cyl " + leaves + " " + rr + " 2");
                y += 2;
            }
            topY = y - 1;
            e.set(x, topY + 1, z, x, topY + 1, z, kind + "_leaves[persistent=true]");
        } else {
            e.shape(x, topY, z, "//sphere " + leaves + " " + lr);
        }
        e.set(x, base, z, x, topY, z, log);
    }

    private static void lampPost(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 0);
        int h = clamp(num(c, "height", 3), 1, 8);
        String post = block(c, "post", "spruce_fence");
        String light = block(c, "light", "lantern");
        int[] g = e.groundRange(x, z, x, z);
        int base = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        e.set(x, base, z, x, base + h - 1, z, post);
        e.set(x, base + h, z, x, base + h, z, light);
    }

    /** box/clear: corners (x,y,z) and (x2,y2,z2), y relative to the anchor. fill: solid|walls|faces. */
    private static void box(JsonObject c, Emitter e, boolean clear) {
        int x1 = num(c, "x", 0), y1 = num(c, "y", 0), z1 = num(c, "z", 0);
        // Missing far corner: use width/height/depth (default a single block).
        int x2 = num(c, "x2", x1 + num(c, "width", 1) - 1);
        int y2 = num(c, "y2", y1 + num(c, "height", 1) - 1);
        int z2 = num(c, "z2", z1 + num(c, "depth", 1) - 1);
        if (clear) {
            e.set(x1, y1, z1, x2, y2, z2, "air");
            return;
        }
        String block = block(c, "block", "stone");
        String fill = str(c, "fill", "solid").toLowerCase(Locale.ROOT);
        switch (fill) {
            case "walls" -> e.region(x1, y1, z1, x2, y2, z2, "//walls " + block);
            case "faces", "hollow", "outline" -> e.region(x1, y1, z1, x2, y2, z2, "//faces " + block);
            default -> e.set(x1, y1, z1, x2, y2, z2, block);
        }
    }

    /** Replace blocks in a region: mask = what to replace (optional), block = new pattern. */
    private static void texture(JsonObject c, Emitter e) {
        int x1 = num(c, "x", 0), y1 = num(c, "y", 0), z1 = num(c, "z", 0);
        // Missing far corner: use width/height/depth (default a single block).
        int x2 = num(c, "x2", x1 + num(c, "width", 1) - 1);
        int y2 = num(c, "y2", y1 + num(c, "height", 1) - 1);
        int z2 = num(c, "z2", z1 + num(c, "depth", 1) - 1);
        String block = block(c, "block", null);
        if (block == null) throw new PlanException("texture needs a block pattern");
        String mask = optBlock(c, "mask");
        e.region(x1, y1, z1, x2, y2, z2, "//replace " + (mask == null ? "" : mask + " ") + block);
    }

    private static void sphere(JsonObject c, Emitter e) {
        int r = clamp(num(c, "radius", 3), 1, 24);
        String cmd = bool(c, "hollow", false) ? "//hsphere " : "//sphere ";
        e.shape(num(c, "x", 0), num(c, "y", 0), num(c, "z", 0), cmd + block(c, "block", "stone") + " " + r);
    }

    private static void cylinder(JsonObject c, Emitter e) {
        int r = clamp(num(c, "radius", 3), 1, 24);
        int h = clamp(num(c, "height", 1), 1, 64);
        String cmd = bool(c, "hollow", false) ? "//hcyl " : "//cyl ";
        e.shape(num(c, "x", 0), num(c, "y", 0), num(c, "z", 0), cmd + block(c, "block", "stone") + " " + r + " " + h);
    }

    private static void singleBlock(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), y = num(c, "y", 0), z = num(c, "z", 0);
        e.set(x, y, z, x, y, z, block(c, "block", "stone"));
    }

    // ======================= attached & extra pieces =======================

    /** Where a building ended up (computed before anything is placed, so attachments line up). */
    static final class BuildingInfo {
        int x1, x2, z1, z2, by, top, h, floors, roofTop, doorX, jetty;
        boolean jF, jB, jL, jR, recess, stoneGround;
        /** Calm settings: lanterns everywhere outside (or only by the door), a quieter yard. */
        boolean lightsAll = true, quiet;
        /** Wings joined to this building: {side index (front,back,left,right), from, to, floors}. */
        final List<int[]> wings = new ArrayList<>();

        boolean hasWing(String side) {
            for (int[] w : wings) if (SIDES[w[0]].equals(side)) return true;
            return false;
        }

        List<int[]> wingSpans(String side) {
            List<int[]> out = new ArrayList<>();
            for (int[] w : wings) if (SIDES[w[0]].equals(side)) out.add(new int[]{w[1], w[2]});
            return out;
        }
        String wall, floor, foundation, frame, wood;
        /** Walls that have a porch attached (front/back/left/right). */
        final java.util.Set<String> porchSides = new java.util.HashSet<>();

        /** Footprint {x1,x2,z1,z2} of floor f; upper floors step out on jettied sides. */
        int[] rect(int f) {
            int off = Math.min(3, jetty * f);
            return new int[]{x1 - (jL ? off : 0), x2 + (jR ? off : 0), z1 - (jF ? off : 0), z2 + (jB ? off : 0)};
        }
        /** Inside cells (local x,z) kept free of furniture because a porch/balcony door opens there. */
        final java.util.Set<Long> keepClear = new java.util.HashSet<>();
    }

    private static long cell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Geometry of a building component without emitting anything (shared by building() and attachments). */
    private static BuildingInfo buildingInfo(JsonObject c, Emitter e) {
        BuildingInfo b = new BuildingInfo();
        int w = clamp(num(c, "width", 9), 5, 64);
        int d = clamp(num(c, "depth", Math.max(5, w - 2)), 5, 64);
        b.x1 = num(c, "x", 0) - (w - 1) / 2; b.x2 = b.x1 + w - 1;
        b.z1 = num(c, "z", 2); b.z2 = b.z1 + d - 1;
        b.floors = clamp(num(c, "floors", 1), 1, 6);
        b.h = clamp(num(c, "floorHeight", 4), 3, 8);
        int[] g = e.groundRange(b.x1, b.z1, b.x2, b.z2);
        b.by = c.has("_baseY") ? num(c, "_baseY", 0) : (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        b.top = b.by + b.floors * b.h - 1;
        b.jetty = clamp(num(c, "jetty", 0), 0, 2);
        String js = str(c, "jettySides", "front,back").toLowerCase(Locale.ROOT);
        boolean all = js.contains("all");
        b.jF = b.jetty > 0 && (all || js.contains("front"));
        b.jB = b.jetty > 0 && (all || js.contains("back"));
        b.jL = b.jetty > 0 && (all || js.contains("left"));
        b.jR = b.jetty > 0 && (all || js.contains("right"));
        int[] tr = b.rect(b.floors - 1);
        int tw = tr[1] - tr[0] + 1, td = tr[3] - tr[2] + 1;
        String style = str(c, "roof", "gable").toLowerCase(Locale.ROOT);
        int o = clamp(num(c, "overhang", 1), 0, 3), p = clamp(num(c, "pitch", 1), 1, 2);
        int kick = p > 1 ? clamp(num(c, "kick", 0), 0, 2) : 0;
        String ridge = str(c, "ridge", "auto").toLowerCase(Locale.ROOT);
        boolean ridgeAlongX = ridge.equals("x") || (!ridge.equals("z") && (tw - 1) >= (td - 1));
        int span = (style.equals("hip") ? Math.min(tw, td) : (ridgeAlongX ? td : tw)) + 2 * o;
        b.roofTop = switch (style) {
            case "flat" -> b.top + 2;
            case "none" -> b.top;
            case "shed" -> b.top + Math.max(tw, td) + o;
            default -> b.top + roofRise((span + 1) / 2, str(c, "roofProfile", "steep").toLowerCase(Locale.ROOT), (span + 1) / 2, p, kick);
        };
        int dx = num(c, "x", 0) + num(c, "doorOffset", 0);
        b.doorX = w >= 7 ? Math.max(b.x1 + 2, Math.min(b.x2 - 2, dx)) : Math.max(b.x1 + 1, Math.min(b.x2 - 1, dx));
        b.wall = block(c, "wall", "oak_planks");
        b.floor = block(c, "floor", "spruce_planks");
        b.foundation = block(c, "foundation", "cobblestone");
        b.frame = optBlock(c, "frame");
        b.wood = woodOf(b.floor, woodOf(b.frame == null ? "" : b.frame, "spruce"));
        b.recess = bool(c, "recess", false) && b.frame != null;
        b.stoneGround = optBlock(c, "lowerWall") != null;
        b.lightsAll = !"door".equals(str(c, "outdoorLights", "all"));
        b.quiet = bool(c, "quietYard", false);
        if (c.has("_wings") && c.get("_wings").isJsonArray()) {
            for (JsonElement we : c.getAsJsonArray("_wings")) {
                JsonObject wo = we.getAsJsonObject();
                int si = java.util.Arrays.asList(SIDES).indexOf(str(wo, "side", "right"));
                b.wings.add(new int[]{Math.max(0, si), num(wo, "a", 0), num(wo, "b", -1), num(wo, "floors", 1)});
            }
        }
        if (c.has("_keep") && c.get("_keep").isJsonArray()) {
            for (JsonElement k : c.getAsJsonArray("_keep")) {
                JsonArray a = k.getAsJsonArray();
                b.keepClear.add(cell(a.get(0).getAsInt(), a.get(1).getAsInt()));
            }
        }
        return b;
    }

    /** Height a gable/hip roof rises over `layers` steps (the first `kick` steps rise one block). */
    static int roofRise(int layers, int p, int kick) {
        return Math.min(layers, kick) + Math.max(0, layers - kick) * p;
    }

    /** Set while a roof with "roofStripes" is drawn: layers cycle through the gradient instead of shading eave->ridge. */
    private static boolean stripes = false;

    private static String gradPick(List<String> grad, int i, int layers) {
        if (stripes) return grad.get(Math.floorMod(i, grad.size()));
        return grad.get(Math.min(grad.size() - 1, i * grad.size() / Math.max(1, layers)));
    }

    /** Roof profiles: how many blocks layer i (0 = eave) rises, for a roof of L layers. */
    static final List<String> PROFILES = List.of("steep", "classic", "rounded", "flared", "mixed");

    static int layerRise(String prof, int i, int L, int p, int kick) {
        return switch (prof) {
            case "classic" -> 1;                                       // 45 degrees
            case "rounded" -> i < Math.max(1, L / 3) ? 2 : 1;          // steep at the eaves, easing to the ridge
            case "flared" -> i < Math.max(1, (L + 1) / 3) ? 1 : 2;     // wide shallow eaves sweeping up steeply
            case "mixed" -> i % 2 == 0 ? 1 : 2;                        // about 56 degrees, stepped
            default -> i < kick ? 1 : p;                               // steep (with an optional kick)
        };
    }

    static int roofRise(int layers, String prof, int L, int p, int kick) {
        int r = 0;
        for (int i = 0; i < layers; i++) r += layerRise(prof, i, L, p, kick);
        return r;
    }

    /** First wood type named in a block id (oak, spruce...), or `def`. */
    private static String woodOf(String block, String def) {
        if (block == null) return def;
        for (String w : new String[]{"dark_oak", "pale_oak", "spruce", "birch", "jungle", "acacia", "mangrove",
                "cherry", "bamboo", "crimson", "warped", "oak"}) {
            if (block.contains(w)) return w;
        }
        return def;
    }

    /** The building whose wall is at or next to (x,z), or null. */
    private static BuildingInfo buildingAt(Emitter e, int x, int z) {
        for (BuildingInfo b : e.buildings) {
            if (x >= b.x1 - 1 && x <= b.x2 + 1 && z >= b.z1 - 1 && z <= b.z2 + 1) return b;
        }
        return null;
    }

    /**
     * Local frame for something attached to a wall or facing a direction. u runs along the wall,
     * v points OUT of the wall (v=0 is the wall itself). side: front (-z), back (+z), left (-x), right (+x).
     */
    private record Side(String name, int x0, int z0) {
        int x(int u, int v) {
            return switch (name) { case "back" -> x0 - u; case "left" -> x0 - v; case "right" -> x0 + v; default -> x0 + u; };
        }
        int z(int u, int v) {
            return switch (name) { case "back" -> z0 + v; case "left" -> z0 + u; case "right" -> z0 - u; default -> z0 - v; };
        }
        String out() {
            return switch (name) { case "back" -> "+z"; case "left" -> "-x"; case "right" -> "+x"; default -> "-z"; };
        }
        String in() {
            return switch (name) { case "back" -> "-z"; case "left" -> "+x"; case "right" -> "-x"; default -> "+z"; };
        }
        /** Local direction of +u. */
        String alongDir() {
            return switch (name) { case "back" -> "-x"; case "left" -> "+z"; case "right" -> "-z"; default -> "+x"; };
        }
        String alongDirOpposite() {
            String d = alongDir();
            return (d.startsWith("+") ? "-" : "+") + d.charAt(1);
        }
        /** Local axis that u runs along. */
        char alongAxis() {
            return name.equals("left") || name.equals("right") ? 'z' : 'x';
        }
        char outAxis() {
            return alongAxis() == 'x' ? 'z' : 'x';
        }
    }

    private static Side sideFor(JsonObject c, Emitter e, int x0, int z0, BuildingInfo b) {
        String s = str(c, "side", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty() && b != null) { // infer from which wall the point is on
            if (z0 <= b.z1) s = "front";
            else if (z0 >= b.z2) s = "back";
            else if (x0 <= b.x1) s = "left";
            else s = "right";
        }
        if (!(s.equals("front") || s.equals("back") || s.equals("left") || s.equals("right"))) s = "front";
        if (b != null) { // snap onto the wall plane
            switch (s) {
                case "front" -> z0 = b.z1;
                case "back" -> z0 = b.z2;
                case "left" -> x0 = b.x1;
                default -> x0 = b.x2;
            }
        }
        return new Side(s, x0, z0);
    }

    /** Fills a (u,v) rectangle at heights y1..y2. */
    private static void setUV(Emitter e, Side s, int u1, int v1, int u2, int v2, int y1, int y2, String pattern) {
        int xa = s.x(u1, v1), xb = s.x(u2, v2), za = s.z(u1, v1), zb = s.z(u2, v2);
        e.set(Math.min(xa, xb), Math.min(y1, y2), Math.min(za, zb), Math.max(xa, xb), Math.max(y1, y2), Math.max(za, zb), pattern);
    }

    private static int[] groundUV(Emitter e, Side s, int u1, int v1, int u2, int v2) {
        return e.groundRange(s.x(u1, v1), s.z(u1, v1), s.x(u2, v2), s.z(u2, v2));
    }

    /** Fence/pane-like blocks connected along a world axis. */
    private static String connected(String block, String worldAxis) {
        if (block.contains("[") || block.contains("%") || block.contains(",")) return block;
        if (!(block.endsWith("_fence") || block.endsWith("_pane") || block.endsWith("_bars") || block.endsWith("_wall"))) return block;
        if (block.endsWith("_wall")) return block; // walls use different states; leave to WorldEdit
        return worldAxis.equals("x") ? block + "[east=true,west=true]" : block + "[north=true,south=true]";
    }

    private static String slabBottom(String block) {
        return block.endsWith("_slab") && !block.contains("[") ? block + "[type=bottom]" : block;
    }

    /** Covered porch against a wall: floor, posts, slab roof, railing, steps down to the ground. */
    private static void porch(JsonObject c, Emitter e) {
        int x0 = num(c, "x", 0), z0 = num(c, "z", 2);
        BuildingInfo b = buildingAt(e, x0, z0);
        Side s = sideFor(c, e, x0, z0, b);
        int w = clamp(num(c, "width", 5), 3, 15), d = clamp(num(c, "depth", 3), 2, 6);
        int ua = -(w - 1) / 2, ub = w / 2;
        String wood = b != null ? b.wood : "spruce";
        String floor = block(c, "floor", b != null ? b.floor : wood + "_planks");
        String post = block(c, "post", wood + "_fence");
        String roofB = block(c, "roofBlock", wood + "_slab");
        String rail = optBlock(c, "railing") == null && c.has("railing") ? null : block(c, "railing", wood + "_fence");
        String foundation = block(c, "foundation", b != null ? b.foundation : "cobblestone");
        int[] g = groundUV(e, s, ua, 1, ub, d);
        int by = b != null ? b.by : (g == null ? 0 : g[1] + 1);
        by += num(c, "y", 0);
        int fy = by - 1; // floor block level

        if (g != null && g[0] < fy) setUV(e, s, ua, 1, ub, d, Math.max(fy - 16, g[0]), fy - 1, foundation);
        setUV(e, s, ua, 1, ub, d, by, by + 2, "air");
        setUV(e, s, ua, 1, ub, d, fy, fy, floor);
        String vpost = axisState(post, "y", e);
        setUV(e, s, ua, d, ua, d, by, by + 2, vpost);
        setUV(e, s, ub, d, ub, d, by, by + 2, vpost);
        boolean lean = !"flat".equals(str(c, "roof", "lean")) && d >= 2;
        String leanSt = VoxelGrid.baseId(roofB).endsWith("_slab") ? VoxelGrid.baseId(roofB).replace("_slab", "_stairs") : wood + "_stairs";
        int lampV = (d + 1) / 2, lampY = by + 2;
        if (lean) { // a little lean-to roof: stairs at the front edge, a higher row against the wall
            String toWall = s.in();
            setUV(e, s, ua, d, ub, d, by + 3, by + 3, stairPat(leanSt, toWall, false, e));
            if (d >= 2) setUV(e, s, ua, d - 1, ub, d - 1, by + 3, by + 3, slabOf(leanSt).replace("type=bottom", "type=top"));
            if (d >= 3) setUV(e, s, ua, 1, ub, d - 2, by + 4, by + 4, stairPat(leanSt, toWall, false, e));
            if (d >= 4) setUV(e, s, ua, 1, ub, d - 3, by + 4, by + 4, slabOf(leanSt));
            // boards along the open sides, a fascia on the front edge
            String fas = optBlock(c, "fascia");
            if (fas != null && VoxelGrid.baseId(fas).endsWith("_trapdoor")) {
                setUV(e, s, ua, d + 1, ub, d + 1, by + 3, by + 3, VoxelGrid.baseId(fas) + "[facing=" + e.frame.dir(s.out()) + ",open=true,half=bottom]");
            }
            if (d >= 3) { lampV = d - 1; lampY = by + 2; }
        } else {
            setUV(e, s, ua, 1, ub, d, by + 3, by + 3, slabBottom(roofB));
        }
        if (rail != null) {
            String along = connected(rail, e.frame.axis(s.alongAxis())), out = connected(rail, e.frame.axis(s.outAxis()));
            setUV(e, s, ua, 1, ua, d - 1, by, by, out);
            setUV(e, s, ub, 1, ub, d - 1, by, by, out);
            if (ub - ua >= 4) { // front rail with a 3-wide opening in the middle
                setUV(e, s, ua + 1, d, -2, d, by, by, along);
                setUV(e, s, 2, d, ub - 1, d, by, by, along);
            }
        }
        setUV(e, s, 0, lampV, 0, lampV, lampY, lampY, "lantern[hanging=true]");
        // Steps from the porch floor down to the ground in front.
        int[] gf = groundUV(e, s, -1, d + 1, 1, d + 1);
        int drop = gf == null ? 0 : fy - gf[1];
        String step = block(c, "steps", wood + "_stairs");
        for (int k = 1; k <= Math.min(drop, 6); k++) {
            int y = fy - k + 1;
            setUV(e, s, -1, d + k, 1, d + k, y, y, stairState(step, s.in(), e));
            if (gf != null && y - 1 > gf[0]) setUV(e, s, -1, d + k, 1, d + k, Math.max(gf[0], y - 8), y - 1, foundation);
        }
        String door = str(c, "door", "none");
        if (!door.equals("none")) placeDoor(e, s, 0, by, door);
    }

    /** Balcony on an upper floor of a building, with railing, brackets and a door out. */
    private static void balcony(JsonObject c, Emitter e) {
        int x0 = num(c, "x", 0), z0 = num(c, "z", 2);
        BuildingInfo b = buildingAt(e, x0, z0);
        Side s = sideFor(c, e, x0, z0, b);
        int w = clamp(num(c, "width", 4), 2, 25), d = clamp(num(c, "depth", 2), 1, 5);
        int ua = -(w - 1) / 2, ub = w / 2;
        int y;
        String wood = b != null ? b.wood : "spruce";
        if (b != null) {
            if (b.floors < 2) throw new PlanException("balcony needs a building with 2+ floors");
            int f = clamp(num(c, "storey", 1), 1, b.floors - 1);
            y = b.by + f * b.h - 1; // same level as that floor
            int[] rf = b.rect(f); // jettied floors stick out: attach to that floor's wall
            s = switch (s.name()) {
                case "front" -> new Side("front", s.x0(), rf[2]);
                case "back" -> new Side("back", s.x0(), rf[3]);
                case "left" -> new Side("left", rf[0], s.z0());
                default -> new Side("right", rf[1], s.z0());
            };
        } else {
            y = num(c, "y", 4);
        }
        String deck = block(c, "floor", b != null ? b.floor : wood + "_planks");
        String rail = block(c, "railing", wood + "_fence");
        boolean deckMode = bool(c, "deck", false);                 // a deep platform on big posts with joists (no roof)
        boolean posts = bool(c, "posts", false) && !deckMode;      // a broad gallery standing on posts
        int roofY = b != null ? y + b.h : y + 4;
        boolean lean = "lean".equals(str(c, "roof", "none")) && d >= 1;
        setUV(e, s, ua, 1, ub, d, y + 1, lean ? roofY - 1 : y + 2, "air");
        setUV(e, s, ua, 1, ub, d, y, y, deck);
        setUV(e, s, ua, d, ub, d, y + 1, y + 1, connected(rail, e.frame.axis(s.alongAxis())));
        if (d > 1) {
            setUV(e, s, ua, 1, ua, d - 1, y + 1, y + 1, connected(rail, e.frame.axis(s.outAxis())));
            setUV(e, s, ub, 1, ub, d - 1, y + 1, y + 1, connected(rail, e.frame.axis(s.outAxis())));
        }
        if (posts) { // timber posts from the ground to the roof at the corners (and the middle of a long gallery)
            String post = axisState(block(c, "post", b != null && b.frame != null ? b.frame : wood + "_log"), "y", e);
            List<Integer> us = new ArrayList<>(List.of(ua, ub));
            if (ub - ua >= 8) us.add((ua + ub) / 2);
            for (int u : us) {
                int[] gp = groundUV(e, s, u, d, u, d);
                int gy = gp == null ? y - 4 : Math.max(y - 16, gp[1] + 1);
                setUV(e, s, u, d, u, d, gy, lean ? roofY - 1 : y + 1, post);
                if (b != null && gy > b.by - 1) { /* raised ground: post stands on it */ }
            }
            String br = wood + "_stairs[facing=" + e.frame.dir(s.out()) + ",half=top]"; // knee braces under the deck
            for (int u : us) if (y - 1 > (b != null ? b.by + 1 : y - 3)) setUV(e, s, u, d - 1, u, d - 1, y - 1, y - 1, br);
        } else {
            String bracket = wood + "_stairs[facing=" + e.frame.dir(s.in()) + ",half=top]";
            setUV(e, s, ua, 1, ua, 1, y - 1, y - 1, bracket);
            setUV(e, s, ub, 1, ub, 1, y - 1, y - 1, bracket);
        }
        if (deckMode) {
            String log = b != null && b.frame != null ? b.frame : wood + "_log";
            String logOut = axisState(log, e.frame.axis(s.outAxis()), e), logUp = axisState(log, "y", e);
            String edge = wood + "_trapdoor";
            setUV(e, s, ua, d + 1, ub, d + 1, y, y, edge + "[facing=" + e.frame.dir(s.out()) + ",open=true,half=bottom]");
            for (int u = ua + 1; u < ub; u += 2) {                  // joists with stepped ends: the brackets of the deck
                setUV(e, s, u, 1, u, d, y - 1, y - 1, logOut);
                setUV(e, s, u, d + 1, u, d + 1, y - 1, y - 1, wood + "_stairs[facing=" + e.frame.dir(s.in()) + ",half=top]");
                setUV(e, s, u, d, u, d, y - 2, y - 2, wood + "_stairs[facing=" + e.frame.dir(s.in()) + ",half=top]");
            }
            for (int u : new int[]{ua + 1, ub - 1}) {               // two big posts to the ground, with capitals
                int[] gp = groundUV(e, s, u, d - 1, u, d - 1);
                int gy = gp == null ? y - 5 : Math.max(y - 16, gp[1] + 1);
                setUV(e, s, u, d - 1, u, d - 1, gy - 1, gy - 1, b != null ? solidOf(VoxelGrid.splitPattern(b.foundation).get(0).replaceAll("^\\d+%", "")) : "stone_bricks");
                setUV(e, s, u, d - 1, u, d - 1, gy, y - 1, logUp);
                setUV(e, s, u - 1, d - 1, u - 1, d - 1, y - 2, y - 2, wood + "_stairs[facing=" + e.frame.dir(s.alongDir()) + ",half=top]");
                setUV(e, s, u + 1, d - 1, u + 1, d - 1, y - 2, y - 2, wood + "_stairs[facing=" + e.frame.dir(s.alongDirOpposite()) + ",half=top]");
            }
        }
        if (lean) { // a pent roof over the gallery: stairs on the outer edge, a slab row against the wall
            String rb = firstStairs(optBlock(c, "roofBlock"), wood + "_stairs");
            setUV(e, s, ua - 1, d + 1, ub + 1, d + 1, roofY, roofY, stairPat(rb, s.in(), false, e));
            setUV(e, s, ua - 1, 1, ub + 1, d, roofY, roofY, slabOf(rb).replace("type=bottom", "type=top"));
        }
        placeDoor(e, s, 0, y + 1, str(c, "door", wood + "_door"));
    }

    private static void placeDoor(Emitter e, Side s, int u, int y, String door) {
        String d0 = safeBlock(door);
        if (d0.equals("none")) return;
        setUV(e, s, u, -1, u, -1, y, y + 1, "air"); // step inside (clears recessed plaster behind the wall)
        String inward = e.frame.dir(s.in());
        if (d0.endsWith("_door")) {
            setUV(e, s, u, 0, u, 0, y, y, d0 + "[facing=" + inward + ",half=lower,hinge=left]");
            setUV(e, s, u, 0, u, 0, y + 1, y + 1, d0 + "[facing=" + inward + ",half=upper,hinge=left]");
        } else {
            setUV(e, s, u, 0, u, 0, y, y + 1, "air");
        }
    }

    /** Chimney rising through a building's roof, with smoke from a campfire on top. */
    private static void chimney(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 2);
        int size = clamp(num(c, "width", 2), 1, 3);
        String block = block(c, "block", "bricks");
        BuildingInfo b = buildingAt(e, x, z);
        int[] g = e.groundRange(x, z, x + size - 1, z + size - 1);
        int bottom = b != null ? b.by - 1 : (g == null ? 0 : g[0]);
        int top = b != null ? Math.max(b.roofTop + 2, b.top + 3) : bottom + clamp(num(c, "height", 10), 3, 40);
        top += num(c, "y", 0);
        e.set(x, bottom, z, x + size - 1, top, z + size - 1, block);
        if (bool(c, "smoke", true)) e.set(x, top + 1, z, x, top + 1, z, "campfire[lit=true]");
    }

    /** Rounded arch: stand-alone gateway, or cut into an existing wall (cut=true). */
    private static void arch(JsonObject c, Emitter e) {
        int x0 = num(c, "x", 0), z0 = num(c, "z", 3);
        Side s = sideFor(c, e, x0, z0, null);
        int w = clamp(num(c, "width", 3), 1, 15), h = clamp(num(c, "height", Math.max(3, w + 1)), 2, 30);
        int t = clamp(num(c, "thickness", 1), 1, 5);
        String block = block(c, "block", "stone_bricks");
        String key = optBlock(c, "keystone");
        boolean cut = bool(c, "cut", true);
        boolean frame = !"none".equals(str(c, "block", ""));
        int[] g = e.groundRange(s.x(0, 0), s.z(0, 0), s.x(0, 0), s.z(0, 0));
        int base = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        int ua = -(w - 1) / 2, ub = w / 2;
        double cu = (ub + ua) / 2.0, rad = w / 2.0;
        int hs = Math.max(1, h - (int) Math.ceil(rad));
        // Thickness uses the same offsets as wall components (-(t-1)/2 .. t/2 along +x/+z), so an arch
        // placed on a wall's line cuts all the way through it.
        int lo = -(t - 1) / 2, hi = t / 2;
        boolean faceAlongX = s.alongAxis() == 'x';
        java.util.function.BiConsumer<int[], String> put = (k, blk) -> {
            int u = k[0];
            if (faceAlongX) e.set(x0 + u, base + k[1], s.z0() + lo, x0 + u, base + k[2], s.z0() + hi, blk);
            else e.set(s.x0() + lo, base + k[1], z0 + u, s.x0() + hi, base + k[2], z0 + u, blk);
        };
        int topFrame = Integer.MIN_VALUE, keyU = 0;
        for (int u = ua - 1; u <= ub + 1; u++) {
            List<int[]> openRuns = new ArrayList<>(), frameRuns = new ArrayList<>();
            int runStart = -1, frameStart = -1;
            for (int y = 0; y <= h + 2; y++) {
                double dy = y - (hs - 0.5);
                boolean inside = u >= ua && u <= ub;
                boolean open = inside && (y < hs || sq(u - cu) + sq(dy) <= rad * rad + 0.01);
                boolean fr = !open && ((y < hs && (u == ua - 1 || u == ub + 1))
                    || (y >= hs && sq(u - cu) + sq(dy) <= sq(rad + 1.5) + 0.01)); // thick enough to stay connected
                if (open && runStart < 0) runStart = y;
                if (!open && runStart >= 0) { openRuns.add(new int[]{runStart, y - 1}); runStart = -1; }
                if (fr && frameStart < 0) frameStart = y;
                if (!fr && frameStart >= 0) { frameRuns.add(new int[]{frameStart, y - 1}); frameStart = -1; }
                if (fr && (y > topFrame || (y == topFrame && Math.abs(u - cu) < Math.abs(keyU - cu)))) { topFrame = y; keyU = u; }
            }
            if (runStart >= 0) openRuns.add(new int[]{runStart, h + 2});
            if (frameStart >= 0) frameRuns.add(new int[]{frameStart, h + 2});
            if (frame) for (int[] r : frameRuns) put.accept(new int[]{u, r[0], r[1]}, block);
            if (cut) for (int[] r : openRuns) put.accept(new int[]{u, r[0], r[1]}, "air");
        }
        if (frame && key != null && topFrame > Integer.MIN_VALUE) {
            put.accept(new int[]{keyU, topFrame, topFrame}, key);
        }
    }

    /** Straight staircase climbing `height` steps in direction `side` from (x,z). */
    private static void staircase(JsonObject c, Emitter e) {
        int x0 = num(c, "x", 0), z0 = num(c, "z", 0);
        Side s = sideFor(c, e, x0, z0, null);
        int n = clamp(num(c, "height", 4), 1, 24), w = clamp(num(c, "width", 3), 1, 9);
        String step = block(c, "block", "stone_brick_stairs");
        String support = optBlock(c, "support");
        if (support == null && !c.has("support")) support = solidOf(step);
        int ua = -(w - 1) / 2, ub = w / 2;
        int[] g = groundUV(e, s, ua, 0, ub, 0);
        int base = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        for (int k = 0; k < n; k++) {
            setUV(e, s, ua, k, ub, k, base + k + 1, base + k + 3, "air"); // headroom
            setUV(e, s, ua, k, ub, k, base + k, base + k, stairState(step, s.out(), e));
            if (support != null && k > 0) setUV(e, s, ua, k, ub, k, base, base + k - 1, support);
        }
    }

    /** Round fountain: basin with water and a center column (top: water, a light, or any block). */
    private static void fountain(JsonObject c, Emitter e) {
        int cx = num(c, "x", 0), cz = num(c, "z", 4);
        int r = clamp(num(c, "radius", 3), 2, 10);
        String rim = block(c, "block", "stone_bricks");
        String top = block(c, "top", "water");
        int[] g = e.groundRange(cx - r, cz - r, cx + r, cz + r);
        int by = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        if (g != null && g[0] < by - 1) e.shape(cx, Math.max(g[0], by - 9), cz, "//cyl " + rim + " " + r + " " + (by - 1 - Math.max(g[0], by - 9)));
        e.shape(cx, by - 1, cz, "//cyl " + rim + " " + r + " 1");
        e.shape(cx, by, cz, "//cyl air " + r + " 4");
        e.shape(cx, by, cz, "//hcyl " + rim + " " + r + " 1");
        e.shape(cx, by, cz, "//cyl water " + (r - 1) + " 1");
        int ph = clamp(num(c, "height", 3), 1, 8);
        e.set(cx, by, cz, cx, by + ph - 1, cz, rim);
        e.set(cx, by + ph, cz, cx, by + ph, cz, top);
    }

    /** Pond dug into the terrain with a sand/gravel shore and lily pads. */
    private static void pond(JsonObject c, Emitter e) {
        int cx = num(c, "x", 0), cz = num(c, "z", 6);
        int r = clamp(num(c, "radius", 4), 2, 16), depth = clamp(num(c, "depth", 2), 1, 6);
        String shore = block(c, "block", "60%sand,40%gravel");
        int[] g = e.groundRange(cx - r, cz - r, cx + r, cz + r);
        int wl = (g == null ? -1 : g[0]) + num(c, "y", 0); // water surface at the lowest ground
        e.shape(cx, wl + 1, cz, "//cyl air " + (r + 1) + " 4");
        e.shape(cx, wl - depth, cz, "//cyl " + shore + " " + (r + 1) + " " + (depth + 1));
        e.shape(cx, wl - depth + 1, cz, "//cyl water " + r + " " + depth);
        if (bool(c, "lilies", true)) e.shape(cx, wl + 1, cz, "//cyl 12%lily_pad,88%air " + Math.max(1, r - 1) + " 1");
    }

    /** Hedged garden bed with flowers; follows the terrain. x = center, z = front edge. */
    private static void garden(JsonObject c, Emitter e) {
        int w = clamp(num(c, "width", 7), 3, 40), d = clamp(num(c, "depth", 5), 3, 40);
        int x1 = num(c, "x", 0) - (w - 1) / 2, x2 = x1 + w - 1, z1 = num(c, "z", 2), z2 = z1 + d - 1;
        String hedge = optBlock(c, "hedge");
        if (hedge == null && !c.has("hedge")) hedge = "oak_leaves";
        String flowers = block(c, "flowers", "55%air,8%poppy,8%dandelion,8%cornflower,7%oxeye_daisy,7%allium,7%azure_bluet");
        int hh = clamp(num(c, "height", 1), 1, 3);
        int[] g = e.groundRange(x1, z1, x2, z2);
        int lo = g == null ? -2 : g[0] - 1, hi = (g == null ? 0 : g[1]) + hh + 1;
        e.region(x1, lo, z1, x2, hi, z2, "//replace " + PLANTS + " air");
        e.region(x1 + 1, lo, z1 + 1, x2 - 1, hi, z2 - 1, "//overlay " + flowers);
        if (hedge != null) {
            int mid = (x1 + x2) / 2;
            for (int layer = 0; layer < hh; layer++) {
                e.region(x1, lo, z2, x2, hi, z2, "//overlay " + hedge);
                e.region(x1, lo, z1, x1, hi, z2 - 1, "//overlay " + hedge);
                e.region(x2, lo, z1, x2, hi, z2 - 1, "//overlay " + hedge);
                if (mid - 2 >= x1 + 1) e.region(x1 + 1, lo, z1, mid - 2, hi, z1, "//overlay " + hedge);
                if (mid + 2 <= x2 - 1) e.region(mid + 2, lo, z1, x2 - 1, hi, z1, "//overlay " + hedge);
            }
        }
    }

    /** Fence from (x,z) to (x2,z2) (along x first, then z), following the terrain, with a gate. */
    private static void fence(JsonObject c, Emitter e) {
        int ax = num(c, "x", 0), az = num(c, "z", 0), bx = num(c, "x2", ax + 10), bz = num(c, "z2", az);
        String block = block(c, "block", "oak_fence");
        String gate = optBlock(c, "gate");
        if (gate == null && !c.has("gate")) gate = block.replace("_fence", "_fence_gate");
        boolean first = true;
        if (ax != bx) { fenceRun(e, Math.min(ax, bx), az, Math.max(ax, bx), az, block, first ? gate : null, 'x'); first = false; }
        if (az != bz) fenceRun(e, bx, Math.min(az, bz), bx, Math.max(az, bz), block, first ? gate : null, 'z');
    }

    private static void fenceRun(Emitter e, int x1, int z1, int x2, int z2, String block, String gate, char axis) {
        int[] g = e.groundRange(x1, z1, x2, z2);
        int lo = g == null ? -2 : g[0] - 1, hi = (g == null ? 0 : g[1]) + 2;
        e.region(x1, lo, z1, x2, hi, z2, "//replace " + PLANTS + " air");
        e.region(x1, lo, z1, x2, hi, z2, "//overlay " + connected(block, e.frame.axis(axis)));
        if (gate != null && gate.endsWith("_fence_gate")) {
            int gx = (x1 + x2) / 2, gz = (z1 + z2) / 2;
            int[] gg = e.groundRange(gx, gz, gx, gz);
            int y = (gg == null ? 0 : gg[1]) + 1;
            String facing = e.frame.dir(axis == 'x' ? "+z" : "+x");
            e.set(gx, y, gz, gx, y, gz, gate + "[facing=" + facing + ",open=false]");
        }
    }

    /** Bridge from (x,z) to (x2,z2) (straight), with railings and support posts down to the ground. */
    private static void bridge(JsonObject c, Emitter e) {
        int ax = num(c, "x", 0), az = num(c, "z", 0), bx = num(c, "x2", ax), bz = num(c, "z2", az + 10);
        if (ax != bx && az != bz) throw new PlanException("bridges must be straight along x or z");
        boolean alongX = az == bz;
        int w = clamp(num(c, "width", 3), 1, 7), lo = -(w - 1) / 2, hiW = w / 2;
        String deck = block(c, "block", "spruce_planks");
        String rail = optBlock(c, "railing");
        if (rail == null && !c.has("railing")) rail = "spruce_fence";
        String support = block(c, "support", "spruce_log");
        int[] ga = e.groundRange(ax, az, ax, az), gb = e.groundRange(bx, bz, bx, bz);
        int y = Math.max(ga == null ? -1 : ga[1], gb == null ? -1 : gb[1]) + num(c, "y", 0);
        int s1 = alongX ? Math.min(ax, bx) : Math.min(az, bz), s2 = alongX ? Math.max(ax, bx) : Math.max(az, bz);
        int fixed = alongX ? az : ax;
        java.util.function.IntBinaryOperator X = (along, across) -> alongX ? along : fixed + across;
        java.util.function.IntBinaryOperator Z = (along, across) -> alongX ? fixed + across : along;
        e.set(Math.min(X.applyAsInt(s1, lo), X.applyAsInt(s2, hiW)), y + 1, Math.min(Z.applyAsInt(s1, lo), Z.applyAsInt(s2, hiW)),
            Math.max(X.applyAsInt(s1, lo), X.applyAsInt(s2, hiW)), y + 3, Math.max(Z.applyAsInt(s1, lo), Z.applyAsInt(s2, hiW)), "air");
        e.set(Math.min(X.applyAsInt(s1, lo), X.applyAsInt(s2, hiW)), y, Math.min(Z.applyAsInt(s1, lo), Z.applyAsInt(s2, hiW)),
            Math.max(X.applyAsInt(s1, lo), X.applyAsInt(s2, hiW)), y, Math.max(Z.applyAsInt(s1, lo), Z.applyAsInt(s2, hiW)), deck);
        if (rail != null) {
            String r = connected(rail, e.frame.axis(alongX ? 'x' : 'z'));
            for (int edge : new int[]{lo, hiW}) {
                int xa = X.applyAsInt(s1, edge), xb = X.applyAsInt(s2, edge), za = Z.applyAsInt(s1, edge), zb = Z.applyAsInt(s2, edge);
                e.set(Math.min(xa, xb), y + 1, Math.min(za, zb), Math.max(xa, xb), y + 1, Math.max(za, zb), r);
            }
        }
        String post = axisState(support, "y", e);
        for (int p = s1 + 2; p <= s2 - 2; p += 4) {
            for (int edge : new int[]{lo, hiW}) {
                int px = X.applyAsInt(p, edge), pz = Z.applyAsInt(p, edge);
                int[] gp = e.groundRange(px, pz, px, pz);
                int gy = gp == null ? y - 4 : gp[1];
                if (gy < y - 1) e.set(px, Math.max(gy - 2, y - 30), pz, px, y - 1, pz, post);
            }
        }
    }

    /** Decorative column with base and capital. radius 0 = 1x1, 1 = 3x3 base/capital. */
    private static void column(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 3);
        int h = clamp(num(c, "height", 5), 2, 40), r = clamp(num(c, "radius", 0), 0, 1);
        String shaft = axisState(block(c, "block", "quartz_pillar"), "y", e);
        String base = block(c, "base", "chiseled_stone_bricks");
        String cap = block(c, "capital", base);
        int[] g = e.groundRange(x - r, z - r, x + r, z + r);
        int by = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        e.set(x - r, by, z - r, x + r, by, z + r, base);
        e.set(x, by + 1, z, x, by + h - 2, z, shaft);
        e.set(x - r, by + h - 1, z - r, x + r, by + h - 1, z + r, cap);
    }

    private static String stairState(String block, String localDir, Emitter e) {
        if (!block.endsWith("_stairs") || block.contains("[")) return block;
        return block + "[facing=" + e.frame.dir(localDir) + ",half=bottom]";
    }

    private static double sq(double v) {
        return v * v;
    }

    // -------- building extras: shutters, interiors, ladders --------

    private static void shutter(Emitter e, String trapdoor, int x, int y1, int z, int y2, String outLocal) {
        e.set(x, y1, z, x, y2, z, trapdoor + "[facing=" + e.frame.dir(outLocal) + ",open=true,half=bottom]");
    }

    /** Furnishes each floor of a building and connects floors with a ladder. */
    private static void furnish(Emitter e, BuildingInfo b, String style, String wallSolid) {
        String wood = b.wood;
        java.util.Set<Long> used = new java.util.HashSet<>(b.keepClear);
        java.util.function.BiFunction<Integer, Integer, Long> key = BuildPlanner::cell;
        // Ladder in the back-right corner when there are several floors (clear of recessed plaster).
        if (b.floors > 1) {
            int r0 = b.recess ? 1 : 0, lx = b.x2 - 1 - r0, lz = b.z2 - 1 - r0;
            int ly2 = b.by + (b.floors - 1) * b.h - 1;
            e.set(lx, b.by, lz, lx, b.top, lz, wallSolid);                       // support pillar
            e.set(lx - 1, b.by, lz, lx - 1, ly2, lz, "ladder[facing=" + e.frame.dir("-x") + "]");
            used.add(key.apply(lx, lz));
            used.add(key.apply(lx - 1, lz));
            used.add(key.apply(lx - 1, lz - 1));
        }
        if (style.equals("none")) return;
        for (int f = 0; f < b.floors; f++) {
            int y = b.by + f * b.h;
            int[] rf = b.rect(f); // each floor's own room (jettied floors are bigger, recessed walls thicker)
            int inset = 1 + (b.recess && !(f == 0 && b.stoneGround) ? 1 : 0);
            int xi1 = rf[0] + inset, xi2 = rf[1] - inset, zi1 = rf[2] + inset, zi2 = rf[3] - inset;
            java.util.Set<Long> u = new java.util.HashSet<>(used);
            if (f == 0) for (int dx = -1; dx <= 1; dx++) { u.add(key.apply(b.doorX + dx, zi1)); u.add(key.apply(b.doorX, zi1 + 1)); }
            Room p = new Room(e, y, xi1, xi2, zi1, zi2, u, key);
            String towardRoom = e.frame.dir("-z"); // things on the back wall face into the room
            int tx = (xi1 + xi2) / 2, tz = (zi1 + zi2) / 2;
            boolean ground = f == 0;
            String kind = ground ? style : (style.equals("library") ? "library" : "bedroom");
            switch (kind) {
                case "tavern" -> {
                    for (int x = xi1; x <= xi2 - 2; x++) p.put(x, zi2, "barrel[facing=up]");
                    if (zi2 - 2 > zi1 + 1) for (int x = xi1 + 1; x <= xi2 - 2; x++) p.put(x, zi2 - 2, "stripped_" + wood + "_log[axis=" + e.frame.axis('x') + "]");
                    table(p, e, wood, xi1 + 2, zi1 + 2);
                    table(p, e, wood, xi2 - 2, zi1 + 2);
                }
                case "library" -> {
                    for (int z = zi1 + 1; z <= zi2; z++) {
                        if (p.put(xi1, z, "bookshelf")) p.above(xi1, z, "bookshelf");
                        if (p.put(xi2, z, "bookshelf")) p.above(xi2, z, "bookshelf");
                    }
                    p.put(tx, zi2, "lectern[facing=" + towardRoom + "]");
                    table(p, e, wood, tx, tz);
                }
                case "shop" -> {
                    if (zi2 - 2 > zi1) for (int x = xi1 + 1; x <= xi2 - 1; x++) p.put(x, zi2 - 2, wood + "_planks");
                    for (int x = xi1; x <= xi2 - 2; x++) p.put(x, zi2, x % 2 == 0 ? "barrel[facing=up]" : "chest[facing=" + towardRoom + "]");
                }
                case "bedroom" -> {
                    String bedFacing = e.frame.dir("+z");
                    if (p.put(xi1, zi2, "red_bed[part=head,facing=" + bedFacing + "]")) p.put(xi1, zi2 - 1, "red_bed[part=foot,facing=" + bedFacing + "]");
                    if (xi2 - xi1 >= 6 && p.put(xi1 + 2, zi2, "blue_bed[part=head,facing=" + bedFacing + "]")) {
                        p.put(xi1 + 2, zi2 - 1, "blue_bed[part=foot,facing=" + bedFacing + "]");
                    }
                    if (p.put(xi1 + 1, zi2, "chest[facing=" + towardRoom + "]")) p.above(xi1 + 1, zi2, "white_candle[candles=1,lit=true]");
                    if (p.put(tx, zi2, "bookshelf")) p.above(tx, zi2, "potted_red_tulip");
                    p.put(tx + 1, zi2, "barrel[facing=up]");
                    if (p.put(xi2, zi2, "bookshelf")) p.above(xi2, zi2, "bookshelf");
                    p.put(tx, tz, "red_carpet");
                    p.put(tx + 1, tz, "red_carpet");
                    p.put(tx, tz - 1, "red_carpet");
                    p.put(tx + 1, tz - 1, "red_carpet");
                }
                default -> { // home ground floor: kitchen + dining table
                    p.put(xi1, zi2, "furnace[facing=" + towardRoom + "]");
                    p.put(xi1 + 1, zi2, "smoker[facing=" + towardRoom + "]");
                    p.put(xi1 + 2, zi2, "crafting_table");
                    p.put(xi1 + 3, zi2, "barrel[facing=up]");
                    p.put(xi1 + 4, zi2, "water_cauldron[level=3]");
                    p.put(xi1, zi1 + 1 < zi2 ? zi1 + 1 : zi2, "chest[facing=" + e.frame.dir("+x") + "]");
                    if (p.put(xi2, zi2, "bookshelf")) p.above(xi2, zi2, "potted_fern");
                    p.put(xi2, zi2 - 1, "decorated_pot");
                    table(p, e, wood, tx, tz);
                    for (int dx = -1; dx <= 1; dx++) p.put(tx + dx, tz + 1, "brown_carpet"); // rug by the table
                }
            }
        }
    }

    /** Places furniture on one floor, never twice in the same spot or outside the room. */
    private static final class Room {
        final Emitter e;
        final int y, xi1, xi2, zi1, zi2;
        final java.util.Set<Long> used;
        final java.util.function.BiFunction<Integer, Integer, Long> key;

        Room(Emitter e, int y, int xi1, int xi2, int zi1, int zi2, java.util.Set<Long> used,
             java.util.function.BiFunction<Integer, Integer, Long> key) {
            this.e = e; this.y = y; this.xi1 = xi1; this.xi2 = xi2; this.zi1 = zi1; this.zi2 = zi2;
            this.used = used; this.key = key;
        }

        boolean put(int x, int z, String block) {
            if (x < xi1 || x > xi2 || z < zi1 || z > zi2 || !used.add(key.apply(x, z))) return false;
            e.set(x, y, z, x, y, z, block);
            return true;
        }

        void above(int x, int z, String block) {
            e.set(x, y + 1, z, x, y + 1, z, block);
        }
    }

    private static void table(Room p, Emitter e, String wood, int x, int z) {
        if (!p.put(x, z, wood + "_fence")) return;
        p.above(x, z, "candle[candles=3,lit=true]");
        p.put(x - 1, z, wood + "_stairs[facing=" + e.frame.dir("-x") + ",half=bottom]");
        p.put(x + 1, z, wood + "_stairs[facing=" + e.frame.dir("+x") + ",half=bottom]");
    }

    // ============================== wings ===============================

    /**
     * Adds the wings of every building ("wing": {...}, "wings": [...], or one chosen by autoWing): side
     * buildings joined to one wall (front, back, left or right), with a doorway between them. A wing on an
     * eave side gets a roof at right angles that runs into the main roof; a wing on a gable end continues the
     * main roof direction and stops against the gable wall. Several wings make L, T, U and cross plans.
     */
    private static JsonArray withWings(JsonArray comps, Frame frame, Ground ground) {
        JsonArray out = new JsonArray();
        List<int[]> boxes = new ArrayList<>();
        for (JsonElement el : comps) boxes.add(el.isJsonObject() ? footprint(el.getAsJsonObject()) : null);
        int idx = -1;
        for (JsonElement el : comps) {
            idx++;
            if (!el.isJsonObject() || !"building".equals(str(el.getAsJsonObject(), "type", ""))) { out.add(el); continue; }
            JsonObject c = el.getAsJsonObject();
            List<JsonObject> wings = new ArrayList<>();
            if (c.has("wings") && c.get("wings").isJsonArray()) {
                for (JsonElement w : c.getAsJsonArray("wings")) if (w.isJsonObject()) wings.add(w.getAsJsonObject());
            }
            if (c.has("wing") && c.get("wing").isJsonObject()) wings.add(c.getAsJsonObject("wing"));
            try {
                if (wings.isEmpty() && bool(c, "autoWing", false)) {
                    JsonObject auto = autoWing(c, frame, ground, boxes, idx);
                    if (auto != null) wings.add(auto);
                }
            } catch (RuntimeException ignored) {
                // no wing
            }
            JsonObject cur = c;
            List<JsonObject> extra = new ArrayList<>();
            for (JsonObject w : wings) {
                try {
                    JsonObject[] pair = makeWing(cur, w, frame, ground);
                    cur = pair[0];
                    extra.add(pair[1]);
                } catch (RuntimeException ignored) {
                    // skip a wing that doesn't fit
                }
            }
            cur.remove("wing");
            cur.remove("wings");
            cur.remove("autoWing");
            out.add(cur);
            for (JsonObject x : extra) out.add(x);
        }
        return out;
    }

    /** Picks a free side for a wing (right or left first, by the building's seed), or null. */
    private static JsonObject autoWing(JsonObject c, Frame frame, Ground ground, List<int[]> boxes, int self) {
        int w = num(c, "width", 9), d = num(c, "depth", 7);
        if (d < 9 || w < 7) return null;
        int seed = Math.floorMod(num(c, "x", 0) * 31 + num(c, "z", 0) * 17 + w * 7 + d, 997);
        String[] order = seed % 2 == 0 ? new String[]{"right", "left"} : new String[]{"left", "right"};
        for (String side : order) {
            JsonObject wing = new JsonObject();
            wing.addProperty("side", side);
            JsonObject[] pair = makeWing(c, wing, frame, ground);
            int[] wb = footprint(pair[1]);
            wb = new int[]{wb[0] + 2, wb[1] - 2, wb[2] + 2, wb[3] - 2}; // exact wing box (footprint pads buildings)
            if (clearOf(wb, boxes, self, 1)) return wing;
        }
        return null;
    }

    private static boolean clearOf(int[] r, List<int[]> boxes, int self, int margin) {
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (i == self || b == null) continue;
            if (b[0] <= r[1] + margin && b[1] >= r[0] - margin && b[2] <= r[3] + margin && b[3] >= r[2] - margin) return false;
        }
        return true;
    }

    /** Rough local footprint {x1,x2,z1,z2} of a component, or null if unknown. */
    private static int[] footprint(JsonObject c0) {
        JsonObject c = normalize(c0);
        String t = str(c, "type", "");
        int x = num(c, "x", 0), z = num(c, "z", 0);
        switch (t) {
            case "building", "garden", "stall", "roof" -> {
                int w = num(c, "width", 9), d = num(c, "depth", 7), j = t.equals("building") ? 2 : 0;
                int x1 = x - (w - 1) / 2;
                return new int[]{x1 - j, x1 + w - 1 + j, z - j, z + d - 1 + j};
            }
            case "tower", "fountain", "pond", "sphere", "cylinder", "well", "tree" -> {
                int r = num(c, "radius", t.equals("tree") ? 3 : t.equals("well") ? 2 : 3) + 1;
                return new int[]{x - r, x + r, z - r, z + r};
            }
            case "path", "wall", "fence", "bridge" -> {
                int x2 = num(c, "x2", x), z2 = num(c, "z2", z), hw = num(c, "width", 1) / 2 + 1;
                return new int[]{Math.min(x, x2) - hw, Math.max(x, x2) + hw, Math.min(z, z2) - hw, Math.max(z, z2) + hw};
            }
            default -> {
                return c.has("x") || c.has("z") ? new int[]{x - 1, x + 1, z - 1, z + 1} : null;
            }
        }
    }

    /** {x1,x2,z1,z2} of a wing (sharing the main wall) for a side, width along the wall, depth out, offset. */
    private static int[] wingRect(BuildingInfo mi, String side, int ww, int wd, int off) {
        return switch (side) {
            case "left" -> new int[]{mi.x1 - wd, mi.x1, mi.z1 + off, mi.z1 + off + ww - 1};
            case "back" -> new int[]{mi.x1 + off, mi.x1 + off + ww - 1, mi.z2, mi.z2 + wd};
            case "front" -> new int[]{mi.x1 + off, mi.x1 + off + ww - 1, mi.z1 - wd, mi.z1};
            default -> new int[]{mi.x2, mi.x2 + wd, mi.z1 + off, mi.z1 + off + ww - 1};
        };
    }

    private static String removeToken(String list, String token) {
        List<String> keep = new ArrayList<>();
        for (String t : list.toLowerCase(Locale.ROOT).split("[,\\s]+")) if (!t.isBlank() && !t.equals(token)) keep.add(t);
        return String.join(",", keep);
    }

    /** Returns {main building with wing hooks, wing building}. Can be applied again for more wings. */
    private static JsonObject[] makeWing(JsonObject main, JsonObject wing, Frame frame, Ground ground) {
        JsonObject m = main.deepCopy();
        m.remove("wing");
        m.remove("wings");
        m.remove("autoWing");
        String side = str(wing, "side", "right").toLowerCase(Locale.ROOT);
        if (!(side.equals("left") || side.equals("right") || side.equals("back") || side.equals("front"))) side = "right";
        // Nothing may jut out over the wing.
        String js = str(m, "jettySides", "front,back").toLowerCase(Locale.ROOT);
        if (js.contains("all")) js = "front,back,left,right";
        m.addProperty("jettySides", removeToken(js, side));
        BuildingInfo mi = buildingInfo(m, new Emitter(frame, ground));
        // Fix the main ridge direction so every wing agrees on which walls are eave sides.
        String ridge = str(m, "ridge", "auto").toLowerCase(Locale.ROOT);
        if (!ridge.equals("x") && !ridge.equals("z")) {
            int[] tr = mi.rect(mi.floors - 1);
            ridge = (tr[1] - tr[0]) >= (tr[3] - tr[2]) ? "x" : "z";
            m.addProperty("ridge", ridge);
        }
        boolean mainRidgeX = ridge.equals("x");
        boolean alongX = side.equals("front") || side.equals("back"); // the shared wall runs along x
        boolean eave = alongX == mainRidgeX;                             // the main roof slopes down to this wall

        int w = mi.x2 - mi.x1 + 1, d = mi.z2 - mi.z1 + 1;
        int wallLen = alongX ? w : d;
        int ww = clamp(num(wing, "width", clamp(wallLen - 3, 5, wallLen)), 5, Math.max(5, wallLen));
        int wd = clamp(num(wing, "depth", 6), 4, 24);
        int wf = clamp(num(wing, "floors", Math.max(1, mi.floors - 1)), 1, mi.floors);
        int off = clamp(num(wing, "offset", Math.max(1, wallLen - ww - 1)), 0, Math.max(0, wallLen - ww));

        int[] r = wingRect(mi, side, ww, wd, off);
        int wx1 = r[0], wx2 = r[1], wz1 = r[2], wz2 = r[3], doorX, doorZ;
        String attach;
        int[] keepMain, keepWing;
        switch (side) {
            case "left" -> {
                doorX = mi.x1; doorZ = wz1 + ww / 2; attach = "right";
                keepMain = new int[]{mi.x1 + 1, doorZ, mi.x1 + 2, doorZ}; keepWing = new int[]{mi.x1 - 1, doorZ, mi.x1 - 2, doorZ};
            }
            case "back" -> {
                doorX = wx1 + ww / 2; doorZ = mi.z2; attach = "front";
                keepMain = new int[]{doorX, mi.z2 - 1, doorX, mi.z2 - 2}; keepWing = new int[]{doorX, mi.z2 + 1, doorX, mi.z2 + 2};
            }
            case "front" -> {
                doorX = wx1 + ww / 2; doorZ = mi.z1; attach = "back";
                keepMain = new int[]{doorX, mi.z1 + 1, doorX, mi.z1 + 2}; keepWing = new int[]{doorX, mi.z1 - 1, doorX, mi.z1 - 2};
            }
            default -> {
                doorX = mi.x2; doorZ = wz1 + ww / 2; attach = "left";
                keepMain = new int[]{mi.x2 - 1, doorZ, mi.x2 - 2, doorZ}; keepWing = new int[]{mi.x2 + 1, doorZ, mi.x2 + 2, doorZ};
            }
        }
        // Hooks on the main building (lists, so several wings add up).
        JsonArray wl = m.has("_wings") && m.get("_wings").isJsonArray() ? m.getAsJsonArray("_wings") : new JsonArray();
        JsonObject hook = new JsonObject();
        hook.addProperty("side", side);
        hook.addProperty("a", alongX ? wx1 : wz1);
        hook.addProperty("b", alongX ? wx2 : wz2);
        hook.addProperty("floors", wf);
        wl.add(hook);
        m.add("_wings", wl);
        String skip = str(m, "_dormerSkip", "");
        m.addProperty("_dormerSkip", skip.isEmpty() ? side : skip + "," + side);
        String cg = str(m, "crossGableSides", mainRidgeX ? "front" : "left,right");
        String cgLeft = removeToken(cg, side);
        m.addProperty("crossGableSides", cgLeft.isEmpty() ? "none" : cgLeft);
        JsonArray keep = m.has("_keep") && m.get("_keep").isJsonArray() ? m.getAsJsonArray("_keep") : new JsonArray();
        keep.addAll(pairs(keepMain));
        m.add("_keep", keep);

        JsonObject wj = m.deepCopy();
        for (String k : new String[]{"_wings", "_dormerSkip", "_keep", "y", "plan",
                "banners", "sign", "awnings", "doorOffset", "crossGables", "crossGableSides", "crossGableWidth",
                "oriel", "orielWidth", "orielGlass", "archetype"}) wj.remove(k);
        int W = wx2 - wx1 + 1, D = wz2 - wz1 + 1;
        wj.addProperty("width", W);
        wj.addProperty("depth", D);
        wj.addProperty("x", wx1 + (W - 1) / 2);
        wj.addProperty("z", wz1);
        wj.addProperty("floors", wf);
        wj.addProperty("_baseY", mi.by);
        wj.addProperty("door", "none");
        wj.addProperty("chimney", false);
        wj.addProperty("yard", false);
        wj.addProperty("dormers", 0);
        wj.addProperty("jetty", 0);
        wj.addProperty("interior", str(wing, "interior", "home"));
        // Hierarchy: the wing is the lesser roof (tight eaves, no skirt or brackets) under the main one.
        wj.addProperty("overhang", Math.min(1, clamp(num(m, "overhang", 1), 0, 3)));
        wj.addProperty("bigRoof", false);
        wj.addProperty("eaveBrackets", false);
        // Eave side: roof at right angles to the main roof. Gable end: same direction, lower.
        wj.addProperty("ridge", eave ? (mainRidgeX ? "z" : "x") : (mainRidgeX ? "x" : "z"));
        wj.addProperty("_attach", attach);
        wj.addProperty("_inner", attach);
        wj.addProperty("_doorX", doorX);
        wj.addProperty("_doorZ", doorZ);
        wj.add("_keep", pairs(keepWing));
        // Calm roofline: a finial only on a wing gable that faces the front.
        if (!str(m, "finialAt", "all").equalsIgnoreCase("all")) wj.addProperty("finialAt", side.equals("front") ? "front" : "none");
        // Anything else the AI gave the wing (wall, roof, ...) overrides what it inherited.
        for (var en : wing.entrySet()) {
            if (!Set.of("side", "width", "depth", "floors", "offset", "interior").contains(en.getKey())) wj.add(en.getKey(), en.getValue());
        }
        BuildingInfo wi = buildingInfo(wj, new Emitter(frame, ground));
        int[] mt = mi.rect(mi.floors - 1);
        JsonArray clip = new JsonArray();
        for (int v : new int[]{mt[0], mt[1], mt[2], mt[3], mi.top}) clip.add(v);
        wj.add("_clip", clip);

        if ("shed".equals(str(wj, "roof", ""))) {
            wj.addProperty("_innerExtend", -1); // a lean-to stops against the main wall
            wj.remove("_clip");
            wj.addProperty("finial", "none");
        } else if (eave) {
            // How far the wing roof runs into the main roof before it is hidden under the main slope.
            int o = clamp(num(m, "overhang", 1), 0, 3), p = clamp(num(m, "pitch", 1), 1, 2);
            int ow = clamp(num(wj, "overhang", 1), 0, 3);
            int kick = p > 1 ? clamp(num(m, "kick", 0), 0, 2) : 0;
            int wSpan = (alongX ? W : D) + 2 * ow;
            String prof = str(wj, "roofProfile", "steep").toLowerCase(Locale.ROOT), mprof = str(m, "roofProfile", "steep").toLowerCase(Locale.ROOT);
            int wingPeak = wi.top + roofRise((wSpan + 1) / 2, prof, (wSpan + 1) / 2, p, kick) + (wSpan % 2 == 0 ? 1 : 0);
            int half = ((alongX ? d : w) - 1) / 2, k = 0, mainL = ((alongX ? d : w) + 2 * o + 1) / 2;
            while (k < half && mi.top + roofRise(o + k + 1, mprof, mainL, p, kick) < wingPeak + 1) k++;
            wj.addProperty("_innerExtend", Math.min(half, k + 1));
        } else {
            wj.addProperty("_innerExtend", -1); // stop one block short: the main gable wall closes it
        }
        return new JsonObject[]{m, wj};
    }

    // ============================ house layouts ============================

    /** House layouts the "plan" option can ask for. */
    public static final List<String> PLANS = List.of("simple", "L", "T", "U", "cross", "stepped", "turret", "L+turret", "tower", "L+tower");

    /**
     * Turns a building's "plan" into wings and corner turrets, trying the next layout when one would run into
     * another part of the plan (falls back to a plain building).
     */
    private static JsonArray withLayouts(JsonArray comps, Frame frame, Ground ground, String style, String climate, int salt) {
        List<int[]> boxes = new ArrayList<>();
        for (JsonElement el : comps) boxes.add(el.isJsonObject() ? layoutBox(el.getAsJsonObject()) : null);
        JsonArray out = new JsonArray();
        int idx = -1;
        for (JsonElement el : comps) {
            idx++;
            if (!el.isJsonObject() || !"building".equals(str(el.getAsJsonObject(), "type", ""))) { out.add(el); continue; }
            JsonObject c = el.getAsJsonObject();
            if (useComposer && HouseComposer.wants(c)) { out.add(c); continue; } // the composer reads plan and switches itself
            String plan = str(c, "plan", "simple");
            c.remove("plan");
            boolean manual = c.has("wing") || c.has("wings");
            if (manual || plan.equalsIgnoreCase("simple") || plan.equalsIgnoreCase("none")) {
                if (!manual) addLeanTo(c, frame, ground, boxes, idx, new ArrayList<>(), salt);
                out.add(c);
                JsonObject porch = frontPorch(c, frame, ground, comps, boxes, idx, List.of());
                if (porch != null) out.add(porch);
                JsonObject gal = frontBalcony(c, frame, ground, comps, boxes, idx, List.of());
                if (gal != null) out.add(gal);
                JsonObject dk = frontDeck(c, frame, ground, comps, boxes, idx, List.of());
                if (dk != null) out.add(dk);
                continue;
            }
            c.remove("autoWing");
            List<JsonObject> turrets = new ArrayList<>();
            JsonObject snap = c.deepCopy(); // a tower changes the house (jetty, wall hooks): undone if the layout is dropped
            try {
                BuildingInfo mi = buildingInfo(c, new Emitter(frame, ground));
                int seed = Math.floorMod(num(c, "x", 0) * 92821 ^ num(c, "z", 0) * 68917 ^ salt * 31, 100_003);
                List<String> tries = new ArrayList<>(List.of(plan));
                for (String p : List.of("L", "T", "turret")) if (!tries.contains(p)) tries.add(p);
                for (String p : tries) {
                    restore(c, snap);
                    List<int[]> rects = new ArrayList<>();
                    JsonArray wings = layoutWings(p, mi, seed, rects);
                    List<JsonObject> tw = wings == null ? null : layoutTurrets(p, c, mi, seed, rects, wings);
                    if (wings == null || tw == null || (wings.isEmpty() && tw.isEmpty())) continue;
                    boolean clear = true;
                    for (int[] r : rects) if (!clearOf(r, boxes, idx, 0)) { clear = false; break; }
                    for (int i = 0; i < rects.size() && clear; i++) for (int j = i + 1; j < rects.size(); j++) { // tower vs its own wings
                        int[] q = rects.get(i), r = rects.get(j);
                        if (!tw.isEmpty() && "square".equals(str(tw.get(0), "shape", "")) && j == rects.size() - 1 && q[0] <= r[1] && q[1] >= r[0] && q[2] <= r[3] && q[3] >= r[2]) clear = false;
                    }
                    if (!clear) { restore(c, snap); continue; }
                    if (!wings.isEmpty()) c.add("wings", wings);
                    keepDoorClear(c, mi, wings);
                    turrets = tw;
                    addLeanTo(c, frame, ground, boxes, idx, rects, salt);
                    break;
                }
            } catch (RuntimeException ignored) {
                // keep the plain building
                restore(c, snap);
                turrets = new ArrayList<>();
            }
            out.add(c);
            for (JsonObject t : turrets) {
                if (c.has("palette")) t.addProperty("palette", str(c, "palette", ""));
                out.add(StylePresets.apply(t, style, climate, salt));
            }
            JsonObject porch = frontPorch(c, frame, ground, comps, boxes, idx, turrets);
            if (porch != null) out.add(porch);
            JsonObject gal = frontBalcony(c, frame, ground, comps, boxes, idx, turrets);
            if (gal != null) out.add(gal);
            JsonObject dk = frontDeck(c, frame, ground, comps, boxes, idx, turrets);
            if (dk != null) out.add(dk);
        }
        return out;
    }

    /** Puts a component back the way it was (keys added since the snapshot are removed). */
    private static void restore(JsonObject c, JsonObject snap) {
        for (String k : new ArrayList<>(c.keySet())) if (!snap.has(k)) c.remove(k);
        for (var en : snap.entrySet()) c.add(en.getKey(), en.getValue().deepCopy());
    }

    /** Exact footprint used when checking room for wings/turrets: paths count at their real width, buildings with a 1-block gap. */
    private static int[] layoutBox(JsonObject c0) {
        JsonObject c = normalize(c0);
        String t = str(c, "type", "");
        int x = num(c, "x", 0), z = num(c, "z", 0);
        if (t.equals("path")) {
            int x2 = num(c, "x2", x), z2 = num(c, "z2", z), hw = Math.max(0, num(c, "width", 1) / 2);
            return new int[]{Math.min(x, x2) - hw, Math.max(x, x2) + hw, Math.min(z, z2) - hw, Math.max(z, z2) + hw};
        }
        int[] f = footprint(c0);
        if (f == null) return null;
        return t.equals("building") ? new int[]{f[0] - 1, f[1] + 1, f[2] - 1, f[3] + 1} : f;
    }

    /**
     * A small covered porch over the front door ("frontPorch": true), when the plan has none there already,
     * nothing stands in front of the door and no upper floor juts out over it.
     */
    private static JsonObject frontPorch(JsonObject c, Frame frame, Ground ground, JsonArray comps, List<int[]> boxes, int self,
                                         List<JsonObject> turrets) {
        if (!bool(c, "frontPorch", false) || "none".equals(str(c, "door", "x"))) return null;
        BuildingInfo mi;
        try { mi = buildingInfo(c, new Emitter(frame, ground)); } catch (RuntimeException ex) { return null; }
        if (mi.jF && mi.floors > 1 && mi.h < 5) return null;
        for (JsonElement el : comps) { // the AI already put a porch on this front
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!"porch".equals(str(o, "type", ""))) continue;
            int px = num(o, "x", 0), pz = num(o, "z", 0);
            if (px >= mi.x1 - 1 && px <= mi.x2 + 1 && pz >= mi.z1 - 6 && pz <= mi.z1 + 1) return null;
        }
        int dx = mi.doorX;
        if (c.has("wings")) for (JsonElement we : c.getAsJsonArray("wings")) { // front wings next to the door
            JsonObject w = we.getAsJsonObject();
            if (!"front".equals(str(w, "side", ""))) continue;
            int a = mi.x1 + num(w, "offset", 0), b = a + num(w, "width", 5) - 1;
            if (dx + 3 >= a && dx - 3 <= b) return null;
        }
        int[] r = {dx - 2, dx + 2, mi.z1 - 4, mi.z1 - 1};
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (i == self || b == null) continue;
            JsonElement el = comps.get(i);
            if (el.isJsonObject() && "path".equals(str(el.getAsJsonObject(), "type", ""))) continue; // paths lead to it
            if (b[0] <= r[1] && b[1] >= r[0] && b[2] <= r[3] && b[3] >= r[2]) return null;
        }
        for (JsonObject t : turrets) {
            int tx = num(t, "x", 0), tz = num(t, "z", 0), tr = num(t, "radius", 3);
            if (tx - tr <= r[1] + 1 && tx + tr >= r[0] - 1 && tz - tr <= r[3] && tz + tr >= r[2]) return null;
        }
        JsonObject p = new JsonObject();
        p.addProperty("type", "porch");
        p.addProperty("x", dx);
        p.addProperty("z", mi.z1);
        p.addProperty("side", "front");
        p.addProperty("width", 5);
        p.addProperty("depth", 3);
        p.addProperty("roofBlock", mi.wood + "_slab");
        p.addProperty("post", mi.frame != null ? mi.frame : mi.wood + "_fence");
        if (c.has("fascia")) p.add("fascia", c.get("fascia"));
        return p;
    }

    /**
     * A broad balcony across the first upper floor of the front ("frontBalcony": true): a gallery two deep on
     * timber posts with knee braces, a fence railing, its own pent roof and a door out from the upstairs room.
     */
    private static JsonObject frontBalcony(JsonObject c, Frame frame, Ground ground, JsonArray comps, List<int[]> boxes, int self,
                                           List<JsonObject> turrets) {
        if (!bool(c, "frontBalcony", false) || bool(c, "oriel", false)) return null;
        BuildingInfo mi;
        try { mi = buildingInfo(c, new Emitter(frame, ground)); } catch (RuntimeException ex) { return null; }
        if (mi.floors < 2 || mi.x2 - mi.x1 + 1 < 9) return null;
        if (c.has("wings")) for (JsonElement we : c.getAsJsonArray("wings")) if ("front".equals(str(we.getAsJsonObject(), "side", ""))) return null;
        for (JsonElement el : comps) { // the AI already placed a balcony or porch on this front
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String t = str(o, "type", "");
            if (!t.equals("balcony") && !t.equals("porch")) continue;
            int px = num(o, "x", 0), pz = num(o, "z", 0);
            if (px >= mi.x1 - 1 && px <= mi.x2 + 1 && pz >= mi.z1 - 6 && pz <= mi.z1 + 1) return null;
        }
        int w = mi.x2 - mi.x1 + 1;
        int bw = clamp(w - 4, 5, 11);
        if (bw % 2 == 0) bw--;
        int cx = (mi.x1 + mi.x2) / 2, d = 2;
        int[] rf = mi.rect(1);
        int[] r = {cx - bw / 2 - 1, cx + bw / 2 + 1, rf[2] - d - 1, rf[2] - 1};
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (i == self || b == null) continue;
            JsonElement el = comps.get(i);
            if (el.isJsonObject() && "path".equals(str(el.getAsJsonObject(), "type", ""))) continue;
            if (b[0] <= r[1] && b[1] >= r[0] && b[2] <= r[3] && b[3] >= r[2]) return null;
        }
        for (JsonObject t : turrets) {
            int tx = num(t, "x", 0), tz = num(t, "z", 0), tr = num(t, "radius", 3) + 1;
            if (tx - tr <= r[1] && tx + tr >= r[0] && tz - tr <= r[3] && tz + tr >= r[2]) return null;
        }
        JsonObject p = new JsonObject();
        p.addProperty("type", "balcony");
        p.addProperty("x", cx);
        p.addProperty("z", mi.z1);
        p.addProperty("side", "front");
        p.addProperty("width", bw);
        p.addProperty("depth", d);
        p.addProperty("storey", 1);
        p.addProperty("posts", true);
        p.addProperty("roof", "lean");
        if (c.has("roofTrim")) p.add("roofBlock", c.get("roofTrim"));
        if (c.has("door") && !"none".equals(str(c, "door", ""))) p.add("door", c.get("door"));
        return p;
    }

    /**
     * A deep front deck at the first upper floor ("frontDeck": true), wider than the house: two big posts to the
     * ground, joists with stepped ends, a carved edge board, a railing and a door out (the reference survival-house look).
     */
    private static JsonObject frontDeck(JsonObject c, Frame frame, Ground ground, JsonArray comps, List<int[]> boxes, int self,
                                        List<JsonObject> turrets) {
        if (!bool(c, "frontDeck", false) || bool(c, "oriel", false) || bool(c, "frontPorch", false) || bool(c, "frontBalcony", false)) return null;
        BuildingInfo mi;
        try { mi = buildingInfo(c, new Emitter(frame, ground)); } catch (RuntimeException ex) { return null; }
        int w = mi.x2 - mi.x1 + 1;
        if (mi.floors < 2 || w < 11) return null;
        if (c.has("wings")) for (JsonElement we : c.getAsJsonArray("wings")) if ("front".equals(str(we.getAsJsonObject(), "side", ""))) return null;
        for (JsonElement el : comps) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String t = str(o, "type", "");
            if (!t.equals("balcony") && !t.equals("porch")) continue;
            int px = num(o, "x", 0), pz = num(o, "z", 0);
            if (px >= mi.x1 - 1 && px <= mi.x2 + 1 && pz >= mi.z1 - 6 && pz <= mi.z1 + 1) return null;
        }
        int bw = w + 4;
        if (bw % 2 == 0) bw--;
        int cx = (mi.x1 + mi.x2) / 2, d = mi.h >= 5 ? 4 : 3;
        int[] rf = mi.rect(1);
        int[] r = {cx - bw / 2 - 1, cx + bw / 2 + 1, rf[2] - d - 2, rf[2] - 1};
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (i == self || b == null) continue;
            JsonElement el = comps.get(i);
            if (el.isJsonObject() && "path".equals(str(el.getAsJsonObject(), "type", ""))) continue;
            if (b[0] <= r[1] && b[1] >= r[0] && b[2] <= r[3] && b[3] >= r[2]) return null;
        }
        for (JsonObject t : turrets) {
            int tx = num(t, "x", 0), tz = num(t, "z", 0), tr = num(t, "radius", 3) + 2;
            if (tx - tr <= r[1] && tx + tr >= r[0] && tz - tr <= r[3] && tz + tr >= r[2]) return null;
        }
        JsonObject p = new JsonObject();
        p.addProperty("type", "balcony");
        p.addProperty("x", cx);
        p.addProperty("z", mi.z1);
        p.addProperty("side", "front");
        p.addProperty("width", bw);
        p.addProperty("depth", d);
        p.addProperty("storey", 1);
        p.addProperty("deck", true);
        if (c.has("door") && !"none".equals(str(c, "door", ""))) p.add("door", c.get("door"));
        return p;
    }

    /**
     * A lean-to (outshot): a low one-room annex with a single slope against a side or the back wall, a roof
     * level of its own under the main eaves ("leanTo": true, or chance via the style). Only on houses of two or
     * more floors, where it fits under the main roof and nothing else stands there.
     */
    private static void addLeanTo(JsonObject c, Frame frame, Ground ground, List<int[]> boxes, int self, List<int[]> rects, int salt) {
        if (!bool(c, "leanTo", false)) return;
        BuildingInfo mi;
        try { mi = buildingInfo(c, new Emitter(frame, ground)); } catch (RuntimeException ex) { return; }
        int o = clamp(num(c, "overhang", 1), 0, 3), wh = 3;
        int maxD = mi.floors * mi.h - wh - o; // the slope has to stay under the main eaves
        if (mi.floors < 2 || maxD < 3) return;
        int wd = Math.min(4, maxD);
        java.util.Set<String> used = new java.util.HashSet<>();
        if (c.has("wings")) for (JsonElement we : c.getAsJsonArray("wings")) used.add(str(we.getAsJsonObject(), "side", ""));
        int seed = Math.floorMod(num(c, "x", 0) * 31337 ^ num(c, "z", 0) * 7919 ^ salt, 9973);
        List<String> order = new ArrayList<>(seed % 2 == 0 ? List.of("back", "left", "right") : List.of("left", "right", "back"));
        if (seed % 3 == 0) java.util.Collections.swap(order, 1, 2);
        int w = mi.x2 - mi.x1 + 1, d = mi.z2 - mi.z1 + 1;
        for (String side : order) {
            if (used.contains(side)) continue;
            boolean alongX = side.equals("back");
            int wallLen = alongX ? w : d;
            int ww = clamp(Math.round(wallLen * 0.6f), 5, wallLen - 2);
            if (ww < 5) continue;
            int off = alongX ? (wallLen - ww) / 2 + ((seed / 7) % 3 - 1) * Math.max(0, (wallLen - ww) / 2 - 1) : wallLen - ww - 1;
            off = clamp(off, 0, wallLen - ww);
            int[] r = wingRect(mi, side, ww, wd, off);
            int[] ro = side.equals("left") ? new int[]{r[0] - o, r[1] - 1, r[2] - o, r[3] + o}
                : side.equals("right") ? new int[]{r[0] + 1, r[1] + o, r[2] - o, r[3] + o}
                : new int[]{r[0] - o, r[1] + o, r[2] + 1, r[3] + o};
            if (!clearOf(ro, boxes, self, 0)) continue;
            boolean hit = false;
            for (int[] q : rects) if (q[0] <= ro[1] + 1 && q[1] >= ro[0] - 1 && q[2] <= ro[3] + 1 && q[3] >= ro[2] - 1) hit = true;
            if (hit) continue;
            JsonObject lw = new JsonObject();
            lw.addProperty("side", side);
            lw.addProperty("width", ww);
            lw.addProperty("depth", wd);
            lw.addProperty("floors", 1);
            lw.addProperty("offset", off);
            lw.addProperty("roof", "shed");
            lw.addProperty("floorHeight", wh);
            lw.addProperty("interior", "none");
            lw.addProperty("lowerWall", "none");
            lw.addProperty("frontPorch", false);
            JsonArray ws = c.has("wings") && c.get("wings").isJsonArray() ? c.getAsJsonArray("wings") : new JsonArray();
            ws.add(lw);
            c.add("wings", ws);
            rects.add(ro);
            return;
        }
    }

    /** Moves the front door (doorOffset) so a wing coming forward never stands right beside it. */
    private static void keepDoorClear(JsonObject c, BuildingInfo mi, JsonArray wings) {
        List<int[]> blocked = new ArrayList<>();
        for (JsonElement we : wings) {
            JsonObject w = we.getAsJsonObject();
            if (!"front".equals(str(w, "side", ""))) continue;
            int a = mi.x1 + num(w, "offset", 0);
            blocked.add(new int[]{a - 1, a + num(w, "width", 5)}); // the wing plus one free column each side
        }
        if (blocked.isEmpty()) return;
        int best = Integer.MIN_VALUE;
        for (int x = mi.x1 + 2; x <= mi.x2 - 2; x++) {
            boolean ok = true;
            for (int[] b : blocked) if (x >= b[0] && x <= b[1]) ok = false;
            if (ok && (best == Integer.MIN_VALUE || Math.abs(x - mi.doorX) < Math.abs(best - mi.doorX))) best = x;
        }
        if (best != Integer.MIN_VALUE && best != mi.doorX) c.addProperty("doorOffset", best - num(c, "x", 0));
    }

    private static JsonObject wingSpec(String side, int width, int depth, int floors, int offset) {
        JsonObject w = new JsonObject();
        w.addProperty("side", side);
        w.addProperty("width", width);
        w.addProperty("depth", depth);
        w.addProperty("floors", floors);
        w.addProperty("offset", offset);
        return w;
    }

    /** Wings for a layout (their rectangles go into rects), or null if the building is too small for it. */
    private static JsonArray layoutWings(String plan, BuildingInfo mi, int seed, List<int[]> rects) {
        int w = mi.x2 - mi.x1 + 1, d = mi.z2 - mi.z1 + 1, f = mi.floors, lower = Math.max(1, f - 1);
        String side = seed % 2 == 0 ? "right" : "left", other = side.equals("right") ? "left" : "right";
        JsonArray out = new JsonArray();
        java.util.function.Consumer<JsonObject> add = ws -> {
            out.add(ws);
            rects.add(wingRect(mi, str(ws, "side", "right"), num(ws, "width", 5), num(ws, "depth", 5), num(ws, "offset", 0)));
        };
        switch (plan.toUpperCase(Locale.ROOT).replace("+TURRET", "").replace("+TOWER", "")) {
            case "L" -> {
                if ((seed / 2) % 2 == 0 || w < 11) { // wing on a side, set back toward the rear
                    int ww = clamp(Math.round(d * 0.65f), 5, d), wd = clamp(Math.round(w * 0.55f), 4, 9);
                    add.accept(wingSpec(side, ww, wd, lower, d - ww));
                } else {                             // wing coming forward at one end of the front
                    int ww = clamp(Math.round(w * 0.4f), 5, Math.max(5, w / 2 - 1)), wd = clamp(Math.round(d * 0.5f), 4, 8);
                    add.accept(wingSpec("front", ww, wd, lower, side.equals("left") ? 0 : w - ww));
                }
            }
            case "T" -> {
                int ww = clamp(Math.round(w * 0.55f), 5, Math.max(5, w - 2)), wd = clamp(Math.round(d * 0.7f), 4, 10);
                add.accept(wingSpec("back", ww, wd, lower, (w - ww) / 2));
            }
            case "U" -> {
                if (w < 13) return null;
                int ww = clamp(Math.round(w * 0.3f), 5, (w - 3) / 2), wd = clamp(Math.round(d * 0.55f), 4, 8);
                add.accept(wingSpec("front", ww, wd, lower, 0));
                add.accept(wingSpec("front", ww, wd, lower, w - ww));
            }
            case "CROSS" -> {
                int ww = clamp(Math.round(d * 0.55f), 5, Math.max(5, d - 2)), wd = clamp(Math.round(w * 0.45f), 4, 8);
                add.accept(wingSpec("left", ww, wd, lower, (d - ww) / 2));
                add.accept(wingSpec("right", ww, wd, lower, (d - ww) / 2));
            }
            case "STEPPED" -> { // a tall-ish wing on one side, a small low one on the other, at different depths
                int wa = clamp(Math.round(d * 0.7f), 5, d), da = clamp(Math.round(w * 0.5f), 4, 8);
                int wb = clamp(Math.round(d * 0.45f), 5, d), db = clamp(Math.round(w * 0.35f), 4, 6);
                add.accept(wingSpec(side, wa, da, lower, d - wa));
                add.accept(wingSpec(other, wb, db, 1, Math.min(1, d - wb)));
            }
            case "TURRET", "TOWER" -> { }
            default -> { return null; }
        }
        return out;
    }

    /** A round turret at a front corner for "turret" layouts, opposite any side wing. */
    private static List<JsonObject> layoutTurrets(String plan, JsonObject c, BuildingInfo mi, int seed, List<int[]> rects, JsonArray wings) {
        if (plan.toUpperCase(Locale.ROOT).contains("TOWER")) return layoutSquareTower(c, mi, seed, rects, wings);
        if (!plan.toUpperCase(Locale.ROOT).contains("TURRET")) return List.of();
        int w = mi.x2 - mi.x1 + 1;
        String wingSide = wings != null && !wings.isEmpty() ? str(wings.get(0).getAsJsonObject(), "side", "") : "";
        boolean leftCorner = wingSide.equals("right") || (!wingSide.equals("left") && seed % 2 == 0);
        if (w < 9) return null;
        // Radius 3, centred 2 blocks out on the diagonal: the ring meets the house exactly at its corner block,
        // so the turret's hollow never cuts into a room (it only trims the roof overhang it runs into).
        int r = 3;
        int cx = leftCorner ? mi.x1 - 2 : mi.x2 + 2, cz = mi.z1 - 2;
        JsonObject t = new JsonObject();
        t.addProperty("type", "tower");
        t.addProperty("x", cx);
        t.addProperty("z", cz);
        t.addProperty("radius", r);
        t.addProperty("height", mi.top - mi.by + 1 + 3); // a little above the eaves; the cone tops the ridge
        t.addProperty("door", "none");
        t.addProperty("_turret", true);
        // Dressed like the house: stone up to the first floor, the house's walls/frame above, its roof colours.
        String lower = optBlock(c, "lowerWall");
        t.addProperty("wall", lower != null ? lower : mi.foundation);
        t.addProperty("foundation", mi.foundation);
        t.addProperty("upperWall", mi.wall);
        t.addProperty("upperFrom", mi.h);
        if (mi.frame != null) t.addProperty("frame", mi.frame);
        t.addProperty("floor", mi.floor);
        for (String k : new String[]{"roofBlock", "roofGradient", "window"}) if (c.has(k)) t.add(k, c.get(k));
        t.addProperty("roof", "cone");
        t.addProperty("roofOverhang", 1);
        t.addProperty("roofSteepness", 3);
        t.addProperty("floorHeight", mi.h);
        rects.add(new int[]{cx - r, cx + r, cz - r, cz + r});
        return List.of(t);
    }

    /**
     * A square stone tower against one side of the house, its front standing two blocks proud of the facade
     * (the JPCORE look): stone storeys with band courses, a timber-framed top room, a pyramid roof in the house's
     * roof colours, a doorway from the house, and a stone staircase winding up the outside to doors on the upper
     * levels. The house side it stands on loses its jetty and is treated like a wing wall (no windows behind it).
     */
    private static List<JsonObject> layoutSquareTower(JsonObject c, BuildingInfo mi, int seed, List<int[]> rects, JsonArray wings) {
        int w = mi.x2 - mi.x1 + 1, d = mi.z2 - mi.z1 + 1;
        if (w < 9 || d < 7 || mi.floors < 2) return null;
        String wingSide = wings != null && !wings.isEmpty() ? str(wings.get(0).getAsJsonObject(), "side", "") : "";
        boolean right = wingSide.equals("left") || (!wingSide.equals("right") && seed % 2 == 1);
        String side = right ? "right" : "left";
        if (wingSide.equals(side)) return null;
        int r = Math.max(w, d) >= 15 ? 3 : 2, n = 2 * r + 1;
        int tx1 = right ? mi.x2 + 1 : mi.x1 - n, tx2 = tx1 + n - 1;
        int tz1 = mi.z1 - 2, tz2 = tz1 + n - 1;
        // Nothing of the house may jut out over the tower; its windows behind the tower are left out.
        String js = str(c, "jettySides", "front,back").toLowerCase(Locale.ROOT);
        if (js.contains("all")) js = "front,back,left,right";
        c.addProperty("jettySides", removeToken(js, side));
        JsonArray wl = c.has("_wings") && c.get("_wings").isJsonArray() ? c.getAsJsonArray("_wings") : new JsonArray();
        JsonObject hook = new JsonObject();
        hook.addProperty("side", side);
        hook.addProperty("a", mi.z1);
        hook.addProperty("b", Math.min(mi.z2, tz2));
        hook.addProperty("floors", mi.floors);
        wl.add(hook);
        c.add("_wings", wl);
        int linkZ = mi.z1 + 1, houseX = right ? mi.x2 : mi.x1;
        JsonArray keep = c.has("_keep") && c.get("_keep").isJsonArray() ? c.getAsJsonArray("_keep") : new JsonArray();
        keep.addAll(pairs(new int[]{houseX - (right ? 1 : -1), linkZ, houseX - (right ? 2 : -2), linkZ}));
        c.add("_keep", keep);
        JsonObject t = new JsonObject();
        t.addProperty("type", "tower");
        t.addProperty("shape", "square");
        t.addProperty("x", (tx1 + tx2) / 2);
        t.addProperty("z", (tz1 + tz2) / 2);
        t.addProperty("radius", r);
        t.addProperty("_baseY", mi.by);
        t.addProperty("_houseSide", right ? "left" : "right");
        t.addProperty("_houseX", houseX);
        t.addProperty("_linkZ", linkZ);
        t.addProperty("_houseFloors", mi.floors);
        // Hierarchy: the tower stands clearly taller than the house, its top room level with the ridge and the spire above it.
        t.addProperty("_wallTop", Math.min(Math.max(mi.top + mi.h + 2, mi.roofTop), mi.top + 2 * mi.h + 2));
        // The top room juts out on brackets; toward the house only over an eave (never into a gable wall).
        int[] mtr = mi.rect(mi.floors - 1);
        String rdg = str(c, "ridge", "auto").toLowerCase(Locale.ROOT);
        boolean rx = rdg.equals("x") || (!rdg.equals("z") && (mtr[1] - mtr[0]) >= (mtr[3] - mtr[2]));
        t.addProperty("_jutHouse", !rx);
        t.addProperty("_houseClearY", mi.top + 2 * (clamp(num(c, "overhang", 1), 0, 3) + 1) + 2);
        t.addProperty("floorHeight", mi.h);
        t.addProperty("door", "none");
        String lower = optBlock(c, "lowerWall");
        String stone = lower != null ? lower : mi.foundation;
        t.addProperty("wall", stone);
        t.addProperty("foundation", mi.foundation);
        t.addProperty("upperWall", mi.wall);
        if (mi.frame != null) t.addProperty("frame", mi.frame);
        t.addProperty("floor", mi.floor);
        t.addProperty("roof", "hip");
        for (String k : new String[]{"roofBlock", "roofGradient", "roofTrim", "roofProfile", "pitch", "kick", "eaveUnderside", "fascia",
                "finial", "finialCap", "window", "baseCourse", "bandCourse", "snowRoof", "roofMoss", "sill", "roofStripes", "thickRoof"}) {
            if (c.has(k)) t.add(k, c.get(k));
        }
        rects.add(new int[]{tx1 - 1, tx2 + 1, tz1 - 1, tz2 + 1}); // with the staircase round it
        return List.of(t);
    }

    /** Square tower (shape "square"), built by layoutSquareTower; also usable on its own. */
    private static void squareTower(JsonObject c, Emitter e) {
        int r = clamp(num(c, "radius", 2), 2, 6), n = 2 * r + 1;
        int cx = num(c, "x", 0), cz = num(c, "z", r + 2);
        int tx1 = cx - r, tx2 = cx + r, tz1 = cz - r, tz2 = cz + r;
        int[] g = e.groundRange(tx1, tz1, tx2, tz2);
        int by = c.has("_baseY") ? num(c, "_baseY", 0) : (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        int fb = g == null ? by - 1 : Math.max(by - 16, Math.min(by - 1, g[0]));
        int h = clamp(num(c, "floorHeight", 4), 3, 8);
        int wallTop = c.has("_wallTop") ? num(c, "_wallTop", by + 12) : by + clamp(num(c, "height", 14), 6, 48) - 1;
        String stoneMix = block(c, "wall", "stone_bricks");
        String stone = solidOf(VoxelGrid.splitPattern(stoneMix).get(0).replaceAll("^\\d+%", ""));
        String foundation = block(c, "foundation", stone);
        String floor = block(c, "floor", "spruce_planks");
        String upper = optBlock(c, "upperWall");
        String frame = optBlock(c, "frame");
        String window = block(c, "window", "glass_pane");
        String houseSide = str(c, "_houseSide", "");                 // the tower side facing the house ("left"/"right")
        boolean right = houseSide.equals("left");                      // tower stands to the right of the house
        int sx = houseSide.equals("right") ? -1 : 1;
        int innerX = sx > 0 ? tx1 : tx2, outerX = sx > 0 ? tx2 : tx1;

        // Body: foundation, hollow stone shell, floor plates every storey.
        e.set(tx1, fb, tz1, tx2, by - 1, tz2, foundation);
        e.set(tx1, by, tz1, tx2, wallTop, tz2, "air");
        e.region(tx1, by, tz1, tx2, wallTop, tz2, "//walls " + stone);
        String baseC = optBlock(c, "baseCourse"), bandC = optBlock(c, "bandCourse");
        if (baseC != null) e.region(tx1, by, tz1, tx2, by, tz2, "//walls " + baseC);
        List<Integer> plates = new ArrayList<>();
        for (int y = by + h - 1; y <= wallTop - 3; y += h) plates.add(y);
        e.set(tx1 + 1, by - 1, tz1 + 1, tx2 - 1, by - 1, tz2 - 1, floor);
        for (int y : plates) {
            e.set(tx1 + 1, y, tz1 + 1, tx2 - 1, y, tz2 - 1, floor);
            if (bandC != null) e.region(tx1, y, tz1, tx2, y, tz2, "//walls " + bandC); // a band at every floor line
        }
        int topStart = plates.isEmpty() ? by : plates.get(plates.size() - 1) + 1;
        boolean timberTop = upper != null && frame != null && wallTop - topStart + 1 >= 3 && !plates.isEmpty();
        // The top room juts out one block on brackets (toward the house only where it clears the house roof).
        boolean jutIn = bool(c, "_jutHouse", false) && topStart >= num(c, "_houseClearY", Integer.MAX_VALUE);
        int ux1 = tx1 - (sx > 0 && !jutIn ? 0 : 1), ux2 = tx2 + (sx < 0 && !jutIn ? 0 : 1), uz1 = tz1 - 1, uz2 = tz2 + 1;
        if (!c.has("_houseSide")) { ux1 = tx1 - 1; ux2 = tx2 + 1; }
        String wood = species(frame);
        if (timberTop) {
            e.set(tx1, topStart, tz1, tx2, wallTop, tz2, "air");
            e.set(ux1, topStart - 1, uz1, ux2, topStart - 1, uz2, floor);                 // jetty floor
            ring(e, ux1, ux2, uz1, uz2, topStart - 1, axisState(frame, e.frame.axis('x'), e), axisState(frame, e.frame.axis('z'), e));
            for (int x = ux1; x <= ux2; x += 2) {                                        // brackets under the jetty
                if (uz1 < tz1) e.set(x, topStart - 2, uz1, x, topStart - 2, uz1, stairPat(wood + "_stairs", "+z", true, e));
                e.set(x, topStart - 2, uz2, x, topStart - 2, uz2, stairPat(wood + "_stairs", "-z", true, e));
            }
            for (int z = uz1; z <= uz2; z += 2) {
                if (ux1 < tx1) e.set(ux1, topStart - 2, z, ux1, topStart - 2, z, stairPat(wood + "_stairs", "+x", true, e));
                if (ux2 > tx2) e.set(ux2, topStart - 2, z, ux2, topStart - 2, z, stairPat(wood + "_stairs", "-x", true, e));
            }
            e.region(ux1, topStart, uz1, ux2, wallTop, uz2, "//walls " + upper);
            String vpost = axisState(frame, "y", e);
            int mx = (ux1 + ux2) / 2, mz = (uz1 + uz2) / 2;
            for (int x : new int[]{ux1, mx, ux2}) for (int z : new int[]{uz1, mz, uz2}) {
                if (x != mx || z != mz) e.set(x, topStart, z, x, wallTop, z, vpost);
            }
            ring(e, ux1, ux2, uz1, uz2, wallTop, axisState(frame, e.frame.axis('x'), e), axisState(frame, e.frame.axis('z'), e));
        } else { ux1 = tx1; ux2 = tx2; uz1 = tz1; uz2 = tz2; }
        // Inside: a ladder in the front corner on the house side, a lantern under every floor.
        int lx = innerX + sx, lz = tz1 + 1;
        int lastPlate = plates.isEmpty() ? by - 1 : plates.get(plates.size() - 1);
        if (lastPlate >= by) e.set(lx, by, lz, lx, lastPlate, lz, "ladder[facing=" + e.frame.dir("+z") + "]");
        for (int y : plates) if (cx != lx || cz != lz) e.set(cx, y - 1, cz, cx, y - 1, cz, "lantern[hanging=true]");
        // Doorway from the house at ground level.
        if (c.has("_linkZ")) {
            int lz0 = num(c, "_linkZ", cz), hx = num(c, "_houseX", innerX - sx);
            e.set(Math.min(hx, innerX), by, lz0, Math.max(hx, innerX), by + 1, lz0, "air");
        }

        // Outside staircase: up the front face, round the outer corner, along the outer side and the back,
        // a landing and a door at each upper storey (corners are flat landings).
        java.util.Map<Long, Integer> stepTop = new java.util.HashMap<>();
        if (bool(c, "outsideStairs", true) && !plates.isEmpty()) {
            String st = stairsOf(stone), solid = stone;
            String door = str(c, "stairDoor", species(frame) + "_door");
            List<int[]> path = new ArrayList<>(); // {x, z, corner?1:0, travel dir code, inward x, inward z}
            for (int k = 0; k < n; k++) { int x = innerX + sx * k; path.add(new int[]{x, tz1 - 1, 0, sx > 0 ? 0 : 1, x, tz1}); }
            path.add(new int[]{outerX + sx, tz1 - 1, 1, 2, 0, 0});
            for (int k = 0; k < n; k++) path.add(new int[]{outerX + sx, tz1 + k, 0, 2, outerX, tz1 + k});
            path.add(new int[]{outerX + sx, tz2 + 1, 1, sx > 0 ? 1 : 0, 0, 0});
            for (int k = 0; k < n - 1; k++) { int x = outerX - sx * k; path.add(new int[]{x, tz2 + 1, 0, sx > 0 ? 1 : 0, x, tz2}); }
            String[] dirs = {"+x", "-x", "+z"};
            int maxF = Math.min(Math.min(2, num(c, "_houseFloors", 2)), plates.size() - 1);
            int cur = by - 1, f = 1;
            for (int[] pc : path) {
                if (f > maxF) break;
                int target = plates.get(f - 1);
                int x = pc[0], z = pc[1];
                int[] gs = e.groundRange(x, z, x, z);
                int gy = gs == null ? by - 1 : Math.max(by - 12, gs[0]);
                if (cur < target && pc[2] == 0) { // a step
                    cur++;
                    if (cur - 1 >= gy) e.set(x, gy, z, x, cur - 1, z, solid);
                    e.set(x, cur, z, x, cur, z, stairState(st, dirs[pc[3]], e));
                    e.set(x, cur + 1, z, x, cur + 3, z, "air");
                } else {                        // flat: a corner, or the landing
                    int y = Math.max(cur, by - 1);
                    e.set(x, gy, z, x, y, z, solid);
                    e.set(x, y + 1, z, x, y + 3, z, "air");
                    boolean cornerCol = (pc[4] == tx1 || pc[4] == tx2) && (pc[5] == tz1 || pc[5] == tz2);
                    if (cur == target && pc[2] == 0 && !cornerCol) {
                        String inward = e.frame.dir(switch (pc[3]) { case 2 -> sx > 0 ? "-x" : "+x"; default -> pc[1] < tz1 ? "+z" : "-z"; });
                        String d0 = safeBlock(door);
                        if (d0.endsWith("_door")) {
                            e.set(pc[4], y + 1, pc[5], pc[4], y + 1, pc[5], d0 + "[facing=" + inward + ",half=lower,hinge=left]");
                            e.set(pc[4], y + 2, pc[5], pc[4], y + 2, pc[5], d0 + "[facing=" + inward + ",half=upper,hinge=left]");
                        }
                        f++;
                    }
                }
                stepTop.put(cell(x, z), cur);
            }
        }
        // Windows: one narrow window per storey, taking turns between the front and the outer face (none where the
        // stairs pass in front); the top room looks out on both.
        String sill = optBlock(c, "sill");
        List<Integer> levels = new ArrayList<>();
        levels.add(by);
        for (int y : plates) levels.add(y + 1);
        for (int k = 0; k < levels.size(); k++) {
            int y0 = levels.get(k) + 1;
            if (y0 + 1 >= wallTop) continue;
            boolean top = k == levels.size() - 1;
            int[][] all = top && timberTop
                ? new int[][]{{cx, uz1, 0, -1}, {sx > 0 ? ux2 : ux1, cz, sx, 0}}
                : new int[][]{{cx, tz1, 0, -1}, {outerX, cz, sx, 0}};
            int[][] faces = top ? all : new int[][]{all[k % 2]};
            for (int[] fc : faces) {
                int ox = fc[0] + fc[2], oz = fc[1] + fc[3];
                Integer blockTop = stepTop.get(cell(ox, oz));
                if (blockTop != null && blockTop >= y0 - 1) continue;
                String pane = paneState(window, e.frame.axis(fc[2] == 0 ? 'x' : 'z'));
                e.set(fc[0], y0, fc[1], fc[0], y0 + 1, fc[1], pane);
                if (sill != null && top && (blockTop == null)) {
                    String towardWall = fc[2] == 0 ? (fc[3] < 0 ? "+z" : "-z") : (fc[2] < 0 ? "+x" : "-x");
                    e.set(ox, y0 - 1, oz, ox, y0 - 1, oz, sillState(sill, towardWall, e));
                }
            }
        }
        // Brackets under the eave, then a flared spire in the house's roof colours: two nearly flat rings at the
        // bottom, drawn up ever steeper into a needle with a finial (the tower roofs of the reference builds).
        String wd = species(frame);
        for (int x = ux1 - 1; x <= ux2 + 1; x += 2) {
            e.set(x, wallTop, uz1 - 1, x, wallTop, uz1 - 1, stairPat(wd + "_stairs", "+z", true, e));
            e.set(x, wallTop, uz2 + 1, x, wallTop, uz2 + 1, stairPat(wd + "_stairs", "-z", true, e));
        }
        for (int z = uz1 - 1; z <= uz2 + 1; z += 2) {
            e.set(ux1 - 1, wallTop, z, ux1 - 1, wallTop, z, stairPat(wd + "_stairs", "+x", true, e));
            e.set(ux2 + 1, wallTop, z, ux2 + 1, wallTop, z, stairPat(wd + "_stairs", "-x", true, e));
        }
        e.set(ux1 - 1, wallTop + 1, uz1 - 1, ux2 + 1, wallTop + 1, uz2 + 1, wd + "_planks");
        List<String> grad = gradient(str(c, "roofGradient", block(c, "roofBlock", "dark_oak_stairs")));
        if (grad.isEmpty()) grad.add("dark_oak_stairs");
        int halfU = Math.max(Math.max(cx - ux1, ux2 - cx), Math.max(cz - uz1, uz2 - cz));
        int R = halfU + 2, y0 = wallTop + 2;
        int[] hr = new int[R + 1];
        for (int rr = 0, hh = 0; rr < R; rr++) {                       // rr counts rings from the outside in
            hh += rr < 2 ? 0 : rr < 5 ? 1 : rr < 7 ? 2 : 3;
            hr[R - rr] = y0 + hh;
        }
        for (int x = cx - R; x <= cx + R; x++) {
            for (int z = cz - R; z <= cz + R; z++) {
                int rr0 = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                if (rr0 == 0) continue;
                int yy = hr[rr0], dx = x - cx, dz = z - cz;
                String band = grad.get(Math.min(grad.size() - 1, (R - rr0) * grad.size() / R));
                String stp = stairsOf(band), sol = solidOf(band);
                String toward = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "-x" : "+x") : (dz > 0 ? "-z" : "+z");
                if (rr0 == R) {
                    if ((x + z) % 2 == 0 && Math.abs(dx) != Math.abs(dz)) e.set(x, yy, z, x, yy, z, slabOf(stp));
                    continue;
                }
                e.set(x, yy, z, x, yy, z, stairPat(stp, toward, false, e));
                if (rr0 <= halfU) { if (yy - 1 >= y0) e.set(x, y0, z, x, yy - 1, z, sol); }
                else if (yy - 1 >= y0) e.set(x, yy - 1, z, x, yy - 1, z, sol);
            }
        }
        int peak = hr[1] + 2;
        String topB = solidOf(grad.get(grad.size() - 1));
        e.set(cx, y0, cz, cx, peak, cz, topB);
        String fin = optBlock(c, "finial");
        if (fin == null) fin = wd + "_fence";
        e.set(cx, peak + 1, cz, cx, peak + 2, cz, fin);
        String cap = optBlock(c, "finialCap");
        if (cap != null && !cap.contains("lantern")) e.set(cx, peak + 3, cz, cx, peak + 3, cz, cap);
    }

    private static String species(String block) {
        String b = block == null ? "" : VoxelGrid.baseId(block).replace("stripped_", "");
        for (String s0 : new String[]{"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "crimson", "warped"})
            if (b.startsWith(s0 + "_")) return s0;
        return "spruce";
    }

    private static JsonArray pairs(int[] xz) {
        JsonArray a = new JsonArray();
        for (int i = 0; i + 1 < xz.length; i += 2) {
            JsonArray p = new JsonArray();
            p.add(xz[i]);
            p.add(xz[i + 1]);
            a.add(p);
        }
        return a;
    }

    // ============================ small pieces ============================

    /** Village well: stone ring with water, corner posts, little gabled roof and a lantern. */
    private static void well(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 4);
        int r = clamp(num(c, "radius", 2), 1, 3);
        String rim = block(c, "block", "60%cobblestone,25%stone_bricks,15%mossy_cobblestone");
        String wood = woodOf(str(c, "post", "spruce_fence"), "spruce");
        String post = block(c, "post", wood + "_fence");
        String roofB = block(c, "roofBlock", wood + "_stairs");
        int depth = clamp(num(c, "depth", 4), 2, 10);
        int[] g = e.groundRange(x - r, z - r, x + r, z + r);
        int by = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        e.set(x - r, by, z - r, x + r, by + 4 + r, z + r, "air");
        if (g != null && g[0] < by - depth - 1) e.set(x - r, g[0], z - r, x + r, by - depth - 2, z + r, rim);
        e.set(x - r, by - depth - 1, z - r, x + r, by, z + r, rim);           // shaft walls and rim
        e.set(x - r + 1, by - depth, z - r + 1, x + r - 1, by - 1, z + r - 1, "water");
        e.set(x - r + 1, by, z - r + 1, x + r - 1, by, z + r - 1, "air");
        for (int[] k : new int[][]{{x - r, z - r}, {x + r, z - r}, {x - r, z + r}, {x + r, z + r}}) {
            e.set(k[0], by + 1, k[1], k[0], by + 3, k[1], post);
        }
        // Gable roof along x, ridge slab on the middle row.
        for (int i = 0; i < r; i++) {
            e.set(x - r, by + 4 + i, z - r + i, x + r, by + 4 + i, z - r + i, stairState(roofB, "+z", e));
            e.set(x - r, by + 4 + i, z + r - i, x + r, by + 4 + i, z + r - i, stairState(roofB, "-z", e));
        }
        e.set(x - r, by + 4 + r, z, x + r, by + 4 + r, z, slabOf(roofB));
        String boards = solidOf(roofB);
        for (int i = 0; i < r; i++) { // gable boards at both ends hold the upper rows
            e.set(x - r, by + 4 + i, z - r + 1 + i, x - r, by + 4 + i, z + r - 1 - i, boards);
            e.set(x + r, by + 4 + i, z - r + 1 + i, x + r, by + 4 + i, z + r - 1 - i, boards);
        }
        e.set(x, by + 3 + r, z, x, by + 3 + r, z, "lantern[hanging=true]");
    }

    /** Market stall: posts, striped cloth roof, a counter in front and crates at the back. */
    private static void stall(JsonObject c, Emitter e) {
        int w = clamp(num(c, "width", 5), 3, 9), d = clamp(num(c, "depth", 3), 2, 5);
        int x1 = num(c, "x", 0) - (w - 1) / 2, x2 = x1 + w - 1, z1 = num(c, "z", 3), z2 = z1 + d - 1;
        String color = str(c, "color", "red").toLowerCase(Locale.ROOT).replaceAll("_wool$", "");
        if (!color.matches("[a-z_]+")) color = "red";
        String wood = woodOf(str(c, "post", "spruce_fence"), "spruce");
        String post = block(c, "post", wood + "_fence");
        int[] g = e.groundRange(x1, z1, x2, z2);
        int by = (g == null ? 0 : g[1] + 1) + num(c, "y", 0);
        if (g != null && g[0] < by - 1) e.set(x1, g[0] + 1, z1, x2, by - 1, z2, "dirt");
        e.set(x1, by, z1 - 1, x2, by + 3, z2, "air");
        for (int[] k : new int[][]{{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}}) e.set(k[0], by, k[1], k[0], by + 2, k[1], post);
        for (int x = x1; x <= x2; x++) { // striped roof, one row hanging out over the counter
            String wool = (x - x1) % 2 == 0 ? color + "_wool" : "white_wool";
            e.set(x, by + 3, z1 - 1, x, by + 3, z2, wool);
        }
        e.set(x1 + 1, by, z1, x2 - 1, by, z1, wood + "_slab[type=top]");     // counter
        String[] goods = {"barrel[facing=up]", "hay_block", "pumpkin", "barrel[facing=up]", "melon", "composter"};
        for (int x = x1 + 1, k = 0; x <= x2 - 1; x++, k++) e.set(x, by, z2, x, by, z2, goods[k % goods.length]);
        e.set((x1 + x2) / 2, by + 2, (z1 + z2) / 2, (x1 + x2) / 2, by + 2, (z1 + z2) / 2, "lantern[hanging=true]");
    }

    /**
     * A saved build from the library (config/aiworldedit/library), placed whole with WorldEdit:
     * x = center, z = front, rotate (0/90/180/270) turns it further. The area is cleared, low ground under
     * it is filled up to its ground layer, then the build is pasted (air in it is skipped).
     */
    private static void library(JsonObject c, Emitter e) {
        String name = str(c, "name", str(c, "build", ""));
        BuildLibrary.Entry en = BuildLibrary.get(name);
        if (en == null) throw new PlanException("no saved build called '" + name + "' (see /ailib)");
        int turns = Math.floorMod(Math.round(num(c, "rotate", 0) / 90f), 4);
        int[] ls = en.localSize(turns);
        int x1 = num(c, "x", 0) - (ls[0] - 1) / 2, z1 = num(c, "z", 2), x2 = x1 + ls[0] - 1, z2 = z1 + ls[2] - 1;
        int[] g = e.groundRange(x1, z1, x2, z2);
        int gy = (g == null ? -1 : g[1]) + num(c, "y", 0); // its ground layer takes the place of the top terrain block
        int top = gy + ls[1] - en.groundLayer;
        e.set(x1, gy + 1, z1, x2, top, z2, "air");
        if (g != null && g[0] < gy) {
            String loose = "air,water,short_grass,tall_grass,fern,large_fern,snow,dandelion,poppy";
            e.region(x1, Math.max(g[0], gy - 12), z1, x2, gy - 1, z2, "//replace " + loose + " dirt");
            e.region(x1, gy, z1, x2, gy, z2, "//replace " + loose + " grass_block");
        }
        BuildLibrary.Placement p = BuildLibrary.prepare(en, e.frame, x1, z1, gy, turns);
        e.cmds.add("//schem load " + p.schemName());
        e.cmds.add("//paste -o -a");
    }

    /** Stack of firewood logs with a slab cover. x,z = first log; length along `axis` (x|z). */
    private static void woodpile(JsonObject c, Emitter e) {
        int x = num(c, "x", 0), z = num(c, "z", 3);
        int len = clamp(num(c, "length", 3), 1, 8), hgt = clamp(num(c, "height", 2), 1, 3);
        char axis = "z".equalsIgnoreCase(str(c, "axis", "x")) ? 'z' : 'x';
        String log = block(c, "block", "spruce_log");
        String cover = optBlock(c, "cover");
        if (cover == null && !c.has("cover")) cover = woodOf(log, "spruce") + "_slab";
        int x2 = axis == 'x' ? x + len - 1 : x, z2 = axis == 'z' ? z + len - 1 : z;
        woodpileAt(e, x, z, x2, z2, hgt, log, cover, axis);
    }

    // ============================== helpers ==============================

    /** Collects commands for one component, in local coordinates. */
    private static final class Emitter {
        final Frame frame;
        final Ground ground;
        final List<String> cmds = new ArrayList<>();
        List<BuildingInfo> buildings = new ArrayList<>();
        /** Towers in the plan as {cx, cz, radius incl. roof flare}, so chimneys can keep clear. */
        List<int[]> towers = new ArrayList<>();
        /** Boxes {x1,x2,z1,z2,yMax} that set() leaves alone (a wing roof must not cut into the main rooms). */
        final List<int[]> clips = new ArrayList<>();

        Emitter(Frame frame, Ground ground) {
            this.frame = frame;
            this.ground = ground;
        }

        private String lastP1, lastP2;

        void region(int x1, int y1, int z1, int x2, int y2, int z2, String command) {
            String p1 = frame.pos(x1, y1, z1), p2 = frame.pos(x2, y2, z2);
            if (!p1.equals(lastP1)) cmds.add("//pos1 " + p1); // selection unchanged: don't resend it
            if (!p2.equals(lastP2)) cmds.add("//pos2 " + p2);
            lastP1 = p1;
            lastP2 = p2;
            cmds.add(command);
        }

        void set(int x1, int y1, int z1, int x2, int y2, int z2, String pattern) {
            setClipped(x1, y1, z1, x2, y2, z2, pattern, 0);
        }

        private void setClipped(int x1, int y1, int z1, int x2, int y2, int z2, String pattern, int ci) {
            if (x1 > x2 || y1 > y2 || z1 > z2) return; // empty range
            if (ci >= clips.size()) { region(x1, y1, z1, x2, y2, z2, "//set " + pattern); return; }
            int[] k = clips.get(ci);
            int ix1 = Math.max(x1, k[0]), ix2 = Math.min(x2, k[1]), iz1 = Math.max(z1, k[2]), iz2 = Math.min(z2, k[3]);
            int iy2 = Math.min(y2, k[4]);
            if (ix1 > ix2 || iz1 > iz2 || y1 > iy2) { setClipped(x1, y1, z1, x2, y2, z2, pattern, ci + 1); return; }
            // Everything except the clipped part, as up to five boxes.
            setClipped(x1, y1, z1, ix1 - 1, y2, z2, pattern, ci + 1);
            setClipped(ix2 + 1, y1, z1, x2, y2, z2, pattern, ci + 1);
            setClipped(ix1, y1, z1, ix2, y2, iz1 - 1, pattern, ci + 1);
            setClipped(ix1, y1, iz2 + 1, ix2, y2, z2, pattern, ci + 1);
            setClipped(ix1, iy2 + 1, iz1, ix2, y2, iz2, pattern, ci + 1);
        }

        /** Straight line of blocks from one corner of the selection to the other. */
        void line(int x1, int y1, int z1, int x2, int y2, int z2, String pattern) {
            region(x1, y1, z1, x2, y2, z2, "//line " + pattern);
        }

        /** Shape command centered/based on pos1 (needs //placement pos1). */
        void shape(int x, int y, int z, String command) {
            String p1 = frame.pos(x, y, z);
            if (!p1.equals(lastP1)) cmds.add("//pos1 " + p1);
            lastP1 = p1;
            cmds.add(command);
        }

        /** {min, max} ground height (relative to the anchor's Y) over a local rectangle, or null. */
        int[] groundRange(int x1, int z1, int x2, int z2) {
            if (ground == null) return null;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
            int step = Math.max(1, Math.max(bx - ax, bz - az) / 24); // sample big areas sparsely
            for (int x = ax; x <= bx; x += step) {
                for (int z = az; z <= bz; z += step) {
                    int y = ground.surfaceY(frame.wx(x, z), frame.wz(x, z));
                    if (y == Integer.MIN_VALUE) continue;
                    lo = Math.min(lo, y);
                    hi = Math.max(hi, y);
                }
            }
            if (lo == Integer.MAX_VALUE) return null;
            int ay = frame.anchor.getY();
            return new int[]{lo - ay, hi - ay};
        }
    }

    private static final class PlanException extends RuntimeException {
        PlanException(String m) { super(m); }
    }

    /** Evenly spaced posts from a to b (inclusive), roughly every `bay` blocks, symmetric. */
    private static List<Integer> posts(int a, int b, int bay, boolean oddBays) {
        List<Integer> p = new ArrayList<>();
        int n = Math.max(1, Math.round((b - a) / (float) bay));
        if (oddBays && n % 2 == 0) n = ((b - a) / (float) (n - 1) <= bay * 1.5f) ? n - 1 : n + 1;
        for (int i = 0; i <= n; i++) {
            int v = a + Math.round(i * (b - a) / (float) n);
            if (p.isEmpty() || v - p.get(p.size() - 1) >= 2 || i == n) p.add(v);
        }
        return p;
    }

    /** Window width centered in a bay, same parity as the bay so it is symmetric. */
    private static int windowWidth(int[] span) {
        int gap = span[1] - span[0] + 1;
        if (gap <= 2) return gap;
        int solidEachSide = Math.max(1, (gap - 3) / 2);
        return gap - 2 * solidEachSide;
    }

    /** Pairs of neighbouring posts that have room for a window between them. */
    private static List<int[]> bays(List<Integer> posts) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < posts.size(); i++) {
            int a = posts.get(i) + 1, b = posts.get(i + 1) - 1;
            if (b - a >= 0) out.add(new int[]{a, b});
        }
        return out;
    }

    private static String stair(String stairs, boolean isStairs, String solid, String localDir, Emitter e) {
        if (!isStairs) return solid;
        return stairPat(stairs, localDir, false, e);
    }

    /** Stairs (or a weighted mix of stairs) facing a local direction; upside down when `top`. */
    private static String stairPat(String stairs, String localDir, boolean top, Emitter e) {
        String state = "[facing=" + e.frame.dir(localDir) + ",half=" + (top ? "top" : "bottom") + "]";
        List<String> out = new ArrayList<>();
        for (String part : VoxelGrid.splitPattern(stairs)) {
            if (part.contains("[")) { out.add(part); continue; }
            out.add(part.endsWith("_stairs") ? part + state : part);
        }
        return String.join(",", out);
    }

    private static String sillState(String sill, String towardWall, Emitter e) {
        if (sill.endsWith("_stairs")) return sill + "[facing=" + e.frame.dir(towardWall) + ",half=top]";
        if (sill.endsWith("_slab")) return sill + "[type=top]";
        return sill;
    }

    private static String paneState(String window, String worldAxis) {
        if (!window.endsWith("glass_pane") || window.contains("[")) return window;
        return worldAxis.equals("x") ? window + "[east=true,west=true]" : window + "[north=true,south=true]";
    }

    private static String axisState(String block, String worldAxis, Emitter e) {
        if (block.contains("[") || block.contains("%") || block.contains(",")) return block;
        boolean pillar = block.contains("_log") || block.contains("_wood") || block.contains("_stem")
            || block.contains("hyphae") || block.contains("pillar") || block.contains("basalt")
            || block.equals("hay_block") || block.equals("bone_block");
        return pillar ? block + "[axis=" + worldAxis + "]" : block;
    }

    /** Full block matching a stair/slab, e.g. stone_brick_stairs -> stone_bricks, oak_stairs -> oak_planks. */
    static String solidOf(String block) {
        if (block.contains(",")) { // weighted mix: convert each part
            List<String> out = new ArrayList<>();
            for (String part : VoxelGrid.splitPattern(block)) {
                int pct = part.indexOf('%');
                out.add((pct >= 0 ? part.substring(0, pct + 1) : "") + solidOf(pct >= 0 ? part.substring(pct + 1) : part));
            }
            return String.join(",", out);
        }
        String b = block.replace("minecraft:", "");
        if (b.contains("[")) b = b.substring(0, b.indexOf('['));
        String base;
        if (b.endsWith("_stairs")) base = b.substring(0, b.length() - 7);
        else if (b.endsWith("_slab")) base = b.substring(0, b.length() - 5);
        else return b;
        if (WOODS.contains(base)) return base + "_planks";
        if (base.equals("purpur")) return "purpur_block";
        if (base.equals("quartz")) return "quartz_block";
        if (base.endsWith("brick") || base.endsWith("tile")) return base + "s";
        return base;
    }

    // ---- AI output clean-up ----

    /** Other names models use for the same field. */
    private static final String[][] FIELD_ALIASES = {
        {"radius", "r", "rad", "size_radius"}, {"height", "h", "tall", "levels_height"},
        {"width", "w", "sizeX", "size_x", "lengthX"}, {"depth", "d", "sizeZ", "size_z", "lengthZ"},
        {"length", "len", "size"}, {"floors", "stories", "storeys", "levels"},
        {"block", "material", "blocks", "pattern"}, {"wall", "wallBlock", "wall_block", "wallMaterial"},
        {"roofBlock", "roof_block", "roofMaterial", "roof_material"}, {"x2", "endX", "end_x", "toX"},
        {"y2", "endY", "end_y", "toY"}, {"z2", "endZ", "end_z", "toZ"},
        {"floorHeight", "floor_height", "storyHeight"}, {"windowSpacing", "window_spacing"},
        {"storey", "story", "floorIndex", "floor_number", "level"}, {"interior", "furniture", "furnish"},
        {"pitch", "roofPitch", "roof_pitch"}};

    /** Other names models use for component types. */
    private static final String[][] TYPE_ALIASES = {
        {"tower", "spire", "turret", "round_tower", "lighthouse", "minaret", "wizard_tower"},
        {"building", "house", "cottage", "hut", "hall", "shop", "room"},
        {"wall", "castle_wall", "fence_wall"}, {"path", "road", "walkway"},
        {"box", "cube", "cuboid", "platform"}, {"lamp_post", "lamp", "lantern_post", "streetlight"},
        {"column", "pillar", "pilaster"}, {"stairs", "staircase", "steps", "stairway"}, {"arch", "archway", "gateway"},
        {"porch", "veranda", "portico"}, {"balcony", "terrace"}, {"chimney", "smokestack"},
        {"fountain"}, {"well", "water_well", "village_well"},
        {"stall", "market_stall", "booth", "stand", "market_stand"}, {"woodpile", "wood_pile", "log_pile", "firewood"},
        {"library", "saved_build", "schematic", "prefab", "template", "litematic"}, {"pond", "lake", "pool"}, {"garden", "flower_bed", "flowerbed", "hedge"},
        {"fence", "fencing", "railing_fence"}, {"bridge", "footbridge"},
        {"cylinder", "cyl", "tube"}, {"sphere", "ball", "dome"}, {"block", "single_block", "decoration"},
        {"clear", "air", "remove"}, {"texture", "replace", "weathering"}};

    /** Fixes common variations so fewer components are rejected. */
    static JsonObject normalize(JsonObject in) {
        JsonObject c = in.deepCopy();
        for (String[] names : FIELD_ALIASES) {
            if (c.has(names[0])) continue;
            for (int i = 1; i < names.length; i++) {
                if (c.has(names[i]) && !c.get(names[i]).isJsonNull()) {
                    c.add(names[0], c.get(names[i]));
                    break;
                }
            }
        }
        // "from":[x,y,z] / "to":[x,y,z], "pos"/"position"/"center":[x,y,z] or {"x":..,"y":..,"z":..}
        copyPoint(c, new String[]{"from", "start", "pos", "position", "center", "at"}, "x", "y", "z");
        copyPoint(c, new String[]{"to", "end"}, "x2", "y2", "z2");
        // "size":[w,h,d]
        if (c.has("size") && c.get("size").isJsonArray()) {
            JsonArray a = c.getAsJsonArray("size");
            if (a.size() == 3) {
                if (!c.has("width")) c.add("width", a.get(0));
                if (!c.has("height")) c.add("height", a.get(1));
                if (!c.has("depth")) c.add("depth", a.get(2));
            }
        }
        if (c.has("type")) {
            String t = str(c, "type", "").toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            outer:
            for (String[] names : TYPE_ALIASES) {
                for (String n : names) {
                    if (t.equals(n)) { t = names[0]; break outer; }
                }
            }
            c.addProperty("type", t);
        }
        return c;
    }

    private static void copyPoint(JsonObject c, String[] keys, String kx, String ky, String kz) {
        for (String k : keys) {
            if (!c.has(k)) continue;
            JsonElement v = c.get(k);
            try {
                if (v.isJsonArray() && v.getAsJsonArray().size() >= 3) {
                    JsonArray a = v.getAsJsonArray();
                    if (!c.has(kx)) c.add(kx, a.get(0));
                    if (!c.has(ky)) c.add(ky, a.get(1));
                    if (!c.has(kz)) c.add(kz, a.get(2));
                } else if (v.isJsonArray() && v.getAsJsonArray().size() == 2) { // [x,z]
                    JsonArray a = v.getAsJsonArray();
                    if (!c.has(kx)) c.add(kx, a.get(0));
                    if (!c.has(kz)) c.add(kz, a.get(1));
                } else if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject();
                    if (!c.has(kx) && o.has("x")) c.add(kx, o.get("x"));
                    if (!c.has(ky) && o.has("y")) c.add(ky, o.get("y"));
                    if (!c.has(kz) && o.has("z")) c.add(kz, o.get("z"));
                }
            } catch (Exception ignored) {
                // leave as is
            }
            return;
        }
    }

    // ---- JSON access ----

    private static int num(JsonObject o, String k, int def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? (int) Math.round(o.get(k).getAsDouble()) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int req(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) throw new PlanException("missing '" + k + "'");
        try {
            return (int) Math.round(o.get(k).getAsDouble());
        } catch (Exception e) {
            throw new PlanException("'" + k + "' must be a number");
        }
    }

    private static String str(JsonObject o, String k, String def) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return def;
            String s = o.get(k).getAsString().trim();
            return s.isEmpty() ? def : s;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String block(JsonObject o, String k, String def) {
        String s = str(o, k, def);
        return s == null ? null : safeBlock(s);
    }

    private static String optBlock(JsonObject o, String k) {
        String s = str(o, k, null);
        if (s == null || s.equalsIgnoreCase("none")) return null;
        return safeBlock(s);
    }

    /** Blocks/patterns may only contain ids, weights, states and separators. */
    private static String safeBlock(String s) {
        String b = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (!b.matches("[a-z0-9_:%,.\\[\\]=#!^*]+")) throw new PlanException("bad block '" + s + "'");
        return b;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
