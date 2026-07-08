package com.larsons.engine.demo;

import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoProto;
import com.larsons.engine.autobattler.AutoServer;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The auto-battler's front door: host a game on a port or join one by
 * {@code host[:port]} (Minecraft-style, exactly like the world game's
 * multiplayer screen), then wait in the lobby. The host can add bots to fill
 * seats — 2 to 10 combatants — and starts the match; every client jumps to the
 * {@link AutoBattlerScene} when the first planning phase begins.
 */
public class AutoBattlerLobbyScene extends AbstractScene {

    private final GameContext ctx;

    private ConfigForm form;
    private String playerName = "Player";
    private String address = "localhost";
    private String hostPort = Integer.toString(AutoProto.DEFAULT_PORT);

    private volatile String status = "";
    private volatile boolean connecting;
    private volatile AutoSession pendingSession;
    private AutoSession session;

    private final List<Rectangle> buttons = new ArrayList<>();
    private final List<String> buttonLabels = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public AutoBattlerLobbyScene(GameContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onEnter() {
        status = "";
        connecting = false;
        session = null;
        AutoSession stale = pendingSession;
        pendingSession = null;
        if (stale != null) stale.close();
        buildForm();
    }

    private void buildForm() {
        form = new ConfigForm("Auto Battler").theme(MenuTheme.dark());
        form.addText("Player name", () -> playerName, v -> playerName = v,
                Protocol.MAX_NAME_LENGTH);
        form.addText("Server address (host[:port])", () -> address, v -> address = v, 64);
        form.addAction("Join Game", this::startJoin);
        form.addText("Host on port", () -> hostPort, v -> hostPort = v, 5);
        form.addAction("Host Game", this::startHost);
        form.addAction("How to Play", () -> scenes.transitionTo("autoguide"));
        form.addAction("Back", () -> scenes.transitionTo("startup"));
    }

    @Override
    public void onExit() {
        // Ownership either moved to the game scene (session == null by then)
        // or the player backed out; only close what we still hold.
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void update(double dt, InputManager input) {
        AutoSession fresh = pendingSession;
        if (fresh != null) {
            pendingSession = null;
            connecting = false;
            session = fresh;
            status = "";
        }

        if (session == null) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
                scenes.transitionTo("startup");
                return;
            }
            form.update(dt, input);
            return;
        }

        AutoClient client = session.client();
        if (!client.isConnected()) {
            status = "Disconnected: " + client.disconnectReason();
            session.close();
            session = null;
            return;
        }

        // The match began: hand the session to the game scene.
        if (client.phase() != null && client.phase().phase() != AutoGame.Phase.LOBBY) {
            AutoBattlerScene game = (AutoBattlerScene) scenes.get("autobattler");
            game.adopt(session);
            session = null;
            scenes.transitionTo("autobattler");
            return;
        }

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            session.close();
            session = null;
            status = "";
            return;
        }

        if (input.isMouseJustPressed()) {
            int mx = input.getMouseX();
            int my = input.getMouseY();
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).contains(mx, my)) {
                    buttonActions.get(i).run();
                    ctx.sfx(com.larsons.engine.audio.AudioManager.Sfx.CLICK);
                    break;
                }
            }
        }
    }

    private void startJoin() {
        if (connecting) return;
        connecting = true;
        String[] hostAndPort = Protocol.splitAddress(address);
        // The world protocol's default port is 7777; the auto-battler's is 7788.
        if (address.lastIndexOf(':') <= 0) {
            hostAndPort[1] = Integer.toString(AutoProto.DEFAULT_PORT);
        }
        status = "Connecting to " + hostAndPort[0] + ":" + hostAndPort[1] + " ...";
        Thread worker = new Thread(() -> {
            try {
                int port = Integer.parseInt(hostAndPort[1].trim());
                AutoClient client = AutoClient.connect(hostAndPort[0], port, playerName, 5000);
                pendingSession = new AutoSession(client, null);
            } catch (Exception e) {
                status = "Could not connect: " + message(e);
                connecting = false;
            }
        }, "auto-join");
        worker.setDaemon(true);
        worker.start();
    }

    private void startHost() {
        if (connecting) return;
        connecting = true;
        status = "Starting server on port " + hostPort + " ...";
        Thread worker = new Thread(() -> {
            AutoServer server = null;
            try {
                int port = Integer.parseInt(hostPort.trim());
                server = new AutoServer(new AutoGame.Config());
                server.start(port);
                AutoClient client = AutoClient.connect(
                        "127.0.0.1", server.getPort(), playerName, 5000);
                pendingSession = new AutoSession(client, server);
            } catch (Exception e) {
                if (server != null) server.stop();
                status = "Could not host: " + message(e);
                connecting = false;
            }
        }, "auto-host");
        worker.setDaemon(true);
        worker.start();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        if (session == null) {
            form.render(g, viewportWidth, viewportHeight);
            String s = status;
            if (!s.isEmpty()) {
                g.setColor(s.startsWith("Could not") || s.startsWith("Disconnected")
                        ? new Color(235, 120, 110) : new Color(140, 200, 150));
                g.setFont(new Font("SansSerif", Font.BOLD, 15));
                g.drawString(s, 24, viewportHeight - 52);
            }
            g.setColor(new Color(120, 120, 140));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("An online auto-battler for 2-10 players. Host a game, "
                    + "or join a friend's IP and port.", 24, viewportHeight - 24);
            return;
        }

        renderLobby(g);
    }

    private void renderLobby(Graphics2D g) {
        AutoClient client = session.client();
        g.setColor(new Color(16, 18, 30));
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        g.setColor(new Color(245, 245, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        drawCentered(g, "Auto Battler Lobby", viewportWidth / 2, 90);

        g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        g.setColor(new Color(150, 155, 175));
        String where = session.isHost()
                ? "Hosting on port " + session.hostedServer().getPort()
                        + " — friends join your IP:port"
                : "Connected to " + address;
        drawCentered(g, where, viewportWidth / 2, 124);

        // Player list.
        List<AutoClient.LobbyPlayer> players = client.lobby().players();
        int y = 180;
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        for (AutoClient.LobbyPlayer p : players) {
            boolean me = p.id() == client.localId();
            boolean host = p.id() == client.lobby().hostId();
            g.setColor(me ? new Color(255, 210, 90) : new Color(210, 215, 230));
            String tags = (host ? "  [HOST]" : "") + (p.bot() ? "  [BOT]" : "")
                    + (me ? "  (you)" : "");
            drawCentered(g, p.name() + tags, viewportWidth / 2, y);
            y += 34;
        }
        g.setColor(new Color(120, 125, 145));
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        drawCentered(g, players.size() + " / " + AutoGame.MAX_PLAYERS + " players"
                        + (players.size() < AutoGame.MIN_PLAYERS
                        ? "  (need " + AutoGame.MIN_PLAYERS + "+ to start)" : ""),
                viewportWidth / 2, y + 8);

        // Buttons.
        buttons.clear();
        buttonLabels.clear();
        buttonActions.clear();
        if (client.isHost()) {
            addButton("Start Game", client::sendStart);
            addButton("Add Bot", client::sendAddBot);
            addButton("Remove Bot", client::sendRemoveBot);
        } else {
            g.setColor(new Color(150, 155, 175));
            g.setFont(new Font("SansSerif", Font.PLAIN, 17));
            drawCentered(g, "Waiting for the host to start...",
                    viewportWidth / 2, viewportHeight - 170);
        }
        addButton("Leave (Esc)", () -> {
            session.close();
            session = null;
            status = "";
        });
        layoutAndDrawButtons(g);

        for (String toast : client.pollToasts()) {
            status = toast;
        }
        if (!status.isEmpty()) {
            g.setColor(new Color(140, 200, 150));
            g.setFont(new Font("SansSerif", Font.PLAIN, 15));
            drawCentered(g, status, viewportWidth / 2, viewportHeight - 30);
        }
    }

    private void addButton(String label, Runnable action) {
        buttonLabels.add(label);
        buttonActions.add(action);
        buttons.add(new Rectangle()); // laid out in layoutAndDrawButtons
    }

    private void layoutAndDrawButtons(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.BOLD, 19));
        FontMetrics fm = g.getFontMetrics();
        int gap = 18;
        int totalW = 0;
        int[] widths = new int[buttonLabels.size()];
        for (int i = 0; i < buttonLabels.size(); i++) {
            widths[i] = fm.stringWidth(buttonLabels.get(i)) + 44;
            totalW += widths[i] + (i > 0 ? gap : 0);
        }
        int x = viewportWidth / 2 - totalW / 2;
        int y = viewportHeight - 130;
        for (int i = 0; i < buttonLabels.size(); i++) {
            Rectangle r = buttons.get(i);
            r.setBounds(x, y, widths[i], 46);
            boolean primary = i == 0 && buttonLabels.get(0).startsWith("Start");
            g.setColor(primary ? new Color(70, 120, 70) : new Color(45, 50, 70));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setColor(primary ? new Color(150, 230, 150) : new Color(160, 170, 200));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(buttonLabels.get(i),
                    r.x + (r.width - fm.stringWidth(buttonLabels.get(i))) / 2,
                    r.y + 30);
            x += widths[i] + gap;
        }
    }

    private void drawCentered(Graphics2D g, String s, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }
}
