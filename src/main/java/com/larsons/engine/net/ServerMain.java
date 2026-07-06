package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.level.LevelLoader;

import java.io.IOException;

/**
 * Headless dedicated server — the engine's equivalent of running a Minecraft
 * Java server jar. Players connect from the game's Multiplayer menu with this
 * machine's IP address and the chosen port.
 *
 * <pre>
 *   # with Gradle
 *   gradle runServer --args="--port 7777 --level levels/sample_level.json --gametype platformer"
 *
 *   # or with a built jar / plain classes
 *   java -cp build/libs/Larsons-2D-Game-Engine-0.1.0.jar com.larsons.engine.net.ServerMain --port 7777
 * </pre>
 *
 * All arguments are optional: the port defaults to {@value Protocol#DEFAULT_PORT},
 * the level to the bundled sample, and the game type to engine defaults
 * (name it with {@code --gametype} to serve one saved under
 * {@code resources/gametypes/}).
 */
public final class ServerMain {

    private ServerMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Protocol.DEFAULT_PORT;
        String levelPath = "levels/sample_level.json";
        String gameType = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(require(args, ++i, "--port"));
                case "--level" -> levelPath = require(args, ++i, "--level");
                case "--gametype" -> gameType = require(args, ++i, "--gametype");
                case "--help", "-h" -> {
                    System.out.println("Usage: ServerMain [--port N] [--level path] [--gametype name]");
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        GameProfile profile = gameType != null
                ? new GameTypeStore().load(gameType)
                : new GameProfile("Dedicated Server");

        String levelJson = LevelLoader.readText(levelPath);
        if (levelJson == null) {
            System.err.println("Level not found: " + levelPath);
            System.exit(1);
            return;
        }

        GameServer server = new GameServer(profile, levelJson);
        server.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "server-shutdown"));

        // Server threads are daemons; keep the process alive until Ctrl+C.
        Thread.currentThread().join();
    }

    private static String require(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println(flag + " needs a value");
            System.exit(2);
        }
        return args[i];
    }
}
