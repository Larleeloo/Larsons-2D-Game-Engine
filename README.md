# Larson's 2D Game Engine

A **generic** 2D game engine in pure Java. It provides a clean game loop
and the building blocks for any 2D game — sprite sheets, level loading,
cameras with multiple perspectives, scenes, input, a customizable menu
system, **online multiplayer** (host a server, friends join by IP + port,
Minecraft-style), and a **shader system** (GLSL-first post-processing with a
CPU fallback that runs anywhere) — without committing to a single genre.

The engine is built to be **a giant custom level loader**: you define a
**game type** by enabling only the features your game needs (perspective, zoom,
framerate bounds, entity sizes, gravity, HUD, …), save it as a named JSON
profile, and then create levels within that type. Game types and levels are
stored independently, so one engine drives many different games.

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
  rounds that drop item components, and deterministic server-simulated
  battles replicated to every client. See
  [Auto Battler](#auto-battler-online-2-10-players).

Everything above **works online**: the authoritative server simulates the
world (mobs, items, drops, day/night), snapshots replicate entities, and
block edits broadcast to every client — including players who join later.
Every feature is a **toggle** on the game type, exactly like the original
engine's features.

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
| ★ | **Feature toggles + game types** | Clickable toggles on launch and in the pause menu enable/disable features; each configuration is saved as a named JSON [`GameProfile`](src/main/java/com/larsons/engine/config/GameProfile.java) ("game type") under `resources/gametypes/` and can be reselected later. |

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
│   └── GameLoop.java      Fixed-timestep loop, precise drift-free frame pacing
├── config
│   ├── GameProfile.java   A named "game type": feature toggles + values
│   ├── GameTypeStore.java List/load/save profiles under resources/gametypes/
│   └── GameContext.java   Active profile + net session; applies live settings
├── graphics
│   ├── Renderer.java      Backend abstraction (honours a ShaderChain)
│   ├── Java2DRenderer.java Default backend (double-buffered Canvas + post-FX)
│   ├── Camera.java        World→screen, per-perspective projection (+inverse)
│   ├── Perspective.java   SIDE_SCROLL | TOP_DOWN | ISOMETRIC
│   ├── SpriteSheet.java   Slice a sheet into frames
│   ├── Animation.java     Delta-timed frame animation
│   ├── AssetLoader.java   Cached image loading + placeholders
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
│   └── LevelStore.java    Per-game-type level saving (creative mode's home)
├── audio
│   └── AudioManager.java  Synthesized sound effects (JDK only, headless-safe)
├── autobattler
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
    ├── GameTypeEditorScene.java Name + configure a game type's features
    ├── MainMenuScene.java       Per-game-type main menu
    ├── MultiplayerScene.java    Host a server / join by host[:port]
    ├── PlayScene.java           Play with every enabled feature; doubles as MP client
    ├── CreativeScene.java       Creative mode: paint blocks/lights/mobs/items
    ├── AutoBattlerLobbyScene.java  Host/join an auto-battler + the pre-game lobby
    ├── AutoBattlerScene.java    The isometric auto-battler client (shop/board/combat)
    ├── AutoBattlerGuideScene.java  Illustrated field guide (rules/synergies/items/odds/units)
    └── ProfileForms.java        Shared feature options (editor + pause menu)
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

**In the demo:** every game type has shader toggles (master switch, global
strength, one toggle per effect) in the editor and the pause menu, applied
live and saved with the profile. The *Export shaders as GLSL* action writes
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
| Blocks   | every non-light block in `BlockRegistry` (terrain, ores, decoration) |
| Lights   | light-emitting blocks (torch, campfire, lantern, magic, crystal) |
| Mobs     | every species in `MobRegistry` |
| Items    | every item in `ItemRegistry`, sorted by rarity |
| Tools    | player spawn marker, eraser |

**Editor controls:**

| Input | Function |
|-------|----------|
| Left click / drag | paint the selected entry (grid-snapped for blocks; drag keeps painting) |
| Right click | erase (entities first, then the block cell) |
| Middle click | pick the hovered block into the palette |
| WASD / arrows | pan the camera |
| Mouse wheel | zoom (over the canvas) / scroll the palette (over the sidebar) |
| Tab | next palette category |
| G | toggle the grid |
| P | play-test the level in place (terrain restored on exit) |
| Ctrl+S / L / N | save / load / new level |
| Esc | back (with a save prompt offline) |

Painting works in **every perspective** — the palette paints through the
same `Camera` projection the game renders with, so you can build in
isometric view if your game type uses it.

**Play-testing** (`P`) drops a player at the spawn marker and simulates the
painted world with the real `PlayerPhysics`/mob/item code and the game
type's lighting — then restores the terrain when you return to editing.

**Levels save into the game type** (the roadmap item):
[`LevelStore`](src/main/java/com/larsons/engine/level/LevelStore.java) writes
`resources/levels/<game-type>/<level>.json`, and the game type remembers its
last saved level — *Play Level* and *Host Server* then run it.

**Online**, the editor opens from the pause menu and paints into the
<em>server's</em> world: strokes become protocol requests, the server
validates them against the host's feature toggles, and the authoritative
results broadcast to every player in real time (other players are visible
while you paint). Save/load/test stay offline-only features.

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

- **Units & shop:** a 28-unit roster across five cost tiers (1-5 gold) with
  TFT-style per-level rarity odds, rerolls (2g), and a **shared unit pool** —
  copies are finite, so contested picks really run out. Three copies of a
  unit combine into a 2-star (and three 2-stars into a 3-star).
- **Synergies:** every unit has an **origin** (Forest, Ember, Frost, Storm,
  Shadow, Holy, Wild, Mech) and a **class** (Warrior, Guardian, Archer, Mage,
  Assassin, Healer, Brawler). Fielding enough distinct units of a trait
  activates tiered team buffs — regen, attack damage, enemy slows, crit,
  team HP, and more. The live synergy panel shows counts and thresholds.
- **Items:** five components drop from creep rounds; any two combine into
  one of 15 named completed items (two components on the same unit fuse
  automatically), all pure stat bundles applied in combat.
- **Economy:** income = 5 base + interest (1 per 10 gold, max 5) + win/loss
  streak bonuses + 1 for a win. XP: +2 per round, buy 4 for 4 gold; your
  **level is your board cap** and shifts shop odds toward rarer units.
- **Abilities:** units build mana by attacking and being hit, then cast
  their class ability — fireballs (Mage, with splash), heals on the weakest
  ally (Healer), armor-ignoring strikes (Assassin, who also leaps to the
  backline at combat start), and so on.
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
  loop and rules, the gold economy, every synergy trait, the item recipe
  grid, the per-level shop odds, and the full unit roster with all of their
  statistics. Every icon (trait, item gem, odds cell, phase node, unit card)
  is clickable and pops a detail card with the fine print — per-tier effects,
  recipes, and star-scaled stats and abilities.

**Controls:** click a unit, then a cell on your half (rows nearest you) or a
bench slot, to move it — clicking an occupied spot swaps; right-click
deselects. Click shop cards to buy; **D** rerolls; **F** buys XP; **S** (or
the red button) sells the selected unit; click an item gem, then a unit, to
equip. Hover anything for a tooltip. **Esc** opens the pause overlay (the
match keeps running online — **L** leaves it).

Customization hooks are deliberately data-driven for what comes next: units,
traits, items, creep waves, pool sizes, and shop odds are all rows in
`AutoUnits` / `AutoItems` / `Trait`, and pacing/economy live in
`AutoGame.Config`.

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

In multiplayer the pause menu doesn't edit features (the server owns the
rules — editing them locally would desync the prediction); the simulation
keeps running server-side while the menu is open, again like Minecraft.

---

## Game types & feature toggles

A **game type** is a named set of enabled features and their values, stored as a
JSON [`GameProfile`](src/main/java/com/larsons/engine/config/GameProfile.java).
The idea: the engine is one big level loader, and a game type tells it which
features to turn on so the *same* engine can drive a platformer, a top-down
adventure, an isometric builder, etc.

**Flow on launch:**

1. **Startup** — pick an existing game type (to keep creating levels within it)
   or *Create New Game Type*.
2. **Editor** — name it and flip the feature toggles you want.
3. **Save** — written to `resources/gametypes/<name>.json`.
4. **Play** — levels load with only the enabled features active. Press **Esc**
   for a **pause menu** exposing the *same* toggles, so you can tune features
   mid-session and save them back to the game type.

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
[`AutoBattlerNetTest`](src/test/java/com/larsons/engine/autobattler/AutoBattlerNetTest.java),
[`AutoBattlerSceneTest`](src/test/java/com/larsons/engine/AutoBattlerSceneTest.java))
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
