# Steam Launch Plan

**Status:** Living document. Last substantive review: 2026-07-28.
**Author:** Larson Sonderman
**Purpose:** A concrete, honest path from the current codebase to a product on
Steam — what exists, what's missing, what order to build it in, and what it
costs.

This document is deliberately blunt about gaps. That is not a criticism of the
work; it is the point of a plan. The engine is ~61k lines built in about a
month, and the parts that are done are genuinely done. What follows is the
distance between "impressive codebase" and "thing a stranger pays for."

**Companion document:** [`RENDER_PLAN.md`](RENDER_PLAN.md) is the plan of record
for GPU acceleration — the ordered steps for GPU post-processing (Job A), GPU
scene rendering (Job B) and eight-point camera rotation (Job C). It supersedes
§5.4 and Appendix A of this document on anything to do with the renderer.

---

## 1. Where the project actually stands

Measured on 2026-07-28 at commit `10ae9e8`.

| Metric | Value |
|--------|-------|
| Main source | 61,557 LOC across 205 files |
| Test source | 16,749 LOC across 54 files |
| Runtime dependencies | **0** (JDK only — Java2D / AWT / Swing / sockets) |
| History | 100 commits, 2026-06-25 → 2026-07-27 |
| Largest single file | [`CreativeScene.java`](src/main/java/com/larsons/engine/demo/CreativeScene.java) — 6,443 LOC |
| Art assets (png/jpg) | **0** |
| Audio assets (wav/ogg/mp3) | **0** |
| Bundled resources | 8 files (JSON game types, one sample level, `.gitkeep` placeholders) |

### What is genuinely strong

- **The creative mode is the crown jewel.** `CreativeScene` is the biggest file
  in the project for a reason: 80+ blocks, simulated liquids, lights, mobs,
  items, decor, doors, cutscenes, a sound editor covering ~2,000 hooks, mini-game
  setup, and character creation — with a `+` entry in every category that lets a
  player define brand-new content that persists to the game type's `custom.json`
  and reloads with it.
- **Content sharing already has a format.**
  [`GamePackage`](src/main/java/com/larsons/engine/config/GamePackage.java)
  defines `.larsonsengine` — a single shareable bundle of a whole game type,
  auto-imported when found. [`ShareJar`](src/main/java/com/larsons/engine/core/ShareJar.java)
  builds a distributable jar. This is the foundation Steam Workshop needs.
- **Authoritative-server multiplayer works**, with deterministic
  [`PlayerPhysics`](src/main/java/com/larsons/engine/sim/PlayerPhysics.java)
  shared between client prediction and server simulation.
- **Zero-dependency portability.** The engine really does run on a bare JDK.
  That is a real engineering achievement and worth protecting (see §5.3 for the
  one place it has to bend).
- **Substantial test coverage exists** — 16.7k lines of it.

### What does not exist yet

- No art. No audio files. Every visual is drawn at runtime from primitives
  (`fillRect`, `drawOval`, `GradientPaint`); every sound is synthesized PCM.
- No gamepad support anywhere — keyboard and mouse only, via AWT listeners.
- No fullscreen, no resolution options, no DPI scaling, no multi-monitor
  handling. [`GameWindow`](src/main/java/com/larsons/engine/core/GameWindow.java)
  is a fixed-size `JFrame` with `pack()`.
- No packaging. `./gradlew jar` produces a plain jar that requires a
  preinstalled Java 21. There is no `jpackage`/`jlink` configuration.
- No `LICENSE` file.
- No CI. There is no `.github/workflows/` — those 16.7k lines of tests run only
  when someone types `./gradlew test`.
- No GPU rendering. See Appendix A — this matters more than it looks.

---

## 2. The strategic question: what are we selling?

Steam is a storefront for *products*, and a product needs a one-sentence pitch.
"A 2D game engine" is not one, because the buyer can't tell what they get.

It's true that tool-shaped and engine-shaped things sell on Steam. It's worth
understanding *why* each one works, because the reasons are not
interchangeable.

| Product | Why it works | Does that apply here? |
|---------|--------------|-----------------------|
| **Aseprite** (~$20, pixel art editor) | Ruthlessly focused on one job. Doesn't ship art because making art *is* the product. Refined since 2001. Its entire value is interface polish. | Partly. Our editor is broad, not focused, and lives inside the game rather than being a standalone tool. |
| **Garry's Mod** ($10, sandbox) | Built on Source and **inherited Valve's entire art library** — props, ragdolls, maps. Funny within five minutes. Community gamemodes became the long tail. | Not yet. GMod's superpower was shipping with a mountain of content. We ship with zero. |
| **RPG Maker** (~$80, no-code game maker) | Targets people who want to make a game *without programming*. Ships tilesets/sprites/music in the box. Sells asset packs as DLC. | **Yes — this is the closest comparable.** |

**RPG Maker is the model to study.** Same audience (wants to make a game, can't
or won't code), same shape (in-app editor + templates + bundled assets), same
viable business model (base product plus asset-pack DLC).

And we have something RPG Maker does not: **online multiplayer**, already
working.

### The pitch

> **Make 2D games without writing code — then play them online with friends.**

### The reframe that matters

The five genre modes — platformer, top-down adventure, auto battler, deckbuilder
([Council of Six](README.md#council-of-six-deckbuilding-board-game-2-6-online)),
evolution sim — currently read as unfinished scope creep. Under the creation-tool
framing they become **genre templates**, which is exactly how RPG Maker and
GameMaker position their starters.

That converts a large body of already-written code from liability to feature.
No rewrite required — only a change in how the product presents itself.

---

## 3. Recommended product shape

**Ship a flagship game first. Ship the creation tool second.**

The flagship game is the plan of record (pixel art and sound assets are already
committed to). This ordering is deliberate:

1. **A finished game is the only honest proof the engine works.** Nobody buys a
   game-making tool whose author hasn't shipped a game with it.
2. **A game is far easier to market than a tool.** It has screenshots, a
   trailer, a genre, and a hook. Store pages for tools are a harder sell.
3. **Building it will surface every engine bug that matters**, under real
   content load rather than procedural placeholder shapes.
4. **The finished game becomes the tool's demo content** — the sample project,
   the trailer footage, and the "look what this made" proof, all at once.
5. **It de-risks the tool.** If the game sells, the tool has an audience. If it
   doesn't, better to learn that before productizing an editor.

Two viable structures, decide later:

- **Two products.** Game ships first at a game price. Creation tool ships later
  as a separate SKU (the RPG Maker path).
- **One product.** Game ships with the creation mode as a headline feature (the
  Terraria / *Mario Maker* path). Simpler to market, one store page, one launch.

There is no need to decide today. The work in §6 is identical either way until
Phase 4.

---

## 4. Non-negotiable blockers

These will stop a Steam launch outright. Everything else is a quality question;
these are yes/no gates.

### 4.1 Players cannot be asked to install Java

Right now the game is a jar requiring a preinstalled Java 21. Steam users
double-click and expect to play. "Install Java first" produces refunds and
angry reviews.

**Fix:** `jpackage` (ships with the JDK) bundles the app *plus a trimmed copy of
the Java runtime* into a native `.exe` / `.app` / Linux binary. The player never
learns Java is involved.

Two gotchas that catch first-time shippers:

- **Windows** shows a SmartScreen "unknown publisher" warning unless the binary
  is signed with a code-signing certificate (a few hundred dollars per year).
  Not strictly fatal, but it visibly costs conversions and trust.
- **macOS** will *refuse to open the app at all* unless you're enrolled in the
  Apple Developer Program ($99/yr) and have run the build through
  **notarization** (uploading it so Apple can scan and bless it). There is no
  workaround. If macOS is out of scope for v1, that's a legitimate choice — say
  so early rather than discovering it at submission.

### 4.2 Steam integration requires native code

Achievements, cloud saves, friends/lobbies, and **Workshop** all live behind
Steamworks — Valve's C library. Java must call out to non-Java code to reach it
("native bindings"), which is precisely what
[requirement #4](README.md#design-goals) avoids.

**Resolve it by layering, not by compromising:**

- The **engine** stays pure JDK. Requirement #4 survives intact.
- The **game/product layer** defines a small `PlatformServices` interface
  (unlock achievement, save to cloud, publish to Workshop, open overlay).
- One implementation wraps `steamworks4j`; a no-op implementation is used for
  non-Steam builds, tests, and the headless server.

This keeps the engine's best property while unblocking the store, and it means
the DRM-free build is the same code with a different implementation injected.

### 4.3 Zero shippable assets

This is the single largest gap between the current repo and a product, and it is
not a code problem. **Already being addressed** — pixel art and sound are the
next planned work.

Worth stating the bar explicitly, because it differs by product:

- **For the flagship game:** enough art and audio to carry a trailer and a
  storefront. Steam is a visual store; capsule art and screenshots do most of
  the selling.
- **For the creation tool:** a *starter library* — enough tilesets, sprites, and
  sounds that a brand-new user can build something that looks decent within
  twenty minutes. RPG Maker's entire business rests on this, and it doubles as
  a DLC revenue stream later.

The [skin/texture override system](README.md#skins-texture-overrides)
(`SkinStore`, `Skins`, the `skins/` directories) is already the hook these
assets plug into. The plumbing is done; the content is not.

### 4.4 No window management

`GameWindow` is a fixed-size `JFrame`. Players expect fullscreen (ideally
borderless), a resolution picker, and correct behavior on high-DPI and
multi-monitor setups. Reviews reliably punish the absence.

---

## 5. Secondary gaps

### 5.1 Controller support

None exists. AWT has no gamepad API, so this needs a library (JInput, an SDL
binding, or Steam Input via Steamworks).

**Priority depends on the product:**

- **Creation tool:** low. Aseprite has no gamepad support and nobody minds. An
  editor is legitimately mouse-and-keyboard.
- **Flagship game:** high, especially for anything real-time. Steam Deck is a
  meaningful share of indie revenue, and "Deck Verified" *requires* full
  controller support.

### 5.2 Netcode hardening

[`Protocol`](src/main/java/com/larsons/engine/net/Protocol.java) is
line-delimited JSON over raw sockets — clean and debuggable, and fine for
friends-and-family play. Shipping it commercially adds obligations:

- **Validate every client message server-side.** Never trust a client. This is
  the difference between "my friends play it" and "strangers play it."
- **Rate-limit** connections and messages.
- **NAT traversal.** Direct IP + port means players must configure port
  forwarding — a permanent support burden. Steam's networking (lobbies and NAT
  punch-through) solves this and is another argument for §4.2.
- **Live-ops reality:** shipping multiplayer means owning server costs and
  player-facing outages indefinitely.

### 5.3 Engineering hygiene

Both of these are cheap and unblock everything else.

- **CI.** 16.7k lines of tests that nothing runs automatically. A GitHub Actions
  workflow running `./gradlew test` on push is the best quality-per-hour trade
  available right now.
- **`LICENSE`.** There isn't one. Decide deliberately, and note that roughly
  half the commit history is AI-assisted (see §7 on Steam's disclosure
  requirement).

### 5.4 Unmeasured performance ceiling

[`ParallelRows`](src/main/java/com/larsons/engine/graphics/shader/ParallelRows.java)
asserts in a comment that a 1280×720 frame stays "well within a 120 FPS budget."
That is plausible for one cheap pass and unverified for a real chain.

**Before committing to the Java2D renderer for launch, measure:** the actual
active shader chain (bloom + lighting + chromatic aberration), at 1080p and
1440p, on a low-end CPU, with a realistic scene. If it holds, the CPU path
carries the launch and the GPU backend stays a roadmap item. If it doesn't,
see Appendix A for what the GPU port actually costs — it is more than the
roadmap implies.

**The instrument now exists.** The
[frame profiler](README.md#frame-profiler-where-the-time-actually-goes)
(F3 in game, or `-Dlarsons.profile.seconds=30` for a scripted run) splits a
frame into `update` / `scene` / `shaders` / `present` / `idle`, breaks the
shader chain down per pass, and records the machine — cores, display scale,
Java2D pipeline, refresh rate — alongside the timings. Run it on the weakest
target machine at each resolution and paste the reports here; that is this
item's deliverable. Two things it is built to stop:

- **Reading a HiDPI laptop wrong.** A "1280×720" window on a Retina panel is
  2560×1440 real pixels, so full-screen CPU passes cost 4× what the window
  size implies. The report states the multiplier rather than leaving it to be
  discovered.
- **Funding the wrong job.** `scene` and `shaders` are the budgets the two
  candidate GPU projects compete for, and they are reported separately with a
  verdict naming which — including "neither, there is headroom".

---

## 6. Phased roadmap

### Phase 0 — Cheap wins (days)

- [ ] Add `.github/workflows/ci.yml` running `./gradlew test` on push and PR.
      (The shader compile check runs there too, and skips where the runner has
      no GL context — install Mesa on the runner to have it actually check.)
- [ ] Add a `LICENSE` file.
- [x] Build the instrument: a per-stage frame profiler that separates scene
      drawing from post-processing and records the machine alongside the
      timings (`FrameProfiler` / `FrameReport`, F3 in game).
- [ ] Benchmark the real shader chain at 1080p/1440p on low-end hardware.
      Record the numbers here. Run:
      `java -Dlarsons.profile=true -Dlarsons.profile.overlay=false -Dlarsons.profile.seconds=30 -jar <jar>`
- [ ] Prototype a `jpackage` build on Windows. Confirm it launches on a machine
      with no JDK installed.
- [ ] Correct the overstated claims in `README.md` (see Appendix B).

### Phase 1 — The flagship game (the main event)

- [ ] Choose the game and write its one-sentence pitch. Everything below
      depends on this.
- [ ] Pixel art: tilesets, characters, items, UI, effects.
- [ ] Audio: music, SFX, and replace or supplement the synthesized PCM.
- [ ] Build the game *in-engine using creative mode* — this is the dogfooding
      that finds the real bugs.
- [ ] Fullscreen, resolution options, DPI, multi-monitor (§4.4).
- [ ] Settings menu: video, audio volumes. **Key rebinding is done** — every
      action is bindable to any key or mouse button from the *Controls (Key
      Binds)* screen, saved in `config/keybinds.json` (see the README's
      [Custom key binds](README.md#custom-key-binds-rebind-anything)).
- [ ] Save system, with an eye toward Steam Cloud later.
- [ ] Controller support if the game is real-time (§5.1).

### Phase 2 — Make it shippable

- [ ] `jpackage` builds for every target platform.
- [ ] Windows code-signing certificate.
- [ ] Apple Developer enrollment + notarization pipeline — *or* an explicit
      decision to skip macOS for v1.
- [ ] `PlatformServices` interface + `steamworks4j` implementation + no-op
      implementation (§4.2).
- [ ] Achievements and Steam Cloud saves.
- [ ] Crash/error reporting — you cannot debug a stranger's machine.
- [ ] Netcode hardening if multiplayer ships in v1 (§5.2).

### Phase 3 — Steam storefront

Start this **early and in parallel** — wishlists compound over time.

- [ ] Pay the $100 Steam Direct fee; complete the tax and banking paperwork
      (this takes longer than expected — start it before you need it).
- [ ] Capsule art. Highest-ROI art asset you will make; budget real effort.
- [ ] Trailer. Second highest.
- [ ] Store page copy, screenshots, system requirements.
- [ ] **Complete Steam's AI content disclosure honestly** (§7).
- [ ] Publish the store page to start accumulating wishlists.
- [ ] Build a demo; sign up for the next **Steam Next Fest**.
- [ ] Playtesting with people who are not you.

### Phase 4 — The creation tool

Only after the game ships and its reception is known.

- [ ] Decide: separate SKU, or headline feature of the game (§3).
- [ ] Steam Workshop integration, built on `.larsonsengine`
      (`GamePackage` is already most of the way there).
- [ ] Editor UI polish — this is what the product will be judged on (§8).
- [ ] Starter asset library shipped in the box.
- [ ] Documentation and tutorials for non-programmers.
- [ ] Asset-pack DLC as an ongoing revenue stream.

### Phase 5 — Post-launch

- [ ] Patch cadence, community management, Workshop curation.
- [ ] GPU renderer backend, *if* §5.4's measurements demand it (Appendix A).

---

## 7. Steam operations primer

For a first-time shipper, the things that aren't obvious:

- **Steam Direct fee:** $100 per title, recouped after $1,000 in revenue.
- **Revenue split:** Valve takes 30% (improving at high revenue tiers most indie
  titles never reach).
- **Wishlists are the whole game.** Steam's launch-visibility algorithms weight
  wishlist count heavily. A store page live six months before launch massively
  outperforms one published at launch. **Get the page up early**, even with
  placeholder-quality material you intend to replace.
- **Capsule art and trailer** do the overwhelming majority of the selling. Most
  shoppers never read the description.
- **Steam Next Fest** is a free, Valve-run demo festival and one of the best
  wishlist drivers available to an unknown title.
- **Reviews are permanent.** A rough launch leaves a scar that discounts and
  patches don't erase. Shipping late beats shipping broken.
- **AI content disclosure is mandatory.** Steam requires declaring AI-generated
  content at submission — this covers code, art, and audio. Roughly half of this
  repository's commits are AI-assisted. Answer accurately; it is a routine
  checkbox, and misrepresenting it is a genuine compliance problem.
- **Paperwork lead time.** Tax forms and bank verification can take weeks. Start
  before the game is ready.

### Cost summary

| Item | Cost | Required? |
|------|------|-----------|
| Steam Direct | $100/title | Yes (recouped at $1k revenue) |
| Apple Developer Program | $99/yr | Only if shipping macOS |
| Windows code-signing cert | ~$100–400/yr | Strongly recommended |
| Pixel art & audio | Time or money | Yes |
| Capsule art & trailer | Time or money | Yes |

---

## 8. Risk register

| Risk | Severity | Mitigation |
|------|----------|------------|
| **Editor UI polish.** Creation tools live or die on interface quality. Aseprite won on it; *Pixel Game Maker MV* shipped rough and carries permanently mixed reviews. | High | Ship the flagship game first. Treat editor UX as a dedicated Phase 4 workstream, not a byproduct. |
| **Scope.** Five genres, an engine, and a tool is a lot of surface for a solo developer. | High | Phase 1 forces a single focus. The other modes stay parked as templates — maintained, not extended. |
| **Java2D performance ceiling.** May not hold the frame budget at modern resolutions. | Medium | Measure in Phase 0, before it can become a launch-week surprise. |
| **Multiplayer without a player base.** Modes needing matchmaking (auto battler) die without day-one critical mass. | Medium | Don't lead with a multiplayer-only mode. Ensure the flagship is compelling solo or with friends. |
| **Marketing claims outrunning reality.** The README's habit of marking aspirational work "✅ Implemented" (Appendix B) becomes a refund-and-negative-review problem on a store page. | Medium | Store bullets describe only what a player experiences. Fix the README now to build the habit. |
| **Remaining work is the unglamorous kind.** Packaging, signing, settings menus, store ops. It will likely take longer than the engine did. | Medium | Phased plan; accept that the pace of the last month won't repeat. |
| **Codebase familiarity.** ~61k lines, roughly half AI-authored, written fast. | Medium | Before launch, deeply understand what breaks in front of players: netcode, save/load, the render loop. |

---

## Appendix A — The shader system, precisely

This came out of a trace of the GLSL code path and materially affects the
roadmap, so it's recorded here rather than left in chat history.

**GLSL never executes. The CPU path is the only path.**

The per-frame path is
[`Java2DRenderer.present()`](src/main/java/com/larsons/engine/graphics/Java2DRenderer.java)
→ [`ShaderChain.apply()`](src/main/java/com/larsons/engine/graphics/shader/ShaderChain.java)
→ `pass.apply(...)` → `ParallelRows.run(...)`. `glsl()` is never called there.

- `ShaderPass` declares `glsl()` and `apply()` as two unrelated members; nothing
  connects them.
- The only `glsl()` call site in main code is
  [`Shaders.writeGlsl`](src/main/java/com/larsons/engine/graphics/shader/Shaders.java),
  which does `Files.writeString(frag, p.glsl())` — text in, text on disk.
- There is no GL context anywhere: no `System.loadLibrary`, no
  `glCreateShader`/`glCompileShader`/`glUseProgram`, no LWJGL/JOGL, and exactly
  one `Renderer` implementation (`Java2DRenderer`). `build.gradle.kts` has zero
  non-test dependencies.

**"CPU fallback" is a misnomer.** There is no GPU probe and no fallback:
`beginFrame()` branches on `shaders.hasPasses()` — whether passes are configured
— not on hardware availability. The CPU path is unconditional.

> **Half-answered 2026-08-03 (RENDER_PLAN B9).** There is now a real GPU probe
> and a real fallback — *for scene rendering*. `Backends` asks for an OpenGL 3.3
> core context at startup, uses the GL backend when it gets one, and uses Java2D
> with a stated reason when it does not; `-Dlarsons.render.backend` overrides,
> and every frame report names the backend and the driver.
>
> **The objection above still stands for post-processing, which is what it was
> about.** The shader chain runs on the CPU unconditionally, and the GL backend
> prints to stderr, once, that an attached chain is not being executed rather
> than dropping the passes quietly. GPU shader execution is Job A in
> `RENDER_PLAN.md`, and it is scheduled after the scene backend on purpose: with
> the scene already drawn by GL the frame is a GPU texture and the ping-pong is
> nearly free, whereas doing it first costs two full-frame transfers per frame.
> The README was corrected in the same commit as B9 and says exactly this — a
> multithreaded CPU post-processing pipeline that ships verified GLSL as a port
> target, and two rendering backends with a probe between them.

**Consequences for this plan:**

1. **~~The GLSL is unverified text.~~ Verified 2026-08-02 — and it is fine.**
   `ShaderCompileTest` now compiles every pass through a real driver (LWJGL,
   test-only, so the zero-runtime-dependency promise is untouched) and links
   each against the shared fullscreen vertex shader. **All ten shaders build:**
   the nine in `allBuiltIns()` plus `LightingPass`, which is tested separately
   because it is not in that list and is the one with array uniforms and a
   uniform-bounded loop. The test skips rather than fails where no GL context
   exists, so headless CI is unaffected, and it carries a deliberately broken
   shader as a negative control — which earned its keep immediately by
   catching that the first "invalid" shader written for it was in fact valid
   (`.qqqq` is the `stpq` swizzle set).

   So this worry was unfounded. The remaining risk in this appendix is item 2,
   which is about *behaviour*, not syntax, and is untouched by compiling.
2. **~~Parity is not guaranteed and cannot be checked.~~ Checked 2026-08-02 —
   and it holds.** `ShaderParityTest` runs each pass both ways over the same
   frame and compares every channel of every pixel. Mean absolute channel
   error, out of 255:

   | pass | error |
   |------|-------|
   | pixelate, wave, chromatic_aberration, color_grade, invert | **0.00** |
   | scanlines | 0.04 |
   | vignette | 0.32 |
   | grayscale | 0.47 |
   | bloom | 3.58 |

   So eight of the nine are the same picture to within rounding, and bloom —
   the one that says in its own javadoc it is an approximation — is much
   closer than that wording suggests. There is no hidden drift. The suite also
   checks that `uStrength` at zero returns every frame untouched, which is the
   cheapest way to catch a uniform that never reaches its shader.
3. **~~The roadmap's GPU backend is not a cheap port.~~ Revised.** This
   followed from items 1 and 2, and both have since been answered: every
   shader compiles on a real driver, and every one behaves like its CPU twin.
   The "budget debugging time for every pass" warning has been spent and cost
   nothing. Better than a `glslang` step, CI now compiles and *executes* the
   shaders, which is a stronger check than syntax validation.

   What remains for a GPU backend is therefore the part that was never in
   doubt: the plumbing. A context, a framebuffer, ping-pong between two
   textures, and the uniform binding — all of which
   `src/test/java/com/larsons/engine/GlShaderHarness.java` already does, in
   about two hundred lines, because parity testing needed exactly the same
   machinery. That harness is the shape of the backend.

None of this is a defect in the CPU pipeline, which works. The architecture is
sound and the port target is real. It is simply less finished than the
documentation suggests.

---

## Appendix B — Claims to correct in the README

Fixing these now builds the discipline that keeps store-page copy honest.

| Location | Claim | Reality | Status |
|----------|-------|---------|--------|
| Requirement #5 table row | "✅ Implemented … **GLSL-first** … so effects run everywhere today and on a GPU backend without porting" | The CPU path is implemented and works. The GLSL is uncompiled source with no execution path. "Without porting" is untested. | **Corrected 2026-08-03.** The row now cites `ShaderCompileTest` and `ShaderParityTest` by their numbers and says plainly that post-processing executes on the CPU today. |
| Same row | "a semantically identical multithreaded CPU fallback" | `BloomPass`'s own javadoc says the GLSL is an approximation, not identical. And it isn't a *fallback* — it's the only path. | **Corrected 2026-08-03.** "Semantically identical" and "fallback" are both gone; the row states the measured error instead of asserting equivalence. |
| Intro paragraph | "a **shader system** (GLSL-first post-processing with a CPU fallback that runs anywhere)" | Reads as though GPU shading happens. Accurate framing: a multithreaded CPU post-processing pipeline that ships hand-written GLSL alongside each effect as a port target. | **Corrected 2026-08-03**, in that wording. The paragraph now also names the two *rendering* backends, which is the claim that did become true (RENDER_PLAN B9). |
| Requirement #4 row | "shader execution … all in-engine" | Accurate as written — worth keeping, since it's the honest one. | Kept, and extended to say why the optional GL jar does not weaken it. |
| Roadmap preamble | "the per-pass GLSL has never been compiled by anything" | Was true when written; `ShaderCompileTest` compiled all ten on a real driver on 2026-08-02. | **Removed 2026-08-03.** A stale warning is worse than none — it argues against work that has already been done. |

The javadoc in
[`ShaderPass`](src/main/java/com/larsons/engine/graphics/shader/ShaderPass.java)
and [`Renderer`](src/main/java/com/larsons/engine/graphics/Renderer.java) was
already accurate ("the engine's default backend executes the CPU side"). Only
the README's summary framing overstated, and it no longer does.

**The rule this table is really for.** Every row above was fixed in the commit
that made it fixable, not before — B9's step in `RENDER_PLAN.md` says so in as
many words, and the one claim that is *still* an overstatement (GPU
post-processing) is still described as future work rather than quietly upgraded
along with its neighbours. That is the discipline this appendix exists to
build, and it costs something to keep exactly once per release.

---

## Quick reference: what to do next

1. **Phase 0** — CI, license, benchmark, `jpackage` prototype. Days of work,
   unblocks everything.
2. **Phase 1** — the flagship game with real pixel art and sound. This is the
   main event and the plan of record.
3. **Phase 3 in parallel** — get a Steam page up early; wishlists compound.
4. **Phase 4** — productize the creation tool once the game has proven the
   engine and found an audience.
