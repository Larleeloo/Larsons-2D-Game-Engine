package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two players looking at one world from different directions, which is correct
 * behaviour rather than a bug to be fixed.
 *
 * <p><b>C10's whole content is a negative.</b> The camera heading is per-client
 * view state and must never be networked: a synchronised camera would mean one
 * player turning to look behind them spun the world for everybody. So there is
 * nothing to build, and the work is proving that nothing leaked — which is
 * harder to test than a feature, because the failure is silent and only shows up
 * as two clients slowly disagreeing about where things are.
 *
 * <p>Three things are asked here, and they are different questions. That the
 * heading <em>reaches</em> the simulation, per client, so the two players walk
 * different ways on one key. That it reaches it <em>only</em> as input — the
 * snapshots coming back are one description of one world, identical whichever
 * client is looking. And that nothing else can quietly acquire a camera: the
 * packages that hold world state may not name the class at all.
 */
@Timeout(30)
class NetCameraIndependenceTest {

    /**
     * A plan view, because a side-scroller has no heading to differ over —
     * §6.1's scope rule decides which levels this test can be about at all.
     */
    /**
     * A plan view, because a side-scroller has no heading to differ over —
     * §6.1's scope rule decides which levels this test can be about at all.
     *
     * <p>Built through the format's own starter rather than written out by
     * hand: a plan-view level's floor has to come from the block registry for
     * {@code Level.layered()} to be true, and a hand-written grid of palette
     * indices is legacy "any tile is solid", which is a level nobody can walk
     * in at all.
     */
    private static final String LEVEL_JSON = turnableLevel();

    private static String turnableLevel() {
        Level level = LevelFormat.TOP_DOWN.starterLevel("Turned Test Level", 24, 24, 32);
        level.spawnX = 12 * 32;
        level.spawnY = 12 * 32;
        return level.toJson();
    }

    private static GameServer startServer() throws IOException {
        GameProfile profile = new GameProfile("Turned Test Type");
        GameServer server = new GameServer(profile, LEVEL_JSON);
        server.start(0);
        return server;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    /**
     * Two clients hold the same key at different headings, and walk different
     * ways in one world.
     *
     * <p>The positive half, and the reason C10 is not simply "delete
     * something": the heading has to reach the server, per client, or C7's
     * whole determinism argument is decoration. One player is looking north and
     * one east; both press "up", which means "away from me" to each of them;
     * the server walks one north and the other east, in the same tick of the
     * same simulation.
     */
    @Test
    void twoPlayersHoldingOneKeyAtDifferentHeadingsWalkDifferentWays() throws IOException {
        GameServer server = startServer();
        try (GameClient facingNorth = GameClient.connect(
                "127.0.0.1", server.getPort(), "North", 3000);
             GameClient facingEast = GameClient.connect(
                     "127.0.0.1", server.getPort(), "East", 3000)) {

            await("both joined", () -> {
                Snapshot s = facingNorth.latest();
                return s != null && s.players().size() == 2;
            });
            PlayerState startNorth = facingNorth.latest().player(facingNorth.localId());
            PlayerState startEast = facingNorth.latest().player(facingEast.localId());
            double northX = startNorth.x, northY = startNorth.y;
            double eastX = startEast.x, eastY = startEast.y;

            // "Up" from each player's own point of view: away from the viewer.
            PlayerInput up = new PlayerInput(false, false, true, false, 1);
            PlayerInput upTurned = new PlayerInput(false, false, true, false, 1);
            upTurned.yaw = 2 * Camera.EIGHTH_TURN;   // this client is looking east
            facingNorth.sendInput(up);
            facingEast.sendInput(upTurned);

            await("both players moved", () -> {
                Snapshot s = facingNorth.latest();
                if (s == null) return false;
                PlayerState a = s.player(facingNorth.localId());
                PlayerState b = s.player(facingEast.localId());
                return a != null && b != null
                        && Math.abs(a.y - northY) > 20 && Math.abs(b.x - eastX) > 20;
            });

            Snapshot now = facingNorth.latest();
            PlayerState north = now.player(facingNorth.localId());
            PlayerState east = now.player(facingEast.localId());

            assertTrue(north.y < northY - 20, "the player looking north walked north; "
                    + "they went from y=" + northY + " to y=" + north.y);
            assertEquals(northX, north.x, 0.001, "and not sideways");

            assertTrue(east.x > eastX + 20, "the player looking east walked east; "
                    + "they went from x=" + eastX + " to x=" + east.x);
            assertEquals(eastY, east.y, 0.001, "and not northward");
        } finally {
            server.stop();
        }
    }

    /**
     * Both clients receive the same description of the same world, whichever
     * way each of them is looking.
     *
     * <p>The negative half, and the one that would fail silently. A heading
     * that leaked into a snapshot — or into anything the server derived from
     * one client's view — would show up as two clients holding different ideas
     * about where the same player is, which is the desync C10 exists to rule
     * out. Compared field by field through the wire form, because that is what
     * actually crosses.
     */
    @Test
    void bothClientsSeeOneWorldWhicheverWayTheyAreLooking() throws IOException {
        GameServer server = startServer();
        try (GameClient facingNorth = GameClient.connect(
                "127.0.0.1", server.getPort(), "North", 3000);
             GameClient facingSouthWest = GameClient.connect(
                     "127.0.0.1", server.getPort(), "SouthWest", 3000)) {

            await("both joined", () -> {
                Snapshot s = facingNorth.latest();
                Snapshot t = facingSouthWest.latest();
                return s != null && t != null
                        && s.players().size() == 2 && t.players().size() == 2;
            });

            // Sustained play, with the two of them turned three eighths apart
            // and both moving the whole time.
            for (int tick = 1; tick <= 40; tick++) {
                PlayerInput a = new PlayerInput(tick % 4 < 2, false, tick % 6 < 3, false, tick);
                PlayerInput b = new PlayerInput(false, tick % 5 < 2, false, tick % 3 == 0, tick);
                b.yaw = 3 * Camera.EIGHTH_TURN;
                facingNorth.sendInput(a);
                facingSouthWest.sendInput(b);
                sleep(15);
            }

            // The two clients' most recent view of the same tick must agree.
            await("a tick both clients have seen", () -> sharedTick(
                    facingNorth, facingSouthWest) != null);
            long tick = sharedTick(facingNorth, facingSouthWest);
            Snapshot fromNorth = at(facingNorth, tick);
            Snapshot fromSouthWest = at(facingSouthWest, tick);
            assertNotNull(fromNorth);
            assertNotNull(fromSouthWest);

            assertEquals(wire(fromNorth), wire(fromSouthWest),
                    "the two clients disagree about tick " + tick + ", and the only "
                            + "thing that differs between them is which way each is "
                            + "looking — a camera has reached the simulation");

            // …and both players really did move, so this compared a world in
            // motion rather than two identical descriptions of nobody moving.
            PlayerState moved = fromNorth.player(facingNorth.localId());
            assertTrue(Math.abs(moved.x - 128) > 5 || Math.abs(moved.y - 128) > 5,
                    "nobody moved, so this proved nothing");
        } finally {
            server.stop();
        }
    }

    /**
     * No snapshot carries a heading, and the level a joining client is handed
     * carries only the one it was authored at.
     *
     * <p>The wire is where "must not be networked" is either true or false.
     * A player's networked state is their position, their health and the
     * direction they are <em>walking</em> — a world direction, which C5 keeps
     * separate from the picture drawn of it. The heading a client is looking
     * from appears in exactly one message in this protocol, the input command,
     * and it is there because the keys are meaningless without it (C7).
     *
     * <p>The level's own {@code heading} is a different thing wearing a similar
     * name and is deliberately allowed: it is where a level <em>opens</em>,
     * authored once and identical for everybody, in the way {@code lightAngle}
     * is. It is level data, not camera state.
     */
    @Test
    void theHeadingIsOnTheWireExactlyOnceAndItIsAnInput() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect(
                "127.0.0.1", server.getPort(), "Looker", 3000)) {
            await("first snapshot", () -> client.latest() != null);

            PlayerInput turned = new PlayerInput(false, true, false, false, 1);
            turned.yaw = 5 * Camera.EIGHTH_TURN;
            client.sendInput(turned);
            await("input acknowledged", () -> {
                Snapshot s = client.latest();
                return s != null && s.player(client.localId()).lastSeq == 1;
            });

            // The player's networked state says nothing about where they are
            // looking, however far round they have turned.
            Map<String, Object> state = client.latest().player(client.localId()).toMap();
            for (String key : state.keySet()) {
                assertFalse(key.equals("y") && state.get(key) instanceof Double d
                                && d == turned.yaw,
                        "the snapshot carries the client's heading under " + key);
            }
            assertFalse(state.containsKey("yaw"), "the snapshot carries a heading");
            assertFalse(state.containsKey("heading"), "the snapshot carries a heading");

            // And the level handed to a joining client carries no heading at
            // all, because this one was authored square to the world.
            Level level = LevelLoader.parse(client.levelJson());
            assertEquals(0, level.authoredHeading);
            assertFalse(client.levelJson().contains("heading"),
                    "the level sent to a joining client mentions a heading it was not "
                            + "authored with");
        } finally {
            server.stop();
        }
    }

    /**
     * Nothing that holds world state may name the camera.
     *
     * <p>C10 says to audit rather than assume, and an audit is worth exactly as
     * long as it takes for the next change to invalidate it. So the audit is a
     * scan: the packages that simulate, serve, serialise and store the world —
     * {@code sim}, {@code net}, {@code world}, {@code entity}, {@code combat},
     * {@code level}, {@code crafting} — may not name {@link Camera}. They do not
     * today; the point is that they cannot start to without this failing.
     *
     * <p>Prose is left alone, and deliberately: several of those files explain
     * at length why they take a perspective from the level rather than from the
     * client's view, and a rule whose only remedy is deleting the explanation is
     * a bad rule. {@code SealedSeamTest} settled this argument once already for
     * Java2D, and strips comments and string literals the same way.
     */
    @Test
    void noClassThatHoldsWorldStateNamesTheCamera() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String pkg : List.of("sim", "net", "world", "entity", "combat",
                "level", "crafting")) {
            Path dir = Path.of("src/main/java/com/larsons/engine", pkg);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String code = stripCommentsAndStrings(Files.readString(file));
                    if (code.contains("Camera")) {
                        offenders.add(dir.getFileName() + "/" + file.getFileName());
                    }
                }
            }
        }
        assertTrue(scanned > 50, "the scan covered only " + scanned + " files, which is "
                + "too few to be pointed at the engine");
        assertEquals(List.of(), offenders, "these hold world state and name the camera: "
                + offenders + ". A camera is per-client view state that C10 keeps off the "
                + "wire; the moment the simulation can read one, two players looking "
                + "different ways are playing different games");
    }

    /** The checker rejects real code and forgives prose, checked both ways. */
    @Test
    void theScanRejectsCodeAndForgivesProse() {
        assertTrue(stripCommentsAndStrings("void f(Camera c) {}").contains("Camera"));
        assertFalse(stripCommentsAndStrings("// the Camera decides where that lands")
                .contains("Camera"));
        assertFalse(stripCommentsAndStrings("/** See {@link Camera}. */").contains("Camera"));
        assertFalse(stripCommentsAndStrings("String s = \"Camera\";").contains("Camera"));
    }

    // --- helpers -------------------------------------------------------------------

    /** Source with comments and string literals removed, so prose stays legal. */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (c == '"') {
                i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The most recent tick both clients have a snapshot for, or {@code null}. */
    private static Long sharedTick(GameClient a, GameClient b) {
        Snapshot sa = a.latest(), sb = b.latest();
        if (sa == null || sb == null) return null;
        long tick = Math.min(sa.tick(), sb.tick());
        return tick > 0 ? tick : null;
    }

    /**
     * The snapshot for {@code tick}, waited for. Clients receive the same
     * broadcasts but not always in the same instant, so the comparison waits
     * for both to have caught up rather than comparing whatever each happens to
     * be holding.
     */
    private static Snapshot at(GameClient client, long tick) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Snapshot s = client.latest();
            if (s != null && s.tick() >= tick) return s.tick() == tick ? s : null;
            sleep(5);
        }
        return null;
    }

    /** A snapshot's players in wire form, ordered, so two can be compared. */
    private static List<Map<String, Object>> wire(Snapshot snapshot) {
        return snapshot.players().stream()
                .sorted((a, b) -> Integer.compare(a.id, b.id))
                .map(PlayerState::toMap)
                .toList();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
