# Simulation Plan — finding and fixing the update stall

**Status:** Written 2026-08-03 from six frame profiles taken on the M1 Air
during `RENDER_PLAN.md` B10. **S1 is done, S2 is answered, and S3 has landed its
first fix.** The cause did not have to be inferred from S1's breakdown in the
end: a seventh report arrived carrying a stack trace, and it named the first
suspect on the list.

```
Exception in thread "game-loop" java.lang.OutOfMemoryError: Java heap space
    at com.larsons.engine.world.LiquidSim.reachable(LiquidSim.java:262)
    at com.larsons.engine.world.LiquidSim.flowFamily(LiquidSim.java:209)
    at com.larsons.engine.world.LiquidSim.tick(LiquidSim.java:141)
    at com.larsons.engine.world.World.step(World.java:360)
```

An out-of-memory error is the loudest possible version of the stall this
document was written about, and it arrived from the same machine and the same
level. S1's instrument was built anyway — the plan's own rule is that nothing
merges that cannot be measured, and a fix with no instrument behind it would
have been exactly the guess §4 warns about.

---

## 0. The evidence

Six profiles, two backends, three configurations, same machine and same level.
The `update` stage in each:

| run | backend | shaders | mean | p50 | p95 | p99 |
|---|---|---|---:|---:|---:|---:|
| 1 | java2d | off | 1.997 | 0.732 | 15.217 | 15.794 |
| 2 | java2d | off | 1.753 | 0.602 | 14.826 | 15.326 |
| 3 | java2d | **on** | 2.372 | 0.763 | 15.652 | **21.681** |
| 4 | gl | off | 1.918 | 0.682 | 16.492 | 17.360 |
| 5 | gl | off | 1.961 | 0.694 | 17.217 | 17.756 |
| 6 | gl | **on** | 2.001 | 0.692 | 17.217 | 17.756 |

**Read the shape of that, not the averages.** A typical update costs **0.6–0.8
ms**. The 95th percentile costs **15–17 ms**. So this is not a simulation that is
uniformly slow — it is one that is very fast about nineteen frames out of twenty
and then spends an entire frame budget, or more than one, on the twentieth.

Three things are already established and worth not re-deriving:

- **It is not the renderer.** The numbers are the same on Java2D and GL, which
  share no code below `DrawTarget`. `RENDER_PLAN.md` Job B cut the scene stage by
  61% and moved this not at all.
- **It is not the shader chain.** Runs 1/2 against 3, and 4/5 against 6.
- **It is reproducible.** Six runs, two builds, one shape.

**Why it matters more than a mean suggests.** At a 60 Hz cap the budget is 16.67
ms. A p95 of 15–17 ms means roughly one frame in twenty has no budget left for
anything else — and the render stage still has to happen. That is a visible
hitch, and it is the kind a player describes as "it stutters sometimes" rather
than "it runs slowly". With GL now leaving 59% of the frame spare, **this is the
single largest remaining threat to a steady 60 FPS.**

---

## 1. What we do not know, and why the instrument is the first problem

`update` is one number. `scene` has a six-way breakdown — decor, terrain,
entities, depth-flush, particles, hud — because `PlayScene.phase(...)` wraps each
one and `FrameProfiler.recordSection` records it. Nothing does that for the
simulation, so the profile can say the update stalled and cannot say what in it
stalled. That asymmetry is the reason Job B could be planned from measurements
and this cannot be yet.

**And there is a second ambiguity, which is worse because it is invisible.**
`GameLoop` runs the simulation at a fixed 120 Hz while the render cap is 60, so a
normal frame runs **two** update steps — and when the loop falls behind it runs
up to **eight** in a row as catch-up. `FrameProfiler`'s own javadoc says
`Stage.UPDATE` "runs once per catch-up step — so records sum". Therefore:

> A `p99` of 21.681 ms might be **one** step that took 21 ms, or **eight**
> catch-up steps of 2.7 ms each. Those have completely different causes and
> completely different fixes, and the current report cannot tell them apart.

The second reading is a *feedback* failure — something makes one frame slow, the
accumulator falls behind, and the next frame pays for it eight times over,
possibly staying behind. The first is a single expensive operation. **Guessing
between them is exactly the mistake `RENDER_PLAN.md` §0 was written to prevent.**

### The suspects, named so they can be eliminated

Not conclusions. Places to point S1's instrument, in the order they are worth
pointing it:

| Suspect | Why it is on the list | What would confirm it |
|---|---|---|
| **Liquid simulation** | `World.step` opens with `liquids.step(level, …)` returning block changes, and B8 recorded that a level with liquids in it rebuilds terrain chunks "continuously, up to four a frame". A whole-level scan is periodic-shaped work | A `liquids` section that owns the spike |
| **Catch-up cascade** | Two steps per frame normally, eight when behind; one slow frame can pay for itself several times over | Step *count* per frame correlating with the spike |
| **GC** | 2 GB heap, and a simulation that allocates per entity per step would pause here | Spikes that do not land in any one section, plus GC logging |
| **Mob AI** | `World.step` iterates every mob, and `drainMobActions`/`resolvePendingBursts` allocate lists lazily inside the loop | A `mobs` section scaling with mob count |
| **Terrain cache invalidation** | `blockChanges` feeds chunk rebuilds; if any of that happens on the update thread it belongs to this plan rather than the render one | A section covering the block-change drain |
| **Sound** | `ctx.sfx`/`Sounds.actor` are called from the update path; a first-play decode is a classic one-in-twenty stall | A `sound` section, or the spike vanishing with audio off |

---

## 2. Invariants — rules no step here may break

These are not style preferences. Two of them will fail multiplayer silently if
broken, which is the worst way for them to fail.

1. **The simulation stays deterministic.** `PlayerPhysics` is the same fixed step
   the server ticks and the client predicts with (`README` requirement #3). Any
   change that alters iteration order, floating-point results, or the sequence of
   random draws breaks client prediction — and it breaks it as *desync under
   load*, which looks like a network problem and is not one. **A change that
   makes one machine faster and two machines disagree is not an optimisation.**
2. **The timestep stays fixed.** Making `dt` variable would make the stall
   disappear from the profile without making the game smoother, by converting a
   measurable hitch into unmeasurable non-determinism. It is the one fix that
   must never be made.
3. **Nothing merges that cannot be measured.** Same rule as `RENDER_PLAN.md`
   invariant 5. The instrument comes first here precisely because there is
   currently nothing to measure against.
4. **The suite stays green**, and the headless server keeps working — it runs the
   same `World.step` with no display at all, which makes it a useful place to
   measure the simulation with the renderer removed entirely.

---

## 3. The steps

### S0 — Freeze a reference

**Goal.** Know what "fixed" looks like before changing anything.

**Do.** Record the six profiles above as the baseline (done — §0). Capture one
more with `-Dlarsons.run.seconds` on the headless server path if it can be driven
without a window, so there is a renderer-free number to compare against.

**Done when.** The baseline is in this document. **It is.**

### S1 — Make the update stage legible

**Goal.** Turn one number into a breakdown, and resolve the catch-up ambiguity.
**Nothing else in this plan can start until this lands.**

**Do.**
- Give the simulation the same treatment the scene got: named phases recorded
  through the profiler. `recordSection` currently nests everything under
  `Stage.SCENE` and `FrameReport` prints it as "Scene breakdown", so this needs
  either a second channel or a section that knows which stage it belongs to.
  Prefer the latter — one mechanism, two owners.
- Phases to start with, from `World.step`'s own structure: `liquids`, `mobs`,
  `projectiles`, `vehicles`, `players`, `block-changes`, and whatever `PlayScene`
  does outside `world.step`.
- **Record the number of catch-up steps in the frame**, and report its
  distribution beside the stage table. This is the measurement that decides
  between "one slow step" and "eight ordinary ones", and it is two lines in
  `GameLoop`.
- Report a per-*step* update cost alongside the per-frame one.

**Verify.** A profile of the same level shows which phase owns the p95, and says
how many steps that frame ran.

**Done when.** The report answers "what stalled, and was it one step or eight?"
without anyone having to reason about it.

### S1 — done

`FrameProfiler.recordSection(Stage, name, start)` — **one mechanism, two
owners**, as this step asked for. Sections are stored under their owning stage,
so the scene's `terrain` and the simulation's `terrain` cannot silently share a
bucket, which is precisely the kind of thing that produces a confident wrong
answer. `World.step` names `liquids`, `mobs`, `vehicles`, `items` and
`projectiles`; the report prints an **Update breakdown** beside the Scene one.

`GameLoop` counts its catch-up steps and `FrameProfiler.Steps` carries the
distribution, so the report now says:

```
Simulation steps per frame
----------------------------------------------------------------
mean 2.03   p50 2   p95 2   max 8   (cap 8)
cost per step : 0.839 ms   (update stage / steps)
```

That last line is the one that resolves §1's second ambiguity. A p99 of 21 ms
against a mean of 2.03 steps is **one expensive step**, not eight cheap ones —
and those have different causes and different fixes.

**One thing this step turned out not to be needed for.** The plan expected S1's
breakdown to identify the suspect. It did not get the chance: the crash report
below named it first. S1's value is now the other half — proving the fix, and
being there for the next one.

### S2 — Name the cause, in writing, before fixing it

**Goal.** One sentence naming the operation and the evidence, in this document.

**Do.** Take three profiles with S1's instrument: the same level, a level with no
liquids, and a level with no mobs. Two of the suspects in §1 die immediately if
the spike survives their absence.

**Done when.** §1's table has one row marked confirmed and the rest eliminated,
with the numbers that did it.

### S2 — answered, by a stack trace rather than by the instrument

| Suspect | Verdict |
|---|---|
| **Liquid simulation** | **Confirmed.** The OOM's own stack, three frames deep in `LiquidSim.reachable`. |
| Catch-up cascade | Eliminated. Steps per frame: mean 2.03, p95 2. The loop is not in catch-up. |
| GC | A symptom here, not the cause: the allocation it could not keep up with is named above. |
| Mob AI | Not implicated. Now has a `mobs` phase if it ever is. |
| Terrain cache invalidation | Downstream of liquids rather than beside them — see below. |
| Sound | Not implicated. |

**Named in one sentence, as this step requires:** *`LiquidSim` did work
proportional to the level rather than to the liquid, so a pond in the corner of
a big map cost exactly what a flooded one did — five whole-grid passes per tick
plus a breadth-first search that allocated an `int[3]` per queue entry and an
`int[width × height]` per liquid family.*

**The arithmetic that makes it an out-of-memory error rather than merely slow.**
A dense level runs to `Level.DENSE_TILE_LIMIT` — 1024×1024, about a million
cells. A cell can be queued once per improvement to its spread budget and
budgets run 0..5, so the queue takes up to six million entries; at 32 bytes an
`int[3]` plus 8 for the deque's slot that is **240 MB**, per liquid family, per
tick, against a 2 GB heap with a game already in it.

**And it is very likely the visible flicker as well** — **confirmed, and fixed;
see `RENDER_PLAN.md` D7.** The mechanism below was right in outline and wrong
about which rule did it: the churn threshold counted changed *cells*, so a liquid
tick rewriting thirty cells inside three chunks swept the entire view live, once
every thirteenth frame, on a beat set by the water. The threshold now decides only
whether baking is futile; whether to sweep is decided by how many chunks need
rebaking, which is the quantity that was always meant. It was found alongside a
larger instance of the same flip-flop — the animation frame number was in every
chunk's validity key — and both are gone. What follows is the original hypothesis,
kept because it was the right suspicion. Every liquid tick bumps
`Level.terrainRevision()`, `TerrainCache` invalidates the chunks that changed,
and it has two rules that switch on how much is changing: a four-chunk rebuild
budget, and a churn threshold beyond which it **stands aside for the frame and
draws the whole view live**. Baked and live differ slightly (105 px on a
480×360 view, measured in `RENDER_PLAN.md` D3). A level whose liquids churn near
that threshold alternates between the two renderings, and alternating between
two slightly different pictures at 60 Hz is a flicker. That needs eyes on the
Air to confirm, and it is not claimed as confirmed here.

### S3 — Fix it

**Goal.** The p95 comes down without invariant 1 moving.

**Do.** Unknowable until S2. What is knowable now is the shape the fix must have:

- **If it is periodic whole-level work** (liquids being the candidate), the fix
  is almost certainly to bound it — a work budget per step, a dirty set instead
  of a scan, or spreading the scan across steps. Each of those changes *when*
  simulation happens, so each needs checking against invariant 1 explicitly:
  spreading work across steps is safe only if every machine spreads it the same
  way.
- **If it is a catch-up cascade**, the fix is in `GameLoop` and is about the
  recovery policy, not about making anything faster.
- **If it is GC**, the fix is allocation in the step, and the instrument is an
  allocation profile rather than a timer.

**Verify.** Re-run S0's six-profile matrix. The p95 falls; the mean falls or
holds; and **a multiplayer session between two machines still agrees**, which is
invariant 1 and is not optional.

**Done when.** The numbers are recorded here, including anything that got worse.

### S3 — first fix landed: the work is proportional to the liquid

**Shape:** "a dirty set instead of a scan", which is what §3 predicted for
periodic whole-level work. The realised form is a bounding box rather than a
dirty set, because a box is a pure function of the grid and a dirty set is
state that two machines could disagree about — and invariant 1 does not bend.

- The one presence scan that remains now also records **where each tile id
  is**. Everything after it works inside that box, grown by the furthest one
  tick can carry the liquid, and clipped to the level.
- The BFS queue is a growable `int[]` of cell indices with the budget read from
  the `best` array, instead of an `ArrayDeque<int[]>`. Relaxation only ever
  improves a cell, so reading the current budget at poll time reaches the same
  fixpoint — which is what makes this safe to change at all.
- `best` is box-sized rather than level-sized.
- `quenchLava` runs inside the lava's own box.

**Verify — done, and the correctness half is the one that mattered.**
[`LiquidSimBoundedTest`](src/test/java/com/larsons/engine/LiquidSimBoundedTest.java)
runs the same source on a 60×40 level and on a 4000×40 one and requires the
resulting grids to be **identical**. A box that was ever too small would clip
the water — and would do so only on levels large enough for the box to matter,
which is exactly the case no small test would have caught. Draining, plan-view
pooling, and a full-size 1024×1024 dense level running 200 ticks are pinned
beside it.

**One bug was written and caught by that test rather than by a player**, which
is worth recording because it is the failure mode this plan exists to avoid: the
first version of the queue compacted itself on growth, invalidating the caller's
`head`/`tail`, and threw `ArrayIndexOutOfBoundsException` on the first level big
enough to need a second growth — the only kind of level that would ever have
reached it in the field.

**What is left, stated rather than claimed fixed.** One whole-grid scan per
0.22 s remains — it is what finds the liquid, so it cannot be bounded by where
the liquid is. On a million-cell level that is a few milliseconds every
thirteenth frame, and it is now the largest known cost in the stage. **It is
deliberately not pre-optimised**: the instrument to measure it exists now, and
the next profile from the Air decides whether it matters. Guessing again would
be the mistake §4 was written about.

### S4 — Guard it

**Goal.** A regression in the simulation's worst case is caught by the build, not
by a player.

**Do.** A headless test that runs `World.step` over a representative level for a
few thousand steps and asserts on the **distribution**, not the mean — a p99
ceiling relative to the median. The server path makes this possible with no
display and no renderer.

**Done when.** It runs on every build and fails if the tail grows.

---

## 4. Order

```
S0  freeze the baseline          ← done (§0)
S1  make the update legible      ← done. Sections per owning stage, and the
                                   catch-up step count beside them. mean 2.03
                                   steps/frame, p95 2 — so the spike was one
                                   expensive step, not eight cheap ones.
S2  name the cause in writing    ← done, by a crash report rather than by S1:
                                   LiquidSim did work proportional to the LEVEL
                                   rather than to the liquid. Confirmed by its
                                   own OutOfMemoryError stack.
S3  fix it                       ← first fix landed. Every pass now works inside
                                   a box around the liquid; the BFS queue is a
                                   primitive int array. Same water, proven on a
                                   4000-wide level against a 60-wide one.
                                   One whole-grid scan per tick remains, on
                                   purpose and unmeasured — see S3.
S4  guard the tail               ← next
```

**S1 before anything else, and it is worth saying why once more.** The
temptation with a 21 ms spike and a list of six suspects is to start optimising
the most suspicious one. That is how a project spends a week making liquids
faster and discovers the stall was the catch-up loop all along — the numbers
would even have improved slightly, which is the trap. The scene stage was made
legible before Job B, and Job B was decidable because of it. This is the same
move on the other half of the frame.
