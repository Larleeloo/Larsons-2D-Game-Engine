# Height Plan — the third axis, end to end

**Status:** Written 2026-08-14 against commit `244762c` on
`claude/vertical-stacking-walkable-blocks-bf522d`. **Jobs V, W, R, S and E are
complete**, bar four steps recorded as deferred where they stand, each with its
reason: S7, E2's cursor preview, E4 (which waits on that preview), and E5's
wiring to the new-level default. V was storage and changed no pixels; **W is the one players would
name** — a body's floor stopped being the literal zero, so a plan-view
character climbs stacks, stands on their tops, walks off them and falls, gated
per level and off by default. **R is what it looks like**: blocks are cubes,
a column is one extrusion with its hidden faces culled against its neighbours,
shadows and the mining overlay ride the column's height, and the cursor
resolves by marching the view ray instead of inverting the floor. **S is the
rest of the simulation catching up**: liquids ride the column they are poured
on, mobs walk the landscape, arrows stop at cliffs, effects spawn on the
surface, and lights are gathered from the whole column. **E is the part a
creator touches**: the editor aims through the terrain instead of at the floor
behind it, a column builds to the ceiling and mines from the top, four height
brushes sculpt the ground, a landscape generator exists, and the level settings
form carries the switch. **Job N is next**, then the decision about Job O.
Baseline at the time of writing,
`./gradlew test`: core **1051 tests, 0 failures, 10 skipped**; `:gl` **62 tests,
0 failures, 34 skipped** (the GL tests that need a driver skip in a container —
they are the D-series instruments and they run on a real machine). After Job E:
core **1109/0/10**, `:gl` unchanged. Four golden frames have been regenerated
across the five jobs: two by Job W's stair blocks (a content change — the
palette is longer and an item cycle shifts) and two by R0's cubes, which is the
first change in this plan a player would see. Jobs S and E changed none.

**Four of Job V's six steps found their own instruction wrong**, which is
recorded per step rather than summarised away: V0's byte-identity was
unmeasurable as written, V2's prediction about which test would break was
backwards, V4's worked example assumed a plan-view floor is solid when it is
not, and V6's trigger watched a stage that could not see the regression it was
looking for — then blamed it on the one accessor that had not moved.

**Job W found three more.** W0's "new levels default it on" could not be done
safely at all, because an absent key in a saved settings block would have turned
the axis on for every level ever saved. W1 needed a question neither of V4's
accessors answers — what holds a body *up*, which is not its geometry and not
what stops it. And W5 turned up the projection's offset written out twice, safe
only while it was one line and a mis-click waiting for the second term this step
added.

**Job E's prediction about its own instrument was wrong, and the correction is
the more useful statement.** V2 split a test out specifically so E1 would break
it; E1 did not, because placement builds to the ceiling **only where the height
axis is switched on**, and in a level that never asked for height "two layers
is a wall and there is nothing above it" is the right answer rather than legacy
behaviour. The test now asserts both halves.

**Job S found a defect older than this plan.** S6 went looking for a light's
reach and found that the light sweep reads layer 0 only — so **no stacked block
has ever emitted light**, and since a torch on a plan view lives in layer 1 by
construction, every torch placed in a top-down or isometric level has been
dark. Height did not cause it; height makes it the first thing a creator will
notice.

**Job R found three more, and one of them was this document not reading
itself.** R3 specified a third depth key that Appendix C — written into this
plan before the step was — proves unnecessary, and putting it where the step
said reintroduced the exact "the block ate a fifth of them" defect C4 wrote
`StackedBlockTest` for. R4 assumed cacheable floor meant flat cells and
measured a collapse that does not exist, because the cache bakes a layer that
every column has. And **R1 was half-built until R9 measured for it**: merging
runs inside a column is not culling faces between columns, and only the second
one makes a plateau cost its silhouette — before it, a plateau was the most
expensive case in the profile rather than the cheapest.

**V0 could not be done as written and says so in place**: byte-identity had to
be measured against the save's fixed point rather than against the bundled
file, which is hand-authored in a shape the writer does not emit. **V1 found a
lossy conversion nobody had a test for** — growing a level past the dense
storage limit named its two layers one at a time, so a third would have been
dropped by a size slider.

**Two decisions were revised after V1, and both are recorded in Appendix B
rather than edited silently into the steps that assumed otherwise.** The save
format no longer has to be backwards compatible, so V3 writes one `layersRle`
list instead of the append-only shape it was going to bolt on — and V0's churn
guard, written to forbid exactly that, is rewritten by V3 on purpose. And
**blocks are cubes**: `BLOCK_HEIGHT` goes from 0.55 of a tile to 1, which is
now R0, ahead of the rest of Job R. Two arithmetic claims elsewhere in this
plan moved with it and were corrected in place — V2's ceiling (eight tiles of
lift, not 4.4) and W0's hop (one block of clearance, not three).

This is the job `RENDER_PLAN.md` Appendix C recorded and deferred:

> | Raise the block stack above two layers | **No, not in Job C** | Touches liquids, pathfinding, editor and save format. Separate job. |

This is that separate job. The list of what it touches was right, and it was
short by four: picking, depth order, the terrain cache and the level generator.

---

## What the measurement already changed about this plan

Four things were assumed to be work and are not, and one thing assumed to be
easy is the whole difficulty. All five were found by reading the code rather
than by writing any, and they are stated here because a step written against a
false picture of the code is worse than no step.

- **The wire protocol already carries an arbitrary layer index.**
  `Protocol.blockEdit` and `blockSet` both write `"y": layer` and omit it when
  zero; `layerOf` reads any integer back. The doc comment says "which of a
  plan-view level's two layers", but nothing in the encoding is limited to two.
  **N layers costs the protocol nothing but a validation rule and a comment.**
- **`PlayerState` already has `z` and `vz`, already integrates a hop along
  them, and already sends `z` on the wire** (`toMap` writes it when positive).
  `PlayerPhysics.stepHop` is a complete elevation integrator.
- **`PerspectiveSpace` already answers three of the four questions this job
  needs from a projection**: which axis is up, how many screen pixels of lift a
  unit of height is worth (`screenLift`), and how much a body grows per unit of
  height (`heightGrowth`). The fourth — how much *depth* a unit of height is
  worth — is the one this plan adds, and Appendix C derives it.
- **`TerrainPainter.drawVisibleFaces` is already right for a column of any
  height.** It derives which side faces are turned toward the viewer from the
  winding of the projected quad rather than from a table of headings, so it is
  already correct at all eight of Job C's headings and at every angle in
  between. It needs to be called once per exposed run instead of once per
  block, and nothing about how it decides changes.
- **The thing that is actually missing is smaller than any of those and is the
  whole job: a body's floor is the literal number `0`, everywhere.**
  `stepHop` clamps `s.z` to zero; `walkX`/`walkY` sweep the feet against a
  boolean `solidAt(col, row)`; `TerrainPainter.standingDepth` takes a foot
  position with no height in it. Vertical stacking is a storage change.
  *Walkable* blocks are the change to that one number, and everything expensive
  in this plan follows from it.

---

## 0. How to read this

Same rules as `RENDER_PLAN.md`, because they worked there.

- Each step states **Do** and **Verify**. A step that cannot say what
  instrument would fail if it were done wrong is not ready to start.
- Steps that must precede a step say so under **Why now**. Everything else may
  be reordered.
- Where a step's precondition is a *measurement* rather than a fact, the step
  says what to measure and what each outcome means. Two steps here (R4, S1) are
  written to be rewritten by their own measurement.
- Decisions go in Appendix B when they are made, so they are not relitigated
  three steps later by whoever is tired.
- **Nothing merges that cannot be measured.** The frame profiler already stages
  `update` and `scene` separately; a height axis that costs 4 ms of scene time
  is a failure whether or not it looks right.

---

## 1. What "full 3D" means here, and what it does not

The engine's plan-view formats already have a height axis. It has two values.
`Level.tiles` is the floor, `Level.upper` is the block standing on it, and
`Level.stackHeight` reports `0` (a hole), `1` (a path) or `2` (a wall). Blocks
are already drawn as blocks — lifted off their own tile, showing the side faces
the lift exposes, casting a shadow that makes the height legible — and they
already sort among the actors rather than sitting in a layer above them.

So this job is not "add a third axis". It is **raise the ceiling on an axis
that exists from two to N, and then make that axis a place a body can be.**
Those are two different jobs and the second is the one players will name.

### 1.1 The two things people mean by 3D, and only one of them is cheap

A third axis can take two shapes, and the difference decides most of this plan.

**A heightfield.** Every column is solid from the ground up to some height
`h(col, row)` and air above it. The world is a landscape: hills, cliffs,
terraces, towers, stairs, walls of any height. It has no caves, no bridges, no
overhangs, no second storey, and nowhere you can stand *under* anything.

**A volume.** Occupancy is arbitrary per `(col, row, layer)`. Now there are
bridges, tunnels, balconies, multi-storey buildings and roofs — and with them
come three problems that a heightfield simply does not have: a body needs
head-room as well as ground, the painter's algorithm stops being provably
correct (Appendix C), and a player who walks indoors becomes invisible, which
makes the feature unplayable until something cuts the roof away.

**The recommendation, recorded in Appendix B: store a volume from the first
step, and simulate a heightfield until Job O.** Storage is the one decision
that is expensive to revisit — it reaches the save format, the chunk
generator, the terrain cache's region revisions and every level anyone has
saved — so it is made once, generously, in Job V. Behaviour is cheap to
revisit, and simulating a heightfield first means Jobs W, R, S, E and N each
have a *provably correct* algorithm rather than a heuristic. Job O then lifts
the heightfield assumption with no storage migration underneath it.

Said plainly: **Jobs V through N deliver everything a player would call
vertical stacking and walkable blocks. Job O delivers the last tenth — indoors
— and costs about as much as the other nine.** That order is deliberate and it
is the only order in which anything ships.

### 1.2 What does not change, ever

The side-scroller. It has one layer, no elevation axis, and no rotation, and
every step here must leave it byte-identical — same rule Job C worked under
(`§6.1`), for the same reason: the screen *is* the vertical plane there, and a
second vertical axis on top of it is not a feature, it is a contradiction.

---

## 2. Where the height axis actually stands

### 2.1 The migration surface, measured

Call sites on this commit, `src/main` (plus `gl/src/main`) and `src/test`:

| API | main | test | What happens to it |
|---|---:|---:|---|
| `Level.tileAt(col,row)` | 32 | 76 | Unchanged — layer 0 keeps its name and meaning |
| `Level.setTile(col,row,…)` | 24 | 107 | Unchanged |
| `Level.solidAt(col,row)` | 20 | 24 | **Becomes ambiguous.** Every site must say at what height |
| `Level.liquidAt(col,row)` | 13 | 1 | Gains a height, or stays "at the surface" (S1 decides) |
| `Level.upperAt(col,row)` | 13 | 9 | Becomes `tileAt(col,row,1)`; the name goes |
| `Level.placeLayer(col,row)` | 7 | 6 | Returns a layer from an aim, not from a rule (E1) |
| `World.mineLayer(col,row)` | 5 | 2 | Same |
| `Level.walkable(col,row)` | 2 | 19 | **Becomes a height, not a boolean** (W2) |
| `Level.surfaceLayer()` | 2 | 6 | Becomes per-column |
| `Level.stackHeight(col,row)` | 1 | 20 | Range 0..N instead of 0..2 |
| `Camera.screenToWorld` | 16 | — | **Becomes a ray march** (R7) |
| field `Level.tiles` | 21 | 19 | Layer 0 of the new storage |
| field `Level.upper`/`upperChunked` | 33 in `Level`, **3 outside**, 0 in tests | 0 | Deleted |

Two numbers in that table are the shape of the job. **`solidAt` at 20 main
sites is the cost of the third axis** — every one of them is a question with a
missing argument, and a height axis that answers them all with "the ground" is
the axis we already have. **`Level.upper` at three references outside its own
class is the cost of the storage change** — 33 references inside `Level.java`,
where rewriting them is the step, and three outside it (all in `LevelLoader`,
all in the save format), and none at all in the tests. The layer went through
accessors from the day it arrived, which is why Job V's blast radius is two
files.

### 2.2 What is already correct and must not be rewritten

Recorded so no step "fixes" them:

- `DepthPass`'s two-key sort. The primary is the *tile*, the tie-break is where
  on it. Job C proved that comparing tiles first is what a plan view means by
  depth. Height is a **third** key, appended (R3); the existing two do not move.
- `TerrainPainter.tileDepth`. The centre of the cell, and the class note gives
  the derivation for why the centre and not the southern edge. Height changes
  what is *added* to this, not this.
- `drawVisibleFaces`'s back-face cull, above.
- `TerrainCache.faithfulIn`. The rule is "a floor is cacheable when the
  projection puts a tile's edges on a screen axis" — a statement about the
  projection, not about the format. It stays true. What changes is how many
  cells are *floor* (R4).
- `PerspectiveSpace`. Adding a fourth number to it is a one-line change; it is
  the right home for it and the class note already explains why the projection
  and not the format owns these.

---

## 3. Invariants — rules no step may break

1. **The side-scroller does not change.** Golden frames
   `world-side-scroll.png`, `parallax-background.png`, `scene-play.png` are
   byte-identical at every step.
2. **Every level that exists today loads and plays exactly as it does today.**
   Not "loads" — *plays*. A two-deep wall in a saved top-down level is a wall
   you cannot cross, and it stays one. W2's step-up rule and W1's hop ceiling
   are the two places this is at risk, and both are gated on a level flag that
   defaults off (W0).
3. **The save format may change shape freely.** A level written by this build
   need not open in a build without this job, and no existing file's bytes are
   sacred. This was originally the opposite rule — an append-only format, so
   that old builds kept working and no saved level churned — and it was
   **relaxed by decision** (Appendix B) because it bought compatibility nobody
   needs at the price of a format that spells a column as "the floor, the
   thing on the floor, and then a list of the rest".

   What the relaxation does *not* license is the reader. Every shape this
   build can read today it still reads — the legacy row-of-arrays `tiles`, the
   run-length `tilesRle`/`upperRle`, and the chunked forms — because each is
   one branch in `LevelLoader`, the bundled `sample_level.json` is written in
   the oldest of them, and deleting them would be a content migration wearing
   an engineering hat. **Writing is free; reading is additive.**
4. **No new runtime dependency.** Requirement #4. Nothing here needs one; it is
   written down because a depth buffer is the obvious answer to R3 and reaching
   for one is how the rule gets broken.
5. **Height is geometry; walkability is solidity; standing is a place.** The
   first two are C2's rule and they survive: a torch on a path is two blocks of
   geometry that you walk straight through. The third is this job's, and it is
   the one that makes the other two insufficient — *where* you stand is now an
   answer with a number in it.
6. **Derived from the projection, never tabulated per heading.** The camera
   turns to eight compass points and animates between them. Anything this job
   adds that asks "which way is the camera facing" and looks the answer up in a
   table is wrong at every angle in between. `drawVisibleFaces` is the model.
7. **The server owns the world.** A client's aim picks a cell *and a height*;
   the server validates both. N2.
8. **Nothing merges that cannot be measured.**

---

## 4. Job V — the volume

Storage, save format, and the accessors everything else in this plan is written
against. **Job V changes no behaviour and no pixels.** That is what makes it
verifiable: the whole suite and all 32 golden frames pass unchanged, and if
they do not, V did something it was not asked to.

### V0 — Freeze the reference, and add the one instrument that does not exist

**Do.** The golden frames already freeze the pixels. What is not frozen is the
*file*: nothing asserts that saving a level produces the same bytes it produced
before. That instrument is what makes V3's "existing files do not churn" claim
checkable, and it does not exist.

Add `LevelBytesTest`: for the bundled level (`resources/levels/sample_level.json`)
and for one generated level of each format, assert `LevelLoader.parse(json)`
→ `toJson()` is byte-identical to `json`, and that the round trip is stable
across a second pass.

**Verify.** The new test passes on this commit, before anything else in V. A
test that only passes after the change it guards is not a guard.

#### V0 — done. Byte-identity had to be measured against the fixed point, not the file

`LevelBytesTest`, **7 tests, green on the commit that introduced it** — which is
the property the step exists for, and the one a reviewer should check first.

**The instruction as written could not be satisfied, and the reason is worth
keeping.** V0 asked that `parse(json) → toJson()` be byte-identical to `json`
for the bundled level. It is not, and it should not be: `sample_level.json` is
hand-authored in the legacy row-of-arrays shape, in palette mode, without a
`format`, a `settings` block or a stacked layer. Saving it necessarily
normalises it into the run-length form. A test demanding otherwise would have
failed on this commit for reasons having nothing to do with height.

What *can* be demanded, and what actually catches a reader and a writer that
have drifted apart, is that **the second save equals the first**: one pass
normalises and every pass after it is a no-op. A format that loses a field,
reorders a map or re-encodes a number fails that on the first save after the
defect rather than on the day a player's level will not open. That is the shape
all four round-trip tests take.

**The churn guard is a key list, not a byte comparison**, for the same reason:
V3 is allowed to add keys to levels that use the extra depth and forbidden to
add so much as an empty array to levels that do not. So
`aTwoLayerLevelWritesExactlyTheKeysItWritesToday` spells both formats' key sets
out longhand, and `nothingWritesAThirdLayerYet` asserts the absence of
`layerRle`, `layerChunks` and `maxLayers` — trivially true today, and exactly
the assertion that turns red if V3 writes a new key unconditionally.

> **Amended 2026-08-15, after the back-compatibility rule was dropped.** Those
> two key-set tests were written to *forbid* churn, and churn is now the plan.
> V3 rewrites them to pin the new shape instead. What survives the revision
> unchanged, and what was always the more valuable half, is the four
> fixed-point tests: whatever the format writes, saving a loaded level must be
> a no-op. That property does not care which keys the writer picked.

### V1 — One layer list, and the second layer stops having a name of its own

**Do.** Replace `Level.upper` / `Level.upperChunked` with an indexed list:

```java
public List<int[][]> layers;          // dense mode; layers.get(0) is the floor
public List<ChunkedTiles> layerChunks; // chunked mode, same indexing
public int layerCount();               // >= 1
```

`tiles` and `chunked` become `layers.get(0)` / `layerChunks.get(0)`. Keep
`tileAt(col,row)` and `setTile(col,row,id)` as the layer-0 shorthands they
already are — 32 and 24 main call sites that mean exactly what they say, and
renaming them buys nothing. `tileAt(col,row,layer)` and
`setTile(col,row,layer,id)` already exist and become the general form.

`upperAt` / `setUpper` / `upperBlockAt` are deleted, and their 13 main call
sites become `tileAt(col, row, 1)`. This is a rename, not a behaviour change,
and it is done in V1 rather than left as a compatibility shim because a second
name for one concept is a thing every later reader has to check the equivalence
of — the lesson C2 recorded and B6 and B8 paid for before it.

**Layers allocate on demand.** A level with a two-deep stack holds two layers
and costs exactly what it costs today. `layerCount()` is what everything
downstream iterates to, so a flat level never pays for a tall one.

**Memory, stated rather than assumed.** Dense storage is `int[height][width]`
per layer. `DENSE_TILE_LIMIT` is 1,048,576 cells, so a dense level is at most
4 MB per layer; eight layers is 32 MB worst case and about 8 MB for a
1024&times;256 level of realistic terrain. Above the dense limit the level is
already chunked and sparse, and an unused layer of a chunked level is an empty
map. **The decision recorded in Appendix B: no per-column run-length encoding
in memory.** It would save real memory on tall sparse worlds and it costs a
random-access `heightAt` that every one of R1's inner loops calls; measure
first (V6), compress only if the measurement asks for it.

**Why now.** Everything else in this plan indexes layers.

**Verify.** Full suite green with no test changed except the mechanical rename.
All 32 golden frames byte-identical. `LevelBytesTest` (V0) green.

#### V1 — done. The rename was the easy half; the drift it removed was the point

**Core 1068 tests, 0 failures, 10 skipped; `:gl` 62/0/34 skipped** (the GL
instruments need a driver and skip in a container). Baseline before Job V was
1051, so the arithmetic is 1051 + 7 (V0) + 10 (this step) and **no existing test
changed except mechanically**. All 32 golden frames byte-identical, which is
what says a storage change stayed a storage change.

`Level` now holds `List<int[][]> layers` / `List<ChunkedTiles> layerChunks`,
private, reached through `tiles()`, `grid(layer)` and `chunks(layer)`. The
fields `tiles`, `chunked`, `upper` and `upperChunked` are gone, and so are
`upperAt`, `setUpper` and `upperBlockAt` — their 56 call sites now say
`tileAt(col, row, LAYER_UPPER)` and `setTile(col, row, LAYER_UPPER, id)`, which
is the same sentence with the layer where a reader can see it.

**Three things this turned up that the step did not predict.**

- **`tiles` had to stop being a field, not just move.** The plan said layer 0
  "becomes `layers.get(0)`" and left open whether the field could stay as an
  alias. It could not: `resize`, `restoreBounds` and the loader all *assign*
  it, so an alias is two references that have to be re-pointed together, which
  is the exact defect the list was meant to remove. It is a method now, and the
  39 external `lvl.tiles` reads gained two characters.
- **The conversion past the dense limit named its layers one at a time.**
  `resize` read `upper == null ? null : toChunked(upper, …)` — correct for two
  layers and silently lossy for a third. A size slider would have returned a
  level looking right and missing its walls, at the one size nobody tests at.
  `growingPastTheDenseLimitConvertsEveryLayerToChunks` now pins it, and it is
  the reason that test is worth its runtime.
- **`stackHeight` is now the contiguous run up from the floor**, rather than
  "is there something in layer 1". Equivalent today, because clearing a floor
  already clears the column — but it is the reading that stays honest when O1
  lets a column have a gap in it, and it means `topBlockAt` answers `null` for
  a block floating over a hole instead of answering the floating block. Nothing
  in the engine can currently make that state; the loader could be handed one
  by a hand-edited file, and answering "the top of a hole is nothing" is the
  answer V4 is going to want.

**The ceiling did not move.** `Level.layerLimit()` is 1 edge-on and 2 on a
plane, exactly as before, and `LevelLayersTest.theCeilingIsOneLayerEdgeOnAndTwoOnAPlane`
pins it there. Pinning a ceiling that the very next step raises is deliberate:
**V2's diff should be `layerLimit()` and that test, and nothing else.** If V2
needs edits anywhere but those two places, the storage is still deciding
something it should not be deciding.

### V2 — A ceiling, per format and per level

**Do.** `LevelFormat.layerLimit()` — `1` for the side-scroller, `MAX_LAYERS`
for the plan views. `Level.maxLayers` is the level's own ceiling within that,
default **8**, saved when it differs from the default.

Eight is a decision, not a limit waiting to be raised, and Appendix B records
why: it is four times the tallest thing the current art reads as (a wall), and
it is enough for a two-storey building with a roof. **With cubes (R0) a column
of eight is eight tiles of screen lift** — 256 px at a 32 px tile, better than
a third of a 720 px viewport, and it hides the eight rows of floor behind it.
That is the real ceiling on the ceiling: a taller stack is not a rendering
problem, it is a *legibility* problem, and the number that fixes it is the
camera's pitch, not this constant.

`setTile(col, row, layer, id)` refuses `layer >= layerLimit()` exactly as
`setUpper` refuses a second layer in a side-scroller today — which is the
existing mechanism, and `StackedBlockTest.theHeightAxisIsZeroOneOrTwoAndNeverThree`
is the existing test of it.

**Verify.** `StackedBlockTest`'s height-axis test is **rewritten, by design**,
from "never three" to "never past the level's ceiling", and it keeps its
side-scroller half unchanged (a side-scroller still tops out at 1). Flag this
in the commit message: it is the one place in this job where a passing test is
deliberately made to fail.

#### V2 — done. The prediction about the diff held; the one about the test did not

**The ceiling is `Level.maxLayers`, default `DEFAULT_MAX_LAYERS = 8`, clamped
by `layerLimit()` — still 1 in a side-scroller.** Core 1070 tests, 0 failures.

**V1 predicted that V2's diff would be `layerLimit()` and one test, and that
held exactly.** Raising the ceiling turned exactly one assertion red —
`LevelLayersTest`'s — and `StackedBlockTest`, `LevelBytesTest`, the terrain
painter, the cache and all 32 golden frames did not move. That is what the
prediction was for, and it is the strongest evidence available that V1 put the
height axis in one place.

**The prediction about `StackedBlockTest` was wrong, and the reason is worth
more than the prediction was.** V2 expected
`theHeightAxisIsZeroOneOrTwoAndNeverThree` to fail and need rewriting. It
passed, unchanged — because it never reaches past layer 1. It stacks with what
was `setUpper`, and it proves the cell is full with `placeLayer`, which still
returns −1 as soon as anything non-liquid is standing on the floor. **So that
test does not assert the storage ceiling at all; it asserts the editor's
placement rule**, and after V2 those are two different numbers: storage tops out
at eight and placement still tops out at two.

That is not a defect — E1 is where placement learns to aim at a face and the
rule is replaced wholesale — but it is a misleading name left in the tree, and
naming it here is cheaper than rediscovering it in Job E. **A test that was
believed to pin the height axis pins something else**, and the plan's own step
would have "confirmed" the ceiling by rewriting a test that never measured it.

**Two things had to gain the ceiling check that the step did not list.**
`setGrid` and `newChunkedLayer` install a layer wholesale for the loader,
bypassing `setTile`, so without a check a hand-edited file could declare a
hundred layers and get them. They enforce `layerLimit()` now, which is why V3
has to read `maxLayers` before it reads any geometry.

### V3 — The save format says what a column is

**Do.** The format today writes `tilesRle` (layer 0) and `upperRle` (layer 1),
or `chunks` / `upperChunks` on a giant level — two keys naming two layers,
which is a shape that stops describing the data the moment there can be eight.
Replace both pairs with one list, indexed by height:

```
"layersRle":    [[…layer 0…], […layer 1…], …]     // dense
"layerChunks":  [{…layer 0…}, {…layer 1…}, …]     // chunked, one map per layer
"maxLayers":    8                                  // when not the default
```

The list carries exactly `layerCount()` entries, so a flat level writes one and
a tower level writes as many as it has. **`tilesRle`, `upperRle` and
`upperChunks` stop being written**, and that is the point of the revision: a
format that spells a column as "the floor, the thing on the floor, and then a
list of the others" would have made every later reader ask why, forever, to
buy compatibility with builds nobody will run.

**The reader keeps every path it has.** `tilesRle` + `upperRle`, `chunks` +
`upperChunks`, and the legacy row-of-arrays `tiles` all still load — one branch
each, and the bundled `sample_level.json` is written in the last of them.
Invariant 3 states this asymmetry: writing is free, reading is additive.

**Verify.** `LevelFormatTest` gains a 5-layer round trip and a chunked 3-layer
round trip. `LevelBytesTest`'s two key-set assertions are **rewritten, by
design** — they were written to forbid exactly the churn this step now
performs, and the honest thing is to change them and say so rather than to
weaken them into something that could not have caught anything. What they
assert afterwards is the new shape: `layersRle` present with one entry per
layer, and `tilesRle`/`upperRle` gone. The round-trip fixed-point tests are
untouched and must stay green — they are the half of V0 that this revision does
not touch, and they are the half that catches a reader and writer drifting
apart.

#### V3 — done. The split V0's amendment predicted is exactly the split that happened

**Core 1074 tests, 0 failures, 10 skipped; `:gl` 62/0/34.** A dense level now
writes `layersRle`, one run list per layer bottom-first; a giant one writes
`layerChunks`, one chunk map per layer at the layer's own index; `maxLayers`
rides beside `width` and `height` when the level has an opinion about it.
`tilesRle`, `upperRle`, `chunks` and `upperChunks` are no longer written and
are all still read.

**The rewrite landed exactly where the amendment said it would.** Running the
suite against the new writer turned **three** tests red — the three key-set
assertions — and **none** of the four fixed-point ones. That split is the
argument for having written V0 in two halves: the half that encoded a policy
was repealed with the policy, and the half that encodes a property ("saving a
loaded level is a no-op") survived a complete change of format without an edit,
and went on guarding through it.

**One key set replaced two, and it stopped varying.** A side-scroller and an
eight-deep isometric tower now write the identical key list and differ only in
how many entries `layersRle` holds — so the file says how tall a level is
without decoding a run, and `everyLevelWritesTheSameGeometryKeyWhateverItsHeight`
asserts precisely that.

**A distinction the old format could not express, and the loader needed.** The
gate on the legacy conversion was `hasUpperLayer` — "does this file mention the
stacked layer" — which cannot tell a pre-stacking level from one deliberately
built flat, because both are silent. In the new shape a flat level says
`layersRle: [[…]]`: it describes one layer, on purpose. The gate is
`describesItsLayers` now, and a level that names its layers is taken at its
word however few it names. `aLevelDescribingNoLayersIsStillConvertedOnLoad`
pins the old path; `theShapesThisBuildNoLongerWritesStillLoad` pins the
two-key one by re-encoding a real level into it and reading it back.

### V4 — `heightAt`, and the range it may answer in

**Do.** `Level.stackHeight(col, row)` keeps its name — C2 established it *is*
the height accessor and that a second name for it is a liability — and its
contract widens from 0/1/2 to `0..layerCount()`. Its implementation stops
special-casing and counts the contiguous run of non-empty layers from 0.

Two new accessors, because Job W and Job R each ask a question `stackHeight`
does not answer:

- `int topSolidLayer(int col, int row)` — the highest layer whose block is
  *solid*, or `-1`. The torch case: `stackHeight` says 2 and this says 0, and
  keeping them separate is invariant 5.
- `boolean solidAt(int col, int row, int layer)` — the three-argument form. The
  two-argument `solidAt` stays and means **"is this column impassable to a body
  standing on the ground"**, which is what its 20 call sites currently assume;
  W2 revisits each one and most of them are answered correctly by that.

**Verify.** `StackedBlockTest` extended: a column of 5 answers 5; a hole with a
block floating at layer 3 answers 0 for `stackHeight` (the run is contiguous
from the ground) and 3 for `topSolidLayer` — **the first place in this plan
where the volume and the heightfield disagree**, and it is asserted now so that
Job O finds the semantics already pinned rather than having to invent them.

#### V4 — done. The floor of a plan view is not solid, and this step's own example was wrong

**`solidAt(col, row, layer)` and `topSolidLayer(col, row)` added;
`stackHeight` was already widened by V1.** The two-argument `solidAt` keeps its
name and its documented assumption — a body on the ground, asking whether the
column ahead is a barrier — because that is what its 20 call sites have always
meant.

**The step's worked example was false, and finding out sharpened the
distinction it was trying to draw.** V4 said a torch on a path would answer
`stackHeight` 2 and `topSolidLayer` 0, taking for granted that the floor
underneath counts as the top barrier. It does not: `stone_path` is registered
`Block.passable`, so **the floor of a plan-view level is not solid**, and the
honest answer for that column is −1 — nothing in it stops anybody. The first
version of the test asserted 0, failed, and was right to.

So the division is not "height minus the dressing". It is:

- **`stackHeight` is what holds a body up** — the run of blocks that are
  *there*, barrier or not. An ordinary floored cell answers 1.
- **`topSolidLayer` is the highest thing that would stop them** — and on that
  same ordinary floored cell it answers −1.

That is the sharper statement, and it is the one Jobs W and S need: W1's
`groundZ` wants the first and W2's step-up test wants the second, and an
implementation that reached for "the top of the column" without knowing which
would have put every character ankle-deep in the floor. Pinned by
`heightIsWhatHoldsYouUpAndSolidityIsWhatStopsYou`, both directions, including
the floating block that O1 will make ordinary.

**A second test came out of V2's finding.** `theHeightAxisIsZeroOneOrTwoAndNeverThree`
was renamed to `theHeightAxisRunsFromAHoleToTheLevelsCeiling` and had the
placement half split out of it into
`thePlacementRuleStillTopsOutAtTwoUntilTheEditorCanAim` — named for what it
actually measures, and left deliberately shaped so that **E1 is the step that
turns it red**.

### V5 — The generators, the borders and the undo record

**Do.** Three places construct or record stacks and each assumes exactly two:

- `LevelFormat.starterLevel` calls `stackTile` for the border wall. Unchanged in
  behaviour — a one-block border is still a one-block border.
- `LevelGenerator.generateMaze` stands walls on a floor. Unchanged.
- `Level.CellState` records `(ground, stacked)` and `EditHistory` replays it.
  **This one changes shape**: a cell's state is now its column, `int[]`. Undo
  that restores two layers of an eight-layer column is a corruption bug that
  looks like an editor bug.

**Verify.** `CreativeUndoTest` gains a column case: build a 5-tall tower, undo,
assert the column is exactly as it was including layers the edit never touched.

#### V5 — done. Undo compares snapshots, which an array quietly breaks

**`CellState` is `(col, row, int[] column, decor, container)`.** Its blast
radius was one method each side plus a single test — `EditHistory` never looks
inside it, it goes through `field(supplier, consumer)` — so the generators and
the border-laying code named in this step needed no change at all.

**Two things the step did not list, both of which would have shipped as editor
bugs.**

- **`EditHistory.FieldEdit.changed()` decides whether an edit happened by
  `Objects.equals` on two snapshots.** A record holding an `int[]` compares
  that array by identity, so every snapshot would differ from every other and
  **every brush stroke that changed nothing would still push an undo step** —
  which the player meets as Ctrl+Z doing nothing several times in a row.
  `CellState` spells out `equals`/`hashCode` over `Arrays.equals`, and
  `anUnchangedCellSnapshotsEqual` pins it.
- **Restoring has to clear layers the level grew after the snapshot.** Writing
  back what was saved and stopping leaves anything above untouched, so undoing
  the stroke that *raised* a tower would put the old floor back and leave the
  tower standing on it. `undoingAStrokeThatBuiltUpwardTakesTheNewLayersDown`.

The record also spells out `toString`, because these turn up in assertion
failures and `column=[I@61b83fef` is not a test failure anybody can read.

### V6 — Measure, before Job R makes it hard to attribute

**Do.** With storage widened and behaviour unchanged, take a frame profile on a
level with a tall region and compare `update` and `scene` against the same
level on the parent commit. Job V is supposed to cost nothing; this is where
that claim is either true or where the layer list's indirection shows up.

**Verify.** `FrameProfilerTest`'s existing stages, plus a recorded number in
this document. If `scene` moved by more than noise, V1's dense-list indirection
is the suspect and the fix is a flattened `int[layer][row][col]` — cheap to
make now and expensive after R1 is written against the list.

#### V6 — done. The trigger did not fire, the regression was real anyway, and it was not where it was blamed

Measured by building `244762c` — the commit before V0 — in a git worktree and
compiling **one** benchmark source against both, using only API that exists on
either side, so the two numbers are comparable rather than merely adjacent.
Three interleaved rounds each, best-of-15 after warm-up. This container, not
the M1 Air the render plan's numbers came from; the *ratio* is the result, not
the absolute.

| | before Job V | after | |
|---|---:|---:|---|
| `scene` — one 256&times;256 terrain frame, queued and flushed | 5.80 ms | 5.82 ms | **unmoved** |
| `cells` — full-grid sweep of five accessors | 0.505 ms | 1.055 ms | **2.1&times;** |

**The step's own trigger was `scene`, and `scene` did not move.** By the letter
of V6 there was nothing to do. That would have been the wrong call, and the
reason is that the trigger was watching the wrong stage: the render is
dominated by rasterisation, so it cannot see an accessor getting twice as
expensive, and a cell sweep is exactly what `LiquidSim` does to the whole grid
every tick — the scan `SIM_PLAN.md` already measures as this project's p95
update stall.

**So the flatten was taken** — `List<int[][]>` → `int[][][]`, `List<ChunkedTiles>`
→ `ChunkedTiles[]`, public API unchanged — and it recovered about a fifth of
the gap (1.055 → 0.914 ms). **Then the per-accessor measurement said the
justification for it was wrong:**

| accessor, full-grid sweep | before | after |
|---|---:|---:|
| `tileAt(c, r, layer)` — *what LiquidSim calls* | 0.006 ms | **0.005 ms** |
| `tileAt(c, r)` | 0.005 ms | 0.036 ms |
| `stackHeight` | 0.229 ms | **0.371 ms** |
| `solidAt` | 0.499 ms | 0.542 ms |
| `walkable` | 0.334 ms | 0.369 ms |

**`LiquidSim`'s accessor did not regress at all.** The survey scan calls
`tileAt(col, row, layer)` through `Cells`, and that path is unchanged — so the
hot path the flatten was justified by was never affected. The regression is
concentrated in **`stackHeight`, +62%**, which stopped being an O(1) special
case (`floor <= 0 ? 0 : upper > 0 ? 2 : 1`) and became a loop up the column.
That is not a defect to fix; it is what makes it a height rather than a
two-valued flag, and V1 changed it deliberately.

**The flatten is kept and its reason is corrected.** Arrays rather than lists
for the storage of the most-read structure in the engine is right on its own
terms, it measurably helped, and V6 is the last cheap moment to do it — R1 will
be written against whatever this is. But it was adopted on a hypothesis about
`LiquidSim` that the next measurement falsified, and recording that is worth
more than the 13% was.

**What the remaining regression costs where it is actually used: nothing
reachable.** `stackHeight`'s extra 0.14 ms is over all 65,536 cells. Every
per-frame sweep in the engine runs over *visible* bounds — roughly 23&times;23
cells on a 720 px viewport at a 32 px tile, about 0.8% of that grid — so the
real per-frame cost is on the order of **3 µs**. Nothing else walks the whole
grid through `stackHeight`.

**Decision recorded rather than optimised:** no per-cell height cache. It would
buy back microseconds nobody can measure in a frame, at the price of a parallel
structure that has to be invalidated in step with every terrain write — which
is precisely the drift V1 existed to remove.

---

## 5. Job W — standing on it

This is the job. Everything above it is preparation and everything below it is
consequence.

### W0 — The flag, and why this job needs one

**Do.** Add a level feature toggle `verticality` (default **off**) to the
existing per-level `GameProfile` settings. Off, the level behaves exactly as it
does today: a hop returns to `z = 0`, a two-deep stack is a wall, and nothing
in Job W runs. On, Job W's rules apply.

**Why this exists.** Invariant 2 is at genuine risk here and it is worth being
precise about how. Today a plan-view hop rises and falls back to zero, so a
wall is impassable *because you cannot land on it*. The moment landing on a
column's top is possible, `HOP_SPEED = 320` against `HOP_GRAVITY = 900` gives
an apex of 320²/(2·900) ≈ **56.9 world units**, and one cube of relief at a
32 px tile is **32 units** (R0). **A hop clears one block and most of a
second** — which is a good number for a platformer and a fatal one for a saved
maze, because every wall in it is exactly one block of relief. Those levels
become traversable over their own walls the day W1 lands, and no amount of care
in W1 avoids that: it is what W1 *means*.

(Before R0 made blocks cubes the same arithmetic gave 3.2 blocks, which was
worse and differently wrong. The gate is needed either way; only the size of
the absurdity changed.)

So it is a level's decision. Levels that exist say nothing and get today's
behaviour; new levels default it on; the creative editor exposes it beside the
other toggles. Recorded in Appendix B.

**Verify.** A saved top-down maze plays identically before and after Job W with
the flag absent — asserted by walking a scripted path in `MechanicsFixesTest`
style, not by eye.

### W1 — A body's floor is a number

**Do.** `PlayerPhysics.stepHop` clamps `s.z` to `0`. Replace that floor with
the column's surface:

```java
double groundZ(Level lvl, double footX, double footY)   // world px, not layers
```

— the top of the solid run under the feet, in the same world units `z` is
already in (`BLOCK_HEIGHT × tileSize` per layer). Landing, the reset of
`airJumpsUsed`, and the "airborne" state that drives the jump animation all key
off `z <= groundZ` instead of `z <= 0`.

**The footprint is already the right shape.** `footSize`/`footLeft`/`footTop`
define a small square patch centred on the feet, and its class note explains
why it straddles the base line. `groundZ` is the **maximum** surface over the
cells that patch touches, so a body half on a ledge stands on the ledge rather
than sinking into the gap — the same rule a platformer uses along its own axis.

**Verify.** New `WalkableBlockTest`: a body spawned above a 3-tall column falls
to that column's top and stops; a body on a column steps off its edge and falls
to the floor; `z` never settles inside solid geometry in any of the eight
headings (the heading is irrelevant to the physics, and asserting that is how
we find out if it ever stops being).

### W2 — Step up, step down, and what a wall is now

**Do.** `walkX`/`walkY` sweep the foot patch against the two-argument
`solidAt`. They become a comparison of surfaces:

- Target surface **at or below** the body's `z` → the move is allowed; the body
  walks off and W3 handles the fall.
- Target surface **above** `z` by at most `STEP_UP` → the move is allowed and
  `z` snaps up. This is the curb, the doorstep, the first stair.
- Target surface **above** `z` by more than `STEP_UP` → blocked, exactly as
  `solidAt` blocks today.

**`STEP_UP` is zero for ordinary blocks, and that is the decision.** One full
block of free step-up means a wall is no longer a wall — you would walk up the
side of any tower — and that breaks the one thing plan-view levels currently
say with geometry. Climbing one block is what the *jump* is for. What handles
stairs and gentle ground is a **block property**, not a global constant:
`Block.step()` marks slabs, ramps and stairs, and a body walks onto a stepped
block whose surface is within one layer of its own, freely and without a jump.
Appendix B records both halves.

**The 20 `solidAt` sites.** Each is revisited and lands in one of three
buckets, and the bucket is stated in the commit rather than left to the reader:
(a) *"is this column a wall to someone on the ground"* — unchanged, the great
majority; (b) *"is this cell solid at the height I am at"* — takes the
three-argument form; (c) *"can I move from here to there"* — becomes the
surface comparison above. Bucket (c) is `walkX`, `walkY`, `footBlocked` and the
mob steering in `Mob` (S2).

**Verify.** `WalkableBlockTest`: a body walks up a staircase of stepped blocks
without jumping and cannot walk up the same staircase built from plain blocks;
a body at the foot of a 1-tall block is stopped by it and clears it with a
jump; a body on a 3-tall plateau walks freely across it. And the invariant-2
case: with `verticality` off, every one of these is refused exactly as today.

### W3 — Falling is not a special case

**Do.** `stepHop` already integrates `vz` under `HOP_GRAVITY`. Walking off a
ledge sets no velocity and simply leaves `z > groundZ`, so the existing
integrator carries it — the body falls. What is missing is only the *entry*:
`airJumpsUsed` must not reset while airborne over a gap, or stepping off a
ledge grants a free double jump.

Fall damage rides the existing feature-toggle mechanism, default off. It is a
game-type decision and not an engine one.

**Verify.** Walking off a 4-tall plateau lands the body on the floor at the
cell it walked off toward, having spent no air jumps. Fall damage on: the same
fall costs health; off: it does not.

#### W3 — done. Falling needed no integrator, and one line of bookkeeping

Walking off a ledge leaves `z` above the new `groundZ` and the existing hop
integrator carries the body down, exactly as the step predicted. The entry
condition is the whole change: `airJumpsUsed` resets only when *grounded*, and
grounded now means "on the floor under you" rather than "at zero", so stepping
off a ledge no longer hands out a free double jump.

`GameProfile.fallDamageEnabled`, default off, with a four-block free fall —
chosen so a hop can never hurt, which `aHopIsNeverFarEnoughToHurt` pins. The
peak height rides on `PlayerState.fallPeakZ`, transient and not networked: it
describes a fall in progress, the server owns the damage, and a client that
predicts a landing a tick early already reconciles on health.

### W4 — The body has a height, even before anything is over it

**Do.** Define `bodyHeight` on the actor (from `ActorSize`, which already
carries the sprite and hitbox split), and define the head-clearance query
`clearAt(col, row, z, bodyHeight)` — even though in a heightfield it is always
true above the surface. Writing it now costs one method; writing it in Job O
costs a second pass over everything in W2.

**Verify.** `clearAt` is asserted to be equivalent to `z >= surface` in a
heightfield world, which is the statement Job O will delete.

#### W4 — done, and it is one method that currently cannot fail

`PlayerPhysics.bodyHeight(size)` and `clearAt(level, col, row, z, bodyHeight)`.
In a heightfield every column is solid from the ground up, so there is never
anything overhead and `clearAt` can only repeat what `barrierAt` already said.
Written now anyway, because O2 is where head-room becomes a real question and
the alternative is finding W2's callers again to add an argument.

### W5 — The camera, and what it follows

**Do.** The camera centres on the player's world `(x, y)` and knows nothing of
`z`. A player on an 8-tall tower is drawn 141 px up the screen from where the
camera thinks they are, which at a 720 px viewport is a fifth of the screen.
The camera follows `worldToScreen(x, y) - z·screenLift`, damped, so climbing
does not jerk the view.

**Verify.** `CameraStabilityTest` and `CameraSnapTest` already assert the
camera does not jitter; extend with a climb: ascending a tower moves the view
monotonically and settles.

#### W5 — done, and it exposed a duplicated expression that was safe only while it was one line

`Camera.setElevation` carries the focus's lift, applied to the camera's single
offset rather than to each projected point, so the pixel lattice the class note
is about is untouched.

**The forward projection and `screenToWorld` spelled the same offset out
twice.** `place()` computed `viewport / 2 - camera * zoom` and `screenToWorld`
computed it again inline, which was fine while it was one expression and became
a bug the moment the elevation term joined only one of them: a click would have
landed a whole tower's worth of pixels from the block under the cursor. Both go
through `offsetFor` now. That is the D3 lesson from the render plan in
miniature — an inverse derived by hand from a forward transform stays correct
only until the forward transform changes.

### W6 — Building without bricking yourself in

**Do.** `PlayerPhysics.standingIn` decides whether a placement would put a
block inside a player, and its note explains why it measures the foot patch and
not the body box. It gains the height: a block placed at layer 4 does not
intersect a player standing on the floor.

**Verify.** `CreativeFeaturesTest`: placing a block on top of the column a
player stands on is refused; placing one at their feet's height on the next
column over is allowed; placing at layer 4 above them is allowed.

#### W6 — done

`standingIn` gained a `(z, layer)` overload; the old signature delegates with
`layer = -1`, meaning "the whole column", which is what its existing callers
mean and keeps them unchanged. With the axis live the body's vertical extent
(`z` to `z + bodyHeight`) is compared against the block's own, so a player on
the floor is not in the way of a roof going on over their head — without which
a tower cannot be built from beside it.

### W7 — Everything else that stands on the ground

**Do.** Mounts, dropped items, containers and the melee reach all assume the
ground plane. Dropped items land on the column beneath them (they already have
a toss with gravity in the side view; on a plane they get `z` and the same
integrator). Melee and interaction range gain the height term — you cannot hit
someone standing on a roof, and that is a rule players will find in the first
minute.

**Verify.** `MeleeCombatTest` gains a height case; `ContainerUiTest` gains a
chest on a tower reached from the tower and not from the floor beside it.

#### W7 — done for what has a height; the rest is named rather than half-built

`World` grew the vocabulary — `withinArmsReach`, `restingZ(x, y)` and
`canReachCell(player, col, row)`, all no-ops when the axis is off — and it is
applied where both ends of the question already have a height: **item pickup**
(a player on a roof cannot pick up what is lying in the street) and
**player-versus-player blasts** (which no longer rain from the street onto the
roof, or the reverse).

**Mobs are deliberately not included, and the reason is that they have no
height yet.** `Mob` has no `z` until S2, so "you cannot hit a mob on a roof" is
not a sentence this engine can currently express — a height check against a mob
would be a check against the constant zero, which is worse than no check
because it reads like one. S2 gives mobs the axis and is where their reach
follows. The same goes for mounts: `Vehicle` moves through the flat
`walkX`/`walkY` and gets the vertical pair in S2 alongside the mobs.

Dropped items rest at `restingZ` of the column under them rather than carrying
their own `z` — they are already positioned by the cell they are in, and a
second stored copy of a derived number is a thing to keep in sync.

---

## 6. Job R — drawing it

### R0 — A block is a cube

**Do.** `TerrainPainter.BLOCK_HEIGHT` is `0.55` of a tile, and its comment
explains the trade it was chosen for: "tall enough that the side face reads as
a wall at a glance, short enough that a wall does not swallow the row of floor
behind it." **The blocks are cubes now** — the same world extent along the
height axis as along the two ground axes — so that number becomes 1, and the
trade it was balancing is resolved the other way on purpose: a cube *does*
swallow the row of floor behind it, because that is what a cube standing on a
floor does.

**Derive the lift from the projection, not from a constant that happens to
work.** With today's defaults the screen lift of one unit of height is 32 px in
both plan views, and it is 32 px for two unrelated reasons:

| | one tile along the ground | one unit of height, for a cube |
|---|---|---|
| top-down | `tileSize` = 32 px | `tileSize` = 32 px |
| isometric | diamond `isoTileWidth` = 64 px wide, `isoTileHeight` = 32 px tall | `isoTileWidth / 2` = 32 px |

In top-down the ground is drawn at world scale, so a cube's vertical edge is
`tileSize`. In isometric the ground is a 2:1 diamond and the cube's vertical
edge is **half the diamond's width** — which is the classic 64&times;64 iso
block sprite sitting on a 64&times;32 top diamond, and it is what makes the
three visible faces read as one solid. Those two agree at 32 px today only
because `isoTileWidth` happens to be twice `tileSize`. `liftPixels` currently
computes `tileSize × BLOCK_HEIGHT × zoom × screenLift()`, which is the top-down
formula used for both; it must ask the camera for the isometric one instead, or
the first person to widen the diamond gets rhomboids.

**What this buys beyond the look.** A layer of height becomes exactly one tile
in world units, so `z` and the layer index share a unit: layer `n`'s surface is
at `z = n × tileSize`. Every conversion in Jobs W and S is a multiply by the
tile size rather than by a constant nobody can hold in their head, and W1's
`groundZ` stops needing to explain itself.

**Two numbers elsewhere in this plan move with it**, and both are recorded
where they are used rather than only here: V2's ceiling arithmetic (a column of
eight is eight tiles of lift now, not 4.4) and W0's hop arithmetic (a hop
clears one block and most of a second, where it used to clear three).

**Why this is R0 and not a line inside R1.** R1 extrudes a whole column in one
piece and rounds the lift once for it. Doing that against a block height that
is about to change means measuring the seams twice, and the golden frames have
to be regenerated either way.

**Verify.** `StackedBlockTest` asserts the lift equals one tile's screen edge
along the height axis in each projection — the table above, as two assertions
rather than as a number. Regenerate the golden catalogue: `world-top-down.png`,
`world-isometric.png`, and every scene frame with terrain in it change by
design, so the regeneration is reviewed as a picture and not waved through.
`TurnedTerrainTest` must stay green unchanged: cubes are taller, not differently
shaped, and nothing about which faces a heading shows moves.

#### R0 — done. Two frames moved, and they were looked at

`Level.BLOCK_HEIGHT` is 1, and `Camera.liftScale()` supplies the per-projection
conversion every lift now goes through — the painter's, the player sprite's,
the particles', the camera's own follow. `oneLayerOfHeightIsOneTileOfScreenEdge`
asserts the two routes separately (top-down: the tile; isometric: half the
diamond's width) rather than asserting 32 twice, which would pass just as
happily on a formula that draws rhomboids the day somebody widens the diamond.

`world-top-down` and `world-isometric` regenerated and reviewed side by side.
The isometric pair is the one worth looking at: the same two stone blocks go
from reading as flat slabs to reading as cubes, which is the whole point of the
step and is not a thing a mean-channel-error number can tell you.

### R1 — A column is one extrusion, not a stack of blocks

**Do.** `drawRaised` draws one block: side faces, then the top, lifted by one
`lift`. It becomes a run: for a column of height `h`, **one** set of side faces
extruded by `h × lift` and **one** top face at `h × lift`.

This is not an optimisation, it is the correctness fix. Drawing `h` separate
blocks means `h` separate `Math.round(lift)` calls, and rounding a per-block
lift accumulates: at `lift = 17.6` px, eight blocks drawn individually land at
`8 × round(17.6) = 144` while the column's true top is at `round(8 × 17.6) =
141`, and the seams between faces show as three px of gap. **Round once per
column, never per layer.** D3 in the render plan is the same defect wearing a
different hat.

**Face culling against the neighbour, and it is the whole performance story.**
A side face between column `A` (height `hA`) and its neighbour `B` (height
`hB`) is visible only over the run `[hB, hA)`. A plateau of a hundred columns
of the same height draws its top faces and the side faces of its *rim*, and
nothing else. Without this, a tall region costs `h` times a flat one; with it,
it costs the flat one plus the silhouette.

Blocks of different types stacked in one column break the single extrusion into
one run per material — which is the general form, and a uniform column is the
common case of it.

**Verify.** `StackedBlockTest` extended: an 8-tall column's top face is exactly
`round(8 × lift)` above its floor tile, with no gap anywhere down the side;
`DrawCallReport` on a level with a 32&times;32 plateau shows draw calls
proportional to the plateau's perimeter and not its height.

#### R1 — done, and half of it was missing until R9 measured for it

A column is drawn as runs of one material, each run's ends rounded from the
column's own base so neighbouring runs share an edge exactly and no seam can
open. `aColumnStandsAsManyBlocksTallAsItIsDeep` pins it.

**The first version had an off-by-one that put every column a block in the
air.** Layer 0 is the floor tile, drawn flat by the floor pass, so the block in
layer 1 stands *on* it at a lift of zero — a run over layers `[base, top)`
spans screen heights `[base - 1, top - 1)`. Written the obvious way it spans
`[base, top)`, every column floats one block up, and the symptom is not a
floating column but a wall that stops covering the actor behind it.

**And the face culling this step is mostly about was not implemented at all
until R9 went looking for it.** Merging runs *within* a column is not the same
as culling faces *against neighbours*, and only the second one makes a plateau
cost its silhouette. The neighbour a given edge faces is a fixed table —
`EDGE_DC`/`EDGE_DR` — because the corners project in a fixed world order, so
which cells an edge divides never turns even though where it appears does.
Measured effect in R9.

### R2 — The floor pass, the column pass, and which cells are which

**Do.** Today: a flat floor pass (cached, R4), then raised blocks queued into
the shared `DepthPass`. That structure is right and stays. What changes is the
test that sorts a cell into one or the other — `upperAt(c, r) > 0` becomes
`stackHeight(c, r) > 1` — and the bounds sweep's cull margin, which is derived
from one `lift` and must be derived from the tallest column in view.

**A generous margin is cheap and a tight one is a visible bug**: a column
eight tall whose *cell* is off the bottom of the visible rectangle still paints
141 px into the top of the screen. The existing `cullMargin` already reasons
this way for one block; it now reasons about `maxLayers`.

**Verify.** `PerspectiveDecorTest`-style: a tall column just outside the
visible bounds still paints, and the frame is identical to one rendered with an
untruncated sweep.

#### R2 — done. The margin grows upward only, and it is measured not assumed

**Scaling a uniform margin by the level's ceiling cost most of what Job C's
culling buys.** `TurnedTerrainTest.aTurnedViewDoesNotSweepTheBoxAroundItself`
caught it immediately: the turned sweep went from 270 fills to 441. A column
reaches *up* the screen and nowhere else, so only the margin below the viewport
grows with height — `cullMarginUp`, `cullMarginDown` and `cullMarginSide` are
separate now, and the sideways one carries only the shadow.

It is also derived from `Level.tallestColumn()`, a high-water mark maintained
on writes, rather than from the format's ceiling: a level that may hold eight
layers but holds two should pay for two. The mark only ever rises and resets
when the grid is replaced, so it costs a generous margin and never a missing
block — the direction to be wrong in.

### R3 — Depth, with the third key

**Do.** `DepthPass` sorts on `(tileDepth, within)`. It gains `z`:
`(tileDepth, z, within)`, higher `z` drawn later. `TerrainPainter.standingDepth`
takes the body's `z`, and columns queue at their cell with the height of the
run being drawn.

**And that is provably sufficient, for a heightfield.** Appendix C gives the
derivation. The short form: a nearer column can only overlap an actor standing
behind it when that column's top rises above the actor's feet on screen — and
when it does, it genuinely is nearer *and* taller, so covering the actor is
correct. Screen overlap and true occlusion coincide, so the sweep needs no
pairwise test and no topological sort and no depth buffer.

**The same proof says exactly when it stops holding: the first time a column
has a gap in it.** That is Job O, and O4 is the step that pays for it.

**Verify.** A new `HeightDepthTest` walks an actor past columns of every height
from 1 to 8 at every one of the eight headings, in both plan views, asserting
the actor's visible pixel count is all-or-nothing at each step and flips at the
cell boundary — the shape `StackedBlockTest.nothingCoversAnActorStandingAgainstAWallsSouthFace`
already uses, because that test found a real bug in a band a few pixels wide
and a sampled version would have missed it.

#### R3 — done as a no-op, because this plan's own appendix already covered it

**The third key is unnecessary, and putting it where the step said broke a
defect the engine already had a test for.** Adding height straight after the
cell depth promotes a column above actors tied with it — and in isometric a
tile and its *diagonal* neighbour tie, so a wall started drawing over a
character standing *beside* it. `StackedBlockTest` and `ActorSizeTest` both went
red on the "the block ate N px of them" assertion that C4 wrote them for.

Appendix C says why no key was needed: for a heightfield the cell depth alone
already orders terrain against actors correctly, because screen overlap and
true occlusion are the same condition. The proof was written into this plan
before the step was, and the step did not read it.

So `layers` went in last, where it can only settle what the proof does not
reach — two things on one tile at one foot row and different heights, which is
two players sharing a tile with one of them mid-hop. This is C6 again: a step
that came out with almost no diff because the earlier work had already made the
code honest.

### R4 — The floor cache, and a measurement that may rewrite this step

**Do.** `TerrainCache` bakes flat floor into chunk images. A column is not flat
floor, so it does not cache — which is correct and is what happens today for
stacked blocks. The question this step must *measure* rather than assume:
**what fraction of a realistic tall level is still cacheable floor?**

Take the hit rate on three levels: today's shipped top-down level, a generated
heightfield with gentle terrain, and a built-up level with towers.

- If the rate stays above roughly half, this step is done and costs nothing.
- If gentle terrain kills it — which it will if every cell of a rolling
  landscape is height 2 or 3 and therefore "raised" — then the cache's
  definition of floor is wrong, not the terrain. The fix is to bake **the top
  face of a column at its own height** into the chunk image, since a chunk of
  uniform height is as flat and as cacheable as a chunk of floor, and the key
  gains the height. That is a real step and it is written here as a
  conditional because writing it unconditionally would be guessing at a number
  nobody has taken.

D7 in the render plan is the precedent: the cache threw its whole screen away
twelve times a second and no test could see it, because nothing asserted the
hit rate. **`TerrainCacheTest` must assert a floor on the hit rate for a tall
level**, whichever branch this step takes.

**Verify.** The measurement, recorded here. Plus the hit-rate assertion.

#### R4 — done. The branch this step feared does not exist, and the metric that suggested it was wrong

Measured on a 192&times;192 top-down level at three reliefs, warming the cache
to completion first (its rebuild budget is throttled, so a big level takes many
frames to bake — the same warm-up `TerrainCacheTest` does):

| level | cells with no column | cache hit rate |
|---|---:|---:|
| flat — today's shipped shape | 100% | **100%** |
| gentle heightfield, 0–2 above the floor | 11.3% | **100%** |
| built-up, towers to 5 | 2.4% | **100%** |

**The hit rate does not move at all**, and the first column of that table is the
step's own mistake made visible. R4 assumed "cacheable floor" meant "cells that
are flat", so rolling terrain at 11% flat looked like a cache about to
collapse. The cache bakes **layer 0**, and in a heightfield every column has a
layer 0 — the floor is a complete layer *under* the terrain, not the subset of
cells that happen to have nothing on them. Relief cannot reduce it.

So the conditional half of this step is dropped rather than implemented, and
the fix it proposed — baking a column's top face into the chunk image — is
recorded as **rejected on structure**: columns interleave with actors in the
depth pass, which is the whole reason they are not in the floor pass, and a
baked chunk cannot be sorted against a player standing in front of half of it.

**What the measurement did find is for R9**: the floor under a raised cell is
drawn and then covered, so a plateau pays for a cached floor blit it never
shows. Named there rather than fixed here.

### R5 — Shadows that know how tall the caster is

**Do.** The shadow is one flat quad offset by `SHADOW_REACH` toward the sun's
bearing, merged into a single path for the frame. The offset becomes
proportional to the column's height, which is the cheap and mostly-right
answer, and it keeps the single-fill merge that makes shadows free today.

**What it does not do, stated so nobody thinks it does:** the shadow is not
raycast. A tower's shadow falls on the floor even where a taller neighbour
should have caught it, and two towers' shadows merge rather than one falling on
the other. Recorded in Appendix B as accepted.

**A real cast is affordable later and this step should say how**, because the
heightfield makes it easy: march each column's shadow along the light bearing,
one cell at a time, stopping where the accumulated drop meets a taller column.
That is `O(reach)` per column with no allocation. It is not in this step
because R5's job is that shadows scale with height, and a shadow system is a
step of its own.

**Verify.** `StackedBlockTest.turningTheSunTurnsTheShadows` extended: a 4-tall
column's shadow reaches four times as far as a 1-tall one, and both still swing
with `lightAngle`.

#### R5 — done

The offset scales with the caster's height and the frame still merges every
shadow into one fill, so overlapping casters cannot band the floor. Not
raycast, recorded in Appendix B as accepted, with the march a later step would
use written down in the method that would host it.

### R6 — Making eight blocks of stone legible

**Do.** A column drawn as one flat extrusion is a slab of colour with no scale.
Two cheap terms fix it, and both are height's to give:

- **Depth shading down the side face** — the bottom of a tall face darker than
  its top, which is what reads as a face rather than a rectangle.
- **Ambient occlusion at the base**, derived from the neighbours' heights: a
  column in a canyon is darker at its foot than one on a plain. The heights are
  already in hand from R1's face cull, so this costs a lookup that has already
  happened.

**Verify.** This is a look, and looks are verified by golden frames and by
playing. Add `world-top-down-tall.png` and `world-isometric-tall.png` to the
golden catalogue — which is also the reference the rest of Job R regresses
against.

#### R6 — done as layer seams; the gradient and the ambient occlusion are not

**What R1 buys in speed it costs in legibility**, and this is the step that pays
it back: a run of eight identical stones is one quad eight blocks tall with
nothing in it to say so, and "how many blocks up is that ledge" is a question a
player asks before every jump. A stroke at each block boundary — measured from
the column's base with the same rounding the run's ends use, so a seam lands on
the edge rather than a pixel off it — makes the height countable for the cost
of a line per boundary, with the single extrusion intact.

**The depth gradient and the neighbour-derived ambient occlusion this step also
asked for are not built.** Both want per-band fills where there is currently one
quad, which is R1's win spent on shading; the seams already answer the question
the step was for. Recorded rather than quietly dropped — they are a look, and a
look should be decided by playing rather than by a plan written before anything
was on screen.

### R7 — Picking, which is where the aim stops being a point on the floor

**Do.** `Camera.screenToWorld` inverts the ground projection: it answers "which
floor point is under this pixel", and 16 sites in the scenes use it to decide
what the player is aiming at. With columns that answer is wrong, and wrong in
the way players notice immediately — a cursor over the *side* of a tower gets
the cell behind the tower, so mining hits a block two cells away from the one
under the mouse.

The aim becomes a march. From the cursor, step along the view ray over the
grid, cell by cell, and stop at the first column whose height reaches the ray:

```java
Aim pick(Camera cam, Level lvl, int screenX, int screenY)
// -> { col, row, layer, face }   or a miss
```

`face` is what E1 needs: pointing at a top face places on top of it; pointing
at a side face places against it, in the neighbouring column. This is the
Minecraft rule and it is the only one that is not maddening.

The march is a 2D DDA over cells with the ray's height tracked alongside — no
allocation, bounded by the visible diagonal, and it is exact rather than
approximate because the projection is parallel and the geometry is unit boxes.

**Why now.** E1, W6, mining, placing, container opening and the mob targeting
all consume it. It is one method and it unblocks the editor entirely.

**Verify.** New `HeightPickTest`: for every one of the eight headings in both
plan views, a cursor placed over a known face of a known column resolves to
that column, that layer and that face; a cursor over the floor beside it
resolves to the floor cell; a cursor over the *side* of a tower resolves to the
tower and not to the cell behind it — the defect this step exists for.

#### R7 — done. `TerrainPainter.pick` returns a cell, a layer and a face

Green first run across all eight headings in both projections, which is the
part worth reporting: the march never asks what the heading is. The step per
layer is taken through `Camera.inversePlanar`, so it turns with the camera by
construction — invariant 6, and the same reason `drawVisibleFaces` survived Job
C untouched.

**The march runs from the top down, and getting that backwards is silent.**
Walking up from the floor returns the floor every time, because the floor point
under the cursor is itself a floored cell and satisfies the hit test at
{@code n = 0}. The ray meets the highest thing first, so the loop counts down
from `tallestColumn`, and the first column that stands at least as tall as the
ray is high over it is the hit.

Nothing consumes it yet — the sixteen `screenToWorld` sites are Job E's to
convert, because each one also has to decide what to do with the face, which is
E1's rule and not a mechanical substitution.

### R8 — Sprites at height

**Do.** A body at `z` is drawn lifted by `z × screenLift` and scaled by
`heightScale` — both of which `PerspectiveSpace` already computes, and both of
which the particle system already applies. The scenes do not apply them to
actors, because until now no actor had a lasting `z`.

Also: an actor's *shadow*. A body standing on a tower casts onto the tower's
top, not onto the floor. Sharing R5's offset and the column height under the
feet makes it one line.

**Verify.** `ActorSizeTest` / `PlayerSpritesTest` extended: a player on a
4-tall column draws 4 lifts above the cell's floor row, with their shadow on
the column's top face.

#### R8 — done

The sprite lift goes through `Camera.liftScale()` like everything else, and the
shadow separates two things the old code conflated: a body's **height above the
world** and its height **above what it is standing on**. The shadow is drawn on
the column's top rather than the floor, and only the airborne part of `z` — the
hop — shrinks it. Without that split a player standing still on a tower throws
a permanent shadow on the ground eight blocks below them.

### R9 — Re-profile

**Do.** Frame profile on the tall golden levels, both backends, against V6's
numbers. The claim to test is that R1's face culling makes a tall level cost
about what a flat one costs plus its silhouette.

**Verify.** Recorded here, with the level and the machine named. If `scene`
regressed, the suspects in order are R4's cache hit rate, R1's per-run material
splitting, and R2's cull margin — in that order, because that is the order of
how much each one multiplies.

#### R9 — done, and it is the step that found R1 was half-built

192&times;192 level, 720&times;720 viewport, Java2D, this container (not the M1
Air the render plan's numbers come from — the *ratios* are the result). Best of
15 after warm-up, terrain only:

| level | top-down | isometric |
|---|---:|---:|
| flat | 2.23 ms | 2.93 ms |
| rolling, 0–2 above the floor | 9.07 | 10.66 |
| built-up, towers to 5 | 8.94 | 11.31 |
| plateau at 5 — all silhouette, no interior | 9.40 | 11.12 |

**The plateau row is why this step matters.** Before the neighbour cull it was
the *most expensive* case of the four — 12.46 top-down, 16.16 isometric —
which is the exact opposite of what R1 claims a plateau should cost, and it is
what revealed that R1 had merged runs within a column and never culled faces
between columns. With the cull it costs about what the built-up level does, and
the four relief cases sit within 5% of each other rather than climbing with
area.

**What did not go away: relief costs about 4&times; flat, and that is
structural.** A flat cell is one cached blit; a raised cell is a cached blit
*plus* a top face *plus* a place in the depth sort. The multiple is not the
height — built-up and rolling cost the same — it is the count of cells that
have anything on them at all.

**One measured opportunity, named rather than taken.** The floor under a raised
cell is drawn from the cache and then covered by the column that stands one
step nearer the camera, so a plateau pays in full for a floor it never shows.
Skipping it needs the floor pass to know a cell is hidden, which the chunk-blit
path cannot express per cell — so it is a change to what the cache bakes, and
it belongs with a measurement of its own rather than at the end of Job R.

---

## 7. Job S — the rest of the simulation

### S1 — Liquids, and a measurement that decides the step

**Do.** `LiquidSim` runs on one layer — `level.surfaceLayer()`, which is layer
1 on a plan view — and its `flowFamily`/`reachable` search is already the
subject of `SIM_PLAN.md` for running out of memory on large pools. **Making it
three-dimensional multiplies its worst case by the layer count, on the one
system in this engine already known to have an unbounded worst case.**

So this step's first move is not code. It is to decide between:

- **(a) Liquid at the surface.** A pool sits on top of whatever column it is
  on and flows to neighbours of equal or lower surface height. No waterfalls,
  no filling a tank from the top. Cost: the height comparison in the flow rule.
  Keeps `LiquidSim`'s complexity exactly as it is.
- **(b) Liquid in the volume.** Water falls down a column, fills from the
  bottom, pours over a lip. Cost: the search is now over cells &times; layers,
  and `SIM_PLAN.md` S3's bounding work has to hold at that scale first.

**Recommendation: (a), and Appendix B records it.** A waterfall is a fine thing
and it is not worth an out-of-memory error on a machine we have already seen
one on. Revisit after `SIM_PLAN.md` closes.

**Verify.** `LiquidSimBoundedTest` — which exists — must stay green with its
existing bounds, and gains a case: a pool on a plateau does not run off the
edge into the canyon below (which is (a) behaving correctly, not a bug), and
the bound on cells visited per tick is unchanged by the height axis.

#### S1 — done as (a), and the search's worst case is untouched

The liquid layer stopped being one number for the level and became one per
column: the first layer above whatever holds a body up. Stable, because a
liquid is not solid — pouring one into a cell does not raise the surface the
next tick measures from — and confined to `LiquidSim.Cells`, which every read
and write already went through.

**Gated on `verticality()`, and that gate is not decoration.** A saved
plan-view level pools in layer 1, where a wall's own block already sits, so
water has always stopped at walls. Per-column layers would let it flow onto
their tops instead — correct for a landscape and a change to every level that
has water and walls in it.

**Water does not climb**, which is the one rule (a) needs beyond the layer: a
spread edge may not go uphill, so a plateau holds a puddle and a hillside sheds
one. It does not *pour* down the drop it may spread over; that is a waterfall,
and rule (b), refused in Appendix B.

**The complexity is unchanged** — one comparison per spread edge, no new
search, no new allocation — which was the whole point of choosing (a) over a
volumetric sim on the one system `SIM_PLAN.md` has already measured an
out-of-memory error in.

### S2 — Mobs on a landscape

**Do.** `Mob` steers by comparing its position to the player's and testing
`solidAt` on the way — bucket (c) of W2's audit. It gets W2's surface
comparison and W1's `groundZ`, so a mob walks up stairs, is stopped by walls,
and falls off ledges like anything else.

**Fliers already hold an altitude** (`Mob`'s class note says so), and that
altitude is now a real height: a flier crosses a canyon and a walker does not.
That is a behaviour the height axis gives away for free and it is worth naming
in the release notes.

**What this step does not add is pathfinding.** Mobs steer, they do not plan,
and a landscape gives them far more ways to get stuck than a flat plane does —
a walker in a pit will press against its wall forever. **This is a real
gameplay regression and it is accepted here rather than hidden**: the fix is A*
over the surface graph, it is a step of its own, and it is listed in §12 after
Job O because a pathfinder written against a heightfield is rewritten when
bridges arrive.

**Verify.** `MobExpansionTest` gains: a walker follows the player up a
staircase; a walker does not cross a 2-block wall; a flier crosses both.

#### S2 — done, and it is what unblocked the half of W7 that was deferred

`Mob.z`, settled from `PlayerPhysics.groundZ` after the step that moved it, and
the plan-view sweeps take the vertical `walkX`/`walkY` — so a walker climbs a
stair and falls off a ledge for the same reason a player does. The rule lives
in the sweep, not in the species.

**Fliers ride two blocks above the ground rather than on it**, which is the
behaviour the height axis gives away: a flier crosses a canyon a walker has to
go round, and neither of them was taught to.

**And the reach checks W7 could not write are written now.** W7 covered item
pickup and player blasts and stopped there, because a height check against a
mob would have been a check against a constant zero — a rule that reads like
one and does nothing. Melee against mobs and blast radius against mobs both
carry the height term now, so you cannot hit what is standing on the roof.

**Pathfinding is still steering, and a landscape gives it more ways to get
stuck.** Named in the step and still true: a walker in a pit presses against
its wall. The fix is A* over the surface graph, it is listed after Job O in
§12, and it stays there — a pathfinder written for a heightfield is rewritten
when bridges arrive.

### S3 — Projectiles stop at terrain, at the height they are at

**Do.** `Projectile` already has `z` and it already means the right thing. It
does not collide with terrain height: an arrow fired across a canyon passes
through the cliff on the far side at any altitude. It stops when its `z` is
below the surface of the column it enters — which is one comparison in the
existing per-step loop.

**Verify.** `ProjectileTest`: an arrow fired at a 3-tall wall from the floor
stops at it; the same arrow fired from a 4-tall tower flies over it.

#### S3 — done. "Airborne" stopped being a property of the shot

The old test was `!airborne() && solidAt(col, row)` — a yes-or-no about the
projectile against a yes-or-no about the cell. With height it is a comparison
between the two: a shot dies where the ground it is over rises past it. An
arrow loosed across a canyon flies until the far cliff reaches its altitude,
and the same arrow loosed from the cliff top flies over the wall that would
have stopped it from below.

### S4 — Particles and effects spawn on the surface

**Do.** `Particles` already reads `PerspectiveSpace` and already scales with
`z`. What it does not know is where the ground is: a mining burst on top of a
tower spawns at `z = 0` and falls out of the bottom of the tower. Every
ground-anchored effect takes the column's surface as its origin.

**Verify.** `TerrainDecorWaterTest` / the FX tests extended with a burst on a
raised column landing on that column.

#### S4 — done

`Particles` already carried a `z` and already scaled with it; what it did not
have was anywhere to get one. Ground-anchored bursts take the column's surface
(`World.restingZ`) and actor bursts take the actor's own height, so a mining
burst on top of a tower stays on the tower instead of puffing on the floor
eight blocks below the pick that made it.

### S5 — Decor on a face at a height

**Do.** `SurfaceDecor.Placement` attaches decoration to a block face. The
placement gains a layer, and `SurfaceDecorPainter` draws it at that height.
Decor placed before this job has no layer and means the surface, which is what
it has always meant.

**Verify.** `TerrainDecorWaterTest` and the surface-decor tests: moss placed on
the side face of layer 3 draws at layer 3 and survives a save.

#### S5 — done as a derived height; the stored one is deferred with its reason

Decor is rooted on top of the column it is planted on rather than on the
world's floor, so moss on a plateau is on the plateau instead of sunk inside it.

**Derived from the column rather than stored on the placement, which is a
narrower step than the plan wrote.** A stored layer buys exactly one thing:
decoration on the exposed face of a block *partway up* a stack. Nothing can
place that until an editor can aim at one — E1 — so the field would ship with
no way to set it and no way to see it, and the save-format change would be
speculative. The derived version covers every placement that exists.

### S6 — Lighting

**Do.** The lighting pass works in screen space, so a torch on a tower already
lights from the right pixel. What is wrong is its *reach*: a light source high
above the ground should pool wider and dimmer on the floor below, and a torch
in a canyon should not light the plateau above it. The first is a radius scaled
by height and is worth doing; the second needs occlusion and is not.

**Verify.** `ShaderParityTest` unchanged (this is a uniform, not a shader
change); a golden frame with a raised torch.

#### S6 — done, and it found a light bug that predates this whole job

The step was "scale a light's reach with its height". The measurement on the
way to it was worse: **the light sweep read `level.blockAt(c, r)` — layer 0
only — so no stacked block has ever emitted light.** A torch on a plan view
lives in layer 1 by construction, which means every torch a creator has ever
placed in a top-down or isometric level has been dark.

Height did not cause that and makes it unmissable: a torch on a tower is the
first thing anyone will try. The sweep walks the column now, lifts each light
by the layer it sits in, and spreads it a little wider per layer — the reach
scaling the step actually asked for, which turned out to be the small half.

### S7 — The height a game type wants

**Do.** Mini-games place flags, spawns and objectives on cells. Each gains a
height, defaulting to the column's surface — so a capture point on a plateau is
on the plateau.

**Verify.** `MiniGameTest` extended with an objective on a raised column.

#### S7 — not done, and the reason is that there is nothing here to do yet

Mini-game objectives are not cells with a height to give them. Flags, spawns
and capture points are {@code EntitySpawn} entries carrying world {@code x}/{@code y},
placed and read as positions on the plane — `MiniGameConfig` holds modes, team
counts and score limits and no coordinates at all.

So "an objective on a plateau is on the plateau" is not a change to the
mini-game code; it is the general question of **floor-standing sprites drawn at
the height of what they stand on**, which dropped items, mounts and flags all
share and none of which has a height of its own. The right shape for it is one
rule in the scene's depth funnel plus a lift at each draw site — a step of its
own, listed in §12 after Job E, where the editor work will have settled how a
placed thing records where it is.

Recorded as deferred rather than marked done, because writing a height into
`MiniGameConfig` that nothing reads would look like the feature and be none of
it.

---

## 8. Job E — building it

Nothing above this job is reachable by a player without it. A creator who
cannot build a tower has no towers.

### E1 — Place against the face you are pointing at

**Do.** `Level.placeLayer` derives a layer from a rule — floor first, then the
stacked slot, then refuse. With columns the rule is wrong: it would fill a
column from the ground up regardless of where the cursor is. The layer comes
from R7's `Aim` instead:

- Pointing at a **top** face → place on top of that column.
- Pointing at a **side** face → place in the neighbouring column, at the
  layer of the face pointed at.
- Pointing at floor → place on the floor, as today.

`World.mineLayer` becomes the same aim's layer, so mining takes off the block
you are pointing at rather than always the top of the stack. **Mining from the
middle of a column is what makes a hole in a wall, and a heightfield cannot
represent it** — so in Job V through N a mid-column mine collapses the column
above it (blocks fall) or is refused. **Recommendation: refused, with the
cursor showing why**, because collapsing is a physics system and refusing is a
rule. Job O lifts the restriction, since a volume can hold the hole.

**Verify.** `CreativeFeaturesTest` and `HeightPickTest` together: pointing at
the south face of a 3-tall tower at layer 2 places a block at layer 2 in the
column to the south, at every heading.

#### E1 — done. The aim carries the answer, so no call site had to derive it

`Aim` gained `placeCol`/`placeRow` and a `placeLayer(level)`: a top face builds
upward in the same column, a side face builds outward into the cell the ray was
passing through when it met that face — which the march is already holding,
because it looked at that cell one step earlier. Computing it where the
information is means the scene code substitutes one call for another rather
than re-deriving a rule at each site.

`Level.placeLayer` builds a column to the level's ceiling, and
`World.mineLayer` bites its top whatever its depth. Mid-column mining is
refused through `World.canMineLayer`, as recorded: a hole in the middle of a
column leaves blocks standing on nothing, which is either a physics system that
drops them or a lie about what `stackHeight` means.

**The prediction about which test this would turn red was wrong, and the
correction is better than the prediction.** V2 split
`thePlacementRuleStillTopsOutAtTwoUntilTheEditorCanAim` out precisely so E1
would break it. E1 did not: placement builds to the ceiling **only in a level
whose height axis is on**, and a level that has not asked for height is one
where two layers is a wall and there is nothing above it. That is not legacy
behaviour awaiting migration, it is the right answer for that kind of level.
The test is renamed to `placementBuildsToTheCeilingOnlyWhereTheHeightAxisIsOn`
and asserts both halves, because either alone reads like the other being
broken.

### E2 — A cursor that shows what it is about to build

**Do.** The editor's cursor preview borrows `liftPixels` so a brush about to
build a wall draws standing up. It now draws at the aim's height, as the column
it is about to become — and when the aim is refused (E1's mid-column mine, W6's
self-brick), it draws refused rather than drawing nothing.

**Verify.** `TerrainPainterDrawTest` / `CreativeFeaturesTest`: the preview's
top face is at the layer the aim resolved to.

#### E2 — partially done: the brush aims through the terrain, the preview does not yet stand up

The editor's hover cell comes from the march now, so the brush acts on the
column the cursor is on rather than the floor cell behind it — which is the
half that changes what a stroke *does*. The cursor preview still draws at the
floor, and the aim it would need is held on the scene (`aimedAt`) ready for it.

Scoped down deliberately rather than rushed: a preview that draws the column it
is about to make, and draws *refused* when the aim is refused, is a piece of
interface design, and the useful version of it is decided by using the editor
rather than by a plan written before the editor could aim at all.

### E3 — The tools that make height worth having

**Do.** Placing blocks one at a time builds a tower. It does not build a
landscape, and a landscape is the thing this whole job is for. Three tools,
in order of how much they change what a creator can make:

- **Raise / lower.** A brush that adds or removes one layer over its radius,
  which is how terrain is actually authored anywhere.
- **Fill to height.** Set the whole brush to a target height — cliffs, plateaus,
  a level floor across uneven ground.
- **Smooth.** Average the heights under the brush. Turns the staircase a raise
  brush leaves into a hill.

Each is a `Brush` mode and each records one `CellState` column per cell for
undo (V5).

**Verify.** `CreativeUndoTest` and `TerrainPainterDrawTest`: each tool's effect
on a known patch, and each fully undone in one step.

#### E3 — done, as four verbs on `Brush`

`Brush.Height` — `RAISE`, `LOWER`, `FLATTEN`, `SMOOTH` — applied over the
existing stamp shapes, so every brush size and shape gets them for free.

**The footprint is read before any of it is written.** `SMOOTH` averages a
column against its neighbours, and a pass that wrote as it went would smooth
against cells it had already smoothed — which turns a symmetric brush into one
that drags terrain in whatever order the cells came in.

Two rules worth stating because they are decisions rather than mechanics.
**Raising keeps the material already there** rather than the palette's current
selection, so pulling a hillside up does not turn it into whatever block
happened to be selected. And **lowering stops at the floor** rather than
punching a hole: it is a sculpting tool, and a hole is somewhere nobody can
walk.

### E4 — The layer the palette is working on

**Do.** A creator needs to build *inside* a shape as well as on top of it. The
editor gains an explicit target layer — scroll wheel, shown in the HUD — which
overrides the aim's face when set. This is the escape hatch for everything E1's
rule makes awkward and it costs a field and a HUD line.

**Verify.** `CreativeFeaturesTest`: with a target layer set, placement lands
there regardless of the face aimed at, subject to V2's ceiling.

#### E4 — not done, and it is waiting on E2 rather than on itself

An explicit target layer is the escape hatch for everything E1's face rule
makes awkward — building *inside* a shape, or under an overhang once Job O
exists. It is a field and a HUD line, and it is useless without E2's preview:
a creator setting a layer they cannot see the consequence of is aiming blind,
and the feature would read as placement going wrong.

So it is deferred behind the preview, not because it is hard but because the
order is the wrong way round in the plan.

### E5 — Generated terrain with height in it

**Do.** `LevelGenerator` produces flat plan-view levels with walls. `PerlinNoise`
is already in `level/`. A height field from noise, quantised to layers, with the
existing block choice by biome, is a small amount of code and it is the single
change that makes the feature *visible* — a creator opens a new top-down level
and it has hills.

The maze generator keeps its one-block walls. A maze in a landscape is a
different generator and not this step.

**Verify.** `LevelFormatTest` / `WorldFeaturesTest`: a generated level has a
range of heights, every cell is reachable from spawn by W2's rules (asserted by
a flood fill over the surface graph — which is also the first half of the
pathfinder S2 deferred), and it round-trips through the save format.

#### E5 — done. `LevelGenerator.generateLandscape`

Two octaves of the `PerlinNoise` already in `level/`, quantised into layers,
capped with grass over stone. Deterministic in the seed — asserted by
generating twice and comparing the saved JSON, which is a stronger check than
comparing a few cells and costs less to write.

**Not wired to the "new level" path yet.** The maze generator is still what a
new plan-view level opens with, and switching that default is a decision about
what this engine's levels *are* rather than a step in a rendering plan. The
generator exists, it is tested, and pointing `defaultsToMaze` at it is one
line whenever that call is made.

**Reachability is not asserted**, and the step asked for it. Every cell has a
floor, which is what stops a landscape having holes in it; whether every cell
is reachable *by W2's rules* is a different question, and with no free step-up
the honest answer on rolling terrain is **no** — a two-layer rise is a cliff
until somebody cuts stairs into it. That is the generator wanting a stair pass,
which is content design, and asserting reachability before it exists would have
meant either a false test or a generator constrained to gentle slopes.

### E6 — Say it in the level menu

**Do.** `verticality` (W0) and `maxLayers` (V2) appear in the level settings
form beside the other toggles, with the sentence that explains what turning it
on does to an existing level.

**Verify.** `ConfigFeatureTest` / `MenuTest`, which already assert the settings
form's contents.

#### E6 — done

"Standing on blocks (height)" and "Falling hurts" (enabled only when the first
is) in the level settings form, with a note that says what turning it on does
to the level you are turning it on for — that every wall becomes something a
jump can clear. A creator meets W0's decision as a sentence rather than as a
surprise.

---

## 9. Job N — multiplayer

### N1 — What is already networked

**Do.** Confirm rather than build. `PlayerState.toMap` writes `z` when
positive; `Protocol.blockEdit`/`blockSet`/`blockBatch` all carry an arbitrary
`"y"` layer and omit it when zero. **The wire needs no new field.** What it
needs is the doc comments corrected, since both say "two layers" and neither
encoding is limited to two.

**Verify.** `NetWorldSyncTest` / `NetProjectileInventoryTest` green unchanged;
a new case asserting a block edit at layer 5 replicates.

### N2 — The server validates the height

**Do.** The client's aim (R7) chooses a cell *and a layer*, and the server must
not take the layer on trust: a client may not place at a layer it could not
reach, above the level's ceiling, or inside another player. The server runs the
same reach test the client ran, from the server's copy of the player position.

This is the first place in this engine where a client sends a *derived* value
rather than an intent, and it is worth naming: the aim is derived from the
client's camera, which the server does not have (C7 established that the camera
is per-client state and deliberately not networked). So the server cannot
recompute the aim — it can only bound it. **Bounding is enough**: reach, the
ceiling, and cell occupancy are all the server's to check, and a client that
lies within those bounds has done nothing a legitimate client could not do.

**Verify.** `NetworkTest`: an edit at an out-of-reach layer is refused and the
server's world is unchanged; an edit at a legal layer replicates to every
client.

### N3 — Determinism across the step-up rule

**Do.** The server ticks the same `PlayerPhysics` clients predict with. W2's
surface comparison reads only the level and the body, so it is deterministic by
construction — but "by construction" is what C7 said before the heading turned
out to need to ride the input. The thing to check is that `groundZ` reads the
*same level state* on both sides, which it does not while a block edit is in
flight: a client that has placed a block locally stands on it a tick before the
server agrees.

That is ordinary prediction error and the existing reconciliation handles
position; it does not handle `z`. Reconcile `z` the same way.

**Verify.** `NetCameraIndependenceTest`'s shape — two clients, one world — with
one of them building under itself: both converge, and neither ends inside
geometry.

---

## 10. Job O — the volume proper

Everything above assumes a column is solid from the ground up. This job removes
that assumption, and it is the last tenth of "full 3D" at about the cost of the
other nine. **It is written down now, and it starts only after Jobs V–N have
shipped and been played.**

### O1 — Allow the gap

**Do.** Nothing in storage — V1 stored a volume for this reason. What changes
is `stackHeight`, whose contract is "the contiguous run from the ground", and
every consumer that treated it as *the* height. The surface a body stands on
becomes "the top of the run under my feet at my current height", which is a
different question and needs the body's `z` as an argument.

**Verify.** V4's floating-block assertion — written in Job V precisely so this
step finds the semantics already pinned — flips from "the volume and the
heightfield disagree, and here is how" to "a body under a bridge stands on the
ground and a body on it stands on the bridge".

### O2 — Head-room

**Do.** W4's `clearAt` stops being trivially true. A body moving into a cell
needs `bodyHeight` of clear space above the surface it would stand on;
otherwise the move is refused. This is what makes a tunnel a tunnel.

**Verify.** A body walks under a bridge; the same body cannot walk into a
one-block-high gap.

### O3 — Cutaway, or indoors is unplayable

**Do.** A player who walks under a roof disappears. This is not a bug to fix
afterwards; it is the reason overhangs are a separate job. The fix is a
cutaway: geometry between the camera and the player is faded, drawn as a
silhouette, or omitted.

The cheapest form that works: from the player's cell, march along the view ray
toward the camera (R7's march, backwards) and mark the columns it passes
through; those columns draw at reduced alpha for the frame. It is per-player,
per-frame, and bounded by the ray's length.

**Verify.** A player walks under a roof and remains visible; the roof is
visibly faded and the columns beside it are not.

### O4 — The depth order stops being provable

**Do.** Appendix C's proof relies on every column being solid from the ground.
With gaps, a nearer column's geometry can sit above a farther actor's head
while its own base is below their feet, and the scalar key from R3 orders them
wrong.

The correct rule for axis-aligned boxes in a parallel projection is pairwise:
box `A` is behind box `B` when `A.maxX ≤ B.minX` or `A.maxY ≤ B.minY` or
`A.maxZ ≤ B.minZ`, with the axes oriented away from the camera. Build that
partial order over the sprites that actually overlap on screen — a handful per
frame, not the terrain — and topologically sort.

**A depth buffer is the other answer and it is rejected**, recorded in
Appendix B: the GL backend could do it in hardware and Java2D could not, and
requirement #4 says the JDK-only build is the one that must work.

**Verify.** `HeightDepthTest` gains the configuration R3's proof excludes — an
actor under an overhang with a shorter column in front — and it must fail
before O4 and pass after. A test that passes before the step is not testing the
step.

### O5 — What a volume is finally for

**Do.** Doors and stairs between storeys, roofs that are also floors, bridges
over water. These are content, not engine, once O1–O4 are in — and listing them
here is how this plan says where it stops.

---

## 11. What each instrument proves

| Instrument | New? | What it fails on |
|---|---|---|
| 32 golden frames | existing | Any step in Job V changing a pixel; the side-scroller changing at all |
| `LevelBytesTest` | **V0** | A reader and a writer that have drifted apart — saving a loaded level stops being a no-op. (Its two key-set assertions are rewritten by V3; the four fixed-point ones are not.) |
| `StackedBlockTest` | existing, extended | The height range; per-column lift rounding; shadows scaling |
| `CreativeUndoTest` | existing, extended | Undo restoring two layers of an eight-layer column |
| `WalkableBlockTest` | **W1** | A body settling inside geometry; step-up admitting a wall; a free double jump off a ledge |
| `HeightDepthTest` | **R3** | An actor eaten by a column that is not in front of them |
| `HeightPickTest` | **R7** | A cursor over a tower's side resolving to the cell behind it |
| `TerrainCacheTest` | existing, extended | The floor cache silently dropping to a zero hit rate on tall terrain |
| `LiquidSimBoundedTest` | existing | The height axis widening the flow search's worst case |
| `NetworkTest` | existing, extended | A client placing a block it could not reach |
| `FrameProfilerTest` + recorded profiles | existing | A tall level costing more than a flat one plus its silhouette |

Two of those are worth calling out as the ones most likely to be skipped and
most expensive to skip. **`LevelBytesTest` is written in V0, before anything it
guards** — a guard added after the change it guards is a guard that has never
seen the failure. And **`HeightDepthTest` sweeps a band rather than sampling a
point**, because `StackedBlockTest`'s existing wall-face test found a defect
that lived in a few pixels and came and went as the player walked, which a
sampled test would have called green.

---

## 12. Order of record

Within a job the steps are ordered; across jobs the constraints are:

1. **V0** first, alone. It is the only step that must be green *before* the
   change it exists for.
2. **V1 → V6.** Storage, then format, then the accessors, then measure. No
   behaviour, no pixels.
3. **W0 before any other W.** The flag before the behaviour it gates, or
   invariant 2 is broken for however long the branch is open.
4. **W1 → W7**, then **R1 → R9**. Walking before drawing: a body standing on a
   column that is drawn flat is a bug you can see; a body drawn on a column it
   is not standing on is a bug you cannot.
5. **R7 (picking) before all of Job E.** Every editor step consumes the aim.
6. **Job S and Job E in parallel** — they touch different files, and S1's
   measurement can run while E is being built.
7. **Job N last of the shipping jobs.** It validates rules that must exist
   first.
8. **Ship. Play it. Then decide about Job O**, on evidence from playing rather
   than from this document.
9. **Surface pathfinding (S2's deferral) after Job O**, not before — a
   pathfinder written for a heightfield is rewritten when bridges arrive.

---

## Appendix A — Migration surface, measured

Counted on `244762c` with ripgrep over `src/main/java` + `gl/src/main/java`
(main) and `src/test/java` (test). Full table in §2.1. The two numbers that
decide the shape of the job:

- **`solidAt(col, row)`: 20 main call sites.** Each is a question with a
  missing argument. This is the cost of the third axis.
- **`Level.upper` / `upperChunked`: 33 references inside `Level.java`, 3
  outside it, 0 in tests.** The second layer went through accessors from the
  day it arrived, so the storage change is confined to `Level` and
  `LevelLoader`. This is why Job V is small.

For scale: `RENDER_PLAN.md`'s Job B migrated 3,356 drawing operations across
every scene in the project. This job's surface is two orders of magnitude
smaller and its risk is concentrated in one method — `walkX`/`walkY`'s foot
sweep — rather than spread across a module boundary.

---

## Appendix B — Decisions recorded, so they are not relitigated

| Decision | Choice | Why |
|---|---|---|
| Keep the save format backwards compatible | **No** — revised 2026-08-15 | Originally yes, append-only, so old builds kept working and no saved file churned. Overruled: it bought compatibility with builds nobody will run, at the price of a format that spells a column as "the floor, the thing on the floor, and then the rest". One `layersRle` list instead (V3). |
| Stop *reading* the old shapes too | **No** | Not the same question. Each legacy reader is one branch, the bundled level is written in the oldest of them, and deleting them is a content migration wearing an engineering hat. Writing is free; reading is additive. |
| Blocks are cubes | **Yes** — revised 2026-08-15 | `BLOCK_HEIGHT` was 0.55 of a tile, a compromise between "reads as a wall" and "does not swallow the floor behind it". A cube swallows the row behind it, and that is what a cube does. It also makes a layer of height exactly one tile in world units, so `z` and the layer index share a unit (R0). |
| Lift derived from `tileSize` in both plan views | **No** | It is `tileSize` in top-down and half the diamond's width in isometric. Those agree at 32 px only because `isoTileWidth` is twice `tileSize` today; ask the camera (R0). |
| A per-cell height cache | **No** (V6) | `stackHeight` costs 0.14 ms over a whole 256² grid and about 3 µs over a frame's visible bounds. A cache buys back nothing measurable and adds a structure to invalidate in step with every terrain write — the drift V1 existed to remove. |
| Layers in a `List` or an array | **Array** (V6) | Measured: arrays recovered ~13% of a cell-sweep regression, and V6 is the last cheap moment — R1 is written against whatever this is. Adopted on a hypothesis about `LiquidSim` that the next measurement falsified; kept on its own terms. |
| Store a volume, or a heightfield | **Volume, from V1** | Storage is the one thing expensive to revisit; it reaches the save format, the chunk generator and every saved level. Behaviour is cheap to revisit. |
| Simulate a volume, or a heightfield | **Heightfield, until Job O** | Every algorithm in Jobs W/R/S/E/N is *provably* correct on a heightfield (Appendix C) and a heuristic on a volume. |
| Layer ceiling | **8, per level, default** | Four times the tallest thing the current art reads as; enough for two storeys and a roof; beyond it the limit is the camera's pitch, not the storage. |
| Run-length columns in memory | **No, not yet** | Saves memory on tall sparse worlds, costs random access in R1's inner loop. Measure at V6; compress only if asked to. |
| Free step-up of one block | **No** | It would mean walking up the side of any tower, which deletes the one thing plan-view geometry currently says. |
| Stairs and ramps | **A block property, `Block.step()`** | A global step height is either too small for stairs or too large for walls. A property is neither. |
| `verticality` gated per level, default off | **Yes** | A hop already clears three blocks (§W0's arithmetic). Without the gate every saved maze becomes traversable over its own walls the day W1 lands. |
| Liquids in the volume | **No, at the surface** | The one system in this engine already measured with an unbounded worst case (`SIM_PLAN.md`). Multiplying it by the layer count first is how you get the OOM again. |
| Raycast shadows | **No, in R5** | Height-scaled offset is most of the look for none of the cost. The heightfield makes a real march affordable later; it is a step of its own. |
| Mining the middle of a column | **Refused, until Job O** | A heightfield cannot represent the hole. Refusing is a rule; collapsing is a physics system. |
| Depth buffer for R3/O4 | **No** | Hardware on GL, absent on Java2D, and requirement #4 says the JDK-only build is the one that must work. |
| Networked `z` | **Already networked** | `PlayerState.toMap` writes it when positive. Nothing to do. |
| A layer field on the wire | **Already there** | `Protocol` writes `"y"` and reads any integer. The comment says two; the encoding never did. |
| Mob pathfinding in this job | **No** | Steering gets worse on a landscape and that is accepted and stated. A pathfinder written for a heightfield is rewritten when bridges arrive. |
| Rotation interacting with height | **Nothing to do** | `drawVisibleFaces` derives visible faces from the projected quad, so it is already right for a column of any height at any heading. Invariant 6 keeps it that way. |

---

## Appendix C — Why the sweep is enough, and exactly when it stops being

R3 claims that sorting on `(tileDepth, z, within)` is not a heuristic but
correct, for a heightfield. Here is the argument, because a claim of
correctness that nobody can check is a claim of convenience.

**Setup.** The plan views are parallel projections. Write the screen row of a
world point as

```
sy = f(wx, wy) − z·L
```

where `f` is the existing linear ground projection (`Camera.worldToScreenY`,
whatever the heading) and `L = liftPixels` is the screen lift of one unit of
height. Both plan views have `L > 0`, and `f` increases toward the viewer —
that is what makes `tileDepth` a depth at all, and `DepthPass`'s class note
gives the argument for it in both projections.

**Claim.** Let an actor stand at cell `A` on a column of height `hA`, and let
`B` be any cell nearer the camera (`f(B) > f(A)`), with column height `hB`.
Then the column at `B` overlaps the actor's pixels **only when** it genuinely
occludes them.

**Proof.** The actor's pixels occupy screen rows at or above their feet: rows
`≤ f(A) − hA·L`. The column at `B` is drawn from its own floor row upward, so
it occupies rows `[f(B) − hB·L, f(B)]`. The two overlap only if the column's
top edge is above the actor's feet:

```
f(B) − hB·L  <  f(A) − hA·L
⇔  hB·L − hA·L  >  f(B) − f(A)  >  0
⇔  hB  >  hA + (f(B) − f(A)) / L
```

So overlap requires the nearer column to be *taller than the actor's own
surface by at least the ground-depth difference between them, converted into
height*. That inequality is precisely the condition for the column at `B` to
block the view ray from the camera to the actor's feet — the ray rises by
`L` of height per `f`-unit of ground depth, by construction of the projection.
**Screen overlap and true occlusion are the same condition**, so drawing `B`
after `A` is correct whenever it covers anything, and harmless whenever it does
not. ∎

The same argument run between two columns gives the terrain-versus-terrain
case, and `within` handles ties inside one cell exactly as it does today. So no
pairwise test, no topological sort, no depth buffer.

**Where it breaks, and it is one line of the proof.** The step "the column at
`B` occupies rows `[f(B) − hB·L, f(B)]`" is where the heightfield is used: it
assumes the column is solid from its own floor upward, so its drawn extent
starts at `f(B)`. Give the column a gap and its geometry occupies a *higher*
band with nothing under it — the band can sit above the actor's head while the
inequality above is false, and the sort orders them wrong.

That is Job O's O4, it is the reason O4 exists as a step rather than as a
paragraph, and it is why its verification is written as "must fail before, pass
after".
