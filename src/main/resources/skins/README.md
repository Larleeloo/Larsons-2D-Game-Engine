# Skins: texture overrides

Drop PNG sprite sheets in this folder (any subfolder works — `units/`,
`items/`, `projectiles/`, `boards/` are provided as a starting layout) and
assign them to game textures in the **Auto Battler lobby → Customize Skins**
menu. Every game texture is overridable: units (per animation state), items,
projectiles, and the game board tiles. Anything you don't skin keeps its
built-in procedural art.

You don't have to place files by hand: the **Import sheet image…** button in
the skin menu (and **Import background image…** in the **Customize Board**
menu) opens a file browser, copies your picked image into the right subfolder
here, and fills the path in automatically.

The **Customize Board** menu also saves its board cosmetics — color scheme,
background image, and rim props — to `board_theme.json` in this folder.

A sheet is sliced left-to-right, top-to-bottom into frames you define by
**pixel width**, **pixel height**, and **frame count**, and plays at a
**framerate between 0 and 120** sprite frames per second (0 = a static
image, only frame 0 is used).

Your assignments are saved to `skins.json` in this folder — part of your
individual game files, so they persist between launches and travel with a
shared jar (bundled skins load from inside the jar too).

## Texture keys

| Key | Reskins |
|-----|---------|
| `unit/<unitKey>/<state>` | one unit in one animation state; states are `idle`, `walk`, `attack`, `cast`, `hit`, `death`. A unit with only an `idle` skin uses it for every state. |
| `item/<itemKey>` | an item gem (bench, tooltips, drag ghost) |
| `projectile/<kind>` | an in-flight projectile; kinds are `arrow`, `orb`, `bolt` |
| `board/tile_a`, `board/tile_b` | the two checkerboard tiles of the game board |

Unit keys (`squire`, `ember_imp`, ...) and item keys (`sword`, ...) are the
registry keys in `AutoUnits` / `AutoItems`; the Customize Skins menu lists
them all, so you never have to type one.

Sheet paths are resolved against the classpath first (this folder is on it,
so `skins/units/my_squire.png` works) and then the working directory.
