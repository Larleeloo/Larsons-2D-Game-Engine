# Larson's 2D Game Engine

A small, **generic** 2D game engine in pure Java. It provides a clean game loop
and the essential building blocks for any 2D game — sprite sheets, level
loading, cameras with multiple perspectives, scenes, input, and a customizable
menu system — without committing to a single genre.

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
| 1 | **120 FPS** | A fixed-timestep [`GameLoop`](src/main/java/com/larsons/engine/core/GameLoop.java) renders with a configurable cap (default **120**) and sleeps between frames so it doesn't peg a CPU. |
| 2 | **Multiple 2D perspectives** | [`Camera`](src/main/java/com/larsons/engine/graphics/Camera.java) + [`Perspective`](src/main/java/com/larsons/engine/graphics/Perspective.java) support `SIDE_SCROLL`, `TOP_DOWN`, and `ISOMETRIC`, switchable at runtime. |
| 3 | **Online play (later)** | The loop updates the simulation at a **fixed rate**, decoupled from rendering. Deterministic fixed-step updates and polled input are the right foundation for netcode; the seam is documented but not yet implemented. |
| 4 | **Out of the box on any Java machine** | The engine uses **only the JDK** (Java2D / AWT / Swing). No third-party runtime dependencies — even JSON parsing is in-engine. |
| 5 | **Shader support (later)** | Rendering goes through a [`Renderer`](src/main/java/com/larsons/engine/graphics/Renderer.java) interface. The default is `Java2DRenderer`; a future GPU/OpenGL backend (likely a separate setup) can implement the same lifecycle. |
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

---

## Architecture

```
com.larsons.engine
├── core
│   ├── Main.java          Entry point; wires up the scenes + game context
│   ├── EngineConfig.java  Title, size, target FPS, update rate, perspective
│   ├── Engine.java        Wires window + renderer + input + scenes + loop
│   ├── GameWindow.java    JFrame hosting an AWT Canvas (BufferStrategy)
│   └── GameLoop.java      Fixed-timestep loop, runtime-adjustable FPS cap
├── config
│   ├── GameProfile.java   A named "game type": feature toggles + values
│   ├── GameTypeStore.java List/load/save profiles under resources/gametypes/
│   └── GameContext.java   Active profile; applies live settings to the engine
├── graphics
│   ├── Renderer.java      Backend abstraction (seam for future shaders)
│   ├── Java2DRenderer.java Default backend (double-buffered Canvas)
│   ├── Camera.java        World→screen, per-perspective projection
│   ├── Perspective.java   SIDE_SCROLL | TOP_DOWN | ISOMETRIC
│   ├── SpriteSheet.java   Slice a sheet into frames
│   ├── Animation.java     Delta-timed frame animation
│   └── AssetLoader.java   Cached image loading + placeholders
├── input
│   └── InputManager.java  Polled keyboard/mouse + typed-text state
├── scene
│   ├── Scene.java         update(dt,input) / render(g,alpha) lifecycle
│   ├── AbstractScene.java No-op base with viewport + manager refs
│   └── SceneManager.java  Named scenes + fade transitions
├── level
│   ├── Level.java         Tile grid + palette + spawns
│   └── LevelLoader.java   Load a Level from JSON
├── ui
│   ├── Menu.java          Keyboard/mouse menu
│   ├── MenuItem.java      Label (dynamic) + action
│   ├── MenuTheme.java     Colours, fonts, spacing
│   └── ConfigForm.java    Clickable toggles / steppers / cyclers / text / buttons
├── util
│   └── Json.java          Dependency-free JSON parser + writer
└── demo
    ├── StartupScene.java        Choose or create a game type
    ├── GameTypeEditorScene.java Name + configure a game type's features
    ├── MainMenuScene.java       Per-game-type main menu
    ├── PlayScene.java           Level + perspectives + sprite, honours profile
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

This is the recommended structure for adding networked play later
(requirement #3): a fixed tick is what lets clients and a server agree on
simulation state.

### Perspectives

`Camera` maps world coordinates to the screen via a per-perspective projection,
then applies zoom and centering. Orthographic perspectives (`SIDE_SCROLL`,
`TOP_DOWN`) use an identity projection; `ISOMETRIC` projects a square grid into a
diamond. Because the projection is the only thing that changes, the *same*
tile/sprite drawing code renders correctly in every perspective — see
`PlayScene`, which simply projects each tile's four world corners.

### Rendering backend & shaders

All drawing goes through the `Renderer` interface. The default `Java2DRenderer`
uses a double-buffered AWT `Canvas`, which is why the engine runs anywhere a JRE
does (requirement #4). Shader support (requirement #5) will likely need a
different backend entirely — e.g. OpenGL via LWJGL. That backend can implement
`Renderer` and keep the loop's `beginFrame → draw → present` lifecycle; the main
porting work is introducing a backend-neutral draw API, since scenes currently
draw with `Graphics2D`.

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

- **Online / multiplayer (requirement #3):** add a network service over the
  fixed-step simulation (input commands in, state snapshots out).
- **Shaders / GPU rendering (requirement #5):** add an OpenGL `Renderer`
  backend and a backend-neutral draw API.
- **Per-game-type level saving / a level editor** — next step: save levels into
  the active game type so types and levels are managed together.
- **Audio, particles, tile collision properties** — natural next layers, kept
  out of the basic outline on purpose.

## Tests

`./gradlew test` runs headless tests
([`EngineSmokeTest`](src/test/java/com/larsons/engine/EngineSmokeTest.java),
[`ConfigFeatureTest`](src/test/java/com/larsons/engine/ConfigFeatureTest.java))
covering JSON read/write, level loading, sprite-sheet slicing, input edge
detection, game-type save/load, the `ConfigForm` widget's keyboard/mouse
interaction, and rendering the scenes off-screen — so the core is verifiable
without a display.
