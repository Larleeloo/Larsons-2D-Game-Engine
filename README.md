# Larson's 2D Game Engine

A small, **generic** 2D game engine in pure Java. It provides a clean game loop
and the essential building blocks for any 2D game — sprite sheets, level
loading, cameras with multiple perspectives, scenes, input, and a customizable
menu system — without committing to a single genre.

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
| 6 | **Editing outline of game essentials** | Working, minimal implementations of sprite sheets, level loading, and menu customization, wired together by two demo scenes. |

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

### Demo controls

- **Menu:** arrow keys / mouse to navigate, **Enter** to select.
- **Level:** `WASD` / arrows to move, **P** to cycle perspective,
  **+ / -** to zoom, **Esc** to return to the menu.
  In side-scroll the character has gravity and can jump; in top-down and
  isometric it moves freely on both axes.

---

## Architecture

```
com.larsons.engine
├── core
│   ├── Main.java          Entry point; wires up the demo scenes
│   ├── EngineConfig.java  Title, size, target FPS, update rate, perspective
│   ├── Engine.java        Wires window + renderer + input + scenes + loop
│   ├── GameWindow.java    JFrame hosting an AWT Canvas (BufferStrategy)
│   └── GameLoop.java      Fixed-timestep loop, 120 FPS cap, FPS counter
├── graphics
│   ├── Renderer.java      Backend abstraction (seam for future shaders)
│   ├── Java2DRenderer.java Default backend (double-buffered Canvas)
│   ├── Camera.java        World→screen, per-perspective projection
│   ├── Perspective.java   SIDE_SCROLL | TOP_DOWN | ISOMETRIC
│   ├── SpriteSheet.java   Slice a sheet into frames
│   ├── Animation.java     Delta-timed frame animation
│   └── AssetLoader.java   Cached image loading + placeholders
├── input
│   └── InputManager.java  Polled keyboard/mouse state (edge detection)
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
│   └── MenuTheme.java     Colours, fonts, spacing
├── util
│   └── Json.java          Tiny dependency-free JSON parser
└── demo
    ├── MainMenuScene.java Customizable menu example
    └── PlayScene.java     Level + perspectives + animated sprite example
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
- **Audio, particles, tile collision properties, a level editor** — natural next
  layers, kept out of the basic outline on purpose.

## Tests

`./gradlew test` runs headless smoke tests
([`EngineSmokeTest`](src/test/java/com/larsons/engine/EngineSmokeTest.java))
covering JSON parsing, level loading, sprite-sheet slicing, and rendering the
demo scenes off-screen — so the core is verifiable without a display.
