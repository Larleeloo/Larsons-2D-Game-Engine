package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.deckbuilder.DeckClient;
import com.larsons.engine.deckbuilder.DeckGame;
import com.larsons.engine.deckbuilder.DeckProto;
import com.larsons.engine.deckbuilder.DeckServer;
import com.larsons.engine.deckbuilder.DeckSession;
import com.larsons.engine.deckbuilder.Leader;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Council of Six's front door: host a game on a port or join one by
 * {@code host[:port]} (Minecraft-style, exactly like the world game's and the
 * auto-battler's multiplayer screens), then wait at the table. In the lobby
 * every player clicks a leader card to claim their seat — the six friends of
 * the crew, each with a one-line passive — and the host adds bots and starts
 * the match. Every client jumps to {@link DeckGameScene} when round 1 begins.
 */
public class DeckLobbyScene extends AbstractScene {

    private final GameContext ctx;

    private ConfigForm form;
    private String playerName = "Player";
    private String address = "localhost";
    private String hostPort = Integer.toString(DeckProto.DEFAULT_PORT);

    private volatile String status = "";
    private volatile boolean connecting;
    private volatile DeckSession pendingSession;
    private DeckSession session;
    private boolean showHelp;

    private final List<Rectangle> buttons = new ArrayList<>();
    private final List<String> buttonLabels = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();
    private final Rectangle[] leaderCards = new Rectangle[Leader.values().length];

    public DeckLobbyScene(GameContext ctx) {
        this.ctx = ctx;
        for (int i = 0; i < leaderCards.length; i++) leaderCards[i] = new Rectangle();
    }

    @Override
    public void onEnter() {
        status = "";
        connecting = false;
        showHelp = false;
        session = null;
        DeckSession stale = pendingSession;
        pendingSession = null;
        if (stale != null) stale.close();
        buildForm();
    }

    private void buildForm() {
        form = new ConfigForm("Council of Six").theme(MenuTheme.dark());
        form.addText("Player name", () -> playerName, v -> playerName = v,
                Protocol.MAX_NAME_LENGTH);
        form.addText("Server address (host[:port])", () -> address, v -> address = v, 64);
        form.addAction("Join Game", this::startJoin);
        form.addText("Host on port", () -> hostPort, v -> hostPort = v, 5);
        form.addAction("Host Game", this::startHost);
        form.addAction("How to Play", () -> showHelp = true);
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
        DeckSession fresh = pendingSession;
        if (fresh != null) {
            pendingSession = null;
            connecting = false;
            session = fresh;
            status = "";
        }

        if (showHelp) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)
                    || input.isKeyJustPressed(KeyEvent.VK_H)
                    || input.isMouseJustPressed()) {
                showHelp = false;
            }
            return;
        }

        if (session == null) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
                scenes.transitionTo("startup");
                return;
            }
            form.update(dt, input);
            return;
        }

        DeckClient client = session.client();
        if (!client.isConnected()) {
            status = "Disconnected: " + client.disconnectReason();
            session.close();
            session = null;
            return;
        }

        // The match began: hand the session to the game scene.
        if (client.phase() != null && client.phase().phase() != DeckGame.Phase.LOBBY) {
            DeckGameScene game = (DeckGameScene) scenes.get("deckgame");
            game.adopt(session);
            session = null;
            scenes.transitionTo("deckgame");
            return;
        }

        if (input.isKeyJustPressed(KeyEvent.VK_H)) {
            showHelp = true;
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
            Leader[] leaders = Leader.values();
            for (int i = 0; i < leaders.length; i++) {
                if (leaderCards[i].contains(mx, my)) {
                    client.sendPickLeader(leaders[i]);
                    ctx.sfx(AudioManager.Sfx.CLICK);
                    return;
                }
            }
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).contains(mx, my)) {
                    buttonActions.get(i).run();
                    ctx.sfx(AudioManager.Sfx.CLICK);
                    break;
                }
            }
        }
    }

    private void startJoin() {
        if (connecting) return;
        connecting = true;
        String[] hostAndPort = Protocol.splitAddress(address);
        // The world protocol's default port is 7777; Council of Six is 7799.
        if (address.lastIndexOf(':') <= 0) {
            hostAndPort[1] = Integer.toString(DeckProto.DEFAULT_PORT);
        }
        status = "Connecting to " + hostAndPort[0] + ":" + hostAndPort[1] + " ...";
        Thread worker = new Thread(() -> {
            try {
                int port = Integer.parseInt(hostAndPort[1].trim());
                DeckClient client = DeckClient.connect(hostAndPort[0], port, playerName, 5000);
                pendingSession = new DeckSession(client, null);
            } catch (Exception e) {
                status = "Could not connect: " + message(e);
                connecting = false;
            }
        }, "deck-join");
        worker.setDaemon(true);
        worker.start();
    }

    private void startHost() {
        if (connecting) return;
        connecting = true;
        status = "Starting server on port " + hostPort + " ...";
        Thread worker = new Thread(() -> {
            DeckServer server = null;
            try {
                int port = Integer.parseInt(hostPort.trim());
                server = new DeckServer(new DeckGame.Config());
                server.start(port);
                DeckClient client = DeckClient.connect(
                        "127.0.0.1", server.getPort(), playerName, 5000);
                pendingSession = new DeckSession(client, server);
            } catch (Exception e) {
                if (server != null) server.stop();
                status = "Could not host: " + message(e);
                connecting = false;
            }
        }, "deck-host");
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
            g.drawString("A deckbuilding board game for 2-6 players, fully online. "
                    + "Host a game, or join a friend's IP and port.", 24, viewportHeight - 24);
        } else {
            renderLobby(g);
        }
        if (showHelp) DeckGameScene.renderHelpOverlay(g, viewportWidth, viewportHeight);
    }

    private void renderLobby(Graphics2D g) {
        DeckClient client = session.client();
        g.setColor(new Color(16, 18, 30));
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        g.setColor(new Color(245, 245, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 38));
        drawCentered(g, "Council of Six", viewportWidth / 2, 68);

        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.setColor(new Color(150, 155, 175));
        String where = session.isHost()
                ? "Hosting on port " + session.hostedServer().getPort()
                        + " — friends join your IP:port"
                : "Connected to " + address;
        drawCentered(g, where, viewportWidth / 2, 96);
        if (session.isHost()) {
            String lan = com.larsons.engine.net.Lan.siteLocalAddress();
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.setColor(new Color(140, 200, 150));
            drawCentered(g, lan != null
                            ? "Same network? They join:  " + lan + ":"
                                    + session.hostedServer().getPort()
                            : "Find your LAN IP (ipconfig / ifconfig) for same-network friends",
                    viewportWidth / 2, 118);
        }

        // Leader cards: click to claim your seat at the table.
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(200, 205, 225));
        drawCentered(g, "Pick your leader", viewportWidth / 2, 152);
        List<DeckClient.LobbyPlayer> players = client.lobby().players();
        Leader mine = null;
        for (DeckClient.LobbyPlayer p : players) {
            if (p.id() == client.localId()) mine = p.leader();
        }
        Leader[] leaders = Leader.values();
        int cardW = Math.min(190, (viewportWidth - 80) / 3 - 12);
        int cardH = 128;
        int cols = 3;
        int gridW = cols * cardW + (cols - 1) * 14;
        int x0 = viewportWidth / 2 - gridW / 2;
        int y0 = 166;
        for (int i = 0; i < leaders.length; i++) {
            Leader l = leaders[i];
            int cx = x0 + (i % cols) * (cardW + 14);
            int cy = y0 + (i / cols) * (cardH + 12);
            leaderCards[i].setBounds(cx, cy, cardW, cardH);

            String takenBy = null;
            boolean takenByMe = false;
            for (DeckClient.LobbyPlayer p : players) {
                if (p.leader() == l) {
                    takenBy = p.name();
                    takenByMe = p.id() == client.localId();
                }
            }

            g.setColor(new Color(30, 33, 50));
            g.fillRoundRect(cx, cy, cardW, cardH, 12, 12);
            g.setColor(l.color);
            g.fillRoundRect(cx, cy, cardW, 26, 12, 12);
            g.fillRect(cx, cy + 14, cardW, 12);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(l.friendName + " — " + l.title, cx + 10, cy + 18);

            g.setColor(new Color(205, 210, 228));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            drawWrapped(g, l.passive, cx + 10, cy + 44, cardW - 20, 15);

            if (takenBy != null) {
                g.setColor(takenByMe ? new Color(255, 210, 90) : new Color(150, 155, 175));
                g.setFont(new Font("SansSerif", Font.BOLD, 12));
                g.drawString(takenByMe ? "YOURS" : "taken by " + takenBy,
                        cx + 10, cy + cardH - 10);
            }
            g.setColor(takenByMe ? new Color(255, 210, 90)
                    : takenBy != null ? new Color(70, 74, 95) : new Color(120, 126, 150));
            g.setStroke(new BasicStroke(takenByMe ? 2.5f : 1.2f));
            g.drawRoundRect(cx, cy, cardW, cardH, 12, 12);
            g.setStroke(new BasicStroke(1f));
        }

        // Seated players, compactly (leader claims already show on the cards).
        int listY = y0 + 2 * (cardH + 12) + 24;
        int maxListY = viewportHeight - 152; // stay clear of the button row
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        for (DeckClient.LobbyPlayer p : players) {
            if (listY > maxListY) break;
            boolean me = p.id() == client.localId();
            boolean host = p.id() == client.lobby().hostId();
            g.setColor(me ? new Color(255, 210, 90) : new Color(210, 215, 230));
            String leader = p.leader() != null ? "  ·  " + p.leader().friendName
                    + " " + p.leader().title : "  ·  picking...";
            String tags = (host ? "  [HOST]" : "") + (p.bot() ? "  [BOT]" : "")
                    + (me ? "  (you)" : "");
            drawCentered(g, p.name() + leader + tags, viewportWidth / 2, listY);
            listY += 21;
        }
        g.setColor(new Color(120, 125, 145));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        String count = players.size() + " / " + DeckGame.MAX_PLAYERS + " players"
                + (players.size() < DeckGame.MIN_PLAYERS
                ? "  (need " + DeckGame.MIN_PLAYERS + "+ to start)" : "")
                + (client.isHost() ? "" : "  —  waiting for the host to start...");
        drawCentered(g, count, viewportWidth / 2, Math.min(listY + 4, maxListY + 18));

        // Buttons.
        buttons.clear();
        buttonLabels.clear();
        buttonActions.clear();
        if (client.isHost()) {
            addButton("Start Game", client::sendStart);
            addButton("Add Bot", client::sendAddBot);
            addButton("Remove Bot", client::sendRemoveBot);
        }
        addButton("How to Play (H)", () -> showHelp = true);
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
            drawCentered(g, status, viewportWidth / 2, viewportHeight - 28);
        }
    }

    private void addButton(String label, Runnable action) {
        buttonLabels.add(label);
        buttonActions.add(action);
        buttons.add(new Rectangle()); // laid out in layoutAndDrawButtons
    }

    private void layoutAndDrawButtons(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        int gap = 16;
        int totalW = 0;
        int[] widths = new int[buttonLabels.size()];
        for (int i = 0; i < buttonLabels.size(); i++) {
            widths[i] = fm.stringWidth(buttonLabels.get(i)) + 40;
            totalW += widths[i] + (i > 0 ? gap : 0);
        }
        int x = viewportWidth / 2 - totalW / 2;
        int y = viewportHeight - 118;
        for (int i = 0; i < buttonLabels.size(); i++) {
            Rectangle r = buttons.get(i);
            r.setBounds(x, y, widths[i], 44);
            boolean primary = i == 0 && buttonLabels.get(0).startsWith("Start");
            g.setColor(primary ? new Color(70, 120, 70) : new Color(45, 50, 70));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setColor(primary ? new Color(150, 230, 150) : new Color(160, 170, 200));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(buttonLabels.get(i),
                    r.x + (r.width - fm.stringWidth(buttonLabels.get(i))) / 2,
                    r.y + 29);
            x += widths[i] + gap;
        }
    }

    private void drawCentered(Graphics2D g, String s, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int width,
                             int lineHeight) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && !line.isEmpty()) {
                g.drawString(line.toString(), x, y);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) g.drawString(line.toString(), x, y);
    }
}
