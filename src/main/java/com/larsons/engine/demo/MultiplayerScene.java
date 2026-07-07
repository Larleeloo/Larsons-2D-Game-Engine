package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.NetSession;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;

/**
 * The Multiplayer screen (requirement #3), modelled on Minecraft Java
 * edition's flow:
 * <ul>
 *   <li><b>Join:</b> type a server address as {@code host} or {@code host:port}
 *       (port defaults to {@value Protocol#DEFAULT_PORT}) and connect. The
 *       server sends back its game type and level, so you play exactly the
 *       world the host configured.</li>
 *   <li><b>Host:</b> start an integrated server on a port using <em>your</em>
 *       active game type and level, and join it locally. Friends then connect
 *       to your IP address and that port.</li>
 * </ul>
 *
 * Connecting happens on a worker thread so the UI never freezes; the finished
 * session is handed to the game loop thread, which owns all scene switching.
 */
public class MultiplayerScene extends AbstractScene {

    private final GameContext ctx;
    private final String levelPath;

    private ConfigForm form;
    private String playerName = "Player";
    private String address = "localhost";
    private String hostPort = Integer.toString(Protocol.DEFAULT_PORT);

    private volatile String status = "";
    private volatile boolean connecting;
    private volatile NetSession pendingSession;

    public MultiplayerScene(GameContext ctx, String levelPath) {
        this.ctx = ctx;
        this.levelPath = levelPath;
    }

    @Override
    public void onEnter() {
        status = "";
        connecting = false;
        // A connect that finished after the user backed out is stale; drop it.
        NetSession stale = pendingSession;
        pendingSession = null;
        if (stale != null) stale.close();
        form = new ConfigForm("Multiplayer").theme(MenuTheme.dark());
        form.addText("Player name", () -> playerName, v -> playerName = v, Protocol.MAX_NAME_LENGTH);
        form.addText("Server address (host[:port])", () -> address, v -> address = v, 64);
        form.addAction("Join Server", this::startJoin);
        form.addText("Host on port", () -> hostPort, v -> hostPort = v, 5);
        form.addAction("Host Server + Play", this::startHost);
        form.addAction("Back", () -> scenes.transitionTo("menu"));
    }

    @Override
    public void update(double dt, InputManager input) {
        // A finished connection is consumed here, on the game-loop thread.
        NetSession session = pendingSession;
        if (session != null) {
            pendingSession = null;
            connecting = false;
            ctx.closeSession(); // drop any stale session before installing the new one
            ctx.setSession(session);
            // Adopt the server's game type; in MP everyone plays the host's rules.
            ctx.setProfile(session.client().profile());
            scenes.transitionTo("play");
            return;
        }

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            scenes.transitionTo("menu");
            return;
        }
        form.update(dt, input);
    }

    private void startJoin() {
        if (connecting) return;
        connecting = true;
        String[] hostAndPort = Protocol.splitAddress(address);
        status = "Connecting to " + hostAndPort[0] + ":" + hostAndPort[1] + " ...";
        Thread worker = new Thread(() -> {
            try {
                int port = Integer.parseInt(hostAndPort[1].trim());
                GameClient client = GameClient.connect(
                        hostAndPort[0], port, playerName, 5000);
                pendingSession = new NetSession(client, null);
            } catch (Exception e) {
                status = "Could not connect: " + message(e);
                connecting = false;
            }
        }, "mp-join");
        worker.setDaemon(true);
        worker.start();
    }

    private void startHost() {
        if (connecting) return;
        connecting = true;
        status = "Starting server on port " + hostPort + " ...";
        Thread worker = new Thread(() -> {
            GameServer server = null;
            try {
                int port = Integer.parseInt(hostPort.trim());
                // Host the game type's last saved creative level when there is
                // one, matching what "Play Level" runs offline.
                String path = levelPath;
                String last = ctx.profile().lastLevelPath;
                if (last != null && !last.isEmpty() && LevelLoader.readText(last) != null) {
                    path = last;
                }
                String levelJson = LevelLoader.readText(path);
                if (levelJson == null) throw new IllegalStateException("Level not found: " + path);
                server = new GameServer(ctx.profile(), levelJson);
                server.start(port);
                GameClient client = GameClient.connect("127.0.0.1", server.getPort(), playerName, 5000);
                pendingSession = new NetSession(client, server);
            } catch (Exception e) {
                if (server != null) server.stop();
                status = "Could not host: " + message(e);
                connecting = false;
            }
        }, "mp-host");
        worker.setDaemon(true);
        worker.start();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        form.render(g, viewportWidth, viewportHeight);

        String s = status;
        if (!s.isEmpty()) {
            g.setColor(s.startsWith("Could not") ? new Color(235, 120, 110) : new Color(140, 200, 150));
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(s, 24, viewportHeight - 52);
        }
        g.setColor(new Color(120, 120, 140));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Hosting uses your current game type + level; friends connect to your IP and port",
                24, viewportHeight - 24);
    }
}
