# AI WorldEdit Builder v21.0.1

Describe a build in chat. The AI designs it as a plan of **building components** (building, roof, tower,
wall, path, tree, lamp_post, box, clear, texture, sphere, cylinder, block). The mod turns each component into
exact WorldEdit commands: stair directions, log orientation, symmetric windows and doors, and foundations
that reach down to the terrain.

## Building pieces

| Piece | What it makes |
|---|---|
| building | House/hall: foundation, walls, timber frame (recessed plaster, cross braces), windows (+ sills, shutters, flower boxes), door, floors, **furnished interior** (home, tavern, library, shop), ladder between floors, gable/hip/flat roof (pitch 1 or steep 2, flared eaves), optional **wing** for L-shaped plans |
| roof | Extra roof over any rectangle |
| tower | Round tower with floors, ladder, slit windows, trim rings, balcony; roof cone, dome, onion, crenellated or flat |
| porch, balcony, chimney | Attach to a building's wall; heights and walls are matched automatically |
| wall, arch | Castle walls with battlements; rounded archways (stand-alone or cut through a wall as a gate) |
| stairs, bridge, column | Staircases, bridges with railings and posts, columns with base and capital |
| path, fence, garden | Terrain-following paths, fences with gates, hedged flower gardens |
| fountain, pond, tree, lamp_post | Landscape details |
| well, stall, woodpile | Village well with a little roof, striped market stall, covered firewood stack |
| box, clear, texture, sphere, cylinder, block | Free-form shapes and single blocks |

To add your own piece: write a function in `BuildPlanner.java`, add its name to `TYPES` and the `switch` in
`expand()`, then describe it in the COMPONENTS section of the prompt in `AiClient.java`.

## Styles

A style fills in every setting the AI leaves out, so builds get a consistent, detailed look.
Requests containing *medieval, tavern, inn, blacksmith, half-timbered, timber-framed, tudor, village* or
*hamlet* use the **medieval** style automatically:

- **Stone ground floor** with corner quoins, moss and cracks low down, buttresses on long side walls, ivy.
- **Timber-framed upper floors** that jut out (jetty) on brackets; the plaster is set back one block so the
  beams stand proud, and X-braces alternate with windows symmetrically from the middle of each wall.
- **Roofs**: steep, with a flared eave (first row shallower), shading dark to light (layers may be mixes),
  dark trim, carved boards under the overhang, dormers and finials.
- **Windows** with sills and flower boxes; shutters on the stone floor. **Entrance** framed, with wall
  lanterns on brackets; taverns and shops also get a hanging sign and banners, shops get awnings.
- **Floor plans** (`"plan"`, new in v13): houses are no longer one box. Each house is built from a main block
  plus connected wings and/or a corner turret:
  - *L*: a wing on one side (set back) or coming forward at one front corner
  - *T*: a wing out the back
  - *U*: two wings coming forward around a little front court (width 13+)
  - *cross*: wings on both sides
  - *stepped*: a taller wing on one side, a small low one on the other
  - *turret* / *L+turret*: a round turret at a front corner, dressed like the house (stone base, the house's
    walls and frame above, a cone in the roof colours); it touches the house only at the corner so it never
    cuts into a room
  - *simple*: one block
  Wing roofs run into the main roof and doorways join the rooms. If the AI doesn't pick a plan, one is chosen
  for the house type and the spot you build on. If a wing or turret would hit another building or a path, the
  next plan that fits is used. The front door moves aside when a wing comes forward next to it.
  The AI can also lay out its own: `"wings":[{"side":"left","width":7,"depth":5},{"side":"back",...}]`.
- **Details tuned against reference houses** (new in v14). The mod was scored against 16 saved reference
  houses (roofline, wall depth, block variety, glass, lights) and the gaps were closed:
  - every gable (main roof, wings, cross gables) gets a timber **finial post** with a spire and sometimes a
    lantern on top; the ridge beam pokes out under it with a **lantern hanging** below; hip roofs get finials
    at both ridge ends; dormers get a small spire; chimneys get a crown
  - **beam ends** stick out under each floor line and the eaves, with lanterns under the front corners
  - a narrow **pent roof** runs along the top of stone ground floors; a **door hood** on brackets otherwise
  - **window hoods**, shutters on the recessed upper windows too, and **fewer, smaller windows**
  - horizontal beams in a **second timber tone** (bark logs with stripped posts, or the reverse)
  - one-storey houses (farmhouses, some cottages) get a **front porch**
  - lanterns along each room, on barrels and hay in the yard; candles, rugs, bookshelves, pots inside
  - **flat inside walls**: the recessed plaster no longer leaves niches behind every post, so rooms are clean
    rectangles (and doors/doorways through recessed walls are cleared)
  - bigger default sizes in the prompt (main block usually 11-19 wide), like the references
  Every one can be switched off per building (`"beamEnds":false`, `"pentice":false`, `"sparseWindows":false`...).
- **Block physics fixes** (new in v21.0.1). WorldEdit places blocks with neighbour updates, so a few designed blocks
  used to misbehave in the real world: concrete powder in wall and roof mixes fell through window and door
  openings, tower ladders in front of stairs or windows popped off, a hanging lantern on the top floor floated, and
  the deck ivy dropped. Composed houses now settle every block before compiling: falling blocks stay only where a
  solid block is placed under them first, ladders get a full block behind, hanging lanterns get a chain to the
  ceiling, vines keep only supported sides, flowers need soil. Every other build (including the AI's own commands)
  is replayed on the real terrain and loses its sand/gravel/powder where it would fall. Big edits also get a few
  extra ticks before the next command so the server and lighting keep up. `"fastSelections": false` in the config
  restores the slower v20 pace (one command per step).
- **Molded houses: the house composer** (new in v21). Medieval houses are no longer stacked boxes. Each one is
  designed block by block on a canvas, the way a builder works by hand, and then compiled into WorldEdit boxes
  (equal neighbouring blocks merged, bottom-up, decorations last). A detailed house costs about the same as the
  old plain one: roughly 450-900 `//set` commands (2,000-2,800 lines including selections).
  The composer shapes outlines that change with height and parts that grow into each other:
  - stone ground storey with quoins, base and band courses, a framed centre door and recessed panels
  - timber-framed upper storeys that jut out on stepped corbels, with posts, shutters and a lit centre window
  - flared roofs with ragged verges, a sweep over side annexes, eave brackets, dormers on deep houses
  - a composed front gable (beam ends, attic balcony and door, lit cross window, horn or finial) or a cross gable
  - side annexes with hipped roofs and a junction chimney, or a corner tower with a jettied top room and a
    flared spire (sometimes a crane with a hanging basket)
  - front decks on big posts, porches, oriels, a simple interior, a lamp post and flowers in the yard
  It is used for every medieval house with an archetype; houses with explicit wings use the old builder.
  Turn it off per building with `"composer": false`.
  Villages place each composed house clear of the others. Commands are also sent faster (a selection goes in the
  same tick as its edit), and `maxCommands` defaults to 20000 (old 3000/6000 settings are raised).
- **Varied villages** (new in v20.1). When the AI gives several houses exactly the same shape settings, every
  repeat after the first drops its layout settings (house type, plan, roof, feature switches) so the style picks a
  different layout, and gets a slightly different size and storey count; materials stay as given. Ask for
  "identical", "matching" or "row houses" to keep them the same. The AI is also told to leave those settings out.
- **Lessons from a hand-built reference** (new in v20). Rebuilding a reference survival house block by block
  showed that its quality comes from big shapes, not small detail:
  - **Big roof** (`"bigRoof"`, on for houses 13+ wide and manors): eaves two blocks out, a lower skirt row one more
    out, ragged gable edges where every other row pokes out, and `"eaveBrackets"` under the eaves at every post
  - **Hierarchy**: wings are a storey lower with a tight roof; the square tower stands taller than the house
  - **Front deck** (`"frontDeck": true`, a signature choice for houses 11+ wide): a deep platform across the first
    upper floor, wider than the house, on two big posts with capitals, joists with stepped ends, a carved edge board,
    railing and a door out. The `balcony` component takes `"deck": true` for the same look
  - **Taller storeys**: houses 15+ wide get 5-block storeys, two-storey manors 6
  - **Better square tower**: the top room juts out on brackets (toward the house only over an eave), brackets under
    the eave, and a flared spire (two nearly flat rings, then ever steeper) in the house's roof colours with a finial
- **Calm pass** (new in v19, `"calm": false` turns it off). Reference builders show restraint; switching every
  detail on for every house is what made ours look busy. Each house now gets:
  - one signature feature (round turret, square tower, porch, bay window, oriel or a front balcony) and at most one
    other addition (a wing or a lean-to)
  - two small secondary details picked from door hood, window hoods, gable lanterns, flower boxes, vines, pent roof,
    blind shutters and beam ends; the rest stay off
  - small details on the front face only (`"focal": "front"`), outside lanterns only by the door
    (`"outdoorLights": "door"`), a quieter yard (`"quietYard"`)
  - one finial, on the front gable only (`"finialAt": all | main | front | none`), and none on wings, dormers or
    cross gables. Finial posts were the biggest difference: 30.7 skyline spikes per 1000 footprint blocks against
    5.2 in the references; now 3.5
  - either one cross gable or dormers, never both; no stepped "mixed" roof profile; one kind of glass
  - all the small timber (window frames, shutters, sills, door, fence posts, fascia) in the frame's wood, roof trim
    from a colour already on the roof (4.0 material families on the outside, references 4.5)
  Anything the AI sets itself is kept.
- **Square tower** (new in v19, `"plan": "tower"` or `"L+tower"`): a stone tower against one side of the house,
  standing two blocks in front of the facade, with band courses at the floor lines, a timber-framed top room, a
  pyramid roof in the house colours, a doorway from the house, and a stone staircase winding up the outside (front
  face, outer side, back) to doors on the upper floors.
- **Front balcony** (new in v19, `"frontBalcony": true`): a broad gallery across the first upper floor, two deep, on
  timber posts with knee braces, a fence railing, its own pent roof and a door out from the upstairs room. The
  `balcony` component also takes `"posts": true` and `"roof": "lean"`.
- **Calm score** (test tools): `ana/calm.py` measures exterior material families, roof masses, lone small blocks,
  skyline spikes and how much detail sits on the busiest face, against the reference builds.
- **Clean materials** (new in v18). Good builds (JPCORE, TrixyBlox and the other references) use gradients in
  ordered bands and keep big surfaces calm; random block mixes everywhere look noisy. Now:
  - plaster between the beams is one block (calcite, white terracotta, smooth sandstone, pale oak...)
  - stone ground floors are laid in courses: a rough base row (`"baseCourse"`), an even body of one stone with a
    little cracked/mossy variation, and a clean band row near the top (`"bandCourse"`, on taller floors)
  - roofs shade in clean rows from the eaves up instead of speckled mixed rows; towers and chimneys use calmer stone
  - palettes learned from your saved builds keep only a surface's main block when it clearly dominates
  Measured as "speckle" (how often neighbouring blocks on one surface differ): walls went from 0.31 to 0.20 and
  roofs from 0.22 to 0.11; the references average 0.22 and 0.16.
- **Roof variety** (new in v16): roofs are no longer all steep. Each house gets a profile, different between
  neighbours: *steep*, *classic* (45 degrees), *rounded* (steep at the eaves, easing toward the ridge), *flared*
  (wide shallow eaves sweeping up) or *mixed*. The AI can pick one with `"roofProfile"`.
- **TrixyBlox look** (new in v16), learned from his USW2 medieval village (in `medieval/trixyblox/`): about 3 in
  10 medieval houses are built his way, and every house when the request mentions TrixyBlox/Trixy:
  - thick roofs (two layers at the edges) in striped rows of oak, jungle and spruce, a toothed ridge, a
    mushroom-block lip along the eaves, rounded or 45-degree profiles
  - **log-cabin corners**: horizontal logs stacked crosswise with the ends sticking out (`"cornerStack"`)
  - bark-wood frames with spruce beams, tuff/andesite stone, grey plaster or dark plank walls, finials capped with
    andesite, a little greenery
  - `"variant":"trixy"` or `"palette":"trixy"` on a building asks for it. His schematics are also study examples:
    asking for a blacksmith, bakery or "a TrixyBlox style house" shows the AI his builds.
- **Trim and roof levels** (new in v15), the two things that most separate great builds from good ones:
  - *Trim*: trapdoor **fascia boards** hang along every eave (main roof, wings, porches, lean-tos); plain stone
    bays get **closed shutters** over a sill instead of bare wall; lanterns under the gable beam ends hang on a
    chain (`iron_chain`).
  - *Roof levels*: a **lean-to** annex (one-slope roof, `"roof":"shed"`) against a side or the back of most
    two-storey houses; a **bay window** box with its own little hipped roof on the ground floor; porches now have
    a **sloped lean-to roof** instead of a flat slab; plus the pent roof, wings and cross gables from before.
  Switches: `"fascia":"none"`, `"blindShutters":false`, `"leanTo":false`, `"bayWindow":false`, porch `"roof":"flat"`.
- **House types** (`"archetype"`), so plain requests don't all look the same:
  - *townhouse*: narrow and tall, gable to the street, jettied front and back.
  - *cottage*: wide; either jettied all round with a bay window, or one storey under a big steep roof (with a porch).
  - *manor*: tall formal floors, hip roof with one big central gable, banners.
  - *farmhouse*: one timber storey on a stone plinth, steep thatch hip roof, often a wing.
  When the AI doesn't name one, a type that suits the footprint is picked, and the pick also depends on
  where you build, so the same request gives different houses in different spots.
- **Palettes**: spruce/stone, dark oak/deepslate, oak/cobblestone, brick roof, grey slate (cottages), tuff (fantasy: tuff stone, pale oak walls, overgrown). Each building
  picks one from its position, so villages mix them; towers, chimneys and walls match the nearest building.
  The AI can choose one with `"palette":"dark_oak"`.
- **Climate**: building on sand (or asking for a desert build) switches to sandstone and warm roofs; snow
  (or a winter/snowy request) gives dark roofs with snow on them and no flowers or ivy.
- Towers get a stone base, timber-framed top and a flared shingled cone.

Every one of these can be turned off or changed per building (e.g. `"braces":false`, `"vines":false`).

Add a style in `StylePresets.java` (defaults per piece + keywords in `detect`).

## How plans work

- The AI works in local coordinates: x = right/left, z = forward (away from you), y = up, with the origin at
  the block you're looking at. The mod rotates everything to the direction you face.
- Ground components (building, tower, wall, path, tree, lamp_post) fit the terrain automatically.
- `/aichat` and `/aifix` edits add new components to the same plan (use `clear`/`texture` to change parts).
- The geometry code is in `BuildPlanner.java`. Improving a component there improves every build that uses it.

## Reference images (style guidance)

Before designing, the mod can look up real reference images so builds match a style:

1. The AI suggests 2-3 short search phrases (e.g. "Japanese pagoda", "half-timbered house").
2. The mod searches **Wikimedia Commons**, then **Openverse**, for openly licensed images and downloads small thumbnails.
3. The AI looks at them, keeps the useful ones and writes a short style brief (proportions, roof shapes, details, block palette).
4. The images and brief go into the build request, the preview review and `/aifix`.

Credits (title, source, license, author) are shown in chat; links are written to `logs/latest.log`.

Use your own images: `/airef https://...jpg` or put a JPG/PNG in `config/aiworldedit/references/` and run
`/airef myfile.png`. Queued images are used for the next `/aibuild` (up to 5). Also: `/airef list`,
`/airef clear`, `/airef off` / `/airef on` (automatic search).

Needs a model that can read images: Gemini, or an Ollama vision model such as `gemma3`. Config:
`referenceSearch`, `referenceSources`, `referenceCandidates`, `maxReferenceImages`, `referenceContact`
(optional email for the User-Agent, which Wikimedia asks API users to include), `ollamaSupportsImages`.

## How the AI checks its own work

1. **Simulated preview (before building):** the mod runs the plan on a copy of the real terrain and draws the
   result as text: front, side and top views plus floor plans, with a block legend. It also runs automatic
   checks (floating blocks, blocked doors, doors missing a half, very flat builds). The AI reviews this and
   can return a corrected plan before anything is placed. Config: `previewReview`.
2. **World inspection (`/aifix`):** scans the real blocks in the build area, draws the same views, and
   compares them with the plan to find blocks that are missing (e.g. a command WorldEdit rejected).
   This is sent with the screenshot.
3. **Optional automatic review:** set `autoReviewAfterBuild` to `true` to run step 2 automatically when a
   build finishes.

## Teaching it with saved builds

Put `.litematic` or `.schem` files of builds you like (Planet Minecraft, shulkr...) in
`.minecraft/config/aiworldedit/library/` (Mac: `~/Library/Application Support/minecraft/config/aiworldedit/library/`)
and run `/ailib reload`. They are **examples to learn from, not copied into your world**:

- The mod measures each build: footprint shape, wall height and floors, how tall the stone base is, what the walls
  and framing are made of, how much is glass, roof height share, pitch, overhang, roof and trim materials, greenery.
- On every `/aibuild` the AI studies the 2 best-matching examples (1 for Ollama): those measurements, a front and
  top drawing, and a rendered picture (for models that can see images). It's told to design its own build to the
  same standard, not to copy.
- Each example also becomes a **palette** (named after the file) that the house types pick from, so your own
  designs take on the materials of the builds you saved. The AI can ask for one: `"palette":"casa-medieval"`.
- **Folders are tags.** `medieval/house/x.litematic` is a "medieval house"; a tower request studies towers,
  a castle request studies castles. Folders starting with `_` (like `_set-aside`) are ignored.
- **Keep the collection anywhere:** set `"libraryFolder": "~/Desktop/mcai_ref"` in `config/aiworldedit.json`.
- Grass, dirt, flowers and crops a schematic sits on are ignored when learning.
- `/ailib` lists them, `/ailib study <name>` shows what was learned, `/ailib reload` after adding files.
- Config `libraryMode`: `learn` (default), `place` (the AI may also paste examples whole), `both`, `off`.
  `/ailib place <name> [90|180|270]` still pastes one by hand if you ever want to.
- Blocks or block states the game doesn't know (newer versions, Bedrock conversions) are fixed or left out.
- Decorations hung on walls (heads, string, pickles, brewing stands, shelves...) and stray blocks under 8% are not
  mistaken for the wall material, so learned palettes stay clean.

More variety in the folder = better results: different sizes, roof shapes and materials.

**Crowd sets** (new in v17): put a big set of simpler builds in a folder named `crowd` (for example
`crowd/house/` with the 150 converted CraftAssist houses). Crowd builds are never turned into palettes and never
crowd out your hand-picked references: one is shown to the AI only when its labelled parts match the request
("a house with a porch and a chimney", "a deck with a railing"), and the AI is told it is a simple example for
layout and parts, not quality.

## Commands

| Command | What it does |
|---|---|
| `/aibuild <description>` | Plans a build at the block you're looking at. Shows a particle outline first. |
| `/airef <link or file>` | Use your own reference image for the next build (`list`, `clear`, `on`, `off`). |
| `/aiconfirm` | Runs the plan that's waiting. |
| `/aichat <message>` | Talk to the builder or ask for edits ("make the roof darker"). It knows what it built last. |
| `/aifix [notes]` | Takes a screenshot of your view, has Gemini critique the build, and proposes fixes. |
| `/aiundo` | Undoes the last build or edit (sends `//undo N`). |
| `/aistop` | Stops the queue, discards a pending plan, and cancels an in-flight request. |
| `/aiforget` | Clears conversation and last-build memory. |
| `/aiprovider [gemini \| claude [model] \| ollama [model]]` | Shows or switches between Gemini, Claude and a local Ollama model. |
| `/aireload` | Reloads `config/aiworldedit.json`. |

## Setup

1. Requires **Minecraft 1.21.11**, Fabric Loader 0.18.1+, Fabric API for 1.21.11, and **WorldEdit 7.3+ for Fabric (1.21.11 build)** (needed for `//placement`).
2. Build: `gradlew.bat build` (JDK 21). The jar is in `build\libs\`.
3. Launch once. Open `.minecraft\config\aiworldedit.json`, set `"apiKey"`, then run `/aireload` in game.
   (The `GEMINI_API_KEY` environment variable still works as a fallback.)

## Using Claude

1. Create an API key at platform.claude.com (API usage is billed separately from a Claude.ai subscription).
2. Put it in `config/aiworldedit.json` as `"claudeApiKey": "sk-ant-..."` (or set the `ANTHROPIC_API_KEY`
   environment variable), then `/aireload`.
3. `/aiprovider claude` (uses `claudeModel`, default `claude-sonnet-5-5`), or pick a model:
   `/aiprovider claude claude-opus-5-5` (best quality) / `claude-haiku-5-5` (cheapest).

All current Claude models read images, so reference images, previews and `/aifix` screenshots work.

## Using a local model (Ollama)

1. Install Ollama from ollama.com and pull a model, e.g. `ollama pull qwen2.5-coder:14b`
   (use a vision model such as `gemma3:12b` if you want `/aifix`).
2. In game: `/aiprovider ollama qwen2.5-coder:14b`. Switch back with `/aiprovider gemini`.
3. Settings: `ollamaUrl`, `ollamaModel`, `ollamaContextLength` (default 16384), `ollamaTimeoutSeconds`.

Local models are slower and less precise than Gemini; start with small builds.

## Config highlights (`config/aiworldedit.json`)

- `model` – Gemini model name.
- `confirmBeforeBuild` – preview + `/aiconfirm` (set `false` to build immediately).
- `anchorAtCrosshair` – build where you look (`false` = at your feet).
- `ticksBetweenCommands` – raise to 10+ on servers where you aren't op, to avoid spam kicks.
- `fastSelections` – send `//pos1`/`//pos2` in the same tick as their edit (default true, about 3x faster builds).
- `usePlacementPos1` – centers shapes on `//pos1`. Turn off only if your WorldEdit lacks `//placement`.
- `maxComponents` – parts per plan (default 40). `maxCommands` – commands per build (default 20000; a composed medieval house is ~2,000-2,800).
- `maxSelectionVolume`, `maxShapeRadius`, `maxShapeHeight`, `maxBuildDistance`, `maxCommands` – safety limits.

## How it keeps builds safe

- Only an allowlist of WorldEdit commands is run (no clipboard, undo/regen, or vanilla commands).
- The validator tracks `//pos1`/`//pos2`, rejecting oversized selections, huge shapes, far-away coordinates,
  and `//stack`/`//move` without an explicit direction. Skipped commands are listed in chat.

## Notes

- `/aiundo` counts edit commands sent. If WorldEdit rejected one of them, `//undo N` may undo one of your own
  earlier edits too. Check with `//redo` if something extra disappears.
- `/aifix` hides the HUD for a few frames to take the screenshot.
