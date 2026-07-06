# Larson's 2D Game Engine

A small, **generic** 2D game engine in pure Java. It provides a clean game loop
and the essential building blocks for any 2D game — sprite sheets, level
loading, cameras with multiple perspectives, scenes, input, a customizable
menu system, **online multiplayer** (host a server, friends join by IP + port,
Minecraft-style), and a **shader system** (GLSL-first post-processing with a
CPU fallback that runs anywhere) — without committing to a single genre.

The engine is built to be **a giant custom level loader**: you define a
**game type** by enabling only the features your game needs (perspective, zoom,
framerate bounds, entity sizes, gravity, HUD, …), save it as a named JSON
profile, and then create levels within that type. Game types and levels are
stored independently, so one engine drives many different games.

It is a deliberately minimal starting point: a *functional outline you edit*,
not a finished game. A companion repository, **Side-Scroller-Game-Engine**, is a
feature-rich example of what a game built on these ideas can grow into; this
engine keeps only the basics so you can take it in any direction.

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
│   └── shader
│       ├── ShaderPass.java    One pass: GLSL 3.30 source + CPU implementation
│       ├── ShaderChain.java   Ordered passes, ping-pong buffers, uTime/uStrength
│       ├── Shaders.java       Built-in library + custom-pass helper + .frag export
│       ├── BloomPass.java     Multi-stage bloom (downsample → blur → composite)
│       ├── PixelShader.java   Per-pixel base class for custom effects
│       ├── ParallelRows.java  All-cores row striping (the CPU's "fragment wave")
│       └── ShaderContext.java Per-frame uniform values (CPU mirror)
├── sim
│   ├── PlayerState.java   Position/velocity/flags — what snapshots carry
│   ├── PlayerInput.java   One tick's movement intent — what clients send
│   └── PlayerPhysics.java The deterministic step shared by SP, prediction, server
├── net
│   ├── Protocol.java      Newline-delimited compact-JSON wire protocol
│   ├── GameServer.java    Authoritative fixed-tick server (host in-game or headless)
│   ├── GameClient.java    Dial host:port, send inputs, receive snapshots
│   ├── Snapshot.java      One state broadcast + arrival time (interpolation)
│   ├── NetSession.java    Active client + optional integrated server
│   └── ServerMain.java    Dedicated server entry point (--port/--level/--gametype)
├── input
│   └── InputManager.java  Polled keyboard/mouse + typed-text state
├── scene
│   ├── Scene.java         update(dt,input) / render(g,alpha) lifecycle
│   ├── AbstractScene.java No-op base with viewport + manager refs
│   └── SceneManager.java  Named scenes + fade transitions
├── level
│   ├── Level.java         Tile grid + palette + spawns
│   └── LevelLoader.java   Load a Level from JSON (or raw text, for the server)
├── ui
│   ├── Menu.java          Keyboard/mouse menu
│   ├── MenuItem.java      Label (dynamic) + action
│   ├── MenuTheme.java     Colours, fonts, spacing
│   └── ConfigForm.java    Clickable toggles / steppers / cyclers / text / buttons; scrolls
├── util
│   └── Json.java          Dependency-free JSON parser + writer (pretty + compact)
└── demo
    ├── StartupScene.java        Choose or create a game type
    ├── GameTypeEditorScene.java Name + configure a game type's features
    ├── MainMenuScene.java       Per-game-type main menu
    ├── MultiplayerScene.java    Host a server / join by host[:port]
    ├── PlayScene.java           Level + perspectives + sprite; doubles as MP client
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

`Level` is intentionally minimal — extend it with tile properties, collision
flags, multiple layers, etc. as your game needs.

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
  `ShaderPass.glsl()` into FBO ping-pong passes — the shader library needs no
  changes, by design. Kept out of the core so the engine itself stays
  JDK-only (requirement #4); the remaining work for GPU *scene* rendering is a
  backend-neutral draw API, since scenes draw with `Graphics2D`.
- **Netcode next steps:** entity replication beyond players, interest
  management for large worlds, lag compensation for hit detection.
- **Per-game-type level saving / a level editor** — next step: save levels into
  the active game type so types and levels are managed together.
- **Audio, particles, tile collision properties** — natural next layers, kept
  out of the basic outline on purpose.

## Tests

`./gradlew test` runs headless tests
([`EngineSmokeTest`](src/test/java/com/larsons/engine/EngineSmokeTest.java),
[`ConfigFeatureTest`](src/test/java/com/larsons/engine/ConfigFeatureTest.java),
[`ShaderTest`](src/test/java/com/larsons/engine/ShaderTest.java),
[`PlayerPhysicsTest`](src/test/java/com/larsons/engine/PlayerPhysicsTest.java),
[`NetworkTest`](src/test/java/com/larsons/engine/NetworkTest.java))
covering JSON read/write, level loading, sprite-sheet slicing, input edge
detection, game-type save/load, the `ConfigForm` widget's keyboard/mouse
interaction (including scrolling), rendering the scenes off-screen,
pixel-exact shader behavior + the GLSL contract and export, deterministic
player physics, and full loopback multiplayer (a real server + clients:
handshake, movement from input commands, join/leave broadcasts, version
rejection, shutdown) — so everything is verifiable without a display.
