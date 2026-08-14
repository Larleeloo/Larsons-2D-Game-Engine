# Render Plan — GPU Acceleration, End to End

**Status:** Living document. Written 2026-08-02 against commit `85196b9` on
`claude/gpu-acceleration-shaders-oqbx54`. **Jobs A, B, C and D are all
complete.** Job C closed with C10: the camera turns to eight compass points,
everything the projection touches turns with it, and two players may look at one
world from different directions — which is correct behaviour, and is now
asserted over a real socket. Job A closed 2026-08-04: the shipped GLSL runs on
the GPU, matching the CPU chain pass for pass, and the GL backend has lighting
again (§5, A2–A6).

**Job B delivered.**
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

**Job A is complete.** It was justified by a profile with the chain *on* that
put the CPU shader stage at **5.460 ms** — 29% of the frame, and enough to take
the Java2D renderer 12.6% over budget at 53 FPS — and, more seriously, showed
the GL backend running **neither** pass: a GPU build had no day/night lighting
at all, a correctness defect rather than a missing optimisation. §5.0 and §5.1.
That is closed. `GlShaderChain` compiles each pass's shipped GLSL once and runs
the chain as a framebuffer ping-pong over the texture A1 left behind, and it
reproduces §2's per-pass parity table **to the hundredth** — which is what those
numbers were measured before a backend existed for. A2–A6.

**Two of the job's own instructions were incomplete, and the second was a
shipped defect rather than an inefficiency.** A3 says lifting the harness's
`bindUniforms` is enough and that a location of −1 would be a backend bug; in
fact three of `LightingPass`'s uniforms could not be advertised at all — a
`Map<String, Float>` cannot carry a `vec3` — and a fourth was being bound with
`glUniform1f` against an `int`, which the driver refuses while raising an error
nobody reads. Either one alone renders a plausible night with no torches in it.
See A3.

**Job D (§7) went first, ahead of A, and is now closed — by three fixes and four
hypotheses, because "the shimmer" was never one defect.** D0 exonerated both
rasterisers, D1 turned vsync on and fixed a real but different problem, and the
shimmer survived both. **D3 found half of it in `Camera`**: the projection
rounded `(world − camera) × zoom` in one step, so every object crossed its
rounding boundary at its own moment and neighbouring blocks slid against each
other — on both backends. **D4 found the other half in the GL sampler**: at a
fractional zoom, texel boundaries land exactly on device pixel centres and
`GL_NEAREST` then decides from the last bits of an interpolated float, which
move when the quad does. Java2D disturbed 0 pixels a step; GL disturbed up to
169, every one inside a tile and none at a tile edge.

**No test in the project could see either**, and for two different reasons worth
keeping: D3 was invisible because everything here renders at `zoom = 1` or `2`,
the only zooms at which the old arithmetic is exactly right; D4 was invisible
because the one instrument aimed at it swept the camera in whole pixels through
integer arithmetic of its own. §7's D3 and D4.

**A crash report closed two more defects this plan had not predicted.** Dragging
the window's edge on the Air terminated the process — AppKit reallocates the GL
drawable on thread 0 while the render thread is blitting into it (§10) — and the
same log showed AWT's `[NSApplication run]` sitting under `glfwPollEvents`, which
is why the window's close button did nothing (§10.3).

**And one shipped defect was found by a player's description alone.** *"Shaders
don't line up unless you're on the same vertical level as a block"* is a
vertical mirror, and it was: the lighting pass read a texture coordinate as a
screen row. The parity harness could not fail on it, because it uploaded and
read back with two flips that cancelled. §5's A7.

**Job C has started, and its first step found that its own verification cannot
fail on the bug it was written for.** C1 put a heading on the camera and the
rotation into all three projection paths; the round-trip property this plan
specified as the proof passed a mirrored rotation, an unturned camera and a
half-turned one, because an inverse derived from the same matrix inverts a wrong
matrix exactly as happily as a right one. What does fail is a statement about
direction rather than about information. C1 also fixed a second defect on the
way past: `TerrainCache` solved for a bake focus in projected space and assigned
it to a world coordinate, which was the same number until the camera could turn.
C2 then found the accessor it was written to add already there under another
name — and the obvious rule about it false, because a torch standing on a path
is two blocks tall and you walk straight through it. §6.

**C3's precondition measurement rewrote its own step, and C4's turned out to be
half-written already.** The floor cache was excluded from isometric for an
antialiasing artefact, and C3 was told to find out whether rotation brings that
artefact to top-down. It does — and the measurement says the rule was never
about the format at all: **a floor is cacheable when the projection puts a
tile's edges on a screen axis**, which top-down loses when it turns and
*isometric gains at 45°*. The cache came out wider than it went in. C4 then
found the depth order it was sent to fix already correct, because the sort key
was never the world row index the step assumed; what it did find was a proxy —
`!iso` — quietly deciding two unrelated things, one of which would have blitted
ground textures unrotated under a turned camera.

**C6 came out with no diff at all, and the reason generalises.** Shadows, decor
and liquids all turn already, because each was written when the isometric
projection arrived — and a diamond is a projection that does not let a
screen-space assumption survive. A camera that turns is a second such
projection, so code the first one made honest was already honest for the
second; the same sentence explains C3's visible bounds and C4's depth order.
**C7 is where the work was**: the heading now rides the input command, because
the keys are a screen intent and the camera that gives them meaning is
per-client state the server is never sent — which makes the determinism
boundary structural rather than careful, and which turned up a turned editor
panning diagonally on the way past.

**C8 finally added a control rather than turning something that already
existed, and both of its findings are about the check rather than the code.**
The keys the step recommended — `Q` and `E` — are both already bound, to
dropping an item and to interacting. Worse, `KeyBinds.conflicts` would have said
they were fine: it reports collisions inside a category, and rotation lives in
`CAMERA` while dropping lives in `ITEMS`, so by its rule the two are different
contexts. They are the same frame of the same game. The negative control passed
until the test stopped asking `conflicts` and started asking what the player can
press at one moment. **C9 then needed nothing for placement** — creative mode
already resolves every click through the camera's inverse — and what it did need
was a sharper claim than the round trip: an editor resolves a pixel to a whole
cell, so the middle of every cell must resolve back to itself.

**Five times in this job a negative control found a defect the entire existing
suite could not see**: a cache key no test ever turned, a branch nothing could
reach, a conversion no scene would have called, a heading every physics test set
for itself, and a key collision the project's own conflict check is built not to
report. §6.

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
| **The shipped GLSL executes on the GPU, and draws what the CPU draws** | A2 — `GlShaderChainTest` runs every pass through the production chain against `ShaderChain` and reproduces §2's own table to the hundredth: five at **0.00**, `scanlines` 0.04, `vignette` 0.32, `grayscale` 0.47, `bloom` 3.58 |
| A GPU build has day/night lighting again | A4 — `LightingPass` runs as GLSL, its arrays bound as arrays; parity **1.27** on a fixed light set, and a separate test asserts the frame is *lit* rather than merely darkened, which is what an unbound light array looks like |
| The uniform contract can be honoured to the letter and still be wrong | A3 — three of `LightingPass`'s uniforms were declared, sampled and advertised to nobody; `ShaderPass.vectorUniforms()` closes the hole and `ShaderCompileTest` now scans both directions instead of one |
| A pass's cost on a GPU is not what the submitting thread's clock says | A2 — `Stage.SHADERS` and the per-pass split come from `GL_TIME_ELAPSED` queries, collected a frame later; `FrameProfiler.recordElapsed` exists for measurements that are not wall time |
| The chain measures in the same pixels the CPU chain does | A2 — `uResolution` is the logical frame size, checked at 2× against the Java2D frame upscaled, with the device-units mistake as the negative control |
| Both backends run the whole game, not a test harness | B9 — `-Dlarsons.run.seconds` launches the real game on each and exits; both wrote a report under `xvfb-run`, `gl` at 0.631 ms scene against `java2d` at 1.591 ms on a software rasteriser |
| **The camera turns, and all three projection paths turn with it** | C1 — `CameraYawTest` runs every case at all eight headings in both rotating formats: the round trip, the tile path against the picking path, the pixel round trip, and D3's rigid sheet **under rotation**, which `Camera`'s note predicted and could not check |
| A projection can invert perfectly and still be mirrored | C1 — the round trip this plan specified as C1's verification passes a sign-flipped rotation, an unturned one, and a half-turned one. An inverse derived from the same matrix inverts a wrong matrix as happily as a right one; what fails is "at heading *h*, world direction *h* projects the way north projects at 0" |
| The height axis exists and is not the walkability axis | C2 — `heightAt` is `Level.stackHeight`, asserted 0/1/2-and-never-3 in every format. A torch on a path is height **2** and still walkable, so C4's face visibility and collision read different questions of the same cell |
| **A baked floor is cacheable when its tile edges land on a screen axis, whatever format asked** | C3 — measured: 0.001–0.055% of the frame at every heading the rule allows, 0.51–0.70% at every heading it refuses. Top-down loses the property when it turns and **isometric gains it at 45°**, so the cache covers four headings in either format and none in between. `theCacheabilityRuleMatchesTheSeamItIsAbout` re-measures it every build |
| A cache can be right about every chunk and wrong about where the floor is | C3 — the single offset every chunk is placed from was the camera's *world* focus, not its projected one: 81.5% of the frame wrong at a quarter turn, and identical to the old arithmetic until C1 |
| Which faces of a block the camera sees is a question the projected corners already answer | C4 — a two-dimensional back-face cull replaces "the diamond's two lower edges, or the southern one", reproduces both to the pixel, and gives every heading in between for nothing |
| A sprite's direction is world state; the sheet drawn for it is not | C5 — `Facing` stays a networked world direction and `Facing.asSeenFrom(viewYaw)` is where it becomes a picture. 64 cases checked against `planarDelta`, not against the same index arithmetic |
| Shadows, decor and liquids already turn, and the isometric projection is why | C6 — all three were written against `planarDelta` or against projected world vectors when the diamond arrived, and a diamond is a projection that does not let a screen-space assumption survive. Verified at eight headings; no diff |
| A rotated camera pans an editor diagonally | C7 — `CreativeScene`'s pan keys moved `camera.x/y` along the world's axes. Found by anchoring the input scan on *every* movement-key input rather than the first, which is the draft that passed |
| **The heading is part of what the player pressed, not of where they are now** | C7 — it rides `PlayerInput` over the wire, so prediction and authority step the same rotation bit-for-bit through 240 ticks of a camera turning under a running player. The server has no camera to ask, and C10 says it never will |
| The camera never rests between compass points | C8 — the last frame of a snap assigns the heading from a whole-number index rather than easing until it is close enough. A control that stops within 0.001% of the target fails six of the step's nine tests, because 44.99° costs the floor cache, the upright tile blit and the exact axis swap for as long as the camera sits there |
| **A key-bind conflict check can be blind to the collision that matters** | C8 — `KeyBinds.conflicts` reports inside a `Category`, so rotation on `Q` (CAMERA) against dropping an item on `Q` (ITEMS) is not a conflict by its rule, and both fire in the same frame. The plan's own suggested keys were caught only after the test stopped asking `conflicts` and started asking what is live at once |
| A level remembers the heading it was built from, and older levels do not gain one | C9 — `heading`, an integer 0–7, absent when zero. The shipped level is asserted unchanged byte-for-byte on disk and free of the field after a round trip; writing it unconditionally fails three tests by name |
| **Two players may look different ways at one world, and do** | C10 — a real server, two real clients on a socket: one looking north and one east both hold "up", and the server walks one north and the other east in the same tick. Forty ticks later their descriptions of the same tick are identical field for field |
| The simulation cannot acquire a camera by accident | C10 — the seven packages that simulate, serve, serialise and store the world are scanned for the class name, prose excluded. The audit is a scan because an audit is worth a day |
| **An unexercised path is where the defect is, four times in one job** | C3's cache key survived all 28 cache tests (none turns a warm camera); C4's winding measurement survived every test in the suite (nothing mirrors a quad, so the branch was unreachable); C5's conversion would have survived all 64 of its own cases while no scene called it; C7's heading would have survived all of `PlayerPhysicsTest`, which sets it itself |

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
4. **The suite stays green.** Last full run, under `xvfb-run`: **1,038 tests,
   0 failures, 3 skipped** in core plus **62/0/0** in `:gl` (was 810/0/3 when
   this was written; B0–B11, D0–D7, A1–A7 and C1–C10 added the rest, twelve of
   them in Job A and fifty-nine in Job C). The three skips are the display
   tests losing a race with the eleven classes that set
   `java.awt.headless=true` in the shared JVM (see B4); with no display at all,
   17 stand aside instead — fourteen of those need a GL driver, seven in core
   and seven in `:gl`. Skipping rather than failing on a missing environment is
   by design. A step ends with that or better.

   **These figures are counted from `build/test-results`, and the 984 recorded
   here at A5 does not reproduce against that count** — the same count read 979
   immediately before C1 touched anything. Rather than carry a number nothing
   reproduces, the number here is the counted one and the method is stated with
   it, which is the rule B10 arrived at the hard way: a figure that cannot be
   re-derived is not a result.
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

> **The GL column's `0.000 — not run` is fixed as of A2.** The chain executes
> on the GPU now, so a future run of this table has three columns that can
> honestly be set against each other. **This one still does not** — the 6.745 ms
> frame in it was measured with no lighting and no bloom in it, and no amount of
> later work makes that measurement mean something it did not mean. It is left
> exactly as it was recorded, as the bug report it was.

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

> **Resolved 2026-08-04: the third, and A really was next.** A2–A5 landed in one
> pass, so neither the warning nor the forced fallback was ever built — both
> would have been code written to describe a defect that was about to stop
> existing. The GL backend runs `LightingPass` as GLSL and a GPU build has
> day/night again.
>
> **What that option cost while it was open is exactly one day**, and the
> alternative reading is worth stating: had A been *deferred* after this
> section was written, "do neither" would have been the wrong choice and the
> plan would have had to come back and take one of the first two. The third
> option is only ever a bet on the next step, and it is honest only in
> retrospect.

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

#### A2 — done

[`GlShaderChain`](gl/src/main/java/com/larsons/engine/gl/GlShaderChain.java):
two textures the size of the drawable, one program per pass compiled on first
sight and kept, one fullscreen triangle per pass, no vertex buffer.
`GlRenderer.present()` resolves the surface, runs the chain and blits the
result instead of the scene.

**The verification came out exactly as this step predicted, which is the
result.** Every pass, both ways, through the shipping chain rather than the
harness:

| pass | mean channel error | §2 says |
|------|-------------------:|--------:|
| `pixelate`, `wave`, `chromatic_aberration`, `color_grade`, `invert` | **0.00** | 0.00 |
| `scanlines` | **0.04** | 0.04 |
| `vignette` | **0.32** | 0.32 |
| `grayscale` | **0.47** | 0.47 |
| `bloom` | **3.58** | 3.58 |

Not close to §2's table — *identical to it*. That is what the numbers were
taken before the backend existed for: they are a property of the shaders, so
reproducing them to the hundredth is a statement about the backend and nothing
else. `build/reports/gl-shader-chain.md` is written on every run.

**Three things the step listed as risks, and what each turned into.**

- **Order.** Held, and tested with a control that immediately caught the
  test's own first draft. The pair chosen to prove ordering mattered was
  grayscale-then-invert against invert-then-grayscale — which *commute*, because
  Rec. 709's weights sum to one and `luma(1 − c) = 1 − luma(c)` exactly. The two
  orders agreed to 0.00 and the control said so on the first run. It is
  `vignette` and `invert` now, which genuinely do not commute.
- **Resize.** Tested by changing both axes mid-run, for the reason the step
  gives: a stale ping-pong does not crash or blank, it stretches, and "the game
  looks a bit soft" is the hardest report to act on.
- **Compile once.** Per pass, by identity, on first use rather than at
  chain-set time — the chain is replaced atomically by a settings menu while
  the render thread is mid-frame (`ShaderChain.setPasses`), so "at chain-set
  time" would mean compiling on whatever thread the menu is on, against a
  context that belongs to another. The uniform *locations* are cached with the
  program for the same reason they should be: `glGetUniformLocation` is a string
  lookup in the driver and the answer cannot change.

**And one thing the step did not raise, which turned out to be the decision the
whole class rests on: `uResolution` is bound at the frame's *logical* size, not
the texture's.** Every pass that touches that uniform uses it as "the pixel
space this frame's coordinates are measured in" — `pixelate` divides a block
size by it, `bloom` turns a radius in pixels into a UV offset with it,
`scanlines` counts rows with it, `lighting` multiplies by it to recover where a
light is. Java2D composes at logical size, so all of those are logical
quantities on the CPU side. Binding the device size — the obvious reading of
"framebuffer size in pixels" — would at 2× HiDPI halve every scanline's
thickness, quarter each pixelate block's area, halve bloom's radius and put
every light at half its position: four wrong pictures, on the machine this plan
exists for, none of which looks like a units bug.

That is now measured rather than argued. `theChainMeasuresInLogicalPixelsSoHiDpiLooksTheSame`
shades at 2× and compares against the logical CPU frame upscaled nearest-neighbour,
the way D0 compares — `scanlines` 0.04, `pixelate` 0.00, `lighting` 1.62 — and
carries the negative control that matters here more than usual: the same run
with device units passed as the measuring unit has to fail the comparison, or
three errors near zero would only prove the metric was blind.

**GPU work is timed on the GPU.** `Stage.SHADERS` and the per-pass breakdown
come from `GL_TIME_ELAPSED` queries collected a frame later, not from
`System.nanoTime` around the draw call. A GL call returns as soon as the command
is queued, so the wall clock would have charged the frame tens of microseconds
for milliseconds of shading — and §5.0 has already had to correct one reading of
`shaders 0.000` that meant "not run". A number that is wrong by two orders of
magnitude *in the flattering direction* is worse than no number. `FrameProfiler`
gained `recordElapsed` / `recordPassElapsed` for measurements that are not the
caller's wall clock; `record(stage, start)` is now one line on top of it.

The first version of that recycled a query object while it was still in flight,
which is `GL_INVALID_OPERATION` and shows up as a pass quietly missing from the
report rather than as an error. Queries that have not landed stay pending and
are asked again next frame.

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

#### A3 — done

Lifted as the step said, into `GlShaderChain.bindUniforms`, and verified by
`strengthZeroLeavesTheFrameAloneThroughTheProductionChain` — the same check
`ShaderParityTest` runs, pointed at the shipping chain, with the same two
geometric passes excluded for the same reason (strength scales their
displacement, not their opacity). `aPassSpecificScalarReachesItsShader` covers
the other half: `uPixelSize` unbound quantises to blocks of zero, which GLSL
clamps to one — an identity pass that looks exactly like a working chain doing
nothing.

**The step's premise was wrong in a way worth recording, and it is the bug this
job existed to find.** A3 says a location of −1 "is a backend bug rather than a
shader bug", because `ShaderCompileTest.everyExtraUniformAPassAdvertisesExistsInItsShader`
guarantees no advertised uniform is missing from its shader. True — and it
guarantees nothing about the opposite direction, which is where the defect was.
`ShaderPass.uniforms()` returns `Map<String, Float>`, and a `Float` cannot carry
a `vec3`, so `LightingPass`'s `uNightTint`, `uLightPos[]` and `uLightColor[]`
were **declared in the shader, sampled by the shader, and advertised to nobody**.
A backend honouring the documented contract to the letter — which is precisely
what "lift `bindUniforms`" produces — shades every dark pixel toward
`vec3(0)` and lights nothing.

So the contract gained the member it was short of, `ShaderPass.vectorUniforms()`,
returning a `Vector(components, values)` that carries its own component count
because six floats are two `vec3`s or three `vec2`s and nothing in the values
says which. And `ShaderCompileTest` now scans **both** directions: every
advertised name must exist in the shader, and every `u`-prefixed uniform the
shader declares must be advertised to backends. The second check is the one that
had never existed and the one that was failing.

**A second, quieter type error in the same pass.** `uLightCount` *is*
advertised — through the `Float` map, because that is the only map there was —
and the shader declares it `int`. `glUniform1f` against an integer uniform is
`GL_INVALID_OPERATION`: the driver refuses the call, raises an error nobody is
reading, and leaves the uniform at its default of zero. A lighting pass that
compiles, links, runs, costs what lighting costs, darkens the frame correctly
and lights nothing. `GlShaderHarness` has had this bug since it was written and
it never showed, because `ShaderParityTest` only ever ran the nine built-ins and
not one of them has an integer uniform.

The production chain therefore asks the program what type each uniform is —
`glGetActiveUniform` once at link time, cached beside the location — and binds
accordingly. That is four extra lines and it is the difference between the
uniform contract being a description and being a contract.

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

#### A4 — done

**Parity on a fixed light set: 1.27 out of 255**, and it is held to the ordinary
3.0 bar rather than a looser one. The two sides are not the same algorithm —
the CPU computes the light field at quarter resolution and upsamples it
bilinearly (the original side-scroller's trick for keeping cost flat in the
light count) while the GPU evaluates the falloff per fragment, because on a GPU
that is the cheaper of the two — so a difference was expected and a looser bar
was written first. Measuring it made that bar an unforced concession. A bar set
above what the code achieves is a bar that lets it get worse in silence.

The arrays bind as arrays through A3's new `vectorUniforms()`, so there is no
lighting-shaped special case in the uniform path; see A3 for why that member had
to exist at all, which is the substance of this step.

**Two tests, because "matches the CPU" and "is lit" are different claims.**
`theFrameIsActuallyLitRatherThanMerelyDarkened` asserts the middle of the frame
is brighter than the corner. That sounds trivial and is the test that would have
caught the shipped defect: an unbound light array renders a *uniformly darkened
frame with no torches in it*, which has no error anywhere to point at and looks
entirely like night.

**The clamp: honoured, provoked, and narrower than the step imagined.** The
budget comes from `GL_MAX_FRAGMENT_UNIFORM_COMPONENTS`, two `vec3`s per light
charged at four components each because a driver may pad, with 64 held back for
everything else. GL 3.3 guarantees 1,024 components and 32 lights need a quarter
of that, so **on any conforming driver the clamp never engages** — which makes it
code a player's driver would be the first ever to execute. So it is forced
reachable with `-Dlarsons.render.gl.maxlights`, the same trick B9 uses to
provoke its fallback by asking for a GL version no driver has, and
`moreLightsThanTheDriverAffordsStillRenders` runs it at a budget of 4 with 12
lights on a machine that could hold all 32.

That test checks both halves, because they are separate mistakes: the *shader*
has to be compiled with arrays the driver can declare (`LightingPass.glsl(int)`
takes the size), and the *loop bound* has to stop inside them. Getting the first
right and the second wrong reads past the end of a uniform array — undefined
behaviour, which renders perfectly on the driver you tested it on.

**One deviation from the step, stated rather than quietly taken.** A4 says the
clamp should keep "the nearest N". It keeps the **first N**, which is the rule
the CPU path has always applied at `MAX_LIGHTS` — `addLight` silently ignores
anything past the limit. Picking the nearest would be a nicer rule and would
make the two backends disagree about which lights are lit, and invariant 3 says
pixel parity is the contract. A rule that is better on one backend and different
on the other is worse than a rule that is merely adequate on both. Changing it
is a change to `LightingPass`, where both paths would get it.

---

### A5 — Keep the CPU chain, and keep testing it

**Do.** `ShaderChain` stays and stays tested. It is what runs under
`-Dlarsons.render.backend=java2d`, in headless tests, and on any machine the GL
path rejects. The parity tests are what keep the two honest, and they only work
while both exist.

**Verify.** Full suite on both backends.

---

#### A5 — done

`ShaderChain` is untouched and every test it had still runs. It is not a legacy
path: it is what runs under `-Dlarsons.render.backend=java2d`, in headless CI,
on a machine whose driver the probe rejects, and — as of A2 — it is the
reference the GPU chain is measured against on every build. The two keep each
other honest and only work while both exist, which is exactly what this step
says.

**Full suite, both backends, under `xvfb-run`: 984 tests, 0 failures, 3
skipped.** Twelve are new here — `GlShaderChainTest` (10), `GlBackendTest`'s
end-to-end chain check, and `ShaderCompileTest`'s reverse-direction scan. The
three skips are the
same display-dependent `FrameProfilerTest` cases B4 traced to eleven classes
setting `java.awt.headless=true` in a shared JVM — not this step's, and not new.

**A5 also bought a test the plan did not ask for, and it is the one that
matters.** `GlShaderChainTest` drives `GlShaderChain` over a texture it uploads
itself, which means it would pass unchanged against a renderer that never called
the chain, that called it with the multisample buffer unresolved, or that ran it
and then presented the *unshaded* surface anyway. All three draw a plausible
frame. So `GlBackendTest.aChainAttachedToTheRendererRunsOverTheSceneItDrew`
attaches a chain the way `Engine` attaches one, draws a red rectangle, presents,
and asks what colour the rectangle came out — cyan, through an `invert` pass,
along with the inverted clear colour, which is what says the whole frame went
through the chain rather than just the geometry. It deliberately does not force
A1's offscreen property, because "offscreen when a chain has passes" is the
condition A2 made load-bearing: get it wrong and there is no scene texture to
post-process at all.

---

### A6 — Correct the documentation, once it is true

**Do.** Update the README rows listed in `STEAM_PLAN.md` Appendix B, and mark
Appendix A item 3 complete. Update `Renderer`'s javadoc, which currently
describes the GPU backend as hypothetical.

---

#### A6 — done

`Renderer`'s javadoc no longer describes GPU shading in the conditional: both
backends execute, and the paragraph names the instrument that holds them
together rather than asserting they agree. `setShaderChain` and `setProfiler`
say the same in miniature, the latter now pointing at `recordElapsed` for a
backend whose work does not happen on the calling thread.

`STEAM_PLAN.md` Appendix A: item 3 is marked done — and its estimate held, which
is worth recording as loudly as a miss would have been. "What remains is the
plumbing… `GlShaderHarness` already does it in about two hundred lines. That
harness is the shape of the backend" was written before any of this and is
exactly what happened. The appendix gains a **fourth** consequence, because A3
found one the trace had missed: the uniform contract was a member short, and a
backend honouring it to the letter would have shipped a lighting pass that lit
nothing.

**README, four places** — three of them corrections of *understatement*, which
is a first for this document and is recorded in Appendix B as such:

| Where | Was | Now |
|-------|-----|-----|
| Intro | "a multithreaded CPU post-processing pipeline that ships hand-written GLSL" | GLSL-first post-processing that runs as real fragment shaders on the GL backend and as the CPU pipeline everywhere else |
| Requirement #5 row | "**Post-processing executes on the CPU today** … the GL backend says so on stderr" | Both sides execute; cites `GlShaderChainTest` reproducing the per-pass errors |
| Rendering backends | (nothing about post-processing) | The chain is a ping-pong over the scene texture, lighting included, timed with GPU queries |
| "Rendering backend & shaders" | "What remains is the GL backend itself" | Stale since B8/B9. Now says what remains is Jobs C and D |

The fourth was not A6's to fix and was fixed anyway, on B4's precedent: a
sentence that says the main remaining work is a thing finished two steps ago
argues against work already done, which is worse than saying nothing.

**Appendix B gains the rule it was missing.** Every row in it until now was an
overstatement walked back. Three of today's are the opposite — text that was
scrupulously accurate on 2026-08-03 and too modest by 2026-08-04. A README that
undersells has the same defect as one that oversells: it has stopped describing
the program. That is why "correct the documentation" is a numbered step with a
precondition rather than a habit, and it is why the precondition is *once it is
true* in both directions.

### A7 — done. The lighting pass was mirrored, and the parity test was built not to notice

**Reported from the Air:** *"shaders don't line up perfectly unless you're on
the same vertical level as a block. This causes random glowing as you move
vertically."* That description names the defect exactly, including the one
position at which it vanishes.

**Every light was reflected about the middle of the screen.** `vTexCoord` is a
*texture coordinate*, and GL puts `v = 0` at the bottom of a picture that
`GlTarget` renders the right way up. The pass read it as a screen row:

```glsl
vec2 px = vTexCoord * uResolution;      // y measured from the bottom
```

while every light in it arrives from `Camera.worldToScreen`, whose y is measured
from the top. The camera keeps the player near the middle of the screen, so a
torch on the player's own level lands about where it belongs and the picture
looks right — and the further the view moves vertically, the further the glow
slides the other way. Measured: a light at `y = 40` of a 200-pixel frame put the
frame's brightest row at **159**, and 200 − 40 = 160.

**The instrument had a symmetry that hid it, and that is the part worth
keeping.** `GlShaderChainTest` uploaded its source texture unflipped and read
the result back unflipped, with a comment explaining that GL numbers rows from
the bottom so the two cancel. They do cancel — and the cancellation is exactly
what made the test blind. Inside it the frame was consistently upside down, so a
pass reading `vTexCoord.y` as a screen row read it from the wrong end of an
inverted frame and came out right. The test agreed with the CPU on every pass to
the hundredth while the player saw lights on the wrong side of the screen. The
same arrangement, and the same blindness, was in the core's `GlShaderHarness`.

> **A test whose two errors cancel measures nothing about either.** This is the
> second time in this plan that an instrument agreed with itself: D0 compared two
> backends and could not see a defect they shared, and this compared two
> orientations and could not see a defect in either. Both were reasonable
> constructions. Neither could fail.

**Fixed in three places, because one of them alone would have been a patch.**
`Shaders.FLIP_Y_GLSL` gives every fragment shader a `flipY` — its own inverse,
so it converts either way — and the passes that *measure* a position rather than
merely sample one now use it: `lighting`, `scanlines`, `pixelate`, `wave`,
`chromatic_aberration`. `vignette` and `bloom` are deliberately left alone
because they are symmetric in y, so that the presence of the call means
something. Both harnesses now upload and read back flipped, so the texture they
hand a pass is oriented like the one the renderer hands it. And
[`GlScreenSpaceTest`](gl/src/test/java/com/larsons/engine/gl/GlScreenSpaceTest.java)
drives the real renderer end to end and asks the crudest possible question: does
a light near the top of the frame light the top of the frame.

**Checked from both sides.** With the fix, the parity table is unchanged to the
hundredth — `lighting` 1.27, `pixelate` 0.00, `scanlines` 0.04, and the rest of
§2's numbers exactly. With the fix removed again, `lighting` parity goes to
**34.68** against a bar of 3.0. It could not do that before, which is the whole
point of having changed the harness.

**One thing the fix uncovered on the way through.** `pixelate`'s parity went
0.00 → 1.27 when the flip was corrected, because a block's centre expressed in
UV lands exactly on a texel boundary, and a boundary flipped to the other end of
an axis comes back one texel out — a whole row of each block sampled from its
neighbour. It now does the arithmetic in the texture's own texels and lands on a
texel centre at any display scale. Same family as D4 below, found the same way:
by a number moving that had no business moving.

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

#### C1 — done, and the step's own verification cannot fail on the bug it was written for

`Camera` has a heading. `yaw` and `targetYaw` are there, the rotation is inside
all three projection paths, and `CameraYawTest` holds nine properties across
both rotating formats at all eight headings.

**The convention, stated once so nothing has to guess it.** `yaw()` is the
compass heading the camera *faces*, radians clockwise from world north — north
being −y, up the screen at heading zero. The projection therefore applies the
**inverse** rotation: turn the camera right and the world swings left, which is
what a camera does and what *Don't Starve* does. At `yaw = π/2` the camera looks
east, so world east is the direction that now points up the screen.

Three decisions inside that, each of which could have gone the other way:

- **Isometric rotates on the ground plane first, then goes through the fixed
  diamond.** The camera turns around the world's vertical axis, not around the
  screen's; rotating the projected diamond instead would tilt the horizon.
- **`SIDE_SCROLL` stores a heading and ignores it.** §6.1's scope rule, and the
  storage half matters: a camera carried into a side-scroller and back out again
  must not silently forget where it was looking. `rotates()` says which it is.
- **`planar` and `inversePlanar` are now public.** C3 needs them — the moment
  the camera turns, the visible region stops being a rectangle of cells, so
  whatever decides what to sweep has to project corners itself. They were
  private because nothing outside had a reason to ask.

`setYaw` keeps `cos`/`sin` beside the angle rather than calling `Math.cos` four
times per tile, and **snaps them at the cardinal headings**: `Math.cos(π/2)` is
6.1e-17, not zero, so left alone a quarter turn would be a rotation by 6.1e-17
radians instead of an exact axis swap. The visible cost of that is 1e-11 px and
the invisible cost is the property worth keeping — at the four headings the
camera actually rests at, the turned projection is exactly the unturned one with
its axes exchanged, so the world's pixel lattice (D3) survives a turn as itself.

**The finding: the round trip this step specifies passes every version of the
bug it was specified to catch.** The four negative controls below were each run
against the whole test class:

| The camera, broken | What `inversePlanar(planar(p))` said | What caught it |
|---|---|---|
| The rotation mirrored (sine sign flipped, on both sides) | **round trip green** | the heading test, and the cardinal axis swaps |
| Yaw in `planar` but not in the inlined tile path | **round trip green** — `screenToWorld` failed, which pairs with `planar` | the tile-path/picking-path agreement test |
| `cos`/`sin` left unsnapped | **round trip green** | the cardinal axis swaps, at 1e-17 |
| `setYaw` stores the heading and the projection ignores it | **round trip green** | the heading test, and eight-distinct-positions |

The reason is worth keeping because it generalises: **an inverse derived from
the same matrix inverts a wrong matrix exactly as happily as a right one.** The
round trip proves the projection loses no information. It says nothing at all
about which way the world turned, and a mirrored rotation is a perfectly
invertible one. It is still worth having — a projection that does not invert
puts creative mode's strokes in the wrong cell — but it is not the sign check
the step believed it was.

What is: **at heading *h*, the world direction *h* must project exactly the way
north projects at heading 0.** That is what "the camera faces *h*" means, said
so it can fail, and it fails on all four controls that change the rotation.

**And one correction to the step's arithmetic.** It says a sign error "shows up
as a world that rotates the wrong way at exactly two of the eight headings". It
is the other way round: a mirrored rotation is exactly *right* at 0° and 180°,
where the sine is zero and the mirror is a no-op, and wrong at the other **six**.
A test that samples two headings is therefore likelier to pass a mirrored world
than to fail it, which is why every case in `CameraYawTest` runs all eight.

**A second defect, in code this step did not plan to touch.**
`TerrainCache.bakeCamera` derives the focus that makes the camera term vanish by
solving `(w − x) · zoom + viewport/2`, and then assigns that solution — a point
in *projected* space — straight to `x`/`y`, which are *world* coordinates. Those
were the same number for as long as `planar` was the identity for the two
formats the cache serves. They stop being the same number at any non-zero
heading, and the chunk is then baked from a position the frame is not looking
from: the shaking bug this class exists to fix, wearing a different hat, arriving
the first time a player pressed the rotate key. It now carries the heading and
routes the focus through `inversePlanar`. At heading 0 the inverse is the
identity and the arithmetic is bit-for-bit what it was, which is what the
unchanged goldens say.

**Verified.** `CameraYawTest`, 9 tests: the round trip, the three-path agreement,
the pixel round trip, the heading convention, eight distinct headings and an
exact full turn, the cardinal axis swaps, D3's rigid sheet **at all eight
headings** (`Camera`'s own note predicted rotation would survive the split
rounding; it is checked now rather than predicted), the side-scroller's
immunity, and `targetYaw`'s inertness. Suite **979 → 988 tests, 0 failures, 3
skipped** in core, plus `:gl` at 62/0/0, under `xvfb-run`. Goldens unchanged, as
a step that adds a heading nothing yet turns must leave them.

*(The 984 in §3's invariant 4 does not reproduce against a count of the result
XML at this commit, which is 979. The figures here are counted ones.)*

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

#### C2 — done. The accessor already existed, and the tidy rule about it is false

**`heightAt` is `Level.stackHeight(col, row)`**, and it has been there since the
plan views learned to stack: `0` bare ground, `1` a floor, `2` a wall. It was
written for mining, which takes a stack apart from the top down, and rotation
wants the same number for a different reason. So no accessor was added. A second
name for one concept is a thing every later reader has to check the equivalence
of, and this plan has already paid for that lesson twice (B6's `drawText`, B8's
white texel): **the third of the plan's instructions to be measured, and the
first to be measured as already satisfied rather than as wrong.**

**The decision C2 asks to record: the stack stays two deep.** Two levels is the
*Don't Starve* look, and a third reaches liquids (which pool in the upper
layer), pathfinding, the editor palette and the save format. Revisit separately
or not at all.

What was actually missing is smaller than an accessor and more useful than one.

- **The range was documented and never asserted.** `theHeightAxisIsZeroOneOrTwoAndNeverThree`
  now walks a cell from hole to floor to wall in every format, asserts the cell
  is then full (`placeLayer` returns −1, so nothing can answer 3), and checks
  that off-grid cells answer 0 — which is what a rotated sweep meets first, when
  its bounds stop being the grid's.
- **A side-scroller tops out at 1**, because `setUpper` refuses an edit in an
  unlayered format. That is §6.1's scope rule arriving from the other side: the
  format with no second layer is exactly the format that does not rotate.
- **"Height 2 means a wall" is false, and it was the natural thing to assert.**
  A torch standing on a path is two blocks deep and you walk straight through
  it: `walkable` asks the stacked block whether it is *solid*, and dressing is
  not a barrier. **Height is geometry; walkability is solidity.** C4 derives
  visible faces from height, so a torch has a side face to show when the camera
  turns — and a test asserting the tidier rule would have passed today, because
  nothing yet reads height for drawing, and would have made the torch
  un-drawable the moment something did. `heightIsGeometryAndWalkabilityIsSolidity`
  pins both halves.

**Verified.** `StackedBlockTest` 12 → 14 tests, green in all three formats. Full
suite after C1 and C2: **990 tests, 0 failures, 3 skipped** in core plus `:gl` at
**62/0/0**, under `xvfb-run`.

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

#### C3 — done. The precondition measurement rewrote the step

**The measurement first, because the step says to take it first.** A floor drawn
whole, against the same floor drawn in chunk-sized pieces and composited —
through the same camera, so placement cannot enter into it and the only thing
being measured is what compositing separately-antialiased edges does. As a share
of frame pixels:

| heading | 0° | 22.5° | 45° | 90° | 135° | 180° |
|---|---|---|---|---|---|---|
| **top-down** | 0.055% | 0.658% | 0.542% | 0.019% | 0.511% | 0.001% |
| **isometric** | 0.622% | 0.696% | **0.039%** | 0.612% | **0.027%** | 0.591% |

The step asked whether the seams appear in top-down under yaw. They do — an
order of magnitude worse than the same view at rest, and the same size as the
artefact isometric was excluded for. But the table says something the question
did not anticipate, and it is the more useful half:

> **Cacheability was never a property of the format.** It is whether the
> projection puts both of a tile's edges on a screen axis. An axis-aligned edge
> has no partial coverage to blend, so chunk images composite exactly; a
> diagonal one blends against transparency in its own image and leaves the
> background showing through along every shared edge. Top-down had the property
> at rest and loses it the moment it turns off a cardinal heading. **Isometric
> gains it at 45°**, where an eighth of a turn puts the diamond's edges back on
> the axes — measured at 0.039%, which is better than top-down at rest.

So `faithfulIn` takes a camera rather than a `Perspective`, and computes the
answer from `planarDelta` instead of consulting a table. It reproduces the old
answers exactly at rest and gives four more: **the floor is cacheable at four of
the eight headings in either format — the other four in isometric than in
top-down — and at none of the angles between them.** The cache got *wider*, not
narrower, which is the opposite of what the step was braced for. And the rule is
kept honest by the measurement itself rather than by the table above:
`theCacheabilityRuleMatchesTheSeamItIsAbout` re-runs the comparison every build
and asserts the rule and the artefact agree, because a number in a comment is a
claim about a version of Java2D's rasteriser rather than about this one.

**The turn behaves the way the step recommended, and needed nothing built for
it.** Mid-snap the projection is on no axis, so nothing is cacheable, so the
painter sweeps live — which was option 2, "invalidate on yaw change", arrived at
by measurement rather than by preference. Two properties fell out that were not
designed:

- **The invalidation is the validity key.** A chunk baked looking north simply
  is not a valid chunk for a camera looking east, in the same way a chunk baked
  at one zoom is not valid at another. There is no cache-clearing code.
- **A turn leaves the cache warm behind it.** A frame that does not use the
  cache does not evict from it either, so turning away and back finds the old
  heading still baked. Instant, and free.

**Two defects had to be fixed before any of that could be measured**, and
neither is visible in a diff:

1. **The single camera offset every chunk is placed from was the camera's
   *world* focus rather than its projected one.** Identical numbers until C1;
   at a quarter turn it put the entire baked floor 81.5% wrong against the live
   sweep.
2. **The heading was not in the validity key at all**, so a chunk baked looking
   north would have been served to a camera looking east.

**And the second of those is the session's third negative-control finding.**
Deleting the heading from the key left **all twenty-eight** of the cache's
existing tests green. Every one of them builds a cache, uses it at one heading
and throws it away; not one turns a camera that a cache is already warm for,
which is the only way the defect can show — and it is what a player does every
time they press the rotate key.

**The bounds instruction was already satisfied**, for the same reason C4's depth
order turned out to be. C3 says the sweep range "must become the axis-aligned
bounding box" of the rotated view; `visibleTileBounds` in both scenes has always
inverse-projected the four viewport corners and taken the box around them, so it
became correct under rotation at C1, for free.

**What was not free is the waste that box now holds.** Measured cells swept
against cells actually on screen:

| heading | 0° | 22.5° | 45° | 67.5° | 90° |
|---|---|---|---|---|---|
| top-down | 1.31× | 2.14× | **2.47×** | 2.14× | 1.31× |
| isometric | **2.28×** | 1.92× | 1.31× | 1.92× | 2.28× |

At rest the box is the view and the 1.31× is the one-cell margin — not worth a
test to save, which is why there has never been one. Turned an eighth, nearly
half of every terrain sweep is cells behind the player's shoulder. So the sweep
now rejects a cell whose projected quad falls outside the viewport, on the
cell's own corners rather than its centre, with a margin covering the lift a
block stands by and the offset its shadow falls at — reject on the base alone
and a wall pops out while the top of it is still in view. It is off while baking
a chunk, where "outside the viewport" means nothing and a chunk is deliberately
baked whole.

It pays twice over: the headings with the worst ratio are exactly the ones the
cache refuses to bake, so the sweep it halves is the live one. **And isometric
has been paying 2.28× of it at rest since it was written**, which no amount of
rotation work was needed to find and which fell out of measuring the thing the
step asked about.

**Verified.** `TerrainCacheTest` 26 → 33 tests: the rule against the seam it is
about, the rule at all eight headings in three formats, mid-turn refused,
baked-against-live parity at every heading claimed (isometric included — the
first diamond this cache has ever baked), a turn invalidating rather than
serving the old heading, and a turn in flight leaving the cache warm.
`TurnedTerrainTest` adds the sweep's two: a turned view that does not sweep the
box around itself, and a block below the screen still drawing what reaches back
into it. Negative controls: the camera offset back on the world focus (81.5%
wrong at 90°) and the heading dropped from the key (9 stale chunks served).
Goldens unchanged.

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

#### C4 — done. Half of it was already true, and the careful half was unreachable

**The faces are derived from the projected quad rather than from the heading.**
The old code asked the perspective and named them: the diamond's two lower edges
in isometric, the southern one straight down. Both are right at rest and neither
survives a turn — and the obvious repair, a table of which faces each heading
shows, needs a row per heading per projection and is wrong at every angle in
between, which is where a snap animation lives.

What decides it is not the heading but where the extruded face ends up pointing,
and the projected corners already know. A block stands *up* the screen, so a
side face is turned toward the viewer exactly when its edge's outward normal
points *down* the screen: an ordinary back-face cull, in two dimensions because
the extrusion is along a screen axis. It reproduces both hand-written answers
exactly — **the goldens did not move** — and gives the rest for nothing: one
face when the tile projects to an upright square, two when it projects to a
diamond, which a square tile does at every heading halfway between the
cardinals. Never three, which a convex quad cannot do.

The corner *order* within a face is derived too, and had to be: reversing it
mirrors the texture on that wall. The rule that reproduces all three
hand-written orders is "drawn from its higher end to its lower one".

**`!iso` was hiding a second defect of the same kind.** It also chose whether a
tile's texture is an upright blit or a warp through the quad's own edges — a
proxy for "the projection is orthographic", which was the same thing for as long
as the only non-upright projection was the diamond. A turned top-down view is
orthographic and *not* upright, so ground textures would have been blitted
unrotated: the tile turns and the road painted on it does not. Measured from the
projection now, and exact at rest because `setYaw` makes the zeros exact.

**The draw order needed no change at all, and the reason is worth keeping.**
C4 says the painter's order "must also be recomputed: back-to-front along the
*rotated* depth axis, not the world row index." It was never the world row
index. `DepthPass` sorts on the projected screen row of a thing's ground contact
point, and `TerrainPainter.baseDepth` gets that row by projecting through the
camera — so C1 turned it, in the same way and at the same moment it turned the
visible bounds. The half-tile offset the base point carries is the *same
constant* for every cell at a given heading, so it cannot reorder them; it only
sets where a sprite standing at a block's edge ties. Checked rather than
reasoned about: the near of two blocks is drawn second at all eight headings in
both plan views, with "behind" swinging round as the camera does.

**And the careful part of the new code was measured to be unreachable.** The
face cull was written the safe way first, measuring each quad's winding per cell
— because a normal derived from the wrong winding points *into* the block, draws
the faces turned away and hides the ones turned toward, which is precisely the
inside-out failure this step warns about. Then the negative control forcing the
winding to a constant **passed every test in the suite**, and the arithmetic
says why: a rotation has determinant +1, and the isometric transform's is
positive as well, so no projection this camera can make turns a tile's corners
round the other way. A branch with no case that reaches it is a branch that
rots, so the constant is the code and the assumption is a test —
`aTileReadsTheSameWayRoundAtEveryHeading` — which fails in one place, naming the
method, if a genuinely mirroring projection is ever added.

**Verified.** `TurnedTerrainTest`: faces at all eight headings in both plan
views (one face or two, and which), the winding assumption, and the near block
drawn second at every heading. Negative controls: the winding forced constant
(no failure — the finding above), and the old hard-coded face selection, which
fails at 1/8 of a turn showing one face where a diamond needs two.

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

#### C5 — done. The decision is recorded, and the risk was never the arithmetic

**Billboards, one set of art, chosen relative to the heading.** The
recommendation, taken: `Facing.asSeenFrom(yaw)` is the compass point whose sheet
should be drawn once the view has turned. Per-yaw sprite sets remain not viable
for the reason the table gives.

**Where the conversion lives is the part worth recording.** The facing stored on
a player or a mob is a *world* direction and has to stay one: it is simulated,
networked, and shared between client prediction and the server, while a camera
heading is per-client view state that is deliberately never sent (C10). They are
not the same quantity. So the conversion happens at the boundary where a
direction stops being something a character is doing and becomes a picture of
it, and nowhere earlier. `Camera.viewYaw()` is what every caller reads — the
heading the picture is actually drawn at, which is zero in a side view whatever
heading the camera was handed, because a camera carried between levels of
different formats must not turn a side-scroller's sprites while its world stays
put.

**The 64 cases are checked against the projection, not against restated
arithmetic.** The mapping is an index rotation, which is the kind that is off by
one in a direction nobody notices until a character moonwalks — and a test that
recomputes the expectation the same way the code does proves only that the
arithmetic was copied. So the expected sheet comes from `Camera.planarDelta`:
the direction the character is walking, projected by a real camera at that
heading, read back as the compass point it points at on screen. If the sheet
disagrees with the direction the world is visibly moving in, that is the bug.
The measurement runs through the top-down projection, because the rotation
belongs to the ground plane and is applied there, before the diamond — which is
also what keeps an isometric level drawing what it always drew at rest.

**And the risk was never the arithmetic.** A correct conversion that nothing
calls does nothing, and all sixty-four cases would still pass, because they
exercise the mapping directly. A facing reaches art through three separate
places — the body sheet, the object in the character's hands, and the arc a
swing draws — and those are far enough apart in a six-thousand-line scene that
the failure is not getting one wrong but forgetting one. Having now watched a
negative control find that exact gap twice in this job (C3's cache key, and this
one), the rule is stated where it can fail: `noSceneHandsARawWorldFacingToTheArt`
scans the two demo scenes and rejects a world facing reaching an art funnel
without the conversion around it, in the manner of `SealedSeamTest` and with its
own negative control. Removing the conversion from one of the nine call sites
fails it by name.

**Verified.** `FacingUnderYawTest`, 8 tests: the 64 cases against the
projection, the direction of travel in plain terms, all eight sheets reachable,
the nearest sheet mid-turn, a side-scroller never turning one, the eight sheets
being eight distinct pictures, the scan, and the scan's own control. Negative
control: the rotation applied the wrong way round, which fails at six of the
eight headings and passes at 0° and 180° — the shape C1 predicted for every sign
error in this job.

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

#### C6 — done. Nothing needed changing, and why that is not luck

All three of the things this step names were already right, and the
verification is the whole deliverable. What each of them turned out to be:

- **Shadows.** `TerrainPainter` already builds the sun's away-vector in world
  units and routes it through `planarDelta` — the step's own instruction,
  followed before there was a yaw to follow it for. The class comment even
  says why: *"the bearing is a compass direction on the world plane, so it is
  projected like anything else on that plane"*.
- **Surface decor.** `SurfaceDecorPainter`'s anchor, its "out of the face"
  direction and its "along the face" direction are all projections of world
  vectors, so all three turn. The one screen-space direction in the class is
  the **rise** — height, drawn straight up the screen — and that is correct
  rather than overlooked: this camera yaws and never pitches, so the elevation
  axis still points at the viewer whatever the heading. It is the same
  reasoning the step applies to liquids, arriving at the same answer.
- **The liquid surface line** is drawn between two *named corners* of a cell,
  and a corner is a world position. It stays on the pool's northern rim
  through a full turn, to the pixel.

**The reason all three were already right is worth keeping, because it is
transferable.** Every one of them was written or rewritten when the isometric
projection arrived, and a diamond is a projection that does not let a
screen-space assumption survive contact with it —
`SurfaceDecorPainter`'s note records exactly that lesson being learned, when
styles written straight up the screen "tore every tuft off the block it
belonged to as soon as the level was seen isometrically". **A camera that turns
is a second such projection, and code made honest by the first was already
honest for the second.** That is why C6 is three tests and no diff, and it is
the same reason C3's visible bounds and C4's depth order needed nothing.

**One control did not fire, and it improved the test rather than the code.**
Taking surface decor's "out of the face" direction as a screen axis passed
every assertion — because in a plan view a grass tuft stands *up the screen*
and spreads square to that, so `out` barely reaches its ink. The first version
of that test claimed more than it could see. It now asserts the sharper thing:
the four faces of one block stay on the block's four *world* sides through a
turn, so a tuft on the north face is still on the north side after a quarter
turn rather than sliding round to join the others.

**Verified.** Three tests in `TurnedTerrainTest`, eight headings each, in both
plan views. The shadow's expected direction is **carried, not restated** — the
offset is measured square-on, carried back into the world through the camera's
own inverse, and re-projected at each heading, so what is asserted is that one
fixed world vector is what the shadow follows. Negative controls: the shadow
offset taken as a screen direction (43° wrong at an eighth of a turn), and the
face's lean taken as a screen offset (the tuft lands on the far side of its
block).

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

#### C7 — done. The heading rides the input, because the server has no camera

**`PlayerInput` carries the heading the keys were pressed at**, and that is the
whole determinism argument in one sentence: the keys are a *screen* intent, the
camera that gives them meaning is per-client view state that C10 forbids
networking, so there is exactly one place the heading can come from — the input
command itself, travelling with the tick it belongs to.

The step says the yaw used must be the one the client had when the input was
generated, and putting it on the input is what makes that structural rather
than careful. Physics reads the heading from the input it is stepping, so
prediction and authority cannot use different ones; and a player turning the
camera while running does not have their in-flight inputs reinterpreted
underneath them, which is the rubber-band the step warns about and which would
have got worse the further behind the connection was.

`PlayerInput.moveX()/moveY()` return the world direction the keys mean, as a
unit vector. **The diagonal normalisation moved in with it**: physics had a
`speed *= √0.5` beside a branch that noticed the two-key case, and a unit
vector says the same thing once for every direction instead of for the four
diagonals. Side-scrollers do not ask — edge-on the keys are the separate things
they are there, left and right walking while up and down swim and climb, and
there is no heading to turn them by.

**The `Facing` that comes out the other side is still a world direction**,
because the rotation happens on the way *in*: physics derives the facing from
the movement it actually performed, which is a world movement. That is what C5
requires of it, and the two steps meet exactly there — one turns a screen
intent into the world, the other turns a world direction back into a picture,
and the thing stored between them is neither.

**The scan found a defect the step did not mention, in the tool the levels are
built with.** A first draft of the wiring check looked for one stamped input per
scene and passed; anchoring it on *every* input built from the movement binds
showed that `CreativeScene`'s first use of them is not the play-test input at
all — it is **the editor's camera pan**, several thousand lines earlier, moving
`camera.x` and `camera.y` along the world's axes. Turned an eighth, pressing
"left" in the editor sends the view off diagonally. It is C7's own defect in a
place C7 does not look, and it now goes through the same arithmetic: the pan
keys become a `PlayerInput`, and the heading rotates them. (It also picks up the
normalisation, so a diagonal pan stops being √2 times as fast as a straight one.)

**Verified.** `PlayerPhysicsTest` 8 → 15 tests: pressing up walks away from the
viewer at all eight headings — with the expected direction taken from the
camera's own definition rather than restated — every key at every heading being
one step in one of four distinct directions, a turned diagonal still one step, a
side-scroller unmoved by any heading on its input, an input from before headings
existed still meaning what it said, and the determinism boundary itself: two
hundred and forty ticks of a camera turning under a running player, prediction
stepping the local input and authority stepping the same input rebuilt from its
wire form, asserted **bit-identical**, not within a tolerance.

Four negative controls: the rotation applied the wrong way round (fails at
45°), the heading dropped from `toMap` (prediction and authority part company at
tick 1), the side-scroller not exempted (its walk speed changes), and a scene
forgetting to stamp the heading — which is the fourth time in this job that the
defect nothing exercised was the one worth writing a test for.

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

#### C8 — done. The keys the step suggested were both taken, and the check that should have said so could not

**The decision, recorded: queue one, drop the rest.** Four presses in a frame
turn the camera two points. A held key then walks the world round one point at a
time and stops when the key does; queueing every press would spin the world for
seconds after the key came up, and blending two turns would leave the camera
resting between compass points — the one thing an eight-point camera may never
do.

**Why "never rests between" is the assertion everything else hangs on.** A
camera left a hair off a compass point is not cosmetic. At 44.99°
`TerrainCache` refuses to bake the floor (C3), a tile texture stops being an
upright blit and becomes a warp (C4), and the cardinal headings stop being exact
axis swaps (C1) — three costs paid for ever by a camera that looks settled. So
the last frame of a snap **assigns** the heading from a whole-number compass
index rather than easing until the difference is small enough, and the tests
assert the resting position with a tolerance of exactly zero.

The heading is kept as that index, not as an angle: adding 45° to a `double`
once per press for the length of a session drifts, and drift is precisely what
turns "exact multiple of 45°" into "nearly". **A consequence worth writing down,
because it surprised the test first:** `yaw()` can step by a whole turn at the
instant a snap settles across north — the animation runs 0° → −45° and the
heading it lands on is 315°. It is the representation wrapping, not the camera;
`snap` gives both the same cosine and sine to the last bit. Anything measuring
how far the camera turned by subtracting two yaws has to fold the difference
into a half turn.

**The keys: not `Q`/`E`.** The step suggested them and both ship bound — `Q`
drops one of the held stack, `E` interacts with doors, chests and mounts. They
are `,` and `.` instead: free, adjacent under the right hand, and what *Don't
Starve* binds camera rotation to, which is the game §6.1 describes the feature
from.

**And the finding that outlived the keys: `KeyBinds.conflicts` could not have
told me.** It reports collisions inside a `Category`, on the argument that two
categories overlapping is not a conflict — the left mouse button attacks in play
and paints in the editor, and never both at once. That argument is sound for
play against editing, and for the world against an open menu. It is **not** sound
between `CAMERA` and `ITEMS`, which are live in the same frame of the same game:
binding rotation to `Q` would have turned the camera and dropped an item on one
press, and every conflict check in the project would have called it fine. The
negative control proved it — binding the rotate keys to the step's own
suggestion produced no failure at all until the test was rewritten to ask the
question the categories cannot: *no action the player can reach without opening
anything may share a key with either rotate key.* Menus and the editor stay
excluded on purpose, which is why `Space` may still both jump and confirm.

**One golden moved, and it should have.** `scene-key-binds` draws the controls
screen, and the screen now has two more rebindable actions in its Camera group.
Regenerated through B0's own escape hatch, which fails the build on the
rewriting run so it cannot happen by accident; only that one PNG changed.

**Verified.** `CameraSnapTest`, 9 tests: a randomised sequence of 30 presses
across 40 runs — presses mid-snap, presses in both directions, bursts faster than
the animation can absorb — asserting at every frame that the camera is either
turning or resting *exactly* on a compass point, and that the heading never
moves two points at once; one press turning exactly one point from each of the
eight, measured as distance swept so a turn that crosses north the long way
round fails; the turn taking time rather than happening on the press; the ease
being an ease; the queue rule; a full circle returning to bit-identical pixels;
and a side-scroller that cannot be turned at all. Negative controls: easing to
within 0.001% of the target instead of assigning it (six of the nine fail),
queueing every press (the queue rule fails), and the plan's own `Q`/`E` (fails
by name, once the test could see it).

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

#### C9 — done. The editor was already safe; the format is the new part

**Placement needed nothing.** Every one of creative mode's seven mouse-to-world
conversions already goes through `camera.screenToWorld`, which C1 made
yaw-aware — so the step's own precondition ("placement must resolve through the
same `inversePlanar` the picking path uses") was satisfied the moment the camera
learned to turn. What it did want is a sharper check than C1's round trip, and
that is now written: the round trip says a screen pixel maps back to *within a
couple of pixels* of the world point it came from, and an editor resolves that
point to a **whole cell**, where a half-pixel disagreement at a tile boundary
paints the neighbour. `aClickLandsOnTheCellItIsOverAtEveryHeading` projects the
centre of every cell in a spread and demands the pixel it lands on resolve back
to that same cell, at all eight headings in both plan views.

**The format: `heading`, an integer 0–7, absent when zero.** Stored as the
compass point rather than as radians for the same reason the camera stores an
index — an angle read from a file can arrive a hair off a compass point, and a
hair costs the floor cache and the upright tile blit for the whole session. It
follows `lightAngle`'s pattern exactly, which is the precedent already in the
file for "a look the level owns": written only when it differs from the default,
read with a default, and carried through the editor's undo `Doc` so an undo
across a change restores it.

**Two decisions inside that, both recorded rather than assumed:**

- **The heading is taken at save time, not on every press of a rotate key.**
  Turning to look at what you are building is not an edit, and making it one
  would fill the undo history with camera moves. `captureLevelSettings` is
  where it happens, beside the settings capture that was already there.
- **It is where the level *opens*, not a constraint on the player.** Both
  scenes place the camera there on adopting a level — settled, through the
  teleport rather than the animation, because a level should not slide into
  position as it loads. The player may turn from there whenever they like, and
  where they turn to stays theirs: C10 keeps the heading off the wire, so two
  players in one world may face different ways.

**Verified.** `LevelFormatTest` 29 → 33 tests: the heading surviving a save, and
being **absent** from a level built square to the world — which is every level
written before the field existed and every side-scroller there will ever be, so
an optional field that was always written would be a format change and a format
change is a file an older build cannot open. A heading outside the eight is
folded onto them rather than trusted. And the level this build ships is asserted
unchanged both ways: byte-for-byte on disk after loading it, and free of a
heading after being written back out. Negative control: writing the field
unconditionally, which fails three of those four by name.

---

### C10 — Multiplayer consistency

**Do.** Yaw is **per-client view state and must not be networked.** Two players
looking at the same world from different angles is the correct behaviour; a
synchronised camera would be a bug. Audit for anything that derives world state
from the camera and would therefore desync — the C7 input path is the known one,
but audit rather than assume.

**Verify.** Two clients at different yaws, same server, sustained play; assert
world state stays identical.

#### C10 — done. The audit is a scan, because an audit is worth a day

**The audit's answer: three camera-derived values cross the wire, all of them
inputs, and no camera does.** `PlayerInput` carries the heading (C7), the world
point an attack was aimed at, and the cell being mined. Each is a quantity the
player chose by looking at their own screen, converted to world terms before it
is sent and resolved by the server against the world — not view state being
synchronised. Nothing else: the `Camera` class is not named, in code, by any of
`sim`, `net`, `world`, `entity`, `combat`, `level` or `crafting`.

**The server had already written this rule down for a different reason**, which
is worth recording because it is the same argument arriving twice:

> *Physics always uses the served level's own format: a client switching its
> local camera view must not change how it moves on the server.*

Perspective was the first thing a client could have leaked into the simulation
and it was closed by taking the value from the level instead. The heading is the
second, and it is closed the other way round — by sending it explicitly, per
tick, as part of what the player pressed.

**One thing that looks like a leak and is not.** The level a joining client
receives now carries `heading` (C9). That is level data, authored once and
identical for everybody, in the way `lightAngle` is — where the level *opens*,
not where anyone is looking now. The distinction is the whole of C10 in one
field, so it is asserted rather than explained: the shipped level carries none,
and a snapshot carries none ever.

**An audit is worth exactly as long as it takes for the next change to
invalidate it, so it is a scan.** `noClassThatHoldsWorldStateNamesTheCamera`
walks the seven packages that simulate, serve, serialise and store the world and
fails if any of them names the class. Prose is left alone — several of those
files explain at length why they take a perspective from the level rather than
from the client, and a rule whose only remedy is deleting the explanation is a
bad rule. `SealedSeamTest` settled that argument once already for Java2D and
this strips comments and string literals the same way, with the same kind of
control on the stripper itself.

**And a control caught the control.** The first attempt at "a world-state class
acquires a camera" edited `public class PlayerState` when the file says
`public final class PlayerState`, so it changed nothing and the scan passed —
which for a moment looked exactly like a scan that does not work. Re-run against
a control that actually applied, it names the file. A negative control that
silently fails to inject is indistinguishable from a test that cannot fail, and
the only defence is asserting the injection landed.

**Verified.** `NetCameraIndependenceTest`, 5 tests, over a real server on a real
socket with two real clients:

- **The positive half.** Two clients, one looking north and one east, both hold
  "up" — which means "away from me" to each of them — and the server walks one
  north and the other east, in the same tick of the same simulation. Without
  this C10 is satisfiable by deleting C7.
- **The negative half.** Forty ticks of both players moving with their headings
  three eighths apart, then the two clients' descriptions of the same tick
  compared field by field in wire form. One world, whichever way you look at it.
- **The wire.** A player's networked state carries position, health and the
  direction they are *walking* — a world direction, which C5 keeps separate
  from the picture drawn of it — and no heading, however far round the client
  has turned.
- **The seal**, and its own control.

Negative controls: the server taking one heading for everybody (the two players
stop walking different ways), the heading leaked into the player snapshot, and a
world-state class acquiring a camera.

**JOB C DELIVERED**, by ten steps, of which **four needed no production change
at all** — C2's accessor already existed, C3's visible bounds and C4's depth
order were already written against the projection, and C6's shadows, decor and
liquids turn because the isometric projection had already forced them to be
honest. The recurring lesson of the job is in those four and in the five defects
found by negative control: **this codebase was mostly already right about
rotation, and the work was finding out where it was not.**

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

**It did survive it.** Reported from the Air after D1 shipped: *"the shimmer or
block shaking was not completely fixed."* D3 is what that turned out to be, and
it was neither of the two suspects named above.

### D2 — done

`GlHiDpiParityTest` is a standing test rather than a diagnostic: the tiles number
must stay at exactly 0.000, the shapes-and-text number must not move with the
camera, and a one-pixel pan must disturb the same count on both backends. It is
the first parity instrument in this project that runs at a display scale other
than 1 — the gap that let this question stay open for four steps.

### D3 — done. It was the projection, and every parity test in the project was blind to it

**The cause is one line of `Camera`, it was never a backend defect at all, and
four steps of this section looked in the wrong place because of how the question
was asked.** D0 compared GL against Java2D and found them pixel-identical, which
is a true and useful fact that cannot possibly find this bug: *both backends
draw exactly what the camera tells them to, and the camera was telling them
something slightly different every frame.* A comparison of two renderers is
structurally unable to see a defect they share. That is the lesson worth keeping
from §7 — more than the answer.

**The defect.** Step 2 of the projection rounded once, at the end:

```java
screen = round((world - camera) * zoom + viewport / 2)
```

`camera` is a `double` and slides continuously as it follows the player, so
every object crosses *its own* rounding boundary at *its own* moment. At
`zoom = 1.7` a 32-unit tile is 54.4 pixels wide, and as the view pans one tile
rounds to 55 while its neighbour is still at 54, then they swap. Neighbouring
blocks slide against each other by a pixel, continuously, for as long as the
camera moves.

**Why no existing test could see it, in one line: every test in this project
renders at `zoom = 1` or `zoom = 2`.** At either, a 32-unit tile is 32 or 64
pixels — a whole number — the fractional parts of neighbouring tiles are all
zero, and the old arithmetic is *exactly* correct. The bug cannot occur at the
only two zooms anything was ever measured at. In the game the zoom is a
`double` the player drives with a held key (`camera.zoom + dt * 2`), so it is
almost never one of those.

**Why the floor was steady and the blocks were not, which is what was
reported.** `TerrainCache` had already worked this out — its class note
describes the identical symptom ("as the view moved a fraction of a pixel the
chunks slid against one another and the terrain visibly shook") and fixes it by
baking each chunk at `round(worldX * zoom)`, with no camera term, and placing it
with one integer offset. But it caches only the *floor*. Stacked blocks, mobs,
dropped items, decor and particles are deliberately never cached — they join
the depth pass — so they kept the old arithmetic. Steady ground with shivering
blocks on it is exactly the picture that description fits.

**The fix is that same lattice, moved down into `Camera` where everything gets
it:**

```java
screen = round(world * zoom) + round(viewport/2 - camera * zoom)
//       \_________________/   \___________________________/
//        no camera term        one offset, shared by everything
```

Two roundings instead of one, so the whole scene can sit up to a pixel from
where a single rounding would have put it — the camera's focus point included.
That error is *uniform*, and a uniform offset is invisible where a moving one is
the bug. It is also the same trade `TerrainCache` already documents and has
shipped with since it was written.

**A consequence worth recording, because it is independent evidence rather than
a restatement: the baked floor and the live sweep now agree at a fractional zoom
as well as they always did at a whole one.** The cache had been on the correct
lattice all along and everything else had not, so the two drifted apart exactly
where the bug lived. Measured on a 480×360 view of a three-by-three-chunk level,
counting pixels where the cached frame differs from the live one with no offset
allowed between them:

| zoom | before D3 | after D3 |
|---|---:|---:|
| 1.0 — the old arithmetic was already exact here | 185 px (0.13%) | 185 px (0.13%) |
| 1.7 | **2,010 px (1.43%)** | **105 px (0.07%)** |

The 185 at `zoom = 1` is the genuine chunk-edge residual this cache has always
had — the two-pixel bake margin, and chunks baked whole so cells just past the
requested bounds are drawn. That number does not move, which is the point: at
1.7 the disagreement was fourteen times *larger* than the residual and is now
smaller than it.

**Measured.** [`CameraStabilityTest`](src/test/java/com/larsons/engine/CameraStabilityTest.java),
at `zoom = 1.7`, all three perspectives:

| what | before | after |
|---|---|---|
| on-screen width of one tile, over 500 camera positions | **54 and 55** | 55 |
| gap between two blocks 8 tiles apart | 451 and 452 | **one value** |
| pixels moving non-rigidly per ¼-pixel camera step, live sweep of a block wall | **2,542** | **0** |
| every projected point shares one delta when the camera moves | no | yes |

The last row is the strong form and the one that closes this: if every point on
screen moves by the same vector then nothing in the picture can move relative to
anything else, whatever it is.

**What this does not claim.** It does not explain why the Air's report singled
out GL — this arithmetic is shared, and Java2D shimmers identically at the same
zoom. D1's vsync change was a real fix for a real GL-only problem (tearing
against an unsynchronised present) and is not withdrawn; this is a second,
backend-independent defect underneath it. **D4 is the GL-only half, and it is
what "singled out GL" was pointing at.** And none of it touches
[`SIM_PLAN.md`](SIM_PLAN.md)'s 15–21 ms `update` spikes, which remain the
outstanding candidate for anything that still reads as a *hitch* rather than a
*shimmer*. Those are different words for different things, and the plan should
stop treating them as one.

### D4 — done. The GL sampler was picking a different texel for the same pixel

**Reported after D3 shipped:** *"the shimmer still happens with every block."*
D3 made the projection rigid and it was not enough, because there was a second
shimmer underneath it that belonged to the backend alone.

**The measurement that separated them.** Same wall of tiles, same real `Camera`
at `zoom = 1.7`, camera crept a quarter-pixel at a time, and the question asked
of each backend on its own: *how many pixels change beyond the whole-pixel shift
the camera asked for?*

| step | GL | Java2D upscaled 2× |
|---|---:|---:|
| 400.50 | **99** | 0 |
| 401.50 | **154** | 0 |
| 402.25 | **141** | 0 |
| 402.75 | **169** | 0 |
| 404.00 | **28** | 0 |

Java2D moved nothing it was not asked to. GL moved up to 169 pixels a step, and
**every one of them was inside a tile — not one within two pixels of a tile's
edge.** That is what ruled out the rasteriser, the multisample resolve and the
batch in a single query, and left the sampler.

**The cause.** A 16-texel block drawn 54.4 pixels wide is 6.75 device pixels per
texel, and at that ratio some texel boundaries land *exactly* on a device pixel
centre. `GL_NEAREST` resolves such a pixel with `floor(uv * textureSize)`, where
`uv` was interpolated across the quad by the rasteriser — so the answer comes
from the last bits of a float, and those bits change when the quad moves, even
by a whole pixel. The inside of every sprite fizzes while its edges sit still.

**Java2D does not have this** because it maps destination column to source
column with integer arithmetic anchored at the rectangle's own origin: translate
the rectangle and the answer translates exactly. That is why it measured zero,
and why the comparison was worth making before reaching for a cause.

**The fix** is to take the decision away from the interpolator. `GlProgram`'s
fragment shader nudges a thousandth of a texel past the boundary, floors, and
then samples that texel's *centre* — four orders of magnitude above the
interpolator's error, three below anything that could move a sample not already
on a boundary, and safe against atlas bleed because the value handed to the
sampler always names a texel interior. GL now moves **0** pixels a step, the
same as Java2D.

**Why nothing caught it.** `GlHiDpiParityTest` — D0's instrument, and the one
whose name suggests it would — sweeps the camera in *whole* pixels, positions
its tiles with integer arithmetic of its own rather than through `Camera`, and
uses a 12×11 tile. Every coordinate in it is a whole number at every step, so
the ratio that makes this possible never occurs. Its answer was true and
narrower than it read.
[`GlSubPixelStabilityTest`](gl/src/test/java/com/larsons/engine/gl/GlSubPixelStabilityTest.java)
is the version that can fail: a real camera, a fractional zoom, quarter-pixel
steps, and the two backends required to disturb the *same* amount of the frame
rather than to draw the same frame — because at a fractional zoom they
legitimately do not, GL being sharper by about 2.6 of 255.

### D5 — done. Two pacers, and the loop was running at 46 FPS while asking for 60

**Reported after D4 shipped:** *"the shimmer is still happening. It is subtle but
noticeable."* And, when bloom was suspected: *"it's not bloom, at least I can turn
it off and the shimmer still happens."*

**So the picture was eliminated before the cause was looked for.** Four
measurements, each on the configuration the player is actually in — a real
`Camera` at `zoom = 1.7`, crept a quarter-pixel at a time:

| what moved beyond the camera's own shift | GL |
|---|---:|
| the scene, no chain | **0 px/step** |
| the scene, `lighting` | **0** |
| the scene, `bloom` | **0** |
| the scene, `lighting` + `bloom` | **0** |
| the terrain, through the cache | **0** |
| the terrain, live sweep | **0** |
| vertical camera motion | **0** |
| diagonal camera motion | **0** |

*(An early run of the bloom figure showed 336 px/step and was the test's own
fault: the comparison window was 12 px from the edge and bloom's ring reaches
14. At an honest margin it is zero. Recorded because it was very nearly reported
as a defect.)*

**The engine's output is rigid. So the defect is not in the picture — it is in
when the picture arrives.** And the profile that came with the report contains
it:

```
present   p99  16.752 ms      <- one refresh period at 60 Hz: the swap blocks
idle      p50  11.975 ms      <- the frame limiter, waiting as well
work/frame     11.333 ms
work + idle    21.549 ms      -> 46 FPS, against a target of 60
```

**D1 turned vsync on and left the software limiter running beside it.** That is
the whole bug, and it is not obvious, because both pacers are individually
correct. The limiter schedules on an absolute timeline from `System.nanoTime`;
the panel refreshes on an unrelated one. The phase between them drifts. Whenever
the limiter's deadline lands just after a refresh boundary, that frame's swap
misses the boundary and blocks a whole further period — so the loop alternates
between one refresh and two, and the apparent motion of everything on screen
alternates with it. **A world drawn rigidly and delivered unevenly looks like a
world that is not rigid.** That is why this is filed under D and not under
performance.

**The fix.** `Renderer.presentationIsPaced()` says whether `present()` already
waits for the display; `GlRenderer` answers yes when vsync is on; `GameLoop`
stands its limiter down when told so. Standing down is not doing nothing — the
cap becomes 240 FPS rather than infinity, because a driver that reports vsync
and does not honour it would otherwise leave the loop unlimited, and an
unlimited loop feeds the fixed-step simulation frame times near zero and parks
it in the catch-up path for good. On a display that really is pacing, that guard
is never reached.

**Two smaller things went with it.** The swap interval is now re-applied
whenever the context becomes current on a thread — it governs the thread that
*swaps*, and in this engine that is never the thread that created the window,
which is where it was being set. And **the frame report now says which pacer is
in charge**, because `idle` means something different under each: when the loop
paces, the wait is `idle`; when the display paces, the same wait is inside
`present` and `idle` falls to nearly nothing. Two profiles that disagree about
which is which cannot be compared, and a reader with no way to tell would
reasonably conclude the headroom had vanished.

**What this does not claim.** It is a fix derived from a measurement in the
player's own profile, not one confirmed on the player's own machine — nobody
here has a 60 Hz panel and an M1. If a shimmer survives it, the next suspect is
already named and is not in this plan: `update` still spikes to **15.040 ms** at
p99 against a **0.579 ms** median in that same profile, which is one frame in
twenty with no budget left, and it is [`SIM_PLAN.md`](SIM_PLAN.md)'s S1.

**Job D took five fixes and five hypotheses.** D1 (tearing, presentation), D3
(`Camera`, projection, both backends), D4 (the sampler, GL only), D5 (pacing,
temporal) — and D0, which found nothing and was right to. The lesson the section
keeps: *"the shimmer" was never one defect*, every instrument that said "no
shimmer here" was answering a narrower question than the one being asked, and
the last of them was answered by eliminating the picture entirely rather than by
finding one more thing wrong with it.

### D6 — done. The `alpha` every scene was handed and none of them read

**Reported after D5 shipped, of a GL profile run:** *"the shimmer or block
shaking only seems to happen in side-scroller games."*

**That sentence is the finding, and the operative word is "only".** D3 and D4
were defects in the *picture*, and a defect in the picture cannot be
format-specific: `Camera` projects all three formats through the same `place`,
`GlTarget` rasterises all three through the same quad. Nothing in the drawing
path knows which format it is drawing. So whatever was left had to be in
something a side-scroller does and the plan views do not — and what a
side-scroller does is **pan continuously along one axis, at a constant speed, for
as long as a run key is held.**

That is not a hint about geometry. It is a hint about *time*, and D5 had already
named the shape of it without finding this instance: *a world drawn rigidly and
delivered unevenly looks like a world that is not rigid.* D5 fixed the delivery
(two pacers). What was still uneven was the **sampling**.

#### The defect, and it is a promise the engine made and never kept

`GameLoop` runs the simulation in fixed `1/updateRate` steps and renders
separately, handing each frame an interpolation factor:

```java
double alpha = Math.max(0.0, Math.min(1.0, accumulator / nsPerUpdate));
render.render(alpha);
```

`Scene.render(DrawTarget, float alpha)` documents it — *"interpolation factor in
[0,1] between sim steps"*. `GameLoop`'s class note promises motion *"stays smooth
when render and update rates differ"*. `EngineConfig` explains that the fixed
rate is *"decoupled from rendering"*.

**No scene has ever read it.** `PlayScene.render` took the parameter and shadowed
the name with a local `int alpha` for a HUD colour. Every frame drew the last
completed 120 Hz simulation step, whole, while the display presented frames on an
entirely unrelated clock.

#### Why that is a shimmer rather than a latency

A frame does not contain a whole number of simulation steps and cannot be made
to. The step is 8.333 ms and a 60 Hz refresh is 16.667 ms, so a frame nominally
owes exactly two — but the two clocks are unrelated, the remainder in the
accumulator random-walks, and whenever it crosses a boundary the frame runs three
steps or one. The world then scrolls **1.8 px, 3.7 px or 5.5 px on successive
frames where it should scroll 3.7 px every time**, and the camera follows the
player, so every static tile on screen carries that unevenness with it. The
blocks are not moving relative to each other at all — they are rigid, and D3
proved it. The whole sheet is being sampled at the wrong moments.

Measured over 20,000 frames against the loop's own arithmetic, with **0.2 ms** of
jitter on the frame clock — a well-behaved machine, not a struggling one — at
220 px/s and zoom 1.7:

| clock | steps per frame | scroll, px/frame | uneven | worst |
|---|---|---|---:|---:|
| 60 Hz display, before | 1: 209 · 2: 19577 · 3: 214 | −10:78 −9:136 −7:4567 −6:15009 −4:22 −3:187 | **3.62%** | **7 px** |
| 60 Hz display, after | *(unchanged)* | −7:4684 −6:15314 −5:1 | 0.01% | 2 px |
| 144 Hz display, before | 0: 3329 · 1: 16671 | — | **27.63%** | 2 px |
| 144 Hz display, after | *(unchanged)* | — | 0.00% | 1 px |

Roughly **one frame in forty, twice a second**, the world jumped. And the 144 Hz
row is the one not to miss: a 120 Hz simulation cannot feed a faster display, so
**better than one frame in four contained no new step at all** and repeated the
one before it. That is the same defect at its most severe, on the monitor a
player is most likely to be pleased with.

#### Why the plan views hid it

Three things compound, and none of them is about the projection:

- **Sustained single-axis motion is what makes it legible.** Holding → in a
  side-scroller pans the view at a constant 220 px/s for seconds. Plan-view play
  is short two-axis bursts, where an uneven step reads as part of the input
  rather than as the world shaking.
- **A side-scroller is the only format that draws the parallax backdrop**, whose
  slow layers give the eye a near-static reference to measure the world's motion
  against.
- **Isometric halves the horizontal rate** per unit of world movement, so the
  same error is a smaller fraction of a smaller number.

#### The engine had already found this bug once, on the other side of the wire

`PlayScene.drawWorldEntities` interpolates *replicated* entities between the two
buffered snapshots straddling render time, and says why:

> *drawing the raw latest snapshot stepped everything at the 30 Hz broadcast
> rate, which read as constant stutter next to the 120 fps local player.*

That is this defect, diagnosed correctly, and fixed for network state only. The
offline simulation is a 120 Hz broadcast into a 60 Hz display and had no
equivalent. **The comment was right and its scope was the bug.**

#### The fix

Keep the promise. `StepInterpolation` holds the argument; every body that moves
keeps the position it held one step ago; a frame draws at
`prev + (cur - prev) * alpha`.

- **The camera focus first, before anything projects through it** — including the
  lighting pass, which converts world positions to screen ones. `update()` still
  centres the camera on the *simulated* position, because everything that maps
  between screen and world in `update` (a mining click, a placed block, a shot's
  aim, the audio listener's reach) has to agree with the authoritative step
  rather than with a frame drawn between two of them. `render()` moves it onto
  the interpolated one. Both are wanted, and the shimmer is a property of what
  was drawn.
- **The local player on the same alpha as the camera**, so the two cannot drift
  against each other by the step being spanned. Then mobs, dropped items,
  projectiles, vehicles, and the client-predicted mount a rider is glued to.
- **`FrameCadence`** — the accumulator arithmetic, lifted out of `GameLoop` so it
  can be measured without a clock or a thread. Same split `FramePacingTest`
  already makes: *what is checked is the decision, not the timing*.

**Interpolating rather than extrapolating, deliberately.** Predicting
`cur + alpha*(cur - prev)` removes even the one step of lag, and overshoots every
time the simulation changes its mind — the frame a jump peaks on, the frame a
runner meets a wall, the frame a shot lands. Those are the frames the eye is
watching. 8.3 ms of constant lag is invisible; a pop there is not.

**One guard covers every relocation.** A spawn, a respawn, a door warp, a level
load, a server correction: each moves a body an arbitrary distance with no
simulation in between, and blending across one would slide the sprite over the map
for a frame. Rather than annotate every call site that can do it — and miss the
next one — the arithmetic notices: movement in one fixed step is bounded by the
fastest thing in the game (under 8 px at 120 Hz), and beyond 64 px it is not
movement. That also covers a body whose previous position was never captured at
all, which is why no constructor has to remember to prime it.

**Nothing here touches the simulation**, which is `SIM_PLAN` invariant 1 and is
not optional: the blend reads two positions and returns a third for drawing. A
server that renders nothing calls none of it, and a client's simulated result is
bit-identical with it and without it. `askingWhereToDrawABodyDoesNotMoveIt` pins
that.

#### What is proved, and what is not

`StepInterpolationTest` makes the strong claim rather than "it looks smoother":
**the interpolated position of a body moving at constant speed is a linear
function of elapsed real time, one fixed step behind it, whatever number of steps
each frame ran.** Let a frame end having run `N` steps with `a` of a step left in
the accumulator; the drawn position is `(N−1)·s + a·s = s·(N + a − 1)`, and real
elapsed time measured in steps is exactly `N + a` — that is what the accumulator
holding the remainder *means*. So drawn position is `s·(t − 1)`: no dependence on
the step count at all. The test asserts it to 1e-6 on every one of 20,000 frames.

Together with `CameraStabilityTest` — rigid in space — that is the whole of
"smooth": a rigid sheet, sampled evenly.

**Two things are deliberately left, stated rather than discovered later.**

- **Particles are not interpolated.** They advance per step and are drawn raw, so
  they carry their own step of judder. They are small, short-lived and fast, and
  the eye does not track one. If a player reports otherwise, the mechanism is the
  same and the plumbing is a `prevX/prevY` on the emitter's array.
- **The editor's camera is not interpolated either**, and that is a choice rather
  than an omission: it is panned by a held key rather than by a simulated body,
  and blending it would put every placed block a fraction of a step from where
  the cursor was when the click landed. An editor that draws exactly where it
  will paint is worth more than one that scrolls slightly more smoothly. The
  play-test inside the editor *is* interpolated, because that is play.

**And what the 1 px in the table is.** A 220 px/s pan at zoom 1.7 is 6.23 px a
frame, and an integer lattice can only render that as an alternation of 6 and 7.
That alternation is regular, and regular quantisation is what the eye reads as
smooth motion — it is the same staircase every pixel-snapped 2D game draws, and
it is the price of the rigid lattice D3 bought. Removing it means a fractional
global translate at present time and bilinear resampling of every sprite, which
trades a defect nobody can see for blurred pixel art everybody can. Not done, and
not a defect.

**Job D took six fixes.** D0 (nothing, correctly), D1 (presentation), D3
(projection), D4 (the GL sampler), D5 (pacing), D6 (sampling). The lesson the
section keeps is unchanged and now has one more instance: *"the shimmer" was never
one defect.* This one hid behind a parameter that was computed, documented,
passed to every scene, and read by none of them — which is the one place a
measurement was never going to look, because the code all appeared to be there.

---

### D7 — done. The cache threw its whole screen away twelve times a second

**Reported after D6 shipped, from two machines:** *"the block shaking in
side-scroller games is still there. The blocks look like they are vibrating."*

**"Vibrating" is a frequency, and that is what finally made this findable.** D6
had removed a defect that fired on about 2.5% of frames — irregular, not
rhythmic. Vibration is periodic. So the question stopped being "what moves?" and
became "what happens on a fixed beat?", and every instrument in this project was
blind to it for one reason: **they all render two or three frames at a stopped
animation clock.** `CameraStabilityTest` creeps a camera and holds `animClock`
at 0. All twenty tests in `TerrainCacheTest` pass `0.0`. D5's exoneration of the
picture was measured the same way. Nothing in the build had ever asked what the
renderer does over *seconds of time passing*.

Asked, over 119 frames of a static side-scroller level with the camera **not
moving at all**, it answers: 71 of them differ from the frame before. And the
pattern is exact:

```
++..+++..+++...+++.+++..+++..     (. identical, + differs)
```

A five-frame cycle at 60 fps. **12 Hz.**

#### The cause

`TerrainCache.Key` carried `animFrame` — the animation clock quantised to 12
FPS — for **every chunk, unconditionally.** The field's own javadoc said "a chunk
holding an animated texture rebuilds at this rate"; nothing checked whether the
chunk held one. So in a level with no animated textures anywhere, every chunk on
screen went stale together twelve times a second.

`MAX_REBUILDS_PER_FRAME` was 4, against 12 visible chunks at 720p. The overflow
was drawn **live**, and that is the frame this class has a measured opinion about
— `MIN_CHURN_TO_STAND_ASIDE`'s note records **22 ms** for a half-and-half frame
against 16 ms all-live and 2 ms all-baked, and says in as many words that "the
decision belongs to the frame". The rebuild budget had been quietly overriding
that on two frames in every five, for ever:

| frame | from cache | re-baked | drawn LIVE | terrain pass |
|---:|---:|---:|---:|---:|
| 0 | 0 | 4 | 8 | **16.2 ms** |
| 1 | 4 | 4 | 4 | 11.3 ms |
| 2 | 8 | 4 | 0 | 10.3 ms |
| 3 | 12 | 0 | 0 | 2.5 ms |
| 4 | 12 | 0 | 0 | 2.4 ms |
| 5 | 0 | 4 | 8 | **15.0 ms** |

**So this was never a drawing defect, and that is why five previous fixes did not
touch it.** The terrain pass alone oscillated between 2.4 ms and 16.2 ms on a
12 Hz beat against a 16.67 ms whole-frame budget — two frames in five with
nothing left for the rest of the frame, delivered late, while the other three
arrived on time. **D5 wrote the rule that names it:** *a world drawn rigidly and
delivered unevenly looks like a world that is not rigid.* D5 fixed the pacer, D6
fixed the sampling, and the terrain pass was making the frame time itself vibrate.

Over 600 frames of walking, 1280x720, zoom 1.7:

| | mean | p50 | p95 | chunk re-bakes/sec |
|---|---:|---:|---:|---:|
| before | 8.19 ms | 9.36 | 15.43 | **152** |
| after | **1.84 ms** | 1.63 | 2.08 | **3.6** |

152 re-bakes a second, each a 435x435 image re-rendered cell by cell and — on the
GL backend — re-uploaded to the GPU. 3.6 is the number a walk genuinely reveals.

#### Why only side-scrollers, which is what the report kept saying

In a side-scroller `Level.layered()` is false, so **the cached floor *is* the
blocks**: the entire visible world sits on the layer that was thrashing. A plan
view draws its stacked blocks and their shadows live in the depth pass over the
floor, so a large share of the screen is live-drawn anyway and the beat is buried
under it. Isometric is not cached at all (`faithfulIn`). The one format where the
cache owns the whole picture is the one format where the report came from.

#### The fix, in two parts, both of which are policy the class already had

1. **`animFrame` moved out of the `Key` and onto the `Entry`,** consulted only for
   a chunk that actually baked an animated texture. `ChunkRenderer` now returns
   whether it resolved one, because the painter is what resolves texture keys and
   the cache never sees them. A chunk of static blocks does not become wrong
   because time passed. `Skins.animated(key)` is the same test `SkinDef.frameAt`
   already used to decide whether to advance a sheet.

2. **The frame's decision is made once, before anything is drawn.** Count the
   visible chunks that cannot be served; if they fit the budget, rebake them and
   every chunk on screen is a blit; if they do not, sweep the whole view live in
   one uniform pass and spend the budget baking for a later frame. A frame is now
   all of one thing, so two consecutive frames can differ only by the camera's
   shift. That is exactly what `MIN_CHURN_TO_STAND_ASIDE` was written to
   guarantee and what the budget had been violating.

**Two things fell out of getting the policy right.**

- **The budget is sized off the viewport rather than being a constant.** A fixed 4
  could not absorb what one frame can newly reveal — crossing a chunk boundary
  uncovers a column, which is 3 chunks at 720p and 6 at 1440p — so on a tall
  display the frame took the live sweep and the next went back to blits, once per
  crossing. Measured at 2560x1440 while walking: 11 frames in 299 differed from
  their predecessor, in clusters 1.16 s apart, which is exactly how long 8 tiles
  take at 220 px/s. It is now one column plus one row, floored at 4 and capped at
  16, read from the window rather than from `bounds` — a caller may legitimately
  ask for a region far bigger than the screen and a budget scaled to the request
  would bake the screenful the cap exists to prevent. Now 1 frame in 299, the cold
  start.

- **The churn threshold stopped answering the wrong question.** It counted changed
  *cells* and swept the whole view past a threshold, which is a bad proxy for "how
  much rebaking does this frame owe": a liquid tick that rewrites thirty cells
  inside three chunks is three cheap rebakes. Because `LiquidSim` ticks every
  0.22 s, that produced a full-view live sweep every thirteenth frame in **any
  side-scroller with running water** — `...BBBBBBBBBBB L r BBBBBBBBBBB L r...` —
  a second rhythmic flip, at 4.6 Hz, on a beat set by the water. **`SIM_PLAN.md`
  S2 named this as a suspected flicker and marked it unconfirmed; it is now
  confirmed and fixed.** The cell count is kept for the one thing chunk counting
  cannot see — whether baking is *futile* because the ground is genuinely being
  rewritten faster than it can be baked — and decides only whether the live sweep
  bothers to bake.

#### What was measured that turned out not to matter

**The pixel disagreement between baked and live is zero on a textured level.**
`TilePainter`'s axis-aligned blit has an integer, camera-free destination
rectangle, so the two renderings are identical: 0 differing pixels of 921,600
with a 16-pixel texture on every block. The 0.05% figure quoted in D3 and here
belongs to the *procedural* path, where a cell is a filled polygon plus an
antialiased darker outline, and at a chunk edge that outline blends against its
neighbour live and against transparency in a chunk image. Nearly half of it sits
within two pixels of a chunk boundary.

That matters because it was the first hypothesis, and it was the weaker one. The
flip-flop's visible cost is almost entirely **temporal**, not spatial — which is
why a player on a texture pack and a player on the default procedural art both
reported the same thing.

#### What is left

One transition per cache-warm: nothing is baked before the first frame, so the
view is swept live until the budget catches up, and the hand-over is a single
frame differing by 0.05% of pixels on procedural art and 0 on textured. It
happens on entering a level. Baking everything on the first frame instead would
trade it for the 13 ms spike the budget exists to prevent, and a level entry is
the one moment a hitch is least visible — so it is a real choice either way, and
it is recorded here rather than made silently.

**Job D is now seven fixes**, and the section's lesson holds for the seventh
time: *"the shimmer" was never one defect.* What this one adds is a sharper
version of it. Every instrument that said "no shimmer here" was answering a
narrower question than the one being asked — and D7's narrowness was not the
scene, or the backend, or the zoom. It was **time**. A renderer measured only
across two consecutive frames cannot see anything that happens on a beat.

---

---

## 8. What each instrument proves

Eight instruments, eight distinct questions. Using the wrong one is how a step
gets declared finished while broken.

| Instrument | Question it answers | Where it lives |
|-----------|---------------------|----------------|
| **Golden frames** | Does the player see the same picture? | B0, new |
| **`SealedSeamTest`** | Can the migration silently un-happen? | `render/SealedSeamTest.java`, B4 |
| **`RecordingTarget`** | Did the code issue the draw calls it was supposed to, in order? | `graphics/draw/RecordingTarget.java` |
| **`DrawStats.mergeRatio()`** | Will a GPU backend help, or is every operation its own batch? | `graphics/draw/DrawStats.java` |
| **`DrawCallReport`** | What did a batching change actually buy, frame by frame? | `render/DrawCallReport.java`, B5 — writes `build/reports/draw-calls.md` |
| **`FrameProfiler` / `FrameReport`** | Where does the frame actually go? | `profile/` |
| **`ShaderParityTest` metric** | Do two implementations of the same effect agree? | `ShaderParityTest`, reused by A2 and B8 |
| **`GlShaderChainTest`** | Does the *shipping* chain run the shaders the way the CPU runs them — in order, at any size, in logical units, with every uniform bound? | `gl/…/GlShaderChainTest.java`, A2–A4 — writes `build/reports/gl-shader-chain.md` |
| **`GlParityTest`** | Does the GPU backend draw the same picture, and how many draw calls does it really issue? | `gl/…/GlParityTest.java`, B8 — writes `build/reports/gl-parity.md`, and PNGs for any frame over the bar |
| **`ModuleBoundaryTest` + `:verifyNoRuntimeDependencies`** | Can the core quietly acquire a runtime dependency? | `render/ModuleBoundaryTest.java` and the root build, B7 |
| **`BackendSelectionTest` + `GlBackendTest`** | Does the right renderer get picked, and does the wrong answer still leave a playable game? | `render/BackendSelectionTest.java` (every route, no GPU needed) and `gl/…/GlBackendTest.java` (the classpath, the driver, the provoked failure), B9 |
| **`GlBatchTest`** | Does the backend survive a frame bigger than its buffers? The catalogue answers *vocabulary*; this answers *volume*, and the two are different questions — B8a shipped because only the first was being asked | `gl/…/GlBatchTest.java`, B8a |
| **`GlResizeTest`** | Does the backend survive the window *changing size*? Every other instrument here renders at a fixed size for the whole of its life | `gl/…/GlResizeTest.java`, §10 |
| **`CameraStabilityTest`** | Does the world hold still relative to *itself* while the camera moves? Every parity test compares two backends and is therefore blind to a defect they share — which D3 was | `CameraStabilityTest.java`, D3 |
| **`GlSubPixelStabilityTest`** | Does the *GL backend* hold still, at a fractional zoom and a fractional camera step? The two questions above are asked at whole-pixel sizes, where the defect cannot occur | `gl/…/GlSubPixelStabilityTest.java`, D4 |
| **`StepInterpolationTest`** | Is the world *sampled* evenly in time? Every instrument above asks about the picture, and this one is about when the picture is taken — a rigid sheet sampled unevenly is indistinguishable from a sheet that is not rigid | `StepInterpolationTest.java`, D6 |
| **`TerrainCacheTest`, the tests at the bottom** | Does the renderer hold still over *seconds*, rather than over two frames? Every other instrument here runs at a stopped `animClock`, which is why a 12 Hz flip-flop survived six fixes | `TerrainCacheTest.java`, D7 |
| **`GlScreenSpaceTest`** | Does a shader know which way up the frame is? The chain's parity harness uploads and reads back with two flips that cancel, so it cannot | `gl/…/GlScreenSpaceTest.java`, A7 |
| **`CameraYawTest`** | Does the camera turn, the right way, in every path out of it? Three pieces of arithmetic say where a world point lands — the tile loop's inlined one, the picking path's, and the inverse creative mode paints with — and a yaw added to one of them renders a correct world that the mouse disagrees with, which no golden frame can see | `CameraYawTest.java`, C1 |
| **`TurnedTerrainTest`** | What does the terrain do when the view turns — which cells are swept, which faces of a block are shown, which of two blocks is in front? Every other terrain instrument renders square-on to the world, where a tile is a rectangle and only one of its edges can face the viewer | `TurnedTerrainTest.java`, C3–C4 |
| **`TerrainCacheTest`'s seam measurement** | Is the cacheability *rule* still the artefact it stands for? The rule is one line of arithmetic about tile edges; what it is really about is what Java2D's rasteriser does at a diagonal, which is not this project's to promise | `TerrainCacheTest.java`, C3 |
| **`FacingUnderYawTest`** | Is a character drawn walking the way the world visibly moves them — and does any scene still hand a raw world facing to the art? The second question is the one that fails in practice: the conversion is correct and forgotten | `FacingUnderYawTest.java`, C5 |
| **`NetCameraIndependenceTest`** | Can one client's view reach another client's world? Every other network test in the project runs one client or two identical ones; this is the only one that makes them differ in something that must not matter | `NetCameraIndependenceTest.java`, C10 |
| **`CameraSnapTest`** | Does the camera always arrive on a compass point and never rest between two? Randomised presses mid-snap are the only way to ask, because every other instrument in Job C sets a heading and reads it back — none of them can produce the state where a turn is interrupted | `CameraSnapTest.java`, C8 |
| **`PlayerPhysicsTest`'s C7 half** | Does a key press mean the same world movement on both sides of the wire, while the camera turns under the player? Every other determinism check in the project steps the *same object* twice; this one steps an input and its round-tripped twin, because the defect it is aimed at lives in the serialisation | `PlayerPhysicsTest.java`, C7 |

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
      │  D1  vsync on by default        ← done. B9 had turned it off. A real
      │        GL-only fix for tearing, and NOT the shimmer — see D3.
      │  D2  GlHiDpiParityTest          ← done. First parity test at scale 2.
      │  D5  two pacers, not one       ← done. D1 turned vsync on and left
      │        the frame limiter running beside it. present p99 = 16.752 ms
      │        (one refresh: the swap blocks) PLUS idle p50 = 11.975 ms (the
      │        limiter waiting too) = 21.5 ms/frame: 46 FPS while asking for
      │        60, delivered at whatever phase two unrelated schedules drifted
      │        into. The picture was eliminated first — scene, cache, chain,
      │        lighting, bloom, vertical, diagonal: 0 px/step on every one.
      │        A rigid world delivered unevenly looks like a world that is
      │        not rigid. Necessary and not sufficient — see D6 below.
      │  D4  the GL sampler            ← done, and D3 was not enough on its
      │        own: "the shimmer still happens with every block". A 16-texel
      │        block drawn 54.4 px wide puts texel boundaries exactly on
      │        device pixel centres, and GL_NEAREST then decides from the
      │        last bits of an interpolated float — which move when the quad
      │        does. Java2D moved 0 px a step; GL moved up to 169, all of
      │        them inside tiles and none at a tile edge. Now 0.
      │        Necessary and not sufficient either — see D5 above.
      │  D3  the shimmer, actually found ← done. Camera rounded
      │        (world - camera) * zoom in ONE step, so every object crossed
      │        its rounding boundary at its own moment and neighbours slid
      │        against each other by a pixel. Not a backend defect: both
      │        drew what the camera said. Invisible to every test in the
      │        project because they all render at zoom 1 or 2, where a
      │        32-unit tile is a whole number of pixels wide and the old
      │        arithmetic is exactly right. Fixed with TerrainCache's own
      │        lattice, moved down into Camera so everything gets it.
      │        Necessary and not sufficient — see D4 above.
      │  D6  the alpha nobody read      ← done, and it was reported as
      │        "only in side-scroller games", which is what named it: a
      │        defect in the PICTURE cannot be format-specific, so what was
      │        left had to be in something only a side-scroller does, and
      │        that is pan continuously along one axis for seconds at a
      │        time. GameLoop computed an interpolation alpha, Scene.render
      │        documented it, EngineConfig explained it, and no scene ever
      │        read one: every frame drew the last completed 120 Hz step
      │        whole. A frame holds no whole number of steps, so 2.46% of
      │        them scrolled by the wrong amount on a 60 Hz panel (worst:
      │        7 px where 6 was due) and 27.63% on a 144 Hz one, where a
      │        120 Hz simulation cannot keep up and one frame in four was a
      │        duplicate. Now blended: the drawn position is provably linear
      │        in real time, one step behind, whatever the step count was.
      │        The engine had already found this bug for NETWORK entities
      │        and written the comment explaining it; the offline path is a
      │        120 Hz broadcast into a 60 Hz display and had no equivalent.
      │        Necessary and not sufficient — see D7 below.
      │  D7  the cache's own 12 Hz beat ← done, from "the blocks look like they
      │        are VIBRATING", on two machines. Vibration is a frequency, and
      │        that is what made it findable: TerrainCache.Key carried the
      │        12 FPS animation frame number for EVERY chunk, animated or not,
      │        so every chunk on screen went stale together twelve times a
      │        second. The 4-per-frame rebuild budget could not keep up and drew
      │        the overflow LIVE — the half-and-half frame this class measures
      │        at 22 ms against 16 all-live and 2 all-baked. So the terrain pass
      │        oscillated 2.4 -> 16.2 ms on a 12 Hz beat against a 16.67 ms
      │        budget: not a drawing defect at all, a DELIVERY one, which is
      │        D5's own rule coming back. Static level, camera not moving:
      │        71 of 119 frames differed from the one before. Mean pass
      │        8.19 -> 1.84 ms; re-bakes 152/s -> 3.6/s. Side-scroller-only
      │        because layered() is false there, so the cached floor IS the
      │        blocks and the whole picture sits on the thrashing layer.
      │        Two more flips went with it: the budget is now sized off the
      │        viewport (a fixed 4 could not absorb a revealed column, so a
      │        1440p walk flipped once per crossing), and the churn rule stopped
      │        counting cells — thirty cells in three chunks is three rebakes,
      │        and counting cells swept the whole view every 13th frame in any
      │        level with running water, which is SIM_PLAN S2's unconfirmed
      │        suspicion, now confirmed. Invisible to twenty cache tests
      │        because every one of them passes animClock = 0.
      │        JOB D DELIVERED, by seven fixes.
      │
      ├─ A1  scene renders to a texture ← done. Offscreen when a chain has
      │        passes; straight at the window otherwise, because the resolve
      │        costs 14-19 ms/frame on a software rasteriser and buys nothing
      │        until A2. JUSTIFIED by 5.460 ms/frame of CPU shaders at 2x
      │        HiDPI, and the GL backend runs NEITHER pass today — a GPU
      │        build has no day/night at all. See §5.0-5.1.
      │  A2  GlShaderChain ping-pong    ← done. Every pass reproduces §2's
      │        parity table to the hundredth (0.00 ×5, 0.04, 0.32, 0.47,
      │        3.58). uResolution is LOGICAL, not device — four effects
      │        would be wrong at 2x otherwise. GPU timer queries, because
      │        a draw call returns before the work starts.
      │  A3  uniform binding            ← done, and it found the defect: three
      │        of LightingPass's uniforms could not be advertised at all
      │        (a Float cannot carry a vec3) and uLightCount was bound with
      │        glUniform1f against an int, which the driver refuses in
      │        silence. ShaderPass.vectorUniforms(); types read from the
      │        program. ShaderCompileTest now scans BOTH directions.
      │  A4  LightingPass               ← done. Parity 1.27, held to the
      │        ordinary 3.0 bar. Clamp provoked at 4 lights on a driver
      │        that holds 32. Keeps the FIRST n, not the nearest — parity
      │        with the CPU path beats a nicer rule. See A4.
      │  A5  keep + test the CPU chain  ← done. 983/0/3 both backends. Plus
      │        the end-to-end test the plan did not ask for: a chain
      │        attached to GlRenderer, over the scene it really drew.
      │  A7  the mirrored lighting pass ← done. vTexCoord is a TEXTURE
      │        coordinate and GL puts v=0 at the bottom; the pass read it as
      │        a screen row, so every light was reflected about the middle of
      │        the screen. Invisible on the player's own level, worse the
      │        further you climb. The parity harnesses uploaded and read back
      │        unflipped, so their two errors cancelled and they could not
      │        fail. Both now match the renderer's orientation; removing the
      │        fix takes lighting parity to 34.68 against a bar of 3.0.
      │  A6  correct the README         ← done. Three of the four fixes are
      │        corrections of UNDERstatement, which is new for Appendix B.
      │        JOB A DELIVERED — the correctness defect in §5.1 is closed.
      │
      ├─ §10 the window resize crash    ← done. Dragging the window's edge
      │        terminated the process on the Air: AppKit reallocates the
      │        drawable on thread 0 while the render thread blits into it.
      │        CGL context lock for the length of a frame; the drawable's
      │        real size instead of round(logical x scale); GlResizeTest.
      │        Came from a crash report, not from this plan.
      │
      └─ C1  camera yaw               ← done. yaw = the compass heading the
         │      camera FACES, clockwise from north; the projection applies the
         │      inverse, so turning right swings the world left. Iso rotates the
         │      ground plane and then goes through the fixed diamond — the axis
         │      is the world's, not the screen's. cos/sin snapped at the four
         │      cardinals so a quarter turn is an exact axis swap rather than a
         │      rotation by 6.1e-17 rad. THE STEP'S OWN VERIFICATION CANNOT FAIL
         │      ON THIS BUG: the round trip passed all four negative controls,
         │      including a mirrored rotation and a camera that ignores yaw
         │      entirely, because an inverse derived from the same matrix
         │      inverts a wrong one just as happily. What fails is "at heading h
         │      the world direction h projects the way north projects at 0".
         │      And the step's arithmetic is backwards: a mirrored rotation is
         │      right at 2 of the 8 headings and wrong at 6. Also fixed
         │      TerrainCache.bakeCamera, which solved for a focus in PROJECTED
         │      space and assigned it to a WORLD coordinate — identical until
         │      the camera could turn, then the shaking bug again.
         C2  formalise the height axis ← done. The accessor already existed:
         │      Level.stackHeight, written for mining, 0 hole / 1 floor / 2 wall.
         │      No second name for it. Stack limit STAYS AT TWO (recorded).
         │      What was missing: the range was documented and never asserted,
         │      and the tidy rule is false — a torch on a path is height 2 and
         │      still WALKABLE. Height is geometry, walkability is solidity, and
         │      C4 reads the first while collision reads the second.
         C3  rotate the grid            ← done, and the precondition measurement
         │      REWROTE the step. Cacheability was never a property of the
         │      format: it is whether the projection puts a tile's edges on a
         │      SCREEN AXIS. Seam 0.001-0.055% at every heading the rule allows,
         │      0.51-0.70% at every heading it refuses. Top-down loses it when
         │      it turns; ISOMETRIC GAINS IT AT 45 degrees, where the diamond is
         │      a square again — so the cache got WIDER, four headings in either
         │      format and none in between, which is where a snap lives. The
         │      turn needs no invalidation code: the heading in the validity key
         │      IS the invalidation, and a frame that skips the cache does not
         │      evict from it, so turning back is instant. Two defects first:
         │      the one offset every chunk is placed from was the camera's WORLD
         │      focus not its projected one (81.5% wrong at a quarter turn), and
         │      the heading was not in the key at all — which all 28 existing
         │      cache tests missed, because not one of them turns a camera a
         │      cache is already warm for. The bounds instruction was already
         │      satisfied (both scenes always inverse-projected the corners).
         │      The waste was not: 1.31x square-on, 2.47x at 45 degrees, and
         │      2.28x that ISOMETRIC HAS PAID AT REST SINCE IT WAS WRITTEN. Now
         │      rejected per cell, on the cell's corners, off while baking.
         C4  face visibility + depth order ← done, and half of it was already
         │      true. Faces come from the projected quad — a 2D back-face cull —
         │      not from the heading: reproduces the diamond's two edges and the
         │      plan view's one TO THE PIXEL, and gives every heading between
         │      them for nothing. `!iso` was hiding a second defect of the same
         │      shape: it also decided whether a tile texture is an upright blit,
         │      so a turned top-down view would have blitted its ground textures
         │      UNROTATED. DEPTH ORDER NEEDED NO CHANGE — the plan expected work
         │      because it assumed the sort key was the world row index, and it
         │      never was: DepthPass sorts on the projected screen row, so C1
         │      turned it. And the careful half of the new code was measured
         │      UNREACHABLE: forcing the quad winding to a constant passed every
         │      test in the suite, because a rotation has determinant +1. The
         │      constant is the code now and the assumption is a test.
         C5  billboards + directional frames ← done. Facing stays a WORLD
         │      direction (it is networked; C10) and Facing.asSeenFrom(viewYaw)
         │      is where it becomes a picture. The 64 cases are checked against
         │      Camera.planarDelta rather than against the same index arithmetic,
         │      so the test can see a mapping that turns the wrong way. The risk
         │      was never the arithmetic: a facing reaches art through the body
         │      sheet, the held object and the swing arc, and the failure is
         │      forgetting one — so a scan rejects a raw facing reaching any of
         │      them, after the same gap was found by control twice already.
         C6  shadows, decor, liquids     ← done, with NO diff, and that is the
         │      finding. All three were already written against the projection:
         │      the shadow's bearing goes through planarDelta (the step's own
         │      instruction, followed before there was a yaw), decor's anchor
         │      and both of its face axes are projected world vectors, and the
         │      liquid line runs between two named CORNERS of a cell. The one
         │      screen-space direction left — decor's rise, and a block's lift —
         │      is correct rather than missed: this camera yaws and never
         │      pitches, so height still points at the viewer. Why none of it
         │      needed changing is transferable: every one was written when the
         │      ISOMETRIC projection arrived, and a diamond does not let a
         │      screen-space assumption survive. A turning camera is a second
         │      such projection. One control did not fire and improved the TEST
         │      rather than the code — a wrong "out of the face" direction is
         │      invisible to a tuft that stands up the screen, so the test now
         │      asserts the four faces stay on the block's four world sides.
         C7  yaw-relative input          ← done. PlayerInput carries the heading
         │      its keys were pressed at, because the camera is per-client view
         │      state C10 forbids networking, so the server has none to ask.
         │      That makes the determinism boundary STRUCTURAL: physics reads
         │      the heading off the input it is stepping, so prediction and
         │      authority cannot use different ones, and a player turning while
         │      running does not have in-flight inputs reinterpreted underneath
         │      them. moveX/moveY return the world direction as a UNIT vector,
         │      which absorbed the √0.5 diagonal special case physics used to
         │      carry. Side-scroll exempt — edge-on the keys are separate things.
         │      The Facing that comes out is still a WORLD direction, which is
         │      exactly what C5 needs of it: one step rotates screen intent into
         │      the world, the other rotates world direction back into a
         │      picture, and what is stored between them is neither. The scan
         │      found a defect the step does not mention: CreativeScene's EDITOR
         │      PAN moved camera.x/y along the world's axes, so a turned editor
         │      panned diagonally. Same arithmetic now.
         C8  the snap animation        ← done. Queue one, drop the rest (recorded):
         │      four presses in a frame turn two points. The last frame of a
         │      snap ASSIGNS the heading from a whole-number index rather than
         │      easing until it is close enough, because 44.99° costs the floor
         │      cache (C3), the upright tile blit (C4) and the exact axis swap
         │      (C1) for as long as the camera sits there — a control that stops
         │      within 0.001% fails six of nine tests. Keys are , and . : the
         │      step suggested Q and E and BOTH SHIP BOUND (drop item, interact).
         │      And KeyBinds.conflicts could not have told me — it reports
         │      inside a Category, so rotation in CAMERA against dropping in
         │      ITEMS is not a conflict by its rule, and both fire in the same
         │      frame. The control passed until the test stopped asking
         │      conflicts and started asking what is live at once. One golden
         │      moved (the controls screen has two more rows) and should have.
         C9  editor + save format      ← done. Placement needed NOTHING: all
         │      seven of creative mode's mouse-to-world conversions already go
         │      through screenToWorld, which C1 turned. What it wanted was a
         │      sharper check than the round trip — an editor resolves a pixel
         │      to a WHOLE CELL, so the middle of every cell must resolve back
         │      to itself, which is now asserted at eight headings. The format
         │      is `heading`, an integer 0-7, absent when zero, following
         │      lightAngle's pattern exactly. Taken at SAVE time, not on every
         │      rotate press — turning to look at what you are building is not
         │      an edit, and making it one fills the undo history with camera
         │      moves. It is where the level OPENS, not a constraint: C10 keeps
         │      the heading off the wire, so two players may face different ways
         │      in one world.
         C10 multiplayer consistency  ← done. The audit's answer: THREE
                camera-derived values cross the wire and all are INPUTS — the
                heading (C7), the world point an attack aimed at, and the cell
                being mined. No camera does: the class is not named in code by
                sim, net, world, entity, combat, level or crafting, which is now
                a scan rather than an audit, because an audit is worth a day.
                The server had already written the rule down for perspective —
                "a client switching its local camera view must not change how it
                moves on the server" — and closed it by taking the value from
                the LEVEL; the heading is closed the other way, by sending it
                explicitly per tick. The level's own `heading` (C9) looks like a
                leak and is not: it is level data, authored once, identical for
                everyone, in the way lightAngle is. A control caught a control —
                the first "a world-state class acquires a camera" injection
                edited `public class PlayerState` where the file says `public
                final class`, changed nothing, and passed.
                JOB C DELIVERED, by ten steps, FOUR of which needed no
                production change at all.

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
risk was already retired, so it went first — but nothing forced that order, and
C followed it.

**All four jobs are now closed, and the shape of the last one is worth keeping.**
Job C was ten steps and **four of them needed no production change at all**:
C2's height accessor already existed under another name, C3's visible bounds and
C4's depth order were already written against the projection rather than against
the grid, and C6's shadows, decor and liquids already turn. The reason is the
same in every case and it is not luck — each was written or rewritten when the
**isometric** projection arrived, and a diamond is a projection that does not
let a screen-space assumption survive contact with it. A camera that turns is a
second such projection. Code the first one made honest was already honest for the
second, and the six steps that did need changing are exactly the places nothing
had ever forced to be.

The other half of the job's shape is that **five defects were found by negative
control rather than by a failing test**: a cache key no test ever turned, a
branch nothing could reach, a conversion no scene would have called, a heading
every physics test set for itself, and a key collision the project's own
conflict check is built not to report. In each case the code was written, the
tests were green, and the only thing that found the hole was deliberately
breaking the thing under test and watching what stayed silent.

---

## 10. The resize crash, and the drawable nobody owned

**Not a job, and it did not come from this plan.** A crash report arrived from
the M1 Air: dragging the window's edge terminated the process. It belongs here
because the fault is in the GL backend and because the arrangement it exposes —
two threads sharing one window — is one this plan designed on purpose and
described as safe.

### 10.0 What the report said

```
Triggered by Thread: 23  Java: game-loop
Exception Type:    EXC_BAD_ACCESS (SIGABRT)
Exception Subtype: KERN_INVALID_ADDRESS at 0x0000000000000000

Thread 23 Crashed:: Java: game-loop
  9  GLEngine        gleBlitFramebuffer + 632
 11  GLEngine        glBlitFramebufferEXT_Exec + 2096
 12  libGL.dylib     glBlitFramebuffer + 80

Thread 0:: Dispatch queue: com.apple.main-thread
 14  AppKit          -[NSWindow(NSWindowResizing) _resizeWithEvent:] + 640
 15  AppKit          -[NSTitledFrame attemptResizeWithEvent:] + 156
```

Both halves of the fault are on one page. The render thread was in
`GlSurface.blitToWindow`, writing into framebuffer 0 — the window's back buffer.
The main thread was inside AppKit's live-resize loop, where GLFW's window
delegate answers `windowDidResize:` with `[nsgl.object update]`, which
reallocates the drawable's storage. The blit dereferenced the buffer that was
being replaced.

**Nothing in the engine's own code was wrong, which is why no amount of reading
it would have found this.** The sizes were right, the framebuffer was complete,
the command was legal. `GlWindow`'s class note was careful about which thread
may call which *function* and correct about all of it — and functions were never
the shared resource. The drawable was.

### 10.1 The fix

`CGLLockContext` is the mutex the platform's own GL stack takes around drawable
changes, and `NSOpenGLContext`'s methods — `update` included — acquire it
internally. So a render thread that holds it for the length of a frame cannot be
interrupted by a resize: AppKit's update blocks on the main thread until the
frame is presented, then proceeds.
[`GlDrawableLock`](gl/src/main/java/com/larsons/engine/gl/GlDrawableLock.java)
is that, `GlRenderer.beginFrame()` takes it and `present()` returns it in a
`finally`, and off macOS none of it is reached — LWJGL's `CGL` class is named
only inside branches a non-Mac never takes.

**The cost is stated rather than hidden:** a drag now waits up to one frame per
mouse move. `-Dlarsons.render.gl.drawablelock=off` is there so a Mac user can
say whether a sluggish drag is this.

**A second, smaller thing was wrong on the same path.** The renderer sized its
surface and its blit from `round(logicalWidth × scale)` rather than from the
drawable GLFW reports. Those agree while the window is still and stop agreeing
during a drag between panels of different scale, when the logical size and the
framebuffer size move at different moments — so a blit could be sized from a
pair that was never simultaneously true. `GlWindow` now publishes the
framebuffer size beside the scale and `GlRenderer` samples both once per frame.

### 10.2 What is tested, and what is not

**The race itself needs a Mac, a mouse and a window edge, and is not
reproducible in this suite.** Saying so is more useful than a test that implies
otherwise. What
[`GlResizeTest`](gl/src/test/java/com/larsons/engine/gl/GlResizeTest.java) does
check, on any machine with a driver, is everything around it — each of which was
an independent way to get the resize path wrong:

- a frame after a resize is the new size in all three places that have to agree
  (the scene's target, the offscreen surface, the blit), growing *and* shrinking;
- twenty consecutive resizes — the shape of a real drag, one per mouse move,
  each reallocating two framebuffers, two renderbuffers and a texture — leave
  the driver with no error to report;
- every frame gives the drawable back, **including a frame that threw**. On
  macOS an unbalanced hold is not a crash but a window that freezes the first
  time it is dragged, so `-Dlarsons.render.gl.drawablelock=force` runs the
  protocol against a counter on platforms that have no CGL to run it against.

**The whole GL suite runs under a software rasteriser**, which is worth writing
down because it had been assumed not to: `xvfb-run ./gradlew test` takes all 52
GL tests from skipped to run. The parity numbers this plan quotes were being
checked on developer machines only.

### 10.3 The second defect in the same report — done, and the close button was the tell

The crash log shows `+[AWTStarter starter:headless:]` → `runAWTLoopWithApp:` →
`[NSApplication run]` on thread 0, **underneath** GLFW's `glfwPollEvents`. AWT's
loop is `do { [app run]; } while (YES)` and never returns, so in that process
`Engine.pumpUntilStopped()` had entered `pumpEvents()` once and would never come
out. Events still flow — AppKit dispatches them to the GLFW window regardless of
who is pumping — so the game plays normally, which is why this survived: what
stopped happening was the *loop*. `closeRequested()` is never read again,
`larsons.run.seconds` never fires, and `Engine.shutdown()` is unreachable.
**Clicking the red button sets a flag nobody looks at.**

This was first recorded here as open, on the grounds that fixing it would break
the three `JFileChooser` sites in the creative, skin and board editors and was
therefore a decision about the product rather than a bug fix. **The first half of
that was wrong, and checking rather than assuming is what changed the answer.**
All three already wrap the chooser in `catch (RuntimeException)` and put "file
browser unavailable" in the scene's status line; `HeadlessException` is a
`RuntimeException`, and `CreativeScene`'s dialog has always had a text field to
type the path into. They degrade to a fallback that exists, on a path that only
exists on macOS with a GPU backend. Against that: a window that cannot be closed.

So `MacGlLauncher.keepAwtOffTheFirstThread()` sets `java.awt.headless=true`, on
macOS, when a GPU backend is on the classpath and Java2D was not asked for by
name, and the relaunched child gets the same thing on its command line because a
property set after the toolkit has started is ignored in silence. An explicit
`-Djava.awt.headless=false` is left alone — this is a default, not a policy.

**One cost was not accepted.** `DeviceProfile.detect()` reads the display's size,
refresh rate and scale from `GraphicsEnvironment`, and a headless process has
none, so every GL frame report would have said "headless / unknown" about a run
on a real monitor — in a project whose reports exist precisely so a profile can
say what it was taken on. `BackendWindow.displayMode()` and `displayScale()` now
answer it from GLFW, which knows the monitor better than AWT does anyway, and
`Engine` fills the profile from the backend for anything AWT could not supply.

**What is tested, and by which module.** `MacGlLauncherTest` in the core holds
every negative — off macOS, no GPU backend on the classpath, Java2D asked for by
name, an explicit setting — and it can hold all of them precisely because the
core's test classpath is the plain jar's world with no backend on it. That is
also why it cannot hold the positive one, which lives in
[`GlHeadlessLaunchTest`](gl/src/test/java/com/larsons/engine/gl/GlHeadlessLaunchTest.java)
where `Backends.discover()` finds something. A headless flag that fires for a
Java2D run is a game with no window at all, so the negatives are the ones worth
the most checking.

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
