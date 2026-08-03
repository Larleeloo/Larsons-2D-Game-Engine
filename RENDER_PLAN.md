# Render Plan — GPU Acceleration, End to End

**Status:** Living document. Written 2026-08-02 against commit `85196b9` on
`claude/gpu-acceleration-shaders-oqbx54`. B0–B3 are done and recorded in place;
B4 is the next step.
**Companion to:** [`STEAM_PLAN.md`](STEAM_PLAN.md), which covers the product.
This one covers the renderer, and is the plan of record for Jobs A, B and C.

**Deliberately contains no timeframes.** Every entry is a logical step with a
stated precondition, a stated output, and a stated way to know it worked.
Estimates were left out on purpose: the interesting question about each step is
*what has to be true before it can start*, not how long it takes. The steps are
ordered so that each one can be finished, verified, committed and left alone.

---

## 0. How to read this

Each step is written as:

- **Goal** — the one sentence that says why the step exists.
- **Do** — the concrete changes, named by file where the file already exists.
- **Verify** — the instrument that proves it, and what number or assertion
  counts as proof. A step with no verification method is not in this plan.
- **Done when** — the condition that lets the next step start.

Steps are numbered by job (`B1`, `A2`, `C3`). A step never depends on a
higher-numbered step in the same job. Cross-job dependencies are stated
explicitly.

---

## 1. The three jobs, precisely

| Job | What it means | Depends on |
|-----|---------------|------------|
| **A — GPU post-processing** | The shader chain (`bloom`, `vignette`, `chromatic_aberration`, …) executes as real GLSL on the GPU instead of `ParallelRows` on the CPU. | Nothing technically. But see below. |
| **B — GPU scene rendering** | Terrain, entities, decor and UI are submitted to the GPU as batched geometry instead of drawn one call at a time through Java2D. | The whole engine drawing through `DrawTarget`. |
| **C — Camera rotation** | The camera yaws to eight fixed compass points, *Don't Starve* style, snapping between them. Turns the planar formats into pseudo-3D. | Job B. Not optional — see §6.0. |

### Why A comes after B even though A is easier

Job A on its own is a net loss. The frame would be composed by Java2D into a
CPU `int[]`, uploaded to the GPU, post-processed, and read back for Java2D to
present. Two full-frame PCIe transfers per frame to save work that the profiler
says is currently **not the bottleneck**: on the M1 Air the shader stage does
not dominate — the scene stage does, at 11.49 ms of a 16.67 ms budget.

Once Job B lands, the composed frame *is already a GPU texture*. Job A then
costs one framebuffer and a ping-pong, no transfers, and the CPU shader stage
disappears entirely. That is why A is scheduled second despite being the
smaller job: it changes from "a wash" to "nearly free".

The risk in Job A has already been retired independently (§2), so nothing is
lost by waiting.

---

## 2. Where rendering actually stands

Everything in this table has been measured or executed, not assumed.

| Claim | Evidence |
|-------|----------|
| All ten shipped shaders compile and link on a real driver | `ShaderCompileTest` — nine in `Shaders.allBuiltIns()` plus `LightingPass`, which is tested separately because it is the only one with array uniforms and a uniform-bounded loop |
| Every shader behaves like its CPU twin | `ShaderParityTest` — mean absolute channel error out of 255: five passes at **0.00**, `scanlines` 0.04, `vignette` 0.32, `grayscale` 0.47, `bloom` 3.58 |
| `uStrength` reaches every shader | `ShaderParityTest.strengthZeroLeavesTheFrameAloneOnBothSides` |
| The compile check can actually fail | `ShaderCompileTest.aDeliberatelyBrokenShaderIsRejected` (negative control) |
| The GPU plumbing a backend needs is already written | [`GlShaderHarness`](src/test/java/com/larsons/engine/GlShaderHarness.java) — context, FBO, upload, uniform binding, readback, in ~200 lines |
| The whole engine renders through `DrawTarget`, not `Graphics2D` | Every painter, widget and scene takes a `DrawTarget`; `graphicsOf` has zero call sites after B3, and `SceneFramesTest.noSceneReachesPastTheDrawTarget` records every scene through a target with no Graphics2D behind it |
| Scenes are pixel-compared, not just the painters | `SceneFrames` — 16 of the 18 scenes goldened at 800×480 from fixed inputs; the two excluded draw nothing without a live network session |
| Frames are always composed offscreen | `Java2DRenderer` composes to a backing image unconditionally (`-Dlarsons.render.direct=true` is the escape hatch). Scene stage on Linux fell 1.071 → 0.374 ms from this alone |
| Static terrain is cached | `TerrainCache` — 7.8× on still ground, parity under churn, one global pixel lattice so the floor does not shake |
| The frame cost is known per stage | `FrameProfiler` / `FrameReport` — see Appendix C |

**The honest summary:** Job A's *unknowns* are gone; only its plumbing remains,
and the plumbing has a working prototype. Job B's migration is done — world,
UI and scenes all draw through the seam — and what remains of B is the
batching work (B5, B6) and the backend itself (B7–B10).

---

## 3. Invariants — rules no step may break

These are not preferences. A step that violates one of these is wrong, however
much faster it makes things.

1. **The core ships with zero runtime dependencies.** LWJGL is `testImplementation`
   today and must stay out of the main runtime classpath. The GL backend goes
   in its own Gradle module (`:gl`), which depends on core; core never depends
   on it. A player with a bare JRE and no module still gets a working game.
2. **Java2D remains a first-class backend, not a legacy path.** It is the floor
   for machines with no usable driver, for headless CI, and for the M1 Air if
   the GL path ever misbehaves. Every step that adds a GL capability must leave
   the Java2D path working and tested.
3. **Pixel parity is the contract.** "Looks fine" is not a result. Every port
   step compares rendered output against a reference and states the error.
4. **The suite stays green.** Last full run: **860 tests, 0 failures, 10 skipped**
   (was 810/0/3 when this was written; B0–B3 added the rest). The skips are
   environment-dependent and skip rather than fail by design — three need a
   display, seven need a GL driver this container has not got. A step ends with
   that or better.
5. **Nothing merges that cannot be measured.** If a change is supposed to make
   the frame faster, the profiler says so before it lands. This rule exists
   because it has already caught three things in this work that felt right and
   were not — per-chunk cache heuristics that made frames slower, a "harmless"
   0.02% jitter that was 16,499 px of visible shaking, and a build stamp that
   froze and made four hours of reports lie about which commit produced them.
6. **`DataBufferInt.getData()` un-accelerates an image permanently.** Any new
   code that grabs a pixel array from a `BufferedImage` must own that it has
   just opted that image out of hardware acceleration forever. This is fine for
   the CPU shader chain, which needs the array anyway; it is a bug anywhere
   else.

---

## 4. Job B — GPU scene rendering

### B0 — Freeze a pixel reference before touching anything

**Goal.** Make "did this port change what the player sees?" a question with a
mechanical answer, before the porting that needs it begins.

**Do.**
- Add `src/test/java/com/larsons/engine/render/GoldenFrames.java`: renders a
  fixed set of scenes at a fixed size, with a fixed seed and a fixed clock, into
  a `BufferedImage` via `Java2DTarget`.
- Cover, at minimum: a side-scroll level with terrain + decor + liquids + an
  entity; a top-down level; an isometric level; the main menu; the creative-mode
  palette; a `ConfigForm`; an inventory `ContainerPanel`; a `SpriteEditorPanel`.
- Store references as PNGs under `src/test/resources/golden/`, and compare with
  the same mean-absolute-channel-error metric `ShaderParityTest` already uses,
  so there is one definition of "the same picture" in the codebase.
- Provide `-Dlarsons.golden.rewrite=true` to regenerate, and make regeneration
  loud in the test output so nobody does it by accident.

**Watch out for.** Anything time-dependent (`System.nanoTime`, animation phase,
particle RNG) has to be injectable or the goldens flap. `TerrainCache.ANIM_FPS`
quantises liquid animation to 12 fps — the test clock must land on a frame
boundary.

**Verify.** Run twice on the same commit; error must be exactly 0.00. Then
deliberately change one colour constant and confirm the test fails.

**Done when.** Golden comparison is in the suite and green.

#### B0 — done

`GoldenFrames` / `GoldenFramesTest` / `FrameError`, fifteen references under
`src/test/resources/golden/`. Three world frames (one per level format, each
with terrain, a liquid pool, a hole, a stacked wall and its shadow, background
and foreground decor, surface decor and a mob) and twelve widget frames, one
per class B2 ports.

What it cost, and what it caught:

| Thing | Outcome |
|-------|---------|
| The metric | Extracted to `FrameError`; `ShaderParityTest` now calls it rather than keeping its own copy, so there is one definition of "the same picture" as this step required |
| `Particles` | Seeded its `Random` from the system clock. Gained `Particles(long seed)`; the no-arg constructor is unchanged and is still what play uses |
| `ProfileOverlay` | **Caught by the determinism check**, not by eye: driving a real `FrameProfiler` measures `System.nanoTime()`, so the frame differed from itself by 1.21/255 every run. The golden now feeds the overlay a literal `Snapshot` and a literal `DeviceProfile` — the latter because the overlay prints the machine's core count, which would otherwise golden the build agent |
| `-Dlarsons.golden.rewrite` | Did not reach the test JVM; Gradle forks and inherits no `-D`. `tasks.test` now forwards everything under `larsons.` |
| Suite | 827 tests, 0 failures, 10 skipped |

**On the skip count.** The baseline in this document says 3 skipped. It is 10
here because this container has no GL driver, so `ShaderCompileTest` (4) and
`ShaderParityTest` (3) stand aside on top of the three display-dependent ones.
That is the environment, not a regression: no golden test skipped.

**The one thing that could not be pinned down: fonts.** Glyph rasterisation
belongs to the JDK and the host's font configuration, so a reference generated
on one machine will not match to 0.00 on another. Loosening the bar to a
tolerance that absorbs that would also absorb a real one-pixel shift, which is
precisely the failure this step exists to catch. Instead the goldens carry a
fingerprint of the fonts that drew them (`font-fingerprint.txt`), and the
comparison **skips, loudly, naming both fingerprints** when it does not match —
the same "skip by design rather than fail by accident" convention the suite
already uses. On a matching machine the bar is exact equality, not a tolerance.

Two tests guard the guard: `theComparisonCanActuallyFail` perturbs one channel
of one pixel and insists the metric notices, and `everyFrameRendersIdenticallyTwice`
renders the whole catalogue twice and demands 0.00 — the test that found the
`ProfileOverlay` flap before it could be committed as a reference.

---

### B1 — Widen `DrawTarget` to the vocabulary the UI actually uses

**Goal.** `DrawTarget` currently has 22 members, chosen for what the *world*
draws. The UI draws different things. Find that out now, from a measurement,
rather than one `UnsupportedOperationException` at a time during the port.

**The measured gap** (occurrences in `src/main/java`, 2026-08-02):

| Java2D call | Sites | Plan |
|-------------|-------|------|
| `setFont` | 309 | Already covered — `drawText` takes a `Font`. No new member. |
| `BasicStroke` | 164 | Partly covered: `drawRect`/`drawOval`/`drawLine`/`drawPolygon` take `thickness`. Audit for caps, joins and **dash arrays**, which are not covered. |
| `fillRoundRect` | 161 | **New:** `fillRoundRect(x, y, w, h, arcW, arcH, argb)` |
| `setStroke` | 135 | Same audit as `BasicStroke`. |
| `drawRoundRect` | 82 | **New:** `drawRoundRect(..., thickness)` |
| `setComposite` | 28 | Covered by `pushAlpha`/`popAlpha` *if* every site is `SRC_OVER` with an alpha. Audit for `SRC`, `DST_OUT`, `CLEAR` — those need explicit support or a rewrite. |
| `AlphaComposite` | 27 | As above. |
| `drawArc` | 17 | **New:** `drawArc(x, y, w, h, startDeg, arcDeg, argb, thickness)` |
| `fillArc` | 15 | **New:** `fillArc(x, y, w, h, startDeg, arcDeg, argb)` |
| `rotate` | 15 | Covered by `pushTransform(AffineTransform)`. |
| `GradientPaint` (linear) | 6 | **New:** `fillLinearGradient(x, y, w, h, x0, y0, argb0, x1, y1, argb1)` |
| `RadialGradientPaint` | 6 | **New:** `fillRadialGradient(cx, cy, radius, int[] argbStops, float[] fractions)` |
| `shear` | 2 | Covered by `pushTransform`. |

**Do.**
- Add the seven new members to
  [`DrawTarget`](src/main/java/com/larsons/engine/graphics/draw/DrawTarget.java),
  each with the `int argb` primary form and a `Color` default overload, matching
  the existing convention exactly.
- Implement all of them in
  [`Java2DTarget`](src/main/java/com/larsons/engine/graphics/draw/Java2DTarget.java).
- Implement all of them in
  [`RecordingTarget`](src/main/java/com/larsons/engine/graphics/draw/RecordingTarget.java)
  as new `Cmd` subtypes in the sealed hierarchy.
- Extend `DrawStats.Kind` if any new call is neither `SHAPE`, `IMAGE`, `TEXT`
  nor `STATE` — gradients probably warrant their own kind, because they will not
  batch the way flat shapes do and lumping them in would flatter the merge ratio.
- Run the composite audit as a real pass over all 55 sites and record the
  result in this document. If a non-`SRC_OVER` composite is in use, decide
  explicitly: support it, or rewrite the call site.
- Run the stroke audit the same way, specifically for dashes.

**Why the arcs are worth real members.** 32 arc sites is not many, but every one
of them is a UI element — cooldown rings, radial meters. Emulating them with
polygons produces visibly different antialiasing, which is precisely the kind of
"nothing changed except everything looks slightly wrong" that B0 exists to catch
and that is miserable to chase later.

**Verify.** Extend `DrawTargetTest` so each new member has a test asserting the
`RecordingTarget` command it produces, and a `Java2DTarget` test asserting the
resulting pixels. Suite green.

**Done when.** No planned UI port needs a Java2D call `DrawTarget` cannot express.

#### B1 — done

Seven members added, in `DrawTarget`, `Java2DTarget` and `RecordingTarget`:

| Member | Serves |
|--------|--------|
| `fillRoundRect(x, y, w, h, arcW, arcH, argb)` | 161 sites |
| `drawRoundRect(..., thickness)` | 82 sites |
| `fillArc(x, y, w, h, startDeg, arcDeg, argb)` | 15 sites |
| `drawArc(..., thickness)` | 17 sites |
| `fillLinearGradient(x, y, w, h, x0, y0, argb0, x1, y1, argb1)` | 3 render-time sites |
| `fillRadialGradient(cx, cy, radius, float[] fractions, int[] argbStops)` | 3 sites |
| `drawDashedLine(x1, y1, x2, y2, argb, thickness, dash, gap)` | the stroke audit's finding, below |

Member count is **26 → 33** abstract methods, plus 16 `Color` convenience
overloads. (This document previously said 22 → 29; that count treated the four
`drawImage` overloads as one verb. Either basis, seven were added.)

`DrawStats.Kind` gained **`GRADIENT`**, and a gradient breaks the batch on both
sides like a state change does. Counting gradients as `SHAPE` would have been
the comfortable choice and the wrong one: a gradient is a distinct paint, and
on GL either its own shader or its own generated texture, so folding it in
would let a screen of gradients report a merge ratio it can never achieve.
The merge ratio is the number the whole GPU case rests on (B5) and an
instrument that flatters what it measures is worse than none.

**The composite audit — all 28 `setComposite` sites.**

| Composite | Sites | Decision |
|-----------|-------|----------|
| `AlphaComposite.SRC_OVER` with an alpha | 24 (12 set / 12 restore) | Already covered by `pushAlpha`/`popAlpha`. No change. |
| `AlphaComposite.Clear` | 2 — `TerrainCache:396`, `EntitySprites:185` | **Not supported, and correctly so.** Both punch transparent holes in an image being *baked* through `createGraphics()`, not drawn to a frame. Baking is Java2D by definition (Appendix A) and stays that way. |
| The `Java2DTarget` implementation itself | 2 | n/a |

No `SRC`, no `DST_OUT`, no destination-modifying blend of any kind reaches a
frame. This is a better result than the step expected and it matters for B8:
`pushAlpha` becomes a multiply into the vertex colour, which costs nothing and
does not break the batch, where `CLEAR` would have meant a blend-state change
and a flush per push.

**The stroke audit — all 135 `setStroke` / 164 `BasicStroke` sites.**

Every site is one of exactly two things: a plain width, or a dash pattern.

- *Plain widths* — already covered by the `thickness` argument the outline
  verbs take. No change.
- *Dashes* — three sites: `MiniGameHud:128` and `CreativeScene:5992` (both the
  escort waypoint path, `{8, 8}`) and `CreativeScene:7403` (`{5, 5}`, inside
  `waypointIcon()`, which bakes a `BufferedImage` and so stays Java2D). Two
  render-time sites, hence `drawDashedLine`.
- *Caps and joins* — **deliberately given no member.** Every render-time site
  that sets `CAP_ROUND`/`JOIN_ROUND` also sets a dash pattern, because a dash
  with butt caps reads as a different decoration; there is no site anywhere
  that varies caps independently of dashing. Round ends are therefore part of
  what `drawDashedLine` *is*, not a default. Every remaining `CAP_`/`JOIN_`
  use — `AutoSprites`, `EntitySprites`, `CreativeScene:7403` — is inside a
  `createGraphics()` bake. A knob nothing turns is a knob both backends must
  implement and test for nobody.

**Verified.** Seven new `DrawTargetTest` tests, each asserting the recorded
command *and* the resulting pixels — the recording half catches a painter
calling the wrong verb, the pixel half catches a backend implementing it
wrongly, and neither catches the other's failure. Two are shaped by what
actually goes wrong rather than by the happy path: the arc test asserts the
left half of a 90°–270° sweep is lit and the right half is not (which is what
catches an implementation measuring angles clockwise), and
`aDashedLineDoesNotLeaveTheNextPlainLineDashed` covers a real bug the stroke
cache would otherwise have had — it keys on width alone, so a dashed and a
plain stroke of the same width collided.

Suite: **834 tests, 0 failures, 10 skipped**. Goldens unchanged at 0.00, as a
step that adds members and ports nothing must leave them.

---

### B2 — Port the shared painters and UI widgets

**Goal.** These classes are called *from* the scenes. Porting them first means
each scene port is mechanical instead of recursive.

**Order** — smallest and most-depended-upon first, so a mistake surfaces on a
small file:

| Order | Class | Lines | `Graphics2D` refs |
|-------|-------|-------|-------------------|
| 1 | [`ParallaxBackground`](src/main/java/com/larsons/engine/graphics/ParallaxBackground.java) | 121 | 4 |
| 2 | [`CutscenePainter`](src/main/java/com/larsons/engine/graphics/CutscenePainter.java) | 186 | 4 |
| 3 | [`CraftingPanel`](src/main/java/com/larsons/engine/ui/CraftingPanel.java) | 227 | 5 |
| 4 | [`CharacterPicker`](src/main/java/com/larsons/engine/character/CharacterPicker.java) | 233 | 5 |
| 5 | [`Menu`](src/main/java/com/larsons/engine/ui/Menu.java) | 301 | 4 |
| 6 | [`AutoSprites`](src/main/java/com/larsons/engine/autobattler/AutoSprites.java) | 325 | 5 |
| 7 | [`ContainerPanel`](src/main/java/com/larsons/engine/ui/ContainerPanel.java) | 378 | 2 |
| 8 | [`Particles`](src/main/java/com/larsons/engine/fx/Particles.java) | 381 | 2 |
| 9 | [`SpriteEditorPanel`](src/main/java/com/larsons/engine/ui/SpriteEditorPanel.java) | 831 | 12 |
| 10 | [`ConfigForm`](src/main/java/com/larsons/engine/ui/ConfigForm.java) | 845 | 6 |
| 11 | [`ProfileOverlay`](src/main/java/com/larsons/engine/profile/ProfileOverlay.java) | 173 | 3 |
| 12 | [`MiniGameHud`](src/main/java/com/larsons/engine/demo/MiniGameHud.java) | 253 | 8 |

**Do, per class.**
- Change the public signature from `Graphics2D g` to `DrawTarget target`.
- Replace `g.setColor(c); g.fillRect(...)` with `target.fillRect(..., c)`. The
  colour-as-argument style is the whole point of `DrawTarget`: it removes the
  hidden state that makes batching impossible.
- Hoist repeated `new Color(...)` into `private static final` constants, as was
  done in `PlayScene` (`HURT_TINT`, `SHIELD_RING`, …). This is not cosmetic —
  `DrawStats` keys batches on the colour object, so a fresh `Color` per call
  breaks every merge.
- Do **not** keep a `Graphics2D` overload "so unported callers keep working"
  beyond the single step that needs it.
  [`TilePainter`](src/main/java/com/larsons/engine/graphics/TilePainter.java)
  has one today; B4 removes it. Every overload left behind is a path that has
  to be maintained and tested twice.

**Verify.** Golden comparison from B0 after each class, error 0.00 expected
(these are pure translations, so any nonzero error is a bug, not rounding).
Suite green after each.

**Done when.** The only non-demo classes still naming `Graphics2D` are the ones
that legitimately should: `Java2DTarget`, `Java2DRenderer`, `Renderer`,
`AssetLoader`, `Skins`, and the sprite factories that call
`BufferedImage.createGraphics()` to bake an image (which is Java2D by
definition and stays that way — see B5).

#### B2 — done

All twelve ported, in the order above. Every one verified against B0's goldens
at **0.00** — these are pure translations, so any nonzero error would have been
a bug, and the bar was held rather than relaxed.

Where `Graphics2D` survives in the twelve, it is a `createGraphics()` bake and
stays: `ParallaxBackground` (layer art), `CutscenePainter` (the placeholder
figure), `CharacterPicker.icon`, `AutoSprites.cached`. `CraftingPanel`,
`Menu`, `ContainerPanel`, `SpriteEditorPanel`, `ConfigForm` and `MiniGameHud`
no longer name it at all.

**Two hidden-state dependencies the port surfaced.** Both are the class of bug
this step exists to find, and neither is visible by reading the diff:

1. `CharacterPicker`'s ultimate rule was drawn with whatever stroke width the
   *card border above it* had last set — 2.5px under the selected card, 1.2px
   elsewhere. Passing the obvious `1f` would have thinned it. The port states
   the width explicitly and the golden confirms it.
2. `SpriteEditorPanel`'s preview-box border ran at the ambient stroke width,
   which its frame-strip loop carefully restored after every box. Here the
   inherited value happened to be the default; the port states it.

Removing the seven per-painter `setRenderingHint(ANTIALIASING, ON)` calls was a
no-op, as expected: `Java2DRenderer` sets it for the whole frame already, and
B0's harness sets the same two hints for the same reason. The goldens are what
turned "expected" into "checked".

**Supporting changes, each forced by the port rather than chosen:**

- `UiText` gained a `Measure` abstraction so `fit`/`fitTail`/`wrap` serve both a
  `FontMetrics` and a `(DrawTarget, Font)` without a second copy of each
  bisection. Not a compatibility shim of the kind this step forbids — `UiText`
  draws nothing, so it has no backend to be tied to, and the `DrawTarget`
  overload is what will keep layout identical when B6 changes rasterisation.
- `AutoBattlerScene` and `AutoBattlerGuideScene` gained the `frameTarget` field
  `PlayScene` and `CreativeScene` already had, so their `AutoSprites` overlays
  reach the frame's target. Their draws were previously uncounted.
- `Engine` draws `ProfileOverlay` through a **second** `Java2DTarget` over the
  same surface. The old code bypassed the seam deliberately, so the readout's
  own draw calls would not be folded into the count it reports; a separate
  target keeps that separation while still going through `DrawTarget`.

**Verified.** Goldens 0.00 on all fifteen frames, plus six new
`RecordingTarget` sequence tests (`PortedPainterTest`) for the properties a
golden is blind to — that `ContainerPanel`'s open animation pushes transform
then alpha and unwinds both, that a settled panel issues *no* state changes at
all, that `Menu` draws its scroll bar after the rows it scrolls over, and that
the escort path emits two `drawDashedLine` calls rather than thirty short
solid ones. §7 says these two instruments answer different questions; they do.

Suite: **840 tests, 0 failures, 10 skipped**.

#### A correction to this document, found while doing B2

B2 says to hoist `new Color(...)` into constants because "`DrawStats` keys
batches on the colour object, so a fresh `Color` per call breaks every merge."
**That is not true of the code as written**, and it was worth checking rather
than repeating. `Java2DTarget` records every flat shape as
`stats.record(Kind.SHAPE, null)` — the batch key is `null`, so colour does not
break a shape batch at all. Only `IMAGE` (keyed by source image) and `TEXT`
(keyed by font) carry keys.

`DrawStats` is right and the guidance was wrong. A GL backend folds colour into
the vertex, exactly as `pushAlpha` will (see B1's composite audit), so
consecutive differently-coloured flat shapes genuinely *do* collapse into one
draw — which is what `null` models.

Hoisting is still worth doing, for the reason that survives the correction: it
removes an allocation per call on a path that runs every frame. The
checkerboard in `SpriteEditorPanel` alone was allocating a `Color` per cell,
hundreds a frame. The comments in the ported classes now say that rather than
the batching claim.

#### Draw-call baseline, per golden frame

Recorded through `GoldenFrames.record()` — the same fixed scenes as the pixel
comparison, so B5 and B6 can publish before-and-after numbers that cannot drift
between measurements.

| frame | ops | batches | merge | shape | image | text | state |
|-------|----:|--------:|------:|------:|------:|-----:|------:|
| world-side-scroll | 145 | 5 | **29.00×** | 142 | 3 | 0 | 0 |
| world-top-down | 149 | 5 | **29.80×** | 146 | 3 | 0 | 0 |
| world-isometric | 153 | 5 | **30.60×** | 150 | 3 | 0 | 0 |
| particles | 62 | 1 | **62.00×** | 62 | 0 | 0 | 0 |
| sprite-editor | 1545 | 86 | 17.97× | 1495 | 3 | 35 | 12 |
| container-panel | 35 | 12 | 2.92× | 26 | 3 | 6 | 0 |
| character-picker | 58 | 22 | 2.64× | 10 | 3 | 45 | 0 |
| auto-sprites | 17 | 7 | 2.43× | 11 | 6 | 0 | 0 |
| parallax-background | 9 | 5 | 1.80× | 1 | 8 | 0 | 0 |
| profile-overlay | 28 | 16 | 1.75× | 15 | 0 | 13 | 0 |
| main-menu | 8 | 5 | 1.60× | 3 | 0 | 5 | 0 |
| config-form | 21 | 14 | 1.50× | 8 | 0 | 13 | 0 |
| minigame-hud | 12 | 8 | 1.50× | 8 | 0 | 4 | 0 |
| cutscene | 10 | 7 | 1.43× | 4 | 2 | 4 | 0 |
| crafting-panel | 36 | 33 | **1.09×** | 9 | 12 | 15 | 0 |

**What this says, before B5 and B6 touch anything.** The world already batches
30× — flat terrain quads collapse almost completely, and a GL backend will
serve them from one draw call. The UI does not, and the reason is legible in
the columns: `crafting-panel` at 1.09× is twelve images and fifteen text runs
*interleaved* (icon, name, icon, count, icon, count…), so nearly every
operation changes the batch key. That is precisely the case B5's sprite atlas
and B6's glyph atlas exist for — with both in place those two columns collapse
to one texture and one atlas, and the interleaving stops mattering.

It is also a warning about where to look. `crafting-panel` and
`character-picker` (45 text runs) are the frames whose numbers should move in
B5/B6; `world-*` are already near the ceiling and will not, so an atlas
measured only against a world scene would look like it did nothing.

---

### B3 — Port the scenes and retire `graphicsOf`

**Goal.** Remove the 39 remaining `Java2DTarget.graphicsOf(target)` calls. That
count is the migration's progress bar and it should reach zero here.

**Current distribution** (measured):

| Scene | `graphicsOf` sites | `Graphics2D` refs | `drawString` sites |
|-------|-----|-----|-----|
| `CreativeScene` | 2 | 62 | 38 |
| `AutoBattlerScene` | 2 | 42 | 43 |
| `PlayScene` | 4 | 32 | 21 |
| `AutoBattlerGuideScene` | 2 | 28 | 41 |
| `EvolutionScene` | 2 | 25 | 33 |
| `DeckGameScene` | 2 | 24 | 41 |
| `EvolutionCatalogScene` | 2 | 12 | 31 |
| `DeckLobbyScene` | 2 | 7 | — |
| `SkinEditorScene`, `AutoBattlerLobbyScene` | 2 each | 6 each | — |
| `MainMenuScene`, `EvolutionLobbyScene`, `BoardCustomizeScene` | 2 each | 4 each | — |
| `StartupScene`, `MultiplayerScene`, `LevelSelectScene`, `KeyBindsScene`, `GameTypeEditorScene` | 2 each | ≤4 each | — |

**Order.** Ascending by `Graphics2D` reference count — the reverse of the table
above. Fourteen scenes have four references or fewer and are close to trivial
once B2 lands. Do those first; they build confidence and shrink the count fast.
`CreativeScene` (6,443 lines, 62 references, the largest file in the project)
goes last, when the pattern is completely established.

**Do.**
- Delete the `Graphics2D g = Java2DTarget.graphicsOf(target);` line and rewrite
  the body against `target`.
- The two sites per scene are almost always "render" and one helper; `PlayScene`
  has four because of its phase split. Handle each phase separately so the
  profiler can still attribute cost per phase.
- Remove `Scene.java`'s javadoc paragraph about `graphicsOf` when the count
  reaches zero — the progress bar has served its purpose and stale guidance is
  worse than none.

**Verify.** `grep -rn graphicsOf src/main/java` returns only the definition in
`Java2DTarget`. Golden comparison green. Suite green.

**Done when.** 39 → 0.

#### B3 — done

All eighteen ported, ascending by `Graphics2D` reference count as planned.
`graphicsOf` is at **0 call sites**; only its definition survives, which B4
deletes. `Scene`'s javadoc paragraph explaining how to unwrap the target — the
progress bar — is gone with the last scene that needed it.

**B3 built the instrument it needed first.** B0's goldens cover the shared
painters, which is what B2 changed. They contain none of the ~2,000 drawing
statements in the scenes, so they would have stayed green through a port that
moved every HUD element in the game by a pixel. [`SceneFrames`](src/test/java/com/larsons/engine/render/SceneFrames.java)
renders whole scenes the way the engine does — fixed inputs, fixed 800×480
viewport, two fixed ticks, drawn once — with every store redirected to a
scratch directory so the pictures do not depend on what a developer has saved.
Sixteen of the eighteen are goldened. The two that are not, `AutoBattlerScene`
and `DeckGameScene`, return immediately on a null client: without a live
network session there is nothing on the screen to compare. They are named in
`NOT_GOLDENABLE` with that reason, and `SceneFramesTest` asserts the list is
exhaustive against the demo package on disk.

Four scenes were *nearly* excluded on a guess. `PlayScene`, `EvolutionScene`,
`AutoBattlerScene` and `DeckGameScene` all look like they roll from an unseeded
RNG. Measuring instead of assuming showed `PlayScene` is deterministic once
handed the level the engine ships, and `EvolutionScene` needed one seeded
`EvolutionGame` passed to the public `adopt` it already had. The two that
remain are excluded for a different reason than the one guessed, which is the
whole argument for checking.

**What the goldens caught.** Two real defects, both invisible in a diff and
neither findable by eye:

1. `AutoBattlerGuideScene`'s tab labels were ported at 14pt. The method sets
   15pt; 14 was the ambient font a few lines up in the source. Mean channel
   error 0.37, worst pixel named: expected white at (53, 81), got the tab fill.
2. `CreativeScene`'s sidebar edge came out a pixel thin. It is drawn with no
   stroke of its own and inherited the 2px width `drawSpawnMarker` left on the
   Graphics2D three method calls earlier in the frame. Error 0.05, worst pixel
   (191, 321).

The second is the same class of bug B2 found twice, and it is now understood
well enough to hunt rather than stumble over: after fixing it, the other four
sites inheriting that same 2px — the slider divider, two cursor-preview spawn
rings, the block preview outline — were traced through the original's
execution order and stated explicitly, even though the goldens' default state
does not reach them.

#### Two corrections to B1's audits, found by porting

Both are the same shape as B2's correction to this document: a count that was
taken carefully and still missed something.

- **The clip audit counted rectangles and concluded rectangles were all the
  engine used.** `AutoBattlerScene`'s skinned board clips to a tile's diamond
  and stretches the skin frame over the diamond's bounding box; the clip is
  what keeps each tile's art off its neighbours, so it is load-bearing and no
  rectangle expresses it. `DrawTarget` gains `pushClip(Shape)`, documented as
  the one expensive verb on the interface — a scissor test becomes a stencil
  pass, so the rectangular form stays the one to prefer.
- **The stroke audit concluded every solid stroke wanted only a width, and
  that round caps belonged to dashes alone.** The auto-battler's arrow trail
  and slash arc are two solid strokes that ask for round caps explicitly. Two
  sites do not justify a cap argument on every outline verb — every backend
  would then implement and test a knob almost nothing turns — so the caps are
  emitted as the geometry they are: a cap of width *w* is a disc of diameter
  *w* on the endpoint. The slash had also silently lost its 3px width in the
  mechanical pass; both are stated now.

`DrawTarget` also gained the `(Color, float)` overload of every outline verb.
Its absence showed the moment the port started stating widths: each call site
had to write `SOME_COLOUR.getRGB()` inline, which is noise at best and an
invitation to drop an alpha at worst.

#### How 2,000 call sites were moved without trusting the mover

The port was mechanical, so it was done mechanically — but the tool was built
to fail loudly rather than to be clever. It tracks the ambient colour, font and
stroke linearly and folds each into the draw that used it, and it *poisons*
that state on leaving any nested block that changed it, because

```java
if (legal) { g.setColor(A); g.setStroke(2.5f); }
else       { g.setColor(B); g.setStroke(1f);  }
g.drawRoundRect(...);
```

cannot be resolved by a linear scan. Anything it could not resolve it left as a
`g.` call — and since the `Graphics2D g` declaration is deleted in the same
pass, the compiler then enumerates every one of them. That is the safety net:
the tool cannot produce a wrong translation that compiles.

It earned that design twice. A `g.setColor(...)` sitting under a comment line
initially failed to match, which would have left the ambient colour stale and
translated the *next* fill with the wrong one — a wrong translation that
compiles, the one failure mode the tool must not have. And in `CreativeScene`
it happily rewrote the 24 `createGraphics()` icon bakes to draw at the frame
instead of the image; they have no `target` in scope, so that too was a compile
error rather than a silent bug, and all 24 were restored verbatim. A bake is
Java2D by definition and stays that way (B2), which is why `CreativeScene` is
the one demo class that still imports `Graphics2D`.

#### What the port removed besides `Graphics2D`

None of this was sought; the port made it visible and it would have been
perverse to leave it.

- **~90 `Font` literals** were constructed inside render methods — a fresh
  object per call per frame for values that never change. Making the font an
  argument at each call site turned the duplication into something you could
  see. `DeckGameScene` alone had twenty-one distinct literals.
- **Per-frame `Polygon` allocation** in four scenes. `BoardCustomizeScene`'s
  8×8 preview built 128 of them a frame (each cell filled, then outlined, each
  `Polygon` holding two more arrays); `EvolutionScene` built two per organism
  per frame in a dish that holds hundreds; `AutoBattlerScene`'s board built 64
  plus the highlights. All now write into scratch arrays, or reuse one instance
  where a `Shape` is genuinely needed.
- **`RadialGradientPaint` and `GradientPaint` objects** built per light, per
  dropped item, per frame — now the `DrawTarget` verbs over shared stop arrays.
- **`frameTarget`**, the field B2 added to four scenes so a half-ported scene
  could hand a `DrawTarget` to a ported painter. With `render` threading its
  own parameter everywhere it was a redundant copy, and a reference outliving
  the frame that set it.
- **`SceneChrome`** collects the furniture nine scenes drew by hand: the same
  near-black backdrop, the same 14pt SansSerif, the same 24px inset, the same
  two baselines 24 and 44 pixels off the bottom, written out nine times. It is
  constants and three one-line draws, deliberately not a widget framework —
  two scenes keep drawing their own bold 15pt connect-result line rather than
  grow a parameter here for two callers.

**Verified.** Goldens 0.00 on all 31 frames (15 painters, 16 scenes). Suite:
**860 tests, 0 failures, 10 skipped** — the same 10 environment-dependent skips
as before. `SceneFramesTest` adds `noSceneReachesPastTheDrawTarget`, which
records every scene through a `RecordingTarget` (no Graphics2D behind it) so a
scene that still unwrapped would throw. Not hypothetical: the first run of that
check, before any of this work, failed exactly that way on `StartupScene`.

#### Draw-call baseline, per frame

Recorded the same way as B2's table, now including the scenes. Sorted by merge
ratio, which is the number that says what a batching backend would buy.

| frame | ops | batches | merge | shape | image | text | gradient | state |
|-------|----:|--------:|------:|------:|------:|-----:|---------:|------:|
| particles | 62 | 1 | **62.00×** | 62 | 0 | 0 | 0 | 0 |
| world-isometric | 153 | 5 | **30.60×** | 150 | 3 | 0 | 0 | 0 |
| world-top-down | 149 | 5 | **29.80×** | 146 | 3 | 0 | 0 | 0 |
| world-side-scroll | 145 | 5 | **29.00×** | 142 | 3 | 0 | 0 | 0 |
| sprite-editor | 1545 | 86 | **17.97×** | 1495 | 3 | 35 | 0 | 12 |
| scene-board-customize | 163 | 25 | **6.52×** | 149 | 0 | 11 | 1 | 2 |
| scene-creative | 132 | 36 | **3.67×** | 95 | 7 | 30 | 0 | 0 |
| container-panel | 35 | 12 | **2.92×** | 26 | 3 | 6 | 0 | 0 |
| character-picker | 58 | 22 | **2.64×** | 10 | 3 | 45 | 0 | 0 |
| scene-evolution | 96 | 39 | **2.46×** | 48 | 0 | 42 | 4 | 2 |
| auto-sprites | 17 | 7 | **2.43×** | 11 | 6 | 0 | 0 | 0 |
| scene-evolution-lobby | 10 | 5 | **2.00×** | 2 | 0 | 8 | 0 | 0 |
| scene-startup | 11 | 6 | **1.83×** | 3 | 0 | 8 | 0 | 0 |
| scene-main-menu | 11 | 6 | **1.83×** | 3 | 0 | 8 | 0 | 0 |
| parallax-background | 9 | 5 | **1.80×** | 1 | 8 | 0 | 0 | 0 |
| profile-overlay | 28 | 16 | **1.75×** | 15 | 0 | 13 | 0 | 0 |
| scene-play | 37 | 22 | **1.68×** | 24 | 5 | 8 | 0 | 0 |
| main-menu | 8 | 5 | **1.60×** | 3 | 0 | 5 | 0 | 0 |
| scene-level-select | 8 | 5 | **1.60×** | 2 | 0 | 6 | 0 | 0 |
| config-form | 21 | 14 | **1.50×** | 8 | 0 | 13 | 0 | 0 |
| minigame-hud | 12 | 8 | **1.50×** | 8 | 0 | 4 | 0 | 0 |
| scene-evolution-catalog | 24 | 16 | **1.50×** | 13 | 0 | 11 | 0 | 0 |
| cutscene | 10 | 7 | **1.43×** | 4 | 2 | 4 | 0 | 0 |
| scene-auto-battler-guide | 70 | 51 | **1.37×** | 29 | 0 | 39 | 0 | 2 |
| scene-multiplayer | 18 | 14 | **1.29×** | 7 | 0 | 11 | 0 | 0 |
| scene-auto-battler-lobby | 24 | 19 | **1.26×** | 11 | 0 | 13 | 0 | 0 |
| scene-skin-editor | 29 | 23 | **1.26×** | 13 | 1 | 15 | 0 | 0 |
| scene-deck-lobby | 22 | 18 | **1.22×** | 9 | 0 | 13 | 0 | 0 |
| scene-game-type-editor | 17 | 14 | **1.21×** | 7 | 0 | 10 | 0 | 0 |
| scene-key-binds | 30 | 25 | **1.20×** | 12 | 0 | 18 | 0 | 0 |
| crafting-panel | 36 | 33 | **1.09×** | 9 | 12 | 15 | 0 | 0 |

**What this says for B5 and B6.** The world still batches ~30× and `particles`
62× — flat geometry collapses almost completely and a GL backend serves it
from one draw call. The scenes are where the ratio is poor, and the columns say
why: `crafting-panel` (1.09×), `scene-key-binds` (1.20×),
`scene-game-type-editor` (1.21×) and `scene-deck-lobby` (1.22×) are all short
frames of *interleaved* text and shapes, where nearly every operation changes
the batch key. `scene-auto-battler-guide` is the volume case — 70 operations
across 51 batches, 39 of them text runs.

That is exactly what B5's sprite atlas and B6's glyph atlas exist for, and the
scenes now give those steps something to move: before B3 the only text-heavy
frames in the table were three widget goldens. `scene-board-customize` at 6.52×
is the counter-example worth keeping in view — 149 flat shapes in its board
preview, already near the ceiling, and an atlas will do nothing for it.

---

### B4 — Seal the Java2D seam

**Goal.** Make it impossible to reintroduce a direct Java2D dependency by
accident. Until the escape hatch is gone, the migration can silently un-happen.

**Do.**
- Delete `Java2DTarget.graphicsOf`. It already throws rather than returning null
  when handed a non-Java2D target; now it should not exist.
- Delete the `Graphics2D` overload on `TilePainter.drawTexture` and any sibling
  compatibility overloads left from B2.
- Change `Renderer.beginFrame()` to return `DrawTarget` instead of `Graphics2D`,
  and update its javadoc — the paragraph that currently says "The remaining
  porting work for full GPU scene rendering is a backend-neutral draw API, since
  scenes currently draw via `Graphics2D`" becomes false at this point and must
  be rewritten, not left to rot.
- Add an architecture test asserting that no class under
  `com.larsons.engine.demo`, `.ui`, `.fx`, `.character` or `.autobattler`
  imports `java.awt.Graphics2D`. A grep-based test is fine and needs no
  dependency.

**Verify.** The architecture test fails when a `Graphics2D` import is added back
to any listed package. Confirm by adding one temporarily.

**Done when.** The seam holds mechanically, not by discipline.

---

### B5 — Sprite atlas

**Goal.** Cut draw calls by making consecutive sprite draws share one texture.
This is the step that actually makes GPU submission worthwhile; without it the
GL backend would issue as many binds as Java2D issues blits, and win nothing.

**Why this and not depth sorting.** An earlier idea in this work was to sort
draws by texture. That is **unsafe** for this engine: sprites overlap, and
reordering overlapping draws under a painter's algorithm changes what the player
sees. Atlasing achieves the same batching without reordering anything, which is
why it is the step in the plan and sorting is not.

**Do.**
- Add `com.larsons.engine.graphics.atlas.SpriteAtlas`: packs `BufferedImage`s
  into one backing image with a shelf or skyline packer, returning a
  `Region(page, u0, v0, u1, v1)` per sprite.
- Have the bake-time sprite factories — `EntitySprites`, `PlayerSprites`,
  `DirectionalSprites`, `AutoSprites`, `Skins` — register their output with the
  atlas at load rather than handing back loose images. These keep using
  `createGraphics()` internally; that is baking, not rendering, and is correct.
- Add `DrawTarget.drawRegion(SpriteAtlas.Region region, int x, int y, int w, int h)`.
  `Java2DTarget` implements it as the existing source-rect `drawImage`; the GL
  backend implements it as a quad with no texture bind.
- Pad each region by one transparent pixel and clamp UVs, or bilinear filtering
  on the GPU will bleed neighbouring sprites into each other along the seams.
  This is the single most common atlas bug and it does not show up in the
  Java2D path, so B0's goldens will not catch it — the GL parity check in B9
  will.
- Cap page size at 2048×2048. It is the floor that every target device
  including the M1 Air supports without question, and spilling to a second page
  is cheaper than a driver refusing the texture.

**Verify.** `DrawStats.mergeRatio()` before and after, on the same recorded
frame from a busy level. This is the number the whole GPU case rests on: a
merge ratio near 1.0 means every operation is its own batch and a GPU backend
will not help. Record the before and after values in this document.

**Done when.** Merge ratio for the entity phase improves measurably, and the
goldens are unchanged.

---

### B6 — Glyph atlas

**Goal.** 350 `drawString` sites, and text is drawn every frame in every scene.
Each one is currently a separate Java2D text-layout-and-rasterise.

**Do.**
- Add `com.larsons.engine.graphics.atlas.GlyphAtlas`: rasterises glyphs on
  demand per `(Font, char)` into an atlas page, caching the result.
- Route `Java2DTarget.drawText` through it when the font is one of the small
  set the UI actually uses; keep the direct `Graphics2D.drawString` path for
  anything unusual so no text can fail to render.
- Keep `textWidth`/`textAscent`/`textHeight` answering from `FontMetrics`, not
  from the atlas. Layout must not change when the rasterisation path does, or
  every UI in the game shifts by a pixel and B0 lights up everywhere for no
  real reason.
- Handle the HiDPI case explicitly: on a 2× panel the atlas must rasterise at
  2× or all text goes soft. `DeviceProfile.displayScale` already reports this.

**Verify.** Golden comparison — expect a small nonzero error from hinting
differences and state the number rather than waving at it. Then profile: the
`hud` phase is 0.37 ms on the M1 Air today, so the win here is modest in the
world scenes and large in the text-heavy ones (`AutoBattlerScene`,
`DeckGameScene`, `AutoBattlerGuideScene` — 41–43 `drawString` sites each).
Profile one of those, not `PlayScene`.

**Done when.** Text batches, layout is bit-identical, and the text-heavy scenes
are measurably cheaper.

---

### B7 — The GL module

**Goal.** A place for GL code to live that cannot contaminate the core's
zero-dependency guarantee.

**Do.**
- Convert the build to a multi-project layout: `settings.gradle.kts` gains
  `include("gl")`. Core stays at the root, or moves to `:engine` — decide once
  and do not revisit; the root-stays option is less churn and is the
  recommendation.
- `gl/build.gradle.kts` depends on the core project and on LWJGL 3.3.3
  (`lwjgl`, `lwjgl-opengl`, `lwjgl-glfw`) as **implementation**, with the
  `lwjglNatives` OS detection already written in the root build reused here.
- The core build keeps LWJGL as `testImplementation` only — the shader tests
  still need it and they must keep running whether or not `:gl` is built.
- Add a `runGl` task mirroring `runProfiled`, so the GL path can be profiled
  with the same instrument and the reports are directly comparable.
- The default `jar` task must stay exactly as it is: a single self-contained jar
  with no external dependencies. Add a separate `glDist` for the GL-enabled
  distribution.

**Verify.** `./gradlew jar` produces a jar that runs on a machine with no LWJGL
anywhere. `./gradlew :gl:build` compiles. `./gradlew test` still runs the shader
tests from core.

**Done when.** Two artefacts exist, and the plain one has no dependencies.

---

### B8 — `GlTarget`: the GL implementation of `DrawTarget`

**Goal.** The actual GPU renderer. Everything before this step existed to make
this step a straightforward implementation of a settled interface rather than an
open-ended rewrite.

**Do.**
- `gl/src/main/java/com/larsons/engine/gl/GlContext`: GLFW window, GL 3.3 core
  context, capability creation. Lift the working parts from
  [`GlShaderHarness`](src/test/java/com/larsons/engine/GlShaderHarness.java) —
  particularly the core-profile VAO requirement, which draws nothing and reports
  no error if forgotten.
- `GlBatch`: a growable vertex buffer of `(x, y, u, v, argb)`. Flushes when the
  bound texture changes, when a state push/pop changes clip or alpha, or when
  the buffer fills.
- `GlTarget implements DrawTarget`: every member maps to appending vertices.
  - Flat shapes: two triangles against a 1×1 white texture, so shapes and
    sprites share one shader and one batch.
  - `fillOval` / `drawOval` / `fillArc` / `drawArc`: tessellate to a triangle
    fan, segment count scaled by on-screen radius.
  - `fillShape(Shape)`: `PathIterator` with a flatness tolerance, then ear-clip.
    Used by the terrain shadow union and nothing else hot, so correctness beats
    speed here.
  - `drawImage(BufferedImage, AffineTransform)`: transform the four corners on
    the CPU and submit a quad. Do not push a GL matrix per image.
  - `pushClip`/`popClip`: `glScissor` stack. Nested clips intersect.
  - `pushAlpha`/`popAlpha`: multiply into the vertex colour, not a uniform, so
    it does not break the batch.
  - `pushTransform`/`popTransform`: CPU-side `AffineTransform` stack applied to
    vertices as they are appended, for the same reason.
  - `drawText`: quads from the B6 glyph atlas.
  - `stats()`: keep feeding `DrawStats` so the same merge-ratio instrument works
    on both backends and the two can be compared directly.
- `GlRenderer implements Renderer`: `beginFrame()` returns the `GlTarget`,
  `present()` swaps buffers.
- **Texture upload format.** Java `int` pixels are `0xAARRGGBB`, which on a
  little-endian machine is exactly `GL_BGRA` with
  `GL_UNSIGNED_INT_8_8_8_8_REV`. `GlShaderHarness` already proved this round-
  trips with zero channel error. Use the same pair everywhere and there is no
  repacking and no channel-swap class of bug.

**Verify.** Golden comparison, `GlTarget` against `Java2DTarget`, using B0's
scenes and B0's error metric. Antialiasing will differ, so state the per-scene
error rather than expecting 0.00 — and set the bar from the shader work's
precedent, where 3.58/255 was accepted as "the same picture" for bloom.

**Done when.** Every golden scene renders on GL within the stated error, and
`DrawStats` shows a merge ratio well above the Java2D baseline.

---

### B9 — Backend selection and honest fallback

**Goal.** Pick the right backend automatically, and never strand a player.

**Do.**
- At startup, if `:gl` is on the classpath, try to create a GL 3.3 core context.
  On any failure — no driver, no 3.3, context creation throws — fall back to
  `Java2DRenderer` and log which backend was chosen and why.
- Add `-Dlarsons.render.backend=java2d|gl|auto`, defaulting to `auto`, so a
  player hitting a driver bug has a one-flag workaround and a bug report can
  specify a backend.
- Extend `DeviceProfile` to report the chosen backend and the GL vendor/renderer
  strings, and have `FrameReport` print them. A profile from a player that does
  not say which backend produced it is nearly useless — the same mistake the
  build stamp already taught this project once.
- **Do not** claim "CPU fallback" anywhere unless there is a real probe behind
  it. `STEAM_PLAN.md` Appendix B correctly flags that the current wording
  overstates. Once this step lands the claim becomes true, and the README should
  be updated in the same commit that makes it true — not before.

**Verify.** Force each backend explicitly and confirm both run. Simulate failure
by requesting an impossible context version and confirm the fallback engages and
says so.

**Done when.** `auto` picks GL where available, Java2D everywhere else, and the
report always names the backend.

---

### B10 — Re-profile and decide

**Goal.** Answer, with numbers, whether Job B delivered.

**Do.**
- Run the 30-second profile on both target machines: the Ryzen 7 / RTX 4080
  Super and the M1 Air. Same level, same activity, both backends, all four runs.
- Compare against Appendix C.
- Publish the table here, including any stage that got *worse*.

**The bar.** On the M1 Air, the scene stage is 11.49 ms of a 16.67 ms budget. If
GL does not cut that substantially, something in B5/B6/B8 is not batching and
the merge ratio will say which. Do not proceed to Job A on a backend that has
not proven itself, because Job A's entire economic case is that the frame is
already a GPU texture.

**Done when.** The numbers are recorded and the decision to continue is made
from them.

---

## 5. Job A — GPU post-processing

Precondition: **B10 complete and passed.** The frame must already live in a GPU
texture, or this job costs two transfers per frame to save work that is not the
bottleneck.

### A1 — Render the scene to a texture, not the backbuffer

**Do.** `GlRenderer.beginFrame()` binds an offscreen FBO sized to the drawable.
`present()` currently swaps; it now becomes: run the chain, then blit the result
to the backbuffer and swap.

**Verify.** Frame is unchanged with an empty chain. Goldens green.

---

### A2 — `GlShaderChain`: FBO ping-pong

**Do.**
- Two textures the size of the drawable, alternating as source and destination
  through the pass list. `ShaderChain` already guarantees pass order; preserve
  it exactly.
- Compile each pass's `glsl()` once at chain-set time, not per frame. Cache by
  pass identity.
- Reuse the fullscreen-triangle draw from `GlShaderHarness` — three vertices, no
  vertex buffer, positions invented in the vertex shader. `Shaders.VERTEX_GLSL`
  is the shader it already links against and it compiles (`ShaderCompileTest`).
- Report the chain's cost to `FrameProfiler.Stage.SHADERS` and each pass to
  `recordPass(name, nanos)`, exactly as the CPU chain does, so the per-pass
  breakdown in `FrameReport` keeps working and CPU and GPU can be compared pass
  by pass.
- Handle resize: both textures are reallocated when the drawable changes size.
  A stale-sized ping-pong buffer produces a frame that is subtly stretched, which
  is easy to miss and infuriating to diagnose.

**Verify.** For each pass, render a known frame through `GlShaderChain` and
through `ShaderChain`, and compare with `ShaderParityTest`'s metric. The
expected errors are already known and tabulated in §2 — this step should
reproduce them. **A different number means the backend is wrong, not the
shader**, and that is exactly why those numbers were measured before the backend
existed.

---

### A3 — Uniform binding contract

**Do.** Bind `uTexture`, `uResolution`, `uTime`, `uStrength`, then everything in
`pass.uniforms()`. This is precisely what `GlShaderHarness.bindUniforms` does;
lift it. `ShaderCompileTest.everyExtraUniformAPassAdvertisesExistsInItsShader`
already guarantees no advertised uniform is missing from its shader, so a
location of −1 here is a backend bug rather than a shader bug.

**Verify.** The strength-zero test from `ShaderParityTest`, run against the
production chain rather than the harness.

---

### A4 — `LightingPass` on the GPU

**Do.** It is handled separately for the same reason `ShaderCompileTest` tests it
separately: it is not in `allBuiltIns()`, it is the only pass with array uniforms
(`uLightPos[]`, `uLightColor[]`) and a uniform-bounded loop, and it is the pass
that actually runs during play. Bind the arrays as arrays; respect the driver's
maximum uniform component count and clamp the light count to it rather than
letting the link fail on a low-end driver.

**Verify.** A level with more lights than the clamp still renders, with the
nearest N lit. Parity against the CPU lighting path on a fixed light set.

---

### A5 — Keep the CPU chain, and keep testing it

**Do.** `ShaderChain` stays and stays tested. It is what runs under
`-Dlarsons.render.backend=java2d`, in headless tests, and on any machine the GL
path rejects. The parity tests are what keep the two honest, and they only work
while both exist.

**Verify.** Full suite on both backends.

---

### A6 — Correct the documentation, once it is true

**Do.** Update the README rows listed in `STEAM_PLAN.md` Appendix B, and mark
Appendix A item 3 complete. Update `Renderer`'s javadoc, which currently
describes the GPU backend as hypothetical.

---

## 6. Job C — Eight-point camera rotation

### 6.0 Why this genuinely needs Job B first

Rotating the camera multiplies the number of distinct sprite orientations and
re-sorts the entire draw order every time the view turns. On Java2D each turn
means re-rasterising the world through a rotated `AffineTransform` — the one
operation Java2D handles worst. With a GPU backend, a yaw is a change to the
vertex transform and costs nothing extra per frame.

It is also last because it changes the level format, the input mapping, the
editor and the save file. Doing that on top of a renderer that is still moving is
how both efforts get harder.

### 6.1 What "eight points" means here

*Don't Starve* rotates the camera around the world's vertical axis to eight fixed
compass headings (0°, 45°, … 315°), animating smoothly between them and never
resting anywhere else. The world is 3D there; here it is a block grid, so the
same effect is produced by re-projecting the grid and swapping which faces of
each block are visible.

**Scope.** `SIDE_SCROLL` does not rotate — the screen *is* the vertical plane,
as `PerspectiveSpace.SIDE_VIEW` documents, and there is no axis to turn around.
Rotation applies to the planar formats, `TOP_DOWN` and `ISOMETRIC`. State this
in the level editor so nobody expects otherwise.

---

### C1 — Yaw in the camera

**Do.** Add `yaw` (radians) and `targetYaw` to
[`Camera`](src/main/java/com/larsons/engine/graphics/Camera.java). Apply the
rotation inside `planar()`, `inversePlanar()` and the allocation-free
`worldToScreen(double, double, int[])` — all three, or the hot tile path and
the picking path will disagree and mouse input will land on the wrong tile.

**Verify.** Round-trip property test: for a grid of world points and all eight
yaws, `inversePlanar(planar(p))` returns `p` within floating-point tolerance.
This is cheap and it catches the sign errors that otherwise show up as a world
that rotates the wrong way at exactly two of the eight headings.

---

### C2 — Formalise the height axis

**Do.** The layered formats already stack blocks two deep — `Level.tiles` and
`Level.upper`, and `LevelFormat.layered()` says one layer is floor, two is a
wall, bare ground is a hole. That is a height axis with two levels. Rotation
needs it addressed as one:
- A `heightAt(col, row)` accessor returning 0/1/2.
- A decision, recorded here, on whether Job C also raises the stack limit above
  two. **Recommendation: no.** Two levels is enough for the *Don't Starve* look,
  and raising it touches liquids, pathfinding, the editor palette and the save
  format. Keep it at two and revisit separately.

**Verify.** `StackedBlockTest` extended to assert `heightAt` against the existing
layer semantics.

---

### C3 — Rotate the grid

**Do.** With yaw applied, terrain iteration order and visible bounds both change.
`TerrainPainter.sweepFloor` walks a rectangular `col0..col1 / row0..row1` range
derived from the camera; under rotation the visible region is a rotated
rectangle and that range must become its axis-aligned bounding box, with
off-screen cells rejected per-cell.

**Interaction with `TerrainCache`.** The cache bakes chunks at a fixed
orientation. Two options:
1. Key cache entries by `(chunk, yaw)` — eight times the memory, instant snapping.
2. Invalidate the whole cache on yaw change — no extra memory, a rebuild spike
   at every turn.

**Recommendation: option 2, with the existing frame-level stand-aside.**
`TerrainCache` already has `MAX_REBUILDS_PER_FRAME = 4` and stands aside entirely
when churn exceeds `max(MIN_CHURN_TO_STAND_ASIDE, visible/2)`. A yaw change is
exactly that condition — draw live during the turn, rebuild the cache once the
camera settles. This reuses machinery that has already been debugged rather than
adding an eightfold memory cost for the duration of an animation.

`TerrainCache.faithfulIn(Perspective)` currently excludes `ISOMETRIC` because
antialiased diagonal edges produced a 16.7% seam artefact. **Rotation makes
every format produce diagonal edges.** Before starting C3, re-run that
measurement under yaw for `TOP_DOWN`; if the seams appear there too, the cache
may have to stand aside for all rotated views, which changes the performance
case for this job and needs to be known early rather than discovered late.

**Verify.** Golden frames at all eight yaws per planar format. Profile a
sustained turn and confirm no frame exceeds budget during the snap.

---

### C4 — Face visibility and draw order

**Do.** A stacked block currently draws a top and one side. Under rotation, which
side faces the camera changes. Derive the visible faces from yaw. Painter's
order — the existing `DepthPass` — must also be recomputed: back-to-front along
the *rotated* depth axis, not the world row index.

**Verify.** For each of the eight yaws, a scene with a block behind another
block renders the near one on top. Test this directly; it is the failure that
looks like "the world is inside out" and it is easy to get right at four
headings and wrong at the other four.

---

### C5 — Sprites under rotation

**Do.** Entities are billboards. Decide once, and record it here:

| Option | Consequence |
|--------|-------------|
| **Always face the camera** (recommendation) | Cheap, consistent, and what *Don't Starve* does for most creatures. A character's facing is conveyed by its existing directional frames, chosen relative to yaw. |
| Per-yaw sprite sets | Eight times the art for zero gameplay benefit, in a project that currently ships **zero art assets** (`STEAM_PLAN.md` §1). Not viable. |

`DirectionalSprites` already picks a frame by direction; it needs to pick by
*direction minus yaw* so a character walking north still looks like it is walking
away from the viewer after the camera turns.

**Verify.** Walk a character in each of eight world directions at each of eight
yaws (64 cases, generated) and assert the selected frame index is the expected
one.

---

### C6 — Shadows, decor and liquids

**Do.** `Camera.planarDelta` already exists precisely for this: it projects a
*direction* rather than a point, so a ground-plane vector keeps pointing the same
way across the floor when the projection changes. Route shadow offsets and any
other ground-plane direction through it, and yaw comes free.

`SurfaceDecorPainter` places decor with per-cell orientation; confirm it reads
yaw rather than assuming screen-up. Liquid surface rendering (`LIQUID_SURFACE`)
draws a horizontal band — under rotation the surface normal still points along
the elevation axis, which is correct and needs no change, but verify rather than
assume.

**Verify.** Goldens at eight yaws, checked specifically for shadows that swing
the wrong way. A shadow rotating opposite to the world is the most visible
possible bug and the easiest to introduce.

---

### C7 — Input relative to yaw

**Do.** Movement input is currently world-axis-aligned. After a turn, pressing
"up" must move the character away from the viewer, not toward world north.
Transform the input vector by the inverse yaw at the point where intent becomes
world velocity.

**This is a determinism boundary.** `PlayerPhysics` is shared between client
prediction and server simulation. The rotation must be applied to the input
*before* it enters physics, and the yaw used must be the one the client had when
the input was generated — otherwise a client mid-turn predicts a different
position than the server computes, and the player rubber-bands every time they
rotate the camera.

**Verify.** Extend `PlayerPhysicsTest` with yawed input. Extend the network
tests: rotate the camera on a client during sustained movement and assert
predicted and authoritative positions stay within the existing tolerance.

---

### C8 — The snap animation

**Do.** Bind rotation to two keys (default `Q`/`E`, registered through the
existing key-bind system so they are rebindable). Each press advances
`targetYaw` by 45°. Ease `yaw` toward `targetYaw` over a short fixed duration —
the animation is what makes it read as a camera rather than a teleport.

Reject a new press while a snap is in flight, or queue it; do not blend two
turns. Decide and record which. **Recommendation: queue one, drop the rest** —
a held key should feel responsive without spinning the world.

**Verify.** `yaw` always converges to an exact multiple of 45° and never rests
between. Assert this after a randomised sequence of presses, including presses
during animation.

---

### C9 — Editor and save format

**Do.**
- Creative mode edits at the current yaw. Placement must resolve through the
  same `inversePlanar` the picking path uses, or blocks land in the wrong cell
  at any non-zero yaw. C1's round-trip test is what makes this safe.
- Save the authoring yaw in the level JSON as an optional field defaulting to 0,
  so existing levels load identically. `Level.toMap()` and its reader both need
  the field, and a level written by an older build must still open.

**Verify.** `LevelFormatTest` round-trips a level with a yaw and one without.
Load an existing level file from `src/main/resources` and confirm it is unchanged.

---

### C10 — Multiplayer consistency

**Do.** Yaw is **per-client view state and must not be networked.** Two players
looking at the same world from different angles is the correct behaviour; a
synchronised camera would be a bug. Audit for anything that derives world state
from the camera and would therefore desync — the C7 input path is the known one,
but audit rather than assume.

**Verify.** Two clients at different yaws, same server, sustained play; assert
world state stays identical.

---

## 7. What each instrument proves

Four tools, four distinct questions. Using the wrong one is how a step gets
declared finished while broken.

| Instrument | Question it answers | Where it lives |
|-----------|---------------------|----------------|
| **Golden frames** | Does the player see the same picture? | B0, new |
| **`RecordingTarget`** | Did the code issue the draw calls it was supposed to, in order? | `graphics/draw/RecordingTarget.java` |
| **`DrawStats.mergeRatio()`** | Will a GPU backend help, or is every operation its own batch? | `graphics/draw/DrawStats.java` |
| **`FrameProfiler` / `FrameReport`** | Where does the frame actually go? | `profile/` |
| **`ShaderParityTest` metric** | Do two implementations of the same effect agree? | `ShaderParityTest`, reused by A2 and B8 |

`RecordingTarget` deserves particular use during B2 and B3: it asserts the
*sequence* of commands, which catches a reordering that goldens would miss when
the reordered draws do not happen to overlap in the test scene — and then break
in a real level where they do.

---

## 8. Order of record

```
B0  golden frames                      ← done
B1  widen DrawTarget (7 new members + 2 audits)  ← done
B2  port 12 shared painters/widgets    ← done
B3  port 18 scenes, graphicsOf 39 → 0  ← done
B4  seal the seam (delete graphicsOf, Renderer returns DrawTarget)  ← start here
B5  sprite atlas
B6  glyph atlas
B7  :gl Gradle module
B8  GlTarget + GlRenderer
B9  backend selection + fallback
B10 re-profile on both machines, decide
      │
      ├─ A1  scene renders to an FBO
      │  A2  GlShaderChain ping-pong
      │  A3  uniform binding
      │  A4  LightingPass
      │  A5  keep + test the CPU chain
      │  A6  correct the README
      │
      └─ C1  camera yaw
         C2  formalise the height axis
         C3  rotate the grid (re-measure TerrainCache seams FIRST)
         C4  face visibility + depth order
         C5  billboards + directional frames
         C6  shadows, decor, liquids
         C7  yaw-relative input (determinism boundary)
         C8  the snap animation
         C9  editor + save format
         C10 multiplayer consistency
```

A and C are independent of each other once B10 passes. A is much smaller and its
risk is already retired, so it is the natural next one — but nothing forces that
order.

---

## Appendix A — Migration surface, measured

Originally counted from `src/main/java` on 2026-08-02 at commit `85196b9`; the
right-hand column is the same measurement re-taken after B3.

| Measure | Before B2 | After B3 |
|---------|----------:|---------:|
| `Java2DTarget.graphicsOf` call sites | 39 (+1 definition) | **0** (+1 definition) |
| Files naming `Graphics2D` | 48 | **26** (2 in `demo/`) |
| `drawString` sites | 350 | **1** |
| `DrawTarget` abstract members | 26 | **29** |
| `DrawTarget` `Color`/convenience overloads | 0 | **23** |

The two `demo/` files left are `CreativeScene`, which bakes 24 palette icons
with `createGraphics()`, and one comment in `DeckGameScene`. The remaining 24
are the ones that should still name it: `Java2DTarget`, `Java2DRenderer`,
`Renderer` (until B4 changes its return type), `Engine`, `AssetLoader`,
`Skins`, `PlayerSprites`, `DirectionalSprites`, `EntitySprites`, `AutoSprites`,
`CharacterPicker`, `ParallaxBackground`, `CutscenePainter`, `Particles`,
`TerrainCache` — bakes, all of them — plus javadoc and comments in `UiText`,
`Scene`, `FrameProfiler`, `DecorPainter` and `SurfaceDecorPainter`.

**Three things for B4 to clear, measured rather than assumed.** `SceneManager`,
`DecorPainter` and `SurfaceDecorPainter` name `Graphics2D` only in an import
that nothing uses. `TilePainter.drawTexture(Graphics2D, …)` and
`TerrainPainter.draw(Graphics2D, …)` / `drawMiningCracks(Graphics2D, …)` now
have **no callers in `src/main`** — only four in tests — so B4 deletes an
overload nobody depends on rather than a migration path someone might.

---

## Appendix B — Baseline to beat

**M1 Air, `frameprofile8`, 30 s sample, side-scroller with active play.**

| Stage | ms/frame | Note |
|-------|----------|------|
| **Total work** | **17.12** | Budget is 16.67 at 60 Hz — over by 0.45 |
| scene | 11.49 | the target of Job B |
| — terrain | 6.38 | already cached; this is the uncached remainder |
| — entities | 3.85 | the target of B5 |
| — hud | 0.37 | the target of B6, but see B6's note on which scene to profile |
| — decor | 0.13 | |
| present | 3.71 | p99 9.74 |
| update | — | p99 15.27 — worth watching; not a rendering problem |
| **Achieved** | **58 FPS** | up from 7 FPS at the start of this work |

**Shader parity, CPU vs GPU, mean absolute channel error out of 255.** These are
the numbers step A2 must reproduce; a deviation indicts the backend, not the
shader.

| pass | error |
|------|-------|
| pixelate, wave, chromatic_aberration, color_grade, invert | 0.00 |
| scanlines | 0.04 |
| vignette | 0.32 |
| grayscale | 0.47 |
| bloom | 3.58 |

**Suite:** 810 tests, 0 failures, 3 skipped (display-dependent; skip by design).

---

## Appendix C — Decisions recorded, so they are not relitigated

| Decision | Choice | Why |
|----------|--------|-----|
| Batch by sorting draws by texture | **No** | Unsafe under a painter's algorithm with overlapping sprites — it changes what the player sees. Atlasing (B5) gets the same batching without reordering. |
| Job A before Job B | **No** | Without B, A costs two full-frame transfers per frame to optimise a stage that is not the bottleneck. |
| LWJGL in the core runtime | **Never** | Requirement #4. It lives in `:gl`, which core does not depend on. |
| Per-yaw terrain cache | **No** | 8× memory for an animation. Reuse the existing frame-level stand-aside instead (C3). |
| Per-yaw sprite sets | **No** | 8× the art in a project with zero art assets. Billboards (C5). |
| Raise the block stack above two layers | **No, not in Job C** | Touches liquids, pathfinding, editor and save format. Separate job. |
| Networked camera yaw | **No** | Per-client view state. Players seeing the world from different angles is correct. |
| Rotation in `SIDE_SCROLL` | **No** | The screen is the vertical plane; there is no axis to rotate around. |
