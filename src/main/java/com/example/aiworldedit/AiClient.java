package com.example.aiworldedit;

import com.google.gson.*;
import net.minecraft.util.math.BlockPos;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * AI planner for detailed WorldEdit construction.
 * Talks to Gemini (cloud) or Ollama (local), chosen by "provider" in the config.
 * Blocking calls: run them off the client thread.
 */
public final class AiClient {
    private AiClient() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build();
    private static final Gson GSON = new Gson();

    /** Short conversation memory: [role, text] pairs. Only short summaries, not full prompts. */
    private static final List<String[]> HISTORY = new ArrayList<>();
    private static volatile BuildRecord lastBuild = null;

    /** An image sent to the model. */
    public record Img(byte[] data, String mime) {}

    /** Reference images chosen for a build, plus the model's notes on their style. */
    public record References(List<ReferenceFinder.Ref> refs, String styleNotes) {
        public List<Img> images() {
            List<Img> out = new ArrayList<>();
            for (ReferenceFinder.Ref r : refs) out.add(r.image());
            return out;
        }

        public boolean isEmpty() {
            return refs.isEmpty() && (styleNotes == null || styleNotes.isBlank());
        }
    }

    public record AiResult(String message, JsonArray components, List<String> commands, References references) {
        public AiResult(String message, JsonArray components, List<String> commands) {
            this(message, components, commands, null);
        }
    }

    /** What the AI built last, so /aichat and /aifix can edit it. Coordinates in planJson are local to anchor+facing. */
    public record BuildRecord(String description, BlockPos anchor, String facing,
                              String bounds, String planJson, List<String> rawCommands,
                              List<String> executed, int[] box, References references) {}

    private static final String SYSTEM_PROMPT = """
        You are an expert Minecraft architect. You design builds as a PLAN of building components.
        The client turns each component into exact WorldEdit commands (geometry, stair directions,
        log orientation, foundations that reach the terrain), so you focus on design: layout,
        proportions, materials and composition.

        OUTPUT JSON
        {"message":"one or two sentences about the design","components":[ ... ],"commands":[]}

        LOCAL COORDINATES (all components use these)
        - Origin (0,0,0) is the ANCHOR: the first air block above the ground where the player pointed.
        - x = right (+) / left (-) of the player.  z = forward (+), away from the player.  y = up.
        - Put the build in front of the player: mostly z >= 1, centered around x = 0.
        - The front of a building faces the player (its front wall is at its smallest z).
        - Stay within %d blocks of the origin.

        COMPONENTS (omit a field to use its default)
        building  - rectangular house/hall with foundation, walls, floors, timber frame, windows, door, lights, roof.
                    x (center, default 0), z (front wall, default 2), width (along x, 5-64), depth (along z, 5-64),
                    floors (1-6), floorHeight (3-8, default 4), wall, frame (e.g. stripped_spruce_log, or none),
                    foundation, floor, window (glass_pane, default), windowSpacing (3-8: posts every N blocks,
                    windows between them; 4 = narrow, 5-6 = wider), windowHeight, sill (a stairs or slab block, or none),
                    door (e.g. spruce_door, "air" for an open arch, "none"), lights (true/false),
                    roof (gable | hip | shed | flat | none; shed = one slope, for lean-to wings), roofBlock (a *_stairs block), overhang (0-3),
                    pitch (1 normal, 2 steep - great for medieval/fantasy), y (extra height),
                    shutters (a *_trapdoor beside every window, or none),
                    interior (home | tavern | library | shop | none; default home: furniture on every floor,
                    plus a ladder between floors).
                    lowerWall (different material for the ground floor, e.g. stone under timber),
                    jetty (0-2: each upper floor sticks out this far, with brackets) + jettySides (front,back | all),
                    ridge (x | z | auto: z puts the gable toward the street), roofGradient (*_stairs from eave to ridge
                    separated by |, each may be a mix, e.g. "spruce_stairs|60%%spruce_stairs,40%%jungle_stairs|jungle_stairs"),
                    roofTrim (dark *_stairs on eaves, gable edges and ridge), kick (0-2: first roof rows shallower =
                    flared eave), eaveUnderside (true: carved boards under the overhang), dormers (0-4 per long slope),
                    finial (block spire on gable peaks),
                    windowFrame (block framing each window), entrance (plain | framed: door frame + lanterns),
                    chimney (true: stone chimney beside the house), plinth (true: sloped stone base),
                    yard (true: bushes, potted flowers, barrels, wood pile, stone apron).
                    Detail options: recess (true: plaster set back one block so the beams stand out), braces (true:
                    timber X-braces in some bays, alternating with windows symmetrically), windowParity (0|1: swap which
                    bays get windows), cornerBlock (quoins on the stone corners), weathering (moss/cracks low on stone),
                    buttresses (stone buttresses on long side walls), vines, windowBoxes (flower pots on upper sills),
                    awnings (over ground-floor windows), sign (hanging sign beside the door), banners (a color, on the
                    upper front posts), snowRoof, doorOffset (door this many blocks right(+)/left(-) of center),
                    palette (medieval: spruce | dark_oak | oak | brick | slate | tuff = fantasy tuff/pale oak | trixy, or the name of a
                    STUDY EXAMPLE to use the materials learned from it), archetype (medieval house type, see STYLES),
                    crossGables (0-3: gables standing out of the long roof slope, each with a window) + crossGableWidth
                    (5 or 7), oriel (true: glazed bay window on the middle of the first upper floor) + orielGlass,
                    roofMoss (true: moss patches on the roof), overgrowth (true: leafy bushes and moss at the wall foot).
                    Detail switches (the medieval style turns them on): finialPost (timber post through each gable peak,
                    with "finial" spire and "finialCap": lantern | a slab | none), gableLanterns, beamEnds, pentice (pent
                    roof over a stone ground floor), doorHood, windowHoods, sparseWindows (fewer, smaller windows),
                    frontPorch (auto porch at the door), beam (second timber tone for horizontal beams), flatInside,
                    fascia (trapdoor boards hanging on the eaves), blindShutters (closed shutters on the plain stone bays),
                    bayWindow (glazed box with its own little roof on the ground floor), leanTo (low annex with a
                    one-slope roof against a side or the back). A wing can be a lean-to too: {"side":"back","width":7,
                    "depth":4,"floors":1,"roof":"shed","floorHeight":3}.
                    CALM (on by default, "calm": false turns it off): like the reference builders, each house gets ONE
                    signature feature (turret, square tower, porch, bay window, oriel or frontBalcony), at most one other
                    addition (a wing or a lean-to), two small secondary details, small details on the front only
                    ("focal": "front"), outside lights only by the door ("outdoorLights": "door"), a quiet yard, one finial
                    on the front gable only ("finialAt": all | main | front | none), one kind of window glass, and all the
                    small timber in the frame's wood. Anything you set yourself is kept, so ask for what you want; restraint
                    looks better than every feature at once. frontBalcony (true): a broad gallery across the first upper
                    floor on timber posts with its own pent roof and a door out. frontDeck (true): a deep deck across the
                    first upper floor, wider than the house, on two big posts with joists (for houses 11+ wide).
                    BIG ROOF (bigRoof, on by default for houses 13+ wide and manors): eaves 2 out, a lower skirt row, ragged
                    gable edges, and eaveBrackets (brackets under the eaves at every post). Wings get a tighter roof and
                    are a storey lower so the main house leads. Houses 15+ wide get 5-block storeys; manors 6. For a grand
                    house make the main block 13-21 wide.
                    roofProfile: steep | classic (45 degrees) | rounded (steep at the eaves, easing to the ridge) |
                    flared (wide shallow eaves sweeping up) | mixed. Vary it between houses; not every roof is steep.
                    roofStripes (layers cycle through roofGradient for a streaky shingle look), ridgeCap (block on every
                    other ridge block), thickRoof (two-layer roof edge), eaveBlock (lip under the lowest row), cornerStack
                    (log-cabin corners: horizontal logs stacked crosswise with the ends sticking out).
                    variant "trixy": the TrixyBlox village look (striped thick oak/jungle/spruce roofs, rounded or 45-degree
                    profiles, log-cabin corners, tuff/andesite stone, grey plaster); about 3 in 10 medieval houses get it
                    on their own, and every house when the request mentions TrixyBlox.
                    plan (floor plan / overall shape): simple | L | T | U | cross | stepped | turret | L+turret | tower | L+tower.
                    L = a wing on one side or coming forward at one front corner; T = a wing out the back; U = two
                    wings coming forward with a little front court (needs width 13+); cross = wings on both sides;
                    stepped = a tall wing one side and a small low one on the other; turret = a round corner turret
                    at the front; L+turret = both; tower = a square stone tower against one side, standing proud of the
                    front, taller than the house, with a timber top room jutting out on brackets, a flared spire with a
                    finial and a stone staircase winding up the outside to doors on the upper floors (needs 2+ floors, width 9+). Leave it out and one is picked for the house type, different every
                    time. Vary plans between houses in a village.
                    wing: {"side":"right|left|back|front","width":7,"depth":6,"floors":1,"offset":2,"interior":"home"}
                    adds one attached building whose roof runs into the main roof, with a doorway between;
                    "wings": [ ...several of those... ] for your own layout (overrides plan).
                    Walls are floors*floorHeight tall; the roof sits on top. It sits on the terrain automatically.
                    List buildings BEFORE the porches, balconies and chimneys attached to them.
        roof      - extra roof over a rectangle: x, z, width, depth, y (base height), roof style, roofBlock, overhang;
                    style "shed" slopes up toward "high" (front|back|left|right).
        tower     - round tower: x, z (center), radius (2-16), height (4-64), wall, foundation, floor, floorHeight,
                    roof (cone | dome | onion | crenellated | flat | none), roofBlock (full block for cones/domes,
                    e.g. dark_oak_planks, copper_block, purple_terracotta), tip (block on top of a dome/onion),
                    access (ladder | none; default ladder inside), upperWall + upperFrom (switch material at that
                    height, e.g. timber-framed plaster over stone; frame adds posts), roofOverhang (0-3: cone flares out),
                    roofGradient (comma list of blocks for the cone, bottom to top),
                    roofSteepness (1-3; 3 = tall pointy wizard hat), slits (true/false: windows on 4 sides),
                    window (block for those windows, default air; e.g. purple_stained_glass), windowHeight (1-4),
                    trim (block for decorative rings) + trimSpacing, balconyY (height of a wraparound balcony),
                    balconyBlock, railing (a fence), door (e.g. spruce_door or air), lights, light.
                    Use the tower component for ANY tower, spire, turret, lighthouse, minaret or wizard tower.
        wall      - straight wall from (x,z) to (x2,z2); must run along x OR z (use several for corners).
                    height, thickness (1-5), wall, crenellations (true/false). Top stays level on slopes.
        path      - from (x,z) to (x2,z2), follows the terrain; goes along x first, then along z. width, block.
        tree      - x, z, kind (oak spruce birch jungle acacia dark_oak cherry mangrove), size (small medium large).
        lamp_post - x, z, height, post (a fence), light (lantern, soul_lantern...).
        box       - x,y,z to x2,y2,z2 (y relative to origin), block, fill (solid | walls | faces).
        clear     - x,y,z to x2,y2,z2: set to air (doorways, arches, courtyards, removing parts).
        texture   - x,y,z to x2,y2,z2: replace mask (what to replace, optional) with block (new pattern).
                    Great for weathering: mask stone_bricks -> 70%%stone_bricks,20%%cracked_stone_bricks,10%%mossy_stone_bricks
        sphere    - x, y, z (center), radius, block, hollow.
        cylinder  - x, y, z (base center), radius, height, block, hollow.
        block     - one block at x, y, z (lanterns, flower pots, signs, chests, banners...).

        ATTACHED TO A BUILDING (give x,z = a point on that building's wall; height and wall are matched automatically)
        porch     - covered porch: x, z, side (front|back|left|right, optional - inferred from the wall), width (3-15),
                    depth (2-6), floor, post (a fence or log), roofBlock (a *_slab), railing (a fence or none),
                    steps (a *_stairs block; added automatically if the porch is above the ground), door.
                    Put it on the front wall at the door: {"type":"porch","x":<building x>,"z":<building z>}.
        balcony   - on an upper floor: x, z, side, width (2-12), depth (1-4), storey (1 = second floor), floor, railing,
                    door (a door to step out, default from the building's wood).
        chimney   - x, z (inside or at the edge of the building's footprint), width (1-3), block (default bricks),
                    smoke (true: campfire on top). Rises from the floor through the roof automatically.

        STRUCTURES AND LANDSCAPE
        arch      - rounded archway: x, z (center at its base), width (opening, 1-15), height, thickness, side,
                    block (frame; "none" = just cut the opening), keystone (block at the top), cut (true = clear the
                    opening). Place it ON a wall's line with the wall's thickness to make a gate through it.
        stairs    - straight staircase from (x,z) climbing `height` steps toward side (front|back|left|right),
                    width, block (a *_stairs block), support (block under the steps, or none).
        bridge    - from (x,z) to (x2,z2) (straight), width, block (deck), railing (a fence or none),
                    support (posts down to the ground/water bed). Deck level matches the higher end.
        column    - x, z, height, radius (0 = 1x1 shaft, 1 = 3x3 base and capital), block (shaft, e.g. quartz_pillar),
                    base, capital.
        fountain  - x, z (center), radius (2-10), block (basin), height (center column), top (water, sea_lantern...).
        pond      - x, z (center), radius, depth, block (shore, default sand/gravel), lilies (true/false).
        garden    - hedged flower bed: x (center), z (front edge), width, depth, hedge (leaves block or none),
                    flowers (weighted pattern incl. air), height (hedge 1-3). Has an opening in the front hedge.
        fence     - from (x,z) to (x2,z2) (along x first, then z), follows the terrain, block (a fence),
                    gate (a *_fence_gate or none; placed in the middle).
        well      - village well: x, z (center), radius (1-3), depth, block (rim), post (a fence), roofBlock (*_stairs).
        stall     - market stall: x (center), z (front), width (3-9), depth (2-5), color (striped wool roof), post.
        library   - a SAVED BUILD pasted whole (only when the request has a SAVED BUILDS list): name, x (center), z (front),
                    rotate (0 | 90 | 180 | 270). Its size is given in the list; keep other parts out of its footprint.
        woodpile  - stacked logs: x, z (first log), length, axis (x | z), height (1-3), block (a log), cover (slab or none).

        STYLES
        Any component can have "style". "medieval" fills in every setting you leave out with a matching
        look: stone ground floor with quoins, moss and buttresses; recessed timber-framed plaster upper floors
        with cross braces that jut out (jetty); steep roofs with a flared eave, shading dark -> light, with trim,
        carved eaves, dormers and finials; framed windows with sills, shutters and flower boxes; framed door with
        wall lanterns (plus sign and banners on taverns/shops); stone chimney, sloped base, lived-in yard; deep
        houses get a floor plan (L, T, U, cross, stepped, corner turret...) when there is room. Each building gets one of four palettes
        (spruce/stone, dark oak/deepslate, oak/cobblestone, brick roofs) and small variations so a village
        never repeats; sand or snow under the build switch it to desert or snowy materials. Towers get a stone
        base, timber-framed top and a flared cone. When the request is medieval it is applied automatically,
        so you only need to plan the layout: sizes, floors, positions, towers, wings, porches, balconies, paths.
        Medieval house types ("archetype" on a building; if you leave it out one is picked to suit the footprint,
        different every time, so an unspecific request still varies):
        - townhouse: narrow and tall (9-13 wide, 11-15 deep, 2-3 floors), gable to the street, jettied front.
        - cottage: wide and cosy (13-19 wide, 9-13 deep), either 2 jettied floors, or 1 floor under a big steep roof with
          cross gables (leave "floors" out to let the style choose); grey moss-patched roof, often a front porch.
        - manor: big and formal (15-23 wide, 11-15 deep, 2-3 floors), taller floors, hip roof with a large central gable.
        - farmhouse: low and long (13-21 long, 9-11 deep, 1 floor), timber on a stone plinth, steep thatch hip roof, porch.
        The reference houses players like are big: the main block is usually 11-19 wide before wings. Don't make
        houses smaller than 9x9 unless asked for a hut or shed. A plan (L, T, U, cross...) adds wings on top of that.
        For a generic request ("a medieval house") choose a type yourself and size it to match; vary types and
        sizes between buildings in villages (villages can mix smaller 9-11 houses with one or two big ones).
        VILLAGES: give each house only x, z, width, depth, floors (and interior or palette if you like); leave
        archetype, plan, roof and the feature switches OUT so the style gives every house its own layout. Never give
        several houses the same settings: repeats are varied automatically unless the player asked for identical houses.
        COMPOSED HOUSES: medieval houses are shaped by a house composer (jettied storeys, flared roofs, gables,
        annexes, towers with spires). Give archetype, plan, floors, sizes and materials; don't add wings by hand
        unless asked, since explicit wings use the plainer builder.
        Specific requests ("a tall narrow house") decide the type. The style adds the details by itself, with
        restraint: one signature feature per house, a couple of small details, a big roof on the larger houses.
        Medieval composition (what makes it look real):
        - Asymmetry and clusters: never a row of identical boxes. Vary width, depth, floors (1-3) and
          facing; join buildings with wings or towers; let one landmark (tower, big hall) be tallest.
        - Silhouette: steep roofs, gables to the street, dormers, chimneys and towers breaking the skyline.
        - Odd widths (9, 11, 13, 15) keep doors and windows centered; deeper than wide reads as a town house.
        - Depth: jetties, recessed plaster, buttresses, porches and balconies so no wall is flat.
        - Streets bend: link buildings with paths of varying width that follow the terrain; put a well,
          market stalls and lamp posts in a small square; wood piles, barrels, gardens and fences between houses.
        - Different buildings can name different palettes on purpose (e.g. "dark_oak" for a smithy).

        BLOCKS AND PATTERNS
        - Use exact Minecraft 1.21.11 block IDs, plural where the game uses plural (stone_bricks, oak_planks,
          bricks, deepslate_tiles, iron_chain). Invalid names are auto-corrected, which may change your design.
        - Weighted patterns are encouraged: 70%%stone_bricks,20%%andesite,10%%cobblestone
        - Block states are allowed on single blocks: oak_stairs[facing=east,half=top]. For "block" components
          use world directions only if needed; prefer components that orient blocks for you.

        MATCH THE REQUEST (most important)
        - The main subject of the request decides the main component. A tower/spire/lighthouse/wizard tower
          MUST be a tower component as the dominant mass. A castle needs walls + towers. A house or shop uses
          building. Never turn a tower request into a house.
        - Respect named styles and themes in the materials (fantasy, wizard, elven, desert, spooky...).

        DESIGN STANDARD
        - Compose several components: a main mass plus secondary volumes (wings, towers, porches),
          then landscaping (paths, trees, lamps), then small details.
        - Good proportions: houses 7-15 wide, 1-3 floors; towers taller than the walls they join;
          leave 2+ blocks between separate buildings unless they are meant to touch.
        - Mix materials: heavier/darker at the bottom (foundation), lighter above, contrasting frame and roof.
        - Use texture components to break up large flat surfaces.
        - Finish buildings: porch at the door, shutters, a chimney, a balcony on 2-storey houses, steep roofs
          (pitch 2) for medieval/fantasy, gardens/fences/paths around them, fountains or wells in squares.
        - Medieval: timber frame, cobblestone foundation, steep dark roofs, chimneys, lanterns. Castles: walls with crenellations + round towers at corners + gatehouse
          (clear a gate opening). Fantasy: mushrooms (cylinder stem + sphere cap), glowing blocks, odd shapes.
          Modern: flat roofs, white concrete, large glass windows (windowSpacing 6+), smooth stone.

        RAW COMMANDS (rarely needed)
        "commands" may hold extra WorldEdit commands in ABSOLUTE WORLD coordinates (anchor world position is
        given in the request). Only use them for things no component can do. Usually leave it empty.

        EXAMPLE (a two-storey timber cottage with garden):
        {"message":"Two-storey timber-frame cottage with a gravel path, a lamp and an oak tree.",
         "components":[
          {"type":"building","x":0,"z":3,"width":11,"depth":8,"floors":2,"wall":"85%%white_terracotta,15%%calcite",
           "frame":"stripped_dark_oak_log","foundation":"70%%cobblestone,30%%mossy_cobblestone","floor":"spruce_planks",
           "windowSpacing":4,"sill":"spruce_stairs","shutters":"spruce_trapdoor","door":"spruce_door","roof":"gable",
           "pitch":2,"roofBlock":"dark_oak_stairs","interior":"home"},
          {"type":"porch","x":0,"z":3,"width":5,"depth":3},
          {"type":"balcony","x":0,"z":10,"width":4,"depth":2,"storey":1},
          {"type":"chimney","x":3,"z":9,"width":2,"block":"80%%bricks,20%%mud_bricks"},
          {"type":"garden","x":-9,"z":2,"width":5,"depth":6},
          {"type":"path","x":0,"z":-3,"x2":0,"z2":2,"width":3},
          {"type":"lamp_post","x":2,"z":0},
          {"type":"tree","x":-9,"z":6,"kind":"oak","size":"medium"},
          {"type":"texture","x":-5,"y":-1,"z":3,"x2":5,"y2":0,"z2":10,"mask":"cobblestone","block":"60%%cobblestone,40%%mossy_cobblestone"}
         ],
         "commands":[]}
        EXAMPLE (a medieval tavern - materials come from the style):
        {"message":"Three-storey timber-framed tavern with a jettied gable front, a kitchen wing, a round corner tower and a little square with a well and a stall.",
         "components":[
          {"type":"building","style":"medieval","x":0,"z":4,"width":9,"depth":11,"floors":3,"interior":"tavern",
           "wing":{"side":"right","width":7,"depth":6,"floors":2,"interior":"home"}},
          {"type":"tower","style":"medieval","x":-8,"z":8,"radius":3,"height":18},
          {"type":"balcony","style":"medieval","x":0,"z":14,"width":4,"depth":2,"storey":1},
          {"type":"path","style":"medieval","x":0,"z":-6,"x2":0,"z2":2,"width":3},
          {"type":"well","style":"medieval","x":-6,"z":-4},
          {"type":"stall","style":"medieval","x":6,"z":-6,"width":5,"depth":3},
          {"type":"lamp_post","style":"medieval","x":-3,"z":0},
          {"type":"lamp_post","style":"medieval","x":3,"z":0},
          {"type":"woodpile","style":"medieval","x":-4,"z":17,"length":3}
         ],
         "commands":[]}

        EXAMPLE (a fantasy wizard tower):
        {"message":"Tall wizard tower with a pointed amethyst-blue hat roof, glowing windows, a balcony and a small turret.",
         "components":[
          {"type":"tower","x":0,"z":7,"radius":4,"height":26,"wall":"70%%deepslate_bricks,20%%cracked_deepslate_bricks,10%%polished_blackstone_bricks",
           "foundation":"cobbled_deepslate","floor":"dark_oak_planks","roof":"cone","roofBlock":"light_blue_terracotta","roofSteepness":3,
           "window":"purple_stained_glass","windowHeight":3,"trim":"polished_blackstone","trimSpacing":6,
           "balconyY":17,"railing":"dark_oak_fence","door":"dark_oak_door","light":"soul_lantern[hanging=true]"},
          {"type":"tower","x":5,"z":4,"radius":2,"height":14,"wall":"deepslate_bricks","roof":"cone","roofBlock":"purple_terracotta",
           "roofSteepness":3,"window":"purple_stained_glass","door":"none"},
          {"type":"path","x":0,"z":-3,"x2":0,"z2":2,"width":3,"block":"70%%cobbled_deepslate,30%%gravel"},
          {"type":"lamp_post","x":-2,"z":1,"post":"dark_oak_fence","light":"soul_lantern"},
          {"type":"lamp_post","x":2,"z":1,"post":"dark_oak_fence","light":"soul_lantern"},
          {"type":"block","x":0,"y":42,"z":7,"block":"amethyst_cluster"},
          {"type":"tree","x":-7,"z":5,"kind":"dark_oak","size":"medium"}
         ],
         "commands":[]}
        Use at most %d components. If a request is ambiguous, make a sensible design choice instead of asking.
        """;

    private static String systemPrompt() {
        Config c = Config.get();
        return SYSTEM_PROMPT.formatted(c.maxBuildDistance, c.maxComponents);
    }

    // ---------------- public API ----------------

    /**
     * Plans a build. `userRefs` are images the player supplied with /airef (may be empty);
     * otherwise, if enabled, reference images are searched for online first.
     */
    public static AiResult generateBuild(String description, BuildPlanner.Frame frame, String environment,
                                         List<ReferenceFinder.Ref> userRefs, java.util.function.Consumer<String> status) {
        References refs = gatherReferences(description, userRefs, status);
        BlockPos a = frame.anchor;
        String user = """
            MODE: BUILD
            Anchor world position: %d,%d,%d. Player faces %s (= local +z).

            %s
            %s
            REQUEST:
            %s

            Design the best detailed build you can as a plan of components.
            """.formatted(a.getX(), a.getY(), a.getZ(), frame.facing, environment, referenceSection(refs), description);
        Config lc = Config.get();
        // Saved builds: study a couple of good examples (and optionally offer them for pasting).
        List<BuildLibrary.Entry> examples = lc.libraryLearn()
            ? BuildStudy.pick(description, lc.isOllama() ? Math.min(1, lc.maxStudyExamples) : lc.maxStudyExamples) : List.of();
        String lib = BuildStudy.promptSection(examples, true)
            + (lc.libraryPlace() ? BuildLibrary.promptSection(Math.max(0, lc.maxLibraryInPrompt)) : "");
        if (!lib.isEmpty()) user = user.replace("REQUEST:\n", lib + "\nREQUEST:\n");
        if (!examples.isEmpty() && status != null) {
            status.accept("Studying " + examples.size() + " saved build(s): "
                + String.join(", ", examples.stream().map(x -> x.name).toList()));
        }
        List<Img> images = refs == null ? null : refs.images();
        if (!examples.isEmpty() && lc.studyImages && supportsImages()) {
            List<Img> all = new java.util.ArrayList<>(images == null ? List.of() : images);
            for (BuildLibrary.Entry ex : examples) {
                byte[] png = BuildStudy.render(ex, 900);
                if (png != null) all.add(new Img(png, "image/png"));
            }
            images = all;
        }
        AiResult result = request(user, "BUILD: " + description, images);

        // If the plan ignores the main subject (e.g. a house for "wizard tower"), ask once more.
        String missing = missingMainComponent(description, result.components());
        if (missing != null) {
            String retry = user + "\nYOUR PREVIOUS PLAN WAS WRONG: the request asks for " + missing
                + ", but your plan has no " + missing + " component. Redo the plan with a " + missing
                + " component as the main structure.";
            AiResult second = request(retry, "BUILD (retry): " + description, images);
            if (missingMainComponent(description, second.components()) == null) result = second;
        }
        AiResult r = repairIfNeeded(user, "BUILD (repair): " + description, result, frame, images);
        return new AiResult(r.message(), r.components(), r.commands(), refs);
    }

    // ---------------- reference images ----------------

    private static final String REF_SYSTEM = "You are an expert Minecraft builder helping choose and read visual "
        + "references. Answer only with the requested JSON.";

    /** Finds (or uses the player's) reference images and has the model pick the useful ones. */
    static References gatherReferences(String description, List<ReferenceFinder.Ref> userRefs,
                                       java.util.function.Consumer<String> status) {
        Config cfg = Config.get();
        if (!supportsImages()) {
            if (userRefs != null && !userRefs.isEmpty()) status.accept("This model can't read images; ignoring /airef images.");
            return null;
        }
        List<ReferenceFinder.Ref> candidates;
        boolean fromPlayer = userRefs != null && !userRefs.isEmpty();
        if (fromPlayer) {
            candidates = userRefs;
        } else {
            if (!cfg.referenceSearch) return null;
            List<String> queries = searchQueries(description);
            if (queries.isEmpty()) return null;
            status.accept("Looking for reference images: " + String.join(", ", queries));
            candidates = ReferenceFinder.search(queries, Math.max(2, cfg.referenceCandidates));
            if (candidates.isEmpty()) {
                status.accept("No reference images found; designing without them.");
                return null;
            }
        }

        // Let the model look at the candidates, keep the relevant ones and describe their style.
        StringBuilder user = new StringBuilder("BUILD REQUEST: ").append(description).append("\n\n");
        user.append("Candidate reference images (attached in this order):\n");
        for (int i = 0; i < candidates.size(); i++) {
            user.append(i + 1).append(". ").append(candidates.get(i).title()).append('\n');
        }
        user.append("""

            1. "chosen": the numbers of the images that are genuinely useful references for this build
               (at most %d; skip photos of the wrong thing, maps, diagrams, people, text-heavy images).%s
            2. "styleNotes": a compact brief a Minecraft builder can follow, based on the chosen images:
               overall silhouette and proportions (e.g. "tower about 5x taller than wide"), roof shapes and
               pitch, wall structure (timber frame, stone courses, buttresses...), windows and openings,
               signature details worth recreating, and a block palette that matches the colors and
               materials you see (real Minecraft block ids).
            """.formatted(Math.max(1, cfg.maxReferenceImages),
                fromPlayer ? " The player chose these images, so keep them unless one is clearly unusable." : ""));
        JsonObject schema = JsonParser.parseString("""
            {"type":"object","properties":{"chosen":{"type":"array","items":{"type":"integer"}},
             "styleNotes":{"type":"string"}},"required":["chosen","styleNotes"]}""").getAsJsonObject();
        List<Img> imgs = new ArrayList<>();
        for (ReferenceFinder.Ref r : candidates) imgs.add(r.image());
        try {
            JsonObject pick = sideRequest(REF_SYSTEM, user.toString(), imgs, schema);
            List<ReferenceFinder.Ref> chosen = new ArrayList<>();
            if (pick.has("chosen") && pick.get("chosen").isJsonArray()) {
                for (JsonElement e : pick.getAsJsonArray("chosen")) {
                    int i = e.getAsInt() - 1;
                    if (i >= 0 && i < candidates.size() && !chosen.contains(candidates.get(i))
                        && chosen.size() < Math.max(1, cfg.maxReferenceImages)) {
                        chosen.add(candidates.get(i));
                    }
                }
            }
            String notes = pick.has("styleNotes") ? pick.get("styleNotes").getAsString() : "";
            if (chosen.isEmpty() && fromPlayer) chosen.addAll(candidates.subList(0, Math.min(candidates.size(), cfg.maxReferenceImages)));
            if (chosen.isEmpty() && notes.isBlank()) return null;
            return new References(chosen, notes);
        } catch (RuntimeException e) {
            status.accept("Couldn't analyze the reference images (" + e.getMessage() + "); designing without them.");
            return null;
        }
    }

    /** Asks the model for short image-search phrases; falls back to the request itself. */
    private static List<String> searchQueries(String description) {
        JsonObject schema = JsonParser.parseString("""
            {"type":"object","properties":{"useReferences":{"type":"boolean"},
             "queries":{"type":"array","items":{"type":"string"}}},"required":["useReferences","queries"]}""")
            .getAsJsonObject();
        String user = """
            A player wants this Minecraft build: "%s".
            Would photos or artwork help design it (buildings, landmarks, architectural styles, vehicles, nature)?
            If yes, give 2-3 short image-search phrases (2-5 words each) for an open image library like
            Wikimedia Commons. Prefer real-world architecture terms ("Gothic cathedral facade",
            "Japanese pagoda", "half-timbered house") or "concept art" for fantasy. No words like
            "Minecraft" or "build". If references would not help, set useReferences to false.
            """.formatted(description);
        try {
            JsonObject r = sideRequest(REF_SYSTEM, user, null, schema);
            if (r.has("useReferences") && !r.get("useReferences").getAsBoolean()) return List.of();
            List<String> out = new ArrayList<>();
            if (r.has("queries") && r.get("queries").isJsonArray()) {
                for (JsonElement e : r.getAsJsonArray("queries")) {
                    String q = e.getAsString().trim();
                    if (!q.isEmpty() && out.size() < 3) out.add(q);
                }
            }
            return out;
        } catch (RuntimeException e) {
            String q = description.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\b(build|make|create|me|a|an|the|please|minecraft|with|and|some|of|in)\\b", " ")
                .replaceAll("\\s+", " ").trim();
            return q.isEmpty() ? List.of() : List.of(q.length() > 60 ? q.substring(0, 60) : q);
        }
    }

    /** Prompt section describing the references (images are attached separately). */
    private static String referenceSection(References refs) {
        if (refs == null || refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nREFERENCES\n");
        if (!refs.refs().isEmpty()) {
            sb.append("Attached reference image(s): ");
            for (int i = 0; i < refs.refs().size(); i++) {
                sb.append(i + 1).append(") ").append(refs.refs().get(i).title()).append("  ");
            }
            sb.append('\n');
        }
        if (refs.styleNotes() != null && !refs.styleNotes().isBlank()) {
            sb.append("Style brief from the references: ").append(refs.styleNotes()).append('\n');
        }
        sb.append("""
            Use the references to guide silhouette, proportions, roof shapes, signature details and the block
            palette. Translate them into components at Minecraft scale; don't try to copy every detail.
            The player's request still decides WHAT to build; the references decide how it LOOKS.
            """);
        return sb.toString();
    }

    public static AiResult chat(String message, BuildPlanner.Frame frame, String environment) {
        BlockPos a = frame.anchor;
        String user = """
            MODE: CONVERSATION / BUILD EDIT
            Local origin: world %d,%d,%d, local +z = %s.

            %s

            %s

            PLAYER MESSAGE:
            %s

            Answer naturally in "message". If the player asks for a change, return ONLY the NEW components
            that make the change (in the same local coordinates as the last build); use clear and texture
            components to remove or recolor existing parts. Do not repeat the whole plan.
            If no change is requested, return an empty components list.
            """.formatted(a.getX(), a.getY(), a.getZ(), frame.facing, lastBuildContext(), environment, message);
        return repairIfNeeded(user, "CHAT (repair): " + message, request(user, "CHAT: " + message, null), frame, null);
    }

    /** Images to send with a review: optional screenshot first, then up to two reference images. */
    private static List<Img> reviewImages(Img screenshot) {
        List<Img> out = new ArrayList<>();
        if (screenshot != null) out.add(screenshot);
        BuildRecord b = lastBuild;
        if (b != null && b.references() != null) {
            List<Img> refs = b.references().images();
            out.addAll(refs.subList(0, Math.min(2, refs.size())));
        }
        return out;
    }

    public static AiResult fix(String notes, BuildPlanner.Frame frame, String environment, byte[] png,
                               String inspection) {
        List<Img> images = reviewImages(png == null ? null : new Img(png, "image/png"));
        boolean hasRefs = images.size() > (png == null ? 0 : 1);
        BlockPos a = frame.anchor;
        String user = """
            MODE: REVIEW AND FIX THE FINISHED BUILD
            %s
            Local origin: world %d,%d,%d, local +z = %s.

            %s

            %s

            %s

            PLAYER NOTES: %s

            Compare what is actually in the world (the drawings and checks above) with what the build should
            look like. Look for: floating or missing parts, gaps in walls or roofs, blocked doors, wrong
            proportions, flat featureless walls, poor palette, bad terrain contact, anything that does not
            match the request. Fix every problem listed under AUTOMATIC CHECKS first.
            Put a short critique in "message" and return NEW components (same local coordinates) that fix
            the most important problems. Return an empty components list if nothing needs fixing.
            """.formatted((png != null
                    ? "The FIRST attached image is the player's current view of the build."
                    : "No screenshot is available.")
                    + (hasRefs ? " The other attached image(s) are the style references the build was based on; "
                        + "check that the build captures their look." : ""),
                a.getX(), a.getY(), a.getZ(), frame.facing, lastBuildContext(),
                inspection == null ? "" : inspection, environment,
                notes == null || notes.isBlank() ? "(none)" : notes);
        return repairIfNeeded(user, "FIX (repair)", request(user, "FIX: " + (notes == null ? "" : notes), images),
            frame, images);
    }

    /**
     * Pre-build self-review: the model sees a simulated drawing of its own plan and may return a
     * corrected COMPLETE plan. An empty components list means "keep the plan as it is".
     */
    public static AiResult reviewPlan(String description, BuildPlanner.Frame frame, String planJson,
                                      String preview, References refs) {
        String user = """
            MODE: CHECK YOUR PLAN BEFORE IT IS BUILT
            REQUEST: %s

            YOUR PLAN (local coordinates):
            %s

            %s

            %s
            Study the drawings like a builder looking at a blueprint. Does it clearly look like what was
            requested? Check proportions, that the main subject dominates, roofs fully cover buildings,
            parts touch where they should (no floating pieces, no towers buried inside houses), doors
            are reachable, paths lead to doors, and details are not lost or hidden inside other parts.
            Fix everything listed under AUTOMATIC CHECKS.
            If changes are needed, return the COMPLETE corrected plan (all components, not just changes)
            and explain the fixes briefly in "message". If the plan is already good, return an empty
            components list and say "Plan looks good".
            """.formatted(description, planJson, preview, refs == null || refs.isEmpty() ? ""
                : "The attached image(s) are the style references; compare the drawings with them too.\n"
                    + (refs.styleNotes() == null ? "" : "Style brief: " + refs.styleNotes() + "\n"));
        List<Img> images = refs == null ? null : refs.images();
        AiResult r = request(user, "PREVIEW REVIEW: " + description, images);
        return r.components().isEmpty() ? r : repairIfNeeded(user, "PREVIEW REVIEW (repair)", r, frame, images);
    }

    /**
     * Dry-runs the plan. If some components are broken (e.g. a value the planner can't use),
     * sends the exact problems back to the model once and keeps whichever plan has fewer problems.
     */
    private static AiResult repairIfNeeded(String userPrompt, String label, AiResult result,
                                           BuildPlanner.Frame frame, List<Img> images) {
        if (result.components().isEmpty()) return result;
        List<String> problems = BuildPlanner.expand(result.components(), frame, null).problems();
        if (problems.isEmpty()) return result;

        StringBuilder fix = new StringBuilder(userPrompt);
        fix.append("\nYOUR PREVIOUS PLAN HAD INVALID COMPONENTS. Problems:\n");
        for (String p : problems.subList(0, Math.min(10, problems.size()))) fix.append("- ").append(p).append('\n');
        fix.append("Previous plan: ").append(result.components()).append('\n');
        fix.append("Return the full corrected plan. Use only the component types and field names listed "
            + "in the instructions, with numbers for sizes and coordinates.");
        try {
            AiResult second = request(fix.toString(), label, images);
            int before = problems.size();
            int after = BuildPlanner.expand(second.components(), frame, null).problems().size();
            boolean better = !second.components().isEmpty()
                && (after < before
                    || (after == before && second.components().size() > result.components().size()));
            return better ? second : result;
        } catch (RuntimeException e) {
            return result; // repair is best-effort; keep the original plan
        }
    }

    /** Returns the component type the request clearly needs but the plan lacks, or null. */
    static String missingMainComponent(String request, JsonArray components) {
        String r = request.toLowerCase(java.util.Locale.ROOT);
        boolean wantsTower = r.matches(".*\\b(tower|spire|turret|lighthouse|minaret|watchtower)s?\\b.*");
        boolean wantsWall = r.matches(".*\\b(castle|fort|fortress|keep|citadel)s?\\b.*");
        boolean hasTower = false, hasWall = false;
        for (JsonElement el : components) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("type")) continue;
            String t = el.getAsJsonObject().get("type").getAsString();
            hasTower |= t.equals("tower");
            hasWall |= t.equals("wall");
        }
        if (wantsTower && !hasTower) return "tower";
        if (wantsWall && !hasWall && !hasTower) return "wall";
        return null;
    }

    public static void recordBuild(BuildRecord record) {
        lastBuild = record;
    }

    public static BuildRecord getLastBuild() {
        return lastBuild;
    }

    public static boolean hasLastBuild() {
        return lastBuild != null;
    }

    public static void clearMemory() {
        synchronized (HISTORY) {
            HISTORY.clear();
        }
        lastBuild = null;
    }

    // ---------------- internals ----------------

    private static String lastBuildContext() {
        BuildRecord b = lastBuild;
        if (b == null) return "LAST BUILD: none yet.";
        StringBuilder sb = new StringBuilder();
        sb.append("LAST BUILD: ").append(b.description()).append('\n');
        sb.append("World area: ").append(b.bounds()).append('\n');
        sb.append("Its plan (local coordinates):\n").append(b.planJson()).append('\n');
        if (b.references() != null && b.references().styleNotes() != null && !b.references().styleNotes().isBlank()) {
            sb.append("Style brief it followed: ").append(b.references().styleNotes()).append('\n');
        }
        List<String> raw = b.rawCommands();
        if (raw != null && !raw.isEmpty()) {
            int max = Math.max(0, Config.get().maxRememberedCommands);
            sb.append("Extra raw commands that were run:\n");
            for (String c : raw.subList(0, Math.min(max, raw.size()))) sb.append(c).append('\n');
        }
        return sb.toString();
    }

    private static final String[] INT_FIELDS = {"x", "y", "z", "x2", "y2", "z2", "width", "depth", "floors",
        "floorHeight", "height", "radius", "thickness", "overhang", "windowSpacing", "windowHeight", "roofSteepness",
        "trimSpacing", "balconyY", "pitch", "storey", "length", "jetty", "dormers", "upperFrom", "roofOverhang",
        "kick", "doorOffset", "windowParity", "crossGables", "crossGableWidth", "orielWidth", "rotate"};
    private static final String[] STR_FIELDS = {"block", "wall", "frame", "floor", "foundation", "window", "sill",
        "door", "roof", "roofBlock", "fill", "mask", "kind", "size", "post", "light", "trim", "balconyBlock", "railing",
        "side", "interior", "shutters", "keystone", "base", "capital", "hedge", "flowers", "gate", "support",
        "access", "tip", "top", "steps", "style", "lowerWall", "roofGradient", "roofTrim", "ridge", "windowFrame",
        "finial", "entrance", "upperWall", "jettySides", "chimneyBlock", "cornerBlock", "banners", "palette", "climate",
        "color", "axis", "cover", "archetype", "crossGableSides", "orielGlass", "name", "plan", "finialCap", "beam", "fascia", "high", "roofProfile", "ridgeCap", "eaveBlock", "variant",
        "finialAt", "focal", "outdoorLights", "shape"};
    private static final String[] BOOL_FIELDS = {"crenellations", "hollow", "slits", "lights", "cut", "smoke", "lilies",
        "chimney", "plinth", "yard", "recess", "braces", "weathering", "buttresses", "vines", "windowBoxes", "awnings",
        "sign", "snowRoof", "eaveUnderside", "autoWing", "oriel", "roofMoss", "overgrowth", "finialPost", "gableLanterns",
        "beamEnds", "pentice", "doorHood", "windowHoods", "sparseWindows", "frontPorch", "flatInside", "blindShutters",
        "bayWindow", "leanTo", "roofStripes", "thickRoof", "cornerStack", "calm", "frontBalcony", "quietYard", "posts",
        "outsideStairs", "bigRoof", "eaveBrackets", "frontDeck", "deck"};

    /** Plan response schema in standard JSON Schema (converted for Gemini in callGemini). */
    private static JsonObject responseSchema() {
        java.util.function.Function<String, JsonObject> t = name -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", name);
            return o;
        };
        JsonObject comp = t.apply("object");
        JsonObject props = new JsonObject();
        JsonObject type = t.apply("string");
        JsonArray types = new JsonArray();
        BuildPlanner.TYPES.stream().sorted().forEach(types::add);
        type.add("enum", types);
        props.add("type", type);
        for (String f : INT_FIELDS) props.add(f, t.apply("integer"));
        for (String f : STR_FIELDS) props.add(f, t.apply("string"));
        for (String f : BOOL_FIELDS) props.add(f, t.apply("boolean"));
        JsonObject wing = t.apply("object"), wingProps = new JsonObject();
        for (String f : new String[]{"width", "depth", "floors", "offset"}) wingProps.add(f, t.apply("integer"));
        for (String f : new String[]{"side", "interior", "wall", "lowerWall"}) wingProps.add(f, t.apply("string"));
        wing.add("properties", wingProps);
        props.add("wing", wing);
        JsonObject wingList = t.apply("array");
        wingList.add("items", wing.deepCopy());
        props.add("wings", wingList);
        comp.add("properties", props);
        JsonArray compReq = new JsonArray();
        compReq.add("type");
        comp.add("required", compReq);

        JsonObject components = t.apply("array");
        components.add("items", comp);
        JsonObject commands = t.apply("array");
        commands.add("items", t.apply("string"));

        JsonObject root = t.apply("object");
        JsonObject rootProps = new JsonObject();
        rootProps.add("message", t.apply("string"));
        rootProps.add("components", components);
        rootProps.add("commands", commands);
        root.add("properties", rootProps);
        JsonArray req = new JsonArray();
        req.add("message");
        req.add("components");
        root.add("required", req);
        return root;
    }

    private static JsonObject textPart(String text) {
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        return p;
    }

    private static JsonObject turn(String role, JsonObject part) {
        JsonObject t = new JsonObject();
        t.addProperty("role", role);
        JsonArray parts = new JsonArray();
        parts.add(part);
        t.add("parts", parts);
        return t;
    }

    private static void remember(String userLabel, AiResult result) {
        int maxEntries = Math.max(0, Config.get().maxHistoryTurns) * 2;
        synchronized (HISTORY) {
            HISTORY.add(new String[]{"user", userLabel});
            HISTORY.add(new String[]{"model", result.message() + " (" + result.components().size() + " components)"});
            while (HISTORY.size() > maxEntries && HISTORY.size() >= 2) {
                HISTORY.remove(0); // remove in pairs so history always starts with a user turn
                HISTORY.remove(0);
            }
        }
    }

    private static final class RetryableException extends RuntimeException {
        RetryableException(String m) { super(m); }
    }

    /** Parses {"message":..., "commands":[...]} from model text (shared by both providers). */
    private static AiResult parseCommandsJson(String modelText) {
        String raw = modelText.trim().replace("```json", "").replace("```", "").trim();
        // Local models sometimes add text around the JSON; keep the outermost object.
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) raw = raw.substring(start, end + 1);

        JsonObject obj;
        try {
            obj = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            throw new RetryableException("The AI's answer wasn't valid JSON.");
        }

        String message = obj.has("message") && obj.get("message").isJsonPrimitive()
            ? obj.get("message").getAsString() : "Plan ready.";
        List<String> commands = new ArrayList<>();
        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
            for (JsonElement e : obj.getAsJsonArray("commands")) {
                if (e.isJsonPrimitive()) commands.add(e.getAsString());
            }
        }
        JsonArray components = obj.has("components") && obj.get("components").isJsonArray()
            ? obj.getAsJsonArray("components") : new JsonArray();
        return new AiResult(message, components, commands);
    }

    // ---------------- transport (Gemini + Ollama) ----------------

    /** Sends one plan request and parses it; retries once if the answer isn't valid JSON. */
    private static AiResult request(String userContent, String historyLabel, List<Img> images) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String text = callModel(systemPrompt(), userContent, images, responseSchema(), true);
            try {
                AiResult result = parseCommandsJson(text);
                remember(historyLabel, result);
                return result;
            } catch (RetryableException e) {
                last = new RuntimeException(e.getMessage());
            }
        }
        throw last;
    }

    /** Small structured side request (no conversation history). Returns the parsed JSON object. */
    private static JsonObject sideRequest(String system, String user, List<Img> images, JsonObject schema) {
        String text = callModel(system, user, images, schema, false);
        String raw = text.trim().replace("```json", "").replace("```", "").trim();
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s >= 0 && e > s) raw = raw.substring(s, e + 1);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    /** True if the current model can be sent images. */
    public static boolean supportsImages() {
        Config cfg = Config.get();
        if (!cfg.isOllama()) return true; // Gemini and Claude models read images
        String setting = cfg.ollamaSupportsImages == null ? "auto" : cfg.ollamaSupportsImages.toLowerCase(java.util.Locale.ROOT);
        if (setting.equals("true")) return true;
        if (setting.equals("false")) return false;
        String m = cfg.ollamaModel.toLowerCase(java.util.Locale.ROOT);
        return m.contains("vl") || m.contains("vision") || m.contains("llava") || m.contains("gemma3")
            || m.contains("gemma4") || m.contains("minicpm-v") || m.contains("moondream") || m.contains("bakllava")
            || m.contains("mistral-small3") || m.contains("granite3.2-vision") || m.contains("pixtral");
    }

    /**
     * Calls the configured model and returns its text. `schema` is standard JSON Schema
     * (lowercase types); it is converted for Gemini automatically.
     */
    private static String callModel(String system, String user, List<Img> images, JsonObject schema,
                                    boolean withHistory) {
        Config cfg = Config.get();
        List<Img> imgs = images == null || !supportsImages() ? List.of() : images;
        if (cfg.isOllama()) return callOllama(system, user, imgs, schema, withHistory, cfg);
        if (cfg.isClaude()) return callClaude(system, user, imgs, schema, withHistory, cfg);
        return callGemini(system, user, imgs, schema, withHistory, cfg);
    }

    /**
     * Claude (Anthropic Messages API). The answer format is given as a JSON Schema in the system
     * prompt; the plan schema is too large for strict structured outputs, and the lenient parser
     * plus one retry handle the rare malformed reply.
     */
    private static String callClaude(String system, String user, List<Img> images, JsonObject schema,
                                     boolean withHistory, Config cfg) {
        String apiKey = cfg.resolveClaudeKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No Claude API key. Put it in config/aiworldedit.json (\"claudeApiKey\") and run /aireload.");
        }
        JsonArray messages = new JsonArray();
        if (withHistory) {
            synchronized (HISTORY) {
                for (String[] turn : HISTORY) {
                    messages.add(claudeText("model".equals(turn[0]) ? "assistant" : "user", turn[1]));
                }
            }
        }
        JsonArray content = new JsonArray();
        for (Img img : images) {
            if (img.data().length > 7_000_000) continue; // stay under the 10 MB base64 limit per image
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", img.mime());
            source.addProperty("data", Base64.getEncoder().encodeToString(img.data()));
            JsonObject block = new JsonObject();
            block.addProperty("type", "image");
            block.add("source", source);
            content.add(block); // images before text, as Anthropic recommends
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", user);
        content.add(text);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", content);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.claudeModel);
        body.addProperty("max_tokens", cfg.maxOutputTokens);
        body.addProperty("system", system + "\n\nOUTPUT FORMAT: reply with exactly one JSON object and nothing else "
            + "(no markdown fences, no comments, no text before or after). It must match this JSON Schema:\n"
            + GSON.toJson(schema));
        body.add("messages", messages);
        String json = GSON.toJson(body);

        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) sleep(3000);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(claudeEndpoint))
                    .timeout(Duration.ofSeconds(Math.max(60, cfg.claudeTimeoutSeconds)))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    last = new RuntimeException("Claude " + code + ": " + apiErrorMessage(resp.body()));
                    if (code == 429 || code == 529 || code >= 500) continue; // rate limit / overloaded: retry once
                    throw last;
                }
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                String stop = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                    ? root.get("stop_reason").getAsString() : "";
                if ("max_tokens".equals(stop)) {
                    throw new RuntimeException("The plan was cut off (too long). Ask for a smaller build, "
                        + "or raise maxOutputTokens in config/aiworldedit.json.");
                }
                if ("refusal".equals(stop)) throw new RuntimeException("Claude declined this request. Try rephrasing.");
                StringBuilder out = new StringBuilder();
                JsonArray blocks = root.getAsJsonArray("content");
                if (blocks != null) {
                    for (JsonElement el : blocks) {
                        JsonObject b = el.getAsJsonObject();
                        if (b.has("type") && "text".equals(b.get("type").getAsString())) out.append(b.get("text").getAsString());
                    }
                }
                if (out.toString().isBlank()) throw new RetryableException("Claude returned an empty answer.");
                return out.toString();
            } catch (RetryableException e) {
                last = new RuntimeException(e.getMessage());
            } catch (RuntimeException e) {
                throw e;
            } catch (java.net.http.HttpTimeoutException e) {
                throw new RuntimeException("Claude took longer than " + cfg.claudeTimeoutSeconds
                    + "s. Try a smaller build or raise claudeTimeoutSeconds.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted.");
            } catch (Exception e) {
                last = new RuntimeException("Request failed: " + e.getMessage());
            }
        }
        throw last == null ? new RuntimeException("Request failed.") : last;
    }

    /** Anthropic Messages endpoint (overridable for tests). */
    static String claudeEndpoint = "https://api.anthropic.com/v1/messages";

    private static JsonObject claudeText(String role, String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", text);
        return m;
    }

    /** Gemini API base URL (overridable for tests). */
    static String geminiBase = "https://generativelanguage.googleapis.com";
    /** Largest response schema (JSON characters) sent to Gemini. */
    private static final int GEMINI_SCHEMA_MAX = 1500;
    /** Set once Gemini refuses a schema; later requests rely on JSON mode only. */
    private static volatile boolean geminiSchemaRejected = false;

    private static String callGemini(String system, String user, List<Img> images, JsonObject schema,
                                     boolean withHistory, Config cfg) {
        String apiKey = cfg.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No API key. Put it in config/aiworldedit.json (\"apiKey\") and run /aireload.");
        }
        JsonArray contents = new JsonArray();
        if (withHistory) {
            synchronized (HISTORY) {
                for (String[] turn : HISTORY) contents.add(turn(turn[0], textPart(turn[1])));
            }
        }
        JsonArray parts = new JsonArray();
        parts.add(textPart(user));
        for (Img img : images) {
            JsonObject inline = new JsonObject();
            inline.addProperty("mimeType", img.mime());
            inline.addProperty("data", Base64.getEncoder().encodeToString(img.data()));
            JsonObject imgPart = new JsonObject();
            imgPart.add("inlineData", inline);
            parts.add(imgPart);
        }
        JsonObject userTurn = new JsonObject();
        userTurn.addProperty("role", "user");
        userTurn.add("parts", parts);
        contents.add(userTurn);

        JsonObject systemInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        sysParts.add(textPart(system));
        systemInstruction.add("parts", sysParts);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("maxOutputTokens", cfg.maxOutputTokens);
        genConfig.addProperty("responseMimeType", "application/json");
        // Gemini rejects large response schemas ("400 invalid argument": too many constraint states), and the
        // build plan has well over 100 optional fields. Big schemas are left out: JSON mode plus the format in
        // the system prompt is enough, and the parser is lenient. Small schemas are still sent.
        boolean sendSchema = schema != null && !geminiSchemaRejected && GSON.toJson(schema).length() <= GEMINI_SCHEMA_MAX;
        if (sendSchema) genConfig.add("responseSchema", toGeminiSchema(schema));

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("systemInstruction", systemInstruction);
        body.add("generationConfig", genConfig);
        String json = GSON.toJson(body);
        String endpoint = geminiBase + "/v1beta/models/" + cfg.model + ":generateContent";

        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) sleep(2000);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(180))
                    .header("content-type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    last = new RuntimeException("Gemini " + code + ": " + apiErrorMessage(resp.body()));
                    System.err.println("[AI WorldEdit] Gemini error " + code + ": " + resp.body());
                    if (code == 400 && sendSchema) { // schema refused: try again without it, and stop sending it
                        geminiSchemaRejected = true;
                        genConfig.remove("responseSchema");
                        json = GSON.toJson(body);
                        sendSchema = false;
                        continue;
                    }
                    if (code == 429 || code >= 500) continue; // retry once
                    throw last;
                }
                return geminiText(resp.body());
            } catch (RetryableException e) {
                last = new RuntimeException(e.getMessage());
            } catch (RuntimeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted.");
            } catch (Exception e) {
                last = new RuntimeException("Request failed: " + e.getMessage());
            }
        }
        throw last == null ? new RuntimeException("Request failed.") : last;
    }

    private static String geminiText(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            String block = root.has("promptFeedback") ? root.get("promptFeedback").toString() : "";
            throw new RuntimeException("Gemini returned no answer. " + truncate(block, 150));
        }
        JsonObject cand = candidates.get(0).getAsJsonObject();
        String finish = cand.has("finishReason") ? cand.get("finishReason").getAsString() : "";
        if ("MAX_TOKENS".equals(finish)) {
            throw new RuntimeException("The plan was cut off (too long). Ask for a smaller build, "
                + "or raise maxOutputTokens in config/aiworldedit.json.");
        }
        if (!finish.isEmpty() && !"STOP".equals(finish)) {
            throw new RuntimeException("Gemini stopped early (" + finish + "). Try rephrasing.");
        }
        JsonObject content = cand.getAsJsonObject("content");
        JsonArray parts = content == null ? null : content.getAsJsonArray("parts");
        if (parts == null) throw new RetryableException("Gemini returned an empty answer.");
        StringBuilder text = new StringBuilder();
        for (JsonElement el : parts) {
            JsonObject p = el.getAsJsonObject();
            boolean thought = p.has("thought") && p.get("thought").getAsBoolean();
            if (!thought && p.has("text")) text.append(p.get("text").getAsString());
        }
        return text.toString();
    }

    private static String callOllama(String system, String user, List<Img> images, JsonObject schema,
                                     boolean withHistory, Config cfg) {
        String base = cfg.ollamaUrl.endsWith("/") ? cfg.ollamaUrl.substring(0, cfg.ollamaUrl.length() - 1) : cfg.ollamaUrl;
        JsonArray messages = new JsonArray();
        messages.add(ollamaMessage("system", system));
        if (withHistory) {
            synchronized (HISTORY) {
                for (String[] turn : HISTORY) {
                    messages.add(ollamaMessage("model".equals(turn[0]) ? "assistant" : "user", turn[1]));
                }
            }
        }
        JsonObject userMsg = ollamaMessage("user", user);
        if (!images.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Img img : images) arr.add(Base64.getEncoder().encodeToString(img.data()));
            userMsg.add("images", arr); // needs a vision model, e.g. gemma3 or qwen2.5vl
        }
        messages.add(userMsg);

        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", cfg.ollamaContextLength); // Ollama's default is too small for this prompt
        options.addProperty("num_predict", cfg.maxOutputTokens);
        options.addProperty("temperature", cfg.ollamaTemperature);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.ollamaModel);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "15m");
        body.add("format", schema); // structured output: forces the JSON shape
        body.add("options", options);
        body.add("messages", messages);
        String json = GSON.toJson(body);

        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/chat"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(Math.max(30, cfg.ollamaTimeoutSeconds)))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    String msg = ollamaError(resp.body());
                    if (code == 404 || msg.contains("not found")) {
                        throw new RuntimeException("Ollama doesn't have model '" + cfg.ollamaModel
                            + "'. Run: ollama pull " + cfg.ollamaModel);
                    }
                    if (!images.isEmpty() && (msg.contains("image") || msg.contains("vision") || msg.contains("multimodal"))) {
                        throw new RuntimeException("Model '" + cfg.ollamaModel + "' can't read images. Use a vision "
                            + "model (e.g. /aiprovider ollama gemma3:12b) or set ollamaSupportsImages to false.");
                    }
                    throw new RuntimeException("Ollama " + code + ": " + msg);
                }
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                String done = root.has("done_reason") ? root.get("done_reason").getAsString() : "";
                if ("length".equals(done)) {
                    throw new RuntimeException("The plan was cut off. Raise maxOutputTokens / ollamaContextLength "
                        + "in config/aiworldedit.json, or ask for a smaller build.");
                }
                JsonObject message = root.getAsJsonObject("message");
                String content = message != null && message.has("content") ? message.get("content").getAsString() : "";
                if (content.isBlank()) throw new RetryableException("Ollama returned an empty answer.");
                return content;
            } catch (RetryableException e) {
                last = new RuntimeException(e.getMessage());
            } catch (RuntimeException e) {
                throw e;
            } catch (java.net.ConnectException e) {
                throw new RuntimeException("Can't reach Ollama at " + base + ". Is Ollama running?");
            } catch (java.net.http.HttpTimeoutException e) {
                throw new RuntimeException("Ollama took longer than " + cfg.ollamaTimeoutSeconds
                    + "s. Try a smaller model or raise ollamaTimeoutSeconds.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted.");
            } catch (Exception e) {
                if (e.getCause() instanceof java.net.ConnectException) {
                    throw new RuntimeException("Can't reach Ollama at " + base + ". Is Ollama running?");
                }
                last = new RuntimeException("Request failed: " + e.getMessage());
            }
        }
        throw last == null ? new RuntimeException("Request failed.") : last;
    }

    /** Gemini wants uppercase type names and format=enum on string enums. */
    private static JsonElement toGeminiSchema(JsonElement el) {
        if (el.isJsonObject()) {
            JsonObject in = el.getAsJsonObject(), out = new JsonObject();
            for (var e : in.entrySet()) {
                if (e.getKey().equals("type") && e.getValue().isJsonPrimitive()) {
                    out.addProperty("type", e.getValue().getAsString().toUpperCase(java.util.Locale.ROOT));
                } else {
                    out.add(e.getKey(), toGeminiSchema(e.getValue()));
                }
            }
            if (in.has("enum") && !in.has("format")) out.addProperty("format", "enum");
            return out;
        }
        if (el.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : el.getAsJsonArray()) out.add(toGeminiSchema(e));
            return out;
        }
        return el.deepCopy();
    }

    private static JsonObject ollamaMessage(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String ollamaError(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) return truncate(o.get("error").getAsString(), 200);
        } catch (Exception ignored) {
            // fall through
        }
        return truncate(body, 200);
    }

    // ---------------- shared helpers ----------------

    private static String apiErrorMessage(String body) {
        try {
            JsonObject err = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (err != null && err.has("message")) return truncate(err.get("message").getAsString(), 200);
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return truncate(body, 200);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
