# Simulation Plan — finding and fixing the update stall

**Status:** Written 2026-08-03, from six frame profiles taken on the M1 Air
during `RENDER_PLAN.md` B10. Nothing has been fixed yet and nothing here claims
to know the cause. **Step S1 exists because the instrument cannot currently
answer the question**, and every step after it is contingent on what S1 finds.

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

### S2 — Name the cause, in writing, before fixing it

**Goal.** One sentence naming the operation and the evidence, in this document.

**Do.** Take three profiles with S1's instrument: the same level, a level with no
liquids, and a level with no mobs. Two of the suspects in §1 die immediately if
the spike survives their absence.

**Done when.** §1's table has one row marked confirmed and the rest eliminated,
with the numbers that did it.

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
S1  make the update legible      ← START HERE. Everything else waits.
S2  name the cause in writing
S3  fix it
S4  guard the tail
```

**S1 before anything else, and it is worth saying why once more.** The
temptation with a 21 ms spike and a list of six suspects is to start optimising
the most suspicious one. That is how a project spends a week making liquids
faster and discovers the stall was the catch-up loop all along — the numbers
would even have improved slightly, which is the trap. The scene stage was made
legible before Job B, and Job B was decidable because of it. This is the same
move on the other half of the frame.
