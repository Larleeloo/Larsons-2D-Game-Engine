# Render Plan — GPU Acceleration, End to End

**Status:** Living document. Written 2026-08-02 against commit `85196b9` on
`claude/gpu-acceleration-shaders-oqbx54`. **Job B is complete and it delivered.**
Measured on the M1 Air across four runs on two builds: the scene stage fell from
**9.77/9.42 ms to 3.92/3.65 ms** (−61%), frame work from 12.1–16.8 ms to a stable
**6.7 ms**, and headroom from between −1.0% and +27% to a steady **+58–60%**. The
backend draws all 32 golden frames within the 3.58 bar (worst 2.59), collapses
3,356 operations into 68 draw calls, is probed for at startup with an honest
fallback (B9), and is named in every report.

Two things are recorded against that result rather than tidied away. **One
serious bug shipped and was caught by playing a real level, not by the suite** —
a vertex buffer that grew mid-triangle and desynchronised every triangle after
it, invisible to 32 pixel-perfect golden frames because none of them is big
enough to trip it (B8a). And **one claim made here from a single sample was wrong
and is corrected in place**: the `present` stage is not a Job B win, it is simply
unstable on this machine. See B10.

**The desktop was skipped by decision**, not omission; the bar B10 stated was in
terms of the Air, which was the machine over budget.

**Job A is now justified and is the next job.** A profile with the chain *on*
puts the CPU shader stage at **5.460 ms** — 29% of the frame, and enough to take
the Java2D renderer 12.6% over budget at 53 FPS. More seriously, the same run
shows the GL backend running **neither** pass: a GPU build currently has no
day/night lighting at all, which is a correctness defect rather than a missing
optimisation. §5.0 and §5.1.

**Job D (§7) is next, ahead of A.** GL shimmers by about a pixel at 2× HiDPI as
the camera moves and Java2D does not. It is scheduled first because a player sees
it every second, and because its likely fix — an offscreen surface at logical
resolution — *is* A1's first deliverable rather than a detour around it.

Also open: **B11** fixed a GL jar that could not open a window when
double-clicked on macOS, the platform it had been profiled on for four steps. And
the `update` stage spikes to 15–21 ms at p99 on both backends, which no renderer
work will touch; that has a plan of its own in
**[`SIM_PLAN.md`](SIM_PLAN.md)**. Two of the plan's own instructions have now been measured to be
incomplete rather than wrong-headed: B6's "route `drawText` through it" (Java2D
already has a glyph cache and beats any per-character blit) and B8's "against a
1×1 white texture" (which shares a shader but not a batch — the white texel has
to be *on the atlas page*). A third — B9's "if `:gl` is on the classpath" —
turned out to be forbidden as written, because the core may not find out; it
became a `ServiceLoader` service instead. All three findings are recorded inside
their steps.
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
| The whole engine renders through `DrawTarget`, not `Graphics2D` | Every painter, widget and scene takes a `DrawTarget`; `Renderer.beginFrame()` returns one; `SceneFramesTest.noSceneReachesPastTheDrawTarget` records every scene through a target with no Graphics2D behind it |
| It cannot quietly stop being true | `SealedSeamTest` — B4. Java2D is named in code by 13 of 236 main sources, 10 of them in `com.larsons.engine.graphics` and 3 bakes outside it; the scan fails the build on a fourth, and carries its own negative control |
| Glyphs share the sprites' pages, so text and icons batch together | `GlyphBatchingTest` — 20 of 32 frames improved; `crafting-panel`, the frame B5 named as unreachable, went 33 batches → 14. `GlyphAtlasTest` holds the pixels at 0.00 over the printable ASCII range in six fonts |
| Scenes are pixel-compared, not just the painters | `SceneFrames` — 16 of the 18 scenes goldened at 800×480 from fixed inputs; the two excluded draw nothing without a live network session |
| Frames are always composed offscreen | `Java2DRenderer` composes to a backing image unconditionally (`-Dlarsons.render.direct=true` is the escape hatch). Scene stage on Linux fell 1.071 → 0.374 ms from this alone |
| Static terrain is cached | `TerrainCache` — 7.8× on still ground, parity under churn, one global pixel lattice so the floor does not shake |
| The frame cost is known per stage | `FrameProfiler` / `FrameReport` — see Appendix C |
| The core still ships with nothing on its runtime classpath | B7 — `:verifyNoRuntimeDependencies` resolves it and fails the `jar` task on any external artefact; `ModuleBoundaryTest` reads the sources and fails on `org.lwjgl` or `com.larsons.engine.gl` appearing in one. The plain jar has 517 engine entries and 0 LWJGL entries |
| A GL backend draws the same picture as Java2D | B8 — `GlParityTest` renders all 32 of B0's frames through both and subtracts: worst **2.59 / 255** against a bar of 3.58, six frames at exactly 0.00 |
| The batching the atlases were built for is real, not modelled | B8 — the catalogue's 3,356 operations become **68** `glDrawArrays` calls, 49.35×, measured on the backend rather than predicted by a counter |
| The engine picks a backend and never strands a player | B9 — `Backends` probes for a GL 3.3 context over `ServiceLoader` and falls back to Java2D with a reason; `BackendSelectionTest` runs every route on a machine with no GPU, `GlBackendTest` provokes the failure on one that has |
| **Job B made the frame faster on the machine that needed it** | B10 — M1 Air, four runs across two builds: scene **9.77/9.42 → 3.92/3.65 ms**, under 7% spread within each backend and −61% between them; work per frame → a stable **6.7 ms**, headroom → **+58–60%** |
| A number that moves between two runs of the same code is not a result | B10 — `present` read 5.061 ms then 0.962 ms on Java2D with nothing in between that touches it. The 78% saving written up from the first pair is withdrawn |
| A backend can pass 32 pixel-perfect frames and still be broken | B8a — `GlBatch` grew mid-triangle above 4,096 vertices and scrambled every triangle after it. No catalogue frame is that big. `GlBatchTest` reproduces it at the predicted vertex |
| A profile says which renderer produced it | B9 — `DeviceProfile.backend()` / `gpu()`, printed by `FrameReport`. The build stamp taught this lesson once already |
| Both backends run the whole game, not a test harness | B9 — `-Dlarsons.run.seconds` launches the real game on each and exits; both wrote a report under `xvfb-run`, `gl` at 0.631 ms scene against `java2d` at 1.591 ms on a software rasteriser |

**The honest summary:** Job A's *unknowns* are gone; only its plumbing remains,
and the plumbing has a working prototype. Job B's migration is done and, as of
B4, sealed — world, UI and scenes all draw through the seam, and a build fails
if anything reaches back through it. Sprites batch as of B5: a busy entity phase
went from 65 draw calls to 34, and every sprite the engine generates sits on one
texture page. Text batches onto that same page as of B6, which took the
interleaved icon-and-label frames from 1.09× to 2.57× and the whole catalogue
from 5.80× to 6.77×. **The backend exists as of B8** and turns those 6.77×
predicted batches into a measured 49.35× — one draw call for most frames in the
catalogue — at a worst-case pixel difference of 2.59 out of 255. **The engine
chooses it as of B9**, over a `ServiceLoader` service so the core still cannot
name it, with a probe, an honest fallback and the backend recorded in every
report. **B10 proved it on the machine that was over budget**: the scene stage
fell 61% and the M1 Air now holds 60 FPS with 58–60% of the budget spare. Job B
is closed.

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
4. **The suite stays green.** Last full run: **964 tests, 0 failures, 3 skipped**
   under `xvfb-run` (was 810/0/3 when this was written; B0–B10 added the rest;
   17 skip with no display at all). The skips are
   environment-dependent and skip rather than fail by design — three need a
   display, fourteen need a GL driver (seven in core, seven in `:gl`). Under
   `xvfb-run` the same suite is **959/0/3**: the GL tests all run on a software
   driver, and the three that do not are display tests losing a race with the
   eleven classes that set `java.awt.headless=true` in the shared JVM (see B4).
   A step ends with that or better.
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
solid ones. §8 says these two instruments answer different questions; they do.

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

> **B5 measured this and both halves of the last paragraph were wrong.**
> `crafting-panel` and `character-picker` did not move at all — their images are
> never *consecutive*, and an atlas merges neighbours. `world-*` moved most
> (29.00× → 36.25×), because their three sprite draws *are* adjacent. The place
> to look was the entity phase, which had no frame in this catalogue until B5
> added one. See B5's correction.

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

> **Half right, measured at B5.** `scene-board-customize` was indeed untouched,
> and is now the control `AtlasBatchingTest` asserts on. But the interleaved
> frames were not what a *sprite* atlas could reach: two atlases only rescue an
> icon-then-text row if they are the same texture. That is now B6's first
> design decision rather than an assumption — see B5's correction.

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

#### B4 — done

Everything above, and the seal came out wider than the step asked for.

**`graphicsOf` is gone**, along with `TilePainter.drawTexture(Graphics2D, …)`,
`TerrainPainter.draw(Graphics2D, …)` (both arities) and
`TerrainPainter.drawMiningCracks(Graphics2D, …)` — the compatibility overloads
B2 promised to remove here. All four had zero callers in `src/main`; the four
test callers now wrap with `Java2DTarget.unsized(g)`, which is the one honest
thing a caller holding a Graphics2D should do, and
`anUnsizedWrapperDrawsTheSamePixelsAsASizedTarget` keeps that path pixel-checked
rather than merely compiled.

**`Renderer.beginFrame()` returns `DrawTarget`.** Nothing in the `Renderer`
interface names Java2D any more, so a frame is backend-neutral verbs from
acquisition to presentation. The stale javadoc paragraph is rewritten rather
than deleted: it now says why the seam is closed, which is the fact the next
reader needs.

Two consequences worth stating, because both look like losses and are not:

- **The backend builds the frame's target, not `Engine`.** That is what makes
  its `DrawStats` the frame's own tally — `Engine` can no longer make a second
  target over the same surface, because a caller holding a `DrawTarget` has
  nothing to make one from.
- **The profile overlay now shares the frame's target.** It used to get its own
  `Java2DTarget` so its draw calls would not inflate the count it was
  reporting. Re-reading `FrameProfiler.recordDraws` showed that is not what
  made the numbers right: it copies the counters out on the spot, and it is
  called when the scene finishes and before the overlay draws. The second
  target was belt-and-braces over a guarantee that already held. `Engine.draw`
  now says so where the reason lives.

**The architecture test seals more than five packages.** The step named
`demo`, `ui`, `fx`, `character` and `autobattler`. Measuring first showed the
honest boundary is one package wider in the other direction: **Java2D is named
in code by thirteen files, ten of them in `com.larsons.engine.graphics` and
three bakes outside it.** So
[`SealedSeamTest`](src/test/java/com/larsons/engine/render/SealedSeamTest.java)
scans the whole engine minus `graphics/**` — 219 of 236 main sources — and the
rule it states is the one worth stating: *Java2D lives in
`com.larsons.engine.graphics` and nowhere else.*

Four names are banned, each for a different reason: `graphicsOf` (the deleted
unwrap, banned by name so it cannot come back under it), `Java2DTarget` (naming
a concrete backend, whether by import, cast or parameter), `.graphics()` (what
a caster calls next), and `Graphics2D` itself — the **token**, not the import,
so a fully-qualified `java.awt.Graphics2D` does not walk past a test that only
reads import lines. Comments and string literals are stripped before scanning,
so prose explaining the migration stays legal; the alternative is a test whose
only remedy is deleting the explanation.

Three files are excused, and only for baking — `CreativeScene` (24 palette
icons), `CharacterPicker`, `AutoSprites`. An excuse is not a trust: an excused
file must actually call `createGraphics()`, and no `public` or `protected`
member of it may mention `Graphics2D`. A bake that keeps its Graphics2D to
itself is fine; one that hands it out is a seam under another name.
`everyBakeExcuseIsStillEarned` deletes the excuse the moment the file stops
needing it, which is the rule `SceneFramesTest` already applies to its own
exclusions.

**The negative control runs on every build, not once by hand.** The step said
to confirm the test fails by adding an import temporarily. Doing that once
proves the test worked on the day it was written. The checker is therefore a
pure function of `(path, source)`, and `theCheckerRejectsEveryFormItClaimsTo`
feeds it all four banned forms — plus the fully-qualified variant — every time
the suite runs; `commentsAndStringsMayStillSayTheWords` checks the other
direction, and `theScanCoversTheEngine` checks the scan is pointed at
something. The one-time confirmation was done as well, on a real file:

```
com/larsons/engine/demo/PlayScene.java:89 names `Graphics2D`
    — draw through DrawTarget; bake through BufferedImage.createGraphics()
com/larsons/engine/demo/PlayScene.java:88 names `Java2DTarget`
    — backend-neutral code must not name a concrete backend
```

**The README said the opposite of the truth in four places**, and the step's own
instruction — rewrite the stale paragraph, do not leave it to rot — applies to
the documentation of record as much as to a javadoc comment. Three said the
remaining work for GPU scene rendering is "a backend-neutral draw API, since
scenes draw with `Graphics2D`", which stopped being true at B3 and is now
enforced false. The fourth was worse than stale: the "A new scene" sample
declared `render(Graphics2D g, float alpha)`, a signature that has not compiled
against `Scene` since B1. All four corrected. (This does not overlap A6, which
is about the shader and GPU-backend rows.)

**Also cleared: seven dead imports** — `Graphics2D` in `SceneManager`,
`DecorPainter` and `SurfaceDecorPainter`, and `Java2DTarget` in
`SurfaceDecorPainter`, `PlayScene`, `AutoBattlerScene`, `AutoBattlerGuideScene`
and `CreativeScene`. All unused since B3, and every one of them a reference a
grep-based instrument would have to explain away for ever.

**Verified.** Suite **865 tests, 0 failures, 10 skipped** (was 860/0/10; the
five new ones are `SealedSeamTest`). Goldens unchanged — 33 `GoldenFramesTest`
and 4 `SceneFramesTest` green, as a step that deletes dead code and changes one
return type must leave them.

**The three display-dependent skips are exactly the code this step changed** —
the `FrameProfilerTest` cases that drive a real `BufferStrategy` through
`beginFrame()`, skipped in the container that is supposed to verify it. Left
there, "suite green" would have meant "the changed line compiles". So they were
run: `xvfb-run -a ./gradlew test --tests '…FrameProfilerTest'` gives **40/40, 0
skipped, 0 failures**, and the new return type is exercised against a real
surface and a real buffer flip.

Two things fell out of doing that, both worth having in writing:

- **Those three skip under `xvfb-run` in a *full* run, and it is not the
  display's fault.** Eleven test classes set `java.awt.headless=true` in a
  `@BeforeAll`, Gradle forks one JVM for the suite, and AWT latches that on
  first use — so whether these three run depends on class order, not on
  `DISPLAY`. Running the class alone is the reliable way to reach them, which
  is what B10 will want for any before/after on a real surface. Not B4's to
  fix, but B4's to stop mistaking for an environment limit.
- **Under `xvfb-run` the seven GL skips disappear.** The full suite goes
  **865 tests, 0 failures, 3 skipped**, with `ShaderCompileTest` 5/5 and
  `ShaderParityTest` 3/3 executed on a software GL driver — bloom reporting
  mean channel error **3.58**, the exact number in Appendix B. B7–B9 can be
  checked in this container after all, rather than only on hardware.

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

#### B5 — done

[`SpriteAtlas`](src/main/java/com/larsons/engine/graphics/atlas/SpriteAtlas.java)
packs with a skyline bottom-left heuristic, one transparent pixel of gutter per
region, pages that start at 256 and double on demand to the 2048 cap.
`DrawTarget` gained `drawRegion`. The five named factories — `EntitySprites`,
`AutoSprites`, `DirectionalSprites`, `Skins` and, through `Skins`,
`PlayerSprites` — register every sprite they bake. **Every sprite the whole test
suite bakes, 892 of them, fits on one 2048×1024 page at 64% occupancy**, which
means the entire engine's generated art is one texture bind.

**Atlasing here is additive, and that is what made it a one-commit change.**
Registering copies a sprite's pixels into a page; the loose `BufferedImage` stays
valid and stays drawn by anything the atlas cannot serve — a warped
`AffineTransform` blit, a sheet too large to be worth packing, a source
rectangle that reaches past the sprite's own bounds. So no factory changed its
return type and none of the ~2,000 call sites moved. The backend resolves the
image it is handed (`SpriteAtlas.regionOf`) and draws the region instead, which
is exactly what the GL backend has to do in B8 anyway.

#### The numbers

Measured by [`DrawCallReport`](src/test/java/com/larsons/engine/render/DrawCallReport.java)
over `SceneFrames.allFrames()` — the same fixed frames as the pixel comparison,
in one process, on one build. The two halves differ only by
`SpriteAtlas.setRouting`, which is the whole reason routing is a switch separate
from registration: gating registration instead would have made each measurement
depend on which ran first, because the factories bake lazily and cache for ever.
Only the frames that moved are listed; the other 27 are byte-for-byte the same
command stream, and the report at `build/reports/draw-calls.md` has all 32.

| frame | ops | batches before | batches after | merge before | merge after |
|-------|----:|---------------:|-------------:|-------------:|------------:|
| **world-crowd** | 374 | 65 | **34** | 5.75× | **11.00×** |
| auto-sprites | 17 | 7 | **2** | 2.43× | **8.50×** |
| world-top-down | 149 | 5 | **3** | 29.80× | **49.67×** |
| world-isometric | 153 | 5 | **4** | 30.60× | **38.25×** |
| world-side-scroll | 145 | 5 | **4** | 29.00× | **36.25×** |
| *all 32 frames* | 3364 | 620 | 580 | 5.43× | 5.80× |

**`world-crowd` is new, and the reason it had to be.** B5's verification asks
for the merge ratio "on the same recorded frame from a busy level", and the
busiest frame in the catalogue drew *one* mob. Entities are 3.85 ms of the M1
Air's 11.49 ms scene stage — the largest single thing this step aims at — and a
number taken from a frame with three sprites in it says nothing about them. The
new frame draws thirty mobs, eight dropped items and six projectiles over a
top-down floor, **each one the way `PlayScene` draws it, interleaving
included**: a healthy mob is one sprite, a hurt one is a sprite followed by a
tint and two health-bar rectangles, a dropped item is a shadow oval and a rarity
gradient *before* its sprite, and the whole lot goes through a `DepthPass`
because the engine sorts entity sprites by depth before emitting them. Leaving
those breaks out would have produced a bigger number and a false one. It still
halves the frame: 65 batches to 34, and since nothing but an image draw changed
its batch key, all 31 of that difference is the 44 sprites in it.

#### A correction to B3's reading of its own table

B3 predicted that `crafting-panel` (1.09×, twelve images) and `character-picker`
(2.64×, three) were "the frames whose numbers should move in B5/B6". **They did
not move at all**, and the measurement says why in one line: their images are
never *consecutive*. A crafting row is icon, name, count, icon, name, count —
so every image draw is preceded and followed by a text run, and there is no run
of sprites for an atlas to collapse. Atlasing merges neighbours; it cannot merge
draws that are not neighbours, and nothing in this step was ever going to.

**This is a real finding for B6, not a footnote.** The plan says that with both
atlases in place "those two columns collapse to one texture and one atlas, and
the interleaving stops mattering". That is only true if the glyph atlas and the
sprite atlas are *the same texture*, or if the backend can address both from one
batch — otherwise `IMAGE`-then-`TEXT`-then-`IMAGE` still flushes twice per row
however well each side is packed, and `crafting-panel` stays at 1.09× with two
atlases instead of none. So B6 should rasterise glyphs into a page of this same
`SpriteAtlas` rather than building a parallel one, and `DrawStats` will need to
stop treating `TEXT` and `IMAGE` as inherently unmergeable when they share a
page. `SpriteAtlas.register` already takes any `BufferedImage`, so the packing
side of that is free; the batch-key model is the part that needs deciding.

#### Two allocations that had to go before anything could be atlased

Neither was sought. Both are the same failure and it is worth naming, because it
is invisible to every instrument except this one:

- **`DirectionalSprites.frame`** returned `sheet.getSubimage(...)`, which
  allocates a fresh `BufferedImage` on every call. This is the unskinned
  player's sprite, so it ran per character per frame. The garbage was the small
  half. The expensive half is that a batching backend keys on the image, so
  every frame handed it a texture it had never seen — a bind per character per
  frame that no atlas could ever have merged, because the thing being drawn was
  a different object each time.
- **`Skins.icon`** baked a fresh image per call, and the creative palette calls
  it once per swatch per frame.

Both are cached now, which is what makes their identity stable, which is what
makes them atlasable at all. A sprite factory that returns a new object per call
cannot be batched by anything.

#### Verified

- **Goldens byte-identical.** Regenerating the whole catalogue with the atlas
  live rewrote exactly one file: the new `world-crowd.png`. All 31 pre-existing
  references came back identical to the ones committed before this step, which
  is the strongest available statement that routing a draw through a packed page
  is a change of texture binding and not a change of picture.
- **Pixel parity, directly.** `SpriteAtlasTest` renders the same icon four ways
  a call site really draws one — natural size, scaled, mirrored, and a
  sub-rectangle — with routing off and on, and subtracts. Also that a packed
  sprite's pixels survive the copy (`AlphaComposite.Src`, not `SrcOver`, or
  every translucent pixel in the engine would come back wrong through the atlas
  and nowhere else), that no two regions overlap across 200 ragged sizes, that
  every region is fenced by a transparent gutter, that a page that doubles
  leaves every region where it was, and that a re-baked key reuses its slot
  rather than walking the atlas out of space on repeated texture-pack rescans.
- **The gutter is checked on the pixels, not on the picture.** Java2D clamps to
  the source rectangle and would never show a bleed, so no golden frame can
  catch the one bug this padding exists to prevent. B9's GL parity pass is what
  proves it end to end; until then the assertion is on the page itself.
- **Suite: 882 tests, 0 failures, 10 skipped** (was 865/0/10; the 17 new are 12
  in `SpriteAtlasTest`, 4 in `AtlasBatchingTest`, and one more golden frame).
  The same 10 environment-dependent skips.
- **`AtlasBatchingTest` keeps the number honest on every build**, because this
  is the kind of number that rots quietly: one factory that stops registering,
  one painter that starts slicing a fresh sub-image per frame, and the ratio
  returns to where it was while every other test stays green. It asserts no
  frame got *worse*, that operation counts are unchanged (atlasing changes which
  texture a draw comes from, never how many draws there are), that
  `auto-sprites` is one bind, and — the control — that `scene-board-customize`,
  149 flat shapes and no sprites, is untouched. An atlas that appeared to
  improve *that* would be measuring itself.

#### What it cost Java2D: nothing measurable

Java2D blits per call either way, so this step is not supposed to make the
shipping backend faster — the merge ratio is a statement about a backend that
does not exist yet. Worth checking that it did not make it *slower*, since every
sprite draw now goes through a hash lookup and a source-rectangle blit out of a
2048-wide page. Over 300 renders per frame per configuration: `world-crowd`
−3.5% then +0.2%, `sprite-editor` −0.6% then +1.0%, `world-top-down` −2.4% then
−1.8% — noise. The one consistent mover is `crafting-panel` at −9.9% and −16.3%,
which is plausible rather than surprising: one hot page image stays in Java2D's
accelerated-image cache where a hundred small ones each need their own entry.
The honest claim is *no regression*, and a hint that the icon-heavy screens
prefer it.

**`-Dlarsons.render.atlas=false`** turns region routing off entirely — the
before-and-after switch above, and a one-flag workaround for a driver that
dislikes the packed path, in the same spirit as `-Dlarsons.render.direct`.

---

### B6 — Glyph atlas

**Goal.** 350 `drawString` sites, and text is drawn every frame in every scene.
Each one is currently a separate Java2D text-layout-and-rasterise.

**Do.**
- Add `com.larsons.engine.graphics.atlas.GlyphAtlas`: rasterises glyphs on
  demand per `(Font, char)` into an atlas page, caching the result.
- **Rasterise into a page of B5's `SpriteAtlas`, not a parallel one**, and
  decide what that means for `DrawStats`' batch key. B5 measured the reason:
  `crafting-panel` is icon-text-icon-text and stayed at 1.09× with sprites fully
  atlased, because an atlas merges *neighbours* and its neighbours are text. Two
  separate atlases leave it at 1.09× — the flush moves from "different image" to
  "different texture", which costs the same. One shared page is what makes the
  interleaving stop mattering, and `SpriteAtlas.register` already takes any
  `BufferedImage`, so the packing side is free. The part that needs deciding is
  that `DrawStats` currently treats `TEXT` and `IMAGE` as inherently
  unmergeable, which stops being true exactly when they share a page.
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

#### B6 — done, and one instruction was measured to be wrong

[`GlyphAtlas`](src/main/java/com/larsons/engine/graphics/atlas/GlyphAtlas.java)
rasterises a glyph on demand per `(font, colour, rendering context, char)` and
packs it into a page of B5's `SpriteAtlas`, as the step required. `DrawStats`
keys a text run on that page, so an icon and the label beside it are one batch.
Text batches, layout is bit-identical, and **the text-heavy scenes are not
measurably cheaper on Java2D — because they cannot be.** That last part is the
step's real finding and it is set out below rather than buried.

| frame | ops | batches before | after | merge before | merge after |
|-------|----:|---------------:|------:|-------------:|------------:|
| **crafting-panel** | 36 | 33 | **14** | 1.09× | **2.57×** |
| scene-evolution-lobby | 10 | 5 | **2** | 2.00× | **5.00×** |
| scene-level-select | 8 | 5 | **2** | 1.60× | **4.00×** |
| **scene-auto-battler-guide** | 70 | 51 | **29** | 1.37× | **2.41×** |
| character-picker | 58 | 22 | **14** | 2.64× | **4.14×** |
| main-menu | 8 | 5 | **3** | 1.60× | **2.67×** |
| scene-main-menu | 11 | 6 | **4** | 1.83× | **2.75×** |
| scene-startup | 11 | 6 | **4** | 1.83× | **2.75×** |
| container-panel | 35 | 12 | **9** | 2.92× | **3.89×** |
| scene-key-binds | 30 | 25 | **20** | 1.20× | **1.50×** |
| *all 32 frames* | 3364 | 580 | **497** | 5.80× | **6.77×** |

Twenty of the thirty-two frames moved. The full table is
`build/reports/glyph-batching.md`, written by `GlyphBatchingTest` on every
build; the only variable between its two halves is `-Dlarsons.render.glyphs`,
with the sprite atlas on throughout, which is the configuration that ships.

**`crafting-panel` is the row that matters**, because B5 named it as the frame
its own step could not reach and predicted exactly what would reach it. 33
batches to 14, 1.09× to 2.57×. Twelve icons and fifteen labels interleaved one
for one now come off one texture, and the interleaving has stopped mattering
exactly as the shared page was supposed to make it stop mattering. B5's
correction was right, and following it is why this step packed into
`SpriteAtlas` instead of building a second atlas beside it.

#### The instruction that was wrong: "route `drawText` through it"

The step says to route `Java2DTarget.drawText` through the atlas, on the premise
that "each one is currently a separate Java2D text-layout-and-rasterise". **That
premise is false.** Java2D already has a glyph atlas — the font system's glyph
cache — and `drawString` rasterises a whole run out of it in one dispatch.
Replacing it with one `drawImage` per character pays the general image
pipeline's per-call setup every character instead of once per run:

| putting one glyph on the surface | cost per glyph |
|----------------------------------|---------------:|
| `drawString` for the whole run | **82.6 ns** |
| `drawImage` from a page sub-rectangle | 304.3 ns |
| `drawImage` from a cached sub-image of the page | 303.8 ns |
| `drawImage` from a loose per-glyph cell | 296.4 ns |
| `drawImage` from a premultiplied per-glyph cell | 293.5 ns |

The four blitting forms agree to within noise, which is the answer: the cost is
the per-call dispatch, so no arrangement of the pixels recovers it. Premultiplied
storage was tried specifically because a non-premultiplied source onto an
`INT_RGB` surface is the classic slow blit, and it changes nothing here. Over
whole frames the penalty was **+0.6% to +18.4%**, worst on exactly the
text-heavy scenes the step aimed at.

So the step ships **packed but not blitted**: `drawText` resolves its run
through the atlas, packs whatever is not packed yet, and records the page as its
batch key — then draws with `drawString`, because on this backend that is
faster. `-Dlarsons.render.glyphblit=true` takes the other path.

Three reasons that is the right answer rather than a retreat:

- **The two halves answer different questions.** The packing is what a GL
  backend needs and what makes an icon and a label one bind; the blitting is how
  *this* backend would have consumed it, and this backend has something better.
  Nothing about the GL case depends on Java2D choosing the slow path to prove
  it.
- **The key is a claim, and it is the same claim B5 already makes.**
  `drawRegion` records a sprite against its page while Java2D blits per call
  either way, on the grounds that a batching backend really would hold one
  texture across the run. That argument is unchanged here, and it is *stronger*,
  because the cells are not merely packed — they are proved drawable.
- **The blitting path is the proof.** With it on, `GlyphAtlasTest` renders every
  printable ASCII character in six fonts through both paths and subtracts. If it
  were deleted, the atlas's contents would go unchecked until B8, on a backend
  that does not exist yet, which is the position B0 exists to prevent.

Measured cost of the shipping configuration, over the seven most text-heavy
frames, 100 renders a burst, best-of-fourteen interleaved bursts per
configuration: **−3.0% to +3.1%**, with `config-form` — the smallest frame in
the set at half a millisecond — reading −0.4% on one run and +9.8% on another,
which is what noise looks like rather than a signal. The honest claim is *no
regression*, which is the bar invariant 2 sets for anything that touches the
shipping backend.

#### Layout is bit-identical, and it is checked rather than hoped

`textWidth`, `textAscent` and `textHeight` still answer from `FontMetrics`,
untouched, as the step required — so nothing in any UI moved by a pixel and the
goldens are unchanged at 0.00 across all 33 painter frames and 16 scenes. The
step predicted "a small nonzero error from hinting differences and state the
number rather than waving at it". **The number is 0.00**, and it is worth saying
why it can be, because it is not luck:

With antialiasing on and fractional metrics off — what this engine renders with
— Java2D lays a string out at integer advances and rasterises each glyph from
its cache. Drawing the glyphs separately at those same advances is therefore the
same operation, not an approximation of it. The property that has to hold is
that per-character advances sum to the run's own width, and `blitRunUnscaled`
asserts exactly that, per run, before it will place anything. It is not
inferred once per font because it is not a property of fonts: at 1× it held for
every font tried, and under a 2× transform it fails for proportional fonts and
still holds for `Monospaced`. A run that fails it falls through to
`drawString`, which does whatever the font and the transform really require.

#### HiDPI, and why `DeviceProfile.displayScale` is not what asks

The step says the atlas must rasterise at 2× on a 2× panel or all text goes
soft, and names `displayScale` as the source. The cells do rasterise at the
destination's scale — but the scale comes from the destination's own
`FontRenderContext`, not from `displayScale`, and that is a correction rather
than a shortcut. `displayScale` describes the *default screen*; the destination
describes what the text is going onto. They differ on a second monitor, on a
window dragged between panels, and on this engine's shipping path, which
composes into an unscaled offscreen `BufferedImage` whatever the panel is doing
(`Java2DRenderer.acquireFrame`). Asking the destination is right in all of those
and never wrong where `displayScale` would have been right. A GL backend, which
has no `Graphics2D` to ask, builds the context itself — and *that* is what
`displayScale` is for.

The 2× path is exact too, and needed a different placement rule to be:
positions come from a `GlyphVector` laid out in the destination's context and
rounded into device pixels, and the blit happens with the transform temporarily
set aside so an integer device rectangle is not re-scaled on its way to the
surface. `aScaledDestinationIsExactToo` holds it at 0.00 for all six fonts.
Non-integer scales (1.25, 1.5) and any rotation or shear are refused outright:
a cell is a rectangle of pixels at an integer device position, and under those
transforms `drawString` is both correct and the only thing that is.

#### What `DrawStats` had to decide, and what it refused to decide

B5 left this as the open question: `TEXT` and `IMAGE` are treated as inherently
unmergeable, and that stops being true when they share a page. They are now one
family for batching — **but the key decides, not the kind.** A run the atlas
could not serve is still keyed by its font, and a font is not a page, so it
merges with nothing.

That distinction is the whole safeguard. The instrument credits the step exactly
where the step applies, and B5's correction is the reason to insist on it: the
plan predicted a win in two frames and measured none, and the only defence
against repeating that is a counter that cannot report a merge the backend would
not get. `RecordingTarget` — which is what the report is measured through —
therefore asks the atlas for the same key `Java2DTarget` would record. Its
metrics stay faked, deliberately: a recorded command stream has to be identical
on every machine, and a real `stringWidth` would make half the sequence
assertions in the suite depend on the host's fonts.

#### Twelve frames did not move, and the reason is the next step's

`profile-overlay` (16 batches, unchanged), `minigame-hud`, `scene-play`,
`scene-auto-battler-lobby` and `scene-board-customize` all draw plenty of text
and gained nothing. The columns say why: their text runs are separated by *flat
shapes* — label, box, label, box — so no text run is ever adjacent to another
text run or to an image, and there is nothing to merge with. An atlas merges
neighbours; these frames' neighbours are geometry.

**This is not a gap in B6 and it should not be chased here.** It is already
answered by B8's first bullet: flat shapes become two triangles against a 1×1
white texture, so they join the same batch as sprites and glyphs. Once that
lands, label-box-label is one batch as well, and these frames move without
anything in B6 changing. The frames to re-measure after B8 are named here so
that step has a prediction to be judged against — and, given B3 and B5 each got
one of these predictions wrong, judged sceptically.

#### Verified

- **Goldens unchanged at 0.00** — all 33 `GoldenFramesTest` frames and 4
  `SceneFramesTest` checks, in every one of the four routing/blitting
  combinations. `neitherSwitchChangesThePicture` walks all four and subtracts,
  because a flag that alters the picture is a bug wearing a flag's clothes.
- **Pixel parity, directly and at the bar B0 set.** `GlyphAtlasTest` compares
  the blitting path against `drawString` over the whole printable ASCII range in
  six fonts at exactly 0.00, and again for five colours including two
  translucent ones, for overhang- and kerning-prone runs, and on a 2×
  destination. Its negative control shifts one run by one pixel and insists the
  metric notices.
- **The gutter is checked on the pixels**, as B5's was and for the same reason
  Java2D can never show the bug: every one of 500+ packed glyphs is fenced by
  transparent pixels on all four sides. Glyphs are the worse case for this —
  they pack tightly and there are thousands.
- **Unservable text falls back rather than vanishing.** A right-to-left run
  needs shaping, is refused, and is still drawn — checked on the pixels, not
  just on the stats, because "refused" and "dropped" look identical in a counter.
- **One page still.** `everythingTheEngineDrawsStillFitsOnOnePage` asserts it,
  because everything above rests on it: spill onto a second page and an
  icon-label row is two binds again while every other assertion here keeps
  passing. After a full suite run the shared page holds **6,760 regions at 56%
  of one 2048×2048**, of which 5,867 are glyph cells over 307 styles. That is
  the whole suite's accumulated art — every scene in every state — so it is a
  generous upper bound on a play session, and the page grew from 2048×1024 to
  hold it, which costs 8 MB of heap and is the price of the row above.
- **Suite: 899 tests, 0 failures, 10 skipped** (was 882/0/10; the 17 new are 12
  in `GlyphAtlasTest` and 5 in `GlyphBatchingTest`). The same 10
  environment-dependent skips.
- **B5's table is unchanged**, at 620 → 580 batches and 5.43× → 5.80×, because
  `DrawCallReport` now holds glyph routing *off* through both halves of the
  sprite measurement. Both atlases pack into the same pages, so leaving it on
  moved both halves and turned B5's published number into a statement about
  whichever step ran last. One variable at a time is what lets a table published
  in one step survive being read in another.

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

#### B7 — done

`settings.gradle.kts` includes `gl`; the core stayed at the root, which was the
recommendation and is not revisited. [`gl/build.gradle.kts`](gl/build.gradle.kts)
depends on `project(":")` and on LWJGL 3.3.3 as `implementation`, with the
natives `runtimeOnly`. The core keeps LWJGL as `testImplementation` only, so
`ShaderCompileTest` and `ShaderParityTest` still run from core whether or not
`:gl` is built.

**The `lwjglNatives` detection moved rather than being copied.** The step says
to reuse the one already written in the root build, and the only way to reuse a
`val` in a Kotlin build script across two projects is to stop it being a `val`:
it is now [`gradle/lwjgl-natives.gradle.kts`](gradle/lwjgl-natives.gradle.kts),
applied by both, holding the classifier and the LWJGL version. Two copies of one
`when` would sit side by side looking identical until one of them learned about
a platform the other did not, and the failure that produces — a jar that runs
everywhere except the machine whose natives came from the stale copy — is silent
until somebody launches it.

**Two artefacts, from two projects, neither derived from the other.**

| | task | contents | size |
|---|------|----------|-----:|
| plain | `./gradlew jar` | 517 engine classes and resources, **0 LWJGL entries** | 1.5 MB |
| GL | `./gradlew :gl:glDist` | the same, plus `:gl`, LWJGL and this OS's natives | 3.7 MB |

`tasks.jar` is byte-for-byte the task it was; `glDist` is a separate `Jar` in
`:gl`. That arrangement matters more than packaging convenience: the plain jar
is not the GL one with pieces removed, so there is no build path along which a
GL dependency arrives in it by omission.

**And the zero-dependency claim is checked twice, from both sides.**

- `:verifyNoRuntimeDependencies` resolves the core's `runtimeClasspath` and
  fails if anything on it came from outside this build. `jar` depends on it, so
  the shipping artefact cannot be produced without the claim being checked, and
  `check` depends on it, so a plain `./gradlew build` fails rather than a
  release-day packaging step.
- [`ModuleBoundaryTest`](src/test/java/com/larsons/engine/render/ModuleBoundaryTest.java)
  reads the files: no runtime-scoped declaration in the root build, no core
  source naming `org.lwjgl`, and — the one a compiler would not catch —
  **no core source naming `com.larsons.engine.gl`**. That import compiles
  perfectly well; it just does not exist for a player holding the plain jar. It
  carries a negative control, in the same spirit as `SealedSeamTest`'s.

The build check sees what resolves and the test sees what is written, and
neither covers the other's blind spot. This is the same two-sided arrangement
B4 used on the Java2D seam, for the same reason: one of them alone has a gap
somebody will eventually walk through.

Both halves were watched failing before being believed. `ModuleBoundaryTest`
carries its negative control in the suite; the build check was verified by hand,
by adding `implementation("org.lwjgl:lwjgl:3.3.3")` to the root build and
confirming that `./gradlew jar` stops with *"the core is supposed to ship with
zero runtime dependencies, and its runtime classpath now carries 1"* rather than
producing a jar.

**`runGl` exists and is honest about what it currently is.** It mirrors
`runProfiled` — same flags, same report format, same instrument — and sets
`-Dlarsons.render.backend=gl`. Nothing reads that property yet, because reading
it is B9. So today the task is the GL classpath and nothing more: it launches,
and it launches Java2D. Written now so B8 had somewhere to be run from and so
B9 changes one file instead of two.

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
    sprites share one shader and one batch. **B6 named the frames this is worth
    re-measuring on**: `profile-overlay`, `minigame-hud`, `scene-play`,
    `scene-auto-battler-lobby` and `scene-board-customize` all gained nothing
    from the glyph atlas because their text runs are separated by flat shapes —
    label, box, label, box — with nothing textured adjacent to merge with. This
    bullet is what should move them. Judge it sceptically: B3 and B5 each made a
    prediction of exactly this shape and each was wrong.
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
  - `drawText`: quads from the B6 glyph atlas, whose cells are already on the
    same pages as the sprites — so a label between two icons appends to the open
    batch with no bind, which is the whole reason B6 packed into `SpriteAtlas`
    rather than beside it. The cells are colour-keyed because Java2D cannot tint
    a shared mask; GL can, so if page pressure ever becomes real this is where a
    coverage-only variant belongs.
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

#### B8 — done

Eight classes in [`gl/src/main/java/com/larsons/engine/gl`](gl/src/main/java/com/larsons/engine/gl):
`GlContext` (lifted from `GlShaderHarness`, VAO requirement and all),
`GlProgram`, `GlBatch`, `GlTextures`, `GlPaths`, `GlSurface`, `GlTarget`,
`GlRenderer`. **All 32 golden frames render on GL within the bar, and the whole
catalogue's 3,356 operations collapse into 68 draw calls.**

| | Java2D | GL |
|---|-------:|---:|
| draw calls, all 32 frames | 3,356 | **68** |
| operations per draw call | 1.00× | **49.35×** |
| worst frame, mean channel error | — | **2.59 / 255** |

The bar is **3.58**, taken from `ShaderParityTest`, where `bloom` was accepted
as "the same picture" on this same metric. Nothing was tuned to reach it: the
number was fixed before the first frame was compared, and two frames failed
against it and were fixed rather than argued with.

#### The numbers, per frame

Written by [`GlParityTest`](gl/src/test/java/com/larsons/engine/gl/GlParityTest.java)
to `build/reports/gl-parity.md` on every build, driver string included. The
reference and the GL render come from **one catalogue** — B0's, shared into
`:gl` as a published test artefact rather than reimplemented — in one process,
from the same fixed inputs. `theTwoRenderersAgreeWithTheGoldenCatalogue` holds
the local reference at exactly 0.00 against `GoldenFrames.render`, because a
comparison whose two sides can drift together is not a comparison.

| frame | error | ops | `DrawStats` predicts | GL draws |
|-------|------:|----:|---------------------:|---------:|
| `world-crowd` | 2.09 | 374 | 34 | **1** |
| `sprite-editor` | 2.12 | 1544 | 82 | **13** |
| `scene-board-customize` | 2.59 | 161 | 23 | **3** |
| `scene-skin-editor` | 2.39 | 26 | 19 | **1** |
| `world-side-scroll` | 1.03 | 145 | 4 | **1** |
| `world-isometric` | 0.55 | 153 | 4 | **4** |
| `crafting-panel` | 1.45 | 36 | 14 | **1** |
| `profile-overlay` | 0.88 | 28 | 16 | **1** |
| `scene-play` | 0.27 | 37 | 22 | **6** |
| `particles` | 0.00 | 62 | 1 | **1** |
| `main-menu`, `parallax-background`, `scene-startup`, `scene-main-menu`, `scene-level-select`, `scene-evolution-lobby` | **0.00** | | | |
| *all 32 frames* | **2.59 max** | 3356 | 488 | **68** |

Six frames are *exactly* zero, which is worth stating plainly because the step
predicted it would not happen: those frames draw only rectangles, images and
text at integer coordinates, and on that content the two rasterisers do not
merely agree closely, they agree bit for bit. The error that does exist is
antialiasing along curves and diagonals — ovals, arcs, isometric diamonds,
rounded corners — which is why the worst rows are the frames with the most
curvature in them and not the busiest ones.

#### The instruction that was incomplete: "against a 1×1 white texture"

The step says flat shapes should become "two triangles against a 1×1 white
texture, so shapes and sprites share one shader and one batch". **They share the
shader. They do not share the batch**, and the difference is the whole of what
this bullet was for: a standalone 1×1 white texture is still a different texture
from the atlas page, and binding it is still a flush. A rectangle between two
labels costs exactly what it cost before.

That was built first, and measured, and it is why the paragraph below is not a
guess:

| frame B6 named | batches before B8 | GL draws, 1×1 white texture | GL draws, white texel **on the page** |
|----------------|------------------:|----------------------------:|--------------------------------------:|
| `profile-overlay` | 16 | 16 | **1** |
| `minigame-hud` | 8 | 8 | **1** |
| `scene-auto-battler-lobby` | 17 | 17 | **1** |
| `scene-play` | 22 | 22 | **6** |
| `scene-board-customize` | 23 | 20 | **3** |
| *all 32 frames* | 488 | 454 | **68** |

The fix is four pixels of a 2,048-pixel page: `GlTarget` registers an opaque
white square into `SpriteAtlas` under the key `gl.white` and every flat shape
samples its centre texel. Shapes, sprites and glyphs then name one texture, and
label-box-label-box is one draw. The catalogue went from 454 draw calls to 68 on
that change alone, with **every frame's error unchanged** — it is a batching
change and it moved no pixels, which is the property that makes it safe.

**So B6's prediction is upheld, and it was upheld by the second implementation
rather than the first.** The plan said to judge it sceptically because B3 and B5
each made a prediction of this shape and each was wrong. The scepticism was
warranted for a reason neither of them had: the prediction was right about
*which* frames and right about *why*, and the instruction written to deliver it
would not have delivered it. Had the 1×1 version shipped, the five named frames
would have sat at their old batch counts and the honest conclusion would have
been a fourth failed prediction.

#### Two bugs the comparison caught that nothing else would have

Both drew entirely plausible pictures. Neither is visible in a screenshot unless
you already know what the frame is supposed to look like — which is what the
golden catalogue is, and why B0 was done before anything was touched.

**1. An upload binds, and the batch was still holding triangles.**
`GlTextures` uploaded on texture unit 0 — the unit the shader samples — so
uploading a new image replaced the texture the *pending* triangles were about to
be flushed against, and they came out wearing it. `parallax-background`
rendered its third layer with the fourth layer's silhouette: a perfectly
reasonable backdrop, wrong by one bind, invisible on every frame whose textures
were already resident. **4.64 → 0.00.** Uploads now happen on a unit the shader
never looks at, so the interference is impossible rather than unlikely, and
`uploadingATextureDoesNotRepaintWhatIsAlreadyInTheBatch` pins it at the
operation that causes it rather than at a whole-frame mean.

**2. Two places in the engine repaint an image in place, and a texture is a
copy.** `SpriteAtlas` packs a page lazily; `TerrainCache` rebuilds a chunk into
the image it already has, deliberately, because at a third of a megabyte per
chunk allocating a fresh one made the cache a garbage generator. Java2D blits
from the `BufferedImage` and sees every write. GL uploaded once and went on
drawing the terrain from a level three frames earlier — `scene-play` at **14.67**
against a bar of 3.58, and the picture it drew was a completely convincing
screenshot of the wrong place.

The fix is in the core, because the announcement has to come from whoever did
the writing:
[`ImageRevision`](src/main/java/com/larsons/engine/graphics/draw/ImageRevision.java)
counts repaints per image, weakly keyed by identity so a discarded chunk is not
pinned for the life of the process. `SpriteAtlas.blit` and `TerrainCache.build`
call `changed()`; `GlTextures` re-uploads when the count moves. It is free on
the draw path: a global epoch says whether *anything anywhere* was repainted, so
on the overwhelming majority of frames the per-image lookup is skipped on one
int compare. **14.67 → 0.27.**

The first version of that check watched atlas pages only, on the reasoning that
loose images are baked once and never touched again. That reasoning was written
down, was wrong, and would have stayed wrong until a player noticed the terrain
was stale — the failure mode being "the game looks fine and shows you somewhere
else".

#### Where the design departed from the step, and why

- **Curves come from `java.awt.geom`, not from a circle formula.** The step asks
  for a triangle fan "with a segment count scaled by on-screen radius".
  Flattening `Ellipse2D`, `Arc2D` and `RoundRectangle2D` through a
  `PathIterator` at a quarter-pixel tolerance does that and does it better: the
  count comes from a bound on the actual error rather than from a proxy for it,
  and the vertices are the ones Java2D would have rasterised — so the only
  difference left on a curve is antialiasing. A hand-rolled circle would have
  introduced a second difference, and the two would be indistinguishable in the
  metric.
- **Outlines are stroked, not `createStrokedShape`d.** Java2D will hand back the
  exact outline as a `Shape`, which would be shorter and geometrically perfect —
  and an annulus, which is not convex, so all 82 rounded-rectangle outlines in
  the UI would take the stencil path and flush the batch. `GlPaths.stroke`
  emits a mitred strip whose adjacent quads *share* their corner vertices, which
  is not tidiness: an outline drawn as independently-offset quads overlaps
  itself at every corner, and at anything under full alpha those corners come
  out darker than the line. That is a bug which is invisible in the opaque case
  and appears the day somebody fades a border out, so
  `strokeTrianglesDoNotOverlapEachOther` asserts total emitted area equals the
  ring's area, at three widths.
- **`fillShape` is stencil-then-cover with an even-odd rule, and non-zero paths
  are normalised through `Area` first.** The plan suggests `PathIterator` plus
  ear-clipping. Ear-clipping does not handle holes, and the one non-zero path
  this engine fills is the terrain shadow union, where the entire point is that
  overlapping shadows fill *once* instead of stacking — fed to an even-odd
  rasteriser it would punch a hole through every overlap, which is the exact
  opposite of what the call site exists for. Stencil parity in one isolated bit
  handles holes, concavity and self-intersection; `Area` makes even-odd lossless
  for the one caller that needs it. Correctness beats speed here, as the step
  says, and it costs one `Area` construction a frame.
- **A `pushClip` rectangle under a rotation becomes a shape clip.** Java2D would
  clip to the transformed parallelogram; `glScissor` cannot. Approximating it by
  the bounding box would let a scene draw slightly outside where it should,
  which reads as a painter bug for as long as it takes to find.

#### What `DrawStats` under-predicts, and why that is the right way round

`DrawStats` said 488 batches. The backend issued 68. The instrument that guided
B5 and B6 is **conservative by a factor of seven**, in two specific ways:

- It breaks a batch on every `STATE` operation. GL does not: alpha multiplies
  into the vertex colour and the transform is applied on the CPU as vertices are
  appended, so neither flushes anything. The entity phase pushes and pops both
  around every sprite, which is why `world-crowd` reads 34 and draws 1.
- It cannot merge `SHAPE` with a textured kind, because until this step no
  backend could. With the white texel on the page, GL can.

Both are worth leaving as they are. An instrument that flattered the change it
was measuring would have been useless for the decision B5 and B6 actually had to
make — "is the art arranged so a backend *could* batch it" — and the number it
reported was never a prediction of draw calls. The row that matters is now
measured on the backend rather than modelled: `GL draws` in the table above is
`glDrawArrays` calls, and Java2D's own draw-call count *is* its operation count,
so the comparison is a before-and-after and not an estimate of one.

#### Verified

- **All 32 frames within 3.58**, worst 2.59, six at exactly 0.00 — the full
  table with the driver string in `build/reports/gl-parity.md`, rewritten on
  every run including failing ones, because a failed comparison is exactly when
  the numbers are wanted. A frame over the bar also writes reference, GL and an
  8×-amplified difference as PNGs: a mean says how much and never where, and
  both bugs above were found by looking at the third image.
- **The metric has teeth.** `theMetricNoticesAFrameThatIsWrong` shifts a passing
  frame by eight per channel — far less than a moved widget, and nothing two
  rasterisers would disagree about — and requires the number to cross the bar. A
  mean over a 480×320 frame forgives a great deal, and a check nobody has
  watched fail is not a check.
- **Both backends count the same operations.**
  `bothBackendsCountTheSameOperations` compares `DrawStats` from a `Java2DTarget`
  and a `GlTarget` fed the identical command stream, per frame, and demands
  equality on operations *and* batches. Without it B10 would be comparing two
  instruments rather than two renderers, and in the flattering direction — it
  already caught the harness's own background fill being counted on one side
  only.
- **The geometry is checked without a driver.** `GlPathsTest` — 18 tests, no GL,
  milliseconds, any machine. Flattening honours its tolerance and no more;
  convexity says yes to every shape the UI fills and no to a 270° pie and an
  arrowhead; a stroke covers its ring exactly once at three widths; square, butt
  and round caps each measure what they should; a repeated point does not
  produce a NaN; a sharp corner bevels instead of spiking; and normalising a
  non-zero path makes even-odd coverage equal the union. On a machine with no
  driver the parity test skips and every one of these still runs.
- **Suite: 933 tests, 0 failures, 3 skipped** under `xvfb-run` (was 899/0/3);
  **933/0/16** with no display at all, where the six GL parity tests skip rather
  than fail, as the seven core GL tests already did. The 34 new are 24 in `:gl`
  and 10 in core (`ModuleBoundaryTest`, `ImageRevisionTest`).
- **Java2D is untouched by all of this.** Its own goldens are unchanged, and the
  two core changes B8 required — `ImageRevision` and the two calls to it — add a
  map increment to two paths that were already doing a full re-render.

#### What is not here, and where it lands

`GlRenderer` exists, implements `Renderer`, and **nothing constructs it**.
Choosing a backend is B9: probing for a context, falling back to
`Java2DRenderer` and saying why, honouring `-Dlarsons.render.backend`, and
reporting the choice in the frame profile. That step also has to decide how a
GLFW window coexists with the AWT `GameWindow` the engine opens today — two
windowing systems, and the answer is a decision rather than a detail, which is
why it is not smuggled in here.

A `ShaderChain` attached to `GlRenderer` is **not run**, and it says so once on
stderr rather than dropping the passes quietly — a renderer that silently
ignores post-processing looks exactly like one whose post-processing has no
effect. Running the CPU chain instead would mean reading the frame back and
uploading it again, which is the two-transfers-per-frame arrangement §1 rejects
Job-A-before-Job-B for in the first place. `GlSurface.resolvedTexture()` is
already there for A1 to render into.

**And one number B10 should go looking for rather than be surprised by: a
changed image is re-uploaded whole.** Over a full catalogue run the backend
uploaded 80.7 MB across 81 uploads of 57 textures — most of that being the
2,048-square atlas page going up again each time a new glyph was packed into it.
In a real session that settles, because a UI runs out of new glyphs within
seconds. What does *not* settle is `TerrainCache`: a level with liquids in it
rebuilds chunks continuously, up to four a frame at about a third of a megabyte
each, and each rebuild is now a full re-upload of that chunk. That is roughly
80 MB/s of bus traffic at 60 fps — not obviously fatal, and not obviously fine
either. The fix if it matters is a dirty rectangle on `ImageRevision` and
`glTexSubImage2D` instead of `glTexImage2D`. It is deliberately **not** written
yet, because invariant 5 says nothing merges that cannot be measured, and the
machine that can measure it is the one B10 runs on rather than a software
rasteriser in CI.

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

#### B9 — done

Five classes in the core, four in `:gl`, and one file with one line in it that
is the entire coupling between them. **Both backends launch the real game,
render, profile and exit; `auto` picks GL where a driver answers and Java2D
everywhere else; and every report says which one drew it.**

The step's own instruction — "if `:gl` is on the classpath" — was the part that
needed a design rather than an implementation, because the core is forbidden
from finding out. `ModuleBoundaryTest.noCoreSourceNamesTheGlModule` reads every
file under `src/main/java` and fails the build on the *string*
`com.larsons.engine.gl`, comments included. So the core cannot import the
backend, cannot reflect on it by name, and cannot mention it. What it can do is
declare the shape of one:

| New in the core | What it is |
|---|---|
| [`RendererFactory`](src/main/java/com/larsons/engine/graphics/RendererFactory.java) | The `ServiceLoader` service a backend registers as, plus the `Request` (size, title, background) it is built from |
| [`Backend`](src/main/java/com/larsons/engine/graphics/Backend.java) | What a factory returns: a renderer and its window, or the reason there is neither |
| [`BackendWindow`](src/main/java/com/larsons/engine/graphics/BackendWindow.java) | A window a backend brings with it — show, size, pump, close, and where input goes |
| [`BackendChoice`](src/main/java/com/larsons/engine/graphics/BackendChoice.java) | The decision and its one-sentence reason, which goes into `DeviceProfile` rather than only into a log |
| [`Backends`](src/main/java/com/larsons/engine/graphics/Backends.java) | The selection itself, and the classpath scan |

and look for implementations on the classpath it was launched with. The plain
jar finds nothing; `:gl:glDist` contains
`META-INF/services/com.larsons.engine.graphics.RendererFactory` with one line in
it and is found. Both jars hold the same engine classes. **Reflection on a class
name would have worked too and would have put the module's name back in the core
as a string literal — the same mistake with an extra step, and one the boundary
test would have caught anyway.**

#### The decision the step said was a decision: two windowing systems

The plan flagged this rather than smuggling it in, so here it is stated:
**whichever backend is chosen owns the only window.** When GL is selected, no
`JFrame` is constructed at all; when it is not, no GLFW window is. They are
never both alive, so there is no second event queue, no second focus owner and
no question about which window a keystroke belongs to.

That forced three follow-on choices, each of which is a correctness requirement
rather than a preference:

- **Events on the engine's thread, GL on the loop's.** GLFW requires its window
  and event functions on the thread that created the window — on macOS a
  platform rule, not a convention. The game loop has always had a thread of its
  own. So `Engine.start()` now blocks, pumping `glfwPollEvents` on the caller's
  thread while the loop renders on its own; `GlContext` gained
  `makeCurrent`/`detachCurrent` that bind and release LWJGL's per-thread
  function pointers along with the context, and the creating thread hands the
  context over once it has read the driver strings.
- **`GlTarget` is built on the first frame, not in the constructor.** It
  allocates a program, a vertex buffer and textures, and the constructor runs on
  the wrong thread for that. Objects created against a context that is current
  somewhere else belong to nothing — and, being GL, say nothing about it.
- **The renderer never asks the window its size.** `GlWindow` pushes logical
  size and device scale into volatile fields from GLFW's own resize callbacks,
  and `GlRenderer` reads those. A renderer calling `glfwGetFramebufferSize` each
  frame would be correct on Linux, mostly correct on Windows, and wrong on the
  machine B10 profiles on.

#### Input, and why `InputManager` grew seven methods

A GL window that renders and cannot be played is not a backend, it is a
screenshot. GLFW events had to reach the same `InputManager` the AWT canvas
feeds, and the obvious route — synthesise `java.awt.event.KeyEvent`s — needs an
AWT `Component` to name as their source, which is the one thing a backend with
no AWT window does not have.

So the manager gained `pressKey`, `releaseKey`, `typeChar`, `pressMouse`,
`releaseMouse`, `moveMouse` and `scroll`, and **its AWT listeners now call
exactly those**. One latching implementation — the part that is genuinely easy
to get wrong, and that carries three paragraphs of javadoc explaining why —
serves both window systems, and a scene cannot tell which it is being played in.

[`GlKeys`](gl/src/main/java/com/larsons/engine/gl/GlKeys.java) is the
translation. Two notes worth keeping:

- **AWT key codes are the target, deliberately.** Every key bind the engine has
  ever saved to disk is a `KeyEvent` constant. A neutral enum would have been
  the tidier design in 2024 and is a migration of every saved bind today, for a
  backend that has to agree with the other one regardless. Binds saved before
  this backend existed work on it.
- **The ASCII coincidence is not relied on.** GLFW numbers printable keys by
  their unshifted ASCII value and so does AWT, so `GLFW_KEY_A` and `VK_A` are
  both 65 — but apostrophe, backquote, backspace and enter are not, and the
  middle and right mouse buttons are swapped between the two. A table that
  passed through anything it did not recognise would map those to nothing. Every
  mapping is written out; anything unlisted reports `VK_UNDEFINED` rather than a
  guess.

#### The negative control, and a flag that had to exist for it

"Simulate failure by requesting an impossible context version" is now
`-Dlarsons.render.gl.version=<major>.<minor>`, read by `GlRendererFactory`.
Asking for 9.9 takes the same path a laptop with no GPU takes — context creation
returns nothing, the factory reports the driver's own words, the engine picks
Java2D — but takes it **on a machine where GL otherwise works**, which is the
only place "fell back" and "was never offered anything" can be told apart. It is
also a real field knob: a player whose driver misbehaves at 3.3 can be asked to
try something else without a new build.

`-Dlarsons.run.seconds=<n>` is the other new flag, and it is what makes "confirm
both run" a command rather than a person watching a window. It quits the game
after *n* seconds whatever else is happening — distinct from
`larsons.profile.seconds`, which stops measuring and leaves the game up.

#### Verified

**From the shipping jars, not from a Gradle task.** Both artefacts, launched the
way a player launches them, under `xvfb-run` on a software rasteriser. The two
jars hold the same engine classes; only the classpath differs.

| Launch | What it printed |
|---|---|
| `java -jar Larsons-2D-Game-Engine-0.1.0.jar` | `backend: java2d — no GPU backend on the classpath` |
| `java -jar larsons-engine-gl.jar` | `backend: gl (Mesa / llvmpipe … / 4.5 (Core Profile) …) — probed and selected automatically` |
| plain jar, `-Dlarsons.render.backend=gl` | `backend: java2d — -Dlarsons.render.backend=gl names a backend that is not on the classpath (none is)` |
| GL jar, `-Dlarsons.render.gl.version=9.9` | `backend: java2d — no usable GPU context — gl: no GL 9.9 core context` |

That is the "Done when" clause, line by line: `auto` picks GL where available
and Java2D everywhere else, a flag that cannot be honoured says so instead of
being ignored, and the provoked failure falls back carrying the driver's own
words. All four runs then played the startup scene and exited on
`-Dlarsons.run.seconds`.

Same thing through the Gradle tasks, with the profiler armed, so the two
renderers can be compared on one machine:

```
./gradlew run       -Dlarsons.render.backend=java2d -Dlarsons.run.seconds=8 …
./gradlew :gl:runGl                                 -Dlarsons.run.seconds=8 …
```

| | Java2D | GL |
|---|---:|---:|
| backend line in the report | `java2d` | `gl` |
| gpu line | *(absent, correctly)* | `Mesa / llvmpipe (LLVM 20.1.2, 256 bits) / 4.5 (Core Profile) Mesa 25.2.8` |
| frames in a 5 s window | 550 | 548 |
| scene | 1.591 ms | **0.631 ms** |
| present | 4.438 ms | 6.690 ms |
| exit | clean | clean |

**These are not B10's numbers and must not be read as any.** It is a menu scene
at 1280×720 on `llvmpipe`, a CPU rasteriser pretending to be a GPU: the scene
stage falling to 40% is the batching doing something real, and the present stage
rising is llvmpipe's swap, which on hardware is a different number entirely.
What this table proves is the only thing B9 claims — both backends run the whole
game, and each report says which one it was.

The suite went **933 / 0 / 3 → 959 / 0 / 3** under `xvfb-run` and
**933 / 0 / 16 → 959 / 0 / 17** with no display at all. The one new skip is the
frame that needs a driver; the other eight GL-side tests — discovery, the
provoked failure, the key and button translation — run everywhere.

Tests: [`BackendSelectionTest`](src/test/java/com/larsons/engine/render/BackendSelectionTest.java)
takes the factories as an argument, so every route through the decision —
including the ones that only happen on a machine whose GPU is broken — runs on a
build agent with no GPU at all. [`GlBackendTest`](gl/src/test/java/com/larsons/engine/gl/GlBackendTest.java)
covers what stubs cannot: that the services file really is found, that the
impossible-version fallback really engages, and that the backend the engine
selected really puts red pixels in the window it brought — read back out of the
default framebuffer, two samples, one inside the rectangle and one outside it,
because a readback of a uniformly red screen would pass just as well if the
clear had been red and nothing else had happened.

#### Three bugs the verification caught, all invisible to the tests

None would have been found by anything short of launching the game, which is
why B9's "Verify" says to launch it.

1. **The window was closed twice.** `GlRenderer.dispose()` closed its window and
   `Engine.shutdown()` closed it again. GLFW's `init`/`terminate` is
   process-wide and reference-counted, so the second close tore the library down
   under whatever was still holding it and printed five `GLFW_NOT_INITIALIZED`
   stack traces on every exit. Fixed by ownership rather than by a guard: the
   window owns the context, the renderer owns the vertex buffers and textures,
   and `dispose()` releases only the second. `GlRenderer.create()` — a static
   helper that made both and left each believing it owned the window — was
   deleted rather than repaired. The idempotence guard went in as well, because
   the failure mode of getting this wrong again is a segfault rather than an
   exception.
2. **`-XstartOnFirstThread` was applied by OS rather than by backend.**
   `runGl` set it on any Mac, and the task can run either renderer. GLFW needs
   thread 0 on macOS or a GL window hangs — but *AWT wants the same thread for
   its own run loop*, so the flag on a Java2D run hangs the `JFrame` instead. It
   now follows the backend, in one function the three launch tasks share. Found
   while splitting B10's runs into two tasks, before it could waste an afternoon
   on the one machine that has to be profiled.
3. **`-P` defaults silently beat `-D` flags.** Gradle applies
   `tasks.withType<JavaExec>().configureEach` *before* a task's own
   configuration block, so `runProfiled` and `runGl` setting
   `larsons.profile.seconds` from their `-Pprofile.seconds` default of 30
   overwrote the `-Dlarsons.profile.seconds=5` on the command line. The symptom
   was a profiling run that produced no report and looked like the profiler was
   broken. Both tasks now read the `-P`, then the `-D`, then their default.

#### What the README may now say, and what it still may not

The step's last instruction was to stop overstating "CPU fallback" and to fix
the wording **in the commit that makes it true, not before**. Half of it is now
true and half is not, and the README says so in those terms:

- **True as of this step:** there is a real probe, and a real fallback, for
  *scene rendering*. `Backends` asks for a GL 3.3 core context and uses Java2D
  when it does not get one, saying which and why.
- **Still not true:** *post-processing* does not run on the GPU. `GlRenderer`
  prints to stderr, once, that an attached `ShaderChain` is not being executed,
  and the CPU chain remains the only implementation. That is Job A, and the
  README now describes the shader system as what it is — a multithreaded CPU
  pipeline that ships hand-written GLSL alongside each effect as a verified port
  target.

`STEAM_PLAN.md`'s Appendix B flagged both. The renderer half of its objection is
now answered in the document; the shader half is left standing, because it is
still correct.

#### What is not here

- **macOS is reasoned about, not measured.** `:gl:runGl` passes
  `-XstartOnFirstThread` when the host is a Mac, because the JVM's main thread is
  not thread 0 without it and a GLFW window created off thread 0 does not fail
  there — it hangs. The thread split above exists precisely so that rule can be
  honoured. Whether AWT and GLFW coexist quietly enough in one process on macOS
  for the game's font and image loading is a question only the M1 Air answers,
  and it answers it in B10.
- **The GL backend still does not run the shader chain.** Job A. It says so once
  on stderr rather than dropping the passes quietly.
- **No pixel comparison of the two *windows*.** `GlParityTest` compares the two
  targets over 32 frames and B9 adds a framebuffer readback through the selected
  backend; nobody has photographed both windows side by side. The parity metric
  is the stronger instrument and it already exists.

---

### B8a — The bug 32 golden frames could not see

**Found by playing the game, on the first real level anyone put through the GL
backend.** Reported as "text looks jumbled and entities have lines coming out of
them". Both symptoms, one cause.

`GlBatch` grew its vertex buffer when a batch reached 4,096 vertices. **4,096 is
not a multiple of three.** The check is `count == capacity` and `count` rises one
vertex at a time, so the growth happened at vertex 4,096 — one vertex into a
triangle. `glDrawArrays(GL_TRIANGLES, 0, 4096)` does not refuse that: it draws
1,365 whole triangles and silently ignores the leftover vertex. The two vertices
still to come were then written at the head of the reallocated buffer, and from
that moment **every triangle in the frame was assembled from vertices two places
apart** — the corners of different sprites and different glyphs. Long thin
triangles between entities; glyph quads built from three different letters.

The class had claimed the opposite in its own javadoc for three steps: "the
buffer holds a multiple of three and callers never straddle it". The second half
was true — every emitter appends three vertices with nothing between them. The
first half was false for 4,096, 8,192, 16,384, 32,768 and 65,536, which is every
capacity it ever used.

**Why 32 golden frames and a 2.59/255 parity number all passed.** The catalogue's
busiest frame issues 374 operations, and its frames change texture often enough
that no single batch ever reached 4,096 vertices. A real level issues 2,942
operations that merge into a handful of batches, so it crossed the boundary
inside the first frame and corrupted every frame after it. **The catalogue tested
the backend's vocabulary exhaustively and its volume not at all** — every verb,
every shape, every text path, at a scale nothing in the game runs at. That is the
general lesson worth keeping: a parity catalogue built from small deterministic
frames answers "does each operation draw the right thing" and cannot answer "does
the backend survive a real frame".

**The fix** is `GlBatch.wholeTriangles`, which rounds any capacity down to a whole
number of triangles and is used by the constructor, by every growth and by the
ceiling. The invariant is now enforced by the code that depends on it rather than
by five literals that all had to stay right. `flush()` additionally drops a
partial triangle and says so once on stderr, so a future violation costs one
missing shape instead of a ruined frame.

**Verified by reproduction.** [`GlBatchTest`](gl/src/test/java/com/larsons/engine/gl/GlBatchTest.java)
draws 4,800 rectangles in one batch — 28,800 vertices, crossing the growth
boundary three times — each in a colour derived from its own index, then reads
the surface back and checks every one. Run against the unfixed code it fails at
**rectangle 682**, which is vertex 4,092, and every rectangle after it: the
predicted index, arrived at from the arithmetic before the test was written. A
second case does the same with several thousand glyph quads and asserts no ink
lands below the last row of text, which is where a shifted stream throws
triangles. Both are pixel tests; the capacity arithmetic is also checked as a
pure function, so the cheapest half of this runs on a build agent with no GPU.

Suite: **959 → 964**, still 0 failures.

---

### B10 — Re-profile and decide

**Goal.** Answer, with numbers, whether Job B delivered.

**Do.**
- Run the 30-second profile on both target machines: the Ryzen 7 / RTX 4080
  Super and the M1 Air. Same level, same activity, both backends, all four runs.
  B9 made each run a task that takes no arguments — in IntelliJ, two entries in
  the run dropdown:

  ```bash
  ./gradlew :gl:profileGl        # → profile-gl.txt
  ./gradlew :gl:profileJava2d    # → profile-java2d.txt
  ```

  Walk into the level, press F3, play for 30 seconds. Each report names its own
  backend and driver at the head, so the four cannot be mixed up afterwards.

  **Both tasks live in `:gl`, including the Java2D one.** Putting the Java2D run
  in the root build would have given it a classpath with no LWJGL on it, making
  it a different program — and then any difference between the two reports could
  be the backend or could be the classpath. One jar, one variable, two reports.
  The same reasoning applies to the level and the activity: profile the same
  place doing the same things, or the comparison measures the play session.
- Compare against Appendix C.
- Publish the table here, including any stage that got *worse*. `present` on a
  software rasteriser rose under GL in B9's launch check; whether that is
  llvmpipe or the backend is a question only hardware answers, and it is this
  step's to answer.

**The bar.** On the M1 Air, the scene stage is 11.49 ms of a 16.67 ms budget. If
GL does not cut that substantially, something in B5/B6/B8 is not batching and
the merge ratio will say which. Do not proceed to Job A on a backend that has
not proven itself, because Job A's entire economic case is that the frame is
already a GPU texture.

**Done when.** The numbers are recorded and the decision to continue is made
from them.

#### B10 — done. The M1 Air, four runs, and Job B delivered

**`PlayScene`, 1280×720, 600-frame windows, 60 FPS cap, same level and same
activity.** Mac OS X 26.3.1, M1, 8 cores, Java 21.0.9, on a 1440×900 panel at
**scale 2.0** — so every logical pixel is four real ones. Two rounds of both
backends: the first on `81c08d5`, the second on `44bca9b` after B8a fixed the
vertex buffer. Four runs, because two would not have shown which numbers are
reproducible and which are not.

| | Java2D r1 | Java2D r2 | GL r1 | GL r2 |
|---|---:|---:|---:|---:|
| **work per frame** | 16.826 | 12.133 | **6.944** | **6.667** |
| headroom (of 16.67 ms) | −1.0% | +27.2% | **+58.3%** | **+60.0%** |
| sustainable FPS | 59 | 82 | 144 | 150 |
| **scene** | **9.768** | **9.418** | **3.920** | **3.653** |
| — terrain | 4.899 | 4.653 | 2.217 | 1.890 |
| — entities | 3.934 | 3.858 | 1.503 | 1.502 |
| — hud | 0.386 | 0.369 | 0.069 | 0.069 |
| present | 5.061 | 0.962 | 1.105 | 1.053 |
| update | 1.997 | 1.753 | 1.918 | 1.961 |

**The verdict is the scene stage, and it is the most reproducible number in the
table.** Java2D 9.77 and 9.42; GL 3.92 and 3.65. Under 7% spread within each
backend and a **61% cut** between them, holding across a code change that
rewrote how the GL backend assembles triangles. B10 set the bar as "if GL does
not cut that substantially, something in B5/B6/B8 is not batching". It did, and
every component moved with it: terrain −60%, entities −62%, hud −82%.

Frame work fell from 12.1–16.8 ms to a stable **6.7 ms**, and headroom went from
somewhere between just-over-budget and 27% to a steady **58–60%**. The Air holds
60 FPS on the GL backend with more than half the budget spare.

`update` is unchanged at ~1.9 ms on all four runs, which is the control: nothing
about a renderer should move the fixed-step simulation, and nothing did.

#### A correction: `present` is not a Job B win

The first round showed Java2D at 5.061 ms of present against GL's 1.105, and
that was written up here as a 78% saving with an explanation attached — that
Java2D was paying to blit a 1280×720 image onto a 2× surface. **The second round
contradicts it.** Java2D's present came back at 0.962 ms, p50 0.913 against the
first round's p50 of 5.377, and B8a touched nothing Java2D uses. In that run GL's
present (1.053) is marginally the *slower* of the two.

So the honest record is: **Java2D's present cost is not reproducible on this
machine — it varied by a factor of five between two runs of the same code — and
no claim about present survives.** GL's is stable at ~1.05 ms across both rounds.
The explanation offered for the first reading was plausible, which is exactly why
it should not have been offered from one sample. Something outside the profiler
moves that stage; whatever it is, it is not the renderer, and finding it is not
Job B's business.

Nothing else in the table depends on it. The scene stage is what B10 was set up
to decide and it is stable to within 7%.

#### The decision, and the scope it was made under

**Job B delivered. Job A is unblocked but not yet justified.**

- **The Ryzen 7 / RTX 4080 Super was deliberately skipped**, by decision rather
  than by omission. B10 as written called for both machines; the bar it stated
  was in terms of the Air, because the Air was the machine over budget and the
  desktop already had headroom to spare. A second machine could have shown the
  win to be larger and could not have shown it to be absent. The plan of record
  is therefore closed on one machine, and this sentence is here so nobody later
  reads the missing column as an oversight.
- **These profiles cannot decide Job A, and their own verdict text should not be
  read as if they could.** Every one of the four says `0 shader pass(es)` in its
  context line: the post-processing chain was switched off entirely. A profile
  with no shader passes in it measures nothing about the cost of shader passes,
  so "neither GPU job is justified by these numbers" is true of these numbers and
  says nothing about Job A. Deciding Job A needs a profile of the same level with
  the chain **on** — and on a 2× panel, where a full-screen CPU pass covers four
  times the pixels its logical size implies, that is exactly the measurement most
  likely to change the answer.

#### Two instrument defects this round exposed

Neither affects the numbers above; both would mislead the next person to read a
report, so they are recorded rather than left to be rediscovered.

1. **`merge ratio 2.2×` is not this backend's batching.** The `Draw calls` block
   is `DrawStats` — the *model*, computed in the core with no backend present, of
   what a batching backend could achieve given the draw order. B8 measured that
   model to be seven times pessimistic against what GL really issues (488
   predicted, 68 actual). The report has no channel for a backend's real
   `glDrawArrays` count, so on a GL run it prints a Java2D-shaped prediction and
   then advises, in the verdict, that "a GPU backend would issue nearly one call
   per sprite and buy little" — advice about a decision already made and already
   contradicted by measurement. `GlTarget.drawCalls()` has the true number and
   nothing carries it into `FrameProfiler`.
2. **The verdict does not know which backend it is judging.** It recommends Job B
   to a report produced *by* the Job B backend. `DeviceProfile.backend()` is
   right there in the same report, and `FrameReport.verdict` never reads it.

---

### B11 — The GL jar could not open a window on the machine it was profiled on

**Found by asking how a player would launch it, rather than how we had been
launching it.** Every GL run through B10 went through `:gl:runGl`, which passes
`-XstartOnFirstThread` on macOS. The shipping artefact is a jar, a manifest
cannot carry JVM arguments, and GLFW must create its window on thread 0 — which
the JVM does not run `main` on unless told. A GL window created off it **does not
fail, it hangs**: no window, no error, nothing in the log.

So `./gradlew :gl:runGl` worked and `java -jar larsons-engine-gl.jar` did not, on
the one platform the backend had been measured on, for the whole of B7–B10.

**The flag cannot just be set for everyone.** AWT wants thread 0 for its own run
loop, so a Java2D launch *with* the flag hangs the `JFrame` exactly as a GL
launch without it hangs the GLFW window. It is a per-backend setting, and the
backend is not known until something has probed for a driver — which needs the
thread.

[`MacGlLauncher`](src/main/java/com/larsons/engine/core/MacGlLauncher.java)
breaks the circle with a second process rather than a second attempt:

1. The original process — no flag, so AWT would work — sees macOS, a GPU backend
   on the classpath, and a request that is not `java2d`. It spawns itself with
   the flag, inherits the console, and waits.
2. The child probes. If it gets a context it runs the game and the parent exits
   with the child's status: one window, one game, one idle `waitFor`.
3. If it cannot, the child exits `86` **before touching AWT**, and the parent
   runs the game itself — in a process that never had the flag, where Swing is
   perfectly happy.

Falling back inside the child would mean opening a Swing window on a thread AWT
does not own, which is this bug pointing the other way.

**Inert for everyone else**, which is the property most worth testing:
non-macOS, `-Dlarsons.render.backend=java2d`, a classpath with no GPU backend on
it (the plain jar — the common case), an already-flagged launch, and the child
itself all skip it.
[`MacGlLauncherTest`](src/test/java/com/larsons/engine/render/MacGlLauncherTest.java)
pins each, faking `os.name` so the macOS branch is reachable from a Linux agent
— which proves the guard that stops a plain jar relaunching is the classpath
scan and not merely the platform check. A spawn that fails for any reason logs
and continues on Java2D; nothing here can strand a player.

**Still to confirm on the hardware:** that a double-clicked
`larsons-engine-gl.jar` now opens a GL window on the M1 Air. Both jars were
verified to still launch correctly on Linux, where the whole mechanism is a
no-op.

---

## 5. Job A — GPU post-processing

Precondition: **B10 complete and passed.** The frame must already live in a GPU
texture, or this job costs two transfers per frame to save work that is not the
bottleneck. **Met — see B10.**

### 5.0 Measured, and now justified

B10's four runs all had the shader chain switched off, which is why they closed
Job B and were explicitly not allowed to decide this one. A fifth and sixth run,
same machine, same level, **2 passes on** (`lighting` + `bloom`):

| | Java2D, shaders **off** | Java2D, shaders **on** | GL, shaders **on** |
|---|---:|---:|---:|
| scene | 9.418 | 9.839 | 3.641 |
| **shaders** | 0.000 | **5.460** | **0.000 — not run** |
| — lighting | | 2.894 | |
| — bloom | | 2.563 | |
| present | 0.962 | 1.091 | 1.103 |
| **work per frame** | 12.133 | **18.762** | 6.745 |
| **headroom** | +27.2% | **−12.6%** | +59.5% |
| sustainable FPS | 82 | **53** | 148 |

**Job A is justified, and the number is 5.460 ms.** Turning two passes on costs
29% of the frame and takes the Java2D renderer from inside its budget to 12.6%
over it — 82 sustainable FPS down to 53. The HiDPI note in the report is not
decoration: at `scale 2.0` a full-screen pass covers four times the pixels the
window's logical size implies, so this is the stage that punishes exactly the
machine this plan cares about. That is the budget A2 and A4 compete for.

**And the GL column is not a comparison — it is a bug report.** `shaders 0.000`
against a context line saying `2 shader pass(es)`: the GL backend was handed a
chain and did not run it, which is what `GlRenderer` has said on stderr since B8
and what §4's B8 notes recorded as Job A's business. **The 6.745 ms frame is a
frame with no lighting and no bloom in it.** It cannot be set against 18.762 and
nothing here does.

### 5.1 What that means before A1 starts, and it is not a performance question

`LightingPass` is not post-processing in the cosmetic sense. It is **day/night
darkness and every point light in the world** — the thing that makes a torch a
torch. A player on the GL distribution who enables lighting in their game type
gets a uniformly lit world and no error they will ever see, because a single
line on stderr is not a user interface. **The GPU build silently has no
day/night.**

That is a correctness defect, not a missing optimisation, and it outranks
everything else in this job. A1–A5 fix it properly. Until they land, the engine
owes the player one of:

- **say so** — surface "post-processing is not available on this backend" where
  the toggle lives, not on stderr; or
- **honour it** — when a chain has passes and the chosen backend cannot run
  them, select Java2D instead and say why, which is exactly the machinery B9
  already built and would cost a condition; or
- **do neither and finish A quickly.**

The third is only honest if A really is next. Whichever is chosen, it is a
decision to record here rather than a detail — the same rule that governed the
window question in B9.

### A1 — Render the scene to a texture, not the backbuffer

**Do.** `GlRenderer.beginFrame()` binds an offscreen FBO sized to the drawable.
`present()` currently swaps; it now becomes: run the chain, then blit the result
to the backbuffer and swap.

**Verify.** Frame is unchanged with an empty chain. Goldens green.

---

#### A1 — done

`GlRenderer` owns a `GlSurface` sized to the drawable, binds it in
`beginFrame()`, and blits it to the back buffer in `present()`.
`sceneTexture()` exposes the resolved texture, which is what A2 samples. The
existing `GlSurface` needed one new method — `blitToWindow` — because a
`glBlitFramebuffer` straight from the multisample buffer to the back buffer is
one driver-side resolve and needs no shader, no vertex buffer and no state of
its own, which matters when it runs after `GlTarget` has finished a frame.

**And it binds the surface only when something will read it, which the step did
not say and the measurement demanded.** The offscreen path costs one multisample
resolve per frame. On a GPU that is a hardware blit; on a software rasteriser it
is four samples × 921,600 pixels of CPU work, and it measured at **14–19 ms a
frame under llvmpipe** — enough to take a playable software-GL configuration (a
VM, a remote desktop, an old integrated driver) and make it unplayable. Since the
backend does not yet run the chain, an unconditional surface would have bought
nothing and cost that.

So `-Dlarsons.render.offscreen` defaults to `auto`: offscreen when a chain with
passes is attached — precisely when A2 needs the texture — and straight at the
window otherwise. Measured under llvmpipe, on the real game:

| | present | work/frame |
|---|---:|---:|
| `auto`, no passes (the pre-A1 path) | 5.875 ms | 6.483 ms |
| `always` (forced offscreen) | 20.731 ms | 22.059 ms |

`always` and `never` force it either way, so the difference can be measured on a
given machine rather than taken from this note. **On the M1 this is expected to
be a fraction of a millisecond and that is expected, not measured** — the number
above is a software rasteriser and says nothing about hardware.

Two things were ruled out on the way to that conclusion rather than assumed: it
is not vsync (the cost is identical with the swap interval at 0 and 1), and it is
not a multisample-to-multisample blit (the window is now requested single-sample,
since coverage sampling belongs to the offscreen surface, and the cost did not
move).

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

## 7. Job D — Sub-pixel stability on HiDPI

**Precondition: A and C are done, or abandoned.** Deliberately last, and §7.0
says why.

**The symptom.** Reported from a real level on the M1 Air: *"the blocks seem to
jitter slightly on GL."* Terrain shimmers by about a pixel as the camera moves.
Java2D on the same machine, same level, same camera does not.

### 7.0 Why this is next, and not after A

**This section previously argued for doing Job D last.** The argument was that
A1 binds an offscreen framebuffer, that a framebuffer sized in logical pixels
would remove the cause for free, and that building a bespoke fix first risks two
implementations of one thing.

**The premise was right and the conclusion was wrong.** If a logical-size
framebuffer is what fixes the shimmer, then *that framebuffer is the fix* — and
it is also A1's first deliverable. Building it now does not duplicate A1's work,
it **front-loads** it: D1 stands the offscreen surface up and presents through
it, and A1 then has only to hang the shader chain off a surface that already
exists. The two jobs share a step; they do not compete for it.

So the ordering is: **D now, A next, C after.** What that changes about D1 is not
whether to build the framebuffer but that it must be built as the thing A1 will
inherit — sized, formatted and owned the way A1 needs — rather than as a
throwaway. That constraint is written into D1 below.

The reason this is worth doing before A is simpler than the sequencing argument:
a shimmering world is a defect a player sees every second they play, and GPU
post-processing is an optimisation of a stage that currently costs the GL backend
nothing at all.

**What still genuinely belongs after C:** any fix that snaps geometry to a pixel
grid. Snapping is correct for an unrotated world and wrong for a yawed one, so
if D0's measurement points there instead, that half waits for C and this section
gets rewritten again.

### 7.1 The hypothesis, and why it was wrong

**Stated so it could be wrong, and it was.** The reasoning was: Java2D composes
into a logical-size image and blits once, so every sprite lands on a whole
logical pixel; GL rasterises straight at device resolution, so a sprite at
logical `x = 100.37` lands at device `x = 200.74`, the texel grid sits at a
fractional offset from the pixel grid, and that offset slides as the camera
moves.

**The premise is false.** `Camera.screenX` ends in `(int) Math.round(...)`, and
`DrawTarget.drawImage` takes `int x, int y`. The scene cannot hand the backend a
fractional position — there is no API for it. Every sprite arrives on a whole
logical pixel, which at scale 2 is an even device pixel. There was never a
fractional offset for anything to slide on.

Worth recording rather than quietly deleting: the hypothesis was plausible, it
was written down before the measurement, and one grep at the `Camera` end would
have killed it in a minute. **D0 was still worth building, because what it found
instead is the thing nobody had checked.**

### D0 — done, and the rasteriser is exonerated

**Every parity measurement in this project had been taken at scale 1**, and the
machine the shimmer was seen on runs at scale 2. `GlParityTest` renders the
golden catalogue on a 1× surface against a 1× Java2D reference; it had never
seen the configuration the player is in.

[`GlHiDpiParityTest`](gl/src/test/java/com/larsons/engine/gl/GlHiDpiParityTest.java)
renders a tile wall through GL at scale 2 and compares it against the Java2D
reference **upscaled 2× nearest — as the window server presents it** — across a
sweep of camera positions:

| what | result |
|---|---|
| tiles only, mean channel error, every camera position | **0.000** |
| shapes and text, mean channel error | 2.666, **identical at every position** |
| pixels changed by a one-pixel pan, GL | 8,160 |
| pixels changed by a one-pixel pan, Java2D upscaled | **8,160** |

Not "within the 3.58 bar" — **exactly equal**, at every offset. A one-pixel pan
disturbs precisely the same pixels on both backends. The 2.666 on the mixed
frame is GL rendering shapes and glyphs at device resolution while Java2D
renders them at logical resolution and the panel doubles them; it is constant,
so it is a static difference in sharpness, not a temporal one.

**The GL rasteriser does not shimmer.** Whatever was seen, it is not the picture
being drawn differently from frame to frame.

*(The first run of this diagnostic reported 1.594 rather than 0.000, entirely
because its clear colour was a constant that disagreed with what `GoldenFrames`
clears to — the exact trap `GlParityTest` has a comment about. It now discovers
the colour instead of assuming it.)*

### D1 — done. What is left is presentation, and B9 caused it

If the picture is identical and the motion is identical, the difference is in
how the frame reaches the panel. B9 set the swap interval to **zero**, with a
comment saying the engine's frame limiter should be the only thing pacing
frames — right for making B10's two profiles comparable, wrong for a player. A
frame presented at an arbitrary phase against a 60 Hz panel tears, and the
apparent motion of a large regular pattern — a wall of blocks — judders. Java2D
never had this because on macOS it presents through the window server, which
composites on the refresh whether asked to or not.

**Vsync is now on by default**, `-Dlarsons.render.vsync=off` for an uncapped
benchmark. It changes what a profile means, and that is written into
`GlWindow`'s javadoc: with vsync on, `swapBuffers` blocks, so the wait moves out
of the limiter's `idle` stage and into `present`. **A profile taken with it on is
not comparable to B10's without saying so.**

**This one is a candidate, not a measurement, and is not claimed as more.** D0's
numbers are hard; this is the remaining explanation with a cheap fix attached,
and it needs eyes on the Air to close. If the shimmer survives it, the next
suspect is not in this plan at all: `update` spikes to 15–17 ms at p95 on both
backends, which is one frame in twenty missing its deadline — visible judder by
any other name, and the subject of [`SIM_PLAN.md`](SIM_PLAN.md).

### D2 — done

`GlHiDpiParityTest` is a standing test rather than a diagnostic: the tiles number
must stay at exactly 0.000, the shapes-and-text number must not move with the
camera, and a one-pixel pan must disturb the same count on both backends. It is
the first parity instrument in this project that runs at a display scale other
than 1 — the gap that let this question stay open for four steps.

---

## 8. What each instrument proves

Six instruments, six distinct questions. Using the wrong one is how a step gets
declared finished while broken.

| Instrument | Question it answers | Where it lives |
|-----------|---------------------|----------------|
| **Golden frames** | Does the player see the same picture? | B0, new |
| **`SealedSeamTest`** | Can the migration silently un-happen? | `render/SealedSeamTest.java`, B4 |
| **`RecordingTarget`** | Did the code issue the draw calls it was supposed to, in order? | `graphics/draw/RecordingTarget.java` |
| **`DrawStats.mergeRatio()`** | Will a GPU backend help, or is every operation its own batch? | `graphics/draw/DrawStats.java` |
| **`DrawCallReport`** | What did a batching change actually buy, frame by frame? | `render/DrawCallReport.java`, B5 — writes `build/reports/draw-calls.md` |
| **`FrameProfiler` / `FrameReport`** | Where does the frame actually go? | `profile/` |
| **`ShaderParityTest` metric** | Do two implementations of the same effect agree? | `ShaderParityTest`, reused by A2 and B8 |
| **`GlParityTest`** | Does the GPU backend draw the same picture, and how many draw calls does it really issue? | `gl/…/GlParityTest.java`, B8 — writes `build/reports/gl-parity.md`, and PNGs for any frame over the bar |
| **`ModuleBoundaryTest` + `:verifyNoRuntimeDependencies`** | Can the core quietly acquire a runtime dependency? | `render/ModuleBoundaryTest.java` and the root build, B7 |
| **`BackendSelectionTest` + `GlBackendTest`** | Does the right renderer get picked, and does the wrong answer still leave a playable game? | `render/BackendSelectionTest.java` (every route, no GPU needed) and `gl/…/GlBackendTest.java` (the classpath, the driver, the provoked failure), B9 |
| **`GlBatchTest`** | Does the backend survive a frame bigger than its buffers? The catalogue answers *vocabulary*; this answers *volume*, and the two are different questions — B8a shipped because only the first was being asked | `gl/…/GlBatchTest.java`, B8a |

**`DrawStats` and `GlParityTest` answer different questions and B8 measured the
gap.** `DrawStats` models what a batching backend *could* merge given the draw
order — which is the question B5 and B6 had to answer about the art, before any
backend existed. `GlParityTest` counts what one *did*. The model came in
seven times pessimistic (488 against 68), because it breaks a batch on every
state change and GL breaks on almost none. Neither number was wrong; they were
never the same number.

`RecordingTarget` earned its keep through B2 and B3 and is the instrument B5
will need next: it asserts the *sequence* of commands, which catches a
reordering that goldens would miss when the reordered draws do not happen to
overlap in the test scene — and then break in a real level where they do. An
atlas that changes draw order without meaning to is exactly that failure.

---

## 9. Order of record

```
B0  golden frames                      ← done
B1  widen DrawTarget (7 new members + 2 audits)  ← done
B2  port 12 shared painters/widgets    ← done
B3  port 18 scenes, graphicsOf 39 → 0  ← done
B4  seal the seam (delete graphicsOf, Renderer returns DrawTarget)  ← done
B5  sprite atlas                       ← done
B6  glyph atlas                        ← done
B7  :gl Gradle module                  ← done
B8  GlTarget + GlRenderer              ← done (2.59/255 worst, 3356 ops → 68 draws)
B9  backend selection + fallback       ← done (ServiceLoader SPI; GLFW owns the
                                              window when GL wins; both launch)
B8a fix GlBatch growing mid-triangle   ← done (found by playing, not by testing)
B10 re-profile, decide                 ← done. M1 Air, 4 runs, 2 builds: scene
                                         9.77/9.42 → 3.92/3.65 ms (−61%), work
                                         → 6.7 ms, headroom → +58-60%.
                                         JOB B DELIVERED. Desktop skipped by
                                         decision — the Air was the machine over
                                         budget and therefore the one that decides.
B11 macOS first-thread relaunch        ← done. The GL jar could not open a window
                                         when double-clicked; only the Gradle
                                         tasks passed -XstartOnFirstThread.
      │
      ├─ D0  measure the HiDPI shimmer  ← done. GL at 2x is PIXEL-IDENTICAL
      │        to Java2D upscaled, at every camera position. The rasteriser
      │        does not shimmer; §7.1's hypothesis was wrong.
      │  D1  vsync on by default        ← done. B9 had turned it off. The
      │        remaining candidate, awaiting eyes on the Air.
      │  D2  GlHiDpiParityTest          ← done. First parity test at scale 2.
      │
      ├─ A1  scene renders to a texture ← done. Offscreen when a chain has
      │        passes; straight at the window otherwise, because the resolve
      │        costs 14-19 ms/frame on a software rasteriser and buys nothing
      │        until A2. JUSTIFIED by 5.460 ms/frame of CPU shaders at 2x
      │        HiDPI, and the GL backend runs NEITHER pass today — a GPU
      │        build has no day/night at all. See §5.0-5.1.
      │  A2  GlShaderChain ping-pong    ← START HERE
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

D is first because a shimmering world is a defect the player sees every second
and GPU post-processing optimises a stage that currently costs the GL backend
nothing. D1 builds the offscreen surface A1 then hangs the shader chain off, so
the two share a step rather than competing for it — see §7.0.

**The simulation stall is not in this plan.** `update` spikes to 15–21 ms at p99
against a 0.6–0.8 ms median, identically on both backends, and no renderer work
will touch it. It now has a plan of its own: **[`SIM_PLAN.md`](SIM_PLAN.md)**,
whose first step is making the update stage as legible as the scene stage
already is.

A and C are independent of each other once B10 passes. A is much smaller and its
risk is already retired, so it is the natural next one — but nothing forces that
order.

---

## Appendix A — Migration surface, measured

Originally counted from `src/main/java` on 2026-08-02 at commit `85196b9`; the
middle column was re-taken after B3, the right-hand one after B4.

| Measure | Before B2 | After B3 | After B4 |
|---------|----------:|---------:|---------:|
| `Java2DTarget.graphicsOf` call sites | 39 (+1 definition) | 0 (+1 definition) | **0** (no definition) |
| Files naming `Graphics2D` **in code** | 48 | 26 | **13** |
| — of those, outside `graphics/` | — | 3 | **3** (all bakes) |
| `Graphics2D` compatibility overloads | — | 4 | **0** |
| `drawString` sites | 350 | 1 | **1** |
| `DrawTarget` abstract members | 26 | 29 | **29** |
| `DrawTarget` `Color`/convenience overloads | 0 | 23 | **23** |

**"In code" is doing real work in that table and did not before.** The B3
figure of 26 counted any file containing the string, which folds together a
file that draws with Graphics2D and a file whose javadoc explains that it no
longer does. Once the second kind is the majority, the number stops measuring
anything: `Engine`, `UiText`, `Scene`, `FrameProfiler`, `Particles`,
`DeckGameScene` and `SurfaceDecorPainter` name it only in prose. Stripping
comments and string literals — which is what `SealedSeamTest` does before it
scans — gives **13**, and the drop from 26 is mostly the change in
instrument, not in code. Comparing them as if they were the same measurement
would be flattering the work.

The 13 are ten in `com.larsons.engine.graphics` (`Java2DTarget`,
`Java2DRenderer`, `AssetLoader`, `Skins`, `PlayerSprites`,
`DirectionalSprites`, `EntitySprites`, `ParallaxBackground`, `CutscenePainter`,
`TerrainCache`) and the three bakes outside it (`CreativeScene`,
`CharacterPicker`, `AutoSprites`). `Renderer` and `TilePainter` left the list
in B4; `DrawTarget`, `TerrainPainter`, `SceneManager` and `DecorPainter` never
belonged on it, and now the instrument agrees.

**All three of B4's stated clean-ups were measured first and held.** The dead
imports in `SceneManager`, `DecorPainter` and `SurfaceDecorPainter` were
unused, and four more dead `Java2DTarget` imports turned up beside them.
`TilePainter.drawTexture(Graphics2D, …)`, `TerrainPainter.draw(Graphics2D, …)`
and `drawMiningCracks(Graphics2D, …)` had no `src/main` callers and four in
tests, so B4 deleted an overload nobody depended on rather than a migration
path someone might.

---

## Appendix B — Baseline to beat

**M1 Air, `frameprofile8`, 30 s sample, side-scroller with active play.**

| Stage | ms/frame | Note |
|-------|----------|------|
| **Total work** | **17.12** | Budget is 16.67 at 60 Hz — over by 0.45 |
| scene | 11.49 | the target of Job B |
| — terrain | 6.38 | already cached; this is the uncached remainder |
| — entities | 3.85 | the target of B5 |
| — hud | 0.37 | was the target of B6 — which found there is no CPU time here to win, only draw calls; see B6 |
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
As of B9: **959 / 0 / 17** with no display at all, **959 / 0 / 3** under
`xvfb-run`.

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
| Core finds the GL backend by reflecting on a class name | **No** | Puts the module's name back in the core as a string literal, which `ModuleBoundaryTest` forbids and rightly. A `ServiceLoader` service moves the name into the module that owns it (B9). |
| An AWT window and a GLFW window coexist | **No** | Two event queues, two focus owners, two ideas of where the mouse is. Whichever backend is chosen owns the only window (B9). |
| Synthesise AWT events for GLFW input | **No** | Needs a `Component` to name as their source, which is exactly what a non-AWT backend lacks. `InputManager` took an injection API instead and its AWT listeners now call it too (B9). |
| Neutral key codes instead of AWT's `VK_` constants | **No** | Every bind ever saved to disk is a `KeyEvent` constant. Translating GLFW at the edge costs one table; migrating the save format costs every player's controls (B9). |
