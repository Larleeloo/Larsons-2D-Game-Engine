package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.deckbuilder.DeckClient;
import com.larsons.engine.deckbuilder.DeckGame;
import com.larsons.engine.deckbuilder.DeckProto;
import com.larsons.engine.deckbuilder.DeckServer;
import com.larsons.engine.deckbuilder.DeckSession;
import com.larsons.engine.deckbuilder.Leader;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
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

    private static final Font RESULT_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font RESULT_FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 38);
    private static final Font SUBTITLE_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font LAN_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font PICK_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font CARD_NAME_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font CARD_BODY_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font CLAIM_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SEAT_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font COUNT_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font BUTTON_FONT = new Font("SansSerif", Font.BOLD, 18);
    private static final Color CONNECTED = new Color(140, 200, 150);
    private static final Color FAILED = new Color(235, 120, 110);
    private static final Color LOBBY_BG = new Color(16, 18, 30);
    private static final Color TITLE = new Color(245, 245, 255);
    private static final Color SUBTITLE = new Color(150, 155, 175);
    private static final Color PICK_LABEL = new Color(200, 205, 225);
    private static final Color CARD_FILL = new Color(30, 33, 50);
    private static final Color CARD_BODY = new Color(205, 210, 228);
    private static final Color CARD_TAKEN_EDGE = new Color(70, 74, 95);
    private static final Color CARD_FREE_EDGE = new Color(120, 126, 150);
    private static final Color YOU = new Color(255, 210, 90);
    private static final Color OTHER_PLAYER = new Color(210, 215, 230);
    private static final Color COUNT = new Color(120, 125, 145);
    private static final Color PRIMARY_FILL = new Color(70, 120, 70);
    private static final Color PRIMARY_EDGE = new Color(150, 230, 150);
    private static final Color BUTTON_FILL = new Color(45, 50, 70);
    private static final Color BUTTON_EDGE = new Color(160, 170, 200);

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
        form.addAction("Controls (Key Binds)",
                () -> KeyBindsScene.open(scenes, "decklobby"));
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
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                    || input.isKeyJustPressed(KeyEvent.VK_H)
                    || input.isMouseJustPressed()) {
                showHelp = false;
            }
            return;
        }

        if (session == null) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
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
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
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
    public void render(DrawTarget target, float alpha) {
        if (session == null) {
            form.render(target, viewportWidth, viewportHeight);
            String s = status;
            if (!s.isEmpty()) {
                target.drawText(s, 24, viewportHeight - 52, RESULT_FONT,
                        s.startsWith("Could not") || s.startsWith("Disconnected")
                                ? FAILED : CONNECTED);
            }
            SceneChrome.hint(target, viewportHeight,
                    "A deckbuilding board game for 2-6 players, fully online. "
                            + "Host a game, or join a friend's IP and port.");
        } else {
            renderLobby(target);
        }
        if (showHelp) DeckGameScene.renderHelpOverlay(target, viewportWidth, viewportHeight);
    }

    private void renderLobby(DrawTarget target) {
        DeckClient client = session.client();
        target.fillRect(0, 0, viewportWidth, viewportHeight, LOBBY_BG);

        drawCentered(target, "Council of Six", viewportWidth / 2, 68, TITLE_FONT, TITLE);

        String where = session.isHost()
                ? "Hosting on port " + session.hostedServer().getPort()
                        + " — friends join your IP:port"
                : "Connected to " + address;
        drawCentered(target, where, viewportWidth / 2, 96, SUBTITLE_FONT, SUBTITLE);
        if (session.isHost()) {
            String lan = com.larsons.engine.net.Lan.siteLocalAddress();
            drawCentered(target, lan != null
                            ? "Same network? They join:  " + lan + ":"
                                    + session.hostedServer().getPort()
                            : "Find your LAN IP (ipconfig / ifconfig) for same-network friends",
                    viewportWidth / 2, 118, LAN_FONT, CONNECTED);
        }

        // Leader cards: click to claim your seat at the table.
        drawCentered(target, "Pick your leader", viewportWidth / 2, 152,
                PICK_FONT, PICK_LABEL);
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

            target.fillRoundRect(cx, cy, cardW, cardH, 12, 12, CARD_FILL);
            target.fillRoundRect(cx, cy, cardW, 26, 12, 12, l.color);
            target.fillRect(cx, cy + 14, cardW, 12, l.color);
            target.drawText(l.friendName + " — " + l.title, cx + 10, cy + 18,
                    CARD_NAME_FONT, Color.BLACK);

            drawWrapped(target, l.passive, cx + 10, cy + 44, cardW - 20, 15,
                    CARD_BODY_FONT, CARD_BODY);

            if (takenBy != null) {
                target.drawText(takenByMe ? "YOURS" : "taken by " + takenBy,
                        cx + 10, cy + cardH - 10, CLAIM_FONT,
                        takenByMe ? YOU : SUBTITLE);
            }
            // The claimed card is ringed at 2.5px and the rest at 1.2px, which
            // used to depend on the stroke being set back to 1f afterwards —
            // stated per call now that thickness travels with the draw.
            target.drawRoundRect(cx, cy, cardW, cardH, 12, 12,
                    (takenByMe ? YOU : takenBy != null ? CARD_TAKEN_EDGE : CARD_FREE_EDGE)
                            .getRGB(),
                    takenByMe ? 2.5f : 1.2f);
        }

        // Seated players, compactly (leader claims already show on the cards).
        int listY = y0 + 2 * (cardH + 12) + 24;
        int maxListY = viewportHeight - 152; // stay clear of the button row
        for (DeckClient.LobbyPlayer p : players) {
            if (listY > maxListY) break;
            boolean me = p.id() == client.localId();
            boolean host = p.id() == client.lobby().hostId();
            String leader = p.leader() != null ? "  ·  " + p.leader().friendName
                    + " " + p.leader().title : "  ·  picking...";
            String tags = (host ? "  [HOST]" : "") + (p.bot() ? "  [BOT]" : "")
                    + (me ? "  (you)" : "");
            drawCentered(target, p.name() + leader + tags, viewportWidth / 2, listY,
                    SEAT_FONT, me ? YOU : OTHER_PLAYER);
            listY += 21;
        }
        String count = players.size() + " / " + DeckGame.MAX_PLAYERS + " players"
                + (players.size() < DeckGame.MIN_PLAYERS
                ? "  (need " + DeckGame.MIN_PLAYERS + "+ to start)" : "")
                + (client.isHost() ? "" : "  —  waiting for the host to start...");
        drawCentered(target, count, viewportWidth / 2, Math.min(listY + 4, maxListY + 18),
                COUNT_FONT, COUNT);

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
        layoutAndDrawButtons(target);

        for (String toast : client.pollToasts()) {
            status = toast;
        }
        if (!status.isEmpty()) {
            drawCentered(target, status, viewportWidth / 2, viewportHeight - 28,
                    RESULT_FONT_PLAIN, CONNECTED);
        }
    }

    private void addButton(String label, Runnable action) {
        buttonLabels.add(label);
        buttonActions.add(action);
        buttons.add(new Rectangle()); // laid out in layoutAndDrawButtons
    }

    private void layoutAndDrawButtons(DrawTarget target) {
        int gap = 16;
        int totalW = 0;
        int[] widths = new int[buttonLabels.size()];
        for (int i = 0; i < buttonLabels.size(); i++) {
            widths[i] = target.textWidth(buttonLabels.get(i), BUTTON_FONT) + 40;
            totalW += widths[i] + (i > 0 ? gap : 0);
        }
        int x = viewportWidth / 2 - totalW / 2;
        int y = viewportHeight - 118;
        for (int i = 0; i < buttonLabels.size(); i++) {
            Rectangle r = buttons.get(i);
            r.setBounds(x, y, widths[i], 44);
            boolean primary = i == 0 && buttonLabels.get(0).startsWith("Start");
            target.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12,
                    primary ? PRIMARY_FILL : BUTTON_FILL);
            target.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12,
                    primary ? PRIMARY_EDGE : BUTTON_EDGE);
            target.drawText(buttonLabels.get(i),
                    r.x + (r.width - target.textWidth(buttonLabels.get(i), BUTTON_FONT)) / 2,
                    r.y + 29, BUTTON_FONT, Color.WHITE);
            x += widths[i] + gap;
        }
    }

    private void drawCentered(DrawTarget target, String s, int cx, int y,
                              Font font, Color color) {
        target.drawText(s, cx - target.textWidth(s, font) / 2, y, font, color);
    }

    private void drawWrapped(DrawTarget target, String text, int x, int y, int width,
                             int lineHeight, Font font, Color color) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (target.textWidth(candidate, font) > width && !line.isEmpty()) {
                target.drawText(line.toString(), x, y, font, color);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) target.drawText(line.toString(), x, y, font, color);
    }
}
