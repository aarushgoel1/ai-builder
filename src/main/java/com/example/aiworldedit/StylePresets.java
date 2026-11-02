package com.example.aiworldedit;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Style presets: a style fills in every setting a component didn't specify, so the AI only has to
 * describe layout and the build still gets a consistent, detailed look. Settings the AI gives win.
 *
 * Medieval buildings also get:
 *  - a palette (spruce/stone, dark oak/deepslate, oak/cobblestone, brick roof), picked per building
 *    from its position so a village mixes them; the AI can name one with "palette";
 *  - small seeded variations (door position, window rhythm, dormers) so no two houses are identical;
 *  - climate changes (desert sandstone, snowy roofs) from the ground the build stands on.
 *
 * Add a style: add a case to apply() and keywords to detect().
 */
public final class StylePresets {
    private StylePresets() {}

    /** Picks a style from the player's request, or null. */
    public static String detect(String request) {
        if (request == null) return null;
        String r = request.toLowerCase(Locale.ROOT);
        if (r.contains("trixy")) return "trixy"; // the TrixyBlox look for every house
        if (r.matches(".*\\b(medieval|tavern|inn|blacksmith|half[- ]timbered|timber[- ]framed|tudor|village|hamlet)\\b.*")) return "medieval";
        return null;
    }

    /** Climate from the request ("desert", "snowy") or null; the ground block decides otherwise. */
    public static String detectClimate(String request) {
        if (request == null) return null;
        String r = request.toLowerCase(Locale.ROOT);
        if (r.matches(".*\\b(desert|sand|sandstone|arid|dune)\\b.*")) return "desert";
        if (r.matches(".*\\b(snow|snowy|winter|frozen|tundra|ice|icy|nordic|alpine)\\b.*")) return "snowy";
        return null;
    }

    /** Climate from the block the build stands on. */
    public static String climateOfGround(String blockId) {
        if (blockId == null) return null;
        String b = blockId.replace("minecraft:", "");
        if (b.equals("sand") || b.equals("red_sand") || b.contains("sandstone") || b.equals("terracotta")) return "desert";
        if (b.equals("snow") || b.equals("snow_block") || b.contains("ice") || b.equals("powder_snow")) return "snowy";
        return null;
    }

    public static JsonObject apply(JsonObject c, String defaultStyle) {
        return apply(c, defaultStyle, null);
    }

    public static JsonObject apply(JsonObject c, String defaultStyle, String climate) {
        return apply(c, defaultStyle, climate, 0);
    }

    /** @param salt extra variation (e.g. from the build position) mixed into each component's seed */
    public static JsonObject apply(JsonObject c, String defaultStyle, String climate, int salt) {
        String style = c.has("style") && c.get("style").isJsonPrimitive()
            ? c.get("style").getAsString().toLowerCase(Locale.ROOT) : defaultStyle;
        if (style == null) return c;
        String type = c.has("type") ? c.get("type").getAsString() : "";
        String cl = c.has("climate") && c.get("climate").isJsonPrimitive()
            ? c.get("climate").getAsString().toLowerCase(Locale.ROOT) : climate;
        if (style.equals("trixy") || style.equals("trixyblox")) {
            if (type.equals("building") && !c.has("variant")) c.addProperty("variant", "trixy");
            if (!c.has("palette")) c.addProperty("palette", "trixy");
            style = "medieval";
        }
        if (style.equals("medieval")) medieval(c, type, cl, salt);
        return c;
    }

    // ------------------------------------------------------------------

    /** One medieval material set. */
    private record Palette(String name, String lowerWall, String wall, String frame, String foundation, String floor,
                           String roofGradient, String roofTrim, String windowFrame, String shutters, String door,
                           String quoins, String chimney, String finial, String towerRoof, String stoneMix) {}

    private static final Palette SPRUCE = new Palette("spruce",
        "85%stone_bricks,15%cracked_stone_bricks",
        "calcite", "stripped_spruce_log",
        "60%cobblestone,25%stone_bricks,15%mossy_cobblestone", "spruce_planks",
        "spruce_stairs|spruce_stairs|jungle_stairs", "dark_oak_stairs",
        "dark_oak_planks", "spruce_trapdoor", "spruce_door", "polished_andesite",
        "80%stone_bricks,20%cobblestone", "spruce_fence", "spruce_planks,jungle_planks,acacia_planks",
        "85%stone_bricks,15%cracked_stone_bricks");

    private static final Palette DARK_OAK = new Palette("dark_oak",
        "80%deepslate_bricks,20%cracked_deepslate_bricks",
        "white_terracotta", "stripped_dark_oak_log",
        "60%cobbled_deepslate,25%deepslate_bricks,15%tuff", "dark_oak_planks",
        "deepslate_tile_stairs|deepslate_tile_stairs|deepslate_brick_stairs",
        "dark_oak_stairs", "spruce_planks", "dark_oak_trapdoor", "dark_oak_door", "polished_deepslate",
        "80%deepslate_bricks,20%cobbled_deepslate", "dark_oak_fence", "deepslate_bricks,cobbled_deepslate,deepslate_tiles",
        "80%deepslate_bricks,20%cracked_deepslate_bricks");

    private static final Palette OAK = new Palette("oak",
        "80%cobblestone,20%mossy_cobblestone",
        "smooth_sandstone", "oak_log",
        "55%cobblestone,30%mossy_cobblestone,15%stone", "oak_planks",
        "dark_oak_stairs|spruce_stairs|spruce_stairs", "cobblestone_stairs",
        "spruce_planks", "oak_trapdoor", "oak_door", "stone_bricks",
        "60%cobblestone,25%mossy_cobblestone,15%stone", "oak_fence", "spruce_planks,dark_oak_planks",
        "80%cobblestone,20%mossy_cobblestone");

    private static final Palette BRICK = new Palette("brick",
        "85%stone_bricks,15%mossy_stone_bricks",
        "calcite", "dark_oak_log",
        "60%cobblestone,25%stone_bricks,15%mossy_cobblestone", "spruce_planks",
        "mangrove_stairs|brick_stairs|brick_stairs", "dark_oak_stairs",
        "dark_oak_planks", "spruce_trapdoor", "spruce_door", "bricks",
        "70%bricks,30%stone_bricks", "dark_oak_fence", "mangrove_planks,bricks",
        "85%stone_bricks,15%cracked_stone_bricks");

    /** Grey stone-slate roof with moss and spruce trim (the cottage look). */
    private static final Palette SLATE = new Palette("slate",
        "85%cobblestone,15%mossy_cobblestone",
        "calcite", "stripped_spruce_log",
        "55%cobblestone,30%mossy_cobblestone,15%stone", "spruce_planks",
        "70%cobblestone_stairs,30%mossy_cobblestone_stairs|cobblestone_stairs|stone_brick_stairs|stone_brick_stairs", "spruce_stairs",
        "spruce_planks", "spruce_trapdoor", "spruce_door", "stone_bricks",
        "60%stone_bricks,25%cobblestone,15%mossy_stone_bricks", "spruce_fence", "cobblestone,stone_bricks",
        "85%cobblestone,15%mossy_cobblestone");

    /** Learned from a saved fantasy-medieval build: tuff stone, pale oak walls, spruce roof with dark oak trim, overgrown. */
    private static final Palette TUFF = new Palette("tuff",
        "80%tuff_bricks,20%polished_tuff",
        "pale_oak_planks", "stripped_spruce_log",
        "50%tuff,30%polished_tuff,20%moss_block", "spruce_planks",
        "dark_oak_stairs|spruce_stairs|spruce_stairs", "dark_oak_stairs",
        "dark_oak_planks", "dark_oak_trapdoor", "dark_oak_door", "chiseled_tuff_bricks",
        "60%tuff_bricks,25%polished_tuff,15%tuff", "dark_oak_fence", "spruce_planks,dark_oak_planks",
        "80%tuff_bricks,20%polished_tuff");

    /** Learned from TrixyBlox's medieval village: tuff/andesite base, grey plaster or dark planks, striped oak/jungle/spruce roofs. */
    private static final Palette TRIXY = new Palette("trixy",
        "35%tuff_bricks,25%andesite,20%mossy_stone_bricks,20%tuff",
        "55%light_gray_concrete_powder,45%light_gray_concrete", "oak_wood",
        "45%andesite,30%tuff,25%mossy_cobblestone", "spruce_planks",
        "45%oak_stairs,35%jungle_stairs,20%spruce_stairs|55%jungle_stairs,25%oak_stairs,20%spruce_stairs"
            + "|60%oak_stairs,40%spruce_stairs|50%spruce_stairs,30%jungle_stairs,20%oak_stairs", "jungle_stairs",
        "spruce_planks", "spruce_trapdoor", "spruce_door", "andesite",
        "50%andesite,25%tuff,15%mossy_cobblestone,10%stone", "spruce_fence", "oak_planks,jungle_planks,spruce_planks",
        "35%tuff_bricks,25%andesite,20%mossy_stone_bricks,20%tuff");

    private static final Palette[] PALETTES = {SPRUCE, DARK_OAK, OAK, BRICK, SLATE, TUFF, TRIXY};

    /** Palettes learned from saved builds (see BuildStudy); mixed into every house type's choices. */
    private static volatile List<Palette> learned = List.of();
    /** Palettes whose houses get moss on the roof and greenery at the walls. */
    private static final java.util.Set<String> OVERGROWN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static { OVERGROWN.add("tuff"); OVERGROWN.add("slate"); }

    /** Turns what BuildStudy measured in saved builds into palettes (missing parts come from spruce). */
    public static void setLearned(List<BuildStudy.Digest> digests) {
        List<Palette> out = new java.util.ArrayList<>();
        for (BuildStudy.Digest d : digests) {
            Map<String, String> p = d.palette();
            if (!p.containsKey("wall") && !p.containsKey("roofGradient")) continue; // not enough to go on
            String trim = p.getOrDefault("roofTrim", SPRUCE.roofTrim());
            String grad = p.getOrDefault("roofGradient", SPRUCE.roofGradient());
            String tower = String.join(",", java.util.Arrays.stream(grad.split("\\|"))
                .map(s -> s.replace("_stairs", "_planks").replace("brick_planks", "bricks").replace("tile_planks", "tiles")).toList());
            String lower = p.getOrDefault("lowerWall", SPRUCE.lowerWall());
            out.add(new Palette(d.name(), lower, p.getOrDefault("wall", SPRUCE.wall()), p.getOrDefault("frame", SPRUCE.frame()),
                p.getOrDefault("foundation", SPRUCE.foundation()), p.getOrDefault("floor", SPRUCE.floor()), grad, trim,
                trim.endsWith("_stairs") ? trim.replace("_stairs", "_planks") : SPRUCE.windowFrame(),
                p.getOrDefault("shutters", SPRUCE.shutters()), p.getOrDefault("door", SPRUCE.door()),
                p.getOrDefault("cornerBlock", SPRUCE.quoins()), p.getOrDefault("chimneyBlock", SPRUCE.chimney()),
                p.getOrDefault("finial", SPRUCE.finial()), tower, lower));
            if ("true".equals(p.get("overgrowth"))) OVERGROWN.add(d.name());
        }
        learned = List.copyOf(out);
    }
    private static final String[] BANNERS = {"red", "blue", "green", "yellow", "purple", "orange"};

    /** Which palettes each kind of house prefers (repeats = more likely). */
    private static Palette[] prefs(String archetype) {
        return switch (archetype == null ? "" : archetype) {
            case "cottage" -> new Palette[]{SLATE, SLATE, SLATE, SPRUCE, SPRUCE, OAK, DARK_OAK, TUFF, TUFF};
            case "manor" -> new Palette[]{DARK_OAK, DARK_OAK, SPRUCE, SPRUCE, BRICK, BRICK, SLATE, TUFF};
            case "farmhouse" -> new Palette[]{OAK, OAK, OAK, SPRUCE, SPRUCE, SLATE};
            default -> new Palette[]{SPRUCE, SPRUCE, SPRUCE, SPRUCE, DARK_OAK, DARK_OAK, OAK, OAK, BRICK, BRICK, TUFF};
        };
    }

    private static Palette palette(JsonObject c, int seed) {
        return palette(c, seed, prefs(null));
    }

    private static Palette palette(JsonObject c, int seed, Palette[] pool0) {
        String want = c.has("palette") && c.get("palette").isJsonPrimitive()
            ? c.get("palette").getAsString().toLowerCase(Locale.ROOT).replace(' ', '_') : "";
        List<Palette> lp = learned;
        for (Palette p : lp) if (p.name().equals(want.replace('_', '-')) || p.name().equals(want)) return p;
        // Every learned palette joins the pool twice, so builds take after the examples you saved.
        Palette[] pool = pool0;
        if (!lp.isEmpty()) {
            List<Palette> all = new java.util.ArrayList<>(java.util.Arrays.asList(pool0));
            for (Palette p : lp) { all.add(p); all.add(p); }
            pool = all.toArray(new Palette[0]);
        }
        for (Palette p : PALETTES) {
            if (p.name().equals(want) || (want.contains("deepslate") && p == DARK_OAK)
                || (want.contains("brick") && p == BRICK) || (want.contains("cobble") && p == OAK)
                || ((want.contains("stone") || want.contains("grey") || want.contains("gray")) && p == SLATE)
                || ((want.contains("fantasy") || want.contains("pale") || want.contains("overgrown")) && p == TUFF)) return p;
        }
        return pool[(seed / 7) % pool.length];
    }

    /** Kinds of medieval house. */
    public static final java.util.List<String> ARCHETYPES = java.util.List.of("townhouse", "cottage", "manor", "farmhouse");

    /**
     * The kind of house: the one the AI named, otherwise picked from those that suit the footprint
     * (narrow and deep reads as a town house, wide as a cottage or manor, low and long as a farmhouse).
     */
    static String archetype(JsonObject c, int seed, int w, int d, String interior) {
        String want = c.has("archetype") && c.get("archetype").isJsonPrimitive()
            ? c.get("archetype").getAsString().toLowerCase(Locale.ROOT).replaceAll("[ _-]", "") : "";
        if (want.contains("town") || want.contains("tall") || want.contains("narrow")) return "townhouse";
        if (want.contains("cottage") || want.contains("cabin")) return "cottage";
        if (want.contains("manor") || want.contains("hall") || want.contains("mansion") || want.contains("guild")) return "manor";
        if (want.contains("farm") || want.contains("long") || want.contains("barn") || want.contains("hut")) return "farmhouse";
        int floors = num(c, "floors", 0);
        boolean wide = w >= d + 2;
        java.util.List<String> opts = new java.util.ArrayList<>();
        if (!wide) java.util.Collections.addAll(opts, "townhouse", "townhouse", "townhouse");
        java.util.Collections.addAll(opts, "cottage", "cottage", "cottage");
        if (Math.max(w, d) >= 11) java.util.Collections.addAll(opts, "manor", "manor");
        if (floors <= 2 && !interior.equals("tavern")) java.util.Collections.addAll(opts, "farmhouse", "farmhouse");
        if (interior.equals("tavern")) java.util.Collections.addAll(opts, "townhouse", "manor");
        return opts.get(seed % opts.size());
    }

    /** Stable per-component number (the same plan always builds the same). */
    private static int seed(JsonObject c, int salt) {
        int h = num(c, "x", 0) * 73856093 ^ num(c, "z", 0) * 19349663 ^ num(c, "width", 9) * 83492791 ^ num(c, "depth", 7) * 2654435;
        h ^= salt * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return Math.floorMod(h, 100_003);
    }

    /**
     * Medieval: stone ground floor with quoins, moss and buttresses; recessed timber-framed plaster upper
     * floors with cross braces that jut out over the street; steep roofs with a flared eave shading
     * dark to light, dormers, finials and carved eaves; framed windows with sills, shutters and flower
     * boxes; wall lanterns, sign and banners; stone chimney and a lived-in yard.
     */
    private static void medieval(JsonObject c, String type, String climate, int salt) {
        int seed = seed(c, salt);
        java.util.Set<String> given = new java.util.HashSet<>(c.keySet()); // what the AI asked for (never overridden)
        String interior0 = c.has("interior") && c.get("interior").isJsonPrimitive() ? c.get("interior").getAsString().toLowerCase(Locale.ROOT) : "home";
        String arch = type.equals("building") ? archetype(c, seed, num(c, "width", 9), num(c, "depth", 7), interior0) : null;
        // Some houses (about 3 in 10, or all when asked) are built in the TrixyBlox manner.
        boolean trixy = "trixy".equals(str(c, "variant", "").toLowerCase(Locale.ROOT).replace("blox", ""));
        if (type.equals("building") && !c.has("variant") && !c.has("palette") && (seed / 23) % 10 < 3) trixy = true;
        if (trixy && type.equals("building")) {
            c.addProperty("variant", "trixy");
            if (!c.has("palette")) c.addProperty("palette", "trixy");
            trixyDefaults(c, arch, seed);
        }
        Palette pal = palette(c, seed, prefs(arch));
        boolean desert = "desert".equals(climate), snowy = "snowy".equals(climate);
        // Climate first: it replaces palette materials that would look wrong there.
        boolean house = type.equals("building") || type.equals("roof");
        if (desert) {
            if (type.equals("building")) {
                def(c, "lowerWall", "85%sandstone,15%smooth_sandstone");
                def(c, "bandCourse", "cut_sandstone");
                def(c, "wall", "smooth_sandstone");
                def(c, "foundation", "60%sandstone,40%smooth_sandstone");
                def(c, "cornerBlock", "cut_sandstone");
                def(c, "chimneyBlock", "70%sandstone,30%smooth_sandstone");
            }
            if (type.equals("tower") || type.equals("wall")) def(c, "wall", "50%sandstone,30%smooth_sandstone,20%cut_sandstone");
            if (type.equals("tower")) { def(c, "upperWall", "60%smooth_sandstone,40%white_terracotta"); def(c, "roofGradient", "acacia_planks,jungle_planks"); }
            if (house) def(c, "roofGradient", "acacia_stairs|jungle_stairs|jungle_stairs");
        } else if (snowy) {
            if (house) {
                def(c, "roofGradient", "dark_oak_stairs|dark_oak_stairs|spruce_stairs");
                def(c, "snowRoof", true);
            }
            if (type.equals("tower")) def(c, "roofGradient", "dark_oak_planks,spruce_planks");
        }
        switch (type) {
            case "building" -> {
                int w = num(c, "width", 9), d = num(c, "depth", 7);
                String interior = c.has("interior") && c.get("interior").isJsonPrimitive() ? c.get("interior").getAsString().toLowerCase(Locale.ROOT) : "home";
                boolean public_ = interior.equals("tavern") || interior.equals("shop");
                c.addProperty("archetype", arch);
                def(c, "palette", pal.name());
                archetypeDefaults(c, arch, w, d, seed, public_);
                def(c, "floors", 2);
                // Grand houses get taller storeys.
                if (Math.max(w, d) >= 15 && num(c, "floors", 2) >= 2) def(c, "floorHeight", 5);
                def(c, "floorHeight", 4);
                def(c, "lowerWall", pal.lowerWall());
                def(c, "wall", pal.wall());
                def(c, "frame", pal.frame());
                if (seed % 5 != 0) def(c, "beam", beamTone(str(c, "frame", pal.frame())));
                def(c, "foundation", pal.foundation());
                def(c, "floor", pal.floor());
                def(c, "roof", "gable");
                def(c, "pitch", 2);
                def(c, "kick", 1);
                def(c, "roofProfile", profile(arch, seed));
                def(c, "roofGradient", pal.roofGradient());
                def(c, "roofTrim", pal.roofTrim());
                def(c, "eaveUnderside", true);
                // Big roof (the reference look): wide flared eaves, a lower skirt row, ragged gable edges and brackets
                // under the eaves, on the bigger houses. Small cottages and farmhouses keep a tight roof.
                boolean big = Math.max(w, d) >= 13 || "manor".equals(arch);
                if (big && !"farmhouse".equals(arch)) {
                    def(c, "bigRoof", true);
                    def(c, "overhang", 2);
                    def(c, "eaveBrackets", true);
                }
                def(c, "overhang", 1);
                def(c, "jetty", 1);
                def(c, "jettySides", "front,back");
                // Narrow fronts show their gable to the street (ridge front-to-back).
                def(c, "ridge", w <= d + 2 ? "z" : "auto");
                def(c, "windowFrame", pal.windowFrame());
                def(c, "windowSpacing", 4);
                def(c, "windowParity", seed % 3 == 0 ? 1 : 0);
                def(c, "sill", "spruce_stairs");
                def(c, "shutters", pal.shutters());
                def(c, "door", pal.door());
                def(c, "entrance", "framed");
                def(c, "dormers", Math.max(w, d) >= 13 ? 1 + seed % 2 : Math.max(w, d) >= 9 ? 1 : 0);
                def(c, "finial", pal.finial());
                def(c, "finialPost", !"farmhouse".equals(arch));
                def(c, "finialCap", switch (seed % 4) { case 0 -> "lantern"; case 1 -> "none"; default -> slab(pal.roofTrim()); });
                def(c, "chimney", true);
                def(c, "chimneyBlock", pal.chimney());
                def(c, "plinth", true);
                // Stone ground floors in courses, not speckle: a rough base row and a clean band under the timber.
                def(c, "baseCourse", pal.foundation());
                def(c, "bandCourse", pal.quoins());
                def(c, "pentice", seed % 3 != 0);
                def(c, "doorHood", true);
                def(c, "beamEnds", true);
                def(c, "windowHoods", true);
                def(c, "sparseWindows", true);
                def(c, "gableLanterns", true);
                def(c, "fascia", pal.shutters());
                def(c, "blindShutters", true);
                def(c, "leanTo", (seed / 11) % 5 < 3);
                def(c, "bayWindow", (seed / 13) % 3 != 0);
                boolean oneFloor = num(c, "floors", 2) <= 1;
                def(c, "frontPorch", oneFloor || ("manor".equals(arch) && seed % 2 == 0) || ("cottage".equals(arch) && seed % 3 == 0));
                def(c, "yard", true);
                def(c, "recess", true);
                def(c, "braces", true);
                def(c, "cornerBlock", pal.quoins());
                def(c, "weathering", !desert);
                def(c, "buttresses", d >= 10);
                def(c, "vines", !desert && !snowy);
                def(c, "windowBoxes", !desert && !snowy);
                def(c, "roofMoss", pal != SLATE && OVERGROWN.contains(pal.name()) && !snowy);
                def(c, "overgrowth", OVERGROWN.contains(pal.name()) && !desert && !snowy);
                if (public_) {
                    def(c, "sign", true);
                    def(c, "banners", BANNERS[seed % BANNERS.length]);
                }
                if (interior.equals("shop")) def(c, "awnings", true);
                // Floor plan: one block, or a main block with wings / a corner turret (L, T, U, cross...).
                if (!c.has("wing") && !c.has("wings")) def(c, "plan", plan(arch, w, d, seed));
                calm(c, given, arch, seed, w, d, trixy);
            }
            case "tower" -> {
                int h = num(c, "height", 16);
                def(c, "wall", pal.stoneMix());
                def(c, "foundation", pal.foundation());
                def(c, "upperWall", pal.wall());
                def(c, "upperFrom", Math.max(4, (int) Math.round(h * 0.55)));
                def(c, "frame", pal.frame());
                def(c, "floor", pal.floor());
                def(c, "roof", "cone");
                def(c, "roofGradient", pal.towerRoof());
                def(c, "roofBlock", pal.towerRoof().substring(pal.towerRoof().lastIndexOf(',') + 1));
                def(c, "roofOverhang", 2);
                def(c, "roofSteepness", 2);
                def(c, "window", "glass_pane");
                def(c, "door", pal.door());
            }
            case "roof" -> {
                def(c, "pitch", 2);
                def(c, "kick", 1);
                def(c, "roofGradient", pal.roofGradient());
                def(c, "roofTrim", pal.roofTrim());
                def(c, "eaveUnderside", true);
            }
            case "chimney" -> def(c, "block", pal.chimney());
            case "wall" -> def(c, "wall", pal.stoneMix());
            case "path" -> def(c, "block", desert ? "50%sand,30%smooth_sandstone,20%gravel"
                : "45%cobblestone,25%gravel,15%mossy_cobblestone,15%coarse_dirt");
            case "lamp_post" -> { def(c, "post", pal.finial()); def(c, "light", "lantern"); }
            case "fence" -> def(c, "block", pal.finial());
            case "arch" -> { def(c, "block", "stone_bricks"); def(c, "keystone", "chiseled_stone_bricks"); }
            case "fountain" -> def(c, "block", "stone_bricks");
            case "well" -> { def(c, "block", pal.foundation()); def(c, "post", pal.finial()); }
            case "stall" -> { def(c, "color", BANNERS[seed % BANNERS.length]); def(c, "post", pal.finial()); }
            case "woodpile" -> def(c, "block", pal.frame().replace("stripped_", ""));
            case "bridge" -> { def(c, "block", pal.floor()); def(c, "railing", pal.finial()); def(c, "support", pal.frame()); }
            default -> { }
        }
    }

    /**
     * The calm pass ("calm": true by default): restraint like the reference builders instead of every detail on
     * every house. Only touches settings the AI did not give.
     *  1. Detail budget: one signature feature (turret, square tower, porch, bay window, oriel or balcony) and two
     *     small secondary details; the rest stay off.
     *  2. One focal point: hoods, flower boxes, blind shutters and beam ends on the front only; lights by the door.
     *  3. Massing: the main block plus at most one addition (a wing, a lean-to, or the turret/tower itself).
     *  4. Material cap: all the small timber (window frames, shutters, sills, door, fence posts, fascia) in the
     *     frame's wood; roof trim from a colour already on the roof.
     *  5. Steady rhythm: one glass type, calm roofline (either one cross gable or dormers, never both; no
     *     stepped "mixed" profile; finial only on the front gable, none on wings, dormers or cross gables).
     */
    private static void calm(JsonObject c, java.util.Set<String> given, String arch, int seed, int w, int d, boolean trixy) {
        if (c.has("calm") && c.get("calm").isJsonPrimitive() && !c.get("calm").getAsBoolean()) return;
        int floors = num(c, "floors", 2);
        boolean stone = !"none".equals(str(c, "lowerWall", "none")) && floors >= 2;
        boolean framed = !"none".equals(str(c, "frame", "none"));
        // 1 + 3: signature feature and massing.
        List<String> sigs = new java.util.ArrayList<>(switch (arch == null ? "" : arch) {
            case "cottage" -> List.of("bay", "porch", "turret", "tower", "deck", "oriel", "balcony");
            case "manor" -> List.of("tower", "deck", "tower", "deck", "porch", "balcony", "turret");
            case "farmhouse" -> List.of("porch", "porch", "bay", "none");
            default -> List.of("balcony", "oriel", "tower", "turret", "deck", "bay", "deck");
        });
        if (floors < 2) sigs.removeIf(s -> s.equals("oriel") || s.equals("balcony") || s.equals("tower"));
        if (w < 9) sigs.removeIf(s -> s.equals("turret") || s.equals("tower") || s.equals("balcony"));
        if (w < 11 || floors < 2) sigs.removeIf(s -> s.equals("deck"));
        if (sigs.isEmpty()) sigs.add("porch");
        String sig = sigs.get((seed / 29) % sigs.size());
        // The AI's own picks count: whatever it switched on is the signature.
        if (given.contains("plan")) {
            String p = str(c, "plan", "simple").toLowerCase(Locale.ROOT);
            if (p.contains("tower")) sig = "tower"; else if (p.contains("turret")) sig = "turret";
        }
        for (String[] k : new String[][]{{"frontPorch", "porch"}, {"bayWindow", "bay"}, {"oriel", "oriel"}, {"frontBalcony", "balcony"}, {"frontDeck", "deck"}}) {
            if (given.contains(k[0]) && c.get(k[0]).isJsonPrimitive() && c.get(k[0]).getAsBoolean()) sig = k[1];
        }
        set(c, given, "frontPorch", sig.equals("porch"));
        set(c, given, "bayWindow", sig.equals("bay"));
        set(c, given, "oriel", sig.equals("oriel"));
        set(c, given, "frontBalcony", sig.equals("balcony"));
        set(c, given, "frontDeck", sig.equals("deck"));
        if (!given.contains("plan") && !given.contains("wing") && !given.contains("wings")) {
            String plan;
            if (sig.equals("turret") || sig.equals("tower")) plan = sig;
            else {
                String[] adds = switch (arch == null ? "" : arch) {
                    case "cottage" -> new String[]{"L", "L", "T", "simple", "lean"};
                    case "manor" -> new String[]{"L", "T", "simple", "L"};
                    case "farmhouse" -> new String[]{"L", "lean", "simple"};
                    default -> new String[]{"simple", "simple", "T", "L", "lean"};
                };
                plan = adds[(seed / 31) % adds.length];
            }
            if (Math.max(w, d) < 7) plan = "simple";
            set(c, given, "leanTo", plan.equals("lean"));
            c.addProperty("plan", plan.equals("lean") ? "simple" : plan);
        } else {
            set(c, given, "leanTo", false);
        }
        // 1: two small secondary details from the ones that suit this house.
        List<String> pool = new java.util.ArrayList<>(List.of("doorHood", "windowHoods", "gableLanterns", "windowBoxes", "vines"));
        if (stone) { pool.add("pentice"); pool.add("blindShutters"); }
        if (framed) pool.add("beamEnds");
        if (sig.equals("porch") || sig.equals("balcony") || sig.equals("deck")) pool.remove("doorHood");
        java.util.Collections.rotate(pool, seed % pool.size());
        List<String> keep = List.of(pool.get(0), pool.get(Math.min(pool.size() - 1, 1 + (seed / 37) % Math.max(1, pool.size() - 1))));
        for (String k : List.of("pentice", "doorHood", "beamEnds", "windowHoods", "gableLanterns", "blindShutters", "windowBoxes", "vines", "buttresses")) {
            set(c, given, k, keep.contains(k));
        }
        // 2: one focal face, lights only by the door, a quieter yard.
        def(c, "focal", "front");
        def(c, "outdoorLights", "door");
        def(c, "quietYard", true);
        // 5: calm roofline and one kind of glass.
        def(c, "finialAt", "front");
        if (!given.contains("finialCap") && "lantern".equals(str(c, "finialCap", ""))) c.addProperty("finialCap", slab(str(c, "roofTrim", "spruce_stairs")));
        if (!given.contains("roofProfile") && "mixed".equals(str(c, "roofProfile", ""))) c.addProperty("roofProfile", "classic");
        if (!given.contains("crossGables") && !given.contains("dormers")) {
            boolean gables = num(c, "crossGables", 0) > 0 && (num(c, "dormers", 0) == 0 || (seed / 41) % 5 < 3);
            c.addProperty("crossGables", gables ? 1 : 0);
            c.addProperty("dormers", gables ? 0 : Math.min(num(c, "dormers", 0), Math.max(w, d) >= 15 ? 2 : 1));
        }
        if (!given.contains("orielGlass")) c.addProperty("orielGlass", str(c, "window", "glass_pane"));
        // 4: material cap (the TrixyBlox look keeps its deliberate mix).
        if (trixy) return;
        String wood = species(str(c, "frame", ""));
        if (wood == null) wood = species(str(c, "floor", "spruce_planks"));
        if (wood == null) return;
        set(c, given, "windowFrame", wood + "_planks");
        set(c, given, "shutters", wood + "_trapdoor");
        if (!"none".equals(str(c, "fascia", "x"))) set(c, given, "fascia", wood + "_trapdoor");
        set(c, given, "sill", wood + "_stairs");
        set(c, given, "door", wood + "_door");
        if (!"none".equals(str(c, "finial", "x"))) set(c, given, "finial", wood + "_fence");
        String roof0 = str(c, "roofGradient", "").split("\\|")[0].replaceAll("^\\d+%", "").split(",")[0];
        String roofWood = species(roof0);
        set(c, given, "roofTrim", roofWood != null && roof0.endsWith("_stairs") ? roof0 : wood + "_stairs");
    }

    private static final String[] SPECIES = {"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped"};

    /** Wood species of a block ("stripped_spruce_log" -> "spruce"), or null. */
    private static String species(String block) {
        String b = block == null ? "" : block.replaceAll("^\\d+%", "").replace("stripped_", "").replaceAll("\\[.*", "");
        for (String s : SPECIES) if (b.startsWith(s + "_")) return s;
        return null;
    }

    private static void set(JsonObject c, java.util.Set<String> given, String key, boolean v) {
        if (!given.contains(key)) c.addProperty(key, v);
    }

    private static void set(JsonObject c, java.util.Set<String> given, String key, String v) {
        if (!given.contains(key)) c.addProperty(key, v);
    }

    /** A floor plan that suits the house type (varied by the building's seed). */
    static String plan(String arch, int w, int d, int seed) {
        String[] opts = switch (arch) {
            case "cottage" -> new String[]{"L", "L", "T", "U", "cross", "turret", "L+turret", "stepped"};
            case "manor" -> new String[]{"U", "U", "cross", "T", "L+turret", "turret", "stepped"};
            case "farmhouse" -> new String[]{"L", "L", "stepped", "T", "simple"};
            default -> new String[]{"simple", "turret", "L", "stepped", "T", "L+turret"}; // townhouse
        };
        String p = opts[(seed / 13) % opts.length];
        if (p.equals("U") && w < 13) p = "L";
        if (Math.max(w, d) < 7) p = "simple";
        return p;
    }

    /** What makes each kind of house different. Runs before the shared medieval defaults, so it wins over them. */
    private static void archetypeDefaults(JsonObject c, String arch, int w, int d, int seed, boolean public_) {
        switch (arch) {
            case "cottage" -> { // wide, cosy, jettied all round, big grey roof with cross gables and a bay window
                boolean alongX = w >= d;
                if ((seed / 5) % 5 < 2 && !c.has("floors")) { // single storey under a big roof (like most reference cottages)
                    def(c, "floors", 1);
                    def(c, "floorHeight", 5);
                    def(c, "lowerWall", "none");
                    def(c, "jetty", 0);
                    def(c, "oriel", false);
                }
                def(c, "floors", 2);
                def(c, "jetty", 1);
                def(c, "jettySides", "front,left,right");
                def(c, "ridge", alongX ? "x" : "z");
                def(c, "crossGables", alongX ? (w >= 15 ? 2 : 1) : 1);
                def(c, "crossGableWidth", (alongX ? w : d) >= 11 ? 7 : 5);
                def(c, "oriel", true);
                def(c, "orielWidth", w >= 11 ? 5 : 3);
                def(c, "orielGlass", "yellow_stained_glass_pane");
                def(c, "dormers", alongX ? 1 : 0);
                def(c, "buttresses", false);
                def(c, "roofMoss", true);
                def(c, "windowParity", 1);
            }
            case "manor" -> { // tall, formal: hip roof with a big central gable, tall floors, no jetty
                def(c, "floors", 2);
                boolean tall = num(c, "floors", 2) <= 2;          // three-storey manors stay a little lower per floor
                def(c, "floorHeight", tall ? 6 : 5);
                def(c, "windowHeight", tall ? 3 : 2);
                def(c, "roof", "hip");
                def(c, "jetty", 0);
                def(c, "crossGables", 1);
                def(c, "crossGableWidth", Math.max(w, d) >= 15 ? 7 : 5);
                def(c, "windowSpacing", 4);
                def(c, "banners", BANNERS[(seed / 3) % BANNERS.length]);
                def(c, "dormers", 0);
            }
            case "farmhouse" -> { // low and long: one timber storey on a stone plinth, steep thatch hip roof
                def(c, "floors", 1);
                def(c, "floorHeight", 5);
                def(c, "lowerWall", "none");
                def(c, "roof", "hip");
                def(c, "roofGradient", "spruce_stairs|bamboo_mosaic_stairs|bamboo_mosaic_stairs|85%bamboo_mosaic_stairs,15%birch_stairs");
                def(c, "roofTrim", "spruce_stairs");
                def(c, "jetty", 0);
                def(c, "dormers", 0);
                def(c, "buttresses", false);
                def(c, "windowParity", 0);
            }
            default -> { // townhouse: narrow, tall, gable to the street, jettied front and back
                def(c, "floors", 2 + seed % 2);
                def(c, "jettySides", "front,back");
            }
        }
    }

    /**
     * The TrixyBlox look (from his USW2 medieval village): 45-degree or rounded roofs in thick striped oak/jungle/
     * spruce rows with a toothed ridge and a mushroom-block eave lip, log-cabin corners with the log ends stacked
     * crosswise, bark-wood frames with spruce beams, tuff and andesite stone, grey plaster or dark plank walls,
     * finial posts capped with andesite, a bit of greenery.
     */
    private static void trixyDefaults(JsonObject c, String arch, int seed) {
        def(c, "roofProfile", (seed / 3) % 3 == 0 ? "classic" : "rounded");
        def(c, "roofStripes", true);
        def(c, "roofGradient", TRIXY.roofGradient());
        def(c, "roofTrim", TRIXY.roofTrim());
        def(c, "ridgeCap", "spruce_slab");
        def(c, "thickRoof", true);
        def(c, "eaveBlock", "brown_mushroom_block[up=false,down=false,north=true,south=true,east=true,west=true]");
        def(c, "eaveUnderside", false);
        def(c, "fascia", "none");
        def(c, "cornerStack", true);
        def(c, "beam", "spruce_log");
        def(c, "finial", "spruce_fence");
        def(c, "finialPost", true);
        def(c, "finialCap", "polished_andesite");
        def(c, "roofMoss", false);
        def(c, "overgrowth", true);
        def(c, "kick", 0);
        if ((seed / 5) % 2 == 0) def(c, "wall", "60%spruce_planks,40%dark_oak_planks");
        if ((seed / 7) % 3 == 0) def(c, "frame", "birch_wood");
    }

    /** Roof profile for a house type, varied by seed so neighbours differ (not every roof steep). */
    static String profile(String arch, int seed) {
        String[] opts = switch (arch == null ? "" : arch) {
            case "cottage" -> new String[]{"classic", "flared", "rounded", "steep", "classic"};
            case "manor" -> new String[]{"mixed", "classic", "steep", "rounded"};
            case "farmhouse" -> new String[]{"flared", "classic", "steep", "classic"};
            default -> new String[]{"steep", "rounded", "mixed", "steep", "flared"};
        };
        return opts[(seed / 17) % opts.length];
    }

    /** A second timber tone for horizontal beams: the bark log for stripped posts, and the stripped log otherwise. */
    private static String beamTone(String frame) {
        if (frame == null || frame.contains("%") || frame.contains(",") || frame.contains("[")) return frame;
        if (frame.startsWith("stripped_")) return frame.substring(9);
        if (frame.endsWith("_log") || frame.endsWith("_wood")) return "stripped_" + frame;
        return frame;
    }

    /** Bottom slab of a stair block ("dark_oak_stairs" -> "dark_oak_slab"); mixes use their first part. */
    private static String slab(String stairs) {
        String b = stairs.contains(",") ? stairs.split(",")[0].replaceAll("^\\d+%", "") : stairs;
        b = b.replaceAll("\\[.*", "");
        if (b.endsWith("_stairs")) return b.substring(0, b.length() - 7) + "_slab";
        return "none";
    }

    private static void def(JsonObject c, String key, String value) {
        if (!c.has(key) || c.get(key).isJsonNull()) c.addProperty(key, value);
    }

    private static void def(JsonObject c, String key, int value) {
        if (!c.has(key) || c.get(key).isJsonNull()) c.addProperty(key, value);
    }

    private static void def(JsonObject c, String key, boolean value) {
        if (!c.has(key) || c.get(key).isJsonNull()) c.addProperty(key, value);
    }

    private static String str(JsonObject c, String key, String def) {
        return c.has(key) && c.get(key).isJsonPrimitive() ? c.get(key).getAsString() : def;
    }

    private static int num(JsonObject c, String key, int def) {
        try {
            return c.has(key) ? (int) Math.round(c.get(key).getAsDouble()) : def;
        } catch (Exception e) {
            return def;
        }
    }
}
