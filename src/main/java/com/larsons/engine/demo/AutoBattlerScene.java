package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.BattleSim;
import com.larsons.engine.autobattler.Trait;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.autobattler.UnitInstance;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The auto-battler client: an isometric 8x8 board (the same diamond
 * projection the engine's {@code Camera} gives every scene) with the full
 * TFT / Auto Chess loop around it — a shop bar with rarity odds, a bench,
 * drag-free click-to-move unit placement on your half, an item bench, a live
 * synergy panel, opponent standings, and replicated combat with interpolated
 * unit movement, health/mana bars, floating damage numbers, and particles.
 *
 * <p>Everything authoritative happens on the server; this scene only sends
 * action requests and renders the replicated state. Runs with the
 * auto-battler's own shader look (bloom + vignette) via
 * {@link GameContext#overrideShaders}.
 *
 * <p>Controls: click a unit, then a cell/slot, to move it (planning phase);
 * click shop cards to buy; <b>D</b> rerolls, <b>F</b> buys XP, <b>S</b> sells
 * the selected unit; click an item gem then a unit to equip; <b>Esc</b> menu.
 */
public class AutoBattlerScene extends AbstractScene {

    private static final int TILE = 32; // world units per board cell
    private static final Color OWN_HALF = new Color(70, 90, 120);
    private static final Color[] COST_COLORS = {
            new Color(150, 155, 170),  // 1: gray
            new Color(110, 185, 110),  // 2: green
            new Color(95, 145, 235),   // 3: blue
            new Color(190, 110, 220),  // 4: purple
            new Color(235, 185, 80)    // 5: gold
    };

    private final GameContext ctx;
    private AutoSession session;
    private AutoClient client;

    private Camera camera;
    private final Particles particles = new Particles();

    // Selection & hover state (recomputed every update).
    private int selectedUnitId = -1;
    private int selectedItemIndex = -1;
    private int hoverCol = -1, hoverRow = -1;
    private int hoverBench = -1;
    private int hoverShop = -1;
    private int hoverItem = -1;
    private UnitInstance hoverUnit;
    private Trait hoverTrait;

    // Clickable HUD rectangles, laid out in update so update+render agree.
    private final Rectangle rerollBtn = new Rectangle();
    private final Rectangle xpBtn = new Rectangle();
    private final Rectangle sellBtn = new Rectangle();
    private final Rectangle[] shopCards = new Rectangle[5];
    private final Rectangle[] benchSlots = new Rectangle[9];
    private final List<Rectangle> itemSlots = new ArrayList<>();
    private final List<Rectangle> traitRows = new ArrayList<>();

    // Combat presentation.
    private final Map<Integer, double[]> displayed = new HashMap<>(); // uid -> smoothed cell pos
    private final List<Floater> floaters = new ArrayList<>();
    private double hitSfxCooldown;

    // Banners & toasts.
    private final List<Toast> toasts = new ArrayList<>();
    private AutoClient.RoundResult banner;
    private double bannerAge;

    private boolean paused;
    private int lastHp = -1;
    private int lastItemCount = -1;

    private static final class Floater {
        double wx, wy;
        String text;
        Color color;
        double age;
    }

    private static final class Toast {
        String text;
        double age;
    }

    public AutoBattlerScene(GameContext ctx) {
        this.ctx = ctx;
        for (int i = 0; i < shopCards.length; i++) shopCards[i] = new Rectangle();
        for (int i = 0; i < benchSlots.length; i++) benchSlots[i] = new Rectangle();
    }

    /** Called by the lobby scene before transitioning in; takes ownership. */
    public void adopt(AutoSession session) {
        this.session = session;
        this.client = session.client();
    }

    @Override
    public void onEnter() {
        camera = new Camera(Perspective.ISOMETRIC, viewportWidth, viewportHeight);
        camera.tileSize = TILE;
        camera.isoTileWidth = 64;
        camera.isoTileHeight = 32;
        layoutCamera();
        selectedUnitId = -1;
        selectedItemIndex = -1;
        paused = false;
        banner = null;
        toasts.clear();
        floaters.clear();
        displayed.clear();
        particles.clear();
        lastHp = -1;
        lastItemCount = -1;
        // The auto-battler always plays with shaders on: soft bloom + vignette.
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
        if (camera != null) layoutCamera();
    }

    /** Fit the 8x8 diamond between the side panels and above the shop bar. */
    private void layoutCamera() {
        camera.setViewport(viewportWidth, viewportHeight);
        double zoom = Math.min((viewportWidth - 460) / 560.0,
                (viewportHeight - 300) / 300.0);
        camera.zoom = Math.max(0.9, Math.min(2.2, zoom));
        // Focusing (c, c) puts planar x at 0; c is the planar y to centre on.
        // Board centre's planar y is 128; add a screen-space upward bias.
        double bias = (viewportHeight / 2.0 - boardCenterY()) / camera.zoom;
        camera.centerOn(128 + bias, 128 + bias);
    }

    private int boardCenterY() {
        return (viewportHeight - shopBarHeight() - 70) / 2 + 56;
    }

    private int shopBarHeight() {
        return 116;
    }

    // ------------------------------------------------------------------ update

    @Override
    public void update(double dt, InputManager input) {
        if (client == null) {
            scenes.transitionTo("autolobby");
            return;
        }

        drainFeeds(dt);
        particles.update(dt);
        for (int i = floaters.size() - 1; i >= 0; i--) {
            Floater f = floaters.get(i);
            f.age += dt;
            if (f.age > 1.1) floaters.remove(i);
        }
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            t.age += dt;
            if (t.age > 3.5) toasts.remove(i);
        }
        if (banner != null && (bannerAge += dt) > 3.2) banner = null;
        if (hitSfxCooldown > 0) hitSfxCooldown -= dt;
        smoothCombatPositions(dt);

        boolean disconnected = !client.isConnected();
        boolean over = client.gameOver() != null;

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (disconnected || over) {
                leave();
                return;
            }
            paused = !paused;
        }
        if (paused && input.isKeyJustPressed(KeyEvent.VK_L)) {
            leave();
            return;
        }
        if (paused || disconnected || over) return;

        layoutHud();
        computeHover(input);
        handleKeys(input);
        handleClicks(input);
    }

    private void leave() {
        if (session != null) {
            session.close();
            session = null;
            client = null;
        }
        scenes.transitionTo("autolobby");
    }

    /** Pull queued client feeds into presentation state (toasts, fx, banners). */
    private void drainFeeds(double dt) {
        for (String msg : client.pollToasts()) {
            Toast t = new Toast();
            t.text = msg;
            toasts.add(t);
            while (toasts.size() > 4) toasts.remove(0);
        }
        for (AutoClient.RoundResult r : client.pollResults()) {
            banner = r;
            bannerAge = 0;
            if (!r.won()) ctx.sfx(AudioManager.Sfx.HURT);
        }
        boolean home = isHomeSide();
        for (AutoClient.CombatEvent e : client.pollCombatEvents()) {
            double wx = (viewCol(e.x(), home) + 0.5) * TILE;
            double wy = (viewRow(e.y(), home) + 0.5) * TILE;
            switch (e.kind()) {
                case "hit", "crit" -> {
                    if (e.amount() >= 1) {
                        boolean crit = e.kind().equals("crit");
                        addFloater(wx, wy, "-" + (int) e.amount(),
                                crit ? new Color(255, 200, 80) : new Color(235, 235, 245));
                        if (hitSfxCooldown <= 0) {
                            hitSfxCooldown = 0.09;
                            ctx.sfx(AudioManager.Sfx.HIT);
                        }
                    }
                }
                case "heal" -> addFloater(wx, wy, "+" + (int) e.amount(),
                        new Color(130, 230, 140));
                case "cast" -> particles.burst(wx, wy, new Color(140, 170, 255), 8);
                case "die" -> {
                    particles.burst(wx, wy, new Color(220, 120, 90), 18);
                    ctx.sfx(AudioManager.Sfx.BOOM);
                }
                default -> { /* unknown fx */ }
            }
        }

        AutoClient.You you = client.you();
        if (you != null) {
            if (lastHp >= 0 && you.hp() < lastHp) ctx.sfx(AudioManager.Sfx.HURT);
            if (lastItemCount >= 0 && you.items().size() > lastItemCount) {
                ctx.sfx(AudioManager.Sfx.PICKUP);
            }
            lastHp = you.hp();
            lastItemCount = you.items().size();
        }
    }

    private void addFloater(double wx, double wy, String text, Color color) {
        Floater f = new Floater();
        f.wx = wx;
        f.wy = wy;
        f.text = text;
        f.color = color;
        if (floaters.size() < 48) floaters.add(f);
    }

    /** Ease displayed combat positions toward the latest snapshot. */
    private void smoothCombatPositions(double dt) {
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null) {
            displayed.clear();
            return;
        }
        boolean home = isHomeSide();
        double blend = Math.min(1, dt * 10);
        for (AutoClient.CombatUnit u : frame.units()) {
            double tx = viewCol(u.x(), home);
            double ty = viewRow(u.y(), home);
            double[] pos = displayed.get(u.id());
            if (pos == null) {
                displayed.put(u.id(), new double[]{tx, ty});
            } else {
                pos[0] += (tx - pos[0]) * blend;
                pos[1] += (ty - pos[1]) * blend;
            }
        }
    }

    /** True when this client's units render on the near (bottom) half. */
    private boolean isHomeSide() {
        AutoClient.MatchInfo m = client.match();
        return m == null || m.home();
    }

    private static double viewCol(double col, boolean home) {
        return home ? col : BattleSim.COLS - 1 - col;
    }

    private static double viewRow(double row, boolean home) {
        return home ? row : BattleSim.ROWS - 1 - row;
    }

    private boolean planning() {
        AutoClient.PhaseState p = client.phase();
        return p != null && p.phase() == AutoGame.Phase.PLAN;
    }

    // --- HUD layout (shared by update hit-testing and render) ---------------------

    private void layoutHud() {
        int w = viewportWidth, h = viewportHeight;
        int shopH = shopBarHeight();
        int barY = h - shopH;

        // Economy buttons on the left of the shop bar.
        xpBtn.setBounds(16, barY + 14, 120, 40);
        rerollBtn.setBounds(16, barY + 62, 120, 40);

        // Five shop cards, centred.
        int cardW = 150, cardH = shopH - 20;
        int cardsX = Math.max(152, w / 2 - (cardW * 5 + 4 * 10) / 2);
        for (int i = 0; i < 5; i++) {
            shopCards[i].setBounds(cardsX + i * (cardW + 10), barY + 10, cardW, cardH);
        }

        // Sell button on the right of the shop bar.
        sellBtn.setBounds(w - 170, barY + 34, 150, 48);

        // Bench strip above the shop bar.
        int slot = 58;
        int benchX = w / 2 - (slot * 9 + 8 * 4) / 2;
        int benchY = barY - slot - 10;
        for (int i = 0; i < 9; i++) {
            benchSlots[i].setBounds(benchX + i * (slot + 4), benchY, slot, slot);
        }

        // Item bench: two columns on the lower left, above the shop bar.
        itemSlots.clear();
        AutoClient.You you = client.you();
        int n = you == null ? 0 : you.items().size();
        for (int i = 0; i < n; i++) {
            int col = i % 2, row = i / 2;
            itemSlots.add(new Rectangle(14 + col * 40, barY - 60 - row * 40, 36, 36));
        }
    }

    private void computeHover(InputManager input) {
        int mx = input.getMouseX();
        int my = input.getMouseY();
        lastMouseX = mx;
        lastMouseY = my;
        hoverCol = -1;
        hoverRow = -1;
        hoverBench = -1;
        hoverShop = -1;
        hoverItem = -1;
        hoverUnit = null;
        hoverTrait = null;

        for (int i = 0; i < 9; i++) {
            if (benchSlots[i].contains(mx, my)) hoverBench = i;
        }
        for (int i = 0; i < 5; i++) {
            if (shopCards[i].contains(mx, my)) hoverShop = i;
        }
        for (int i = 0; i < itemSlots.size(); i++) {
            if (itemSlots.get(i).contains(mx, my)) hoverItem = i;
        }
        for (int i = 0; i < traitRows.size(); i++) {
            if (traitRows.get(i).contains(mx, my)) {
                List<TraitCount> counts = synergyRows();
                if (i < counts.size()) hoverTrait = counts.get(i).trait;
            }
        }

        // Board cell under the mouse (only when not over HUD chrome).
        if (hoverBench < 0 && hoverShop < 0 && hoverItem < 0
                && my < viewportHeight - shopBarHeight() - 70) {
            double[] world = camera.screenToWorld(mx, my);
            int c = (int) Math.floor(world[0] / TILE);
            int r = (int) Math.floor(world[1] / TILE);
            if (c >= 0 && c < BattleSim.COLS && r >= 0 && r < BattleSim.ROWS) {
                hoverCol = c;
                hoverRow = r;
            }
        }

        AutoClient.You you = client.you();
        if (you != null) {
            if (hoverBench >= 0) {
                hoverUnit = benchUnitAt(you, hoverBench);
            } else if (hoverCol >= 0 && planning()) {
                hoverUnit = boardUnitAt(you, hoverCol, hoverRow);
            }
        }
    }

    private void handleKeys(InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_D)) client.sendReroll();
        if (input.isKeyJustPressed(KeyEvent.VK_F)) client.sendBuyXp();
        if (input.isKeyJustPressed(KeyEvent.VK_S) && selectedUnitId >= 0) {
            client.sendSell(selectedUnitId);
            selectedUnitId = -1;
        }
        if (input.isRightMouseJustPressed()) {
            selectedUnitId = -1;
            selectedItemIndex = -1;
        }
    }

    private void handleClicks(InputManager input) {
        if (!input.isMouseJustPressed()) return;
        int mx = input.getMouseX();
        int my = input.getMouseY();
        AutoClient.You you = client.you();
        if (you == null) return;

        if (hoverShop >= 0) {
            client.sendBuy(hoverShop);
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (rerollBtn.contains(mx, my)) {
            client.sendReroll();
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (xpBtn.contains(mx, my)) {
            client.sendBuyXp();
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (selectedUnitId >= 0 && sellBtn.contains(mx, my)) {
            client.sendSell(selectedUnitId);
            selectedUnitId = -1;
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (hoverItem >= 0) {
            selectedItemIndex = selectedItemIndex == hoverItem ? -1 : hoverItem;
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }

        // An item is armed: clicking one of your units equips it.
        if (selectedItemIndex >= 0) {
            UnitInstance target = null;
            if (hoverBench >= 0) target = benchUnitAt(you, hoverBench);
            else if (hoverCol >= 0) target = boardUnitAt(you, hoverCol, hoverRow);
            if (target != null && planning()) {
                client.sendEquip(selectedItemIndex, target.id);
                ctx.sfx(AudioManager.Sfx.CLICK);
            }
            selectedItemIndex = -1;
            return;
        }

        // Unit movement: select, then click a destination (server swaps).
        if (selectedUnitId >= 0) {
            if (hoverBench >= 0 && planning()) {
                client.sendMoveToBench(selectedUnitId, hoverBench);
                ctx.sfx(AudioManager.Sfx.CLICK);
            } else if (hoverCol >= 0 && hoverRow >= BattleSim.ROWS / 2 && planning()) {
                client.sendMoveToBoard(selectedUnitId, hoverCol, hoverRow);
                ctx.sfx(AudioManager.Sfx.CLICK);
            }
            selectedUnitId = -1;
            return;
        }

        UnitInstance clicked = null;
        if (hoverBench >= 0) clicked = benchUnitAt(you, hoverBench);
        else if (hoverCol >= 0 && planning()) clicked = boardUnitAt(you, hoverCol, hoverRow);
        if (clicked != null) {
            selectedUnitId = clicked.id;
            ctx.sfx(AudioManager.Sfx.CLICK);
        }
    }

    private static UnitInstance benchUnitAt(AutoClient.You you, int slot) {
        for (UnitInstance u : you.bench()) if (u.bench == slot) return u;
        return null;
    }

    private static UnitInstance boardUnitAt(AutoClient.You you, int col, int row) {
        for (UnitInstance u : you.board()) if (u.col == col && u.row == row) return u;
        return null;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(Graphics2D g, float alpha) {
        int w = viewportWidth, h = viewportHeight;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(14, 16, 28),
                0, h, new Color(26, 24, 44)));
        g.fillRect(0, 0, w, h);
        if (client == null) return;

        layoutHud();
        drawBoard(g);
        if (client.phase() != null && client.phase().phase() == AutoGame.Phase.FIGHT
                && client.combatLatest() != null) {
            drawCombatUnits(g);
        } else {
            drawPlanningUnits(g);
        }
        particles.render(g, camera);
        drawFloaters(g);

        drawTopHud(g);
        drawSynergies(g);
        drawStandings(g);
        drawBench(g);
        drawShopBar(g);
        drawItemBench(g);
        drawToasts(g);
        drawBanner(g);
        drawTooltips(g);
        drawOverlays(g);
    }

    private void drawBoard(Graphics2D g) {
        boolean plan = planning();
        for (int r = 0; r < BattleSim.ROWS; r++) {
            for (int c = 0; c < BattleSim.COLS; c++) {
                Polygon p = tilePolygon(c, r);
                boolean own = r >= BattleSim.ROWS / 2;
                Color base = (c + r) % 2 == 0
                        ? new Color(40, 46, 66) : new Color(46, 52, 74);
                if (plan && own) {
                    base = (c + r) % 2 == 0
                            ? new Color(48, 60, 86) : new Color(54, 66, 94);
                }
                g.setColor(base);
                g.fillPolygon(p);
                if (plan && c == hoverCol && r == hoverRow && own) {
                    g.setColor(new Color(120, 170, 255, 70));
                    g.fillPolygon(p);
                }
                g.setColor(new Color(20, 22, 34));
                g.drawPolygon(p);
            }
        }
        // Board edge glow.
        g.setColor(new Color(90, 110, 170, 90));
        g.setStroke(new BasicStroke(2f));
        int[] sx = new int[4];
        int[] sy = new int[4];
        int[][] corners = {{0, 0}, {BattleSim.COLS, 0},
                {BattleSim.COLS, BattleSim.ROWS}, {0, BattleSim.ROWS}};
        int[] out = new int[2];
        for (int i = 0; i < 4; i++) {
            camera.worldToScreen(corners[i][0] * TILE, corners[i][1] * TILE, out);
            sx[i] = out[0];
            sy[i] = out[1];
        }
        g.drawPolygon(sx, sy, 4);
        g.setStroke(new BasicStroke(1f));
    }

    private Polygon tilePolygon(int c, int r) {
        int[] out = new int[2];
        Polygon p = new Polygon();
        double[][] corners = {{c, r}, {c + 1, r}, {c + 1, r + 1}, {c, r + 1}};
        for (double[] corner : corners) {
            camera.worldToScreen(corner[0] * TILE, corner[1] * TILE, out);
            p.addPoint(out[0], out[1]);
        }
        return p;
    }

    private void drawPlanningUnits(Graphics2D g) {
        AutoClient.You you = client.you();
        if (you == null) return;
        List<UnitInstance> board = new ArrayList<>(you.board());
        board.sort(Comparator.comparingInt(u -> u.col + u.row));
        int size = (int) (46 * camera.zoom);
        int[] out = new int[2];
        for (UnitInstance u : board) {
            UnitDef def = u.def();
            if (def == null) continue;
            camera.worldToScreen((u.col + 0.5) * TILE, (u.row + 0.5) * TILE, out);
            if (u.id == selectedUnitId) {
                g.setColor(new Color(255, 220, 110));
                g.setStroke(new BasicStroke(2.5f));
                g.drawPolygon(tilePolygon(u.col, u.row));
                g.setStroke(new BasicStroke(1f));
            }
            BufferedImage img = AutoSprites.unit(def, size, true);
            g.drawImage(img, out[0] - size / 2, out[1] - size + size / 4, null);
            AutoSprites.drawStars(g, u.star, out[0], out[1] - size + size / 8, 8);
            drawItemPips(g, u, out[0], out[1] + size / 6);
        }
    }

    private void drawItemPips(Graphics2D g, UnitInstance u, int cx, int y) {
        if (u.items.isEmpty()) return;
        int pip = 10;
        int x = cx - (u.items.size() * (pip + 2)) / 2;
        for (String key : u.items) {
            AutoItem item = AutoItems.get(key);
            g.setColor(item == null ? Color.GRAY : item.color);
            g.fillRect(x, y, pip, pip);
            g.setColor(new Color(20, 22, 32));
            g.drawRect(x, y, pip, pip);
            x += pip + 2;
        }
    }

    private void drawCombatUnits(Graphics2D g) {
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null) return;
        boolean home = isHomeSide();
        int friendlyTeam = home ? BattleSim.HOME : BattleSim.AWAY;

        List<AutoClient.CombatUnit> units = new ArrayList<>(frame.units());
        units.sort(Comparator.comparingDouble(u -> {
            double[] pos = displayed.get(u.id());
            return pos == null ? 0 : pos[0] + pos[1];
        }));

        int size = (int) (46 * camera.zoom);
        int[] out = new int[2];
        for (AutoClient.CombatUnit u : units) {
            if (u.dead()) continue;
            UnitDef def = AutoUnits.get(u.key());
            double[] pos = displayed.get(u.id());
            if (def == null || pos == null) continue;
            camera.worldToScreen((pos[0] + 0.5) * TILE, (pos[1] + 0.5) * TILE, out);
            boolean friendly = u.team() == friendlyTeam;
            BufferedImage img = AutoSprites.unit(def, size, friendly);
            g.drawImage(img, out[0] - size / 2, out[1] - size + size / 4, null);
            AutoSprites.drawStars(g, u.star(), out[0], out[1] - size + size / 12, 7);

            // Health + mana bars.
            int bw = (int) (40 * camera.zoom);
            int bx = out[0] - bw / 2;
            int by = out[1] - size + size / 4 - 10;
            g.setColor(new Color(15, 15, 22, 210));
            g.fillRect(bx, by, bw, 5);
            g.setColor(friendly ? new Color(110, 220, 120) : new Color(235, 100, 90));
            g.fillRect(bx, by, (int) (bw * Math.max(0, u.hp() / Math.max(1, u.maxHp()))), 5);
            if (u.manaMax() > 0) {
                g.setColor(new Color(15, 15, 22, 210));
                g.fillRect(bx, by + 6, bw, 3);
                g.setColor(new Color(90, 150, 255));
                g.fillRect(bx, by + 6, (int) (bw * u.mana() / u.manaMax()), 3);
            }
        }
    }

    private void drawFloaters(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        int[] out = new int[2];
        for (Floater f : floaters) {
            camera.worldToScreen(f.wx, f.wy, out);
            int alpha = (int) (255 * Math.max(0, 1 - f.age / 1.1));
            g.setColor(new Color(f.color.getRed(), f.color.getGreen(), f.color.getBlue(), alpha));
            g.drawString(f.text, out[0] - g.getFontMetrics().stringWidth(f.text) / 2,
                    (int) (out[1] - 34 - f.age * 26));
        }
    }

    // --- HUD ---------------------------------------------------------------------

    private void drawTopHud(Graphics2D g) {
        AutoClient.PhaseState phase = client.phase();
        if (phase == null) return;
        String phaseName = switch (phase.phase()) {
            case PLAN -> "Planning";
            case FIGHT -> "Combat";
            case POST -> "Results";
            case OVER -> "Game Over";
            default -> "";
        };
        String title = "Round " + phase.round() + (phase.pve() ? "  ·  Creep Round" : "");
        AutoClient.MatchInfo m = client.match();
        if (m != null && phase.phase() == AutoGame.Phase.FIGHT && !phase.pve()) {
            title += "  ·  vs " + m.opponent();
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(new Color(235, 238, 250));
        drawCentered(g, title, viewportWidth / 2, 30);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.setColor(new Color(160, 168, 190));
        drawCentered(g, phaseName + "  ·  " + (int) Math.ceil(phase.leftNow()) + "s",
                viewportWidth / 2, 54);

        int ping = client.pingMillis();
        if (ping >= 0) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(new Color(110, 115, 135));
            g.drawString(ping + " ms", viewportWidth - 52, 18);
        }
    }

    private record TraitCount(Trait trait, int count) {}

    private List<TraitCount> synergyRows() {
        AutoClient.You you = client.you();
        if (you == null) return List.of();
        Map<Trait, Integer> counts = BattleSim.countTraits(you.board());
        List<TraitCount> rows = new ArrayList<>();
        for (Map.Entry<Trait, Integer> e : counts.entrySet()) {
            rows.add(new TraitCount(e.getKey(), e.getValue()));
        }
        rows.sort((a, b) -> {
            int tierDiff = b.trait.tier(b.count) - a.trait.tier(a.count);
            if (tierDiff != 0) return tierDiff;
            int countDiff = b.count - a.count;
            return countDiff != 0 ? countDiff : a.trait.label.compareTo(b.trait.label);
        });
        return rows;
    }

    private void drawSynergies(Graphics2D g) {
        traitRows.clear();
        List<TraitCount> rows = synergyRows();
        int x = 14, y = 80;
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Synergies", x, y - 8);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (rows.isEmpty()) {
            g.setColor(new Color(110, 115, 135));
            g.drawString("Field units to activate traits", x, y + 12);
            return;
        }
        for (TraitCount tc : rows) {
            int tier = tc.trait.tier(tc.count);
            Rectangle row = new Rectangle(x, y - 14, 190, 22);
            traitRows.add(row);
            if (tier > 0) {
                g.setColor(new Color(50, 58, 84, 180));
                g.fillRoundRect(row.x - 4, row.y, row.width, row.height, 8, 8);
            }
            g.setColor(tier > 0 ? tc.trait.color : new Color(110, 115, 135));
            g.fillOval(x, y - 10, 12, 12);
            g.setColor(tier > 0 ? new Color(230, 234, 246) : new Color(130, 136, 156));
            StringBuilder marks = new StringBuilder();
            for (int threshold : tc.trait.thresholds) {
                if (marks.length() > 0) marks.append("/");
                marks.append(threshold);
            }
            g.drawString(tc.trait.label + "  " + tc.count + "  (" + marks + ")", x + 20, y + 1);
            y += 26;
        }
    }

    private void drawStandings(Graphics2D g) {
        List<AutoClient.Standing> rows = new ArrayList<>(client.standings());
        rows.sort((a, b) -> {
            if (a.alive() != b.alive()) return a.alive() ? -1 : 1;
            if (a.alive()) return b.hp() - a.hp();
            return a.place() - b.place();
        });
        int x = viewportWidth - 205, y = 80;
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Players", x, y - 8);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (AutoClient.Standing s : rows) {
            boolean me = s.id() == client.localId();
            if (me) {
                g.setColor(new Color(50, 58, 84, 180));
                g.fillRoundRect(x - 6, y - 14, 196, 24, 8, 8);
            }
            g.setColor(s.alive() ? (me ? new Color(255, 220, 120) : new Color(220, 224, 238))
                    : new Color(105, 108, 126));
            String label = s.name() + (s.bot() ? " [bot]" : "");
            if (!s.alive() && s.place() > 0) label = "#" + s.place() + " " + label;
            g.drawString(trim(g, label, 120), x, y + 2);
            if (s.alive()) {
                g.setColor(new Color(15, 15, 22, 200));
                g.fillRect(x + 128, y - 8, 60, 10);
                g.setColor(hpColor(s.hp()));
                g.fillRect(x + 128, y - 8, (int) (60 * Math.min(1, s.hp() / 100.0)), 10);
                g.setColor(new Color(230, 234, 246));
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString(String.valueOf(s.hp()), x + 132, y + 1);
                g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            }
            y += 27;
        }
    }

    private static Color hpColor(int hp) {
        if (hp > 60) return new Color(110, 210, 120);
        if (hp > 30) return new Color(230, 190, 90);
        return new Color(230, 110, 95);
    }

    private void drawBench(Graphics2D g) {
        AutoClient.You you = client.you();
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int i = 0; i < 9; i++) {
            Rectangle r = benchSlots[i];
            g.setColor(new Color(30, 34, 52, 220));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(i == hoverBench ? new Color(140, 170, 240) : new Color(60, 66, 92));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        }
        if (you == null) return;
        for (UnitInstance u : you.bench()) {
            if (u.bench < 0 || u.bench >= 9) continue;
            Rectangle r = benchSlots[u.bench];
            UnitDef def = u.def();
            if (def == null) continue;
            if (u.id == selectedUnitId) {
                g.setColor(new Color(255, 220, 110));
                g.setStroke(new BasicStroke(2.5f));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
                g.setStroke(new BasicStroke(1f));
            }
            BufferedImage img = AutoSprites.unit(def, r.width - 10, true);
            g.drawImage(img, r.x + 5, r.y + 2, null);
            AutoSprites.drawStars(g, u.star, r.x + r.width / 2, r.y + 2, 7);
            drawItemPips(g, u, r.x + r.width / 2, r.y + r.height - 12);
        }
    }

    private void drawShopBar(Graphics2D g) {
        AutoClient.You you = client.you();
        int w = viewportWidth, h = viewportHeight;
        int shopH = shopBarHeight();
        int barY = h - shopH;
        g.setColor(new Color(18, 20, 34, 235));
        g.fillRect(0, barY, w, shopH);
        g.setColor(new Color(60, 66, 92));
        g.drawLine(0, barY, w, barY);
        if (you == null) return;

        // Economy buttons.
        drawButton(g, xpBtn, "Buy XP  4g  (F)", you.gold() >= 4 && you.level() < 9);
        drawButton(g, rerollBtn, "Reroll  2g  (D)", you.gold() >= 2);

        // Gold / level / XP readout above the buttons.
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.setColor(new Color(255, 214, 100));
        g.drawString(you.gold() + " gold", 16, barY - 14);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(170, 176, 198));
        String xp = you.level() >= 9 ? "max" : you.xp() + "/" + you.xpNeed() + " xp";
        g.drawString("Level " + you.level() + "  ·  " + xp
                + "  ·  " + you.board().size() + "/" + you.boardCap() + " fielded", 100, barY - 14);
        if (you.streak() != 0) {
            g.setColor(you.streak() > 0 ? new Color(130, 220, 140) : new Color(230, 130, 110));
            g.drawString((you.streak() > 0 ? "W" : "L") + Math.abs(you.streak()) + " streak",
                    340, barY - 14);
        }

        // Shop cards.
        for (int i = 0; i < 5; i++) {
            Rectangle r = shopCards[i];
            String key = i < you.shop().size() ? you.shop().get(i) : null;
            g.setColor(new Color(28, 32, 50));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            if (key == null) {
                g.setColor(new Color(48, 52, 72));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
                continue;
            }
            UnitDef def = AutoUnits.get(key);
            if (def == null) continue;
            Color tier = COST_COLORS[def.cost - 1];
            g.setColor(i == hoverShop ? tier.brighter() : tier);
            g.setStroke(new BasicStroke(i == hoverShop ? 2.5f : 1.5f));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setStroke(new BasicStroke(1f));

            BufferedImage img = AutoSprites.unit(def, 54, true);
            g.drawImage(img, r.x + 6, r.y + r.height / 2 - 27, null);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.setColor(new Color(232, 236, 248));
            g.drawString(trim(g, def.name, r.width - 72), r.x + 62, r.y + 22);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(def.origin.color);
            g.drawString(def.origin.label, r.x + 62, r.y + 42);
            g.setColor(def.clazz.color);
            g.drawString(def.clazz.label, r.x + 62, r.y + 58);
            g.setColor(new Color(255, 214, 100));
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString(def.cost + "g", r.x + r.width - 30, r.y + r.height - 10);
            if (you.gold() < def.cost) {
                g.setColor(new Color(10, 10, 18, 130));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            }
        }

        // Sell button when a unit is selected.
        if (selectedUnitId >= 0) {
            UnitInstance sel = findOwn(selectedUnitId);
            if (sel != null && sel.def() != null) {
                drawButton(g, sellBtn, "Sell " + sel.def().value(sel.star) + "g  (S)", true,
                        new Color(120, 55, 55), new Color(235, 140, 120));
            }
        }
    }

    private UnitInstance findOwn(int unitId) {
        AutoClient.You you = client.you();
        if (you == null) return null;
        for (UnitInstance u : you.bench()) if (u.id == unitId) return u;
        for (UnitInstance u : you.board()) if (u.id == unitId) return u;
        return null;
    }

    private void drawButton(Graphics2D g, Rectangle r, String label, boolean enabled) {
        drawButton(g, r, label, enabled, new Color(45, 52, 76), new Color(160, 172, 205));
    }

    private void drawButton(Graphics2D g, Rectangle r, String label, boolean enabled,
                            Color fill, Color edge) {
        g.setColor(enabled ? fill : new Color(32, 35, 50));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setColor(enabled ? edge : new Color(70, 74, 95));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(enabled ? Color.WHITE : new Color(120, 124, 145));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2,
                r.y + r.height / 2 + 5);
    }

    private void drawItemBench(Graphics2D g) {
        AutoClient.You you = client.you();
        if (you == null || you.items().isEmpty()) return;
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(160, 168, 190));
        Rectangle first = itemSlots.isEmpty() ? null : itemSlots.get(itemSlots.size() - 1);
        if (first != null) g.drawString("Items", 14, first.y - 6);
        for (int i = 0; i < itemSlots.size() && i < you.items().size(); i++) {
            Rectangle r = itemSlots.get(i);
            AutoItem item = AutoItems.get(you.items().get(i));
            g.setColor(new Color(30, 34, 52, 220));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            boolean active = i == selectedItemIndex;
            g.setColor(active ? new Color(255, 220, 110)
                    : i == hoverItem ? new Color(140, 170, 240) : new Color(60, 66, 92));
            g.setStroke(new BasicStroke(active ? 2.5f : 1f));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g.setStroke(new BasicStroke(1f));
            if (item != null) {
                g.drawImage(AutoSprites.item(item, r.width - 8), r.x + 4, r.y + 4, null);
            }
        }
    }

    private void drawToasts(Graphics2D g) {
        int y = viewportHeight - shopBarHeight() - 86;
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            int alpha = (int) (230 * Math.max(0, Math.min(1, (3.5 - t.age) / 0.8)));
            g.setColor(new Color(240, 240, 250, alpha));
            drawCentered(g, t.text, viewportWidth / 2, y);
            y -= 20;
        }
    }

    private void drawBanner(Graphics2D g) {
        if (banner == null) return;
        float fade = (float) Math.max(0, Math.min(1, (3.2 - bannerAge) / 0.6));
        String text;
        Color color;
        if (banner.draw()) {
            text = "DRAW vs " + banner.opponent() + "  (-" + banner.damage() + " HP)";
            color = new Color(200, 205, 220);
        } else if (banner.won()) {
            text = "VICTORY vs " + banner.opponent();
            color = new Color(140, 230, 150);
        } else {
            text = "DEFEAT vs " + banner.opponent() + "  (-" + banner.damage() + " HP)";
            color = new Color(235, 120, 105);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int) (255 * fade)));
        drawCentered(g, text, viewportWidth / 2, 120);
    }

    // --- tooltips ------------------------------------------------------------------

    private void drawTooltips(Graphics2D g) {
        AutoClient.You you = client.you();
        if (you == null) return;
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();

        if (hoverTrait != null) {
            lines.add(hoverTrait.label + "  ("
                    + (hoverTrait.kind == Trait.Kind.ORIGIN ? "Origin" : "Class") + ")");
            colors.add(hoverTrait.color);
            lines.add(hoverTrait.description);
            colors.add(new Color(200, 205, 222));
        } else if (hoverShop >= 0 && hoverShop < you.shop().size()
                && you.shop().get(hoverShop) != null) {
            tooltipForDef(AutoUnits.get(you.shop().get(hoverShop)), 1, List.of(), lines, colors);
        } else if (hoverItem >= 0 && hoverItem < you.items().size()) {
            AutoItem item = AutoItems.get(you.items().get(hoverItem));
            if (item != null) {
                lines.add(item.name + (item.isComponent() ? "  (component)" : ""));
                colors.add(item.color);
                lines.add(item.statLine());
                colors.add(new Color(200, 205, 222));
                if (item.isComponent()) {
                    lines.add("Click, then click a unit to equip.");
                    lines.add("Two components combine on a unit.");
                    colors.add(new Color(150, 156, 178));
                    colors.add(new Color(150, 156, 178));
                }
            }
        } else if (hoverUnit != null) {
            tooltipForDef(hoverUnit.def(), hoverUnit.star, hoverUnit.items, lines, colors);
        }
        if (lines.isEmpty()) return;

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        FontMetrics fm = g.getFontMetrics();
        int tw = 0;
        for (String line : lines) tw = Math.max(tw, fm.stringWidth(line));
        int th = lines.size() * 18 + 14;
        int mx = Math.min(viewportWidth - tw - 28, Math.max(8, lastMouseX + 16));
        int my = Math.min(viewportHeight - th - 8, Math.max(8, lastMouseY - th - 6));
        g.setColor(new Color(12, 14, 24, 235));
        g.fillRoundRect(mx, my, tw + 20, th, 10, 10);
        g.setColor(new Color(90, 100, 140));
        g.drawRoundRect(mx, my, tw + 20, th, 10, 10);
        int y = my + 20;
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(colors.get(i));
            g.setFont(new Font("SansSerif", i == 0 ? Font.BOLD : Font.PLAIN, 13));
            g.drawString(lines.get(i), mx + 10, y);
            y += 18;
        }
    }

    private void tooltipForDef(UnitDef def, int star, List<String> items,
                               List<String> lines, List<Color> colors) {
        if (def == null) return;
        double mult = UnitDef.starMultiplier(star);
        lines.add(def.name + "  " + "★".repeat(Math.max(1, star)) + "  ·  " + def.cost + "g");
        colors.add(COST_COLORS[Math.max(0, def.cost - 1)]);
        if (def.origin != null && def.clazz != null) {
            lines.add(def.origin.label + " · " + def.clazz.label);
            colors.add(new Color(170, 178, 205));
        }
        lines.add((int) (def.hp * mult) + " HP  ·  " + (int) (def.ad * mult)
                + " AD  ·  " + def.attackSpeed + "/s  ·  range " + (int) def.range
                + "  ·  " + (int) def.armor + " armor");
        colors.add(new Color(200, 205, 222));
        for (String key : items) {
            AutoItem item = AutoItems.get(key);
            if (item != null) {
                lines.add("• " + item.name + "  (" + item.statLine() + ")");
                colors.add(item.color);
            }
        }
    }

    private int lastMouseX, lastMouseY;

    // --- overlays -----------------------------------------------------------------

    private void drawOverlays(Graphics2D g) {
        if (client.gameOver() != null) {
            AutoClient.GameOver over = client.gameOver();
            dim(g);
            g.setFont(new Font("SansSerif", Font.BOLD, 44));
            g.setColor(new Color(255, 214, 100));
            drawCentered(g, over.winner() + " wins!", viewportWidth / 2, viewportHeight / 3);
            g.setFont(new Font("SansSerif", Font.PLAIN, 20));
            int y = viewportHeight / 3 + 56;
            for (String row : over.standings()) {
                g.setColor(new Color(215, 220, 235));
                drawCentered(g, row, viewportWidth / 2, y);
                y += 30;
            }
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.setColor(new Color(150, 156, 178));
            drawCentered(g, "Press Esc to return to the lobby",
                    viewportWidth / 2, y + 24);
            return;
        }
        if (!client.isConnected()) {
            dim(g);
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            g.setColor(new Color(235, 120, 105));
            drawCentered(g, "Disconnected", viewportWidth / 2, viewportHeight / 2 - 20);
            g.setFont(new Font("SansSerif", Font.PLAIN, 17));
            g.setColor(new Color(200, 205, 220));
            drawCentered(g, String.valueOf(client.disconnectReason()),
                    viewportWidth / 2, viewportHeight / 2 + 12);
            g.setColor(new Color(150, 156, 178));
            drawCentered(g, "Press Esc to leave", viewportWidth / 2, viewportHeight / 2 + 44);
            return;
        }
        if (paused) {
            dim(g);
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            g.setColor(new Color(235, 238, 250));
            drawCentered(g, "Paused", viewportWidth / 2, viewportHeight / 2 - 24);
            g.setFont(new Font("SansSerif", Font.PLAIN, 17));
            g.setColor(new Color(190, 195, 214));
            drawCentered(g, "The match keeps running online",
                    viewportWidth / 2, viewportHeight / 2 + 10);
            drawCentered(g, "Esc — resume   ·   L — leave match",
                    viewportWidth / 2, viewportHeight / 2 + 40);
        }
    }

    private void dim(Graphics2D g) {
        g.setColor(new Color(8, 9, 16, 200));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
    }

    private void drawCentered(Graphics2D g, String s, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }

    private static String trim(Graphics2D g, String s, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String out = s;
        while (out.length() > 1 && fm.stringWidth(out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
