package com.larsons.engine.save;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.sim.StatRuleEngine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A run in progress: the {@link RunRecord}, the {@link SaveStore} it belongs
 * to, and the machinery that gets one into the other without dropping a frame.
 *
 * <p>This is what a scene holds. It is deliberately not a scene's inner class:
 * saving has no business knowing about a viewport, and a headless test needs to
 * be able to drive a whole run through doors and back without opening a window.
 *
 * <h2>Why saving happens on another thread</h2>
 *
 * <p>Measured on this machine, writing one level costs:
 *
 * <table border="1">
 *   <caption>Cost of serializing a dense level</caption>
 *   <tr><th>level</th><th>{@code toMap()}</th><th>{@code stringify}</th><th>total</th></tr>
 *   <tr><td>256×256</td><td>1.67 ms</td><td>6.08 ms</td><td>7.7 ms</td></tr>
 *   <tr><td>512×512</td><td>5.20 ms</td><td>6.68 ms</td><td>11.9 ms</td></tr>
 *   <tr><td>1024×1024</td><td>33.74 ms</td><td>125.56 ms</td><td>159.3 ms</td></tr>
 * </table>
 *
 * <p>At a 60 Hz cap the whole frame is 16.67 ms, so a save on the game thread
 * is a dropped frame from 512×512 up and a visible freeze beyond it — exactly
 * the stall shape {@code SIM_PLAN.md} was written about, and not something to
 * introduce on purpose.
 *
 * <p>Three things follow, and each is one of the numbers above:
 *
 * <ul>
 *   <li><b>The snapshot is taken here, the text is built elsewhere.</b>
 *       {@code toMap()} reads live game state and so has to run on the thread
 *       that owns it; the result is a plain tree of maps, lists and boxed
 *       numbers that shares nothing with the {@link Level} it came from, so
 *       {@code stringify} — the larger half every time — happens on the writer
 *       thread along with the file I/O.</li>
 *   <li><b>Unchanged levels are not written at all.</b> {@link Level} already
 *       counts its own edits ({@link Level#terrainRevision()}), so a level that
 *       has not been touched since it was last saved costs nothing rather than
 *       5 ms. In practice a periodic autosave writes only {@code run.json},
 *       which is microseconds — the level cost is paid when the player has
 *       actually been building, which is also when they would most hate to lose
 *       it.</li>
 *   <li><b>Saves are ordered, not collapsed.</b> One writer thread, and every
 *       save queued on it — including the synchronous ones, which wait for
 *       their turn rather than jumping the queue. Collapsing saves would be
 *       cheaper and is wrong twice over: door travel saves the level it is
 *       leaving and then the one it is arriving at, and a quit must not be
 *       overtaken by an autosave taken half a minute earlier. Both of those
 *       were real, and both were intermittent.</li>
 * </ul>
 */
public final class RunSession implements AutoCloseable {

    /** How often the periodic autosave writes, in seconds. */
    public static final double AUTOSAVE_SECONDS = 120;

    private final SaveStore store;
    private final RunRecord record;

    /**
     * {@link Level#terrainRevision()} for each level as of its last write, so
     * an untouched level is skipped. Absent means "never written by this run".
     */
    private final Map<String, Long> writtenRevisions = new HashMap<>();

    /** Levels whose containers/entities changed without the terrain doing so. */
    private final Map<String, Boolean> forceWrite = new HashMap<>();

    private final ExecutorService writer;
    /** Saves queued or in flight, so a caller can tell when the disk is caught up. */
    private final AtomicInteger queued = new AtomicInteger();

    private double sinceAutosave;
    private boolean dirty;
    private volatile boolean writing;

    private RunSession(SaveStore store, RunRecord record) {
        this.store = store;
        this.record = record;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "run-save");
            // A daemon: an autosave must never be the reason the game will not
            // shut down. close() flushes what matters before that can bite.
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Continue the run in {@code store}, or begin one if the slot is empty (or
     * holds a save this build cannot read — see {@link SaveStore#loadRun}).
     */
    public static RunSession open(SaveStore store) {
        RunRecord saved = store.loadRun();
        return new RunSession(store, saved != null ? saved : new RunRecord());
    }

    /** Begin a run in {@code store}, discarding nothing until it is saved. */
    public static RunSession fresh(SaveStore store) {
        return new RunSession(store, new RunRecord());
    }

    public SaveStore store() { return store; }

    public RunRecord record() { return record; }

    /** Whether this session was opened on a run that already existed. */
    public boolean resumed() {
        return !record.levelName.isBlank();
    }

    /** Whether anything has happened since the last write. */
    public boolean dirty() { return dirty; }

    /** Note that the run has changed and ought to be written. */
    public void markDirty() { dirty = true; }

    /**
     * Note that a level's contents changed in a way its terrain revision does
     * not count — a chest's contents, a spawned entity — so the next save
     * writes it even though no tile moved.
     */
    public void markLevelDirty(String levelName) {
        if (levelName != null && !levelName.isBlank()) forceWrite.put(levelName, Boolean.TRUE);
        dirty = true;
    }

    // --- the run's levels ------------------------------------------------------

    /**
     * The level to play under {@code levelName}: this run's copy if it has one,
     * the authored level if not.
     *
     * <p><b>Levels the run has been in are kept in memory.</b> Not as a cache
     * for speed — as the answer to a race that a disk-only version loses.
     * Door travel queues the departing level's write and then, in the same
     * frame, reads the destination; walking straight back would read a file the
     * writer thread had not finished producing, and find the level as it was
     * authored. Holding the visited levels means a return through a door does
     * not touch the disk at all, and the file is left to do the job it is
     * actually for, which is surviving a quit.
     *
     * <p>Bounded at {@link #VISITED_LEVELS}, because a game type may have
     * dozens of levels and a large one is megabytes. Reading a level that has
     * fallen out of that window waits for the writer first, so the file is
     * complete by the time it is read.
     */
    public Level level(String levelName) {
        Level held = visited.get(levelName);
        if (held != null) return held;
        awaitWrites();
        Level level = store.levelFor(levelName);
        adopt(level);
        return level;
    }

    /** How many visited levels are held in memory (see {@link #level}). */
    public static final int VISITED_LEVELS = 4;

    /**
     * The levels this run has been in, most-recently-used last. Access-ordered
     * so the map itself does the eviction.
     */
    private final Map<String, Level> visited =
            new LinkedHashMap<>(VISITED_LEVELS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Level> eldest) {
                    return size() > VISITED_LEVELS;
                }
            };

    /**
     * Block until the writer has caught up. Called before reading a level from
     * disk, so a queued write is never read half-finished.
     *
     * <p>Bounded: a writer that has somehow wedged costs a stutter, not a
     * hung game.
     */
    public void awaitWrites() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queued.get() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /**
     * Note that {@code level} is, as of right now, exactly what is on disk —
     * so the next save skips it unless the player actually changes something.
     *
     * <p>True whichever copy it came from: the run's own copy is what was last
     * written, and the authored copy needs no copy in the slot until it
     * differs from it. Without this every door transition would duplicate the
     * destination into the slot for no reason, at 5 ms a level.
     */
    public void adopt(Level level) {
        if (level == null) return;
        writtenRevisions.put(level.name, level.terrainRevision());
        forceWrite.remove(level.name);
        visited.put(level.name, level);
    }

    /** Whether {@code level} has changed since this run last wrote it. */
    public boolean levelNeedsWrite(Level level) {
        if (level == null) return false;
        if (Boolean.TRUE.equals(forceWrite.get(level.name))) return true;
        Long written = writtenRevisions.get(level.name);
        return written == null || written != level.terrainRevision();
    }

    // --- capture ---------------------------------------------------------------

    /**
     * Fold the live run into the record, without writing anything. Called
     * before every save and on every door transition, so the record is always
     * one step from being correct on disk.
     */
    public void capture(String gameType, Level level, PlayerState player, Inventory inventory,
                        PlayerStats stats, double timeOfDay) {
        String levelName = level == null ? record.levelName : level.name;
        RunRecord snap = RunRecord.capture(gameType, levelName, player, inventory, stats,
                timeOfDay, record.playSeconds);
        record.gameType = snap.gameType;
        record.levelName = snap.levelName;
        record.characterKey = snap.characterKey;
        record.x = snap.x;
        record.y = snap.y;
        record.z = snap.z;
        record.facing = snap.facing;
        record.facingLeft = snap.facingLeft;
        record.health = snap.health;
        record.maxHealth = snap.maxHealth;
        record.mana = snap.mana;
        record.maxMana = snap.maxMana;
        record.stamina = snap.stamina;
        record.maxStamina = snap.maxStamina;
        record.ultCharge = snap.ultCharge;
        record.inventory = snap.inventory;
        record.selectedSlot = snap.selectedSlot;
        record.stats = snap.stats;
        record.timeOfDay = snap.timeOfDay;
        record.savedAt = snap.savedAt;
        // firedCounts is deliberately not overwritten: it accumulates across
        // every level the run has entered, and RunRecord.capture only ever
        // knows about the one being played.
    }

    /** Remember a level's stat-rule fire counts (see {@link RunRecord}). */
    public void rememberFired(String levelName, StatRuleEngine engine) {
        if (engine != null) record.putFiredCounts(levelName, engine.firedCounts());
    }

    /** Put a level's saved fire counts back into a freshly built engine. */
    public void restoreFired(String levelName, StatRuleEngine engine) {
        if (engine != null) engine.restore(record.firedCountsFor(levelName));
    }

    /** Advance the play clock. */
    public void tick(double dt) {
        if (dt > 0 && dt < 1) record.playSeconds += dt;
        sinceAutosave += dt;
    }

    /** Whether enough time has passed, and enough has happened, to autosave. */
    public boolean autosaveDue() {
        return dirty && sinceAutosave >= AUTOSAVE_SECONDS;
    }

    // --- writing ---------------------------------------------------------------

    /**
     * Snapshot now, write on the writer thread. Returns immediately.
     *
     * <p>Jobs are queued rather than collapsed into one. An earlier version
     * kept a single pending job and let a newer save replace it, which is a
     * reasonable thing to do when every save writes the same file and a wrong
     * thing to do here: door travel saves the level being left and then, a few
     * lines later, the level being arrived at, and collapsing those two loses
     * the first — which is the exact failure this whole job exists to fix. The
     * writer is single-threaded, so queued saves land in the order they were
     * taken and the newest run record is the one left on disk.
     *
     * @param level the level being played, written only if it has changed
     */
    public void saveAsync(Level level) {
        Runnable job = snapshot(level);
        queued.incrementAndGet();
        writer.execute(() -> {
            writing = true;
            try {
                job.run();
            } catch (RuntimeException e) {
                System.err.println("RunSession: could not write the run to "
                        + store.directory() + " (" + e.getMessage() + ")");
            } finally {
                queued.decrementAndGet();
                writing = false;
            }
        });
    }

    /** How many saves are queued or in flight (tests wait on this). */
    public int queuedSaves() { return queued.get(); }

    /**
     * Snapshot and write here and now — for quitting and for the pause menu's
     * <em>Save Run</em>, where returning before the bytes are on disk would
     * defeat the point.
     *
     * <p><b>Goes through the writer thread rather than around it.</b> Writing
     * inline looks simpler and is wrong: an autosave queued moments earlier —
     * door travel queues one — would still be in flight, and would land
     * <em>after</em> this one and overwrite it with the older snapshot. The
     * symptom is a quit that saves the game as it was thirty seconds ago,
     * intermittently, depending on how the two threads happened to interleave.
     * Handing this to the same single-threaded writer and waiting for it puts
     * both saves in the order they were taken.
     */
    public void saveNow(Level level) {
        Runnable job = snapshot(level);
        if (writer.isShutdown()) {
            job.run(); // after close(): nothing else can be in flight
            return;
        }
        queued.incrementAndGet();
        try {
            writer.submit(() -> {
                writing = true;
                try {
                    job.run();
                } finally {
                    queued.decrementAndGet();
                    writing = false;
                }
            }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            System.err.println("RunSession: could not write the run to " + store.directory()
                    + " (" + e.getMessage() + ")");
        }
    }

    /** Whether a background write is in flight. */
    public boolean writing() { return writing; }

    /**
     * Take the snapshot on the calling thread and hand back the part that can
     * happen anywhere. Everything that reads live game state happens before
     * this method returns; the returned job touches only the maps it was given.
     */
    private Runnable snapshot(Level level) {
        boolean writeLevel = levelNeedsWrite(level);
        String levelName = level == null ? null : level.name;
        long revision = level == null ? 0 : level.terrainRevision();
        Map<String, Object> levelData = writeLevel ? level.toMap() : null;
        // Stamped here rather than in capture(): a save can be taken without a
        // capture in front of it, and "when this was saved" that only moves
        // when something else happens to call capture is a lie the pause
        // screen's save chip would repeat. This is the moment the save exists.
        record.savedAt = System.currentTimeMillis();
        Map<String, Object> runData = new LinkedHashMap<>(record.toMap());

        dirty = false;
        sinceAutosave = 0;
        if (writeLevel) {
            writtenRevisions.put(levelName, revision);
            forceWrite.remove(levelName);
        }

        return () -> {
            // The level first: a run record pointing at a level whose changes
            // never landed is worse than a level whose run record is one save
            // behind, and this order is what decides which of the two an
            // interrupted save leaves behind.
            if (levelData != null) store.saveLevelData(levelName, levelData);
            store.saveRunData(runData);
        };
    }

    /**
     * Flush and stop. Waits a bounded time for the writer to finish, because
     * "quit" that loses the save it just took is not a save.
     */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("RunSession: the save thread did not finish in 10s");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
