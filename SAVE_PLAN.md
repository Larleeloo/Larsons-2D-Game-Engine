# Save Plan — the run that survives the session

**Status:** Written 2026-08-17 as an analysis of what the engine is missing
that is *essential to playing it*, rather than to building it. **All six jobs
have landed** — O, P, C, D, U and A — with the tests each one names. §12 at the
end records what the build taught that the plan did not know, including two
concurrency bugs the tests found and one measurement that changed a design.
The finding is one sentence long:

> **The engine persists the world and never persists the run.**

Everything a level *is* can be written to disk and read back — terrain,
entities, containers, decor, cutscenes, stat rules, per-level settings, the
format it was built in. Nothing a player *does* can. Inventory, health, mana,
stamina, position, the character you chose, the counters every stat rule is
measured against, the time of day: all of it is constructed fresh on entry and
dropped on exit, every time, with no prompt and no slot to put it in.

This document is written the way [`RENDER_PLAN.md`](RENDER_PLAN.md) and
[`HEIGHT_PLAN.md`](HEIGHT_PLAN.md) are: the evidence first, then what is
already correct and must not be rewritten, then invariants, then numbered jobs
each of which states what must be true before it starts and the instrument that
proves it worked.

---

## 0. The evidence

### 0.1 What entering a level destroys

`PlayScene.onEnter()` builds a run from nothing. Every line below runs on
*every* entry — starting a level, returning from the menu, coming back from
creative mode:

| line | statement | what it discards |
|---|---|---|
| [`PlayScene.java:382`](src/main/java/com/larsons/engine/demo/PlayScene.java#L382) | `stats = new PlayerStats();` | every tracked counter — blocks mined, distance, kills, deaths, parries |
| [`PlayScene.java:414`](src/main/java/com/larsons/engine/demo/PlayScene.java#L414) | `inventory = new Inventory(…)` | the entire inventory and hotbar |
| [`PlayScene.java:425`](src/main/java/com/larsons/engine/demo/PlayScene.java#L425) | `me = new PlayerState(…, level.spawnX, level.spawnY)` | position, velocity, elevation |
| [`CharacterProfile.java:121`](src/main/java/com/larsons/engine/character/CharacterProfile.java#L121) | `s.health = s.maxHealth;` | health, mana, stamina, ultimate charge |
| [`World.java:95`](src/main/java/com/larsons/engine/world/World.java#L95) | `private double timeOfDay = 0.15;` | the day/night clock — every run begins at the same morning |

There is no code path that reads any of these back. Nothing in the source
names the concept either: the only occurrence of "checkpoint" is an escort
payload's waypoint
([`MiniGame.java:559`](src/main/java/com/larsons/engine/minigame/MiniGame.java#L559)),
and "continue" as a player-facing word appears once — in the creative editor,
offering to continue *editing* the last level.

### 0.2 The one save that exists saves the wrong noun

The pause menu offers four entries offline
([`PlayScene.java:2633`](src/main/java/com/larsons/engine/demo/PlayScene.java#L2633)):
*Resume*, *Controls*, **Save Level**, *Edit in Creative*, *Quit to Menu*.

`saveLevel()`
([`PlayScene.java:2663`](src/main/java/com/larsons/engine/demo/PlayScene.java#L2663))
calls `level.captureSettings(p)` and `store.save(level)`. That is a complete,
correct save — **of the level**. It writes terrain, entities, containers and
the level's own toggles. It writes nothing about the player, because the player
is not part of a `Level` and should not be.

So the pause menu's save button preserves the mountain you dug and loses the
diamonds you dug out of it.

And because `LevelStore.save()` resolves its path from the level's *name*
([`LevelStore.java:158`](src/main/java/com/larsons/engine/level/LevelStore.java#L158)),
it writes back over the authored file. **Pressing *Save Level* during play
overwrites the level's authored copy with the played-in one.** For an engine
whose stated identity is "a giant custom level loader", that is the wrong
direction of travel: there is no distinction anywhere between *the level as its
author built it* and *the world as this player left it*.

*Quit to Menu*
([`PlayScene.java:2639`](src/main/java/com/larsons/engine/demo/PlayScene.java#L2639))
is a bare `scenes.transitionTo("menu")` — no prompt, no autosave, no "you have
unsaved changes". Every unsaved thing goes, silently, on a menu click.

### 0.3 A door is a one-way trip for everything except you

`tryDoorTravel()`
([`PlayScene.java:979`](src/main/java/com/larsons/engine/demo/PlayScene.java#L979))
is the feature the README describes as making "a game type with doors play like
one continuous world". It does three things in this order:

1. `level = store.load(link.targetLevel())` — read the destination **from disk**
2. rebuild `world`, `ruleEngine`, `cutscenes`, camera, parallax, sound
3. move `me` to the destination's spawn

It never writes the level being left. So:

- Mine out a cave in level A, walk through the door to B, walk back to A. **The
  cave is back.** A's disk copy never changed, and A's in-memory copy was
  dropped on the floor at step 1.
- Stash your spare gear in a chest in A — `level.containers`
  ([`Level.java:181`](src/main/java/com/larsons/engine/level/Level.java#L181)) —
  and it is gone on return for the same reason.

The continuity that door travel *does* preserve is real and deliberate:
`inventory`, `stats` and `me` are not reallocated, so you carry your things
between levels. That is exactly right, and it is what makes the next item a
bug rather than a design choice.

### 0.4 The stat-rule re-fire — a concrete exploit that falls out of 0.3

`StatRuleEngine` owns a `fired[]` array and documents its own lifetime:

> *"One engine instance per run — it owns the per-rule 'how many times has this
> fired' state, so re-entering a level starts fresh."*
> — [`StatRuleEngine.java:12`](src/main/java/com/larsons/engine/sim/StatRuleEngine.java#L12)

But `stats` is **not** per-level; it survives door travel, while the rule engine
is rebuilt at
[`PlayScene.java:1000`](src/main/java/com/larsons/engine/demo/PlayScene.java#L1000)
with `fired[]` zeroed. Two different lifetimes for two halves of the same
mechanism. Therefore:

```
Level A rule: "blocks_mined ≥ 50 → +1 diamond"   (once, not repeating)

mine 50 blocks in A       → stats.blocks_mined = 50, rule fires, +1 diamond
walk through door to B    → stats survive; A's engine is discarded
walk back through to A    → new engine, fired[0] = 0, stats.blocks_mined = 50
                          → 50 ≥ 50 → fires again, +1 diamond
                          → repeat forever
```

Every one-shot reward a map-maker authors is farmable by walking through a door
and back. The whole of `StatRule` — the engine's only authored-progression
system — is unsound across level transitions today. **The root cause is that
the engine has no object that represents "this run", so the two halves of run
state ended up with two different owners.**

### 0.5 Player preferences live inside the level file

[`KeyBindStore`](src/main/java/com/larsons/engine/input/KeyBindStore.java#L19)
states the correct doctrine outright:

> *"Binds are a property of the person playing, not of a game type, so there is
> a single file rather than one per profile."*

Audio never got that treatment. `masterVolume`, `sfxVolume` and `musicVolume`
are fields on `GameProfile`
([`GameProfile.java:113`](src/main/java/com/larsons/engine/config/GameProfile.java#L113)),
`captureSettings` writes them into every level file, and
`applyFeaturesFrom` copies them back out on load
([`GameProfile.java:315-317`](src/main/java/com/larsons/engine/config/GameProfile.java#L315))
via `GameContext.applyLevelSettings`
([`GameContext.java:134`](src/main/java/com/larsons/engine/config/GameContext.java#L134)),
which runs on `onEnter` *and* on every door transition. Consequences:

- Load a level someone else authored and **your volume becomes theirs.**
- Walk through a door between two levels saved at different volumes and the
  mix changes under you, mid-run.
- There is nowhere in the pause menu to fix it — the audio sliders exist only
  in creative mode and in the per-level settings form, i.e. in the *authoring*
  surface, not the *playing* one.

The same class of gap, smaller: `LOOK_SENSITIVITY` is a hardcoded constant
([`PlayScene.java:2824`](src/main/java/com/larsons/engine/demo/PlayScene.java#L2824))
with no invert-Y, on a first-person camera reached with `[F5]`. Mouse-look
comfort is not a taste question for a lot of people; it is whether they can
play in that mode at all.

### 0.6 Why this is the item worth doing next

The engine is not short of features. It has three standalone game modes, two
rendering backends, a GLSL shader chain, 512-block verticality, online
multiplayer, a level editor per format, melee and ranged combat, crafting,
vehicles, mobs with jobs, character profiles, ultimates, skins, sound packs,
cutscenes, mini games and rebindable everything. It is short of a *reason to
come back tomorrow*.

Every long-form system already built is measured in a currency the engine
throws away at the door:

- **Stat rules** — authored progression, scoped to one visit, farmable (§0.4).
- **Crafting and the item registry** — rarity tiers and recipes matter over
  hours; the inventory holding them lasts minutes.
- **Verticality** — a 512-block tower is a build project. It survives only if
  the player remembers to press *Save Level*, and saving it corrupts the
  authored level (§0.2).
- **Doors** — sold as one continuous world; are three rooms that reset (§0.3).
- **Character profiles** — you re-pick your character on every entry.

Nothing else in the roadmap changes that. The GPU post-processing pass makes an
existing frame prettier; interest management makes an existing server bigger.
This is the item that turns a very large sandbox into a game you have a
*save file* for.

---

## 1. What is already correct and must not be rewritten

This job is unusually well set up, and most of the reason is that the
serialization work is already done and already good. Nothing below gets
touched:

- **`Level` ↔ JSON is complete and round-trips.** `Level.toJson()` /
  `LevelLoader.load()` already carry chunked and dense tiles, N layers, all
  entity spawns, surface decor, containers, cutscenes, stat rules, the palette,
  the block registry, per-level settings and the authored heading.
  A saved world needs **no new format** — it needs a different *place*.
- **`LevelStore` is already re-rootable.** `LevelStore(String rootDir, String
  gameTypeName)`
  ([`LevelStore.java:71`](src/main/java/com/larsons/engine/level/LevelStore.java#L71))
  takes its root as an argument, with `DEFAULT_DIR` only as a convenience.
  **A save slot is just a second levels root.** Job C is mostly this sentence.
- **Door travel already carries the player.** `inventory`, `stats` and `me`
  survive `tryDoorTravel` by construction. Job D adds a write; it does not
  change what is carried.
- **Key binds already model "the player" correctly.** `KeyBindStore` is the
  template Job O copies for audio and look settings, down to the file layout.
- **`GameContext.save()` and `GameProfile` are the game-type record.** The run
  record is a *new* neighbour to these, not a change to them. A save slot must
  not become a second place that game-type identity lives.
- **The online path is server-authoritative and stays that way.** `PlayScene`
  already branches on `net != null` for the world, the mini game, cutscenes and
  stat bars. Every job here is inside the offline branch (see §10).

---

## 2. Invariants

Rules no job below may break.

- **I1 — An authored level is a template; a run is a copy.** After this plan,
  nothing on the play path writes into `resources/levels/`. Play reads the
  authored level once, when a run is created, and writes only into the run's
  own folder. Creative mode remains the only writer of authored levels.
- **I2 — One owner for run state.** Inventory, stats, the rule engines' fired
  counts, health/mana/stamina, position, character key and the world clock
  belong to one object with one lifetime. If two things that must agree have
  two owners, §0.4 happens again.
- **I3 — A save is a whole run, not a level.** Saving in level B must not lose
  the cave you dug in level A. The unit of persistence is the slot.
- **I4 — No silent loss.** No path out of play — quit, door, crash-adjacent
  window close — may discard progress without either writing it or asking.
- **I5 — A missing or corrupt slot is "no save", never an error.** Same
  doctrine `KeyBindStore` and `SkinStore` already follow: the game must always
  start.
- **I6 — Player settings are the player's.** Volume, look sensitivity and
  invert-Y are read from the player's own file and are never overwritten by
  loading a level, entering a door, or joining a server.
- **I7 — Offline first, and online unchanged.** No job may alter the wire
  protocol or the server's authority. Online runs are out of scope (§10).
- **I8 — Saves are plain readable files beside the game**, like every other
  store here, so a run can be copied to another machine or handed to a friend.

---

## 3. Job P — the player record

**Precondition:** none. This is the first step.

Introduce `com.larsons.engine.sim.PlayerRecord` (or `save.RunPlayer`) — a
serializable snapshot of everything §0.1 currently discards:

```
characterKey        health / maxHealth      mana      stamina    ultCharge
levelName (which level in the game type the run is standing in)
x, y, elevation, facing
inventory  (slots + hotbar, via a new Inventory.toMap()/fromMap())
stats      (PlayerStats.all() is already a Map<String,Double>)
firedCounts per level  →  per-rule fire counts, keyed by level name
timeOfDay
playSeconds, savedAt   (for the slot list)
```

`Inventory` and `PlayerStats` both need `toMap`/`fromMap`; `PlayerStats`
already exposes `all()`, and `ItemStack` already serializes for
`Level.containers`, so the item half is a reuse, not a new format.

The fired-counts map is what closes §0.4: `StatRuleEngine` gains
`firedCounts()` / `restore(int[])`, and the run — not the scene — owns them
across level transitions (**I2**).

**Instrument:** `SaveRecordTest` — build a record with a stocked inventory,
non-zero stats, mid-range health and a fired one-shot rule; round-trip through
JSON; assert field-for-field equality, and assert that an unknown key in the
JSON is ignored rather than thrown (forward compatibility, **I5**).

---

## 4. Job C — a save slot is a levels root

**Precondition:** Job P.

`SaveStore`, modelled on `LevelStore` and `GameTypeStore`:

```
src/main/resources/saves/<game-type>/<slot>/
    run.json          the PlayerRecord
    levels/<name>.json  every level this run has modified
```

`SaveStore` holds a `LevelStore(slotDir + "/levels", gameType)` and answers one
question for the play path: *"give me level N for this run"* — the run's copy if
one exists, otherwise the authored one from `LevelStore`, loaded and then owned
by the run (**I1**, **I3**).

That single indirection is the whole mechanism. It costs no new serialization,
it cannot corrupt an authored level, and a slot is a directory a player can zip
up and send to someone (**I8**).

**Instrument:** `SaveStoreTest` — create a run over a two-level game type,
modify a tile in each, save, reload; assert both modifications come back, and
assert `resources/levels/<type>/*.json` is **byte-identical** to before the run.
That second assertion is the enforcement of **I1** and belongs in CI.

---

## 5. Job D — doors write before they read

**Precondition:** Job C.

Two lines of ordering in `tryDoorTravel`
([`PlayScene.java:979`](src/main/java/com/larsons/engine/demo/PlayScene.java#L979)):
write the departing level into the run's slot **before**
`store.load(link.targetLevel())`, and read the destination through `SaveStore`
so a level you have been in before comes back as you left it.

Then the §0.4 fix, which is now a one-liner rather than a redesign: the new
`StatRuleEngine` for the destination is restored from the run's per-level fired
counts, and the departing level's counts are written back into the run first.
One-shot rules stay one-shot across any number of transitions.

**Instrument:** `DoorContinuityTest` — A→B→A round trip asserting (a) a tile
mined in A is still mined on return, (b) a chest stocked in A still holds its
items, (c) a one-shot stat rule that fired in A does **not** fire again. (c) is
a regression test for a bug that exists today and should be written to fail
against `HEAD` before the fix lands.

---

## 6. Job U — Continue, and the menu that offers it

**Precondition:** Jobs P and C.

- **Main menu:** a **Continue** entry above *Play*, enabled when the selected
  game type has a slot, subtitled with the level name and play time — the
  pattern `NewLevelScene` already uses when it offers to continue editing the
  last level.
- **A slot list:** `SaveSelectScene`, built the way `LevelSelectScene` is —
  slots with *Continue*, *Delete*, and *New Run*. Three slots is enough; the
  store imposes no limit.
- **Pause menu:** *Save Level* becomes **Save Run** (world + player + every
  level this run has touched). *Save and Quit* replaces the bare *Quit to
  Menu*, and quitting with unsaved progress asks (**I4**).
- **A new run** starts from the authored levels — which is now a meaningful
  sentence, because after **I1** the authored levels are still authored.

**Instrument:** `SaveMenuTest`, in the style of the existing `MenuTest` /
`LevelSelectSceneTest`: with no slot present *Continue* is absent; with one it
is present and its subtitle names the run's level; the quit prompt appears iff
the run is dirty. A golden frame for the slot list joins the catalogue.

---

## 7. Job A — autosave, so that **I4** does not depend on the player

**Precondition:** Job D.

Write the run on the events that already exist and already mean "a chapter
ended": door travel (Job D writes there anyway), death and respawn
([`World.java:560`](src/main/java/com/larsons/engine/world/World.java#L560)),
and a periodic tick — two minutes, skipped when nothing is dirty. A window
close during play saves through the same path.

Autosave writes to the slot, and never to `resources/levels/` (**I1**).

The one measurement this job needs: the write must not be felt. A large chunked
level is megabytes of JSON, and the game loop is the wrong thread for it. Save
on a background thread from an immutable snapshot, and record the stage in
`FrameProfiler` so the cost is visible in the same report every other stage
already appears in.

**Instrument:** `AutosaveTest` for the trigger points; and a profiler run on the
largest level in the catalogue asserting no frame exceeds budget during an
autosave. `SIM_PLAN.md`'s rule applies — this is exactly the shape of stall it
was written about, so it gets an instrument before it gets a feature.

---

## 8. Job O — the player's settings stop living in the level file

**Precondition:** none; independent of P–A and can land first.

`PlayerSettings` + `PlayerSettingsStore`, copied from `KeyBindStore` down to the
classpath fallback and the "unreadable file means defaults" rule
([`KeyBindStore.java:22`](src/main/java/com/larsons/engine/input/KeyBindStore.java#L22)):

```
config/player.json
    masterVolume  sfxVolume  musicVolume     (moved off the level, per §0.5)
    lookSensitivity  invertY                 (§0.5, currently a constant)
    hudScale                                 (see §9)
```

`GameProfile.applyFeaturesFrom` stops copying the three volume fields
([`GameProfile.java:315-317`](src/main/java/com/larsons/engine/config/GameProfile.java#L315)),
exactly as it already refuses to copy `name`, `texturePackDir`, `soundPackDir`
and `lastLevelPath`. The mixer reads the player's file. Old level files keep
their volume keys and are simply ignored on load — no migration, no format
break.

Then the pause menu grows an **Options** entry (`ConfigForm`, which already
does sliders and checkboxes) with volume, sensitivity and invert-Y — the first
place in the engine a *player* rather than an *author* can change how the game
feels.

**Instrument:** `PlayerSettingsTest` — set a volume, load a level authored at a
different volume, assert the player's value survives; walk a door between two
levels saved at different volumes, assert it survives that too (**I6**).

---

## 9. Smaller things found along the way, in the order they are worth doing

Not part of the save system; listed because they came out of the same read and
are each small.

1. **The stat-rule re-fire (§0.4)** — a real exploit, live today. Job D fixes
   it properly; a two-line stopgap (carry `fired[]` across door travel in a
   `Map<String,int[]>` on the scene) could land immediately.
2. **`saveLevel()` overwrites the authored level (§0.2)** — even before slots
   exist, this deserves a confirmation prompt.
3. **Invert-Y and mouse sensitivity (§0.5)** — Job O. Accessibility-adjacent:
   for some players an uninvertible Y axis means the `[F5]` mode does not
   exist.
4. **HUD scale.** Every HUD size is a constant against a pixel viewport; on a
   4K display the HUD is unreadable. One multiplier in `PlayerSettings`.
5. **A death that reads as a death.** `World` calls `p.restore()` and moves the
   body to a spawn point
   ([`World.java:558`](src/main/java/com/larsons/engine/world/World.java#L558))
   — full health, same frame, no screen, no pause, no "you died". The
   strongest feedback moment in the game currently passes as a teleport. Cheap:
   a scrim, the cause, the run's death count, a beat before control returns.
6. **No fullscreen toggle.** `BackendWindow` sizes to a window; there is no key
   or option for borderless fullscreen. For a Steam release
   ([`STEAM_PLAN.md`](STEAM_PLAN.md)) this is table stakes.
7. **No compass or minimap in plan view.** With 512-block verticality and
   chunked worlds, "where is my base" has no answer in the UI.

---

## 10. What this plan does not do

- **Online runs.** A server-authoritative save — who owns the world, who owns
  each player's inventory, what happens to a player's things when they
  disconnect — is a genuinely different design, and it needs the offline run
  model to exist first so it has something to serialize. `net != null` keeps
  today's behaviour throughout (**I7**).
- **A new file format.** Every job reuses `Level`'s JSON and `LevelStore`'s
  layout. If any job finds itself designing a format, it has gone wrong.
- **Cross-game-type progression.** A slot belongs to one game type, like every
  other store here.

---

## 11. The order, and why it is that order

```
Job O  (player settings)      independent — can land first, small, visible
Job P  (the player record)    the object §0.4 says is missing
Job C  (slot = levels root)   where it goes; enforces I1
Job D  (doors)                the first thing that reads it back; fixes §0.4
Job U  (Continue + slots)     the first thing a player sees
Job A  (autosave)             makes I4 true without asking the player to care
```

P before C before D is forced: there is nothing to store until the record
exists, nowhere to put it until the store exists, and no way to prove either
works until a door round-trips through them. U before A is a choice — a manual
save that a player can see and trust is worth more than an automatic one they
cannot, and A's background-write measurement is easier to attribute once the
save path is otherwise settled.

---

## 12. What building it taught

Written after the fact. The plan above is left as it was so the two can be
compared; this section is what the code knows that the plan did not.

### 12.1 The measurement that changed a design

Job A was planned as "save on a background thread from an immutable snapshot"
without knowing what a save costs. Measured, on a dense level:

| level | `toMap()` | `stringify` | total | file |
|---|---:|---:|---:|---:|
| 256×256 | 1.67 ms | 6.08 ms | 7.7 ms | 576 KB |
| 512×512 | 5.20 ms | 6.68 ms | 11.9 ms | 2.3 MB |
| 1024×1024 | 33.74 ms | 125.56 ms | 159.3 ms | 9.2 MB |

A 60 Hz frame is 16.67 ms, so a save on the game thread is a dropped frame from
512×512 up. Two things came out of that:

- **The split is not where the plan assumed.** `toMap()` reads live game state
  and so *has* to happen on the game thread — but it produces a tree of plain
  maps and boxed numbers that shares nothing with the `Level`, so it **is** the
  immutable snapshot the plan asked for, and `stringify` — the larger half every
  time — moves off the thread for free. No deep copy of `Level` was needed.
- **The cheapest save is the one that does not happen.** `Level` already counts
  its own edits (`terrainRevision()`), so `RunSession` skips a level nobody has
  touched. A periodic autosave for a player who is walking around rather than
  building writes only `run.json` — a few hundred bytes — and pays the level
  cost only when there are changes to lose.

### 12.2 Two concurrency bugs, both found by the tests

Neither was in the plan, and both were intermittent, which is the worst way for
a save system to be wrong.

- **A queued save could be overtaken.** The first `RunSession` kept one pending
  job and let a newer save replace it — sensible-looking coalescing that loses
  the departing level on door travel, because a door saves the level it is
  leaving and then, lines later, the one it is arriving at. Saves are queued in
  order now, not collapsed.
- **A synchronous save could be overwritten by an older asynchronous one.**
  `saveNow` wrote inline while an autosave queued moments earlier was still in
  flight; the autosave landed second and put the older snapshot on disk. A quit
  would then save the game as it had been half a minute before — sometimes.
  `saveNow` goes through the same single writer thread and waits its turn.

`DoorContinuityTest` caught the first and `AutosaveTest.aLaterSaveIsNeverOvertakenByAnEarlierOne`
pins the second.

### 12.3 The run holds the levels it has been in

Not in the plan, and forced by the first bug above: with saves queued rather
than instant, walking back through a door could read a file the writer had not
finished. `RunSession` keeps the last `VISITED_LEVELS` (4) levels in memory, so
a return through a door does not touch the disk at all and the file is left to
do the job it is for — surviving a quit. Reading a level that has fallen out of
that window waits for the writer first.

### 12.4 Where the plan was too clever

- **`dirty` is not a save trigger.** It was going to decide whether leaving
  writes anything. It is assembled from whichever events the scene happens to
  notice, and being wrong about it at the moment of quitting costs the player
  something they cannot get back — so `onExit` writes unconditionally, and
  `dirty` is left to drive the periodic autosave and the quit prompt, where
  being slightly over-eager is free.
- **Writes are atomic.** Not in the plan. A save writes to a temporary file and
  moves it into place, because a truncated `run.json` reads as "no save" — a
  mild-sounding way to describe losing a run that was fine a second ago.

### 12.5 What did not change

The three load-bearing predictions held. `Level` ↔ JSON needed no changes at
all; `LevelStore`'s re-rootable constructor made a save slot a second levels
root exactly as claimed; and door travel already carried the player, so Job D
really was an ordering fix plus the fire counts. Invariant I1 — an authored
level is a template — is asserted by two tests that fingerprint the whole
authored folder before and after a run.

### 12.6 Still open

- **Online runs** remain out of scope (§10). `PlayScene` holds no `RunSession`
  in a session and every save path is a no-op there.
- **HUD scale** applies to the play HUD (top bar, health, hotbar, item name).
  The inventory grid, crafting and container panels still draw at fixed sizes.
- **§9's other items** — the death screen, fullscreen, the minimap — were not
  part of the save system and were not built. §9.1 and §9.2 were: the
  stat-rule re-fire is fixed by Job D, and `saveLevel` no longer overwrites an
  authored level because it no longer exists.
- **Editing a level you have a run in.** *Edit in Creative* writes the
  **authored** level, and the run keeps its own copy — so returning to play
  shows the run's world rather than the edit. That is the right way round (the
  alternative silently discards whatever the player had built), but it is
  surprising the first time, and a run has no way to say "adopt the new
  authored version of this level". A *Reload this level from the authored copy*
  action in the pause menu is the obvious answer; it is not built.
