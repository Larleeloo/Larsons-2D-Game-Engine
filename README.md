# Larson's 2D Game Engine

A **generic** 2D game engine in pure Java. It provides a clean game loop
and the building blocks for any 2D game — sprite sheets, level loading,
cameras with multiple perspectives, scenes, input, a customizable menu
system, **online multiplayer** (host a server, friends join by IP + port,
Minecraft-style), and a **shader system** (GLSL-first post-processing with a
CPU fallback that runs anywhere) — without committing to a single genre.

The engine is built to be **a giant custom level loader**: you group levels
under a **game type** (a folder), and each **level** carries its own **format**
— side-scroller, top-down or isometric, each built in its own creative mode —
plus only the features it needs (zoom, framerate bounds, entity sizes, gravity,
HUD, …). The format and the toggles live on the level, so one game type can
hold a diverse mix of levels of all three kinds and they all play as one game;
the game type just supplies the default template new levels start from.
One engine drives many different games.

This engine is a **merge**: the minimal outline above, plus the feature
systems of its feature-rich sibling, **Side-Scroller-Game-Engine**, ported
over in a generic, data-driven form and wired to the same toggles:

- **Creative Mode** — a level editor for *painting objects into the world*
  (blocks, lights, mobs, items) with palette categories, drag-painting,
  erasing, pick-block, pan/zoom, play-testing, `Ctrl+Z` undo over **every**
  action it can take, and per-game-type level saving — in **three modes, one
  per level format** (side-scroller, top-down, isometric), each with its own
  palette, starter canvas and movement model.
  Works offline **and inside a multiplayer session**, where strokes replicate
  to every player. See [Creative mode](#creative-mode-paint-objects) and
  [The three level formats](#the-three-level-formats).
- **Blocks** — a data-driven [`BlockRegistry`](src/main/java/com/larsons/engine/world/BlockRegistry.java)
  (terrain, ores, decorations, light sources) with solidity, drops, and light
  emission; mining/placing in play mode with drops and particles.
- **Mobs** — a data-driven [`MobRegistry`](src/main/java/com/larsons/engine/entity/MobRegistry.java)
  of species driven by the ported AI state machine (IDLE → WANDER → CHASE →
  ATTACK → FLEE → DEAD).
- **Items & inventory** — a data-driven [`ItemRegistry`](src/main/java/com/larsons/engine/entity/ItemRegistry.java)
  with the original categories + rarity tiers, dropped items with bounce
  physics and pickup, and a fully interactive hotbar + grid inventory:
  click stacks to move/merge/swap them, drop items back into the world,
  eat food — all server-authoritative online.
- **Combat** — a full melee move set carried by whatever you are *holding*:
  swing, **parry**, **lunge**, **dash**, and a held **shield-ready** guard,
  each with its own wind-up → strike → recovery on that weapon's timings, its
  own animation state, and its own sound states. Mobs fight with the same
  machine and can carry real weapons; a held object can bring its own
  sprite sheets for the fighter using it (idle is always the fallback), so
  every item can animate its own combat. Plus the knockback, mob loot,
  health and respawn it always had. See
  [Melee combat](#melee-combat-swing-parry-lunge-dash-shield).
- **Projectiles & ranged weapons** — a data-driven
  [`ProjectileRegistry`](src/main/java/com/larsons/engine/entity/ProjectileRegistry.java)
  (arrows, rocks, throwing knives, magic bolts, exploding fireballs): bows
  consume arrows, throwables throw themselves, physical shots arc under
  gravity and land as recoverable drops, magic glows through the lighting
  shader pass. Simulated by the same `World` everywhere, so it all works
  online (server-side ammo, snapshot replication, impact FX broadcasts).
- **Lighting** — day/night cycle and point lights, implemented as a
  [`LightingPass`](src/main/java/com/larsons/engine/graphics/shader/LightingPass.java)
  in the GLSL-first shader chain, so it composes with every other effect.
- **Sound** — every action state in the game is a *sound key* you can
  supply: the player swimming, sprinting, landing and casting an ultimate;
  each block placed, broken, mined and walked on; each mob spawning,
  attacking and dying; each shot fired, in flight and landing; per-level
  music, ambience, doors, cutscenes and mini-game events. Audio comes from a
  **drop-in sound pack** of WAVs and MP3s beside the jar, and creative mode's
  **sound editor** lists every one of them. Every sound plays at a slightly
  different pitch each time, Minecraft-style, so repeats never sound
  mechanical. See [Sound](#sound-every-action-state).
- **Parallax backgrounds and particles** — procedural, keeping the engine
  asset-free and JDK-only; the same is true of sound, which falls back to
  synthesized effects and, beyond those, to silence.
- **Auto Battler** — a complete standalone game mode, its own option on the
  launch menu: an
  online auto-battler for **2-10 players** in the style of Dota Auto Chess /
  Teamfight Tactics, played on an **isometric** board with synergies, rounds,
  items, and units collected over the game — shops, a shared unit pool,
  3-copies-combine star-ups, an economy with interest and streaks, PvE creep
  rounds that drop item components, an **elemental damage layer**
  (attack elements, resistances, and weaknesses whose impact grows round
  over round), **synergy categories** with filterable UI, and deterministic
  server-simulated battles replicated to every client — presented with
  **replicated animation states**, per-unit **cartoony idle animations**,
  **animated projectiles**, melee/cast/death effects, a per-unit
  **damage meter** split by damage type, **board scouting** (click any
  player's name), **skinnable textures** (sprite-sheet overrides for
  units, items, projectiles, and the board), and **personal board
  customization** (color schemes, background images, decorative props). See
  [Auto Battler](#auto-battler-online-2-10-players).
- **Council of Six** — a second complete standalone game mode, its own option
  on the launch menu: an online **deckbuilding board game for 2-6 players**
  in the spirit of Dune Imperium and Inis, themed around the crew itself —
  play cards to place agents on board locations, buy from a shared market
  row, deploy troops for territory majorities, and win round-end conflicts,
  with six leader passives (Larson, Matt, Dustin, Kris, Bella, Eric), bots
  to fill seats, turn timers, and **shader-lit particle effects** (every
  table event bursts through the mode's bloom pass). Deliberately simpler
  than Magic. See [Council of Six](#council-of-six-deckbuilding-board-game-2-6-online).
- **Evolution** — a third complete standalone game mode, its own option on the
  launch menu: an **artificial life simulator** where organisms are strands of
  red/green/blue DNA that replicate imperfectly, express traits and shapes from
  hard-coded genetic rules, and are pruned by hunger, crowding, temperature and
  each other. You seed one square cell, feed the dish, and earn shop credit for
  every strand and colony combination that has never existed before, recorded in
  a reference book that ships empty on purpose. Discoveries are kept in two tiers, so the game can be **fully reset**
  whenever you like while your history of every organism ever found is kept
  forever. See [Evolution](#evolution-artificial-life-simulator).
- **Skins (texture overrides)** — drop PNG sprite sheets in
  `resources/skins/` and assign them in the lobby's **Customize Skins** menu:
  frame pixel width/height + frame count + a 0-120 fps playback rate, per
  texture (units get one per animation state). Saved to your game files and
  applied live. See [Skins](#skins-texture-overrides).
- **Texture packs** — or skip the menus entirely: a `textures/` folder next
  to the jar, with a subfolder per palette category and files named after the
  objects (`blocks/dirt.png`, `mobs/slime.png`), reskins the game on sight.
  A generated key list names every object for you, one universal frame
  size/count/fps covers the whole pack, and anything you don't supply keeps
  its built-in icon. Blocks get a **second pool for the plan-view
  perspectives** — `blocks_top/` and `blocks_side/` — because a side-scroller
  and a top-down level look at different faces of the same block. See
  [Texture packs](#texture-packs-drop-in-art).
- **Stacked blocks (top-down & isometric)** — the plan views build in **two
  layers**, and the stack is their geometry: bare ground is a hole, one layer
  is a pathway, two is a barrier. Blocks stack by themselves — place one on a
  cell that already has a block and it goes on top — and a stacked block is
  drawn standing off its own floor tile, **casting a shadow** in the direction
  the level's sun is set to, so height is something you can see rather than a
  colour you have to learn. It sorts against the players, mobs and scenery
  around it, so you pass behind the wall to your north and in front of the one
  to your south. See
  [Stacked blocks](#stacked-blocks-the-plan-views-geometry).
- **Share with friends** — launching from IntelliJ auto-builds a `share/`
  folder with a runnable jar, double-click launch scripts, online-play
  instructions (including your LAN address), and empty texture and sound
  packs to fill. See [Sharing the game](#sharing-the-game--how-joining-works).
- **Giant levels (up to 65536×65536)** — levels past 1024×1024 switch to
  sparse **chunked storage**
  ([`ChunkedTiles`](src/main/java/com/larsons/engine/level/ChunkedTiles.java)):
  only the chunks the camera/simulation actually touch are loaded, generated
  worlds build their chunks **lazily and deterministically** on first sight
  ([`ChunkGenerator`](src/main/java/com/larsons/engine/level/ChunkGenerator.java)),
  pristine chunks evict under memory pressure and regenerate identically, and
  saves keep only the edited chunks (RLE-compressed). The creative editor's
  **"override map size"** button unlocks its size sliders up to the full
  65536.
- **Full AABB block collisions** — movement resolves axis-separated sweeps
  against every block face: walls stop sideways movement, ceilings stop
  jumps, floors stop falls — for players *and* mobs, via shared helpers in
  [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java).
- **Advanced mob navigation** — mobs hop low walls and gaps while chasing,
  swim (buoyancy + surfacing) in liquids, refuse to walk into lava/acid/
  spikes, and **dodge incoming projectiles** aimed their way.
- **Block durability & tools** — every block has a hardness (seconds of
  hold-to-mine, with a growing crack overlay); pickaxes/axes/shovels in
  wood/stone/iron/diamond tiers break their matching block families faster.
- **Crafting & alchemy stations** — place a crafting table or alchemy
  station, stand next to it and press **E**: a recipe panel
  ([`CraftingPanel`](src/main/java/com/larsons/engine/ui/CraftingPanel.java))
  combines 1-3 ingredient stacks into new items
  ([`RecipeRegistry`](src/main/java/com/larsons/engine/crafting/RecipeRegistry.java)) —
  logs → planks → sticks → tools/weapons, ore smelting, potion brewing —
  so most of the catalog is reachable from resources found in the world.
- **Stamina & mana** — sprinting (Shift) and jumping spend stamina, magic
  staves cost mana, both regenerate and render as HUD bars.
- **Programmable stat rules** — the engine tracks per-run stats (blocks
  mined/placed, items picked up, distance traveled, jumps, kills, crafts…)
  in [`PlayerStats`](src/main/java/com/larsons/engine/sim/PlayerStats.java);
  map makers script triggers over them
  ([`StatRule`](src/main/java/com/larsons/engine/level/StatRule.java), saved
  with the level): *"mined 50 blocks → receive a potion"*, *"every 1000 px
  travelled → receive bread"*, *"holding ≥ 10 stone → consume 10 stone,
  receive an ingot"* — one-shot or repeating, optionally charted as live HUD
  progress bars.
- **Triggerable cutscenes** — map makers script cinematic sequences in the
  creative editor ([`Cutscene`](src/main/java/com/larsons/engine/level/Cutscene.java)):
  a trigger (walk into a zone, press **E** at a marker, or level start), a
  cast of sprite-sheet **actors with named animation states** (per-state
  sheet, frame size/count, 0-120 fps, loop or one-shot), and an ordered step
  script — show / say / move / switch animation state / wait / camera pan /
  hide. Cutscenes save with the level and play in play-test and play with
  letterbox bars, dialogue captions, and Enter/Esc skipping. See
  [Cutscenes](#cutscenes-triggerable-scripted-scenes).
- **Coloured rarity lighting** — uncommon+ items glow with a pulsing halo in
  their rarity tier's colour, and after dark they carry a real point light of
  that colour through the lighting pass.
- **Custom content ("+" entries)** — every creative palette category leads
  with a **+** icon that opens a fully-customizable property form
  (Hytale-style) for new blocks, liquids, lights, mobs, items, and
  decorations; creations persist per game type
  ([`CustomContentStore`](src/main/java/com/larsons/engine/config/CustomContentStore.java))
  and re-register on load so saved levels keep working. A new block is always
  asked whether it has a **top texture, a side texture, or both**, and told
  the exact files to draw for them.
- **Brush shapes** — square/circle/diamond/line/spray brushes up to 12 tiles
  across paint or erase many blocks per stroke
  ([`Brush`](src/main/java/com/larsons/engine/level/Brush.java)).
- **Surface decor** — per-face block details (tall grass tufts, hanging
  moss, twigs, icicles, cobwebs…) attach to a block's up/down/left/right
  face with toggles for *open/closed-face* visibility and
  *background/foreground* layering
  ([`SurfaceDecor`](src/main/java/com/larsons/engine/world/SurfaceDecor.java)).

Everything above **works online**: the authoritative server simulates the
world (mobs, items, drops, day/night), snapshots replicate entities, and
block edits broadcast to every client — including players who join later.
Every feature is a **toggle** carried by each level (game types just group
levels into a folder), exactly like the original engine's features.

> Author: Larson Sonderman

> **Where this is headed:** the engine is a work in progress on the way to a
> commercial release. **[`STEAM_PLAN.md`](STEAM_PLAN.md)** is the plan of
> record — an honest inventory of what's built and what isn't, the product
> strategy (a flagship game with real pixel art and sound, then the creation
> tool), the launch blockers (packaging, Steamworks, assets, window
> management), a phased roadmap, and the costs. Read it before planning work.

---

## Design goals

This engine was built against six explicit requirements:

| # | Requirement | How it's addressed |
|---|-------------|--------------------|
| 1 | **120 FPS** | A fixed-timestep [`GameLoop`](src/main/java/com/larsons/engine/core/GameLoop.java) renders with a configurable cap (default **120**). The limiter schedules frames on an absolute timeline and uses a hybrid coarse-sleep / fine-park wait, so the cap is hit precisely without pegging a CPU. |
| 2 | **Multiple 2D perspectives** | Three **distinct level formats** ([`LevelFormat`](src/main/java/com/larsons/engine/level/LevelFormat.java)) — side-scroller, top-down, isometric — each with its own creative mode, movement model and **number of block layers**, all loading and playing through the same code. [`Camera`](src/main/java/com/larsons/engine/graphics/Camera.java) + [`Perspective`](src/main/java/com/larsons/engine/graphics/Perspective.java) supply the projections (`SIDE_SCROLL`, `TOP_DOWN`, `ISOMETRIC`). A level's format is fixed for its lifetime — the three are different worlds, not three views of one — and a door into a level of another format is how a game changes perspective. |
| 3 | **Online play** | ✅ Implemented — see [Online play](#online-play). An authoritative [`GameServer`](src/main/java/com/larsons/engine/net/GameServer.java) ticks the same deterministic [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java) clients predict with; host in-game or run a headless dedicated server; friends join by IP + port like Minecraft Java edition. |
| 4 | **Out of the box on any Java machine** | The engine uses **only the JDK** (Java2D / AWT / Swing / sockets). No third-party runtime dependencies — JSON parsing, networking, and shader execution are all in-engine. |
| 5 | **Shader support** | ✅ Implemented — see [Shaders](#shaders). Every [`ShaderPass`](src/main/java/com/larsons/engine/graphics/shader/ShaderPass.java) is defined **GLSL-first** (real GPU fragment-shader source, exportable as `.frag` files) with a semantically identical multithreaded CPU fallback, so effects run everywhere today and on a GPU backend without porting. |
| 6 | **Editing outline of game essentials** | Working, minimal implementations of sprite sheets, level loading, and menu customization, wired together by the demo scenes. |
| ★ | **Feature toggles + game types** | Clickable toggles enable/disable features. Toggles are stored **per level** ([`Level.settings`](src/main/java/com/larsons/engine/level/Level.java)) so one game type can group diverse levels; the game type ([`GameProfile`](src/main/java/com/larsons/engine/config/GameProfile.java) under `resources/gametypes/`) is the folder + the template new levels inherit. **Load Level** picks an individual level and either plays it or edits its settings. |

---

## Requirements

- **Java 21+** (the only requirement to run).
- Gradle is **optional** — a wrapper is included (`./gradlew`), but you can also
  build with plain `javac`/`java` (see below).

## Running it

### With Gradle (recommended)

```bash
./gradlew run          # launch the demo (menu -> playable level)
./gradlew test         # run the headless smoke tests
./gradlew jar          # build build/libs/Larsons-2D-Game-Engine-0.1.0.jar
java -jar build/libs/Larsons-2D-Game-Engine-0.1.0.jar

# headless dedicated multiplayer server (see "Online play"):
./gradlew runServer --args="--port 7777 --level levels/sample_level.json"
```

### With just the JDK (no Gradle, no downloads)

```bash
# compile
find src/main/java -name '*.java' > sources.txt
javac -d out @sources.txt

# run (resources dir on the classpath so levels load)
java -cp "out:src/main/resources" com.larsons.engine.core.Main
```

On launch you'll choose or create a **game type** before playing — see
[Game types & feature toggles](#game-types--feature-toggles).

### Demo controls

- **Menus / forms:** arrow keys to move, **Left/Right** to adjust a value,
  **Enter** to activate, or use the mouse (hover + click the toggles/steppers).
  In the game-type editor, just type to set the name.
- **Level:** `WASD` / arrows to move, **Space** to jump, **+ / -** to zoom
  (if enabled), **Esc** to open the pause menu. **Space is the only jump key** — `W`/`Up` are *directions*:
  they stroke upward while swimming, climb while flying, and walk north in a
  top-down or isometric level, so holding one no longer bounces you off the
  ground. Jumping itself works in **all three formats**: gravity in
  side-scroll, and a **hop along the elevation axis** in top-down and isometric
  (you rise over your own shadow and land back down) — same key, same double
  jump, same stamina cost. Which of these are available depends on the active
  game type and the character you picked.
- **World interaction** (per the game type's toggles): **left-click** fires
  the held ranged weapon / throwable, else mines the aimed block, else swings
  at mobs; **right-click** places the selected hotbar block; **1-5** / mouse
  wheel select the hotbar slot; **Q** drops one of the selected item;
  **F** eats the selected food (a server request online); **R** fires your
  character's **ultimate ability** once its meter is full; **I** opens the
  inventory — click a stack to pick it up, click another slot to place/merge/
  swap it, click outside the panel to drop it into the world.
- **Melee moves** (whatever you are holding decides how they feel — see
  [Melee combat](#melee-combat-swing-parry-lunge-dash-shield)): **left-click**
  swings, **V** parries (catches a blow outright and turns shots around),
  **X** lunges, **Z** dashes, and **holding C** raises the guard.
- **Starting a level:** if its creator put more than one character on the
  level's roster, a **character picker** opens first — arrow keys or the
  mouse to choose, Enter to drop in (see
  [Characters, ultimates & directional animation](#characters-ultimates--directional-animation)).
- **Creative mode:** see [Creative mode](#creative-mode-paint-objects).
- **Multiplayer:** from the main menu, *Multiplayer (Host / Join)* — host a
  server on a port, or type a `host[:port]` address and join (see
  [Online play](#online-play)).

---

## Architecture

```
com.larsons.engine
├── core
│   ├── Main.java          Entry point; wires up the scenes + game context
│   ├── EngineConfig.java  Title, size, target FPS, update rate, perspective
│   ├── Engine.java        Wires window + renderer + shaders + input + scenes + loop
│   ├── GameWindow.java    JFrame hosting an AWT Canvas (BufferStrategy)
│   ├── GameLoop.java      Fixed-timestep loop, precise drift-free frame pacing
│   └── ShareJar.java      Auto-builds the shareable runnable jar + scripts on launch
├── config
│   ├── GameProfile.java   Feature toggles + values; the game-type template & each level's settings
│   ├── GameTypeStore.java List/load/save profiles under resources/gametypes/
│   └── GameContext.java   Active profile + net session; applies live & per-level settings
├── graphics
│   ├── Renderer.java      Backend abstraction (honours a ShaderChain)
│   ├── Java2DRenderer.java Default backend (double-buffered Canvas + post-FX)
│   ├── Camera.java        World→screen, per-perspective projection (+inverse)
│   ├── Perspective.java   SIDE_SCROLL | TOP_DOWN | ISOMETRIC
│   ├── TerrainPainter.java Terrain in as many layers as the format has: floor,
│   │                       cast shadows, stacked blocks queued into the depth pass
│   ├── DepthPass.java     Painter's queue for everything standing on the floor
│   ├── SpriteSheet.java   Slice a sheet into frames
│   ├── SpriteCanvas.java  The pixels behind "Create texture": frames, paint tools, undo, export
│   ├── Animation.java     Delta-timed frame animation
│   ├── AssetLoader.java   Cached image loading + placeholders
│   ├── CutscenePainter.java Cutscene actors (sheet frames + fallbacks) + letterbox/captions
│   ├── SkinDef.java       One texture override: sheet + frame w/h/count + 0-120 fps
│   ├── SkinStore.java     Persist skins.json under resources/skins/ (game files)
│   ├── Skins.java         Runtime resolver: assignment → texture pack → null (built-in art)
│   ├── TexturePack.java   Drop-in textures/ folder beside the jar; scaffold + config + lookup
│   ├── TextureKeys.java   Every skinnable object → its pack folder and file name
│   ├── EntitySprites.java Procedural mob/item/block sprites (no assets needed)
│   ├── ParallaxBackground.java Procedural multi-layer parallax backdrop
│   └── shader
│       ├── ShaderPass.java    One pass: GLSL 3.30 source + CPU implementation
│       ├── ShaderChain.java   Ordered passes, ping-pong buffers, uTime/uStrength
│       ├── Shaders.java       Built-in library + custom-pass helper + .frag export
│       ├── BloomPass.java     Multi-stage bloom (downsample → blur → composite)
│       ├── LightingPass.java  Day/night darkness + point lights (GLSL + CPU)
│       ├── PixelShader.java   Per-pixel base class for custom effects
│       ├── ParallelRows.java  All-cores row striping (the CPU's "fragment wave")
│       └── ShaderContext.java Per-frame uniform values (CPU mirror)
├── world
│   ├── Block.java         One block definition (colour, solidity, light, drops)
│   ├── BlockRegistry.java Data-driven block set with stable ids
│   └── World.java         Live world: level + mobs + items + projectiles + clock
├── entity
│   ├── MobDef.java / MobRegistry.java    Data-driven mob species
│   ├── Mob.java           The ported AI state machine + physics
│   ├── ItemDef.java / ItemRegistry.java  Items with categories + rarities
│   ├── ItemStack.java / Inventory.java   Hotbar-first stacked inventory (move/merge/swap/drop)
│   ├── DroppedItem.java   Items in the world (bounce physics, pickup)
│   ├── ProjectileDef.java / ProjectileRegistry.java  Data-driven projectile kinds
│   ├── Projectile.java    A shot in flight (arcs, hits, explosions, drops)
│   └── EntityView.java    Client-side view of a replicated entity
├── sim
│   ├── PlayerState.java   Position/velocity/health/flags — what snapshots carry
│   ├── PlayerInput.java   One tick's movement + attack intent — what clients send
│   └── PlayerPhysics.java The deterministic step shared by SP, prediction, server
├── net
│   ├── Lan.java           Site-local address discovery (the "join my IP" hint)
│   ├── Protocol.java      Newline-delimited compact-JSON wire protocol
│   ├── GameServer.java    Authoritative fixed-tick server + world (mobs, edits)
│   ├── GameClient.java    Dial host:port, send inputs/edits, receive snapshots
│   ├── Snapshot.java      One state broadcast: players + mobs + items + time
│   ├── NetSession.java    Active client + optional integrated server
│   └── ServerMain.java    Dedicated server entry point (--port/--level/--gametype)
├── input
│   └── InputManager.java  Polled keyboard/mouse (3 buttons + wheel) + typed text
├── scene
│   ├── Scene.java         update(dt,input) / render(g,alpha) lifecycle
│   ├── AbstractScene.java No-op base with viewport + manager refs
│   └── SceneManager.java  Named scenes + fade transitions
├── level
│   ├── Level.java         Tile grid (palette or block-registry mode) + spawns;
│   │                      two layers in the plan views — the stack is the geometry
│   ├── LevelFormat.java   The 3 level formats: side-scroller | top-down | isometric
│   ├── LevelLoader.java   Load a Level from JSON (or raw text, for the server)
│   ├── LevelStore.java    Per-game-type level saving + listing levels by format
│   ├── Cutscene.java      Cutscene data: trigger + actors (animation states) + steps
│   ├── CutscenePlayer.java Runs one cutscene's step script (headless)
│   └── CutsceneDirector.java Watches triggers per run, owns the active playback
├── audio
│   └── AudioManager.java  Synthesized sound effects (JDK only, headless-safe)
├── autobattler
│   ├── AnimState.java     Replicated unit animation states (idle/walk/attack/cast/hit/death)
│   ├── Trait.java         Synergy traits (origins + classes) with tiers + effects
│   ├── UnitDef.java / AutoUnits.java   The 28-unit roster, creeps, pool sizes, shop odds
│   ├── AutoItem.java / AutoItems.java  Item components + the full combination table
│   ├── UnitInstance.java  An owned unit: star level, items, bench/board position
│   ├── UnitPool.java      The shared pool shops draw from (scarcity)
│   ├── AutoPlayer.java    One player's life/economy/bench/board/shop state
│   ├── BattleSim.java     Deterministic 8x8 auto-battle (move/attack/mana/abilities)
│   ├── AutoGame.java      Rounds, phases, pairings, damage, elimination — the rules
│   ├── AutoBot.java       Server-side bot opponents (fill lobbies, solo play)
│   ├── AutoProto.java     The auto-battler's wire messages (own version + port)
│   ├── AutoServer.java    Authoritative server: lobby + 2-10 players + bots
│   ├── AutoClient.java    Client: typed replicated state + action senders
│   ├── AutoSession.java   Active client + optional integrated server
│   └── AutoSprites.java   Procedural unit figures / item gems (asset-free)
├── deckbuilder
│   ├── Leader.java        The six friends' leaders + their one-line passives
│   ├── LocationIcon.java / Territory.java   Icon families + the contested map
│   ├── CardDef.java / Cards.java            The card catalog (starters + market)
│   ├── LocationDef.java / Locations.java    The eight agent locations
│   ├── DeckPlayer.java    One player's piles, resources, troops, turn flags
│   ├── DeckGame.java      Rounds, turns, market, conflict, majorities — the rules
│   ├── DeckBot.java       Server-side bot opponents (fill seats, solo play)
│   ├── DeckProto.java     Council of Six's wire messages (own version + port)
│   ├── DeckServer.java    Authoritative server: lobby + 2-6 players + bots
│   ├── DeckClient.java    Client: typed replicated state + action senders
│   └── DeckSession.java   Active client + optional integrated server
├── fx
│   └── Particles.java     Pooled particles (block breaks, hits)
├── ui
│   ├── Menu.java          Keyboard/mouse menu (scroll bar when it overflows)
│   ├── MenuItem.java      Label (dynamic) + action
│   ├── MenuTheme.java     Colours, fonts, spacing
│   ├── ConfigForm.java    Clickable toggles / steppers / cyclers / text / buttons; draggable scroll bar
│   └── SpriteEditorPanel.java "Create texture": the paint window — tools, palette,
│                          frame strip, onion skin, live preview at the chosen fps
├── util
│   └── Json.java          Dependency-free JSON parser + writer (pretty + compact)
└── demo
    ├── StartupScene.java        Choose or create a game type
    ├── GameTypeEditorScene.java Name + configure a game type's default features
    ├── MainMenuScene.java       Per-game-type main menu (Play / Load Level / Rename Game Type / …)
    ├── LevelSelectScene.java    "Load Level": pick a level → Play or Edit Settings (rename + toggles)
    ├── MultiplayerScene.java    Host a server / join by host[:port]
    ├── PlayScene.java           Play with every enabled feature; doubles as MP client
    ├── CreativeScene.java       Creative mode: paint blocks/lights/mobs/items
    ├── AutoBattlerLobbyScene.java  Host/join an auto-battler + the pre-game lobby
    ├── AutoBattlerScene.java    The isometric auto-battler client (shop/board/combat)
    ├── DeckLobbyScene.java      Host/join Council of Six + the leader-pick lobby
    ├── DeckGameScene.java       The deckbuilder table (board/market/hand/particles)
    ├── AutoBattlerGuideScene.java  Illustrated field guide (rules/synergies/items/odds/units)
    ├── AutoHud.java             The auto-battler HUD's screen geometry (overlap-checked)
    ├── SkinEditorScene.java     The lobby's skin customization menu (sheet imports)
    └── ProfileForms.java        Shared feature options (game-type editor + Load Level's Edit Settings)
```

### The game loop

`GameLoop` separates **update** from **render**:

- **Update** runs at a fixed `updateRate` (default 120 Hz). Each step gets the
  same `dt = 1/updateRate`, so simulation is deterministic and frame-rate
  independent. Catch-up updates per frame are capped to avoid a "spiral of
  death" after a hitch.
- **Render** runs up to `targetFps` (default 120) and receives an interpolation
  `alpha` for smooth motion when the two rates differ.

This structure is what online play (requirement #3) is built on: the server
ticks the same fixed-step simulation clients predict with, so both sides agree
on results. The frame limiter schedules frames on an absolute timeline (each
deadline advances by exactly one frame period, so timing error can't
accumulate) and waits with a hybrid strategy — coarse `sleep` until ~2 ms
before the deadline, then short `parkNanos` slices — because a bare
`Thread.sleep` oversleeps by a scheduler quantum, which at 120 FPS (8.3 ms
frames) costs real frames.

### The three level formats

A level belongs to one of three **formats**
([`LevelFormat`](src/main/java/com/larsons/engine/level/LevelFormat.java)) — and
the format, not the game type, is what decides how it is built and how it
plays:

| Format | Projection | Up is | Movement | Palette |
|--------|-----------|-------|----------|---------|
| **Side-Scroller** | orthographic | up the screen | gravity: run, jump, swim, fall | everything except paths/walls |
| **Top-Down** | orthographic | out of the screen | walks the plane on both axes | everything, **plus paths & walls** |
| **Isometric** | diamond | along the screen's vertical | walks the plane on both axes | everything, **plus paths & walls** |

Each format has its **own creative mode** — the main menu's *Creative Mode*
entry picks which one to open, and the editor then paints, play-tests and
generates for that format. Playing is the opposite: a level simply loads in the
format it was built in, so a game type can hold side-scrolling caves, a
top-down overworld and an isometric town at once, and a **door between two
formats swaps the camera and the movement model mid-play** with no reload.

The format is saved in the level file (`"format": "isometric"`), so
[`LevelStore`](src/main/java/com/larsons/engine/level/LevelStore.java) can list
a game type's levels by format without loading them, and levels written before
formats existed load as side-scrollers.

**What actually differs.** Only the *path* and *wall* block families are
format-specific — they read as plan-view geometry, so they appear in the
top-down and isometric palettes only (a side-scrolling level that already
contains them still renders and collides with them). Everything else — blocks,
liquids, lights, mobs, items, decorations, block details, doors, cutscenes,
vehicles, mini games — is offered in all three and behaves in all three:

- **Gravity** is a side-scroller property. In the plan-view formats sand and
  gravel stay where they are placed, liquids pool outward in all four
  directions instead of pouring down, dropped items skid across the floor and
  settle (with a hover + shadow) instead of arcing, and vehicles steer both
  axes.
- **Mobs** run a platform-walker AI in side-scroll (jump smarts, swimming,
  fliers holding altitude) and a plan-view AI in top-down/isometric — every
  species, fliers included, wanders to 2D destinations and chases, flees and
  bursts along both axes, refusing to walk into hazards on *either* axis.
- **The player** walks the whole plane in top-down/isometric, with diagonals
  normalized (a diagonal isn't √2 faster than an axis) and sprint applying in
  every direction.
- **Which way is up** — every directional effect resolves against the format's
  own axes, not a side-scroller's screen. See
  [the three physical spaces](#the-three-physical-spaces-which-way-is-up).
- **Online**, the server simulates the *served level's* format, so hosting an
  isometric level moves everyone isometrically and client prediction agrees.

### The three physical spaces (which way is up)

A format is not a camera angle with the same physics behind it. Each one loads
a **space** of its own
([`PerspectiveSpace`](src/main/java/com/larsons/engine/sim/PerspectiveSpace.java)),
and that is the axis every directional thing in the engine asks before it
moves:

| | Side-Scroller | Top-Down | Isometric |
|---|---|---|---|
| The screen shows | the vertical plane | the floor, from above | the floor, in a diamond |
| Up points | up the screen | out of the screen, at you | oblique to the view |
| Gravity pulls along | world **+y** | the **elevation** axis | the **elevation** axis |
| Height is drawn as | *(no height axis)* | a lift **and a growth** — rising means coming nearer | a pure vertical lift, same size |

**Gravity does not switch off on a plane — it turns.** The pull is the same
strength in all three formats; only the axis changes. That is what makes a
top-down level feel like a floor you are standing on rather than a wall you
are pinned to.

What used to go wrong without this: anything with a direction was authored in
screen terms and replayed unchanged in every format, so "up" quietly meant
**north** in a top-down level and **north-west** in an isometric one. Meteors
called down from the sky spawned a screen's worth of pixels north of their
target and flew in sideways along the ground; embers drifted north instead of
rising; drips crawled south away from whatever they dripped off; a blast ring
tilted out of the floor it had just blasted. Now:

- **Sky strikes** (Meteor Staff, the Meteor Volley ultimate) spawn *above* the
  aim point on the elevation axis, ringed around it, and fall onto the tile you
  picked. While falling they are **over** the level — they clear walls and pass
  over heads — and they strike the instant they touch down. In a side-scroller
  they still arrive from up the screen, exactly as before.
- **Particles** spread across the *floor* (which the isometric camera projects
  into a diamond for free) and put their upward component on the elevation
  axis, so embers rise off the ground toward you, fountains go straight up,
  shards rain back down onto the floor, and a blast ring stays flat on it.
- **Knockback** follows the whole hit vector on a plane: a mob struck from the
  north is knocked *south*, not shoved east or west because that was the only
  axis a side-scroller had.
- **Thrown stacks** leave along the direction you are facing — all eight of
  them — instead of always due east or west.
- **The side-scroller is untouched.** It is the reference format, and every one
  of the above keeps its original motion there.

### Perspectives (the projection)

`Camera` maps world coordinates to the screen via a per-perspective projection,
then applies zoom and centering. Orthographic perspectives (`SIDE_SCROLL`,
`TOP_DOWN`) use an identity projection; `ISOMETRIC` projects a square grid into a
diamond. Because the projection is the only thing that changes, the *same*
tile/sprite drawing code renders correctly in every perspective — see
`PlayScene`, which simply projects each tile's four world corners. The editor
grid goes through the same projection, so isometric levels get a diamond
lattice to line blocks up against.

Rendering cost scales with the screen, not the level: `PlayScene` computes the
visible tile range by inverse-projecting the viewport corners
(`Camera.screenToWorld`) and only draws those tiles, so arbitrarily large
levels render at the same speed.

A level's perspective is **fixed for its lifetime**. There is no in-game
switch, because the three formats are not three views of one world: they differ
in which axis is up, in what a block *means*, and in how many layers of them a
level is written in, so there is nothing coherent for a mid-level switch to
show. Walking through a door into a level of another format is how a game
changes perspective, and that works mid-play with no reload.

### Stacked blocks (the plan views' geometry)

Top-down and isometric levels build in **two layers of blocks**, and the stack
is what their geometry means — see
[`TerrainPainter`](src/main/java/com/larsons/engine/graphics/TerrainPainter.java)
and [`Level.walkable`](src/main/java/com/larsons/engine/level/Level.java):

| Stack | What it is | Why |
|-------|-----------|-----|
| **Bare ground** | a hole — unwalkable | a plan view has no "down" to fall along, so a gap in the floor is simply somewhere you cannot go |
| **One layer** | a pathway to walk along | the block grid *is* the floor |
| **Two layers** | a barrier | the stacked block stands up out of the floor and reads as a wall |

This replaced the old arrangement, where a handful of `*_path` and `*_wall`
block families carried the plan views' geometry on their own. That is a thing
the camera cannot show: seen from above, a wall and the floor beside it are
both squares, and the only difference between them was a colour the player had
to learn. **Height** is the difference now, and a stacked block is drawn as
one — lifted off its own floor tile, showing the side face that lift exposes,
and casting a shadow onto the floor behind it. Every block builds either way,
so the creative palette hides nothing in any format.

Because a wall has height, it is not a layer painted over the actors but a
thing standing among them: raised blocks join the same
[`DepthPass`](src/main/java/com/larsons/engine/graphics/DepthPass.java) as the
trees, mobs, dropped items and players, queued at the screen row of their base.
Walking north behind a wall puts the wall in front of you; walking south past
it puts you in front of the wall — the same rule that already decided whether
you pass in front of a tree.

**Blocks stack by themselves.** Painting (in the editor) or placing (in play)
drops the block on the floor when the cell is bare and *on top of what is
already there* when it isn't — so building a wall is painting the same cell
twice, with no mode to arm first. Mining and erasing take the stack apart from
the top in the same way: wall → path → hole, one layer per click. Liquids pool
in the stacked layer, so a puddle lies *on* the floor rather than eating it.

The editor's cursor preview stands at the height the block would land at, so
hovering a stack outlines the top of it rather than the floor underneath, and
the mining-crack overlay rises onto the block being chipped.

**The light direction is the level's.** Tools → *Light Direction…* sets the
sun's bearing, and every stacked block throws its shadow away from it; the
level redraws live as the slider moves, and the bearing saves with the level
(`"lightAngle"`). One sun per level, because shadows that disagree stop reading
as light at all.

**A side-scroller has one layer and is untouched.** Its blocks are drawn
edge-on, so they already show their own height; solidity comes from the block
definition exactly as it always did. Plan-view levels written before blocks
stacked are converted on load
([`Level.liftSolidsToUpperLayer`](src/main/java/com/larsons/engine/level/Level.java)):
solid blocks become stacks of themselves, passable ones stay one layer, and
the air that used to be walkable corridor becomes floor — so an old level still
plays exactly as its author drew it.

### The world simulation

The systems merged from the Side-Scroller engine all hang off one class:
[`World`](src/main/java/com/larsons/engine/world/World.java) — a
[`Level`](src/main/java/com/larsons/engine/level/Level.java) plus the mobs,
dropped items, and day/night clock simulated inside it. Exactly one `World`
is authoritative at a time — the local one in single-player and creative
play-testing, the **server's** in multiplayer (clients render replicated
entity state) — so every mode runs the identical simulation code, the same
seam the player netcode was built on.

Content is **data-driven**: the Side-Scroller engine's 18-constant block
enum, 23 mob classes, 198 item classes, and projectile type table became rows in
[`BlockRegistry`](src/main/java/com/larsons/engine/world/BlockRegistry.java),
[`MobRegistry`](src/main/java/com/larsons/engine/entity/MobRegistry.java),
[`ItemRegistry`](src/main/java/com/larsons/engine/entity/ItemRegistry.java), and
[`ProjectileRegistry`](src/main/java/com/larsons/engine/entity/ProjectileRegistry.java).
Adding a block/mob/item/projectile is one `register(...)` call — no engine edits. Block
ids are stable contracts: they're what level grids store and what block
edits send over the wire. Light sources (torch, campfire, lantern, …) are
simply non-solid blocks with a light radius, so painting light is painting a
block, and it replicates online like any other tile.

Sprites are procedural
([`EntitySprites`](src/main/java/com/larsons/engine/graphics/EntitySprites.java))
so the engine stays asset-free; a game with real art draws its own images
per registry key instead.

### Projectiles & ranged weapons

The Side-Scroller engine's `ProjectileEntity` (arrows, bolts, fireballs,
thrown rocks/knives, explosions, trails) ported into the same data-driven,
simulation-seam shape as everything else:

- A [`ProjectileDef`](src/main/java/com/larsons/engine/entity/ProjectileDef.java)
  is a data row — speed, damage, a **gravity factor** (0 = straight-flying
  magic, ~0.35 = an arrow's arc, ~0.8 = a lobbed rock), collision radius,
  lifetime, an optional **explosion radius**, an optional **glow** (radius +
  colour fed to the lighting pass), an optional **trail colour**, and an
  optional **drop item** (physical shots land as recoverable pickups, exactly
  like the original's throwing-knife recovery).
- Items link to projectiles by key: `ItemDef.projectile` is what a
  **ranged weapon** fires (bows consume `ammo` — arrows — per shot; staves
  fire freely) or what a **throwable** becomes (it consumes itself).
  Skeletons drop arrows, so the ammo economy closes.
- [`World.playerShoot`](src/main/java/com/larsons/engine/world/World.java)
  resolves a shot from what the player holds, and `World.step` flies every
  projectile with the same deterministic rules everywhere: single-player,
  creative play-tests, and the authoritative server. Left-click fires when
  the held item shoots; otherwise it mines/swings as before.
- Hits use the combat toggle (with combat off, projectiles are decorative
  physics), explosions deal area damage with falloff, and every impact is an
  event — offline it feeds particles + sound directly; online the server
  broadcasts it as an `fx` message so **every client sees the hit**.
- **Shaders compose:** a fireball at night carries its own point light
  through [`LightingPass`](src/main/java/com/larsons/engine/graphics/shader/LightingPass.java)
  (and blooms, if bloom is on) — projectiles render inside the scene, so the
  whole post-FX chain applies to them like everything else.

### Melee combat (swing, parry, lunge, dash, shield)

The close-quarters half of the same seam. Whatever a fighter is holding
brings a set of **five moves** with it
([`MeleeAction`](src/main/java/com/larsons/engine/combat/MeleeAction.java)),
and the timings of those moves belong to the *object*, not to the engine — so
a dagger and a war hammer play completely differently out of the same
controls, and a mob issued a Battle Axe fights the way a player holding one
does.

| Move | What it is | Keys |
| --- | --- | --- |
| **Swing** | The plain attack: wind-up, strike, recovery | left-click |
| **Parry** | A short catching window — a blow that lands in it deals **nothing** and leaves the attacker staggered; shots in the air are **turned around and sent home** | `V` |
| **Lunge** | A committed thrust: you travel with it, and it lands harder | `X` |
| **Dash** | Evasive footwork, **untouchable** while it lasts | `Z` |
| **Shield ready** | A *held* guard stance that soaks a fraction of everything and slows you to a walk | hold `C` |

Every move runs the same three-phase shape on
[`MeleeState`](src/main/java/com/larsons/engine/combat/MeleeState.java) —
**wind-up → active → recovery** — which is what gives each weapon its weight:

```
WINDUP    committed, nothing has happened yet (the tell)
ACTIVE    the move does its work: the hit lands, the parry catches,
          the dash carries, the guard is up
RECOVER   the tail you are stuck in before you can act again
```

A telegraphed hammer blow can be stepped out of. A swing lands **exactly
once**, at the tick its hit window opens, however the frame rate wobbles.

**Weapons are data rows**, like everything else here. A
[`MeleeProfile`](src/main/java/com/larsons/engine/combat/MeleeProfile.java)
carries reach, arc width, knockback, what a raised guard soaks, and one
`Move` (wind-up / active / recovery / cooldown / stamina / damage scale /
burst speed) per action.
[`MeleeProfiles`](src/main/java/com/larsons/engine/combat/MeleeProfiles.java)
**derives** one for every item already in the game from what it plainly is —
`battle_axe` chops, `throwing_knife` flicks, `iron_spear` out-reaches
everything, `diamond_pickaxe` swings like the tool it is, a staff jabs with
its pommel, an apple is a fist with an apple in it — so the whole system
works on existing content and on custom items without anybody registering
anything. A game type that wants more can say "this fights like a spear"
(`MeleeProfiles.setStyle`) or hand-write a whole profile
(`MeleeProfiles.register`).

An action a profile *lacks* simply can't be performed: nobody fences with a
pickaxe. Nine
[`MeleeStyle`](src/main/java/com/larsons/engine/combat/MeleeStyle.java)
presets — fists, dagger, sword, axe, spear, hammer, shield, tool, staff —
cover the ladder, and rarity buys a slightly better guard, so a Tower Shield
stops more than a plank.

**Mobs fight with the same machine.** A species can carry a weapon
(`MobDef.weapon` — the Knight has an Iron Sword, the Orc a Battle Axe, the
Royal Guard a Tower Shield) and inherits its timings, its sounds, and its
art; a bare animal fights with claws sized to it. A mob's attack is now a
real wind-up rather than an instant subtraction, hostiles **lunge** to close
the last stretch, the dodge reflex reads as a **dash**, the guard species
raise a real shield, and an armed mob can **parry your swing** and leave you
reeling.

**Everything is one simulation.** Players and mobs run the same
`MeleeState`; strikes resolve through
[`World.meleeStrike`](src/main/java/com/larsons/engine/world/World.java);
incoming blows funnel through `PlayerState.takeBlow`, which is the single
place dash frames, parries and guards get their say. Online, the server runs
its own copy of every player's machine — cooldowns, stamina, wind-ups and
guards are all decided there, so a client can no more lunge on cooldown than
it can fabricate a shot — while the client predicts with the identical code
and the stance rides the snapshot, so other players see the swing and hear
the clang.

#### Custom art and sound per item

The point of the whole thing: **an object dresses the fighter holding it.**
Two independent sheets resolve, and both are optional
([`MeleeSprites`](src/main/java/com/larsons/engine/combat/MeleeSprites.java)):

```
wield/iron_sword/swing/e   the player swinging an Iron Sword, facing east
wield/iron_sword/swing     …whichever way they face
wield/iron_sword           …just holding it — the idle fallback
item/iron_sword/swing      the blade's own sheet, swept through the arc
item/iron_sword            the blade's icon — the idle fallback
```

`wield/` is a full-body sheet of *whoever* is holding the object — player,
character profile, or mob — so a Frostmourne can have a two-handed overhead
swing while a dagger flicks, on the same character. **Every chain ends at
idle**, which is what makes adding melee combat change how nothing looks
until somebody draws something: with no art at all, a swing draws the
character's existing idle sheet and the built-in procedural arc.

A move's sheet plays **once across the move**, not on a loop — so a slow
hammer and a quick dagger each get the whole strip in the time they take.

Sounds work the same way, most specific first
([`MeleeSounds`](src/main/java/com/larsons/engine/combat/MeleeSounds.java)):

```
item/iron_sword/swing        the blade's own sound, if the pack has one
character/rogue/swing        …else this character swinging anything
player/swing                 …else any player swinging anything
player/attack                …else the generic attack the engine always had
```

Ten sound states per fighter and per object (`swing`, `swing_hit`, `parry`,
`parry_success`, `lunge`, `lunge_hit`, `dash`, `shield_up`, `shield_block`,
`shield_down`), all defaulting to silence like everything else in the pack.
In creative mode, an item's texture dialog grows an **Action state** row
covering its icon, each move's own sheet, and each move's *wielder* sheet —
with the **Facing** row on top for the wielder ones.

### Rendering backend & shaders

All drawing goes through the `Renderer` interface. The default `Java2DRenderer`
uses a double-buffered AWT `Canvas`, which is why the engine runs anywhere a JRE
does (requirement #4). Every backend honours a `ShaderChain` of post-processing
passes — see [Shaders](#shaders) for how that satisfies requirement #5 today on
the CPU, and how the same passes run unmodified on a GPU backend. For full GPU
*scene* rendering the remaining porting work is a backend-neutral draw API,
since scenes currently draw with `Graphics2D`.

---

## Shaders

The shader system (requirement #5) is **GLSL-first**: every
[`ShaderPass`](src/main/java/com/larsons/engine/graphics/shader/ShaderPass.java)
carries a complete **GLSL 3.30 fragment shader** — the universal GPU shading
language (OpenGL directly; Vulkan/Metal via the standard SPIR-V translators;
WebGL after a mechanical downgrade) — plus a semantically identical CPU
implementation. The default backend executes the CPU side, multithreaded
across all cores in row stripes, which is what keeps requirement #4 intact:
shaders work out of the box on any Java machine, no native bindings. A GPU
backend gets real GPU execution by compiling each pass's `glsl()` with the
shared fullscreen-triangle vertex shader and binding four standard uniforms
(`uTexture`, `uResolution`, `uTime`, `uStrength`) plus per-pass extras from
`uniforms()` — no per-effect porting.

**Built-in library** (`Shaders`): grayscale, invert, color grading, vignette,
scanlines (CRT), pixelate, chromatic aberration, animated wave distortion, and
a proper multi-stage bloom (quarter-res bright pass → separable blur →
bilinear composite). The CPU loops are optimized — fixed-point arithmetic,
baked lookup maps for anything that doesn't change per frame (vignette
falloff, pixelate/chromatic sample maps), and zero per-pixel allocation — so
typical chains fit a 120 FPS budget on a desktop CPU. When no passes are
enabled the pipeline is skipped entirely and costs nothing.

**In the demo:** every level has shader toggles (master switch, global
strength, one toggle per effect) in the game-type editor (defaults) and in
Load Level → Edit Settings (per level), saved with the level. The *Export
shaders as GLSL* action writes
ready-to-compile `fullscreen.vert` + `<effect>.frag` files to `shaders/` —
drop them into any GLSL tool, engine, or your own OpenGL backend.

```java
// Programmatic use: the chain hangs off the engine.
engine.shaders().setPasses(List.of(Shaders.bloom(), Shaders.vignette()));
engine.shaders().setStrength(0.8f);

// A custom effect is one class: GLSL for GPUs + a per-pixel Java fallback.
ShaderPass warm = new PixelShader("warm",
        Shaders.fragmentShader("", """
                vec4 c = texture(uTexture, vTexCoord);
                fragColor = vec4(c.r * 1.08, c.g, c.b * 0.92, c.a);
            """)) {
    @Override protected int shade(int x, int y, int[] src, int w, int h, ShaderContext ctx) {
        int c = src[y * w + x];
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * 1.08));
        int b = (int) ((c & 0xFF) * 0.92);
        return 0xFF000000 | (r << 16) | (c & 0x0000FF00) | b;
    }
};
engine.shaders().add(warm);

// The GPU bridge: export everything as .frag/.vert files.
Shaders.writeGlsl(Shaders.allBuiltIns(), Path.of("shaders"));
```

Multi-stage effects (like `BloomPass`) implement `ShaderPass` directly and run
whatever internal stages they need, parallelized with `ParallelRows`.

**Lighting is a shader pass.** The Side-Scroller engine's lighting system
(day/night darkness with point-light cutouts) was ported as
[`LightingPass`](src/main/java/com/larsons/engine/graphics/shader/LightingPass.java):
GLSL-first like every pass, with a CPU fallback that computes the light field
at quarter resolution (the original's trick) and upsamples bilinearly. Scenes
feed it screen-space lights each frame — every light-emitting block on screen
plus a small player glow. It rides the same `ShaderChain` as the post-FX
(so bloom over torchlight Just Works) but has its **own toggle**, independent
of the post-FX master switch, and it deliberately ignores `uStrength`:
darkness *is* its strength. In multiplayer the time of day comes from server
snapshots, so night falls for everyone together.

---

## Creative mode (paint objects)

The Side-Scroller engine's built-in level editor, rebuilt on this engine's
camera/registry architecture. From the main menu choose **Creative Mode**
(or from the pause menu in a running game): a palette sidebar lists
everything the registries know, in categories —

| Category | Contents |
|----------|----------|
| Blocks   | every non-light, non-liquid block in `BlockRegistry` — 80+ of them: stone families, woods, bricks, ores, plants, hazards, crafting stations |
| Liquids  | water, lava, acid — real simulated liquids (see below) |
| Lights   | light-emitting blocks (torch, campfire, lantern, glowstone, neon…) |
| Mobs     | every species in `MobRegistry` |
| Items    | every item in `ItemRegistry`, sorted by rarity |
| Decor    | trees, rocks, bushes, crystals… painted into the background or foreground layer |
| Surface  | per-face block details (grass tufts, hanging moss, twigs, icicles, cobwebs…) with face / open-closed / layer toggles |
| Doors    | the game type's door list (external `doors.json`), each linking to another level |
| Characters | every playable [character profile](#characters-ultimates--directional-animation) — skins and traits you create with "+ New Character" — plus *Level Roster…*, which picks the ones **this** level offers at its start |
| Effects  | every particle style and projectile the game throws; click one to open its texture dialog (these aren't painted into the level, they belong to whatever throws them) |
| Sounds   | the [sound editor](#sound-every-action-state): *Sound Editor…* lists **every place the game makes a noise** (~2,000 of them, custom objects included) with what each currently plays, *Sound Options…* holds the volumes and the fresh-pitch toggle, *Level Music…* picks this level's track, and one entry per family opens the list filtered to it |
| Cutscenes | the level's scripted cutscenes — paint one to place its trigger marker; *Manage Cutscenes…* (or right-clicking an entry) opens the editor |
| Mini Game | the *Mini Game Setup…* window plus the objective markers the four team modes are built from: flag bases, stockpile crates, team spawns, escort waypoints |
| Tools    | player spawn, multiplayer spawn points, eraser, Brush Settings, the Generate button, **Light Direction…** (where the sun stands, and so which way stacked blocks throw their shadows), the Stat Rules editor, the Sound Editor |

Objects **you** created (via the "+" entries) wear a green corner badge in
the palette and say "· custom" in the caption, so they're obvious at a
glance — right-click one and the dialog offers **DELETE this custom
object** alongside its texture settings.

Every creatable category **leads with a "+" entry** — click it to define a
brand-new block/liquid/light/mob/item/decoration with fully customizable
properties (colours, solidity, light, damage, hardness/tool, AI stats,
rarity…). Creations are registered live, persist to the game type's
`custom.json`, and reload with it. The form finishes with **Create & draw
its texture…**, which makes the object and opens the
[sprite-sheet editor](#create-texture-draw-the-sprite-sheet-in-game) on it —
so a new object can be given its own art on the spot.

A new **block** is always asked one extra question: whether it comes with a
**top texture, a side texture, or both** — the faces a top-down or isometric
level sees that a side-scroller never does (see
[texture packs](#texture-packs-drop-in-art)). Whatever you answer, the form
names the exact files to draw, and a face you leave off falls back to the
block's flat sheet and then to its colour, so no answer leaves you with a
broken block.

**Editor controls:**

| Input | Function |
|-------|----------|
| Left click / drag | paint the selected entry (grid-snapped for blocks; drag keeps painting) |
| Right click (canvas) | erase — entities first, then **one layer** off the top of the block stack per click |
| Right click (palette icon) | that object's texture dialog: assign a sprite sheet, or **✎ Create texture** to draw one here |
| Middle click | pick the hovered block into the palette (the top of the stack) |
| WASD / arrows | pan the camera |
| Mouse wheel | zoom (over the canvas) / scroll the palette (over the sidebar) |
| Tab | next palette category |
| B | toggle the decoration layer (background / foreground) |
| [ / ] | shrink / grow the paint brush (shapes cycle in the sidebar's Brush row) |
| G | toggle the grid |
| P | play-test the level in place (terrain restored on exit) |
| Ctrl+Z | undo the last thing you did (a whole drag, or a whole window's worth of editing) |
| Ctrl+Y / Ctrl+Shift+Z | redo it |
| Ctrl+S / L / N | save / load / new level |
| Esc | back (with a save prompt offline) |

**Undo everything (`Ctrl+Z`).** Every action in creative mode can be taken
back, and put back again with `Ctrl+Y`. A history step is an *action*, not a
change: one brush drag comes back at once however many cells it covered, and
so does a window session, so nothing has to be walked back a cell or a field
at a time. The top bar names what `Ctrl+Z` will undo next and how many steps
are left behind it.

It covers the lot:

* **Painting and erasing** — blocks in either layer, brush shapes and
  multi-block mixes, surface details, and the things that follow a block when
  its cell is cleared (its stack, its details, a container's contents all come
  back with it).
* **Markers** — mobs, items, decorations, doors, multiplayer spawns, mini-game
  flags/stockpiles/spawns/waypoints (renumbered escort paths included), the
  player spawn, and cutscene triggers.
* **Level-shape edits** — live resizes (including the content a shrink
  dropped, and the dense grid a giant resize converted to chunks), and
  *New* / *Load* / *Generate*, which hand the level you were editing back
  exactly as it was.
* **Window sessions** — stat rules, cutscenes with their actors, animation
  states and step scripts, mini game setup, the character roster, the sun's
  bearing, the level's music track, and the game type's door directory.
* **The objects and their art** — everything "+ New …" creates and the palette
  deletes (unregistered/re-registered in `custom.json` and the live
  registries, keeping the same block id so painted levels still resolve), plus
  texture and sound assignments (`skins.json`, the pack's own exception
  files).

Three things it deliberately leaves alone. *Looking* is not an edit, so the
camera, grid, palette selection, brush settings and decoration layer are not
in the history. *Saving* is not an edit either: `Ctrl+Z` never un-writes a
level file, and never deletes a sheet drawn in the paint window — which has an
undo of its own for the drawing itself. And painting a **server's** world is
the server's to answer for, so undo is offline only; online it says so instead
of pretending.

**One creative mode per level format.** The main menu's *Creative Mode*
entry asks which format you are building — Side-Scroller, Top-Down or
Isometric (with how many levels of each the game type already holds) — and
the editor opens as that format's creative mode: its camera projection, its
starter canvas (a ground floor to land on, or a walled plan-view arena), its
palette, its generator default, and a play-test that moves under that
format's rules. Picking a format continues the game type's last level when
that level is in the same format, and starts a fresh canvas otherwise. The
*New Level* and *Generate* dialogs carry a **Format** row, so you can switch
modes in place without leaving the editor.

Painting itself works in **every format** — the palette paints through the
same `Camera` projection the game renders with, so building in isometric is
the same act as building flat, and the grid becomes a diamond lattice to line
blocks up against.

The **path** and **wall** block families are the one part of the palette that
is format-specific: they are plan-view geometry, so they appear while building
top-down and isometric levels and not while building side-scrollers. (A level
that already contains them keeps them — hiding a family from a palette never
changes a tile.) Everything else in the palette is shared by all three modes.

**Level size sliders.** The sidebar's bottom panel has live width/height
sliders: drag to resize the level in place — existing tiles are preserved,
the spawn is clamped back in, and out-of-bounds entities are dropped. The
**Override map size** button beneath them unlocks the sliders past
1024×1024 all the way to **65536×65536** (the scale turns exponential);
crossing 1024² converts the level to chunked storage transparently, and the
top bar starts reporting how many chunks are loaded/edited. The same
override appears in the *New Level* and *Generate* dialogs — a giant
generated world builds its terrain chunk-by-chunk as you pan over it.

**Brushes.** The Brush row above the size sliders picks a stroke shape
(square, circle, diamond, horizontal/vertical line, spray) and size (1-12
tiles, also `[` / `]`): one drag paints — or right-click erases — the whole
footprint, with a live preview under the cursor. **Brush Settings…** (Tools
palette) opens the full brush window: shape, size, and a **multi-block
mix** — name up to three extra block keys and every stroke scatters them
stably alongside the selected block, so one drag lays down varied terrain
(stone + granite + gravel, say) instead of a flat fill.

**Liquids flow.**
[`LiquidSim`](src/main/java/com/larsons/engine/world/LiquidSim.java) makes
painted water/lava/acid sources pour: liquid falls freely, spreads a
per-liquid range along floors via hidden `*_flow` blocks, and drains when
you remove the source or cut the stream. Water quenches lava into stone
(obsidian for sources), players swim (buoyant sink, hold up to stroke),
lava and other hazards burn, and on a server the flow broadcasts to every
client as authoritative block events.

**Doors reference an external list.**
[`DoorDirectory`](src/main/java/com/larsons/engine/level/DoorDirectory.java)
stores the game type's doors in `resources/levels/<game-type>/doors.json`;
each entry names a label, colour, and target level. *Manage Doors…* edits
the list; painting stamps a door into the level; walking into one and
pressing `E` (in play or play-test) loads its target level — retarget the
directory entry and every painted instance re-routes at once.

**Multiplayer spawn points** (Tools palette) are dealt out round-robin to
joining players by the server, and respawns use them too. Without any, the
single spawn marker is used, as before.

**Right-click textures.** Right-clicking a palette icon opens the texture
dialog for that object. Two ways to supply art, and the first needs no
setting at all:

- **Texture pack folder** (a per-object toggle, **on** by default) — the
  sheet is whatever sits at that object's file name inside the drop-in
  [texture pack](#texture-packs-drop-in-art) beside the jar. The dialog
  names the file it wants (`blocks/dirt.png`) and says whether it's there
  yet; click that row to rescan after adding sheets mid-session. Nothing
  there? The built-in icon stands, which is why the toggle is safe to leave
  on for everything.
- **A sheet elsewhere** — turn the pack off (or just fill in *Sheet
  elsewhere*, used as the pack's fallback) to point that one object at any
  image on disk.

A **block** picks which of its faces the sheet is for first — *flat* (the one
sheet a side-scroller draws), *top*, or *side* — so each plan-view face can be
assigned its own art here, exactly like a mob's action states. A face with no
sheet of its own falls back to the flat one, then to the built-in colour.

Either way you set frame size, count and fps (0 = static), per action state
for mobs (idle/walk/attack/hurt); frame settings for a pack texture are
saved into the pack's own `texturepack.json`, so the exception travels with
the folder. The assignment applies live everywhere that thing is drawn,
persists via the engine's `Skins`/`skins.json` system, and **the palette
swatch redraws with the new texture** — the sidebar always previews what
will land on the canvas. *Reset to defaults* puts an object back on the
pack with the procedural art as its fallback.

### Create texture (draw the sprite sheet in game)

There is a third way to supply art, and it needs no art program and no file
manager at all: **✎ Create texture** in that same dialog opens a **paint
window** over the editor
([`SpriteEditorPanel`](src/main/java/com/larsons/engine/ui/SpriteEditorPanel.java)).
It is offered for **every** object the palette can reskin — the blocks, mobs
and items that ship with the engine as much as the ones you made yourself —
and the "+ New …" form has its own **Create & draw its texture…** button, so
a custom object can go from "doesn't exist" to "has its own animated
sprite" without leaving the editor.

Open it on an object that already has a sheet and that sheet opens for
**editing**; open it on one that hasn't and you get a blank canvas at the
pack's frame size.

| Tool | Key | What it does |
|------|-----|--------------|
| Pencil | `B` | paint with the selected colour; drag to draw a stroke |
| Eraser | `E` | paint transparency (right-dragging erases with any tool selected) |
| Fill   | `G` | flood fill up to the colour boundary |
| Line   | `L` | drag a straight line, previewed until you let go |
| Rect   | `R` | drag a rectangle — outlined, or solid with the Outline/Solid toggle |
| Pick   | `I` | eyedropper: take a colour off the canvas |

A 40-swatch palette and R/G/B sliders pick the colour, `[` and `]` size the
brush, the wheel zooms, `Ctrl+Z`/`Ctrl+Y` undo and redo **whole strokes**
(not single pixels), and *− size / + size* changes the frame size itself,
keeping what is already drawn.

**Frame by frame, forward.** The strip along the bottom is the animation.
**+ Frame** adds a frame that starts as a *copy of the one you are on*, so
you draw only what moves — and the previous frame shows through underneath
as an **onion skin** while you do (`O` toggles it). **+ Blank** starts a
fresh frame instead, **Delete** removes one, and `,` / `.` step between
them. The **fps stepper** sets the playback rate (0 = a still image), and
the box on the right plays the animation at that rate as you draw it, so
the framerate is chosen by watching it rather than by guessing.

**Saving puts it in the texture pack.** *Save to texture pack* (or `Ctrl+S`)
writes the frames as one sheet, left to right, to **this object's own file
name** inside the [texture pack](#texture-packs-drop-in-art) —
`blocks/moon_rock.png` for a custom block, `mobs/slime_walk.png` for a mob's
walk cycle — creating the pack folder if this is the first texture anyone
made. The frame size, length and rate it was drawn at are recorded as that
texture's entry in `texturepack.json`, the object redraws with it
immediately (palette swatch included), and because the result is an ordinary
PNG in the pack folder it ships with the game, can be opened in a real paint
program later, and can be handed to someone else as part of a pack. `Esc`
backs out and writes nothing.

**Generate** (Tools palette) builds a level from Perlin noise
([`LevelGenerator`](src/main/java/com/larsons/engine/level/LevelGenerator.java)):
Minecraft-style fractal terrain, caves, depth-scaled ore veins, surface
lakes and a bottom lava ocean — fused with a Metroidvania network of carved
rooms and corridors (union-find guarantees everything connects, platform
ladders make vertical runs climbable), plus torches, decorations, treasure,
mobs, and multiplayer spawns. Same seed + size ⇒ the identical level.
Hill amplitude is capped in absolute tiles, so terrain rolls smoothly at
**any** map size instead of spiking into unclimbable mountains on tall
maps, and surface details (grass tufts, wildflowers, hanging moss,
dripstone) generate with the terrain automatically.

The Generate dialog also has a **Mode** switch: *Perlin terrain*, or
**Maze** — the automatic generator for the plan-view formats (it defaults to
Maze while building a top-down or isometric level, and to terrain for a
side-scroller). A seeded recursive-backtracker
maze is built from solid walls and walkable path floors, dressed with
torches at junctions, loot chests and mobs in the dead ends, multiplayer
spawns in the corners, and the gold key waiting in a chest at the cell
farthest from the entrance.

**Surface details** (Surface palette) attach to the face of an existing
block — click near the face you want (or pin one with the Face toggle).
The three option rows control the **face** (auto/up/down/left/right), the
**condition** (always · only while the face is *open*, i.e. not touching
another block · only while it's *closed*), and the **layer** (background,
behind the player, or foreground in front). Tall grass on soil, moss and
icicles under overhangs, twigs and shelf mushrooms on trunks — details
follow their host block and vanish when it's mined or covered.

**Stat rules** (Tools → Stat Rules…) are the map-maker scripting layer:
each rule watches a tracked stat (blocks mined/placed, items picked up,
distance traveled, jumps, kills, crafts, deaths, damage taken, shots
fired), and when it crosses the threshold it optionally **consumes** items
from the player's inventory and **grants** a reward — one-shot or repeating
every threshold step, with an optional live HUD progress bar. Rules save
with the level and run in play and play-test.

### Cutscenes (triggerable scripted scenes)

The CUTSCENES palette scripts **triggerable cutscenes** into the level. Each
cutscene has three parts, edited through *Manage Cutscenes…* (or the "+"
entry, or right-clicking a cutscene's palette icon):

- **A trigger** — *walk into it* (a zone the player enters), *press E at it*
  (an interaction marker), or *when the level starts*. Zone/interact
  cutscenes have a marker painted into the world (click the canvas with the
  cutscene's palette entry selected — repainting moves it, exactly like the
  spawn marker) and a trigger radius in tiles, drawn as a ring in the
  editor. *Play once per run* makes it a one-shot; re-triggerable zone
  cutscenes re-arm only after the player leaves the zone.
- **Actors** — the scene's cast. Each actor is a set of named **animation
  states for sprite sheets**: `idle`, `walk`, `talk`, or anything you like,
  each state its own sheet (path or *Browse…*), frame pixel width/height,
  frame count, a 0-120 fps playback rate, and a **loop** flag (off = a
  one-shot that holds its last frame — a wave, a collapse). The runtime
  plays `walk` automatically while an actor moves and `talk` while it
  speaks, falls back to `idle` for states an actor doesn't define, and an
  actor with no working sheet at all draws as a procedural stand-in figure,
  so a missing PNG never breaks the scene.
- **Steps** — the script, run in order: **SHOW** an actor at a tile
  (optionally in a named state) · **SAY** dialogue (a caption box with the
  speaker's name) · **MOVE** an actor to a tile over some seconds ·
  **ANIM** switch an actor's animation state (optionally holding) ·
  **WAIT** · **CAMERA** pan to a tile · **HIDE** an actor. A *Set X,Y to
  the camera center* button grabs coordinates from wherever you're looking.

During play-test (`P`) and play, [`CutsceneDirector`](src/main/java/com/larsons/engine/level/CutsceneDirector.java)
watches the triggers and [`CutscenePlayer`](src/main/java/com/larsons/engine/level/CutscenePlayer.java)
runs the script: the world holds still, letterbox bars ease in, the camera
follows the script, and **Enter/Esc skips** (every remaining effect still
applies, so skipping never strands actors mid-scene). Cutscenes serialize
with the level JSON, so they travel with saved levels like stat rules do.

**Play-testing** (`P`) drops a player at the spawn marker and simulates the
painted world with the real `PlayerPhysics`/mob/item code and the game
type's lighting — with a full working inventory: hold to mine blocks
against their durability (tools speed it up), pick up painted items, place
from the hotbar, sprint on stamina, cast on mana, craft at stations with
`E`, eat, shoot, take lava damage, and walk through doors (your inventory
carries across levels). The terrain is restored when you return to editing.

**Levels save into the game type** (the roadmap item):
[`LevelStore`](src/main/java/com/larsons/engine/level/LevelStore.java) writes
`resources/levels/<game-type>/<level>.json`. Saving snapshots the active feature
toggles into the level, so each level reloads with its own settings. The game
type remembers its last saved level — *Play Level* and *Host Server* run it,
while *Load Level* lists every level in the type so you can pick another.

**Online**, the editor opens from the pause menu and paints into the
<em>server's</em> world: strokes become protocol requests, the server
validates them against the host's feature toggles, and the authoritative
results broadcast to every player in real time (other players are visible
while you paint). Save/load/test stay offline-only features.

---

## Mini games (online team modes)

Any level can be turned into a competitive team game in creative mode: the
**Mini Game** palette's *Mini Game Setup…* window picks one of four modes and
its rules, and the palette's markers build the arena — all placeable
**anywhere on the map**, exactly like any other painted object. The setup
saves *inside the level* ([`MiniGameConfig`](src/main/java/com/larsons/engine/minigame/MiniGameConfig.java)),
so **hosting that level runs the game online** for everyone who joins, and
playing it offline referees the same rules locally for solo testing.

| Mode | Teams | The game |
|------|-------|----------|
| **Capture the Flag** | 2 | Steal the enemy flag (painted anywhere via the two *Flag Base* markers) and carry it home while your own flag is at its base. Dying drops the flag where you fell; the owning team can touch it to return it, or it flies home on its own after 25 s. First to the capture limit wins. |
| **Stockpile** | 2-4 | Teams race to bank resources at their *Stockpile* marker — walk into its ring and every configured resource item in your inventory deposits automatically. **Which item keys count is chosen in creative** (default: coal, iron ingot, gold ingot), and **PvP is a toggle**. First team to the resource limit wins. |
| **Battle** | 2-4 | Team deathmatch. Everyone spawns with a **magic-weapon loadout** (arcane staff, fire staff, sword, tools, bread); kills score for your team, first to the kill limit wins. PvP is always on. |
| **Escort** | 2 | Red escorts a payload cart along the painted waypoint path (*Escort Waypoint* markers auto-number themselves; #1 is the start); Blue stops them. The cart only rolls while an escort is beside it and no defender is in range — Overwatch rules. Reaching the last waypoint wins for Red; running out the clock wins for Blue. |

How it plays online: the server owns one
[`MiniGame`](src/main/java/com/larsons/engine/minigame/MiniGame.java) referee —
joiners are dealt onto the **smallest team**, spawn (and respawn) at their
team's painted *Team Spawn* markers, and every action resolves
server-side: melee swings and projectiles hit enemy players only when the
mode's **PvP rule** allows it (never teammates, never with PvP off), flag
pickups/captures and deposits happen where the server says the players are,
and kill credit follows the last attacker. State broadcasts ride alongside
snapshots as `mg` messages, driving every client's HUD — team score pills,
the escort progress bar + clock, your team banner, flags, the payload cart,
and team-coloured rings under every player. Announcements ("X took the Blue
flag!") reuse the ordinary server event feed. When a team wins, the winner
banner shows and the round **resets automatically** a few seconds later:
scores clear, objectives reset, and everyone respawns at their base.

Building checklist (creative): pick the mode in *Mini Game Setup…*, paint
the mode's markers (CTF: both flag bases · Stockpile: one crate per team ·
Escort: 2+ waypoints), optionally add per-team spawn points (every mode uses
them; without them teams fall back to their flag/stockpile/path ends), then
save and host. The setup window tells you what's still missing.

---

## Movement, worlds & storage updates

A batch of gameplay and editor refinements layered onto the systems above:

- **Double jump, always** — one mid-air jump is built into
  [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java);
  carrying a *Feather Charm* unlocks the triple, a *Sky Totem* two more, and
  the mythic *Wings of Icarus* jump forever (generated treasure rooms hide
  them). Swimmers now get a **water-exit jump**: stroking up with your head
  at the surface converts into a real jump that clears the pool's lip, and
  resting on the level's bottom edge counts as ground (both were traps that
  locked movement before).
- **The player is exactly 1×1 blocks** — `playerSize` locks to `tileSize`,
  so the player fits perfectly through one-tile gaps in every game type.
- **Three level formats, one game** — every level *is* a side-scroller, a
  top-down map, or an isometric one ([`LevelFormat`](src/main/java/com/larsons/engine/level/LevelFormat.java)),
  each with its own creative mode, and every level plays in the format it was
  built in — including through a door from one format straight into another,
  which swaps the camera and the movement model mid-play. The **path** and
  **wall** families paint only in the plan-view modes; everything else — mobs,
  items, blocks, decorations, lights, liquids, vehicles, cutscenes, mini games
  — is offered in all three and behaves in all three: mobs run format-specific
  AI (platform walkers with jump smarts in side-scroll, full-plane
  wander/chase/flee in top-down/iso), liquids pour down or pool outward,
  sand/gravel fall only under gravity, dropped items arc-and-bounce or
  scatter-and-hover with a shadow, the player's diagonals are normalized on the
  plane, and sprite-sheet block textures warp correctly into the isometric
  diamond instead of falling back to flat colours.
- **Chests & barrels are real storage** — stand next to one and press `E`:
  its second inventory opens ([`ContainerPanel`](src/main/java/com/larsons/engine/ui/ContainerPanel.java)),
  and the contents **save inside the level data** (`containers` in the level
  JSON). Mining the block spills what it held.
- **Tool durability** — tools carry a wear budget (`ItemDef.maxDurability`)
  and break completely when it runs out, with a green-to-red wear bar under
  the icon; the hotbar also names the selected item, and hovering a recipe
  at a crafting/alchemy station shows it in plain text
  ("2× Planks + 1× Stick → 4× Platform").
- **Food that feeds** — eating restores health directly, stamina alongside,
  and rare-or-better delicacies restore mana too (same rule offline and on
  the server).
- **Sand & gravel fall** — unsupported granular blocks drop cell-by-cell
  (custom blocks opt in with the "+ New Block" form's *falls* toggle), and
  **water can't be mined** — cover it with a block to displace it. Glass is
  now a solid, genuinely transparent pane.
- **Destructible decorations** — trees, rocks, bushes and such carry an
  optional hitbox: a few swings (an axe chops double) break them down into
  resources — logs + leaves for trees, stone for rocks…
- **Bigger mobs can actually reach you** — attack/detect ranges measure from
  the mob's body edge, not its top-left corner.
- **Softer feedback** — the hit/hurt effects are gentle sine thuds instead
  of the old alarming square-wave shrieks.
- **Texture pack folder** — a `textures/` folder beside the jar reskins the
  game by file name alone ([Texture packs](#texture-packs-drop-in-art)); the
  texture dialog toggles it per object, *Browse…* starts there, and bare
  sheet filenames resolve against it. Surface details (grass, spikes…) are
  sprite-sheet skinnable like everything else (`surface/<key>`), and the
  stat-rule editor's reward/consume fields grow **look-up cyclers** over the
  whole item catalog so nobody memorizes keys.

---

## The menagerie, the reliquary & the garage

A content expansion across three fronts — mobs, magic items, and rideable
vehicles — all simulated in the shared
[`World`](src/main/java/com/larsons/engine/world/World.java), so every one of
these behaves identically in single-player, the creative play-test, and on
the authoritative multiplayer server (clients render replicated snapshots;
none of it can be conjured client-side).

### Mobs with jobs

The roster nearly triples (~48 species), and species are no longer just stat
rows — [`MobDef`](src/main/java/com/larsons/engine/entity/MobDef.java) gained
`projectile` (ranged species open fire from their attack range) and an
`ability` (a per-species trick layered onto the shared AI state machine):

- **Marksmen** — *Skeleton Archer* (arrows), *Goblin Slinger* (rocks),
  *Dark Ranger* (knives) fight at range; their shots are mob-owned
  projectiles that never hit other mobs and hit players without any PvP rule,
  exactly like a melee strike. Mobs also stopped dodging their own side's
  volleys.
- **Elemental casters** — *Fire Imp* and *Pyromancer* burn, *Ice Witch*
  chills, *Storm Caller* chains lightning, *Venom Spitter* sickens,
  *Banshee* wails shadow, and the *Ancient Dragon* rains fireballs.
- **Ability specialists** — *Shadow Panther* **pounces** (LEAP), *Wild Boar*
  and *Sand Scorpion* **charge** with a rooted windup, *Shadow Wraith* /
  *Frost Revenant* / *Void Stalker* **blink** next to their prey (TELEPORT),
  *Necromancer* and *Spider Queen* **summon** minions, the *Giant Slime*
  **splits** into two slimes on death, the *Boomshroom* **detonates** on
  death (chains of exploders resolve as a proper chain reaction), *Troll*
  and *Treant* **regenerate**, the *Vampire* **lifesteals**, and *Stone
  Golem* / *Royal Guard* cycle a briefly-invulnerable **shield** stance
  (rendered as a glowing ring).
- **Wildlife** — *Yeti*, *Harpy*, *Griffin*, *Ember Wisp*, *Plague Rat*,
  *Turtle*, *Penguin*, *Firefly* round out the calmer corners.
- **Essence loot** — elemental species drop their school's essence
  (*Fire/Frost/Storm Essence*, *Venom Gland*, *Shadow Essence*, *Void
  Shard*), the alchemy reagents the new staves are brewed from; the phoenix
  drops its feather.

Elemental **statuses** live on the mob (`burn`/`chill`/`poison` timers plus
the shield flag), tick in its own deterministic step, and ride snapshots as
a status bitmask — so a burning zombie glows, sheds embers, and dies of its
burns on every client at once. Chilled mobs move at half speed; poisoned
ones drip.

### Relics & the elemental arsenal

The item catalog grows elemental staves, area weapons, and a shelf of relics
([`ItemRegistry`](src/main/java/com/larsons/engine/entity/ItemRegistry.java)):

- **Elemental staves** — *Ember Wand*, *Frost Staff*, *Storm Staff* (chains
  to a second target), *Venom Staff*, *Void Staff*; each fires its school's
  bolt with matching impact particles (embers float, ice shards rain,
  sparks snap, venom drips) via the styled particle system.
- **Explosives & AoE mining** — thrown *Bomb* / *Mega Bomb* and the *Meteor
  Staff* (a three-meteor salvo called down from the sky above your aim)
  explode for area damage **and shatter terrain**: `ProjectileDef.breakRadius`
  mines every block in the crater, popping drops, honouring the game type's
  block-editing toggle, and broadcasting each broken tile as an
  authoritative block event online. The *Harvest Staff* is the pacifist
  version — it shatters terrain into drops and harms no one.
- **The Warp Staff** — the completely-new one: its bolt deals a scratch, but
  wherever it lands, *the caster follows*. Aimed teleportation as a weapon
  slot, resolved server-side so it works (and can't be faked) online.
- **Relic passives** — carried anywhere in the inventory, applied each tick
  from the *server's* copy online (`Inventory.applyPassivesTo`): *Hermes
  Boots* (+35% speed), *Gravity Amulet* (slow fall), *Aether Wings* (hold
  jump to **fly**), *Magnet Charm* (4× pickup vacuum), *Power Gauntlet*
  (+6 melee) — joining the Feather Charm / Sky Totem / Wings of Icarus
  triple-to-infinite jump family, whose bonus now correctly applies on the
  server too.
- **Relic actives** — hold one and press `F`: the *Nova Crystal* detonates
  an arcane ring around you (30 mana), the *Tremor Totem* quakes the ground
  into drops (25 mana).
- **The Phoenix Feather** — dying consumes it and revives you *in place* at
  half health in a fountain of embers, instead of respawning. Works online:
  the feather burns out of your server-side inventory.
- **Scatter Bow** — fans three arrows per drawn arrow.

New particle styles (`FOUNTAIN` geysers, `IMPLODE` collapsing rings) join
the burst/ember/shard/spark/drip/ring/mote set, and every ability has wire
FX — blinks, summons, warps, novas, tremors, chain arcs, and revives all
broadcast as `fx` events so everyone sees the same fireworks.

### Vehicles & mounts

Rideables are a fourth replicated entity family
([`VehicleDef`](src/main/java/com/larsons/engine/entity/VehicleDef.java) /
[`Vehicle`](src/main/java/com/larsons/engine/entity/Vehicle.java) /
[`VehicleRegistry`](src/main/java/com/larsons/engine/entity/VehicleRegistry.java)),
obtained through the ordinary item economy: craft the item, press `F` to
deploy it, walk up and press `E` to ride, `E` again to dismount, and a swing
at the empty vehicle packs it back into its item so mounts are never lost.

- **Ground mounts** — *Horse* (fast, real jump), *Ostrich* (faster, huge
  jump), *Battle Boar* (rams mobs at speed for contact damage).
- **Fliers** — *Magic Carpet*, *Broomstick*, and the *War Dragon*, which
  breathes fireballs when its rider attacks (shots are rider-owned, so PvP
  rules and kill credit apply normally).
- **Boat** — floats up to the surface and skims across water, sluggish
  ashore.
- **Drill Machine** — the creative one: a tunneler that grinds through
  terrain it's driven into (hold *down* to dig a shaft), popping block drops
  and broadcasting every broken tile.

While mounted, your input drives the vehicle's own deterministic physics
(the same AABB collision players use) and you're locked to the saddle.
Online, the server validates mounting (`mount`/`dismount` messages — near
the vehicle, saddle free), simulates every vehicle, and replicates them in
snapshots (`veh`); the riding client *predicts* its vehicle with the same
step and blends toward the server state, exactly like player prediction, so
a gallop feels instant at any ping. Levels can also declare vehicles in
their entity lists (`{"kind":"vehicle","type":"horse",…}`).

---

## Characters, ultimates & directional animation

Who you play as, what they can do, and which way they are drawn — three
systems that arrived together, all built on the same seams as everything
else: created from the creative palette like a block, persisted with the game
type, and resolved in the one authoritative
[`World`](src/main/java/com/larsons/engine/world/World.java) so they behave
identically offline, in the play-test, and on the dedicated server.

### Directional animations

The direction a character faces picks the sprite that draws them
([`Facing`](src/main/java/com/larsons/engine/graphics/Facing.java)):

- **Side-scroll** — two directions. Facing right, the **right arm swings in
  front of the torso and the left behind it**; facing left, the reverse. It
  is one drawing and its mirror, which is exactly what makes the near arm
  stay near through a turn.
- **Top-down / isometric** — **all eight** compass points (E, NE, N, NW, W,
  SW, S, SE). Walk north and you are drawn from behind; walk south and you
  are looking at the camera; the diagonals are three-quarter views.

Mobs use the same system, so a slime chasing you north-east is drawn turned
away, not mirrored in profile.

**Every direction has pre-generated fallback art**
([`DirectionalSprites`](src/main/java/com/larsons/engine/graphics/DirectionalSprites.java)):
a four-frame walk cycle drawn per facing, with far limbs shaded behind the
body and near limbs in front, so a game with no art at all already reads as
directional. Supply real art whenever you like — the resolution runs from
most specific to least, and stops at the first sheet that exists:

```
player/walk/ne   ->  player/walk_ne.png   this facing's own sheet
player/walk/nw   ->  player/walk_ne.png   the eastern twin, drawn mirrored
player/walk      ->  player/walk.png      one sheet for every direction
player/idle      ->  player/idle.png      the state fallback chain
                                          … then the generated art
```

The same nesting works for mobs (`mobs/slime_walk_e.png` → `mobs/slime_walk`
→ `mobs/slime`) and for a character profile's own sheets
(`player/rogue_walk_ne.png`). In creative mode, right-click any of these and
the texture dialog grows a **Facing** row: leave it on *(every direction)* —
the normal case — or assign one compass point at a time.

### Character profiles

A **character profile** is a skin plus the traits that make someone feel
different to control
([`CharacterProfile`](src/main/java/com/larsons/engine/character/CharacterProfile.java)):

| Trait | What it does |
|-------|--------------|
| Body / skin colour | tints the generated directional art (real sheets override it entirely) |
| Speed | multiplies walk and sprint speed |
| Sprint | whether Shift sprints at all |
| Mid-air jumps | 1 is the classic double jump; 0 grounds them; up to 8 |
| Jump height | multiplies take-off velocity — side-scroll jumps and plan-view hops alike |
| Max health / mana / stamina | this character's own pools, not the engine's defaults |
| Ultimate | which signature ability they bring, and a switch to turn it off |

They are created **exactly like a custom block or mob**: the creative
palette's **Characters** category leads with a **"+ New Character"** button,
and the form edits every field above. Create saves it into the game type's
`characters.json` beside its levels
([`CharacterStore`](src/main/java/com/larsons/engine/character/CharacterStore.java))
and registers it live, so it is immediately paintable-adjacent in the palette,
right-clickable for its skin, and deletable from that same dialog.

**Each level decides which characters it offers.** The Characters palette's
*Level Roster…* window (and the same toggles on the *Load Level → Edit
Settings* screen) tick the profiles this level allows; the roster saves inside
the level file. When the level starts, a **character picker** shows a card per
profile — its sprite walking, its traits, its ultimate — and the one you
choose is applied to the simulated player: pools resized, speed and jumps
retuned, ultimate meter reset. A roster with one entry skips the picker; an
empty roster means *every* profile, so a level built before profiles existed
is never unplayable.

### Ultimate abilities

Each profile can carry one **ultimate**
([`Ultimates`](src/main/java/com/larsons/engine/character/Ultimates.java)),
charged the way an Overwatch ultimate is: a slow passive trickle plus a much
faster gain **per point of damage dealt**, so a player who fights earns theirs
long before a player who hides. A full meter fires once on **R**, spends
itself entirely, and — because every one of them is radial, aimed, or
self-targeted, with no assumption of a "down" — **plays the same in
side-scroll, top-down and isometric**.

| Ability | What it does |
|---------|--------------|
| **Overdrive** | Move half again as fast and hit twice as hard for 8 seconds — stamina never runs dry |
| **Nova Burst** | Detonate a ring of arcane force around you, damaging everything within four tiles |
| **Bulwark** | Shrug off 80% of incoming damage and regenerate steadily for 7 seconds |
| **Blink Strike** | Flash to where you are aiming, cutting down everything along the way |
| **Meteor Volley** | Call five meteors down onto the spot you are aiming at |
| **Time Dilation** | Freeze the tempo: everything within six tiles crawls for 6 seconds |
| **Life Siphon** | Drain the life out of everything near you for 5 seconds, healing yourself |
| **Earthshatter** | Slam the ground: a shockwave hurls enemies back and the terrain around you shatters |

The meter is a bar across the bottom of the HUD that pulses when it is ready
and counts down while a sustained ability runs. Resolution lives in
`World.useUltimate`, so online it is a request
(`{"t":"ult","x":…,"y":…}`) the **server** validates against its own copy of
your meter — nobody casts one they haven't earned. A game type with combat
switched off simply keeps the charge instead of burning it on nothing.

### Jumping in every perspective

In top-down and isometric levels the tile grid is the **floor**, so gravity has
moved off it onto the elevation axis and **Space** lifts you along that instead
([`PlayerState.z`](src/main/java/com/larsons/engine/sim/PlayerState.java)):
you rise, hang, and settle back down over your own **shadow**, which shrinks
as you climb. It is a real jump, not a decoration — steering keeps working
mid-air, the character's air jumps apply, it costs the same stamina, it feeds
the `jumps` stat rule, and while airborne you clear contact-damage tiles
(lava, spikes) exactly as a side-scroll jump does. The jump/fall animation
states play off it too, so a per-state sprite sheet animates a hop.

**Space is the jump key, and the only one, in all three formats.** `W`/`Up`
used to jump as well, which made them unusable as what they actually are — a
direction. They now only ever mean *up*: stroking toward the surface while
swimming, climbing while flying, walking north on a plane. Mounts follow the
same rule, so steering a flier no longer vaults it.

### Particle & projectile textures

Every particle style and every projectile is a **texture key of its own**, so
the effects layer is as reskinnable as the world:

```
particles/burst.png      particles/embers.png     particles/shards.png
particles/sparks.png     particles/drip.png       particles/ring.png
particles/motes.png      particles/fountain.png   particles/implode.png
projectiles/arrow.png    projectiles/fireball.png projectiles/ice_shard.png  …
```

Drop them in the [texture pack](#texture-packs-drop-in-art) folder and they
apply by name, or open the creative palette's **Effects** category and click
one for its texture dialog (frame size, count, fps, a sheet from anywhere on
disk). A skinned particle plays its sheet across the fleck's own lifetime and
fades out with it; an unskinned one keeps the engine's coloured fleck, and an
unskinned projectile keeps its procedural bolt — so **every effect has a
pre-generated fallback** and a missing file never breaks anything.

---

## Auto Battler (online, 2-10 players)

A complete standalone game inside the engine, launched straight from the
launch menu as its own option:
**Auto Battler (2-10 Online)** — no need to pick or create a game type first.
It plays like Dota Auto Chess / Teamfight
Tactics on the engine's **isometric** camera, and it is online-first: one
player hosts (from the lobby screen, exactly like hosting a world server),
everyone else joins by `ip:port` (default port **7788**). The host can add
**bots** to fill seats — so it's playable solo against bots, with 2 friends,
or with a full lobby of 10.

**The loop.** Each round has a **planning phase** (buy from your personal
shop, position units on your half of the 8x8 board, equip items) and a
**combat phase** — a fully automatic battle the server simulates and streams
to both players. Losing costs player HP scaled by the winner's surviving
units; at 0 HP you're eliminated, and the last player standing wins. Round 1
and every 5th round are **PvE creep rounds** whose victories drop **item
components**. With an odd number of players, one fights a **ghost copy** of
another player's board.

- **Units & shop:** a 43-unit roster across five cost tiers (1-5 gold) with
  TFT-style per-level rarity odds, rerolls (2g), and a **shared unit pool** —
  copies are finite, so contested picks really run out. Three copies of a
  unit combine into a 2-star (and three 2-stars into a 3-star). Every synergy
  has several carriers, so no single build path is forced.
- **Synergies:** every unit has an **origin** (Forest, Ember, Frost, Storm,
  Shadow, Holy, Wild, Mech, Mystic, Merchant) and a **class** (Warrior,
  Guardian, Archer, Mage, Assassin, Healer, Brawler). Fielding enough
  distinct units of a trait activates tiered team buffs — regen, attack
  damage, enemy slows, crit, team HP, team spell power, even bonus gold
  (Merchant). Breakpoint ladders are deliberately **varied** (2/4, 3/5,
  1/3/5, 2/4/6…) rather than one copied system, with super-linear tier
  values. The live synergy panel shows counts and thresholds.
- **Synergy categories:** every synergy belongs to one or more functional
  **categories** (Support, Damage, Tank, Healing, Shielding, Range, Crowd
  Control, Magic, Mobility, Economy, Utility), each with its own icon.
  The synergy panel and the field guide both **filter by category**, and
  **support synergies** (Holy, Guardian, Healer, Mystic) are flagged as
  team-enhancers rather than standalone archetypes.
- **Elemental damage:** a second strategic layer on top of synergies. Units
  can attack with up to two **elements** (Fire, Cryo, Corrosive, Explosive,
  Electric, Radiation), resist up to two, and be weak to up to two — many
  carry none. Damage into a weakness is amplified, into a resistance
  dampened, and the swing **grows round over round** (Borderlands-style), so
  scouting opponents and adapting your build matters more and more late.
  Radiation is the late-game element: only cost 4+ units carry it natively.
- **Items:** five components drop from creep rounds; any two combine into
  one of 15 named completed items (two components on the same unit fuse
  automatically), all pure stat bundles applied in combat. Creep rounds also
  occasionally drop **elemental relics**: infusion charms that add an attack
  element, a Radiation Core that converts a unit's attacks entirely, and a
  Prism Ward that grants resistance to every element.
- **Economy:** income = 5 base + interest (1 per 10 gold, max 5) + win/loss
  streak bonuses + 1 for a win. XP: +2 per round, buy 4 for 4 gold; your
  **level is your board cap** and shifts shop odds toward rarer units.
- **Abilities:** units build mana by attacking and being hit, then cast
  their class ability — fireballs (Mage, with splash), heals on the weakest
  ally (Healer), armor-ignoring strikes (Assassin, who also leaps to the
  backline at combat start), and so on.
- **Longer battles:** all hostile damage is globally rescaled down (heals
  untouched) and the combat cap raised, so fights build instead of ending in
  an instant, tank-oriented builds get to matter, and abilities actually come
  online before someone's board evaporates.
- **Combat presentation:** every combat snapshot carries each unit's
  **animation state** (idle / walk / attack / cast / hit / death), so units
  visibly do what the simulation says — walkers bob, attackers lunge, casters
  flare, the hit flinch, and the dead fall as fading corpses. Idle units play
  **exaggerated, cartoony personality animations** — every species bounces,
  breathes, sways, or wiggles in its own way (phase-shifted per unit, so a
  bench of triplets never moves in lockstep). Ranged attacks
  fly as **animated projectiles** (arrows, orbs, bolts by class) that deliver
  their damage number on impact; melee hits slash, and elemental hits colour
  their damage numbers by element. Combat events name their
  **source unit**, which is what makes attacker→target projectiles possible.
- **Damage meter:** during combat the left panel lists every unit in the
  fight with how much damage it has dealt, as a stacked bar split by type —
  **attack** (physical) vs **ability** (magic) — plus healing done, live from
  the replicated per-unit tallies.
- **Board scouting:** click any player's name in the standings to open their
  board in an overlay — their fielded units (stars + items), bench, and
  public stats (HP, level/XP, gold, streak, synergies). It refreshes while
  open, works while eliminated (spectating), and Esc / clicking outside
  closes it. Clicking another name switches to that player.
- **Skinnable:** every auto-battler texture — board tiles, unit figures (per
  animation state), item gems, projectiles — checks the [skin
  system](#skins-texture-overrides) first and falls back to the procedural
  art, so a sprite-sheet drop-in reskins the game with zero code. Both the
  skin menu and the board menu have an **Import… file browser** that copies
  a picked image into the skins folder and assigns it automatically — no
  paths to type.
- **Board customization:** the lobby's **Customize Board** menu personalises
  your board — six **color schemes** (tiles, backdrop, edge glow), an
  optional **background image** (imported via the file browser), and
  **decorative props** (plants, statues, lanterns, banners, mushrooms,
  crystals, fountains) in eight slots around the board's rim, with a live
  preview. All of it is **cosmetic only** — combat and replication never
  read it — and persists to `board_theme.json` in your game files.
- **Netcode:** the same authoritative model as the world game — a fixed-tick
  server owns every rule (purchases, combines, placement legality, the
  battles themselves), clients send action requests and render replicated
  state. Battles are **deterministic and seeded**, simulated only on the
  server; clients receive ~15 Hz combat snapshots (interpolated for smooth
  motion) plus event streams for damage numbers, particles, and sounds.
  Disconnected players' boards fight on; joins after the start are refused.
- **Shaders on:** the mode always runs with its own post-FX look (bloom +
  vignette through the engine's standard `ShaderChain`), independent of the
  active game type's toggles, and restores them on exit.

- **Field guide:** the lobby's **How to Play** button opens an illustrated,
  tabbed reference built from the same data the game runs on — the round
  loop and rules, the gold economy, every synergy trait (**filterable by
  category**, with role icons), the **elements** (who attacks with, resists,
  and fears each one, plus the round-scaling rules), the item recipe
  grid and elemental relics, the per-level shop odds, and the full unit
  roster with all of their statistics. Every icon (trait, element, item gem,
  odds cell, phase node, unit card)
  is clickable and pops a detail card with the fine print — per-tier effects,
  recipes, and star-scaled stats and abilities.

**Controls:** drag a unit between the bench and your half of the board (rows
nearest you) to place it — dropping on an occupied spot swaps them — and drag
an item gem onto a unit to equip it. A plain click still works as a fallback:
click a unit then a cell/slot to move it, or click an item gem then a unit to
equip. Right-click deselects or cancels a drag. Click shop cards to buy;
click a **player's name** to scout their board; **D** rerolls; **F** buys XP;
**S** (or the red button) sells the selected unit. Hover anything for a
tooltip. **Esc** closes the scout view, else opens the pause overlay (the
match keeps running online — **L** leaves it).

**On-screen text never overlaps.** All HUD geometry lives in one place
(`AutoHud`) — panels clamp their row counts to the space they actually have
("+N more…" past that), the economy readout stays clear of the bench, shop
cards shrink on narrow windows instead of running under the sell button —
and a layout test asserts every text region stays pairwise disjoint across
window sizes and worst-case fill (a full 12-item bench, 10 players).

Customization hooks are deliberately data-driven for what comes next: units,
traits, items, creep waves, pool sizes, and shop odds are all rows in
`AutoUnits` / `AutoItems` / `Trait`, and pacing/economy live in
`AutoGame.Config`.

---

## Council of Six (deckbuilding board game, 2-6 online)

A second complete standalone game on the launch menu:
**Council of Six (Deckbuilder, 2-6 Online)** — a deckbuilding board game in
the spirit of **Dune Imperium** (play a card → send an agent to a board
location, buy from a shared market row, commit might to a round-end
conflict) crossed with **Inis** (persistent troops on territories, strict
majorities score) — with the rulebook kept to one screen on purpose: no
stack, no instants, no priority. Fully online, same model as everything
else in the engine: one player hosts, friends join by `ip:port` (default
port **7799**), bots fill empty seats.

**The leaders are the crew.** Every player claims one of six leaders in the
lobby — **Larson** the Architect, **Matt** the Tactician, **Dustin** the
Spellslinger, **Kris** the Quartermaster, **Bella** the Envoy, and **Eric**
the Warlord — each with exactly one always-on passive (Larson starts every
round +1 gold; Matt's first troop deployment each round adds a bonus troop;
Dustin draws 6-card hands; Kris earns interest; Bella may place an agent on
an occupied location once per round; Eric commits +1 might to every
conflict). Picks are exclusive, first come first served; anyone still
unpicked at start (bots included) gets a random free leader.

**The loop.** First to **10 VP** at the end of a round wins (8 rounds max).
Each round everyone draws 5 cards and gets 2 agents; the first-player token
rotates every round. On your turn:

- **Play** a card: send an agent to a free location matching one of the
  card's icons (Economy / Knowledge / Military / Council); the location's
  and the card's effects both resolve, and your turn ends. Eight locations,
  two per icon — The Mines, Grand Bazaar, Library of Whispers, Alchemist's
  Tower, War Camp, Mercenary Hall, High Council (2 lore → 1 VP), The
  Crossroads — and an occupied spot blocks everyone else (worker-placement
  squeeze; Bella disagrees).
- **Reveal** your remaining hand instead: its reveal values become gold to
  spend and might committed to the conflict; you keep the turn to shop,
  then end it.
- **Buy** market cards with gold any time on your turn — a shared 5-card
  row that refills from a finite market deck; purchases land in your
  discard pile and shuffle back around, so your deck grows stronger every
  round (starter decks are 10 cards; the catalog runs from Spice Merchants
  and Druids to Sandworm Riders and the Dragon of the Peaks).

**Round end**: the most committed **might** wins the round's conflict prize
(the rewards grow round over round; second place takes a consolation; a
tied top splits it), and every territory where someone holds a **strict
troop majority** scores them a VP. Troops persist — presence built early
pays every round, exactly the Inis half of the recipe.

**Netcode**: the same authoritative model as the auto-battler — a
fixed-tick [`DeckServer`](src/main/java/com/larsons/engine/deckbuilder/DeckServer.java)
owns every rule in a headlessly-testable
[`DeckGame`](src/main/java/com/larsons/engine/deckbuilder/DeckGame.java),
clients ([`DeckClient`](src/main/java/com/larsons/engine/deckbuilder/DeckClient.java))
send action requests and render replicated state, turn timers keep the
table moving, leavers' seats auto-pass, and disconnected games play on.
Cards, locations, leaders, and territories are all data rows
([`Cards`](src/main/java/com/larsons/engine/deckbuilder/Cards.java) /
[`Locations`](src/main/java/com/larsons/engine/deckbuilder/Locations.java) /
[`Leader`](src/main/java/com/larsons/engine/deckbuilder/Leader.java)) —
adding a card is one `register(...)` line.

**Shaders + particles**: the mode always runs with its own post-FX look
(bloom + vignette through the engine's GLSL-first `ShaderChain`), and every
table event is presented as a styled [`Particles`](src/main/java/com/larsons/engine/fx/Particles.java)
burst tuned to read through that bloom pass — agent placements flare in
their location's icon colour, buys shower gold sparks, deployments blast
rings in the territory's colour, scored VP rings gold with lingering motes,
and a conflict victory fills the table with rising embers.

**Controls**: click a hand card, then a highlighted location (troop plays
ask which territory); click market cards to buy; **R** reveals, **E** ends
the turn, right-click cancels, **H** opens the one-screen rulebook, **Esc**
pauses (the table keeps playing online).

---

## Evolution (artificial life simulator)

A third complete standalone game on the launch menu: **Evolution (Artificial
Life Simulator)** — a Petri dish you scan like a microscope slide, full of
organisms that are nothing but **strands of coloured DNA**. Every rule for
reading that DNA is hard-coded and deterministic; *which* strands ever exist is
not. The game ships with an empty reference book, and the whole point is
finding out what the rules can produce.

### DNA is the animal

An organism is a sequence of **red**, **green** and **blue** nucleotides —
literally three colours of pixel — and everything about it is decoded from
that strand by
[`Phenotype`](src/main/java/com/larsons/engine/evolution/Phenotype.java):

- **Red encodes hostility**, **blue encodes altruism**. Each is simply its
  share of the strand, so a cell's colour (the average of its nucleotides) is a
  direct read-out of what it does — pure red hunts, pure blue cooperates, an
  even mix reads white.
- **Green is a wild card read by slot.** A green in slot *s* expresses
  `Trait.forSlot(s)` with magnitude *s*: slot 1 is speed, slot 2 consumption
  rate, **slot 3 is light emission**, slot 4 heat tolerance, 5 digestive
  efficiency, 6 memory, 7 vision, 8 pattern recognition — and the wheel repeats
  every eight slots, so a longer strand reaches the same traits at higher
  magnitudes.
- **The nucleotide after a green modifies it**: a following **red doubles** the
  magnitude, a following **blue halves** it but refines digestion, a following
  **green adds one** and chains on. (A green in slot 3 is light emission of 3;
  followed by red it is 6.)
- **Every adjacent pair also unlocks an ability**, so the same letters read
  twice — once for magnitude, once for capability:

  | Pair | Ability | Pair | Ability | Pair | Ability |
  |---|---|---|---|---|---|
  | `RR` | predation | `GR` | *(doubles the green)* | `BR` | kin defence |
  | `RG` | exothermy | `GG` | complex digestion | `BG` | broadcast |
  | `RB` | venom | `GB` | endothermy | `BB` | sharing |

- **Two abilities need a long strand**, the "eventually unlockable" traits:
  **tool use** (a `GRG` motif at slot 9 or later, in a strand that also
  recognises patterns) and **multicellularity** (`BBB` in a strand of 14+).
- **The tail of the strand governs copying fidelity** — greens near the end
  make replication sloppier, so offspring variability is itself under
  selection — and green content over the whole strand sets how fast a body
  rots back into food after death.
- **Shape follows what a strand commits to**, then feeds back as a modifier.
  Everything starts as the primordial **square** and only differentiates at six
  nucleotides: triangle (hunter), circle (altruist), star (light emitter),
  hexagon (colonist), diamond (tool user), cross (complex digester), pentagon
  (scout).

### The dish does the selecting

[`Dish`](src/main/java/com/larsons/engine/evolution/Dish.java) is pure
simulation — no drawing, no scene state, so the whole ecology runs headlessly
in tests at any speed. Every rule is local, and the interesting behaviour
(blooms, crashes, predator/prey cycles, colonies) is emergent rather than
scripted:

- **Scarcity.** Energy is finite. Corpses and digested waste recycle back into
  orbs — strictly energy-neutral, nothing minted or lost — so a dish is a
  closed loop a well-adapted population can ride indefinitely and a greedy one
  drains and starves in.
- **Crowding.** Upkeep rises with local density, so a bloom eats its own margin.
- **Temperature.** A diffusing heat field, pushed around by your sources and by
  the dish's own exothermic and endothermic strands; cells outside their
  evolved comfort band burn extra energy.
- **Predation**, with **pattern recognition** as the counter — prey that can
  read a hunter's colours run from it.
- **Light.** The slide has unlit patches, and sight range scales with how well
  lit a spot is, so bioluminescence and the spotlight genuinely change what the
  cells around them can find — the radius that lights the gel *is* the radius
  the simulation forages with. The light field is drawn in **world space**
  rather than through the engine's screen-space
  [`LightingPass`](src/main/java/com/larsons/engine/graphics/shader/LightingPass.java):
  that pass dims the finished frame, HUD and all, and the instrument panel has
  to stay readable. A gentle bloom from the shader chain is what makes the glow
  bloom.
- **Death**, from starvation or old age, so nothing stagnates.

**You can see what cells are doing.** Hunting, sharing, signalling, tool
pickups and decomposition are otherwise silent, so each throws a colour-coded
ring into the gel: a strike flashes red, a donation pulses blue at whoever
received it, a broadcast expands to its real earshot, a body handing energy
back rings the orb it just produced — which is what makes the recycling loop
legible instead of orbs appearing next to a corpse from nowhere. Cells carrying
a tool wear it as a ring in the tool's colour, and a hunter shows a notch while
its strike is off cooldown.

Neighbour lookups go through a uniform spatial grid rebuilt each tick, so a
full dish (260 organisms, 1500 orbs) costs about **1.2 ms per tick** — well
inside the 8.3 ms budget at 120 Hz.

### The game around it

You start exactly as the design calls for: **one dish, one square organism** of
whichever colour you pick, and **100 energy orbs** to place by hand.

**The colour is a difficulty choice.** Green's wild cards give it speed,
appetite and light from the first second and it is the easy opening; blue runs
frugally and shares, which keeps a colony alive; red pays the upkeep of a
predation it has nothing to hunt yet, and its only early edge is that hostility
feeds aggressively. Averaged over 40 seeded runs of a kept-fed dish, a red lab
catalogues roughly a fifth of what a green one does over the same fifteen
minutes and about three quarters of what blue does — hardest to use, but not a
dead end.

- **Credit for novelty.** Every strand that has never existed before is
  catalogued and paid for, scaled to its complexity — as is every new **colony
  combination** multicellular strands invent between themselves. Breeding
  complexity is what funds the lab.
- **The shop** sells food (simple and complex energy), life (starter colonies,
  more dishes, transfer spatulas, cell tool kits), environment (barrier
  pillars, exothermic sources, endothermic sinks, spotlights, mutagen vials)
  and three permanent **instruments**: the thermometer (temperature overlay),
  the **DNA catalog scanner** (reads a cell's exact strand instead of guessing)
  and the time warp dial (0.25× to 8×).
- **Cell tools** are dropped *for the organisms* — only a strand that evolved
  tool use can pick up a flagellum, scalpel, sieve or lantern, each with a
  limited number of uses that pass to whoever picks it up next when the carrier
  dies.
- **The run ends** when every dish has run out of life and there is no way left
  to reseed one.
- **The game can be fully reset** at any time: **Reset the lab** in the pause
  menu, or simply **New Experiment** from the front menu, which are the same
  thing — the front menu offers it once rather than as two rows that read
  differently and behave identically. Everything goes — dishes, bench,
  instruments, credits, and the game catalog itself — leaving the opening state
  with every strand to find again and every credit to re-earn. What a reset
  never touches is your **history**: every organism you have ever discovered
  stays on the permanent record, along with your achievements and lifetime
  totals. Resetting costs you the lab, never the collection.

### The reference book: this game, and everything you have ever found

Discoveries are kept in **two tiers**, which is what makes a full reset safe:

- **Game Catalog** — what the *current* game has discovered, and what it has
  been paid for. A reset empties this, because a reset is a real restart.
- **History** — every organism you have **ever** discovered, in any game, with
  the lifetime totals above it: organisms, colony combinations, games played,
  credits ever earned, shapes and abilities seen, the deepest lineage, and the
  longest and most complex strands you have produced. **Nothing is ever removed
  from here** — not by a reset, not by a new game, not by deleting the save.
  Achievements live at this tier too, so a reset never takes one back.

A strand pays when it is new *to the current game*, so a fresh game can
rediscover and be paid again; the history still records each organism exactly
once, and tells you at a glance whether the strand you are looking at is a
first-ever find or a rediscovery.

Nothing ships with the game: the book contains exactly what your dishes have
produced, and an entry decodes all the way back down to its traits and
abilities — because a strand *is* its own description. There are **24
achievements** for the finds worth bragging about (first predator,
bioluminescence, tool use, multicellularity, all eight body shapes, a strand at
the 48-nucleotide maximum, …).

On disk there are two files. `evolution/save.json` is the current game (dishes
and everything in them, the bench, the credit balance and the game catalog),
written on exit, from the pause menu, and automatically every 90 seconds.
`evolution/history.json` is the permanent record: every organism ever
discovered, the colony combinations, the achievements and the lifetime totals.
Folders of per-organism files from older builds (`evolution/history/`, and
`evolution/catalog/` from older still) are folded into it the first time it is
read, so a collection from an earlier build carries over.

**How the save stays small.** A dish holds up to 260 cells and 1500 orbs, and a
lab holds a shelf of dishes, so the parts of the save there are thousands of are
written as *packed blocks* rather than a JSON object per item: the field names
are written once for the whole list in a `format` line, each item is one line of
values, every cell's DNA is hoisted into a per-dish `strands` dictionary (a
bloom of clones therefore writes its sequence once, not once per body), numbers
are rounded to what the simulation can actually tell apart — a hundredth of a
dish unit — and fields still carrying their default are dropped off the end of
the row. A full dish drops from **299 KB to 59 KB**, and the file is still plain
JSON you can open and read:

```json
"organisms": {
  "format": "id strand x y vx vy energy age generation colony venom memory(x,y)...",
  "strands": ["GGGG", "GRGGGRRGB"],
  "rows": ["17 0 13.34 12.16 1.59 -25.74 26.92 94.97 2"]
}
```

**How the collection stays small.** The history is the one thing here that only
ever grows, and it used to be one JSON file per discovery — ~450 bytes each, of
which two thirds was decoded traits, and each one still costing a whole 4 KB
disk block. A collection of 410 organisms came to 1.7 MB on disk to hold about
16 KB of facts, and opening the book meant reading 410 files.

What a record actually has to keep is what the strand does not already say. The
shape, the colour, the traits, the abilities and the complexity are a pure
function of the DNA — recomputed on load, and never read back off disk even when
they were written there — and so are the species name and the credit it paid. So
a discovery is now one row of `dna at dish generation credit name` in
`history.json`, with the dish names in a dictionary beside it and the derived
fields written only in the odd case where they disagree with the rules:

```json
"species": {
  "format": "dna at dish generation credit name",
  "dishes": ["Dish 1", "Dish 2"],
  "rows": ["BBBBRR 1785009893 0 9", "BBBR 1785009899 1"]
}
```

Those same 410 organisms are **13.9 KB in one file** — 7% of the bytes and 1% of
the disk — and opening the book reads one file instead of 411 (9 ms → 6 ms warm
at this size, and the gap widens with every discovery). Nothing is lost: the
entry still decodes to everything it ever showed. When you want one organism as
a standalone artefact — to look at, to keep, to send someone — the store writes
it out fully decoded on demand (`EvolutionStore.exportSpecies`), which is the
readable-artefact idea aimed at the organism you care about rather than at all
several thousand of them on every save.

Saves and histories written by earlier builds still load: every packed list also
accepts the older array-of-objects form, and both older history layouts are
folded in and then cleared away — but only after the merged collection has been
written safely to `history.json`, and only for the files that were read
successfully.

### Controls

Keys `1`-`9` and `0` pick a tool (energy, complex energy, starter colony,
barrier, heat, cold, spotlight, mutagen, tool kit, spatula); `I` or `` ` ``
goes back to inspecting. **Left-click** uses the held tool, **right-drag** (or
WASD/arrows) pans the stage, the **wheel** zooms around the cursor. **B** opens
the shop, **K** the reference book, **Tab** switches dish, **T** toggles the
thermometer overlay, **[** and **]** work the time warp, **H** explains the
genetics, **Esc** pauses (and offers the full game reset).

---

## Skins (texture overrides)

Every game texture is overridable with your own art, without touching code:

1. Drop PNG **sprite sheets** anywhere under
   [`resources/skins/`](src/main/resources/skins/) (`units/`, `items/`,
   `projectiles/`, `boards/` are provided as a starting layout, kept in the
   repo with `.gitkeep`).
2. In the Auto Battler lobby, open **Customize Skins** and pick a target:
   any **unit** (per **animation state** — idle, walk, attack, cast, hit,
   death; a unit with only an idle skin uses it everywhere), any **item**,
   a **projectile** kind (arrow / orb / bolt), or the **board tiles**.
3. Define the sheet import: **frame pixel width**, **frame pixel height**,
   **frame count** (sliced left-to-right, top-to-bottom), and a **framerate
   from 0 to 120** sprite frames per second (0 = static image).
4. **Apply + Save** — it takes effect live, with a preview right in the menu.

Assignments persist to `resources/skins/skins.json` — part of *your* game
files ([`SkinStore`](src/main/java/com/larsons/engine/graphics/SkinStore.java)),
loaded on every launch, and bundled into the [share jar](#sharing-the-game--how-joining-works)
so friends see your skins too. Anything without a (working) skin keeps the
engine's procedural art — a bad path never breaks the game. The runtime side
is [`Skins`](src/main/java/com/larsons/engine/graphics/Skins.java): game code
asks for a texture key's frame at a point in time and draws the fallback when
it gets `null`; the key table is documented in
[`resources/skins/README.md`](src/main/resources/skins/README.md).

### Texture packs (drop-in art)

Assigning sheets one at a time is the *precise* route. The **texture pack**
is the bulk one: a `textures/` folder **next to the jar**, filled with
correctly-named PNGs, reskins the game with **no menu visit at all**
([`TexturePack`](src/main/java/com/larsons/engine/graphics/TexturePack.java)).
Launching from IntelliJ scaffolds an empty one inside `share/`, so it ships
with the game you hand a friend:

```
share/textures/
├── texturepack.json     universal frame size / count / fps  (+ per-texture overrides)
├── TEXTURE_KEYS.txt     every object's file name and texture key (generated)
├── README.txt
├── blocks/       dirt.png · stone.png · …        (what a side-scroller draws)
├── blocks_top/   dirt.png · …   the face a plan view looks *down* at
├── blocks_side/  dirt.png · …   the face a stacked block turns to the camera
├── liquids/    water.png · lava.png · …
├── lights/     torch.png · lantern.png · …
├── mobs/       slime.png (all states) · slime_walk.png (one state) · slime_walk_e.png (one facing)
├── items/      iron_sword.png · iron_sword_swing.png (the blade sweeping a melee move)
├── wield/      iron_sword.png (holding it) · iron_sword_swing_e.png (swinging it, facing east)
├── player/     idle.png · walk_ne.png (one facing) · rogue_walk.png (a character profile)
├── particles/  embers.png · sparks.png · shards.png · …
├── projectiles/ arrow.png · fireball.png · …
├── decor/  ·  block_decor/  ·  lights/  ·  units/  ·  board/
```

**Subfolders are palette categories, files are objects.** A sheet is picked
up purely by where it sits and what it's called — `blocks/dirt.png` is the
Dirt block, `mobs/slime.png` is the Slime in every animation state (add
`_walk` for one state only, and `_walk_e` for one state in one **facing** —
see [directional animations](#directional-animations)), `player/idle.png` is
the player standing still, `particles/embers.png` is the ember particle.
The generated `TEXTURE_KEYS.txt` lists **every object in the game** with the
exact file name and texture key to use, including custom content you created
yourself — so nobody has to guess or memorize a key. PNG is preferred; GIF
and JPG load too.

**Blocks have a second pool, for the plan-view perspectives.** A side-scroller
and a top-down or isometric level look at *different faces* of the same block,
and one sheet cannot be both a wall seen edge-on and a floor seen from above.
So `blocks_top/` supplies the face a plan view looks down at (floors, and the
lid of a [stacked block](#stacked-blocks-the-plan-views-geometry)) and
`blocks_side/` the face a stacked block turns toward the camera, which is what
gives a wall its height. Both are optional and independent: a block with no top
or side sheet falls back to its `blocks/` sheet, and with none of the three to
its procedural colour, so a pack can dress one format, both, or neither.
**"+ New Block" always asks** which faces your block has and names the exact
files to draw for them.

**One spec for the whole pack.** Every sheet plays at the universal settings
in `texturepack.json` — **32×32 frames, 3 frames, 3 fps** — so a pack is
drawn to a single target (the default sheet is one 96×32 image, sliced
left-to-right). Any single texture can depart from that via the `overrides`
block, or from the creative texture dialog, which writes the override back
into the pack.

**Art drawn in game lands here.** The creative editor's
[Create texture](#create-texture-draw-the-sprite-sheet-in-game) window saves
the sheet it painted into this folder under the object's own file name, with
its frame size/length/rate written into `texturepack.json` — so a texture
drawn in game is the same kind of thing as one dropped in by hand, and
travels with the pack.

**Always safe to leave on.** The pack is consulted for every texture key by
default, and a key with no file in it keeps its built-in procedural icon —
so a pack can be one file or a thousand. Per object, the texture dialog can
switch the pack off or point at [a sheet somewhere
else](#creative-mode-paint-objects) instead. A game type that wants its pack
kept elsewhere sets *Texture pack folder* in that dialog; blank — the normal
case — means "beside the jar", which is what makes a shared game just work.

---

## Sound (every action state)

Sound works exactly like [texture packs](#texture-packs-drop-in-art), and for
the same reason: a creator should be able to give their game a voice by
**dropping files in a folder**, without visiting a menu or writing a line of
code. The difference is that sound is *silent by default* — every one of the
game's sound keys makes no noise at all until you supply audio for it.

### Sound keys: object + action state

A **sound key** names an object and something it does
([`SoundKeys`](src/main/java/com/larsons/engine/audio/SoundKeys.java)):

```
player/jump                       the player jumping
player/swim                       swimming (repeats while you swim)
player/run                        sprinting footsteps
player/ult_activate               firing an ultimate
character/rogue/hurt              the Rogue's own cry (falls back to player/hurt)
block/dirt/break                  breaking Dirt
block/dirt/step                   walking on Dirt
block/water/splash                falling into Water
mob/slime/attack                  the Slime lunging
mob/slime/death                   the Slime dying
mob/royal_guard/shield_up         the Royal Guard bracing behind its shield
item/iron_sword/use               drawing the Iron Sword
item/iron_sword/swing             …and cutting the air with it
item/iron_sword/swing_hit         …and landing it
item/tower_shield/parry_success   the clang of a blow caught on a guard
projectile/meteor/fire            a meteor being called down
projectile/meteor/flight          the meteor falling (repeats until it lands)
projectile/meteor/impact          the meteor crashing
ultimate/meteor_volley/activate   casting the volley
music/level  ·  music/boss        the level's music
ambient/night  ·  door/open  ·  minigame/victory  ·  ui/click
```

The engine currently names **~2,000 of these**, because every object in every
registry gets the full set of action states for its kind — and that includes
the blocks, mobs, items, decorations and characters you create with the
palette's **"+ New …"** buttons, which register into the same registries the
catalogue reads. Make a new mob and it arrives with `spawn`, `idle`, `step`,
`attack`, `hurt` and `death` — plus the ten
[melee-move states](#melee-combat-swing-parry-lunge-dash-shield) every
fighter and every held object has — waiting for audio.

Keys fall back one segment at a time, exactly like texture keys, so
`mobs/slime.wav` alone gives a Slime one voice for everything it does, and
adding `mobs/slime_death.wav` beside it takes over just for dying. The engine
also asks for the *specific* sound before the general one: a footstep tries
`block/stone/step`, then `character/rogue/walk`, then `player/walk`, and goes
quiet if you supplied none of them.

### Sound packs (drop-in audio)

A `sounds/` folder **next to the jar**, filled with correctly-named WAVs and
MP3s, gives the game its voice with no menu visit
([`SoundPack`](src/main/java/com/larsons/engine/audio/SoundPack.java)).
Launching from IntelliJ scaffolds an empty one inside `share/`, beside the
texture pack, so it ships with the game you hand a friend:

```
share/sounds/
├── soundpack.json    volume · pitch · pitch drift  (+ per-sound overrides)
├── SOUND_KEYS.txt    every sound in the game and the file to name it (generated)
├── README.txt
├── player/       jump.wav · swim.wav · run.wav · ult_activate.wav · parry.wav · …
├── blocks/       dirt_break.wav · stone_place.wav · …
├── liquids/      water_splash.wav · lava_ambient.wav · …
├── mobs/         slime.wav (everything it does) · slime_death.wav (just dying)
├── items/        iron_sword_use.wav · iron_sword_swing.wav (a melee move) · …
├── projectiles/  meteor_fire.wav · meteor_flight.wav · meteor_impact.wav
├── ultimates/    meteor_volley_activate.wav · nova_burst_impact.wav · …
├── music/        level.mp3 · boss.mp3 · menu.mp3 · …
├── lights/  ·  decor/  ·  block_decor/  ·  vehicles/  ·  particles/
├── ui/  ·  world/  ·  ambient/  ·  doors/  ·  cutscenes/  ·  minigame/
```

**WAV and MP3 both load.** WAV, AIFF and AU go through the JDK; MP3 goes
through the engine's own decoder
([`Mp3Decoder`](src/main/java/com/larsons/engine/audio/Mp3Decoder.java)) — a
complete MPEG-1/2/2.5 Layer III decoder in pure Java, ported from the
public-domain [minimp3](https://github.com/lieff/minimp3) (CC0), because the
JDK has no MP3 support and the engine ships with **no third-party jars** by
design. A file whose contents disagree with its extension is retried the
other way, so a `.wav` that is really an MP3 still plays.

**Everything defaults to silence.** A sound key with no file makes no noise.
The one exception is the handful of actions the engine has always had a
synthesized voice for — placing and breaking blocks, hitting a mob, picking
an item up, jumping, firing, exploding, menu clicks — which keep theirs, so
adding two thousand new sound slots doesn't silence a game that already made
noise ([`SoundSynth`](src/main/java/com/larsons/engine/audio/SoundSynth.java)).
Each of those is still a normal sound key, so a pack overrides it like
anything else, and the editor can switch the built-in off to make the action
genuinely silent.

### Fresh pitch (the Minecraft trick)

**Every sound plays at a slightly different pitch each time** — ±8% by
default, drawn per playback. It is the reason a run of footsteps or a burst
of block-breaking sounds alive instead of like a stuck record, and it is the
same thing Minecraft does as you move around the world.

It is a **toggle** in the sound editor (*Fresh pitch each time*), with a
slider for the spread (0–50%), and it is saved with the game type and in the
pack's `soundpack.json` so the feel travels with the folder. Music is never
pitch-varied — a wandering soundtrack is a bug, not an effect — and any
individual sound can opt out.

Pitch is possible at all because the engine mixes sound in software
([`SoundMixer`](src/main/java/com/larsons/engine/audio/SoundMixer.java)):
voices are resampled as they play, so any number of sounds overlap, each at
its own pitch, volume and stereo position, with music looping underneath. On
a machine with no audio device — CI, a container, a dedicated server — the
mixer disables itself silently and every call is a no-op, so gameplay code
never has to ask whether sound exists.

### The sound editor (creative mode)

Creative mode has a **SOUNDS** palette category. It opens **the whole list**:
every place the game makes a noise, what that sound currently resolves to,
and the dialog to change it.

```
SOUNDS palette
├── Sound Editor…        every sound in the game, one row each
├── Sound Options…       master/effects/music volume · fresh pitch · pack folder ·
│                     whether this machine has an audio device at all
├── Level Music…         which music/… track this level plays
└── Player Sounds… · Blocks… · Mobs… · Items… · Ultimate abilities… ·
    Projectiles… · Vehicles… · Music… · Ambience… · Mini games… · …
    (one entry per family, opening the list filtered to it)
```

Each row reads `Slime — attack · pack: mobs/slime_attack.wav`, or
`· silent`, or `· built-in`. A **Show sounds** filter narrows the list to
*only the silent ones* (what still needs audio) or *only the ones with
audio*. Clicking a row opens that one sound:

- **Use sound pack folder** — on by default; this folder supplies the audio.
- **Pack file: `player/swim.wav` ✓ found** / *(not there yet — click to
  rescan)* — the exact file to create, and whether it is there yet. Clicking
  rescans, for files added while the game is running.
- **Sound file elsewhere** + **Browse…** — point this one sound at any WAV
  or MP3 on disk instead.
- **Volume**, **Pitch**, **Loop while the state holds**, **Fresh pitch each
  time**, and **Built-in fallback** (for the keys that have one).
- **▶ Preview** plays it as the dialog currently stands.

Volume/pitch/loop are written into the pack's own `soundpack.json`, so those
exceptions travel with the folder; the pack switch and any explicit path go
to `sounds.json` beside `skins.json`. *Rewrite SOUND_KEYS.txt* regenerates
the key list against the objects that exist right now, including everything
you just created.

### What actually makes noise

Triggers are wired through the level loader — the play scene and creative
mode's **play-test**, so a level under test sounds exactly like the level
being played. One-shot events fire where they happen; anything that has to be
watched frame to frame lives in
[`SceneSounds`](src/main/java/com/larsons/engine/audio/SceneSounds.java):

| What | Sounds |
| --- | --- |
| **Player** | footsteps timed to the gait (walk/run/swim), jump, double jump, landing, splash going in and out of water, sprint start, hurt, death, respawn, mining scrape, break, place, chop, pickup, drop, eat, drink, craft, teleport, door entry, mount/dismount |
| **Ultimates** | the meter filling (`charged`), the cast (`activate`), a sustained ability's hum (`loop`), what it does where it lands (`impact`), and the effect ending (`end`) |
| **Meteors** | the volley being cast, each meteor's `flight` looping as it falls, and its `impact`/`explode` where it lands — three separate sounds for one ability |
| **Blocks** | place, break, the scrape while mining, footsteps per block underfoot, hits; liquids add splash, swim, flow and a lapping ambience |
| **Mobs** | spawn, idle murmurs, footsteps as they close, the lunge into an attack, hurt, death — positioned and faded by distance so a horde off-screen is a murmur |
| **Items** | use, equip, pickup, drop, craft, and a tool breaking |
| **Projectiles** | fire, flight, impact, explode — per projectile type |
| **World** | level load/save/generate, daybreak, nightfall, chests, crafting stations, stat rules firing, explosions |
| **Music** | per level (`Level Music…`), plus menu, creative, combat, boss, victory and defeat tracks |
| **Everything else** | doors, cutscenes, mini-game scoring and rounds, vehicles, particles, block decorations, and the interface |

Levels store their own music track, so a boss arena can ask for `music/boss`
while the caves next door play `music/level` — the track travels with the
level like its other settings do.

---

## Sharing the game & how joining works

**Launching the game from IntelliJ automatically builds a shareable copy**
in `share/`, in the background, on every launch
([`ShareJar`](src/main/java/com/larsons/engine/core/ShareJar.java) — skipped
when nothing changed):

```
share/
├── larsons-2d-game-engine.jar   # the whole game: java -jar, Java 21+, no deps
├── run.bat                      # double-click launcher (Windows)
├── run.sh                       # double-click launcher (Mac/Linux)
├── HOW_TO_PLAY_ONLINE.txt       # hosting/joining instructions + your LAN IP
├── textures/                    # drop-in texture pack (see Texture packs)
└── sounds/                      # drop-in sound pack   (see Sound)
```

**This only happens inside IntelliJ.** The share folder is a development
convenience, so a shipped game never writes a copy of itself beside wherever
the player put it. `ShareJar` looks for two kinds of evidence:

- **Direct launch markers** — IntelliJ's own `idea.*` system properties, the
  `idea_rt` helper on the classpath or attached as an agent, its
  `com.intellij.rt` launcher, the `IDEA_*` environment and its built-in
  terminal. These cover an *Application* run configuration.
- **The project checkout** — a `.idea/` folder in the working directory
  *and* the game running from **class files rather than a jar**. This one
  matters because IntelliJ's default for a Gradle project is "build and run
  using Gradle": the game is forked into a fresh JVM by the Gradle daemon,
  which inherits none of the markers above. (`build.gradle.kts` also hands
  `idea.active` down through that fork, so both signals agree.)

A player is excluded by either half — they run a jar, and have no `.idea/`
beside it. A `./gradlew run` from a terminal *in the same checkout* does
build the folder; it's the same developer on the same project.
`-Dlarsons.share=true` (or `false`) overrides the whole check. When a launch
from class files is skipped, the console says so rather than leaving you
wondering.

Send a friend the `share/` folder (or just the jar) and they can play — and
because the jar packages your `resources/`, your game types and skins travel
with it. The `textures/` folder rides along too, so they can reskin the game
by dropping PNGs beside the jar.

**Connecting — which address do I type?**

| You are... | Address to join |
|------------|-----------------|
| On the **same machine** as the host (testing with two windows) | `localhost:7788` |
| On the **same network** (same house / wifi / LAN) | the **host's LAN IP**, e.g. `192.168.1.23:7788` |
| Somewhere else on the internet | the host's **public IP**, with TCP port 7788 forwarded on their router |

`localhost` (127.0.0.1) always means *"this same computer"* — it loops back
before ever reaching the network, so it can never reach a host on another
machine, even on the same wifi. For same-network play the host's **lobby
screen shows the exact address to share** ("Same network? They join:
192.168.x.x:7788", via [`Lan`](src/main/java/com/larsons/engine/net/Lan.java)),
and it's also written into `share/HOW_TO_PLAY_ONLINE.txt`. No port
forwarding is needed on a LAN — that's only for internet play, exactly like
hosting a Minecraft server. The world game works the same way on its default
port 7777.

---

## Online play

Online play (requirement #3) works like Minecraft Java edition: someone hosts
a server on a port, everyone else connects to `ip:port`.

**Hosting from the game:** main menu → *Multiplayer* → set a port → *Host
Server + Play*. This starts an integrated server with **your** active game
type and level and joins it locally. Friends on your LAN connect to your local
IP; friends over the internet connect to your public IP (forward the TCP port
on your router, exactly like a Minecraft server).

**Joining:** main menu → *Multiplayer* → type `host` or `host:port` (port
defaults to 7777) → *Join Server*. The server sends its game type **and the
level itself** on join, so clients don't need the host's files — you play
exactly the world the host configured.

**Dedicated server** (headless, no window — run it on any machine with a JDK):

```bash
./gradlew runServer --args="--port 7777 --level levels/sample_level.json --gametype platformer"
# or from the jar:
java -cp build/libs/Larsons-2D-Game-Engine-0.1.0.jar com.larsons.engine.net.ServerMain --port 7777
```

### How the netcode works

The server is **authoritative** — the model the fixed-timestep loop was
designed for: *input commands in, state snapshots out*.

- Clients never send positions. Each tick the client sends a
  [`PlayerInput`](src/main/java/com/larsons/engine/sim/PlayerInput.java)
  (left/right/up/down + sequence number); the server drains each player's
  queued inputs (so edge-triggered jumps and attack clicks are never lost
  between ticks, however fast the client sends) and steps
  [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java)
  at a fixed 60 Hz — paced with the same precise coarse-sleep/fine-park
  waits as the render loop, so the tick rate holds on every OS — then
  broadcasts snapshots at 30 Hz. Cheating by teleporting isn't possible,
  and a laggy client only degrades itself.
- **Prediction & reconciliation:** the local player runs the *identical*
  `PlayerPhysics` step locally, so movement feels instant. Snapshots echo the
  last input sequence the server applied; the client rewinds to that
  authoritative state, **replays its still-unacknowledged inputs**, and
  compares like with like — when both simulations agree nothing tugs at the
  player, so there is no rubber-banding at any ping (small residual errors
  blend away smoothly, large ones snap).
- **Interpolation:** remote players *and* replicated entities are drawn
  ~70 ms in the past, blended between the two buffered snapshots straddling
  that moment, so everything moves smoothly regardless of snapshot timing.
- **Entity replication:** the server simulates mobs, dropped items, and
  projectiles in flight (the same `World` code single-player runs) and
  includes them in snapshots; clients just render them. Snapshots also carry
  the time of day, so the lighting pass darkens every screen in sync.
- **World edits:** placing and creative painting are requests
  (`edit`/`paint`/`erase`); the server validates them against the host's
  feature toggles, applies them on the tick thread, and broadcasts the
  authoritative `block` result to everyone (bursts — liquid flow, explosions —
  batch into one `blocks` message). **Play-mode mining is hold-to-mine
  online too:** the mining intent rides the input command and the server
  accumulates progress against the block's hardness (matching tools speed it
  up, finished blocks wear the tool), so durability behaves exactly as
  offline. Late joiners get the *live* level — serialized compact +
  run-length-encoded so even giant custom levels fit the handshake — so an
  hour of collaborative painting is never lost on them.
- **Combat, shooting & pickups:** attack intent rides the input command
  (edge-triggered by sequence number so one click is one swing), along with
  the player's hotbar selection — so the server knows what each player holds
  and resolves accordingly: a melee swing (weapon damage included), or a
  **projectile** for a held ranged weapon/throwable, spending ammo from that
  player's **server-side inventory**. Impacts broadcast as `fx` events so
  everyone sees the same explosion. Loot and pickups land server-side, and
  each change pushes the authoritative inventory down to its owner.
- **Inventory actions:** moving/merging/swapping stacks, dropping items into
  the world, and eating food are requests (`invmove`/`invdrop`/`use`) the
  server validates and applies — and play-mode block placement consumes the
  matching block item from the placer's inventory, so blocks can't be
  conjured from nothing.
- **Wire protocol:** newline-delimited compact JSON over TCP, built on the
  engine's own `Json` — zero dependencies (requirement #4) and debuggable
  with `telnet`. See [`Protocol`](src/main/java/com/larsons/engine/net/Protocol.java)
  for the full message flow (join/welcome handshake with a protocol version,
  input, state, info events, ping/pong RTT measurement that doubles as the
  keep-alive).
- **Threading:** one accept thread, a reader + queued writer per connection
  (a slow client can never stall the simulation; if its queue overflows it is
  disconnected), and one tick thread that owns all state. Silent connections
  time out after 15 s.

The pause menu never edits features (per-level toggles are edited in Load Level
→ Edit Settings); in multiplayer the simulation keeps running server-side while
the menu is open, again like Minecraft.

---

## Game types, levels & feature toggles

A **game type** is a named **folder of levels** plus a default set of feature
toggles, stored as a JSON
[`GameProfile`](src/main/java/com/larsons/engine/config/GameProfile.java). The
idea: the engine is one big level loader, and the toggles tell it which features
to turn on so the *same* engine can drive a platformer, a top-down adventure, an
isometric builder, etc.

**Toggles are tied to each level, not to the game type.** Every level carries
its own copy of the settings ([`Level.settings`](src/main/java/com/larsons/engine/level/Level.java)),
so a single game type can group wildly different levels — a lit, gravity-on
boss arena next to an unlit, top-down puzzle room. Loading a level loads its
toggles. The game type's own profile is just the **template** new levels start
from (and remembers which level to open by default).

**Flow on launch:**

1. **Startup** — pick an existing game type (to keep creating levels within it)
   or *Create New Game Type*.
2. **Editor** — name it and flip the default feature toggles new levels inherit.
3. **Save** — the template is written to `resources/gametypes/<name>.json`.
4. **Main menu** — **Play Level** opens the last level you played; **Load Level**
   lists the game type's individual levels
   ([`LevelSelectScene`](src/main/java/com/larsons/engine/demo/LevelSelectScene.java)).
   Click a level and you get two buttons: **Play Level** (load and play it) and
   **Edit Settings** (a form to **rename the level** and edit *that level's* own
   toggles, saved back into the level). This is the one place per-level settings
   are edited. The main menu also has **Rename Game Type**, which renames the
   folder — its levels, doors, and custom content move with it.
5. **Play** — the level loads with only its own enabled features active. Press
   **Esc** for a deliberately simple **pause menu**: *Resume*, *Save Level*
   (persist this world + its settings), *Edit in Creative*, and *Quit to Menu*.

Levels are authored and saved in **Creative Mode**, which snapshots the active
toggles into the level on every save, and are stored under
`resources/levels/<game-type>/<level>.json`.

**Currently configurable features:**

| Feature | Type | Notes |
|---------|------|-------|
| Default level format | cycler | Side-Scroller / Top-Down / Isometric — the format **new** levels start in (each level then carries its own, for life) |
| Zoom enabled | toggle | gates the zoom controls + range |
| Min / Max / Default zoom | steppers | enabled only when zoom is on |
| Min / Max framerate | steppers | **Max** is applied live as the render cap |
| Gravity / jumping | toggle | side-scroll falling + jump |
| Show HUD | toggle | on-screen info bar |
| Show grid | toggle | tile grid overlay |
| Mobs (AI creatures) | toggle | spawn + simulate the level's painted mobs |
| Items & inventory | toggle | drops, pickup, hotbar + inventory UI |
| Combat | toggle | swings hurt mobs, mobs hurt players; off = ambient wildlife |
| Projectiles & ranged weapons | toggle | bows/staves/throwables fire; off = melee only |
| Mine / place blocks in play | toggle | left-click mine (with drops), right-click place |
| Creative mode (paint objects) | toggle | the creative editor + online painting |
| Lighting | toggle | the lighting shader pass (works without post-FX) |
| Day/night cycle · Night (fixed) | toggles | time-driven darkness, or a constant night |
| Night darkness · Ambient light | steppers | how dark night gets / the light floor |
| Parallax background | toggle | procedural multi-layer backdrop (side-scroll) |
| Particles | toggle | block-break shards, hit sparks |
| Sound effects | toggle | synthesized SFX (jump, mine, place, pickup, hit…) |
| Tile / Player / Default entity size | steppers | sizes in world pixels |
| Shaders (post-FX) | toggle | master switch for the shader chain |
| Shader strength | stepper | global `uStrength` in [0, 1] |
| Pixelate (+ pixel size), Wave, Chromatic aberration, Bloom, Grayscale, Scanlines, Vignette | toggles | individual passes, applied in that order |
| Export shaders as GLSL | action | writes `.vert`/`.frag` files to `shaders/` |

Adding a new feature is three edits: a field on `GameProfile` (it auto-serializes
via `toMap`/`fromMap`), a row in
[`ProfileForms`](src/main/java/com/larsons/engine/demo/ProfileForms.java), and
honouring it where it matters (e.g. in `PlayScene`).

```java
// Programmatic use:
GameTypeStore store = new GameTypeStore();        // resources/gametypes/
GameProfile profile = new GameProfile("My Platformer");
profile.perspective = Perspective.SIDE_SCROLL;   // the format new levels start in
profile.zoomEnabled = false;
store.save(profile);                              // -> my_platformer.json
// later:
GameProfile reloaded = store.load("My Platformer");
```

> Game types are written to the **`src/main/resources/gametypes/`** folder, so
> run from the project root (e.g. `./gradlew run`). Bundled example types ship
> on the classpath and also load from a packaged jar.

### Exporting & sharing a game type (`.larsonsengine`)

A finished game type can be handed to someone else as a single file. From the
main menu, **Export Game Type (.larsonsengine)** bundles the game type's profile
**and every level in it** — plus the `doors.json` / `custom.json` that wire those
levels together and define their custom blocks/mobs/items — into one
`<name>.larsonsengine` file
([`GamePackage`](src/main/java/com/larsons/engine/config/GamePackage.java)). A
level is never exported on its own: it only means something inside the game type
whose features, doors, and custom content it was built against.

The file is written next to the runnable jar (in the `share/` folder when you're
running from IntelliJ). **At launch the engine scans that folder for
`.larsonsengine` files and installs any it hasn't seen** — so a recipient just
drops the file beside their jar and starts the game; the game type appears on
the startup chooser with all its levels. An already-installed game type is left
alone, so re-scanning never clobbers a player's local edits.

**Finalize toggle.** The export dialog has a **Finalize** toggle. When it's on,
the packaged copy is marked *play-only*: after import, its levels can be
**played but not edited** — Creative Mode, per-level *Edit Settings*, feature
edits, and renames are all hidden, and the menu labels the type
`finalized (play-only)`. Finalizing only affects the exported package; your own
local copy stays fully editable. (It's just a `finalized` flag on the
[`GameProfile`](src/main/java/com/larsons/engine/config/GameProfile.java), so the
lock travels inside the file.)

```java
// Programmatic export/import (roots default to resources/gametypes + resources/levels):
GameProfile profile = new GameTypeStore().load("My Platformer");
Path file = GamePackage.export(profile, new LevelStore("My Platformer"),
                               GamePackage.dropInDir(), /* finalized = */ true);
// on a recipient's machine, at launch:
GamePackage.importDropIns();   // installs any .larsonsengine dropped beside the jar
```

The package is a plain JSON document (the engine's own dependency-free parser
reads it), so it stays inspectable:

```json
{
  "larsonsengine": 1,               // schema version (drives migration)
  "name": "My Platformer",
  "gameType": { "name": "My Platformer", "finalized": true, ... },
  "levels":  { "level_one": { ...level... }, "level_two": { ... } },
  "doors":   { "doors": [ ... ] },
  "custom":  { "blocks": [ ... ], "mobs": [ ... ] }
}
```

**Forward compatibility.** A `.larsonsengine` file exported today is designed to
keep loading in every future build, guaranteed three ways:

1. **It's versioned** — the `larsonsengine` schema version travels in the file,
   so a future build always knows what it's looking at.
2. **Readers are tolerant** — `GameProfile.fromMap` and the level loader default
   anything missing and ignore anything unknown, so a newer build never chokes
   on an old file (and an older build won't choke on a newer one — it imports
   *best-effort* rather than refusing).
3. **There's a migration hook** — on import, `GamePackage.migrate` upgrades an
   older schema to the current one. It's a no-op at v1; when the format ever
   changes, that's where a `v1 → v2` step goes.

The contract future changes must keep is intentionally small: **only add keys
(with safe defaults); never repurpose or remove one**, and when a shape must
truly change, add a migration step keyed to the version it changed at. The
`GamePackageTest` suite pins this behaviour (minimal old-shaped packages,
unknown fields, and newer-versioned packages all import), so a change that would
break an old export fails CI.

### Building a feature form

`ConfigForm` is the reusable clickable widget behind the editor and pause menu.
Each control binds to a getter/setter, so it edits your object in place:

```java
ConfigForm form = new ConfigForm("Settings");
form.addToggle("Zoom", () -> p.zoomEnabled, v -> p.zoomEnabled = v);
form.addDouble("Max zoom", () -> p.maxZoom, v -> p.maxZoom = v, 0.1, 8.0, 0.1)
    .enabledWhen(() -> p.zoomEnabled);            // greyed out + skipped when off
form.addEnum("Format", LevelFormat.values(), () -> LevelFormat.of(p.perspective),
        v -> p.perspective = v.perspective());
form.addText("Name", () -> p.name, v -> p.name = v, 40);
form.addNote("Explains the rows around it — wraps, and the selection skips it.");
form.addAction("Save", () -> store.save(p));
// in the scene: form.update(dt, input); form.render(g, w, h);
```

Rows lay out **control first**: the control is right-aligned in the content
column and the label gets what's left, shortened with an ellipsis if it has to
be. So a wordy label, a level name inside a cycler, or a long path typed into a
field is never drawn over the control beside it, and a text field shows the
*end* of its value (the part being typed) rather than running off the screen.
That is a backstop, not a licence to write long labels — put prose in
`addNote`, which wraps across the column at the theme's smaller note font.

---

## Extending the essentials

### Sprite sheets

```java
SpriteSheet sheet = SpriteSheet.load("assets/player.png", 32, 32); // frame size
Animation walk = sheet.animation(10, 0, 4, true);   // 10 fps, frames 0..3, loop
// each update:
walk.update(dt);
g.drawImage(walk.current(), x, y, null);
```

Missing images resolve to a magenta/black placeholder instead of crashing, so
you can build out art incrementally.

Sheets can also be *made* from code — or from the game, which is what
[Create texture](#create-texture-draw-the-sprite-sheet-in-game) does:

```java
SpriteCanvas canvas = new SpriteCanvas(32, 32, 6); // 32x32 frames at 6 fps
canvas.plot(4, 4, 0xffb13e53, 1);                  // ARGB pixel, 1px brush
canvas.addFrame();                                 // frame 2 = a copy of frame 1
canvas.fill(0, 0, 0xff1a1c2c);                     // flood fill the background
TexturePack.writeSheet("block/moon_rock", canvas.toSheet()); // → blocks/moon_rock.png
```

### Levels

Levels are JSON loaded from the classpath (bundled, including inside the jar) or
the filesystem. Only `tiles` is required:

```json
{
  "name": "Sample Level",
  "format": "side_scroller",
  "perspective": "SIDE_SCROLL",
  "tileSize": 32,
  "width": 24, "height": 14,
  "background": "#10141e",
  "lightAngle": 315,
  "palette": ["#785a3c", "#5aa050", "#6e6e78"],
  "spawn": { "x": 64, "y": 96 },
  "tiles": [[0,0,1,...], ...],
  "upperRle": [id, runLength, ...],
  "entities": [ { "type": "player", "x": 64, "y": 96 } ]
}
```

```java
Level level = LevelLoader.load("levels/sample_level.json");
```

`"format"` names the level's [`LevelFormat`](src/main/java/com/larsons/engine/level/LevelFormat.java)
(`side_scroller` / `top_down` / `isometric`) — which creative mode builds it
and how it plays. `"perspective"` is the same choice in the older spelling;
either key alone is enough, and a level with neither loads as a side-scroller.
A level keeps the format it was saved with for its whole life.

`"upperRle"` (or `"upperChunks"` on a giant level) carries the **second layer
of blocks** the plan-view formats stack — see
[Stacked blocks](#stacked-blocks-the-plan-views-geometry). A side-scroller has
no such key, and neither does a top-down or isometric level written before
blocks stacked: a plan-view level with no upper layer in the file is converted
on load so it still plays as drawn.

Levels come in two modes. **Palette mode** (above, the original format):
tile ids index the colour palette and every tile is solid. **Registry mode**
(what the creative editor saves; add `"tileset": "registry"`): tile ids are
`BlockRegistry` block ids, which bring solidity, light emission, and drops.
Both load with the same `LevelLoader`, and levels serialize back with
`level.toJson()` — that round-trip is how creative saves and how a
multiplayer server hands its live, edited world to joining players.
Entity spawns take a `kind` (`"mob"` / `"item"`) resolved against the
registries:

```json
"entities": [
  { "kind": "mob",  "type": "zombie", "x": 300, "y": 128 },
  { "kind": "item", "type": "apple",  "x": 200, "y": 100 }
]
```

### Menus

```java
Menu menu = new Menu("My Game")
    .subtitle("press start")
    .theme(MenuTheme.light())              // or .dark(), or a custom MenuTheme
    .add("Play",     () -> scenes.transitionTo("play"))
    .add("Settings", () -> scenes.transitionTo("settings"))
    .add("Quit",     () -> System.exit(0));
```

`MenuTheme` exposes every colour, font, and spacing value; `MenuItem` labels can
be dynamic (e.g. a "Perspective: ISOMETRIC" toggle that updates live). Menus
with more entries than fit on screen **scroll**: the mouse wheel and a draggable
scroll bar down the right edge move the view, keyboard navigation keeps the
selection visible, and a menu that fits shows no bar — so every menu screen
handles any number of entries. Titles, subtitles and items are shortened to the
window when they'd overrun it, which matters because a menu is often titled with
a name the creator typed.

### A new scene

```java
public class MyScene extends AbstractScene {
    @Override public void onEnter() { /* load */ }
    @Override public void update(double dt, InputManager input) { /* logic */ }
    @Override public void render(Graphics2D g, float alpha) { /* draw */ }
}
// register + show:
engine.scenes().register("mine", new MyScene());
engine.scenes().setScene("mine"); // or transitionTo for a fade
```

---

## Roadmap

The items below are *engine* roadmap items. For the **product** roadmap — the
path to a Steam release, phased with blockers, costs and risks — see
**[`STEAM_PLAN.md`](STEAM_PLAN.md)**. Note that the GPU backend below is a
larger job than it looks: the per-pass GLSL has never been compiled by
anything, so it is an untested port target rather than ready source
(see [Appendix A](STEAM_PLAN.md#appendix-a--the-shader-system-precisely)).

- **GPU renderer backend:** an OpenGL (LWJGL) `Renderer` that compiles each
  `ShaderPass.glsl()` into FBO ping-pong passes — the shader library
  (including `LightingPass`) needs no changes, by design. Kept out of the
  core so the engine itself stays JDK-only (requirement #4); the remaining
  work for GPU *scene* rendering is a backend-neutral draw API, since scenes
  draw with `Graphics2D`.
- **Netcode next steps:** interest management for large worlds, lag
  compensation for hit detection.
- **Deeper ports from the Side-Scroller engine:** alchemy/crafting recipes,
  vault storage, equipment overlays, moving blocks, doors/buttons/triggers —
  the registries and the request protocol are the hooks they'd plug into
  (projectiles + ranged weapons and server-side eat/consume shipped with the
  inventory/projectile update).

## Tests

`./gradlew test` runs headless tests
([`EngineSmokeTest`](src/test/java/com/larsons/engine/EngineSmokeTest.java),
[`ConfigFeatureTest`](src/test/java/com/larsons/engine/ConfigFeatureTest.java),
[`ShaderTest`](src/test/java/com/larsons/engine/ShaderTest.java),
[`PlayerPhysicsTest`](src/test/java/com/larsons/engine/PlayerPhysicsTest.java),
[`NetworkTest`](src/test/java/com/larsons/engine/NetworkTest.java),
[`WorldFeaturesTest`](src/test/java/com/larsons/engine/WorldFeaturesTest.java),
[`ProjectileTest`](src/test/java/com/larsons/engine/ProjectileTest.java),
[`NetWorldSyncTest`](src/test/java/com/larsons/engine/NetWorldSyncTest.java),
[`NetProjectileInventoryTest`](src/test/java/com/larsons/engine/NetProjectileInventoryTest.java),
[`AutoBattlerTest`](src/test/java/com/larsons/engine/autobattler/AutoBattlerTest.java),
[`MechanicsFixesTest`](src/test/java/com/larsons/engine/MechanicsFixesTest.java),
[`AutoBattlerNetTest`](src/test/java/com/larsons/engine/autobattler/AutoBattlerNetTest.java),
[`AutoBattlerSceneTest`](src/test/java/com/larsons/engine/AutoBattlerSceneTest.java),
[`EngineFeatureTest`](src/test/java/com/larsons/engine/EngineFeatureTest.java),
[`MobExpansionTest`](src/test/java/com/larsons/engine/MobExpansionTest.java),
[`RelicsTest`](src/test/java/com/larsons/engine/RelicsTest.java),
[`VehicleTest`](src/test/java/com/larsons/engine/VehicleTest.java),
[`GenomeTest`](src/test/java/com/larsons/engine/evolution/GenomeTest.java),
[`EvolutionGameTest`](src/test/java/com/larsons/engine/evolution/EvolutionGameTest.java),
[`EvolutionSceneTest`](src/test/java/com/larsons/engine/EvolutionSceneTest.java),
[`DirectionalAnimationTest`](src/test/java/com/larsons/engine/DirectionalAnimationTest.java),
[`CharacterProfileTest`](src/test/java/com/larsons/engine/CharacterProfileTest.java),
[`UltimateAbilityTest`](src/test/java/com/larsons/engine/UltimateAbilityTest.java),
[`EffectSkinsAndJumpTest`](src/test/java/com/larsons/engine/EffectSkinsAndJumpTest.java),
[`Mp3DecoderTest`](src/test/java/com/larsons/engine/Mp3DecoderTest.java),
[`Mp3TablesTest`](src/test/java/com/larsons/engine/audio/Mp3TablesTest.java),
[`SoundPackTest`](src/test/java/com/larsons/engine/SoundPackTest.java),
[`SoundMixerTest`](src/test/java/com/larsons/engine/SoundMixerTest.java),
[`SoundEditorTest`](src/test/java/com/larsons/engine/SoundEditorTest.java),
[`SpriteEditorTest`](src/test/java/com/larsons/engine/SpriteEditorTest.java),
[`MeleeCombatTest`](src/test/java/com/larsons/engine/MeleeCombatTest.java),
[`CreativeUndoTest`](src/test/java/com/larsons/engine/CreativeUndoTest.java))
covering JSON read/write, level loading (both tile modes + round-trips),
sprite-sheet slicing, input edge detection, game-type save/load, the
`ConfigForm` widget's keyboard/mouse interaction (including scrolling),
rendering the scenes off-screen (play + creative), pixel-exact shader
behavior + the GLSL contract and export (including the lighting pass),
deterministic player physics, the mob AI state machine, world simulation
(mining → drops → pickup, melee combat, the day/night curve),
melee combat (every move's wind-up → active → recovery phases and the rule
that a swing lands exactly once; per-move cooldowns and stamina; the styles
every existing item derives and the two ways a game type overrides them;
weapon reach and arc deciding what a strike catches; a guard soaking its
share, a parry catching a blow outright and turning shots around, dash frames
avoiding one; a committed burst carrying a fighter and letting go; armed mobs
inheriting their weapon's timings, winding up before they land, and catching a
player's swing; the stance and the mob's move riding the wire; and the sheet
and sound chains — a held object's wielder art, the object's own art, both
falling back to idle, and the move sheet playing exactly once across the
move), projectiles
(registry + item links, ammo consumption, gravity arcs vs straight magic,
mob hits, explosions with area damage, recoverable drops, toggle gating),
inventory primitives (move/merge/swap/removeAt), per-game-type level saving,
the creative/engine feature set (giant chunked levels with lazy
deterministic generation and edited-chunk-only saves, AABB wall/ceiling
collisions, sprint stamina, block durability with tool speed-ups, crafting
and smelting recipes, mana-costed magic, stat rules firing rewards and
consumptions, brush footprints, mob wall-hopping, surface-decor and
stat-rule serialization, and the creative scene rendering off-screen),
creative mode's undo (a step grouping a whole action and undoing its parts in
reverse; an action that changed nothing leaving no step to press through;
overlapping saves of one cell resolving to the state before the stroke;
nested steps counting as one action; a new edit dropping what redo would have
put back; the bound forgetting the oldest first; an undo that cannot record a
step of its own; cell snapshots bringing back a stack with its details and its
container; document snapshots restoring markers, rules, cutscenes edited in
place, the mini game and the roster, and comparing equal when nothing changed;
resize snapshots restoring dropped content and the dense grid a giant resize
converted away; a level swap handing back the very level that was open; and
Ctrl+Z / Ctrl+Y driven through the real editor with synthesized clicks and
keystrokes — a whole drag taken back at once, an erased block and the detail
that hung off it coming back in order, a painted marker unplaced and placed
again, and Ctrl+Z with nothing to undo leaving the level alone),
sound (the MP3 decoder against streams it builds itself — frames, ID3v1/v2
tags, resynchronisation past damage, and every truncation and random-byte
case a half-written file can produce; the packed Huffman code books walked
for structural integrity, since a mistyped digit there would corrupt audio
silently rather than fail; the sound pack's name-based lookup with its
specific-beats-general fallback and its liquids/lights folders; the rule that
everything defaults to silence except the actions that always had a voice;
per-sound overrides round-tripping through `soundpack.json` and `sounds.json`;
the fresh-pitch drift staying inside its bound while never repeating, and
never touching music; the whole system being a no-op with no audio device;
objects made with the "+" button arriving with a full set of action states in
the generated key list; and the creative sound menu driven by clicking it),
the in-game sprite-sheet editor (pencil, flood fill, line and rectangle
coverage; a drag interpolated into a stroke rather than dots; a new frame
starting as a copy of the one before it while leaving that one alone; the
last frame cleared rather than deleted away; whole-stroke undo, including
across a canvas resize; the exported sheet slicing back into exactly the
frames it was drawn as; painting, right-click erasing and `Ctrl+S` driven
through the real window with synthesized mouse and key events; and the saved
sheet landing in the texture pack under the object's own file name, at the
frame rate it was drawn at, drawing immediately),
cutscenes ([`CutsceneTest`](src/test/java/com/larsons/engine/CutsceneTest.java):
sheet-anim frame timing with loop/one-shot clamping, the step player's
sequencing — captions, moves with walk-state restore and facing, camera
pans, skipping applying every remaining effect — the trigger director's
zone/interact/level-start semantics with once-per-run and re-arming, and
level-JSON round-trips),
the expanded menagerie (ranged mobs whose shots hurt players but never other
mobs, summoners, splitters, death-bursts, blinks, regen/lifesteal/shield,
elemental burn/chill/poison/chain statuses and their wire bits, essence
loot), relics and elemental weapons (passives flowing from inventory to
physics — speed, slow fall, flight, magnetism, melee power — the Phoenix
Feather revive, Nova/Tremor actives, bombs cratering terrain under the
editing toggle, the Harvest Orb, the Warp Staff's owner-teleport, meteor
salvos and the Scatter Bow, and recipe-catalog integrity), vehicles (item
links, mount validation, gallop/jump physics, buoyant boats, flying
carpets, the terrain-grinding drill, dragon fire, pack-up recovery, wire
form — plus a full online ride: mount request, replicated gallop with the
rider glued to the saddle, dismount),
characters and their signature moves (the eight-point compass and its
side-scroll two, per-facing sprite resolution with mirrored twins and the
pre-generated fallback nobody flips, per-direction pack file names,
character-profile persistence and re-registration per game type, trait
clamping, level rosters saving and degrading gracefully when a profile is
deleted, traits actually reaching the physics — speed, sprint permission, air
jumps, jump height, resized pools — ultimate charging from time and damage,
one-cast spending, every ability's effect in all three perspectives, sustained
buffs lapsing on their own and on death, particle and projectile texture keys
with their built-in fallbacks, a skinned particle sheet actually rendering,
and jumping in all three formats: Space off the ground in side-scroll, the
plan-view hop's launch/peak/landing, its mid-air steering and double jump, the
jump/fall animation states it drives, and the Z axis parking when a door leads
into a side-scrolling level),
the per-format physical spaces (which axis each one calls up, that gravity
turns rather than switching off, and how height draws in each; that `W`/`Up`
jump nothing — on foot or on a mount — while Space still does; meteor salvos
spawning above the aim point on a plane and landing on the tile they were
aimed at, still arriving from up the screen in a side-scroller, and a falling
shot clearing walls until it touches down; particle trajectories rising up the
screen isometrically instead of spraying north-east and splashing across the
floor instead of running south, with the side-scroller's own motion unchanged;
knockback following the hit vector on a plane; thrown stacks leaving along all
eight facings),
and full loopback multiplayer (a real server + clients: handshake, movement,
join/leave, version rejection, shutdown — plus block edits replicating to
every client and late joiners, painted mobs appearing in snapshots and being
erased, pickups landing in the server-side inventory, feature toggles gating
edits server-side, shots consuming server-side ammo and replicating with
impact-fx broadcasts, inventory move/drop/eat requests, and placement
consuming block items) — so everything is verifiable without a display.

The auto-battler is covered the same way: registry integrity (every trait
tier reachable, every component pair combining), pool scarcity + shop odds,
buy/combine/sell round-trips, placement rules, the economy, deterministic
seeded battles, whole bot games running headlessly to a winner (PvE rounds,
ghosts, eliminations, placements), real loopback lobbies with host-only
controls and combat replication, and both scenes rendering off-screen
against a live server.

Evolution is covered end to end from the genetics up:
[`GenomeTest`](src/test/java/com/larsons/engine/evolution/GenomeTest.java)
pins the decoding rules themselves (the design's worked example — a green in
slot 3 is light emission of 3, doubled by a following red — the whole
wild-card wheel and its wrap-around, all nine pair abilities, the long-strand
unlockables, colour and shape derivation, and that replication miscopies often
enough to explore while still letting a faithful long strand breed true);
[`EvolutionGameTest`](src/test/java/com/larsons/engine/evolution/EvolutionGameTest.java)
runs the ecology headlessly (the opening state the design specifies,
replication and divergence, starvation ending a run, energy-neutral corpse
recycling, digestion waste, complex energy gated on the ability that eats it,
predators actually killing, barriers and dish walls holding, heat diffusing,
shadows and spotlights, the shop, the spatula spending only on a real
transfer, catalog uniqueness and colony combinations) and the JSON layer (save
round-trips, one file per discovery, the book outliving a new experiment, and
corrupt or junk-bearing saves being reported rather than thrown, the two
discovery tiers round-tripping separately, a full reset clearing the game
catalog and the balance while the history keeps every organism, achievement and
lifetime total across repeated resets, an older layout's discoveries being
migrated rather than stranded, and the tuning invariants that keep red the
hardest opening); and
[`EvolutionSceneTest`](src/test/java/com/larsons/engine/EvolutionSceneTest.java)
renders every screen off-screen against a live dish — lobby, microscope, shop,
help, pause, and both pages of the reference book — and drives the tool tray
and inspector by synthesized clicks.

The presentation/customization layer has its own suite:
[`AutoBattlerFxTest`](src/test/java/com/larsons/engine/autobattler/AutoBattlerFxTest.java)
(replicated animation states, damage-by-type tallies, source-carrying combat
events, board-scouting requests — including for eliminated spectators),
[`AutoBattlerScoutTest`](src/test/java/com/larsons/engine/demo/AutoBattlerScoutTest.java)
(clicking a standings row scouts a live server's board end to end),
[`SkinsTest`](src/test/java/com/larsons/engine/SkinsTest.java) (skin
definitions, the 0-120 fps clamp, store round-trips, sheet slicing and
fallbacks), [`AutoHudTest`](src/test/java/com/larsons/engine/demo/AutoHudTest.java)
(HUD text regions stay pairwise disjoint across window sizes and fill
levels), [`ShareJarTest`](src/test/java/com/larsons/engine/ShareJarTest.java)
(the auto-built share jar is runnable, scripted, documented, carries a
texture pack, isn't rebuilt needlessly — and is only built inside IntelliJ,
including when its Gradle fork strips the IDE's markers),
and [`TexturePackTest`](src/test/java/com/larsons/engine/TexturePackTest.java)
(the drop-in folder scaffolds itself, finds sheets by palette-category file
name, plays them at one universal spec that any single texture can override,
lets each object opt out or point elsewhere, and keeps palette icons showing
the texture that actually renders).
