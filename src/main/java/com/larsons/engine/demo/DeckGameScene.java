package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.deckbuilder.CardDef;
import com.larsons.engine.deckbuilder.Cards;
import com.larsons.engine.deckbuilder.DeckClient;
import com.larsons.engine.deckbuilder.DeckGame;
import com.larsons.engine.deckbuilder.DeckSession;
import com.larsons.engine.deckbuilder.Leader;
import com.larsons.engine.deckbuilder.LocationDef;
import com.larsons.engine.deckbuilder.LocationIcon;
import com.larsons.engine.deckbuilder.Locations;
import com.larsons.engine.deckbuilder.Territory;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Council of Six table: the eight-location board across the top, the four
 * contested territories under it, the shared market row on the right, the
 * standings on the left, and your hand of cards along the bottom. Everything
 * authoritative happens on the server; this scene only sends action requests
 * and renders the replicated table.
 *
 * <p>The mode always runs with its own shader look — bloom + vignette through
 * the engine's GLSL-first {@code ShaderChain} — and every table event lands
 * as a styled, brightly-coloured {@link Particles} burst tuned to read
 * through that bloom pass: agent placements flare in their location's icon
 * colour, market buys shower gold sparks, troop deployments blast rings in
 * the territory's colour, scored points ring gold, and a conflict victory
 * fills the table with rising embers.
 *
 * <p>Controls: click a hand card, then a highlighted location to send an
 * agent there (troop plays then ask for a territory). Click market cards to
 * buy any time on your turn. <b>R</b> or the button reveals your remaining
 * hand (gold + might); <b>E</b>/the button ends your turn after revealing.
 * Right-click cancels a selection. <b>H</b> shows the rules; <b>Esc</b>
 * pauses (or closes overlays).
 */
public class DeckGameScene extends AbstractScene {

    private static final Color BG_TOP = new Color(18, 20, 34);
    private static final Color BG_BOTTOM = new Color(24, 22, 40);
    private static final Color PANEL = new Color(28, 31, 48);
    private static final Color PANEL_EDGE = new Color(70, 74, 95);
    private static final Color TEXT = new Color(225, 228, 240);
    private static final Color TEXT_DIM = new Color(150, 155, 175);
    private static final Color GOLD = new Color(235, 185, 80);
    private static final Color LORE = new Color(95, 145, 235);
    private static final Color MIGHT = new Color(220, 90, 80);
    private static final Color VP_COLOR = new Color(255, 215, 120);
    private static final Color HIGHLIGHT = new Color(140, 220, 150);
    private static final Color DISCONNECTED = new Color(235, 120, 110);

    // The rules card, shared with DeckLobbyScene's How to Play.
    private static final Font HELP_TITLE_FONT = new Font("SansSerif", Font.BOLD, 24);
    private static final Font HELP_BODY_FONT = new Font("SansSerif", Font.PLAIN, 15);
    private static final Color HELP_SCRIM = new Color(0, 0, 0, 200);
    private static final Color HELP_PANEL = new Color(26, 29, 46);
    private static final Color HELP_EDGE = new Color(90, 96, 122);
    private static final Color HELP_TITLE = new Color(245, 245, 255);
    private static final Color HELP_BULLET = new Color(200, 205, 224);
    private static final Color HELP_BODY = new Color(225, 228, 240);
    private static final Color HELP_FOOTER = new Color(150, 155, 175);

    // Every font this scene sets, hoisted. They used to be built inside the
    // render methods, which meant a fresh Font object per call per frame for
    // values that never change; the port makes the font an argument at each
    // call site, so the duplication became visible and the fix obvious.
    private static final Font SANS_BOLD_10 = new Font("SansSerif", Font.BOLD, 10);
    private static final Font SANS_BOLD_13 = new Font("SansSerif", Font.BOLD, 13);
    private static final Font SANS_BOLD_14 = new Font("SansSerif", Font.BOLD, 14);
    private static final Font SANS_BOLD_15 = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SANS_BOLD_16 = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SANS_BOLD_17 = new Font("SansSerif", Font.BOLD, 17);
    private static final Font SANS_BOLD_20 = new Font("SansSerif", Font.BOLD, 20);
    private static final Font SANS_BOLD_22 = new Font("SansSerif", Font.BOLD, 22);
    private static final Font SANS_BOLD_24 = new Font("SansSerif", Font.BOLD, 24);
    private static final Font SANS_BOLD_28 = new Font("SansSerif", Font.BOLD, 28);
    private static final Font SANS_BOLD_34 = new Font("SansSerif", Font.BOLD, 34);
    private static final Font SANS_BOLD_40 = new Font("SansSerif", Font.BOLD, 40);
    private static final Font SANS_ITALIC_12 = new Font("SansSerif", Font.ITALIC, 12);
    private static final Font SANS_PLAIN_11 = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font SANS_PLAIN_12 = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font SANS_PLAIN_13 = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SANS_PLAIN_14 = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font SANS_PLAIN_15 = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font SANS_PLAIN_16 = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font SANS_PLAIN_17 = new Font("SansSerif", Font.PLAIN, 17);
    private static final Font SANS_PLAIN_20 = new Font("SansSerif", Font.PLAIN, 20);

    private final GameContext ctx;
    private DeckSession session;
    private DeckClient client;

    private Camera camera; // identity world->screen mapping for the particles
    private final Particles particles = new Particles();

    private double animClock;
    private boolean paused;
    private boolean showHelp;

    // Selection state.
    private int selectedCard = -1;      // hand index
    private String pendingLoc;          // location waiting on a territory pick
    private int pendingCard = -1;
    private int hoverX = -1;            // pointer position captured in update,
    private int hoverY = -1;            // for render-time tooltips

    // Layout rectangles, recomputed every update so update+render agree.
    private final Rectangle[] locRects = new Rectangle[Locations.all().size()];
    private final Rectangle[] terrRects = new Rectangle[Territory.values().length];
    private final List<Rectangle> marketRects = new ArrayList<>();
    private final List<Rectangle> handRects = new ArrayList<>();
    private final List<Rectangle> standingRects = new ArrayList<>();
    private final Rectangle revealBtn = new Rectangle();
    private final Rectangle doneBtn = new Rectangle();
    private final Rectangle[] terrPickRects = new Rectangle[Territory.values().length];

    // Presentation feeds.
    private final List<Toast> toasts = new ArrayList<>();
    private final List<Floater> floaters = new ArrayList<>();
    private String banner;
    private double bannerAge;
    private int lastTurnPlayer = -1;

    private static final class Toast {
        String text;
        double age;
    }

    private static final class Floater {
        double x, y;
        String text;
        Color color;
        double age;
    }

    public DeckGameScene(GameContext ctx) {
        this.ctx = ctx;
        for (int i = 0; i < locRects.length; i++) locRects[i] = new Rectangle();
        for (int i = 0; i < terrRects.length; i++) terrRects[i] = new Rectangle();
        for (int i = 0; i < terrPickRects.length; i++) terrPickRects[i] = new Rectangle();
    }

    /** Called by the lobby scene before transitioning in; takes ownership. */
    public void adopt(DeckSession session) {
        this.session = session;
        this.client = session.client();
    }

    @Override
    public void onEnter() {
        camera = new Camera(Perspective.TOP_DOWN, viewportWidth, viewportHeight);
        camera.zoom = 1;
        camera.centerOn(viewportWidth / 2.0, viewportHeight / 2.0);
        particles.clear();
        toasts.clear();
        floaters.clear();
        banner = null;
        paused = false;
        showHelp = false;
        selectedCard = -1;
        pendingLoc = null;
        pendingCard = -1;
        lastTurnPlayer = -1;
        // Council of Six always plays with shaders on: the particle effects
        // are tuned for this bloom pass (bright cores bloom into glows).
        ctx.overrideShaders(List.of(Shaders.bloom(), Shaders.vignette()), 0.85);
    }

    @Override
    public void onExit() {
        if (session != null) {
            session.close();
            session = null;
            client = null;
        }
        ctx.applyLiveSettings(); // restore the game type's shader toggles
    }

    @Override
    public void onResize(int width, int height) {
        super.onResize(width, height);
        if (camera != null) {
            camera.setViewport(width, height);
            camera.centerOn(width / 2.0, height / 2.0);
        }
    }

    // ------------------------------------------------------------------ update

    @Override
    public void update(double dt, InputManager input) {
        if (client == null) {
            scenes.transitionTo("decklobby");
            return;
        }

        animClock += dt;
        particles.update(dt);
        layout();
        hoverX = input.getMouseX();
        hoverY = input.getMouseY();
        drainFeeds();
        for (int i = floaters.size() - 1; i >= 0; i--) {
            Floater f = floaters.get(i);
            f.age += dt;
            f.y -= 26 * dt;
            if (f.age > 1.4) floaters.remove(i);
        }
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            t.age += dt;
            if (t.age > 4.0) toasts.remove(i);
        }
        if (banner != null && (bannerAge += dt) > 3.0) banner = null;

        boolean disconnected = !client.isConnected();
        boolean over = client.gameOver() != null;

        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE)) {
            if (showHelp) {
                showHelp = false;
            } else if (disconnected || over) {
                leave();
                return;
            } else if (pendingLoc != null) {
                clearSelection();
            } else {
                paused = !paused;
            }
        }
        if (paused && input.isKeyJustPressed(KeyEvent.VK_L)) {
            leave();
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_H)) showHelp = !showHelp;
        if (paused || showHelp || disconnected || over) return;

        handleKeys(input);
        handlePointer(input);
    }

    private void leave() {
        if (session != null) {
            session.close();
            session = null;
            client = null;
        }
        scenes.transitionTo("decklobby");
    }

    private boolean myTurn() {
        return client.myTurn() && client.phase() != null
                && client.phase().phase() == DeckGame.Phase.PLAY;
    }

    private void handleKeys(InputManager input) {
        DeckClient.You you = client.you();
        if (you == null || !myTurn()) return;
        if (input.isKeyJustPressed(KeyEvent.VK_R) && !you.revealed()) {
            client.sendReveal();
            ctx.sfx(AudioManager.Sfx.CLICK);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_E)) {
            client.sendDone();
            ctx.sfx(AudioManager.Sfx.CLICK);
        }
    }

    private void handlePointer(InputManager input) {
        if (input.isRightMouseJustPressed()) {
            clearSelection();
            return;
        }
        if (!input.isMouseJustPressed()) return;
        int mx = input.getMouseX();
        int my = input.getMouseY();
        DeckClient.You you = client.you();
        if (you == null) return;
        boolean turn = myTurn();

        // Territory pick popup captures the pointer while open.
        if (pendingLoc != null) {
            Territory[] terrs = Territory.values();
            for (int i = 0; i < terrs.length; i++) {
                if (terrPickRects[i].contains(mx, my)) {
                    client.sendPlay(pendingCard, pendingLoc, terrs[i]);
                    ctx.sfx(AudioManager.Sfx.PLACE);
                    clearSelection();
                    return;
                }
            }
            clearSelection();
            return;
        }

        // Buttons.
        if (turn && revealBtn.contains(mx, my) && !you.revealed()) {
            client.sendReveal();
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (turn && doneBtn.contains(mx, my)) {
            client.sendDone();
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }

        // Hand cards (select / deselect).
        for (int i = 0; i < handRects.size(); i++) {
            if (handRects.get(i).contains(mx, my)) {
                if (turn && !you.revealed() && you.agentsLeft() > 0) {
                    selectedCard = selectedCard == i ? -1 : i;
                    ctx.sfx(AudioManager.Sfx.CLICK);
                }
                return;
            }
        }

        // Locations (play the selected card there).
        if (turn && selectedCard >= 0 && selectedCard < you.hand().size()) {
            CardDef card = Cards.get(you.hand().get(selectedCard));
            List<LocationDef> locs = Locations.all();
            for (int i = 0; i < locs.size(); i++) {
                LocationDef loc = locs.get(i);
                if (!locRects[i].contains(mx, my)) continue;
                if (card == null || !isLegalTarget(card, loc, you)) return;
                if (loc.gainTroops + card.playTroops > 0) {
                    // The play deploys troops: ask where before sending.
                    pendingLoc = loc.key;
                    pendingCard = selectedCard;
                } else {
                    client.sendPlay(selectedCard, loc.key, null);
                    ctx.sfx(AudioManager.Sfx.PLACE);
                    selectedCard = -1;
                }
                return;
            }
        }

        // Market cards (buy any time on your turn; the server validates).
        if (turn) {
            DeckClient.Table table = client.table();
            for (int i = 0; i < marketRects.size(); i++) {
                if (marketRects.get(i).contains(mx, my)) {
                    if (table != null && i < table.market().size()) {
                        client.sendBuy(i);
                        ctx.sfx(AudioManager.Sfx.CLICK);
                    }
                    return;
                }
            }
        }
    }

    private void clearSelection() {
        selectedCard = -1;
        pendingLoc = null;
        pendingCard = -1;
    }

    /** The client-side legality guess (the server is the real judge). */
    private boolean isLegalTarget(CardDef card, LocationDef loc, DeckClient.You you) {
        if (!card.icons.contains(loc.icon)) return false;
        if (you.gold() < loc.costGold || you.lore() < loc.costLore) return false;
        List<Integer> occ = occupantsOf(loc.key);
        if (occ.isEmpty()) return true;
        return you.leader() == Leader.BELLA && you.envoyReady();
    }

    private List<Integer> occupantsOf(String key) {
        DeckClient.Table table = client.table();
        if (table != null) {
            for (DeckClient.LocationState ls : table.locations()) {
                if (ls.key().equals(key)) return ls.occupants();
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------- event feeds

    /** Turn queued client feeds into particles, floaters, banners, sounds. */
    private void drainFeeds() {
        for (String msg : client.pollToasts()) {
            addToast(msg);
        }

        for (DeckClient.FxEvent e : client.pollEvents()) {
            switch (e.kind()) {
                case "play" -> {
                    Rectangle r = locRect(e.location());
                    LocationDef loc = Locations.get(e.location());
                    Color color = loc != null ? loc.icon.color : TEXT;
                    if (r != null) {
                        particles.burst(r.getCenterX(), r.getCenterY(), color, 16);
                        particles.burst(r.getCenterX(), r.getCenterY(),
                                Color.WHITE, 6, Particles.Style.SPARKS);
                    }
                    if (e.text() != null) addToast(e.text());
                    ctx.sfx(AudioManager.Sfx.PLACE);
                }
                case "buy" -> {
                    Rectangle r = marketRects.isEmpty() ? null : marketRects.get(0);
                    double cx = r != null ? r.getCenterX() : viewportWidth - 130;
                    double cy = r != null ? r.y + 80 : 200;
                    particles.burst(cx, cy, GOLD, 14, Particles.Style.SPARKS);
                    if (e.text() != null) addToast(e.text());
                    ctx.sfx(AudioManager.Sfx.PICKUP);
                }
                case "deploy" -> {
                    Rectangle r = e.territory() != null
                            ? terrRects[e.territory().ordinal()] : null;
                    if (r != null) {
                        particles.burst(r.getCenterX(), r.getCenterY(),
                                e.territory().color, 8 + 5 * e.amount(),
                                Particles.Style.RING);
                    }
                    ctx.sfx(AudioManager.Sfx.HIT);
                }
                case "vp" -> {
                    Rectangle r = standingRect(e.playerId());
                    double cx = r != null ? r.getCenterX() : viewportWidth / 2.0;
                    double cy = r != null ? r.getCenterY() : viewportHeight / 2.0;
                    particles.burst(cx, cy, VP_COLOR, 18, Particles.Style.RING);
                    particles.burst(cx, cy, VP_COLOR, 8, Particles.Style.MOTES);
                    addFloater(cx, cy, "+" + Math.max(1, e.amount()) + " VP", VP_COLOR);
                    if (e.text() != null) addToast(e.text());
                    ctx.sfx(AudioManager.Sfx.PICKUP);
                }
                case "reveal" -> {
                    Rectangle r = standingRect(e.playerId());
                    if (r != null) {
                        particles.burst(r.getCenterX(), r.getCenterY(),
                                LORE, 10, Particles.Style.MOTES);
                    }
                    if (e.text() != null) addToast(e.text());
                }
                case "conflict" -> {
                    banner = e.text();
                    bannerAge = 0;
                    double cx = viewportWidth / 2.0;
                    double cy = viewportHeight / 2.0 - 40;
                    particles.burst(cx, cy, MIGHT, 26, Particles.Style.EMBERS);
                    particles.burst(cx, cy, GOLD, 20, Particles.Style.RING);
                    if (e.playerId() == client.localId()) {
                        particles.burst(cx, cy, VP_COLOR, 24, Particles.Style.EMBERS);
                    }
                    ctx.sfx(AudioManager.Sfx.BOOM);
                }
                case "turn" -> {
                    if (e.playerId() == client.localId()
                            && lastTurnPlayer != e.playerId()) {
                        banner = "Your turn";
                        bannerAge = 0;
                        particles.burst(viewportWidth / 2.0, viewportHeight - 190,
                                HIGHLIGHT, 14, Particles.Style.SPARKS);
                        ctx.sfx(AudioManager.Sfx.CLICK);
                    } else if (e.text() != null) {
                        addToast(e.text());
                    }
                    lastTurnPlayer = e.playerId();
                }
                default -> {
                    if (e.text() != null) addToast(e.text());
                }
            }
        }

        // Keep the selected-card index inside the (replicated) hand.
        DeckClient.You you = client.you();
        if (you != null && selectedCard >= you.hand().size()) selectedCard = -1;
    }

    private Rectangle locRect(String key) {
        if (key == null) return null;
        List<LocationDef> locs = Locations.all();
        for (int i = 0; i < locs.size(); i++) {
            if (locs.get(i).key.equals(key)) return locRects[i];
        }
        return null;
    }

    private Rectangle standingRect(int playerId) {
        DeckClient.Table table = client.table();
        if (table == null) return null;
        List<DeckClient.Standing> rows = table.standings();
        for (int i = 0; i < rows.size() && i < standingRects.size(); i++) {
            if (rows.get(i).id() == playerId) return standingRects.get(i);
        }
        return null;
    }

    private void addToast(String text) {
        Toast t = new Toast();
        t.text = text;
        toasts.add(t);
        while (toasts.size() > 5) toasts.remove(0);
    }

    private void addFloater(double x, double y, String text, Color color) {
        Floater f = new Floater();
        f.x = x;
        f.y = y;
        f.text = text;
        f.color = color;
        floaters.add(f);
    }

    // ------------------------------------------------------------------ layout

    private static final int LEFT_W = 244;
    private static final int RIGHT_W = 252;
    private static final int TOP_H = 54;
    private static final int HAND_H = 208;

    private void layout() {
        int cx0 = LEFT_W + 12;
        int cx1 = viewportWidth - RIGHT_W - 12;
        int cw = cx1 - cx0;

        // Locations: 2 rows x 4 columns.
        int gap = 10;
        int locW = (cw - 3 * gap) / 4;
        int locH = 88;
        for (int i = 0; i < locRects.length; i++) {
            int col = i % 4;
            int row = i / 4;
            locRects[i].setBounds(cx0 + col * (locW + gap),
                    TOP_H + 10 + row * (locH + gap), locW, locH);
        }

        // Territories row.
        int ty = TOP_H + 10 + 2 * (locH + gap) + 6;
        int terrW = (cw - 3 * gap) / 4;
        int terrH = Math.max(64, viewportHeight - HAND_H - ty - 14);
        for (int i = 0; i < terrRects.length; i++) {
            terrRects[i].setBounds(cx0 + i * (terrW + gap), ty, terrW, terrH);
        }

        // Market column, sized to stop above the action buttons.
        marketRects.clear();
        DeckClient.Table table = client.table();
        int slots = table == null ? DeckGame.MARKET_SIZE : table.market().size();
        int mx = viewportWidth - RIGHT_W + 8;
        int mw = RIGHT_W - 20;
        int marketBottom = viewportHeight - 162;
        int mh = Math.max(78,
                (marketBottom - (TOP_H + 34) - 8) / DeckGame.MARKET_SIZE - 8);
        for (int i = 0; i < slots; i++) {
            marketRects.add(new Rectangle(mx, TOP_H + 34 + i * (mh + 8), mw, mh));
        }

        // Hand row.
        handRects.clear();
        DeckClient.You you = client.you();
        int n = you == null ? 0 : you.hand().size();
        if (n > 0) {
            int cardW = 116;
            int cardH = 158;
            int spacing = Math.min(cardW + 8, (viewportWidth - 460) / Math.max(1, n));
            int totalW = spacing * (n - 1) + cardW;
            int x0 = (viewportWidth - totalW) / 2;
            int y = viewportHeight - cardH - 10;
            for (int i = 0; i < n; i++) {
                handRects.add(new Rectangle(x0 + i * spacing, y, cardW, cardH));
            }
        }

        // Action buttons, right of the hand.
        revealBtn.setBounds(viewportWidth - 190, viewportHeight - 150, 170, 44);
        doneBtn.setBounds(viewportWidth - 190, viewportHeight - 98, 170, 44);

        // Territory pick popup, centred above the hand.
        int pw = 168;
        int ph = 44;
        int totalW = 4 * pw + 3 * 10;
        int px0 = viewportWidth / 2 - totalW / 2;
        int py = viewportHeight - HAND_H - 64;
        for (int i = 0; i < terrPickRects.length; i++) {
            terrPickRects[i].setBounds(px0 + i * (pw + 10), py, pw, ph);
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawTarget target, float alpha) {
        target.fillLinearGradient(0, 0, viewportWidth, viewportHeight,
                0, 0, BG_TOP.getRGB(), 0, viewportHeight, BG_BOTTOM.getRGB());

        if (client == null) return;
        DeckClient.Table table = client.table();
        DeckClient.You you = client.you();

        renderTopBar(target, table);
        renderStandings(target, table);
        renderLocations(target, table, you);
        renderTerritories(target, table);
        renderMarket(target, table, you);
        renderHand(target, you);
        renderButtons(target, you);
        if (pendingLoc != null) renderTerritoryPick(target);

        // The particle layer: rendered bright, bloomed by the shader chain.
        particles.render(target, camera);

        renderFloatersAndToasts(target);
        renderBanner(target);
        renderTooltip(target);

        if (paused) renderPause(target);
        if (client.gameOver() != null) renderGameOver(target);
        else if (!client.isConnected()) renderDisconnected(target);
        if (showHelp) renderHelpOverlay(target, viewportWidth, viewportHeight);
    }

    private void renderTopBar(DrawTarget target, DeckClient.Table table) {
        target.fillRect(0, 0, viewportWidth, TOP_H, PANEL);
        target.drawLine(0, TOP_H, viewportWidth, TOP_H, PANEL_EDGE);

        target.drawText("Council of Six", 16, 34, SANS_BOLD_22, TEXT);

        if (table == null) return;
        String round = "Round " + table.round();
        target.drawText(round, 220, 33, SANS_PLAIN_15, TEXT_DIM);

        DeckGame.ConflictReward cr = table.conflict();
        String conflict = "Conflict prize: " + rewardText(cr.firstVp(), cr.firstGold())
                + "   ·   2nd: " + rewardText(cr.secondVp(), cr.secondGold());
        target.drawText(conflict, 310, 33, SANS_PLAIN_15, MIGHT);

        // Whose turn + the clock.
        String turnText;
        DeckGame.Phase phase = client.phase() == null ? null : client.phase().phase();
        if (phase == DeckGame.Phase.POST) {
            turnText = "Round over — scoring...";
        } else if (table.turnPlayerId() == client.localId()) {
            turnText = "YOUR TURN — " + (int) Math.ceil(table.turnLeftNow()) + "s";
        } else {
            DeckClient.Standing s = standingOf(table, table.turnPlayerId());
            turnText = s == null ? ""
                    : s.name() + "'s turn — " + (int) Math.ceil(table.turnLeftNow()) + "s";
        }
        target.drawText(turnText,
                viewportWidth - target.textWidth(turnText, SANS_BOLD_16) - 16, 34,
                SANS_BOLD_16,
                table.turnPlayerId() == client.localId() ? HIGHLIGHT : TEXT_DIM);
    }

    private static String rewardText(int vp, int gold) {
        StringBuilder sb = new StringBuilder();
        if (vp > 0) sb.append(vp).append(" VP");
        if (gold > 0) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(gold).append(" gold");
        }
        return sb.isEmpty() ? "—" : sb.toString();
    }

    private static DeckClient.Standing standingOf(DeckClient.Table table, int id) {
        for (DeckClient.Standing s : table.standings()) {
            if (s.id() == id) return s;
        }
        return null;
    }

    private void renderStandings(DrawTarget target, DeckClient.Table table) {
        int x = 8;
        int y = TOP_H + 10;
        int w = LEFT_W - 8;
        int h = viewportHeight - HAND_H - y - 8;
        target.fillRoundRect(x, y, w, h, 10, 10, PANEL);
        target.drawRoundRect(x, y, w, h, 10, 10, PANEL_EDGE);

        target.drawText("PLAYERS", x + 12, y + 20, SANS_BOLD_13, TEXT_DIM);

        standingRects.clear();
        if (table == null) return;
        int rowY = y + 30;
        int rowH = 64;
        for (DeckClient.Standing s : table.standings()) {
            if (rowY + rowH > y + h) break;
            Rectangle r = new Rectangle(x + 6, rowY, w - 12, rowH - 6);
            standingRects.add(r);

            boolean me = s.id() == client.localId();
            boolean turn = s.id() == table.turnPlayerId();
            if (turn) {
                target.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8, new Color(46, 56, 60));
            }

            Color accent = s.leader() != null ? s.leader().color : TEXT_DIM;
            target.fillOval(r.x + 6, r.y + 8, 30, 30, accent);
            String initial = s.leader() != null
                    ? s.leader().friendName.substring(0, 1) : "?";
            target.drawText(initial, r.x + 16, r.y + 29, SANS_BOLD_16, Color.BLACK);

            String name = s.name() + (s.bot() ? " [BOT]" : "") + (me ? " (you)" : "");
            target.drawText(clip(target, SANS_BOLD_14, name, r.width - 96), r.x + 44, r.y + 16, SANS_BOLD_14,
                    me ? new Color(255, 210, 90) : TEXT);
            if (s.leader() != null) {
                target.drawText(s.leader().friendName + " · " + s.leader().title, r.x + 44,
                        r.y + 31, SANS_PLAIN_12, TEXT_DIM);
            }
            if (!s.connected()) {
                target.drawText("disconnected", r.x + 44, r.y + 45, SANS_PLAIN_12,
                        new Color(235, 120, 110));
            } else {
                target.drawText(s.revealed() ? "revealed"
                        : s.agentsLeft() + " agents", r.x + 44, r.y + 45, SANS_PLAIN_12, s.revealed() ? TEXT_DIM : HIGHLIGHT);
            }

            // VP big on the right; the resource line under it.
            String vp = s.vp() + " VP";
            target.drawText(vp,
                    r.x + r.width - target.textWidth(vp, SANS_BOLD_20) - 8,
                    r.y + 22, SANS_BOLD_20, VP_COLOR);

            String line = s.gold() + "g · " + s.lore() + "l · " + s.might() + "m · "
                    + s.troops() + "t";
            target.drawText(line,
                    r.x + r.width - target.textWidth(line, SANS_PLAIN_12) - 8,
                    r.y + 42, SANS_PLAIN_12, TEXT_DIM);

            rowY += rowH;
        }
    }

    private void renderLocations(DrawTarget target, DeckClient.Table table,
                                 DeckClient.You you) {
        CardDef selected = you != null && selectedCard >= 0
                && selectedCard < you.hand().size()
                ? Cards.get(you.hand().get(selectedCard)) : null;

        List<LocationDef> locs = Locations.all();
        for (int i = 0; i < locs.size(); i++) {
            LocationDef loc = locs.get(i);
            Rectangle r = locRects[i];
            boolean legal = selected != null && you != null && myTurn()
                    && isLegalTarget(selected, loc, you);

            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, PANEL);
            target.fillRoundRect(r.x, r.y, 8, r.height, 10, 10, loc.icon.color);

            target.drawText(clip(target, SANS_BOLD_14, loc.name, r.width - 24), r.x + 16, r.y + 20, SANS_BOLD_14,
                    selected != null && !legal ? TEXT_DIM : TEXT);

            target.drawText(loc.icon.label, r.x + 16, r.y + 37, SANS_PLAIN_12, loc.icon.color);
            drawWrapped(target, loc.summary(), r.x + 16, r.y + 54, r.width - 26, 14,
                    SANS_PLAIN_12,
                    selected != null && !legal ? new Color(110, 114, 132) : TEXT_DIM);

            // Agents standing here, as leader-coloured discs.
            List<Integer> occ = occupantsOf(loc.key);
            int dx = r.x + r.width - 24;
            for (int id : occ) {
                Color c = leaderColorOf(table, id);
                target.fillOval(dx, r.y + 8, 16, 16, c);
                target.drawOval(dx, r.y + 8, 16, 16, Color.BLACK);
                dx -= 12;
            }

            // A legal target pulses in a heavier ring; everything else keeps
            // the panel edge at 1px. Both used to be set on the Graphics2D and
            // read back by the shared draw below.
            Color ring = PANEL_EDGE;
            float ringWidth = 1f;
            if (legal) {
                float pulse = (float) (0.55 + 0.45 * Math.sin(animClock * 5));
                ring = new Color(HIGHLIGHT.getRed(), HIGHLIGHT.getGreen(),
                        HIGHLIGHT.getBlue(), (int) (140 + 80 * pulse));
                ringWidth = 2.5f;
            }
            target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10, ring, ringWidth);
        }
    }

    private Color leaderColorOf(DeckClient.Table table, int playerId) {
        if (table != null) {
            DeckClient.Standing s = standingOf(table, playerId);
            if (s != null && s.leader() != null) return s.leader().color;
        }
        return TEXT_DIM;
    }

    private void renderTerritories(DrawTarget target, DeckClient.Table table) {
        Territory[] terrs = Territory.values();
        for (int i = 0; i < terrs.length; i++) {
            Territory t = terrs[i];
            Rectangle r = terrRects[i];
            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, PANEL);
            target.fillRoundRect(r.x, r.y, r.width, 22, 10, 10, new Color(t.color.getRed(), t.color.getGreen(),
                    t.color.getBlue(), 60));
            target.drawText(clip(target, SANS_BOLD_13, t.label, r.width - 40), r.x + 10, r.y + 16, SANS_BOLD_13,
                    t.color);
            target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10, PANEL_EDGE);

            if (table == null) continue;
            Map<Integer, Integer> counts = table.territories().get(t);
            if (counts == null || counts.isEmpty()) {
                target.drawText("unclaimed", r.x + 10, r.y + 42, SANS_PLAIN_12, TEXT_DIM);
                continue;
            }

            // Strict majority holder gets the crown.
            int best = 0;
            int majority = -1;
            boolean tied = false;
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    majority = e.getKey();
                    tied = false;
                } else if (e.getValue() == best) {
                    tied = true;
                }
            }

            int rowY = r.y + 38;
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                if (rowY > r.y + r.height - 8) break;
                DeckClient.Standing s = standingOf(table, e.getKey());
                Color c = leaderColorOf(table, e.getKey());
                int pips = Math.min(e.getValue(), 8);
                for (int p = 0; p < pips; p++) {
                    target.fillOval(r.x + 10 + p * 12, rowY - 8, 9, 9, c);
                }
                String label = (s != null ? s.name() : "?") + " ×" + e.getValue()
                        + (!tied && e.getKey() == majority ? "  ♛ +1 VP" : "");
                target.drawText(clip(target, SANS_PLAIN_12, label, r.width - 20 - pips * 12), r.x + 14 + pips * 12,
                        rowY, SANS_PLAIN_12, !tied && e.getKey() == majority ? VP_COLOR : TEXT_DIM);
                rowY += 16;
            }
        }
    }

    private void renderMarket(DrawTarget target, DeckClient.Table table,
                              DeckClient.You you) {
        int x = viewportWidth - RIGHT_W + 2;
        target.drawText("MARKET — buy on your turn", x + 6, TOP_H + 24, SANS_BOLD_13, TEXT_DIM);

        if (table == null) return;
        List<String> market = table.market();
        for (int i = 0; i < marketRects.size() && i < market.size(); i++) {
            Rectangle r = marketRects.get(i);
            CardDef card = Cards.get(market.get(i));
            if (card == null) continue;
            int price = Math.max(0, card.cost
                    - (you != null && you.bazaarDiscount() ? 1 : 0));
            boolean affordable = you != null && you.gold() >= price && myTurn();

            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                    affordable ? new Color(36, 42, 58) : PANEL);

            target.drawText(clip(target, SANS_BOLD_14, card.name, r.width - 56), r.x + 10, r.y + 20, SANS_BOLD_14,
                    affordable ? TEXT : TEXT_DIM);

            // Price coin.
            target.fillOval(r.x + r.width - 34, r.y + 6, 24, 24, GOLD);
            String p = Integer.toString(price);
            target.drawText(p,
                    r.x + r.width - 22 - target.textWidth(p, SANS_BOLD_14) / 2,
                    r.y + 23, SANS_BOLD_14, Color.BLACK);

            renderIconChips(target, card, r.x + 10, r.y + 30);
            target.drawText(clip(target, SANS_PLAIN_12, "Play: " + card.playSummary(), r.width - 18), r.x + 10,
                    r.y + 60, SANS_PLAIN_12, affordable ? new Color(190, 195, 214) : TEXT_DIM);
            if (r.height >= 88) {
                target.drawText(clip(target, SANS_PLAIN_12, "Reveal: " + card.revealSummary(), r.width - 18),
                        r.x + 10, r.y + 76, SANS_PLAIN_12,
                        affordable ? new Color(190, 195, 214) : TEXT_DIM);
            }

            target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                    affordable ? new Color(110, 160, 120) : PANEL_EDGE);
        }
    }

    private void renderIconChips(DrawTarget target, CardDef card, int x, int y) {
        for (LocationIcon icon : LocationIcon.values()) {
            if (!card.icons.contains(icon)) continue;
            int w = target.textWidth(icon.label, SANS_BOLD_10) + 10;
            target.fillRoundRect(x, y, w, 14, 7, 7, new Color(icon.color.getRed(), icon.color.getGreen(),
                    icon.color.getBlue(), 70));
            target.drawText(icon.label, x + 5, y + 11, SANS_BOLD_10, icon.color);
            x += w + 5;
        }
    }

    private void renderHand(DrawTarget target, DeckClient.You you) {
        // Resource readout above the hand (clear of a lifted, selected card).
        int ry = viewportHeight - 190;
        if (you != null) {
            int x = 16;
            x = drawStat(target, x, ry, GOLD, you.gold() + " gold");
            x = drawStat(target, x, ry, LORE, you.lore() + " lore");
            x = drawStat(target, x, ry, MIGHT, you.might() + " might");
            x = drawStat(target, x, ry, VP_COLOR, you.vp() + " VP");
            x = drawStat(target, x, ry, HIGHLIGHT, you.agentsLeft() + " agents");
            target.drawText("deck " + you.deckCount() + " · discard " + you.discardCount(), x + 6,
                    ry, SANS_PLAIN_13, TEXT_DIM);
            if (you.bazaarDiscount()) {
                target.drawText("bazaar: buys -1 gold", x + 6, ry + 16, SANS_PLAIN_13, GOLD);
            }
        }

        if (you == null || you.hand().isEmpty()) {
            target.drawText(you != null && you.revealed()
                            ? "Hand revealed — buy from the market, then end your turn."
                            : "No cards in hand.", viewportWidth / 2 - 180, viewportHeight - 90, SANS_PLAIN_15, TEXT_DIM);
            return;
        }

        boolean active = myTurn() && !you.revealed() && you.agentsLeft() > 0;
        for (int i = 0; i < handRects.size() && i < you.hand().size(); i++) {
            Rectangle r = handRects.get(i);
            CardDef card = Cards.get(you.hand().get(i));
            if (card == null) continue;
            boolean sel = i == selectedCard;
            int lift = sel ? -16 : 0;

            target.fillRoundRect(r.x, r.y + lift, r.width, r.height, 10, 10,
                    sel ? new Color(44, 52, 72) : new Color(34, 38, 56));

            drawWrapped(target, card.name, r.x + 8, r.y + lift + 18, r.width - 16, 14,
                    SANS_BOLD_13, active ? TEXT : TEXT_DIM);
            renderIconChips(target, card, r.x + 8, r.y + lift + 44);

            drawWrapped(target, card.playSummary(), r.x + 8, r.y + lift + 76,
                    r.width - 16, 13, SANS_PLAIN_11,
                    active ? new Color(195, 200, 218) : TEXT_DIM);
            target.drawText(clip(target, SANS_PLAIN_11, "Rev: " + card.revealSummary(), r.width - 16), r.x + 8,
                    r.y + lift + r.height - 10, SANS_PLAIN_11, TEXT_DIM);

            target.drawRoundRect(r.x, r.y + lift, r.width, r.height, 10, 10,
                    sel ? HIGHLIGHT : PANEL_EDGE, sel ? 2.4f : 1f);
        }
    }

    private int drawStat(DrawTarget target, int x, int y, Color color, String text) {
        target.drawText(text, x, y, SANS_BOLD_15, color);
        return x + target.textWidth(text, SANS_BOLD_15) + 18;
    }

    private void renderButtons(DrawTarget target, DeckClient.You you) {
        if (you == null) return;
        boolean turn = myTurn();
        drawButton(target, revealBtn, "Reveal hand (R)", turn && !you.revealed(),
                new Color(70, 100, 130));
        drawButton(target, doneBtn, you.revealed() ? "End turn (E)" : "Pass — reveal & end (E)",
                turn, new Color(70, 120, 70));
        if (turn && selectedCard >= 0) {
            target.drawText("Click a highlighted location", viewportWidth - 190,
                    viewportHeight - 44, SANS_PLAIN_13, TEXT_DIM);
        }
    }

    private void drawButton(DrawTarget target, Rectangle r, String label, boolean enabled,
                            Color color) {
        target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                enabled ? color : new Color(40, 44, 60));
        target.drawText(label,
                r.x + (r.width - target.textWidth(label, SANS_BOLD_14)) / 2,
                r.y + 27, SANS_BOLD_14, enabled ? Color.WHITE : TEXT_DIM);
        target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                enabled ? new Color(160, 190, 210) : PANEL_EDGE);
    }

    private void renderTerritoryPick(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, viewportHeight - HAND_H, new Color(0, 0, 0, 120));
        String label = "Deploy troops to which territory?";
        target.drawText(label,
                viewportWidth / 2 - target.textWidth(label, SANS_BOLD_17) / 2,
                terrPickRects[0].y - 14, SANS_BOLD_17, TEXT);
        Territory[] terrs = Territory.values();
        for (int i = 0; i < terrs.length; i++) {
            Rectangle r = terrPickRects[i];
            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(terrs[i].color.getRed(), terrs[i].color.getGreen(),
                    terrs[i].color.getBlue(), 200));
            target.drawText(terrs[i].label,
                    r.x + (r.width - target.textWidth(terrs[i].label,
                            SANS_BOLD_14)) / 2,
                    r.y + 28, SANS_BOLD_14, Color.BLACK);
        }
    }

    private void renderFloatersAndToasts(DrawTarget target) {
        for (Floater f : floaters) {
            int a = (int) Math.max(0, 255 * (1 - f.age / 1.4));
            target.drawText(f.text, (int) f.x, (int) f.y, SANS_BOLD_17, new Color(f.color.getRed(), f.color.getGreen(),
                    f.color.getBlue(), a));
        }

        int y = viewportHeight - HAND_H - 16;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            int a = (int) Math.max(0, Math.min(255, 255 * (4.0 - t.age)));
            target.drawText(t.text, 16, y, SANS_PLAIN_14, new Color(210, 215, 230, a));
            y -= 20;
        }
    }

    private void renderBanner(DrawTarget target) {
        if (banner == null) return;
        float fade = (float) Math.max(0, Math.min(1, 3.0 - bannerAge));
        int w = target.textWidth(banner, SANS_BOLD_34);
        int x = viewportWidth / 2 - w / 2;
        int y = viewportHeight / 2 - 60;
/*WAS setColor new Color(0, 0, 0, (int) (150 * fade))*/
        target.fillRoundRect(x - 24, y - 36, w + 48, 52, 16, 16,
                new Color(0, 0, 0, (int) (150 * fade)));
/*WAS setColor new Color(255, 225, 150, (int) (255 * fade))*/
        target.drawText(banner, x, y, SANS_BOLD_34, new Color(255, 225, 150, (int) (255 * fade)));
    }

    private void renderTooltip(DrawTarget target) {
        // A flavor tooltip for the hovered hand or market card (hover position
        // captured in update, where the input is available).
        DeckClient.You you = client.you();
        DeckClient.Table table = client.table();
        CardDef card = null;
        int mx = -1;
        int my = -1;
        if (you != null) {
            for (int i = 0; i < handRects.size() && i < you.hand().size(); i++) {
                if (handRects.get(i).contains(hoverX, hoverY)) {
                    card = Cards.get(you.hand().get(i));
                    mx = handRects.get(i).x;
                    my = handRects.get(i).y;
                }
            }
        }
        if (card == null && table != null) {
            for (int i = 0; i < marketRects.size() && i < table.market().size(); i++) {
                if (marketRects.get(i).contains(hoverX, hoverY)) {
                    card = Cards.get(table.market().get(i));
                    mx = marketRects.get(i).x - 250;
                    my = marketRects.get(i).y;
                }
            }
        }
        if (card == null || card.flavor == null || card.flavor.isEmpty()) return;

        int w = 240;
        int x = Math.max(8, Math.min(viewportWidth - w - 8, mx));
        int y = Math.max(TOP_H + 8, my - 54);
        target.fillRoundRect(x, y, w, 46, 10, 10, new Color(12, 14, 24, 235));
        target.drawRoundRect(x, y, w, 46, 10, 10, PANEL_EDGE);
        drawWrapped(target, card.flavor, x + 10, y + 18, w - 20, 14,
                SANS_ITALIC_12, new Color(200, 195, 170));
    }

    private void renderPause(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(0, 0, 0, 170));
        drawCentered(target, "Paused", viewportWidth / 2, viewportHeight / 2 - 40,
                SANS_BOLD_34, TEXT);
        drawCentered(target, "The table keeps playing online.",
                viewportWidth / 2, viewportHeight / 2, SANS_PLAIN_17, TEXT_DIM);
        drawCentered(target, "Esc — resume    ·    L — leave the game",
                viewportWidth / 2, viewportHeight / 2 + 30, SANS_PLAIN_17, TEXT_DIM);
    }

    private void renderGameOver(DrawTarget target) {
        DeckClient.GameOver over = client.gameOver();
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(0, 0, 0, 190));
        drawCentered(target, over.winner() + " wins!", viewportWidth / 2, 160,
                SANS_BOLD_40, VP_COLOR);
        int y = 220;
        for (String row : over.standings()) {
            drawCentered(target, row, viewportWidth / 2, y, SANS_PLAIN_20, TEXT);
            y += 30;
        }
        drawCentered(target, "Press Esc to return to the lobby", viewportWidth / 2, y + 24,
                SANS_PLAIN_16, TEXT_DIM);
    }

    private void renderDisconnected(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(0, 0, 0, 190));
        drawCentered(target, "Disconnected", viewportWidth / 2, viewportHeight / 2 - 30,
                SANS_BOLD_28, DISCONNECTED);
        drawCentered(target, String.valueOf(client.disconnectReason()),
                viewportWidth / 2, viewportHeight / 2 + 4, SANS_PLAIN_17, TEXT_DIM);
        drawCentered(target, "Press Esc to return to the lobby",
                viewportWidth / 2, viewportHeight / 2 + 34, SANS_PLAIN_17, TEXT_DIM);
    }

    /** The rules card; shared with the lobby scene's How to Play. */
    static void renderHelpOverlay(DrawTarget target, int viewportWidth, int viewportHeight) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, HELP_SCRIM);
        int w = Math.min(760, viewportWidth - 60);
        int x = viewportWidth / 2 - w / 2;
        int y = Math.max(24, viewportHeight / 2 - 280);
        target.fillRoundRect(x, y, w, 560, 16, 16, HELP_PANEL);
        target.drawRoundRect(x, y, w, 560, 16, 16, HELP_EDGE);

        target.drawText("How to play Council of Six", x + 28, y + 40, HELP_TITLE_FONT, HELP_TITLE);

        String[] lines = {
                "First to 10 victory points at the end of a round wins (8 rounds max).",
                "",
                "Each round you draw 5 cards and get 2 agents. On your turn:",
                "  ·  PLAY a card: send an agent to a free location matching one of the",
                "     card's icons. Location and card effects both resolve. Turn ends.",
                "  ·  or REVEAL your remaining hand: its reveal values become gold to",
                "     spend and might committed to the conflict. Then buy freely from",
                "     the market and END TURN when you're done.",
                "  ·  BUY market cards with gold any time on your turn; they go to",
                "     your discard pile and come back around as you reshuffle.",
                "",
                "Troops deployed to territories stay there. At the end of each round:",
                "  ·  The most committed MIGHT wins the round's conflict prize",
                "     (second place takes a smaller one; ties split the consolation).",
                "  ·  A strict troop majority in a territory scores +1 VP.",
                "",
                "Every leader has one passive — read the cards in the lobby. That's",
                "the whole rulebook: no stack, no instants, no priority. Sorry, Dustin.",
        };
        int ly = y + 74;
        for (String line : lines) {
            target.drawText(line, x + 28, ly, HELP_BODY_FONT,
                    line.startsWith("  ·") ? HELP_BULLET : HELP_BODY);
            ly += 24;
        }
        target.drawText("H or Esc closes this.", x + 28, y + 536, HELP_BODY_FONT, HELP_FOOTER);
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

    private String clip(DrawTarget target, Font font, String s, int width) {
        if (target.textWidth(s, font) <= width) return s;
        String ellipsis = "…";
        int i = s.length();
        while (i > 1 && target.textWidth(s.substring(0, i) + ellipsis, font) > width) i--;
        return s.substring(0, i) + ellipsis;
    }
}
