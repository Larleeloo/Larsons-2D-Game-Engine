# Larson's 2D Game Engine

A **generic** 2D game engine in pure Java. It provides a clean game loop
and the building blocks for any 2D game — sprite sheets, level loading,
cameras with multiple perspectives, scenes, input, a customizable menu
system, **online multiplayer** (host a server, friends join by IP + port,
Minecraft-style), and a **shader system** (GLSL-first post-processing with a
CPU fallback that runs anywhere) — without committing to a single genre.

The engine is built to be **a giant custom level loader**: you group levels
under a **game type** (a folder), and each **level** enables only the features
it needs (perspective, zoom, framerate bounds, entity sizes, gravity, HUD, …).
The toggles live on the level, so one game type can hold a diverse mix of
levels; the game type just supplies the default template new levels start from.
One engine drives many different games.

This engine is a **merge**: the minimal outline above, plus the feature
systems of its feature-rich sibling, **Side-Scroller-Game-Engine**, ported
over in a generic, data-driven form and wired to the same toggles:

- **Creative Mode** — a level editor for *painting objects into the world*
  (blocks, lights, mobs, items) with palette categories, drag-painting,
  erasing, pick-block, pan/zoom, play-testing, and per-game-type level
  saving. Works offline **and inside a multiplayer session**, where strokes
  replicate to every player. See [Creative mode](#creative-mode-paint-objects).
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
- **Combat** — melee swings, knockback, mob loot, health + respawn.
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
- **Parallax backgrounds, particles, and synthesized sound effects** — all
  procedural, keeping the engine asset-free and JDK-only.
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
- **Skins (texture overrides)** — drop PNG sprite sheets in
  `resources/skins/` and assign them in the lobby's **Customize Skins** menu:
  frame pixel width/height + frame count + a 0-120 fps playback rate, per
  texture (units get one per animation state). Saved to your game files and
  applied live. See [Skins](#skins-texture-overrides).
- **Share with friends** — launching from IntelliJ/Gradle auto-builds a
  `share/` folder with a runnable jar, double-click launch scripts, and
  online-play instructions (including your LAN address). See
  [Sharing the game](#sharing-the-game--how-joining-works).
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
  and re-register on load so saved levels keep working.
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

---

## Design goals

This engine was built against six explicit requirements:

| # | Requirement | How it's addressed |
|---|-------------|--------------------|
| 1 | **120 FPS** | A fixed-timestep [`GameLoop`](src/main/java/com/larsons/engine/core/GameLoop.java) renders with a configurable cap (default **120**). The limiter schedules frames on an absolute timeline and uses a hybrid coarse-sleep / fine-park wait, so the cap is hit precisely without pegging a CPU. |
| 2 | **Multiple 2D perspectives** | [`Camera`](src/main/java/com/larsons/engine/graphics/Camera.java) + [`Perspective`](src/main/java/com/larsons/engine/graphics/Perspective.java) support `SIDE_SCROLL`, `TOP_DOWN`, and `ISOMETRIC`, switchable at runtime. |
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
- **Level:** `WASD` / arrows to move, **P** to cycle perspective (if enabled),
  **+ / -** to zoom (if enabled), **Esc** to open the pause menu.
  In side-scroll with gravity enabled the character can jump; otherwise it
  moves freely on both axes. Which of these are available depends on the active
  game type.
- **World interaction** (per the game type's toggles): **left-click** fires
  the held ranged weapon / throwable, else mines the aimed block, else swings
  at mobs; **right-click** places the selected hotbar block; **1-5** / mouse
  wheel select the hotbar slot; **Q** drops one of the selected item;
  **F** eats the selected food (a server request online); **I** opens the
  inventory — click a stack to pick it up, click another slot to place/merge/
  swap it, click outside the panel to drop it into the world.
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
│   ├── SpriteSheet.java   Slice a sheet into frames
│   ├── Animation.java     Delta-timed frame animation
│   ├── AssetLoader.java   Cached image loading + placeholders
│   ├── CutscenePainter.java Cutscene actors (sheet frames + fallbacks) + letterbox/captions
│   ├── SkinDef.java       One texture override: sheet + frame w/h/count + 0-120 fps
│   ├── SkinStore.java     Persist skins.json under resources/skins/ (game files)
│   ├── Skins.java         Runtime resolver: skin frame for a key at a time, or null
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
│   ├── Level.java         Tile grid (palette or block-registry mode) + spawns
│   ├── LevelLoader.java   Load a Level from JSON (or raw text, for the server)
│   ├── LevelStore.java    Per-game-type level saving (creative mode's home)
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
│   ├── Menu.java          Keyboard/mouse menu
│   ├── MenuItem.java      Label (dynamic) + action
│   ├── MenuTheme.java     Colours, fonts, spacing
│   └── ConfigForm.java    Clickable toggles / steppers / cyclers / text / buttons; draggable scroll bar
├── util
│   └── Json.java          Dependency-free JSON parser + writer (pretty + compact)
└── demo
    ├── StartupScene.java        Choose or create a game type
    ├── GameTypeEditorScene.java Name + configure a game type's default features
    ├── MainMenuScene.java       Per-game-type main menu (Play / Load Level / Creative / …)
    ├── LevelSelectScene.java    "Load Level": pick a level → Play or Edit Settings
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

### Perspectives

`Camera` maps world coordinates to the screen via a per-perspective projection,
then applies zoom and centering. Orthographic perspectives (`SIDE_SCROLL`,
`TOP_DOWN`) use an identity projection; `ISOMETRIC` projects a square grid into a
diamond. Because the projection is the only thing that changes, the *same*
tile/sprite drawing code renders correctly in every perspective — see
`PlayScene`, which simply projects each tile's four world corners.

Rendering cost scales with the screen, not the level: `PlayScene` computes the
visible tile range by inverse-projecting the viewport corners
(`Camera.screenToWorld`) and only draws those tiles, so arbitrarily large
levels render at the same speed.

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
| Cutscenes | the level's scripted cutscenes — paint one to place its trigger marker; *Manage Cutscenes…* (or right-clicking an entry) opens the editor |
| Tools    | player spawn, multiplayer spawn points, eraser, Brush Settings, the Generate button, the Stat Rules editor |

Objects **you** created (via the "+" entries) wear a green corner badge in
the palette and say "· custom" in the caption, so they're obvious at a
glance — right-click one and the dialog offers **DELETE this custom
object** alongside its texture settings.

Every creatable category **leads with a "+" entry** — click it to define a
brand-new block/liquid/light/mob/item/decoration with fully customizable
properties (colours, solidity, light, damage, hardness/tool, AI stats,
rarity…). Creations are registered live, persist to the game type's
`custom.json`, and reload with it.

**Editor controls:**

| Input | Function |
|-------|----------|
| Left click / drag | paint the selected entry (grid-snapped for blocks; drag keeps painting) |
| Right click (canvas) | erase (entities first, then the block cell) |
| Right click (palette icon) | assign a sprite-sheet texture to that block/item/mob/decoration |
| Middle click | pick the hovered block into the palette |
| WASD / arrows | pan the camera |
| Mouse wheel | zoom (over the canvas) / scroll the palette (over the sidebar) |
| Tab | next palette category |
| B | toggle the decoration layer (background / foreground) |
| [ / ] | shrink / grow the paint brush (shapes cycle in the sidebar's Brush row) |
| G | toggle the grid |
| P | play-test the level in place (terrain restored on exit) |
| Ctrl+S / L / N | save / load / new level |
| Esc | back (with a save prompt offline) |

Painting works in **every perspective** — the palette paints through the
same `Camera` projection the game renders with, so you can build in
isometric view if your game type uses it.

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
dialog: point it at any sprite sheet (frame size, count, fps — 0 = static),
per action state for mobs (idle/walk/attack/hurt), and the assignment
applies live everywhere that thing is drawn and persists via the engine's
`Skins`/`skins.json` system. Remove the override to get the procedural art
back.

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
**Maze** — the automatic generator for top-down / isometric levels (it
defaults to Maze for those perspectives). A seeded recursive-backtracker
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
- **Perspective-aware worlds** — every level remembers whether it's a
  side-scroller, top-down, or isometric world; the creative editor, its
  play-test, and Play all follow the level (the *New Level* dialog picks the
  perspective). Blocks paint the same everywhere but obstruct per
  perspective; top-down/iso get **path** and **wall** block families; mobs
  run perspective-specific AI (platform walkers with jump smarts in
  side-scroll, full-plane wander/chase/flee in top-down/iso); dropped items
  arc-and-bounce under gravity or scatter-and-hover with a shadow on the
  plane; and sprite-sheet block textures now warp correctly into the
  isometric diamond instead of falling back to flat colours.
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
- **Texture pack folder** — set one per game type in the texture dialog:
  *Browse…* starts there and bare sheet filenames resolve against it. Surface
  details (grass, spikes…) are sprite-sheet skinnable like everything else
  (`surface/<key>`), and the stat-rule editor's reward/consume fields grow
  **look-up cyclers** over the whole item catalog so nobody memorizes keys.

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

---

## Sharing the game & how joining works

**Launching the game from IntelliJ (or `./gradlew run`) automatically
builds a shareable copy** in `share/`, in the background, on every launch
([`ShareJar`](src/main/java/com/larsons/engine/core/ShareJar.java) — skipped
when nothing changed):

```
share/
├── larsons-2d-game-engine.jar   # the whole game: java -jar, Java 21+, no deps
├── run.bat                      # double-click launcher (Windows)
├── run.sh                       # double-click launcher (Mac/Linux)
└── HOW_TO_PLAY_ONLINE.txt       # hosting/joining instructions + your LAN IP
```

Send a friend the `share/` folder (or just the jar) and they can play — and
because the jar packages your `resources/`, your game types and skins travel
with it.

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
  (left/right/up/down + sequence number); the server applies each player's
  latest input and steps
  [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java)
  at a fixed 60 Hz, then broadcasts snapshots at 30 Hz. Cheating by
  teleporting isn't possible, and a laggy client only degrades itself.
- **Prediction:** the local player runs the *identical* `PlayerPhysics` step
  locally, so movement feels instant; incoming snapshots correct the
  prediction (small errors blend away smoothly, large ones snap).
- **Interpolation:** remote players are drawn ~100 ms in the past, blended
  between the two most recent snapshots, so they move smoothly regardless of
  snapshot timing.
- **Entity replication:** the server simulates mobs, dropped items, and
  projectiles in flight (the same `World` code single-player runs) and
  includes them in snapshots; clients just render them. Snapshots also carry
  the time of day, so the lighting pass darkens every screen in sync.
- **World edits:** mining, placing, and creative painting are requests
  (`edit`/`paint`/`erase`); the server validates them against the host's
  feature toggles, applies them on the tick thread, and broadcasts the
  authoritative `block` result to everyone. Late joiners get the *live*
  level (the server serializes its current world on join), so an
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
   **Edit Settings** (a form editing *that level's* own toggles, saved back into
   the level). This is the one place per-level settings are edited.
5. **Play** — the level loads with only its own enabled features active. Press
   **Esc** for a deliberately simple **pause menu**: *Resume*, *Save Level*
   (persist this world + its settings), *Edit in Creative*, and *Quit to Menu*.

Levels are authored and saved in **Creative Mode**, which snapshots the active
toggles into the level on every save, and are stored under
`resources/levels/<game-type>/<level>.json`.

**Currently configurable features:**

| Feature | Type | Notes |
|---------|------|-------|
| Perspective | cycler | `SIDE_SCROLL` / `TOP_DOWN` / `ISOMETRIC` |
| Switch perspective in-game | toggle | allow the **P** key to cycle |
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
profile.perspective = Perspective.SIDE_SCROLL;
profile.zoomEnabled = false;
store.save(profile);                              // -> my_platformer.json
// later:
GameProfile reloaded = store.load("My Platformer");
```

> Game types are written to the **`src/main/resources/gametypes/`** folder, so
> run from the project root (e.g. `./gradlew run`). Bundled example types ship
> on the classpath and also load from a packaged jar.

### Building a feature form

`ConfigForm` is the reusable clickable widget behind the editor and pause menu.
Each control binds to a getter/setter, so it edits your object in place:

```java
ConfigForm form = new ConfigForm("Settings");
form.addToggle("Zoom", () -> p.zoomEnabled, v -> p.zoomEnabled = v);
form.addDouble("Max zoom", () -> p.maxZoom, v -> p.maxZoom = v, 0.1, 8.0, 0.1)
    .enabledWhen(() -> p.zoomEnabled);            // greyed out + skipped when off
form.addEnum("Perspective", Perspective.values(), () -> p.perspective, v -> p.perspective = v);
form.addText("Name", () -> p.name, v -> p.name = v, 40);
form.addAction("Save", () -> store.save(p));
// in the scene: form.update(dt, input); form.render(g, w, h);
```

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

### Levels

Levels are JSON loaded from the classpath (bundled, including inside the jar) or
the filesystem. Only `tiles` is required:

```json
{
  "name": "Sample Level",
  "perspective": "SIDE_SCROLL",
  "tileSize": 32,
  "width": 24, "height": 14,
  "background": "#10141e",
  "palette": ["#785a3c", "#5aa050", "#6e6e78"],
  "spawn": { "x": 64, "y": 96 },
  "tiles": [[0,0,1,...], ...],
  "entities": [ { "type": "player", "x": 64, "y": 96 } ]
}
```

```java
Level level = LevelLoader.load("levels/sample_level.json");
```

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
be dynamic (e.g. a "Perspective: ISOMETRIC" toggle that updates live).

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
[`EngineFeatureTest`](src/test/java/com/larsons/engine/EngineFeatureTest.java))
covering JSON read/write, level loading (both tile modes + round-trips),
sprite-sheet slicing, input edge detection, game-type save/load, the
`ConfigForm` widget's keyboard/mouse interaction (including scrolling),
rendering the scenes off-screen (play + creative), pixel-exact shader
behavior + the GLSL contract and export (including the lighting pass),
deterministic player physics, the mob AI state machine, world simulation
(mining → drops → pickup, melee combat, the day/night curve), projectiles
(registry + item links, ammo consumption, gravity arcs vs straight magic,
mob hits, explosions with area damage, recoverable drops, toggle gating),
inventory primitives (move/merge/swap/removeAt), per-game-type level saving,
the creative/engine feature set (giant chunked levels with lazy
deterministic generation and edited-chunk-only saves, AABB wall/ceiling
collisions, sprint stamina, block durability with tool speed-ups, crafting
and smelting recipes, mana-costed magic, stat rules firing rewards and
consumptions, brush footprints, mob wall-hopping, surface-decor and
stat-rule serialization, and the creative scene rendering off-screen),
cutscenes ([`CutsceneTest`](src/test/java/com/larsons/engine/CutsceneTest.java):
sheet-anim frame timing with loop/one-shot clamping, the step player's
sequencing — captions, moves with walk-state restore and facing, camera
pans, skipping applying every remaining effect — the trigger director's
zone/interact/level-start semantics with once-per-run and re-arming, and
level-JSON round-trips),
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
levels), and [`ShareJarTest`](src/test/java/com/larsons/engine/ShareJarTest.java)
(the auto-built share jar is runnable, scripted, documented, and not
rebuilt needlessly).
