package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.autobattler.AnimState;
import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.BattleSim;
import com.larsons.engine.autobattler.BoardTheme;
import com.larsons.engine.autobattler.Element;
import com.larsons.engine.autobattler.Relic;
import com.larsons.engine.autobattler.SynergyCategory;
import com.larsons.engine.autobattler.Trait;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.autobattler.UnitInstance;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
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
 * drag-and-drop unit placement on your half, an item bench, a live
 * synergy panel, opponent standings, and replicated combat with interpolated
 * unit movement, health/mana bars, floating damage numbers, and particles.
 *
 * <p>Combat is presented with full readability effects: units play replicated
 * animation states (idle/walk/attack/cast/hit/death), ranged attacks fly as
 * animated projectiles that deliver their damage number on impact, melee hits
 * slash, casts flare, and deaths leave fading corpses. During combat the left
 * panel becomes a per-unit damage meter split by damage type. Clicking a
 * player in the standings scouts their board (with their public stats) in an
 * overlay. Every texture — board tiles, units per animation state, items,
 * projectiles — can be reskinned with sprite sheets via {@link Skins} (see the
 * lobby's skin customization menu).
 *
 * <p>Everything authoritative happens on the server; this scene only sends
 * action requests and renders the replicated state. Runs with the
 * auto-battler's own shader look (bloom + vignette) via
 * {@link GameContext#overrideShaders}.
 *
 * <p>Controls: drag a unit between the bench and your half of the board to
 * place it, and drag an item gem onto a unit to equip it (planning phase). A
 * plain click still works too — click a unit then a cell/slot to move it, and
 * click an item gem then a unit to equip. Click shop cards to buy; click a
 * player's name to scout their board; <b>D</b> rerolls, <b>F</b> buys XP,
 * <b>S</b> sells the selected unit; <b>Esc</b> closes the scout view / menu.
 * Those letters are the defaults — this game has its own group of rebindable
 * actions ({@code GameAction.Category.AUTO_BATTLER}) and its own controls
 * screen on the lobby, and the buttons on screen name whatever they are bound
 * to now.
 * The <b>Lock</b> toggle keeps the current shop through the round change;
 * the <b>Gather / Spread / Flip</b> buttons rearrange the fielded board in
 * one click; a selected unit with items offers a once-per-round
 * <b>Remove items</b> button; and dragging a Wisp onto a unit you own a pair
 * of fuses it as the missing copy. The top-left badge shows your current
 * relic (hover it for details) — relics re-deal every 10 rounds.
 */
public class AutoBattlerScene extends AbstractScene {

    private static final int TILE = 32; // world units per board cell
    private static final Color[] COST_COLORS = {
            new Color(150, 155, 170),  // 1: gray
            new Color(110, 185, 110),  // 2: green
            new Color(95, 145, 235),   // 3: blue
            new Color(190, 110, 220),  // 4: purple
            new Color(235, 185, 80)    // 5: gold
    };

    // Damage-meter type colours (attack / ability / healing).
    private static final Color LEGEND_TEXT = new Color(160, 168, 190);
    private static final Color OWN_HALF_TINT = new Color(90, 130, 200, 40);
    private static final Color HOVER_TINT = new Color(120, 170, 255, 70);
    private static final Color TILE_EDGE = new Color(20, 22, 34);
    private static final Color SELECTED_TILE = new Color(255, 220, 110);

    /** Half the arrow trail's 2.5px stroke, rounded out to whole pixels. */
    private static final int ARROW_CAP = 1;

    /** The slash arc's stroke width, and therefore its round cap's diameter. */
    private static final int SLASH_WIDTH = 3;

    /** Reused by {@link #tilePolygon}; see the note there. */
    private final Polygon tile = new Polygon();
    private final int[] boltXs = new int[4];
    private final int[] boltYs = new int[4];

    private static final Color DMG_PHYSICAL = new Color(235, 150, 80);
    private static final Color DMG_MAGIC = new Color(140, 160, 255);
    private static final Color DMG_HEAL = new Color(120, 220, 140);

    private static final double MISSILE_SPEED = 340;  // world units per second
    private static final double SLASH_SECONDS = 0.22;
    private static final double CORPSE_SECONDS = 0.9;

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
    private SynergyCategory hoverCategory;

    // Synergy panel category filter: null shows every synergy. The chip row
    // is laid out while rendering and hit-tested a frame later, the same
    // deferred pattern the standings rows use.
    private SynergyCategory categoryFilter;
    private final List<Rectangle> categoryChips = new ArrayList<>();
    private final List<SynergyCategory> chipCategories = new ArrayList<>();

    // Drag-and-drop state. A press over a unit or item gem "grabs" it; once the
    // pointer moves past a small threshold the grab becomes a drag and drops on
    // release (moving the unit / equipping the item). A press that never moves
    // that far falls back to the plain click-to-select/place model, so both
    // interaction styles coexist. Grabbing only happens during the planning
    // phase — the only time a board is editable.
    private static final int DRAG_THRESHOLD = 6; // pixels of travel to start a drag
    private int grabUnitId = -1;    // unit picked up by the active press, or -1
    private int grabItemIndex = -1; // item picked up by the active press, or -1
    private boolean dragging;       // the grab has crossed the movement threshold
    private int pressX, pressY;     // screen position where the active press began
    private boolean pointerWasDown; // left-button state last tick, for release edges

    // Clickable HUD rectangles, laid out in update so update+render agree.
    private final Rectangle rerollBtn = new Rectangle();
    private final Rectangle xpBtn = new Rectangle();
    private final Rectangle sellBtn = new Rectangle();
    private final Rectangle lockBtn = new Rectangle();
    private final Rectangle unequipBtn = new Rectangle();
    private final Rectangle relicBadge = new Rectangle();
    private final Rectangle[] arrangeBtns = new Rectangle[3];
    private static final String[] ARRANGE_MODES = {"front", "spread", "flip"};
    private static final String[] ARRANGE_LABELS = {"Gather", "Spread", "Flip"};
    private final Rectangle[] shopCards = new Rectangle[5];
    private final Rectangle[] benchSlots = new Rectangle[9];
    private final List<Rectangle> itemSlots = new ArrayList<>();
    private final List<Rectangle> traitRows = new ArrayList<>();
    private boolean hoverRelic;

    // Combat presentation.
    private double animClock; // drives idle/skin animation everywhere
    private final Map<Integer, double[]> displayed = new HashMap<>(); // uid -> smoothed cell pos
    private final Map<Integer, UnitFx> unitFx = new HashMap<>();      // uid -> anim state clock
    private final List<Floater> floaters = new ArrayList<>();
    private final List<Missile> missiles = new ArrayList<>();
    private final List<Slash> slashes = new ArrayList<>();
    private final List<Corpse> corpses = new ArrayList<>();
    private double hitSfxCooldown;

    // Board scouting (click a standings row). Row rectangles are recorded
    // while rendering the standings and hit-tested a frame later, the same
    // deferred pattern ConfigForm uses.
    private int viewingId = -1;
    private double viewRefresh;
    private final Rectangle viewPanelRect = new Rectangle();
    private final Rectangle viewCloseRect = new Rectangle();
    private final List<Rectangle> standingRects = new ArrayList<>();
    private final List<Integer> standingIds = new ArrayList<>();

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

    /** Client-side clock per combat unit: how long its anim state has played. */
    private static final class UnitFx {
        AnimState state = AnimState.IDLE;
        double time;
    }

    /** An animated projectile flying from attacker to target (world coords). */
    private static final class Missile {
        double x, y;          // current position
        double tx, ty;        // impact position
        double vx, vy;        // velocity (world units / s)
        double life;          // seconds until impact
        double trailAccum;
        String kind;          // arrow | orb | bolt (skin key + procedural style)
        Color color;
        String impactText;    // damage floater delivered on arrival, or null
        Color impactColor;
        Element element;      // elemental payload, for the impact particles
    }

    /** A short melee slash arc at the point of impact. */
    private static final class Slash {
        double wx, wy;
        double age;
        Color color;
    }

    /** A fading corpse left where a unit died. */
    private static final class Corpse {
        UnitDef def;
        boolean friendly;
        double wx, wy;
        double age;
    }

    private static final Font SANS_BOLD_28 = new Font("SansSerif", Font.BOLD, 28);
    private static final Font SANS_BOLD_30 = new Font("SansSerif", Font.BOLD, 30);
    private static final Font SANS_BOLD_34 = new Font("SansSerif", Font.BOLD, 34);
    private static final Font SANS_BOLD_44 = new Font("SansSerif", Font.BOLD, 44);
    private static final Font SANS_PLAIN_16 = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font SANS_PLAIN_17 = new Font("SansSerif", Font.PLAIN, 17);
    private static final Font SANS_PLAIN_20 = new Font("SansSerif", Font.PLAIN, 20);
    private static final Font SANS_BOLD_10 = new Font("SansSerif", Font.BOLD, 10);
    private static final Font SANS_BOLD_11 = new Font("SansSerif", Font.BOLD, 11);
    private static final Font SANS_BOLD_12 = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SANS_BOLD_13 = new Font("SansSerif", Font.BOLD, 13);
    private static final Font SANS_BOLD_14 = new Font("SansSerif", Font.BOLD, 14);
    private static final Font SANS_BOLD_15 = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SANS_BOLD_20 = new Font("SansSerif", Font.BOLD, 20);
    private static final Font SANS_PLAIN_11 = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font SANS_PLAIN_12 = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font SANS_PLAIN_13 = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SANS_PLAIN_14 = new Font("SansSerif", Font.PLAIN, 14);

    public AutoBattlerScene(GameContext ctx) {
        this.ctx = ctx;
        for (int i = 0; i < shopCards.length; i++) shopCards[i] = new Rectangle();
        for (int i = 0; i < benchSlots.length; i++) benchSlots[i] = new Rectangle();
        for (int i = 0; i < arrangeBtns.length; i++) arrangeBtns[i] = new Rectangle();
    }

    /** Called by the lobby scene before transitioning in; takes ownership. */
    public void adopt(AutoSession session) {
        this.session = session;
        this.client = session.client();
    }

    @Override
    public void onEnter() {
        camera = new Camera(Perspective.THREE_D, viewportWidth, viewportHeight);
        camera.tileSize = TILE;
        // A fixed diamond rather than a camera anyone can move: an arena is a
        // board, and a board is drawn from one angle for ever. The levels'
        // camera turns and tilts (see Camera); this one does neither, so the
        // squares stay where the layout below measured them.
        camera.useBoardDiamond(64, 32);
        layoutCamera();
        selectedUnitId = -1;
        selectedItemIndex = -1;
        clearGrab();
        pointerWasDown = false;
        paused = false;
        banner = null;
        viewingId = -1;
        toasts.clear();
        floaters.clear();
        displayed.clear();
        particles.clear();
        clearCombatFx();
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
        return AutoHud.SHOP_BAR_HEIGHT;
    }

    // ------------------------------------------------------------------ update

    @Override
    public void update(double dt, InputManager input) {
        if (client == null) {
            scenes.transitionTo("autolobby");
            return;
        }

        animClock += dt;
        drainFeeds(dt);
        particles.update(dt);
        updateCombatFx(dt);
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

        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE)) {
            if (disconnected || over) {
                leave();
                return;
            }
            if (viewingId >= 0) {
                closeView();
            } else {
                paused = !paused;
            }
        }
        if (paused && KeyBinds.pressed(input, GameAction.AUTO_LEAVE)) {
            leave();
            return;
        }
        if (paused || disconnected || over) return;

        layoutHud();
        computeHover(input);
        if (viewingId >= 0) {
            updateViewOverlay(dt, input);
            return; // the overlay captures the pointer; no board edits beneath it
        }
        handleKeys(input);
        handlePointer(input);
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
                case "hit", "crit" -> handleHitEvent(e, home, wx, wy);
                case "heal" -> {
                    if (e.amount() >= 1) {
                        addFloater(wx, wy, "+" + (int) e.amount(), DMG_HEAL);
                    }
                    particles.burst(wx, wy, DMG_HEAL, 5);
                }
                case "cast" -> {
                    UnitDef def = defOfCombatUnit(e.sourceId());
                    Color accent = def != null ? def.accent : new Color(140, 170, 255);
                    particles.burst(wx, wy, accent, 12);
                }
                case "die" -> {
                    particles.burst(wx, wy, new Color(220, 120, 90), 18);
                    ctx.sfx(AudioManager.Sfx.BOOM);
                    spawnCorpse(e.sourceId(), wx, wy);
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

    /**
     * A damage event: distant attackers launch an animated projectile that
     * delivers the damage number on impact; adjacent ones read as a melee
     * slash with the number popping immediately.
     */
    private void handleHitEvent(AutoClient.CombatEvent e, boolean home, double wx, double wy) {
        boolean crit = e.kind().equals("crit");
        String text = e.amount() >= 1 ? "-" + (int) e.amount() : null;
        // Elemental hits colour their damage numbers; crits stay gold.
        Color color = crit ? new Color(255, 200, 80)
                : e.element() != null ? e.element().color : new Color(235, 235, 245);

        double dx = e.sx() - e.x();
        double dy = e.sy() - e.y();
        boolean ranged = e.sourceId() > 0 && dx * dx + dy * dy > 1.6 * 1.6;
        if (ranged) {
            spawnMissile(e, home, wx, wy, text, color);
            return;
        }
        if (text != null) addFloater(wx, wy, text, color);
        Slash s = new Slash();
        s.wx = wx;
        s.wy = wy;
        s.color = crit ? new Color(255, 200, 80) : new Color(230, 235, 250);
        if (slashes.size() < 48) slashes.add(s);
        if (e.element() != null) elementBurst(e.element(), wx, wy);
        playHitSfx();
    }

    /**
     * The element-specific impact effect: fire embers float up, cryo shards
     * rain down, electric sparks snap, corrosive drips fall, explosive rings
     * blast outward, radiation motes linger.
     */
    private void elementBurst(Element element, double wx, double wy) {
        Particles.Style style = switch (element) {
            case FIRE -> Particles.Style.EMBERS;
            case CRYO -> Particles.Style.SHARDS;
            case ELECTRIC -> Particles.Style.SPARKS;
            case CORROSIVE -> Particles.Style.DRIP;
            case EXPLOSIVE -> Particles.Style.RING;
            case RADIATION -> Particles.Style.MOTES;
        };
        particles.burst(wx, wy, element.color, 9, style);
    }

    private void spawnMissile(AutoClient.CombatEvent e, boolean home,
                              double wx, double wy, String text, Color color) {
        if (missiles.size() >= 48) return;
        Missile m = new Missile();
        m.x = (viewCol(e.sx(), home) + 0.5) * TILE;
        m.y = (viewRow(e.sy(), home) + 0.5) * TILE;
        m.tx = wx;
        m.ty = wy;
        double dx = m.tx - m.x, dy = m.ty - m.y;
        double dist = Math.max(0.001, Math.sqrt(dx * dx + dy * dy));
        m.vx = dx / dist * MISSILE_SPEED;
        m.vy = dy / dist * MISSILE_SPEED;
        m.life = dist / MISSILE_SPEED;
        UnitDef def = defOfCombatUnit(e.sourceId());
        m.kind = missileKind(def);
        // Elemental shots fly in their element's colour so the payload reads
        // mid-flight, not just on impact.
        m.element = e.element();
        m.color = m.element != null ? m.element.color
                : def != null ? def.accent : new Color(200, 210, 235);
        m.impactText = text;
        m.impactColor = color;
        missiles.add(m);
    }

    /** Which projectile a unit fires, keyed for skins ({@code projectile/<kind>}). */
    private static String missileKind(UnitDef def) {
        if (def == null || def.clazz == null) return "bolt";
        return switch (def.clazz) {
            case ARCHER -> "arrow";
            case MAGE -> "orb";
            default -> "bolt";
        };
    }

    private UnitDef defOfCombatUnit(int uid) {
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null || uid <= 0) return null;
        for (AutoClient.CombatUnit u : frame.units()) {
            if (u.id() == uid) return AutoUnits.get(u.key());
        }
        return null;
    }

    private void spawnCorpse(int uid, double wx, double wy) {
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null || uid <= 0 || corpses.size() >= 32) return;
        boolean home = isHomeSide();
        int friendlyTeam = home ? BattleSim.HOME : BattleSim.AWAY;
        for (AutoClient.CombatUnit u : frame.units()) {
            if (u.id() != uid) continue;
            Corpse c = new Corpse();
            c.def = AutoUnits.get(u.key());
            c.friendly = u.team() == friendlyTeam;
            double[] pos = displayed.get(uid);
            if (pos != null) {
                c.wx = (pos[0] + 0.5) * TILE;
                c.wy = (pos[1] + 0.5) * TILE;
            } else {
                c.wx = wx;
                c.wy = wy;
            }
            if (c.def != null) corpses.add(c);
            return;
        }
    }

    private void playHitSfx() {
        if (hitSfxCooldown <= 0) {
            hitSfxCooldown = 0.09;
            ctx.sfx(AudioManager.Sfx.HIT);
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

    /** Advance missiles, slashes, corpses, and per-unit anim-state clocks. */
    private void updateCombatFx(double dt) {
        for (int i = missiles.size() - 1; i >= 0; i--) {
            Missile m = missiles.get(i);
            m.x += m.vx * dt;
            m.y += m.vy * dt;
            m.life -= dt;
            m.trailAccum += dt;
            if (m.trailAccum >= 0.035) {
                m.trailAccum = 0;
                particles.burst(m.x, m.y, m.color, 1);
            }
            if (m.life <= 0) {
                if (m.impactText != null) addFloater(m.tx, m.ty, m.impactText, m.impactColor);
                if (m.element != null) elementBurst(m.element, m.tx, m.ty);
                else particles.burst(m.tx, m.ty, m.color, 7);
                playHitSfx();
                missiles.remove(i);
            }
        }
        for (int i = slashes.size() - 1; i >= 0; i--) {
            Slash s = slashes.get(i);
            s.age += dt;
            if (s.age > SLASH_SECONDS) slashes.remove(i);
        }
        for (int i = corpses.size() - 1; i >= 0; i--) {
            Corpse c = corpses.get(i);
            c.age += dt;
            if (c.age > CORPSE_SECONDS) corpses.remove(i);
        }
        for (UnitFx fx : unitFx.values()) fx.time += dt;
    }

    private void clearCombatFx() {
        missiles.clear();
        slashes.clear();
        corpses.clear();
        unitFx.clear();
    }

    /** Ease displayed combat positions toward the latest snapshot. */
    private void smoothCombatPositions(double dt) {
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null) {
            displayed.clear();
            if (!unitFx.isEmpty() || !missiles.isEmpty()) clearCombatFx();
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
            UnitFx fx = unitFx.computeIfAbsent(u.id(), k -> new UnitFx());
            if (fx.state != u.state()) {
                fx.state = u.state();
                fx.time = 0;
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

    private boolean fighting() {
        AutoClient.PhaseState p = client.phase();
        return p != null && p.phase() == AutoGame.Phase.FIGHT && client.combatLatest() != null;
    }

    private int itemCount() {
        AutoClient.You you = client == null ? null : client.you();
        return you == null ? 0 : you.items().size();
    }

    // --- HUD layout (shared by update hit-testing and render) ---------------------

    private void layoutHud() {
        int w = viewportWidth, h = viewportHeight;

        xpBtn.setBounds(AutoHud.xpButton(w, h));
        rerollBtn.setBounds(AutoHud.rerollButton(w, h));
        sellBtn.setBounds(AutoHud.sellButton(w, h));
        lockBtn.setBounds(AutoHud.lockButton(w, h));
        unequipBtn.setBounds(AutoHud.unequipButton(w, h));
        relicBadge.setBounds(AutoHud.relicBadge(w));
        for (int i = 0; i < 3; i++) {
            arrangeBtns[i].setBounds(AutoHud.arrangeButton(w, i));
        }
        for (int i = 0; i < 5; i++) {
            shopCards[i].setBounds(AutoHud.shopCard(w, h, i));
        }
        for (int i = 0; i < 9; i++) {
            benchSlots[i].setBounds(AutoHud.benchSlot(w, h, i));
        }

        itemSlots.clear();
        int n = itemCount();
        for (int i = 0; i < n; i++) {
            itemSlots.add(AutoHud.itemSlot(w, h, i));
        }

        viewPanelRect.setBounds(AutoHud.viewPanel(w, h));
        viewCloseRect.setBounds(viewPanelRect.x + viewPanelRect.width - 36,
                viewPanelRect.y + 10, 26, 26);
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
        hoverCategory = null;
        hoverRelic = false;
        if (viewingId >= 0) return; // the scout overlay owns the pointer

        AutoClient.You me = client.you();
        hoverRelic = me != null && me.relic() != null && relicBadge.contains(mx, my);

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
        for (int i = 0; i < categoryChips.size() && i < chipCategories.size(); i++) {
            if (categoryChips.get(i).contains(mx, my)) hoverCategory = chipCategories.get(i);
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
        if (KeyBinds.pressed(input, GameAction.AUTO_REROLL)) client.sendReroll();
        if (KeyBinds.pressed(input, GameAction.AUTO_BUY_XP)) client.sendBuyXp();
        if (KeyBinds.pressed(input, GameAction.AUTO_SELL) && selectedUnitId >= 0) {
            client.sendSell(selectedUnitId);
            selectedUnitId = -1;
        }
        if (input.isRightMouseJustPressed()) {
            selectedUnitId = -1;
            selectedItemIndex = -1;
        }
    }

    /**
     * Drive the pointer each tick: turn press/move/release into either a drag
     * (grab a unit or item, drop it to move/equip) or, when the press never
     * travels far, a plain click resolved by {@link #resolveClick}.
     */
    private void handlePointer(InputManager input) {
        int mx = input.getMouseX();
        int my = input.getMouseY();
        boolean down = input.isMouseDown();
        boolean justPressed = input.isMouseJustPressed();

        // Right-click already deselects (handleKeys); it also cancels a drag.
        if (input.isRightMouseJustPressed()) clearGrab();

        if (justPressed) beginPointer(mx, my);

        // Promote a grab to a drag once the pointer travels past the threshold;
        // starting a drag supersedes any click-selection.
        if ((grabUnitId >= 0 || grabItemIndex >= 0) && !dragging
                && (Math.abs(mx - pressX) > DRAG_THRESHOLD
                    || Math.abs(my - pressY) > DRAG_THRESHOLD)) {
            dragging = true;
            selectedUnitId = -1;
            selectedItemIndex = -1;
        }

        // Release: the button went up this tick, or it was a tap that pressed
        // and released within one tick (never observed as held).
        boolean released = (pointerWasDown && !down) || (justPressed && !down);
        if (released) endPointer(mx, my);

        pointerWasDown = down;
    }

    /** On press, grab whatever draggable sits under the cursor (planning only). */
    private void beginPointer(int mx, int my) {
        pressX = mx;
        pressY = my;
        dragging = false;
        grabUnitId = -1;
        grabItemIndex = -1;
        AutoClient.You you = client.you();
        if (you == null || !planning()) return; // boards are only editable while planning

        if (hoverItem >= 0 && hoverItem < you.items().size()) {
            grabItemIndex = hoverItem;
        } else if (hoverBench >= 0) {
            UnitInstance u = benchUnitAt(you, hoverBench);
            if (u != null) grabUnitId = u.id;
        } else if (hoverCol >= 0) {
            UnitInstance u = boardUnitAt(you, hoverCol, hoverRow);
            if (u != null) grabUnitId = u.id;
        }
    }

    /** On release, drop a drag onto its target, or resolve a plain click. */
    private void endPointer(int mx, int my) {
        AutoClient.You you = client.you();
        if (you == null) {
            clearGrab();
            return;
        }
        if (dragging && grabItemIndex >= 0) {
            dropItem(you, grabItemIndex);
        } else if (dragging && grabUnitId >= 0) {
            dropUnit(you, grabUnitId);
        } else {
            resolveClick(you, mx, my);
        }
        clearGrab();
    }

    /** Finish a unit drag: move it to the bench slot or board cell under the cursor. */
    private void dropUnit(AutoClient.You you, int unitId) {
        UnitInstance dragged = findOwn(unitId);
        if (!planning() || dragged == null) return;
        // A 1-star Wisp dropped onto a unit with a pair fuses as the missing
        // copy (Io-style) instead of swapping places.
        UnitInstance target = null;
        if (hoverBench >= 0) target = benchUnitAt(you, hoverBench);
        else if (hoverCol >= 0) target = boardUnitAt(you, hoverCol, hoverRow);
        if (target != null && target != dragged && canFuseInto(you, dragged, target)) {
            client.sendFuse(unitId, target.id);
            ctx.sfx(AudioManager.Sfx.PICKUP);
            return;
        }
        if (hoverBench >= 0) {
            client.sendMoveToBench(unitId, hoverBench);
            ctx.sfx(AudioManager.Sfx.CLICK);
        } else if (hoverCol >= 0 && hoverRow >= BattleSim.ROWS / 2) {
            client.sendMoveToBoard(unitId, hoverCol, hoverRow);
            ctx.sfx(AudioManager.Sfx.CLICK);
        }
        // Dropped outside a legal target: the unit stays where it was.
    }

    /** Whether dropping {@code dragged} (a Wisp) on {@code target} completes a merge. */
    private boolean canFuseInto(AutoClient.You you, UnitInstance dragged,
                                UnitInstance target) {
        if (!AutoUnits.WISP.equals(dragged.key) || dragged.star > 1) return false;
        if (AutoUnits.WISP.equals(target.key) || target.star >= 3) return false;
        int copies = 0;
        for (UnitInstance u : you.bench()) {
            if (u.key.equals(target.key) && u.star == target.star) copies++;
        }
        for (UnitInstance u : you.board()) {
            if (u.key.equals(target.key) && u.star == target.star) copies++;
        }
        int need = target.star == 1 && you.relic() == Relic.APPRENTICE ? 2 : 3;
        return copies >= need - 1;
    }

    /** Finish an item drag: equip it on the unit (bench or board) under the cursor. */
    private void dropItem(AutoClient.You you, int itemIndex) {
        if (!planning() || itemIndex >= you.items().size()) return;
        UnitInstance target = null;
        if (hoverBench >= 0) target = benchUnitAt(you, hoverBench);
        else if (hoverCol >= 0) target = boardUnitAt(you, hoverCol, hoverRow);
        if (target != null) {
            client.sendEquip(itemIndex, target.id);
            ctx.sfx(AudioManager.Sfx.CLICK);
        }
    }

    private void clearGrab() {
        grabUnitId = -1;
        grabItemIndex = -1;
        dragging = false;
    }

    /**
     * The original click-to-select/place model, kept as a fallback for presses
     * that don't turn into a drag (and for phases where dragging is disabled).
     */
    private void resolveClick(AutoClient.You you, int mx, int my) {
        // Scouting: clicking a standings row opens that player's board.
        for (int i = 0; i < standingRects.size() && i < standingIds.size(); i++) {
            if (standingRects.get(i).contains(mx, my)) {
                openView(standingIds.get(i));
                ctx.sfx(AudioManager.Sfx.CLICK);
                return;
            }
        }
        // Synergy category chips: filter the panel (click again to clear).
        for (int i = 0; i < categoryChips.size() && i < chipCategories.size(); i++) {
            if (categoryChips.get(i).contains(mx, my)) {
                SynergyCategory picked = chipCategories.get(i);
                categoryFilter = categoryFilter == picked ? null : picked;
                ctx.sfx(AudioManager.Sfx.CLICK);
                return;
            }
        }
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
        if (lockBtn.contains(mx, my)) {
            client.sendLock();
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (planning()) {
            for (int i = 0; i < arrangeBtns.length; i++) {
                if (arrangeBtns[i].contains(mx, my)) {
                    client.sendArrange(ARRANGE_MODES[i]);
                    ctx.sfx(AudioManager.Sfx.CLICK);
                    return;
                }
            }
        }
        if (selectedUnitId >= 0 && sellBtn.contains(mx, my)) {
            client.sendSell(selectedUnitId);
            selectedUnitId = -1;
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }
        if (selectedUnitId >= 0 && unequipBtn.contains(mx, my)) {
            client.sendUnequip(selectedUnitId);
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

    // --- board scouting -------------------------------------------------------------

    private void openView(int playerId) {
        viewingId = playerId;
        viewRefresh = 0; // request immediately
    }

    private void closeView() {
        viewingId = -1;
    }

    /** While the scout overlay is open: keep it fresh, route clicks, close it. */
    private void updateViewOverlay(double dt, InputManager input) {
        viewRefresh -= dt;
        if (viewRefresh <= 0) {
            viewRefresh = 1.5; // boards change while scouting; poll for updates
            client.sendView(viewingId);
        }
        if (input.isRightMouseJustPressed()) {
            closeView();
            return;
        }
        if (!input.isMouseJustPressed()) return;
        int mx = input.getMouseX();
        int my = input.getMouseY();
        // Clicking another standings row switches the scouted player.
        for (int i = 0; i < standingRects.size() && i < standingIds.size(); i++) {
            if (standingRects.get(i).contains(mx, my)) {
                openView(standingIds.get(i));
                ctx.sfx(AudioManager.Sfx.CLICK);
                return;
            }
        }
        if (viewCloseRect.contains(mx, my) || !viewPanelRect.contains(mx, my)) {
            closeView();
        }
    }

    private UnitInstance findOwn(int unitId) {
        AutoClient.You you = client.you();
        if (you == null) return null;
        for (UnitInstance u : you.bench()) if (u.id == unitId) return u;
        for (UnitInstance u : you.board()) if (u.id == unitId) return u;
        return null;
    }

    // ------------------------------------------------------------------ render


    @Override
    public void render(DrawTarget target, float alpha) {
        int w = viewportWidth, h = viewportHeight;

        BoardTheme theme = BoardTheme.active();
        BoardTheme.Scheme scheme = theme.scheme();
        target.fillLinearGradient(0, 0, w, h,
                0, 0, scheme.bgTop.getRGB(), 0, h, scheme.bgBottom.getRGB());
        drawBackgroundImage(target, theme, w, h);
        if (client == null) return;

        layoutHud();
        drawBoard(target);
        drawProps(target, theme);
        if (fighting()) {
            drawCorpses(target);
            drawCombatUnits(target);
            drawMissiles(target);
            drawSlashes(target);
        } else {
            drawPlanningUnits(target);
        }
        particles.render(target, camera);
        drawFloaters(target);

        drawTopHud(target);
        if (fighting()) {
            drawDamagePanel(target);
        } else {
            drawSynergies(target);
        }
        drawStandings(target);
        drawBench(target);
        drawShopBar(target);
        drawItemBench(target);
        drawToasts(target);
        drawBanner(target);
        drawDrag(target);
        if (!dragging && viewingId < 0) drawTooltips(target);
        drawBoardView(target);
        drawOverlays(target);
    }

    /** The optional custom backdrop image, scaled to cover and dimmed to taste. */
    private void drawBackgroundImage(DrawTarget target, BoardTheme theme, int w, int h) {
        if (theme.background().isEmpty()) return;
        BufferedImage bg = AssetLoader.loadImageOrNull(theme.background());
        if (bg == null) return;
        // Cover the viewport, preserving the image's aspect ratio.
        double scale = Math.max(w / (double) bg.getWidth(), h / (double) bg.getHeight());
        int bw = (int) (bg.getWidth() * scale);
        int bh = (int) (bg.getHeight() * scale);
        target.drawImage(bg, (w - bw) / 2, (h - bh) / 2, bw, bh);
        // Dim it so the board and HUD stay readable over any picture.
        target.fillRect(0, 0, w, h, new Color(8, 9, 16, 150));
    }

    /**
     * Decorative props (cosmetic only) at their player-placed positions;
     * slots with an imported image draw that instead of the procedural prop.
     */
    private void drawProps(DrawTarget target, BoardTheme theme) {
        int size = (int) (40 * camera.zoom);
        int[] out = new int[2];
        for (int i = 0; i < BoardTheme.PROP_SLOTS; i++) {
            if (!theme.propVisible(i)) continue;
            camera.worldToScreen((theme.propCol(i) + 0.5) * TILE,
                    (theme.propRow(i) + 0.5) * TILE, out);
            BufferedImage img = theme.propImage(i).isEmpty() ? null
                    : AssetLoader.loadImageOrNull(theme.propImage(i));
            if (img == null && theme.prop(i) != BoardTheme.Prop.NONE) {
                img = AutoSprites.prop(theme.prop(i), size);
            }
            if (img == null) continue;
            target.drawImage(img, out[0] - size / 2, out[1] - size + size / 4, size, size);
        }
    }

    private void drawBoard(DrawTarget target) {
        boolean plan = planning();
        BoardTheme.Scheme scheme = BoardTheme.active().scheme();
        BufferedImage tileA = Skins.frame("board/tile_a", animClock);
        BufferedImage tileB = Skins.frame("board/tile_b", animClock);
        for (int r = 0; r < BattleSim.ROWS; r++) {
            for (int c = 0; c < BattleSim.COLS; c++) {
                Polygon p = tilePolygon(c, r);
                boolean own = r >= BattleSim.ROWS / 2;
                BufferedImage skin = (c + r) % 2 == 0 ? tileA : tileB;
                if (skin != null) {
                    // Skinned board: stretch the frame over the tile diamond.
                    // The clip is what keeps each tile's art off its
                    // neighbours — see DrawTarget.pushClip(Shape).
                    target.pushClip(p);
                    Rectangle b = p.getBounds();
                    target.drawImage(skin, b.x, b.y, b.width, b.height);
                    target.popClip();
                    if (plan && own) {
                        target.fillPolygon(p.xpoints, p.ypoints, p.npoints, OWN_HALF_TINT);
                    }
                } else {
                    Color base = (c + r) % 2 == 0 ? scheme.tileA : scheme.tileB;
                    if (plan && own) {
                        base = (c + r) % 2 == 0 ? scheme.ownA : scheme.ownB;
                    }
                    target.fillPolygon(p.xpoints, p.ypoints, p.npoints, base);
                }
                if (plan && c == hoverCol && r == hoverRow && own) {
                    target.fillPolygon(p.xpoints, p.ypoints, p.npoints, HOVER_TINT);
                }
                target.drawPolygon(p.xpoints, p.ypoints, p.npoints, TILE_EDGE);
            }
        }
        // Board edge glow.
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
        target.drawPolygon(sx, sy, 4, new Color(scheme.edge.getRed(), scheme.edge.getGreen(),
                scheme.edge.getBlue(), 90), 2f);
    }

    /**
     * The four screen corners of board cell {@code (c, r)}, as a reusable
     * {@link Polygon}.
     *
     * <p>One instance, reset per call, because the board is 8x8 and every tile
     * is filled and then outlined every frame — a fresh {@code Polygon} (and
     * the two arrays inside it) per call was 64 allocations a frame before the
     * highlights and the glow tiles were counted. Every caller uses the result
     * immediately and none of them keeps it, which is what makes the reuse
     * safe; the type stays {@code Polygon} rather than a pair of scratch
     * arrays because the skinned-tile path needs a {@link Shape} to clip to.
     */
    private Polygon tilePolygon(int c, int r) {
        int[] out = new int[2];
        tile.reset();
        double[][] corners = {{c, r}, {c + 1, r}, {c + 1, r + 1}, {c, r + 1}};
        for (double[] corner : corners) {
            camera.worldToScreen(corner[0] * TILE, corner[1] * TILE, out);
            tile.addPoint(out[0], out[1]);
        }
        return tile;
    }

    /**
     * The image for a unit right now: the assigned skin frame for its
     * animation state when one exists (falling back to the unit's idle skin),
     * else the procedural figure. Callers draw it scaled to {@code size}.
     */
    private BufferedImage unitImage(UnitDef def, int size, boolean friendly,
                                    AnimState state, double stateTime) {
        double t = state == AnimState.IDLE || state == AnimState.WALK ? animClock : stateTime;
        BufferedImage skin = Skins.unitFrame(def.key, state.key(), t);
        return skin != null ? skin : AutoSprites.unit(def, size, friendly);
    }

    /** An item gem image: its skin frame when assigned, else the procedural gem. */
    private BufferedImage itemImage(AutoItem item, int size) {
        BufferedImage skin = Skins.frame("item/" + item.key, animClock);
        return skin != null ? skin : AutoSprites.item(item, size);
    }

    /**
     * Draw one unit anchored at its cell's screen point with its animation
     * state's procedural motion: walking bobs, attacking pops forward,
     * getting hit jitters, casting glows — and idling plays an exaggerated,
     * cartoony personality animation chosen per unit species (bouncing,
     * breathing squash-and-stretch, swaying, or wiggling), phase-shifted per
     * instance so a bench of the same unit doesn't move in lockstep.
     */
    private void drawUnitInWorld(DrawTarget target, UnitDef def, int size, boolean friendly,
                                 AnimState state, double stateTime, int cx, int cy,
                                 int seed) {
        int dx = 0, dy = 0;
        double sx = 1, sy = 1; // squash & stretch factors
        switch (state) {
            case IDLE -> {
                // Personality by species, phase by instance.
                int style = Math.floorMod(def.key.hashCode(), 4);
                double phase = Math.floorMod(def.key.hashCode() >> 3, 97) * 0.31
                        + seed * 0.73;
                double t = animClock + phase;
                switch (style) {
                    case 0 -> { // bouncy hop: airtime up top, squash on landing
                        double hop = Math.abs(Math.sin(t * 3.4));
                        dy = -(int) (hop * size * 0.10);
                        sy = 1 - (1 - hop) * 0.10;
                        sx = 1 + (1 - hop) * 0.10;
                    }
                    case 1 -> { // deep cartoon breathing
                        double breath = Math.sin(t * 2.6);
                        sy = 1 + breath * 0.07;
                        sx = 1 - breath * 0.06;
                    }
                    case 2 -> { // side-to-side sway with a little lean
                        dx = (int) (Math.sin(t * 2.2) * size * 0.06);
                        sy = 1 + Math.abs(Math.sin(t * 2.2)) * 0.03;
                    }
                    default -> { // excited double-bob wiggle
                        double wig = Math.sin(t * 6.5);
                        dy = -(int) (Math.abs(wig) * size * 0.05);
                        dx = (int) (Math.sin(t * 3.25) * size * 0.03);
                        sx = 1 + wig * 0.04;
                    }
                }
            }
            case WALK -> dy = -(int) (Math.abs(Math.sin(animClock * 9)) * size * 0.06);
            case ATTACK -> dy = -(int) (size * 0.07);
            case HIT -> dx = (int) (Math.sin(animClock * 55) * size * 0.05);
            default -> { }
        }
        if (state == AnimState.CAST) {
            // Casting: a pulsing accent ring on the ground.
            int rw = (int) (size * (0.8 + 0.15 * Math.sin(animClock * 12)));
            target.drawOval(cx - rw / 2, cy - rw / 6 + size / 4, rw, rw / 3,
                    new Color(def.accent.getRed(), def.accent.getGreen(),
                            def.accent.getBlue(), 120), 2.5f);
        }
        BufferedImage img = unitImage(def, size, friendly, state, stateTime);
        // Squash & stretch scale about the feet so figures stay grounded.
        int drawW = (int) (size * sx);
        int drawH = (int) (size * sy);
        int footY = cy + size / 4;
        target.drawImage(img, cx - drawW / 2 + dx, footY - drawH + dy, drawW, drawH);
        if (state == AnimState.HIT) {
            // Hit flash: a brief red ring around the figure.
            target.drawOval(cx - drawW / 2 + dx, footY - drawH + dy, drawW, drawH,
                    new Color(235, 90, 80, 110), 2f);
        }
    }

    private void drawPlanningUnits(DrawTarget target) {
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
                Polygon p = tilePolygon(u.col, u.row);
                target.drawPolygon(p.xpoints, p.ypoints, p.npoints, SELECTED_TILE, 2.5f);
            }
            drawUnitInWorld(target, def, size, true, AnimState.IDLE, animClock,
                    out[0], out[1], u.id);
            AutoSprites.drawStars(target, u.star, out[0], out[1] - size + size / 8, 8);
            drawItemPips(target, u, out[0], out[1] + size / 6);
        }
    }

    private void drawItemPips(DrawTarget target, UnitInstance u, int cx, int y) {
        if (u.items.isEmpty()) return;
        int pip = 10;
        int x = cx - (u.items.size() * (pip + 2)) / 2;
        for (String key : u.items) {
            AutoItem item = AutoItems.get(key);
            target.fillRect(x, y, pip, pip, item == null ? Color.GRAY : item.color);
            target.drawRect(x, y, pip, pip, new Color(20, 22, 32));
            x += pip + 2;
        }
    }

    private void drawCombatUnits(DrawTarget target) {
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
            UnitFx fx = unitFx.get(u.id());
            AnimState state = fx != null ? fx.state : u.state();
            double stateTime = fx != null ? fx.time : 0;
            drawUnitInWorld(target, def, size, friendly, state, stateTime,
                    out[0], out[1], u.id());
            AutoSprites.drawStars(target, u.star(), out[0], out[1] - size + size / 12, 7);

            // Health + mana bars.
            int bw = (int) (40 * camera.zoom);
            int bx = out[0] - bw / 2;
            int by = out[1] - size + size / 4 - 10;
            target.fillRect(bx, by, bw, 5, new Color(15, 15, 22, 210));
            target.fillRect(bx, by, (int) (bw * Math.max(0, u.hp() / Math.max(1, u.maxHp()))), 5,
                    friendly ? new Color(110, 220, 120) : new Color(235, 100, 90));
            if (u.manaMax() > 0) {
                target.fillRect(bx, by + 6, bw, 3, new Color(15, 15, 22, 210));
                target.fillRect(bx, by + 6, (int) (bw * u.mana() / u.manaMax()), 3,
                        new Color(90, 150, 255));
            }
        }
    }

    /** Fading corpses where units died, so deaths read on the board. */
    private void drawCorpses(DrawTarget target) {
        if (corpses.isEmpty()) return;
        int size = (int) (46 * camera.zoom);
        int[] out = new int[2];
        for (Corpse c : corpses) {
            float a = (float) Math.max(0, 1 - c.age / CORPSE_SECONDS);
            camera.worldToScreen(c.wx, c.wy, out);
            // Scoped per corpse rather than set-and-restore-once: each one
            // fades at its own rate, and a stack has to balance.
            target.pushAlpha(a * 0.8f);
            BufferedImage img = unitImage(c.def, size, c.friendly, AnimState.DEATH, c.age);
            int sink = (int) (c.age * 12);
            target.drawImage(img, out[0] - size / 2, out[1] - size + size / 4 + sink, size, size);
            target.popAlpha();
        }
    }

    /** In-flight projectiles: skinned frames, or procedural arrows/orbs/bolts. */
    private void drawMissiles(DrawTarget target) {
        if (missiles.isEmpty()) return;
        int[] out = new int[2];
        int[] ahead = new int[2];
        for (Missile m : missiles) {
            camera.worldToScreen(m.x, m.y, out);
            camera.worldToScreen(m.x + m.vx * 0.05, m.y + m.vy * 0.05, ahead);
            double angle = Math.atan2(ahead[1] - out[1], ahead[0] - out[0]);
            int size = (int) (18 * camera.zoom);

            BufferedImage skin = Skins.frame("projectile/" + m.kind, animClock);
            if (skin != null) {
                AffineTransform spin = AffineTransform.getTranslateInstance(out[0], out[1]);
                spin.rotate(angle);
                target.pushTransform(spin);
                target.drawImage(skin, -size / 2, -size / 2, size, size);
                target.popTransform();
                continue;
            }
            switch (m.kind) {
                case "arrow" -> {
                    int len = size;
                    int tx = (int) (Math.cos(angle) * len / 2);
                    int ty = (int) (Math.sin(angle) * len / 2);
                    target.drawLine(out[0] - tx, out[1] - ty, out[0] + tx, out[1] + ty,
                            m.color, 2.5f);
                    // The stroke asked for round caps; the head already has a
                    // disc, so the tail gets the matching one. See the note on
                    // ARROW_CAP.
                    target.fillOval(out[0] - tx - ARROW_CAP, out[1] - ty - ARROW_CAP,
                            ARROW_CAP * 2, ARROW_CAP * 2, m.color);
                    target.fillOval(out[0] + tx - 3, out[1] + ty - 3, 6, 6, m.color);
                }
                case "orb" -> {
                    target.fillOval(out[0] - size / 2, out[1] - size / 2, size, size,
                            new Color(m.color.getRed(), m.color.getGreen(),
                                    m.color.getBlue(), 90));
                    target.fillOval(out[0] - size / 4, out[1] - size / 4, size / 2, size / 2,
                            m.color);
                    target.fillOval(out[0] - size / 8, out[1] - size / 8, size / 4, size / 4,
                            Color.WHITE);
                }
                default -> { // bolt: a small rotated diamond
                    AffineTransform spin = AffineTransform.getTranslateInstance(out[0], out[1]);
                    spin.rotate(angle);
                    target.pushTransform(spin);
                    int hw = Math.max(4, size / 3);
                    boltXs[0] = -hw; boltXs[1] = 0;       boltXs[2] = hw; boltXs[3] = 0;
                    boltYs[0] = 0;   boltYs[1] = -hw / 2; boltYs[2] = 0;  boltYs[3] = hw / 2;
                    target.fillPolygon(boltXs, boltYs, 4, m.color);
                    target.popTransform();
                }
            }
        }
    }

    /** Melee impact arcs, expanding and fading over their short life. */
    private void drawSlashes(DrawTarget target) {
        if (slashes.isEmpty()) return;
        int[] out = new int[2];
        for (Slash s : slashes) {
            double t = s.age / SLASH_SECONDS;
            int alpha = (int) (220 * (1 - t));
            int r = (int) ((14 + t * 22) * camera.zoom);
            camera.worldToScreen(s.wx, s.wy, out);
            int startAngle = (int) (40 - t * 60);
            Color ink = new Color(s.color.getRed(), s.color.getGreen(), s.color.getBlue(),
                    Math.max(0, alpha));
            int cy = out[1] - r - (int) (10 * camera.zoom);
            target.drawArc(out[0] - r, cy, r * 2, r * 2, startAngle, 100, ink, 3f);
            // Round caps, as the stroke asked for: a cap of width w is a disc
            // of diameter w on the endpoint, so the two ends get one each.
            roundCap(target, out[0], cy + r, r, startAngle, ink);
            roundCap(target, out[0], cy + r, r, startAngle + 100, ink);
        }
    }

    /**
     * The disc a round-capped stroke of {@link #SLASH_WIDTH} paints at the end
     * of an arc, placed at the angle's point on the circle.
     *
     * <p>Drawn rather than asked for. B1's stroke audit concluded that every
     * solid stroke in the engine wanted only a width and that round caps
     * belonged to dashes alone; the slash arc and the arrow trail here are the
     * two sites that contradict it. Two sites do not justify a cap argument on
     * every outline verb — every backend would have to implement and test a
     * knob almost nothing turns — so the cap is emitted as the geometry it is.
     */
    private void roundCap(DrawTarget target, int cx, int cy, int r, int degrees, Color color) {
        double a = Math.toRadians(degrees);
        int px = cx + (int) Math.round(Math.cos(a) * r);
        int py = cy - (int) Math.round(Math.sin(a) * r);
        int half = SLASH_WIDTH / 2;
        target.fillOval(px - half, py - half, SLASH_WIDTH, SLASH_WIDTH, color);
    }

    private void drawFloaters(DrawTarget target) {
        int[] out = new int[2];
        for (Floater f : floaters) {
            camera.worldToScreen(f.wx, f.wy, out);
            int alpha = (int) (255 * Math.max(0, 1 - f.age / 1.1));
            target.drawText(f.text, out[0] - target.textWidth(f.text, SANS_BOLD_15) / 2,
                    (int) (out[1] - 34 - f.age * 26), SANS_BOLD_15,
                    new Color(f.color.getRed(), f.color.getGreen(), f.color.getBlue(), alpha));
        }
    }

    // --- HUD ---------------------------------------------------------------------

    private void drawTopHud(DrawTarget target) {
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
        Rectangle band = AutoHud.titleBand(viewportWidth);
        drawCentered(target, trim(target, SANS_BOLD_20, title, band.width),
                viewportWidth / 2, band.y + 22, SANS_BOLD_20, new Color(235, 238, 250));
        drawCentered(target, phaseName + "  ·  " + (int) Math.ceil(phase.leftNow()) + "s",
                viewportWidth / 2, band.y + 46, SANS_PLAIN_16, new Color(160, 168, 190));

        int ping = client.pingMillis();
        if (ping >= 0) {
            target.drawText(ping + " ms", viewportWidth - 52, 18, SANS_PLAIN_12,
                    new Color(110, 115, 135));
        }

        if (phase.phase() == AutoGame.Phase.PLAN) drawArrangeButtons(target);
        drawRelicBadge(target, phase.round());
    }

    /** One-click formation buttons, shown while the board is editable. */
    private void drawArrangeButtons(DrawTarget target) {
        AutoClient.You you = client.you();
        boolean enabled = you != null && !you.board().isEmpty();
        for (int i = 0; i < arrangeBtns.length; i++) {
            Rectangle r = arrangeBtns[i];
            boolean hot = r.contains(lastMouseX, lastMouseY);
            target.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8, !enabled ? new Color(30, 33, 48)
                    : hot ? new Color(58, 66, 96) : new Color(40, 46, 68));
            target.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8,
                    enabled ? new Color(120, 130, 160) : new Color(60, 64, 84));
            drawCentered(target, ARRANGE_LABELS[i], r.x + r.width / 2, r.y + 15, SANS_BOLD_11,
                    enabled ? new Color(210, 216, 234) : new Color(110, 114, 134));
        }
    }

    /** The current relic in the top-left corner; hover for its description. */
    private void drawRelicBadge(DrawTarget target, int round) {
        AutoClient.You you = client.you();
        if (you == null || you.relic() == null) return;
        Relic relic = you.relic();
        Rectangle r = relicBadge;
        target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(22, 25, 42, 235));
        target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                hoverRelic ? relic.color : new Color(70, 78, 108));
        target.fillOval(r.x + 8, r.y + r.height / 2 - 7, 14, 14, relic.color);
        target.drawText(trim(target, SANS_BOLD_13, "Relic: " + relic.label, r.width - 36),
                r.x + 28, r.y + 19,
                SANS_BOLD_13, new Color(230, 234, 246));
        int untilSwap = AutoGame.RELIC_INTERVAL - ((round - 1) % AutoGame.RELIC_INTERVAL);
        target.drawText("swaps in " + untilSwap + " round" + (untilSwap == 1 ? "" : "s"), r.x + 28,
                r.y + 36, SANS_PLAIN_11, new Color(150, 156, 178));
    }

    private record TraitCount(Trait trait, int count) {}

    private List<TraitCount> synergyRows() {
        AutoClient.You you = client.you();
        if (you == null) return List.of();
        Map<Trait, Integer> counts = BattleSim.countTraits(you.board());
        List<TraitCount> rows = new ArrayList<>();
        for (Map.Entry<Trait, Integer> e : counts.entrySet()) {
            if (categoryFilter != null
                    && !e.getKey().categories.contains(categoryFilter)) {
                continue;
            }
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

    /** Clickable category-icon chips ("All" first) atop the panel, wrapping. */
    private int drawCategoryChips(DrawTarget target, Rectangle panel, int y) {
        categoryChips.clear();
        chipCategories.clear();
        int chip = 16;
        int x = panel.x + 4;

        Rectangle all = new Rectangle(x, y, chip + 8, chip);
        categoryChips.add(all);
        chipCategories.add(null);
        target.fillRoundRect(all.x, all.y, all.width, all.height, 6, 6,
                categoryFilter == null ? new Color(70, 82, 118) : new Color(34, 38, 56));
        target.drawText("All", all.x + 5, all.y + 12, SANS_BOLD_10,
                categoryFilter == null ? new Color(230, 234, 246) : new Color(130, 136, 156));
        x += all.width + 2;

        for (SynergyCategory cat : SynergyCategory.values()) {
            if (x + chip > panel.x + panel.width) { // wrap; every category stays reachable
                x = panel.x + 4;
                y += chip + 3;
            }
            Rectangle r = new Rectangle(x, y, chip, chip);
            categoryChips.add(r);
            chipCategories.add(cat);
            boolean active = categoryFilter == cat;
            target.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6, active ? new Color(70, 82, 118)
                    : hoverCategory == cat ? new Color(52, 58, 84) : new Color(34, 38, 56));
            target.drawImage(AutoSprites.categoryIcon(cat, chip - 4), r.x + 2, r.y + 2);
            x += chip + 2;
        }
        return y + chip + 8;
    }

    private void drawSynergies(DrawTarget target) {
        traitRows.clear();
        List<TraitCount> rows = synergyRows();
        Rectangle panel = AutoHud.leftPanel(viewportWidth, viewportHeight, itemCount());
        int x = panel.x + 4, y = panel.y + 16;
        target.drawText("Synergies", x, y - 4, SANS_BOLD_14, new Color(160, 168, 190));
        y = drawCategoryChips(target, panel, y + 2);
        if (rows.isEmpty()) {
            target.drawText(categoryFilter != null
                    ? "No " + categoryFilter.label + " synergies fielded"
                    : "Field units to activate traits", x, y + 16, SANS_PLAIN_14, new Color(110,
                            115, 135));
            return;
        }
        // Cap the list so it can never grow into the item bench below it.
        int maxRows = Math.max(1, (panel.y + panel.height - (y + 6)) / 26);
        int shown = Math.min(rows.size(), rows.size() > maxRows ? maxRows - 1 : maxRows);
        y += 8;
        for (int i = 0; i < shown; i++) {
            TraitCount tc = rows.get(i);
            int tier = tc.trait.tier(tc.count);
            Rectangle row = new Rectangle(x, y - 14, 190, 22);
            traitRows.add(row);
            if (tier > 0) {
                target.fillRoundRect(row.x - 4, row.y, row.width, row.height, 8, 8,
                        new Color(50, 58, 84, 180));
            }
            target.fillOval(x, y - 10, 12, 12,
                    tier > 0 ? tc.trait.color : new Color(110, 115, 135));
            StringBuilder marks = new StringBuilder();
            for (int threshold : tc.trait.thresholds) {
                if (marks.length() > 0) marks.append("/");
                marks.append(threshold);
            }
            target.drawText(tc.trait.label + "  " + tc.count + "  (" + marks + ")", x + 20, y + 1,
                    SANS_PLAIN_14, tier > 0 ? new Color(230, 234, 246) : new Color(130, 136, 156));
            // The trait's role icons, right-aligned in the row.
            int icon = 11;
            int ix = row.x + row.width - icon - 2;
            List<SynergyCategory> cats = tc.trait.categories;
            for (int ci = Math.min(2, cats.size() - 1); ci >= 0; ci--) {
                target.drawImage(AutoSprites.categoryIcon(cats.get(ci), icon), ix - ci * (icon + 2),
                        y - 9);
            }
            y += 26;
        }
        if (shown < rows.size()) {
            target.drawText("+" + (rows.size() - shown) + " more…", x + 20, y + 1, SANS_PLAIN_14,
                    new Color(110, 115, 135));
        }
    }

    /**
     * The combat damage meter: how much damage each unit in the fight has
     * dealt, split by type (attack / ability), plus healing done. Replaces the
     * synergy panel while a battle runs.
     */
    private void drawDamagePanel(DrawTarget target) {
        traitRows.clear(); // no synergy hover targets during combat
        categoryChips.clear();
        chipCategories.clear();
        AutoClient.CombatFrame frame = client.combatLatest();
        if (frame == null) return;
        boolean home = isHomeSide();
        int friendlyTeam = home ? BattleSim.HOME : BattleSim.AWAY;

        List<AutoClient.CombatUnit> rows = new ArrayList<>();
        double maxTotal = 1;
        for (AutoClient.CombatUnit u : frame.units()) {
            double total = u.dmgPhysical() + u.dmgMagic();
            if (total >= 1 || u.healing() >= 1) rows.add(u);
            maxTotal = Math.max(maxTotal, total);
        }
        rows.sort(Comparator.comparingDouble(
                (AutoClient.CombatUnit u) -> u.dmgPhysical() + u.dmgMagic()).reversed());

        Rectangle panel = AutoHud.leftPanel(viewportWidth, viewportHeight, itemCount());
        int x = panel.x + 4, y = panel.y + 16;
        target.drawText("Damage", x, y - 4, SANS_BOLD_14, new Color(160, 168, 190));
        // Legend: the damage-type colours.
        int lx = x + 66;
        lx = legendSwatch(target, lx, y - 12, DMG_PHYSICAL, "attack");
        lx = legendSwatch(target, lx, y - 12, DMG_MAGIC, "ability");
        legendSwatch(target, lx, y - 12, DMG_HEAL, "heal");

        if (rows.isEmpty()) {
            target.drawText("No damage dealt yet", x, y + 16, SANS_PLAIN_13,
                    new Color(110, 115, 135));
            return;
        }

        int rowH = AutoHud.DAMAGE_ROW_HEIGHT;
        int maxRows = Math.max(1, (panel.y + panel.height - (y + 4)) / rowH);
        int shown = Math.min(rows.size(), maxRows);
        int barMax = panel.width - 18;
        y += 6;
        for (int i = 0; i < shown; i++) {
            AutoClient.CombatUnit u = rows.get(i);
            UnitDef def = AutoUnits.get(u.key());
            boolean friendly = u.team() == friendlyTeam;
            double phys = u.dmgPhysical(), mag = u.dmgMagic(), heal = u.healing();
            double total = phys + mag;

            // Your own units glow green so the meter reads at a glance.
            if (friendly) {
                target.fillRoundRect(x - 3, y - 2, barMax + 6, rowH - 2, 6, 6,
                        new Color(60, 160, 80, 70));
            }

            Color nameColor = u.dead() ? new Color(115, 118, 136)
                    : friendly ? new Color(150, 235, 160) : new Color(240, 185, 175);
            String name = def != null ? def.name : u.key();
            target.drawText(trim(target, SANS_PLAIN_12, name, barMax - 58), x, y + 10,
                    SANS_PLAIN_12, nameColor);

            String amount = fmtAmount(total) + (heal >= 1 ? "  +" + fmtAmount(heal) : "");
            target.drawText(amount, x + barMax - target.textWidth(amount, SANS_PLAIN_12),
                    y + 10, SANS_PLAIN_12,
                    heal >= 1 ? DMG_HEAL : new Color(200, 206, 226));

            drawDamageBar(target, u, x, y + 14, barMax, total, maxTotal, phys);
            y += rowH;
        }
    }

    /**
     * One damage bar, scaled to the fight's top damage and broken down by
     * element: each attack element gets a segment in its own colour, and
     * whatever damage wasn't elemental splits into the plain attack/ability
     * colours.
     */
    private void drawDamageBar(DrawTarget target, AutoClient.CombatUnit u, int x, int y,
                               int barMax, double total, double maxTotal, double phys) {
        target.fillRect(x, y, barMax, 5, new Color(15, 15, 22, 200));
        int bw = (int) (barMax * Math.min(1, total / maxTotal));
        if (bw <= 0 || total <= 0) return;

        double elemental = 0;
        for (double amount : u.dmgByElement().values()) elemental += amount;
        elemental = Math.min(elemental, total);

        int drawn = 0;
        for (Map.Entry<Element, Double> e : u.dmgByElement().entrySet()) {
            int seg = (int) (bw * e.getValue() / total);
            if (seg <= 0) continue;
            target.fillRect(x + drawn, y, seg, 5, e.getKey().color);
            drawn += seg;
        }
        // Plain remainder: attack then ability colours.
        double plain = total - elemental;
        if (plain > 0) {
            double plainPhys = Math.min(phys, plain);
            int physW = (int) (bw * plainPhys / total);
            target.fillRect(x + drawn, y, physW, 5, DMG_PHYSICAL);
            drawn += physW;
        }
        if (drawn < bw) {
            target.fillRect(x + drawn, y, bw - drawn, 5, DMG_MAGIC);
        }
    }

    private static int legendSwatch(DrawTarget target, int x, int y, Color color, String label) {
        target.fillRect(x, y, 8, 8, color);
        target.drawText(label, x + 11, y + 8, SANS_PLAIN_11, LEGEND_TEXT);
        return x + 11 + target.textWidth(label, SANS_PLAIN_11) + 8;
    }

    /** Compact damage numbers: 843, 1.2k, 24k. */
    private static String fmtAmount(double v) {
        if (v >= 10_000) return (int) (v / 1000) + "k";
        if (v >= 1000) return String.format("%.1fk", v / 1000);
        return Integer.toString((int) v);
    }

    private void drawStandings(DrawTarget target) {
        standingRects.clear();
        standingIds.clear();
        List<AutoClient.Standing> rows = new ArrayList<>(client.standings());
        rows.sort((a, b) -> {
            if (a.alive() != b.alive()) return a.alive() ? -1 : 1;
            if (a.alive()) return b.hp() - a.hp();
            return a.place() - b.place();
        });
        Rectangle panel = AutoHud.standingsPanel(viewportWidth, viewportHeight);
        int x = panel.x + 6, y = panel.y + 16;
        target.drawText("Players", x, y - 4, SANS_BOLD_14, new Color(160, 168, 190));
        target.drawText("click a name to scout", x + 66, y - 4, SANS_PLAIN_11,
                new Color(110, 115, 135));

        int rowH = AutoHud.STANDING_ROW_HEIGHT;
        int maxRows = Math.max(1, (panel.y + panel.height - (y + 6)) / rowH);
        int shown = Math.min(rows.size(), rows.size() > maxRows ? maxRows - 1 : maxRows);
        y += 10;
        for (int i = 0; i < shown; i++) {
            AutoClient.Standing s = rows.get(i);
            boolean me = s.id() == client.localId();
            Rectangle rowRect = new Rectangle(panel.x, y - 14, panel.width - 4, 24);
            standingRects.add(rowRect);
            standingIds.add(s.id());
            boolean hovered = viewingId < 0
                    ? rowRect.contains(lastMouseX, lastMouseY) : s.id() == viewingId;
            if (me || hovered) {
                target.fillRoundRect(rowRect.x, rowRect.y, rowRect.width, rowRect.height, 8, 8,
                        hovered ? new Color(62, 72, 104, 200) : new Color(50, 58, 84, 180));
            }
            String label = s.name() + (s.bot() ? " [bot]" : "");
            if (!s.alive() && s.place() > 0) label = "#" + s.place() + " " + label;
            target.drawText(trim(target, SANS_PLAIN_14, label, 120), x, y + 2, SANS_PLAIN_14,
                    s.alive() ? (me ? new Color(255, 220, 120) : new Color(220, 224, 238))
                            : new Color(105, 108, 126));
            if (s.alive()) {
                target.fillRect(x + 128, y - 8, 60, 10, new Color(15, 15, 22, 200));
                target.fillRect(x + 128, y - 8, (int) (60 * Math.min(1, s.hp() / 100.0)), 10,
                        hpColor(s.hp()));
                target.drawText(String.valueOf(s.hp()), x + 132, y + 1, SANS_PLAIN_11,
                        new Color(230, 234, 246));
            }
            y += rowH;
        }
        if (shown < rows.size()) {
            target.drawText("+" + (rows.size() - shown) + " more…", x, y + 2, SANS_PLAIN_14,
                    new Color(110, 115, 135));
        }
    }

    private static Color hpColor(int hp) {
        if (hp > 60) return new Color(110, 210, 120);
        if (hp > 30) return new Color(230, 190, 90);
        return new Color(230, 110, 95);
    }

    private void drawBench(DrawTarget target) {
        AutoClient.You you = client.you();
        for (int i = 0; i < 9; i++) {
            Rectangle r = benchSlots[i];
            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(30, 34, 52, 220));
            target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                    i == hoverBench ? new Color(140, 170, 240) : new Color(60, 66, 92));
        }
        if (you == null) return;
        for (UnitInstance u : you.bench()) {
            if (u.bench < 0 || u.bench >= 9) continue;
            Rectangle r = benchSlots[u.bench];
            UnitDef def = u.def();
            if (def == null) continue;
            if (u.id == selectedUnitId) {
                target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(255, 220, 110),
                        2.5f);
            }
            int size = r.width - 10;
            BufferedImage img = unitImage(def, size, true, AnimState.IDLE, animClock);
            target.drawImage(img, r.x + 5, r.y + 2, size, size);
            AutoSprites.drawStars(target, u.star, r.x + r.width / 2, r.y + 2, 7);
            drawItemPips(target, u, r.x + r.width / 2, r.y + r.height - 12);
        }
    }

    private void drawShopBar(DrawTarget target) {
        AutoClient.You you = client.you();
        int w = viewportWidth, h = viewportHeight;
        int shopH = shopBarHeight();
        int barY = h - shopH;
        target.fillRect(0, barY, w, shopH, new Color(18, 20, 34, 235));
        target.drawLine(0, barY, w, barY, new Color(60, 66, 92));
        if (you == null) return;

        // Economy buttons.
        drawButton(target, xpBtn, "Buy XP  4g  (" + KeyBinds.label(GameAction.AUTO_BUY_XP) + ")",
                you.gold() >= 4 && you.level() < 9);
        drawButton(target, rerollBtn, "Reroll  2g  (" + KeyBinds.label(GameAction.AUTO_REROLL) + ")",
                you.gold() >= 2);

        // Shop lock: keep this exact shop through the round change.
        boolean locked = you.shopLocked();
        target.fillRoundRect(lockBtn.x, lockBtn.y, lockBtn.width, lockBtn.height, 8, 8,
                locked ? new Color(200, 165, 60) : new Color(45, 52, 76));
        target.drawRoundRect(lockBtn.x, lockBtn.y, lockBtn.width, lockBtn.height, 8, 8,
                locked ? new Color(255, 225, 130) : new Color(120, 130, 158));
        drawCentered(target, "Lock", lockBtn.x + lockBtn.width / 2, lockBtn.y + 17, SANS_BOLD_11,
                locked ? new Color(35, 28, 8) : new Color(170, 178, 200));

        // Gold / level / XP / streak readout: two short lines that stay left of
        // the (centred) bench strip — see AutoHud.economyLine.
        Rectangle eco = AutoHud.economyLine(w, h);
        String goldText = you.gold() + "g";
        target.drawText(goldText, eco.x + 2, eco.y + 14, SANS_BOLD_15, new Color(255, 214, 100));
        int cx = eco.x + 2 + target.textWidth(goldText, SANS_BOLD_15);
        String lvl = " · Level " + you.level();
        target.drawText(lvl, cx, eco.y + 14, SANS_PLAIN_13, new Color(170, 176, 198));
        cx += target.textWidth(lvl, SANS_PLAIN_13);
        if (you.streak() != 0) {
            target.drawText("  " + (you.streak() > 0 ? "W" : "L") + Math.abs(you.streak()), cx,
                    eco.y + 14, SANS_PLAIN_13,
                    you.streak() > 0 ? new Color(130, 220, 140) : new Color(230, 130, 110));
        }
        String xp = you.level() >= 9 ? "max xp" : you.xp() + "/" + you.xpNeed() + " xp";
        target.drawText(trim(target, SANS_PLAIN_12,
                        xp + " · " + you.board().size() + "/" + you.boardCap() + " fielded",
                        eco.width - 4),
                eco.x + 2, eco.y + 34, SANS_PLAIN_12, new Color(150, 156, 178));

        // Shop cards.
        for (int i = 0; i < 5; i++) {
            Rectangle r = shopCards[i];
            String key = i < you.shop().size() ? you.shop().get(i) : null;
            target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(28, 32, 50));
            if (key == null) {
                target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10, new Color(48, 52, 72));
                continue;
            }
            UnitDef def = AutoUnits.get(key);
            if (def == null) continue;
            Color tier = COST_COLORS[def.cost - 1];
            target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                    i == hoverShop ? tier.brighter() : tier, i == hoverShop ? 2.5f : 1.5f);

            BufferedImage img = unitImage(def, 54, true, AnimState.IDLE, animClock);
            target.drawImage(img, r.x + 6, r.y + r.height / 2 - 27, 54, 54);
            if (!def.attackElements.isEmpty()) {
                AutoSprites.drawElementPips(target, def.attackElements,
                        r.x + 33, r.y + r.height - 20, 9);
            }
            target.drawText(trim(target, SANS_BOLD_14, def.name, r.width - 72),
                    r.x + 62, r.y + 22, SANS_BOLD_14,
                    new Color(232, 236, 248));
            target.drawText(trim(target, SANS_PLAIN_12, def.origin.label, r.width - 96),
                    r.x + 62, r.y + 42,
                    SANS_PLAIN_12, def.origin.color);
            target.drawText(trim(target, SANS_PLAIN_12, def.clazz.label, r.width - 96),
                    r.x + 62, r.y + 58,
                    SANS_PLAIN_12, def.clazz.color);
            int price = you.shopPrice(i);
            boolean discounted = price < def.cost;
            target.drawText(price + "g", r.x + r.width - 30, r.y + r.height - 10, SANS_BOLD_14,
                    discounted ? new Color(130, 235, 150) : new Color(255, 214, 100));
            if (discounted) {
                // The Bargainer's slash: the list price, struck through.
                String was = def.cost + "g";
                int wx = r.x + r.width - 30;
                int wy = r.y + r.height - 24;
                target.drawText(was, wx, wy, SANS_PLAIN_11, new Color(170, 176, 198));
                target.drawLine(wx - 1, wy - 4, wx + target.textWidth(was, SANS_PLAIN_11), wy - 4,
                        new Color(170, 176, 198));
            }
            if (you.gold() < price) {
                target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                        new Color(10, 10, 18, 130));
            }
        }

        // Sell + item-removal buttons when a unit is selected.
        if (selectedUnitId >= 0) {
            UnitInstance sel = findOwn(selectedUnitId);
            if (sel != null && sel.def() != null) {
                drawButton(target, sellBtn, "Sell " + sellPrice(you, sel) + "g  ("
                                + KeyBinds.label(GameAction.AUTO_SELL) + ")", true,
                        new Color(120, 55, 55), new Color(235, 140, 120));
                if (!sel.items.isEmpty()) {
                    drawButton(target, unequipBtn, you.unequipUsed()
                                    ? "Items removed this round"
                                    : "Remove items  (1/round)",
                            !you.unequipUsed(), new Color(50, 70, 100),
                            new Color(140, 180, 235));
                }
            }
        }
    }

    /** Mirror of the server's sell pricing, for the button label. */
    private static int sellPrice(AutoClient.You you, UnitInstance u) {
        if (u.star <= 1) return u.paid >= 0 ? u.paid : u.def().cost;
        int value = u.def().value(u.star);
        return you.relic() == Relic.FENCE ? value * 3 : value;
    }

    private void drawButton(DrawTarget target, Rectangle r, String label, boolean enabled) {
        drawButton(target, r, label, enabled, new Color(45, 52, 76), new Color(160, 172, 205));
    }

    private void drawButton(DrawTarget target, Rectangle r, String label, boolean enabled,
                            Color fill, Color edge) {
        target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                enabled ? fill : new Color(32, 35, 50));
        target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                enabled ? edge : new Color(70, 74, 95));
        target.drawText(label, r.x + (r.width - target.textWidth(label, SANS_BOLD_13)) / 2,
                r.y + r.height / 2 + 5, SANS_BOLD_13,
                enabled ? Color.WHITE : new Color(120, 124, 145));
    }

    private void drawItemBench(DrawTarget target) {
        AutoClient.You you = client.you();
        if (you == null || you.items().isEmpty()) return;
        Rectangle top = itemSlots.isEmpty() ? null : itemSlots.get(itemSlots.size() - 1);
        if (top != null) target.drawText("Items", 14, top.y - 6, SANS_BOLD_12, LEGEND_TEXT);
        for (int i = 0; i < itemSlots.size() && i < you.items().size(); i++) {
            Rectangle r = itemSlots.get(i);
            AutoItem item = AutoItems.get(you.items().get(i));
            target.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8, new Color(30, 34, 52, 220));
            boolean active = i == selectedItemIndex;
            target.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8,
                    active ? new Color(255, 220, 110)
                    : i == hoverItem ? new Color(140, 170, 240) : new Color(60, 66,
                            92), active ? 2.5f : 1f);
            if (item != null) {
                target.drawImage(itemImage(item, r.width - 8), r.x + 4, r.y + 4, r.width - 8,
                        r.height - 8);
            }
        }
    }

    // --- drag & drop feedback -----------------------------------------------------

    /**
     * While dragging, highlight the legal drop target under the cursor and draw
     * the grabbed unit or item gem floating at the pointer.
     */
    private void drawDrag(DrawTarget target) {
        if (!dragging) return;
        AutoClient.You you = client.you();
        if (you == null) return;

        if (grabUnitId >= 0) {
            UnitInstance u = findOwn(grabUnitId);
            if (u == null || u.def() == null) return;
            highlightUnitDrop(target);
            int size = (int) (52 * camera.zoom);
            BufferedImage img = unitImage(u.def(), size, true, AnimState.IDLE, animClock);
            drawGhost(target, img, lastMouseX, lastMouseY, size);
            AutoSprites.drawStars(target, u.star, lastMouseX, lastMouseY - size / 2, 8);
        } else if (grabItemIndex >= 0 && grabItemIndex < you.items().size()) {
            AutoItem item = AutoItems.get(you.items().get(grabItemIndex));
            if (item == null) return;
            highlightItemDrop(target, you);
            int size = 40;
            drawGhost(target, itemImage(item, size), lastMouseX, lastMouseY, size);
        }
    }

    /** Draw a semi-transparent sprite centred on the cursor. */
    private void drawGhost(DrawTarget target, BufferedImage img, int cx, int cy, int size) {
        target.pushAlpha(0.8f);
        target.drawImage(img, cx - size / 2, cy - size / 2, size, size);
        target.popAlpha();
    }

    /** Green highlight on the bench slot or own-half cell a dragged unit would land on. */
    private void highlightUnitDrop(DrawTarget target) {
        Color glow = new Color(130, 225, 150);
        if (hoverBench >= 0) {
            Rectangle r = benchSlots[hoverBench];
            fillGlowRect(target, r, glow);
        } else if (hoverCol >= 0 && hoverRow >= BattleSim.ROWS / 2) {
            fillGlowTile(target, tilePolygon(hoverCol, hoverRow), glow);
        }
    }

    /** Highlight the unit a dragged item would equip — red if it can hold no more. */
    private void highlightItemDrop(DrawTarget target, AutoClient.You you) {
        // Named `unit`, not `target`: the drawing surface owns that name now.
        UnitInstance unit = null;
        if (hoverBench >= 0) unit = benchUnitAt(you, hoverBench);
        else if (hoverCol >= 0) unit = boardUnitAt(you, hoverCol, hoverRow);
        if (unit == null) return;
        boolean full = unit.items.size() >= UnitInstance.MAX_ITEMS;
        Color glow = full ? new Color(235, 120, 110) : new Color(130, 225, 150);
        if (unit.onBoard()) {
            fillGlowTile(target, tilePolygon(unit.col, unit.row), glow);
        } else if (unit.bench >= 0 && unit.bench < benchSlots.length) {
            fillGlowRect(target, benchSlots[unit.bench], glow);
        }
    }

    private void fillGlowRect(DrawTarget target, Rectangle r, Color glow) {
        target.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10,
                new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 80));
        target.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10, glow, 2.5f);
    }

    private void fillGlowTile(DrawTarget target, Polygon p, Color glow) {
        target.fillPolygon(p.xpoints, p.ypoints, p.npoints,
                new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 80));
        target.drawPolygon(p.xpoints, p.ypoints, p.npoints, glow, 2.5f);
    }

    private void drawToasts(DrawTarget target) {
        int y = viewportHeight - shopBarHeight() - 86;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            int alpha = (int) (230 * Math.max(0, Math.min(1, (3.5 - t.age) / 0.8)));
            drawCentered(target, t.text, viewportWidth / 2, y, SANS_PLAIN_14,
                    new Color(240, 240, 250, alpha));
            y -= 20;
        }
    }

    private void drawBanner(DrawTarget target) {
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
        drawCentered(target, text, viewportWidth / 2, 120, SANS_BOLD_34,
                new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        (int) (255 * fade)));
    }

    // --- the scout overlay -----------------------------------------------------------

    /** The board-view overlay: another player's board, bench, and stats. */
    private void drawBoardView(DrawTarget target) {
        if (viewingId < 0) return;
        Rectangle p = viewPanelRect;
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(8, 9, 16, 120));
        target.fillRoundRect(p.x, p.y, p.width, p.height, 14, 14, new Color(16, 18, 32, 245));
        target.drawRoundRect(p.x, p.y, p.width, p.height, 14, 14, new Color(90, 100, 140));

        // Close button.
        target.fillRoundRect(viewCloseRect.x, viewCloseRect.y, viewCloseRect.width,
                viewCloseRect.height, 8, 8, new Color(45, 50, 70));
        target.drawRoundRect(viewCloseRect.x, viewCloseRect.y, viewCloseRect.width,
                viewCloseRect.height, 8, 8, new Color(200, 206, 226));
        drawCentered(target, "✕", viewCloseRect.x + viewCloseRect.width / 2,
                viewCloseRect.y + 18, SANS_BOLD_13, new Color(200, 206, 226));

        AutoClient.BoardView bv = client.boardView();
        if (bv == null || bv.id() != viewingId) {
            drawCentered(target, "Fetching board…", p.x + p.width / 2, p.y + p.height / 2,
                    SANS_PLAIN_16, new Color(160, 168, 190));
            return;
        }

        // Title.
        String title = bv.name() + (bv.bot() ? "  [BOT]" : "");
        target.drawText(trim(target, SANS_BOLD_20, title, p.width - 260),
                p.x + 18, p.y + 32, SANS_BOLD_20,
                new Color(235, 238, 250));
        if (!bv.alive()) {
            target.drawText("Eliminated" + (bv.place() > 0 ? "  ·  #" + bv.place() : ""), p.x + 18,
                    p.y + 50, SANS_PLAIN_13, new Color(230, 130, 110));
        }

        // Their board (own half: rows 4-7) + bench, drawn as a flat grid.
        int statsW = 216;
        int cs = Math.max(24, Math.min(46,
                Math.min((p.width - statsW - 48) / 8, (p.height - 170) / 5)));
        int gx = p.x + 18, gy = p.y + 62;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 8; c++) {
                target.fillRect(gx + c * cs, gy + r * cs, cs - 1, cs - 1,
                        (c + r) % 2 == 0 ? new Color(40, 46, 66) : new Color(46, 52, 74));
            }
        }
        for (UnitInstance u : bv.board()) {
            UnitDef def = u.def();
            int gridRow = u.row - BattleSim.ROWS / 2;
            if (def == null || u.col < 0 || u.col >= 8 || gridRow < 0 || gridRow >= 4) continue;
            int ux = gx + u.col * cs, uy = gy + gridRow * cs;
            BufferedImage img = unitImage(def, cs - 6, true, AnimState.IDLE, animClock);
            target.drawImage(img, ux + 3, uy + 2, cs - 6, cs - 6);
            AutoSprites.drawStars(target, u.star, ux + cs / 2, uy + 1, 5);
            drawItemPips(target, u, ux + cs / 2, uy + cs - 8);
        }

        // Bench strip below the board.
        int bs = Math.max(18, cs * 8 / 9 - 2);
        int by = gy + 4 * cs + 10;
        target.drawText("Bench", gx, by - 2, SANS_BOLD_11, new Color(130, 136, 156));
        for (int i = 0; i < 9; i++) {
            target.fillRect(gx + i * (bs + 2), by + 2, bs, bs, new Color(30, 34, 52, 220));
        }
        for (UnitInstance u : bv.bench()) {
            UnitDef def = u.def();
            if (def == null || u.bench < 0 || u.bench >= 9) continue;
            int ux = gx + u.bench * (bs + 2);
            BufferedImage img = unitImage(def, bs - 4, true, AnimState.IDLE, animClock);
            target.drawImage(img, ux + 2, by + 4, bs - 4, bs - 4);
            AutoSprites.drawStars(target, u.star, ux + bs / 2, by + 3, 4);
        }

        // Stats column on the right.
        int sx = p.x + p.width - statsW;
        int sy = p.y + 66;
        target.fillRect(sx, sy - 12, 130, 14, new Color(15, 15, 22, 200));
        target.fillRect(sx, sy - 12, (int) (130 * Math.min(1, bv.hp() / 100.0)), 14,
                hpColor(bv.hp()));
        target.drawText(bv.hp() + " HP", sx + 138, sy, SANS_PLAIN_14, new Color(235, 238, 250));
        sy += 26;
        String xp = bv.xpNeed() > 0 ? bv.xp() + "/" + bv.xpNeed() + " xp" : "max";
        target.drawText("Level " + bv.level() + "  ·  " + xp, sx, sy, SANS_PLAIN_14,
                new Color(200, 206, 226));
        sy += 22;
        target.drawText(bv.gold() + " gold", sx, sy, SANS_PLAIN_14, new Color(255, 214, 100));
        if (bv.streak() != 0) {
            target.drawText((bv.streak() > 0 ? "W" : "L") + Math.abs(bv.streak()) + " streak",
                    sx + 92, sy, SANS_PLAIN_14,
                    bv.streak() > 0 ? new Color(130, 220, 140) : new Color(230, 130, 110));
        }
        sy += 22;
        target.drawText(bv.board().size() + "/" + bv.boardCap() + " fielded", sx, sy, SANS_PLAIN_14,
                new Color(200, 206, 226));
        sy += 28;

        // Their synergies, from the scouted board.
        target.drawText("Synergies", sx, sy, SANS_BOLD_13, new Color(160, 168, 190));
        sy += 18;
        Map<Trait, Integer> counts = BattleSim.countTraits(bv.board());
        List<Map.Entry<Trait, Integer>> traits = new ArrayList<>(counts.entrySet());
        traits.sort((a, b) -> b.getValue() - a.getValue());
        int traitBottom = p.y + p.height - 26;
        if (traits.isEmpty()) {
            target.drawText("none", sx, sy, SANS_PLAIN_13, new Color(110, 115, 135));
        }
        for (Map.Entry<Trait, Integer> e : traits) {
            if (sy > traitBottom) break;
            int tier = e.getKey().tier(e.getValue());
            target.fillOval(sx, sy - 10, 11, 11,
                    tier > 0 ? e.getKey().color : new Color(110, 115, 135));
            target.drawText(e.getKey().label + "  " + e.getValue(), sx + 17, sy, SANS_PLAIN_13,
                    tier > 0 ? new Color(230, 234, 246) : new Color(130, 136, 156));
            sy += 20;
        }

        drawCentered(target, "Esc or click outside to close  ·  updates live",
                p.x + p.width / 2, p.y + p.height - 10, SANS_PLAIN_11,
                new Color(130, 136, 156));
    }

    // --- tooltips ------------------------------------------------------------------

    private void drawTooltips(DrawTarget target) {
        AutoClient.You you = client.you();
        if (you == null) return;
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();

        if (hoverRelic && you.relic() != null) {
            Relic relic = you.relic();
            lines.add(relic.label + "  (relic)");
            colors.add(relic.color);
            lines.add(relic.description);
            colors.add(new Color(200, 205, 222));
            for (Trait t : you.traitBoosts()) {
                lines.add("Zealot boost: " + t.label + " is 1.5x for the rest of the game");
                colors.add(t.color);
            }
            lines.add("Relics are re-dealt at random every "
                    + AutoGame.RELIC_INTERVAL + " rounds.");
            colors.add(new Color(150, 156, 178));
        } else if (hoverCategory != null) {
            lines.add(hoverCategory.label);
            colors.add(hoverCategory.color);
            lines.add(hoverCategory.description);
            colors.add(new Color(200, 205, 222));
            lines.add("Click to show only " + hoverCategory.label + " synergies.");
            colors.add(new Color(150, 156, 178));
        } else if (hoverTrait != null) {
            lines.add(hoverTrait.label + "  ("
                    + (hoverTrait.kind == Trait.Kind.ORIGIN ? "Origin" : "Class") + ")");
            colors.add(hoverTrait.color);
            lines.add(hoverTrait.description);
            colors.add(new Color(200, 205, 222));
            StringBuilder cats = new StringBuilder();
            for (SynergyCategory cat : hoverTrait.categories) {
                if (cats.length() > 0) cats.append(" · ");
                cats.append(cat.label);
            }
            lines.add("Role: " + cats);
            colors.add(new Color(170, 178, 205));
            if (hoverTrait.isSupport()) {
                lines.add("Support synergy — enhances the rest of your team.");
                colors.add(SynergyCategory.SUPPORT.color);
            }
        } else if (hoverShop >= 0 && hoverShop < you.shop().size()
                && you.shop().get(hoverShop) != null) {
            tooltipForDef(AutoUnits.get(you.shop().get(hoverShop)), 1, List.of(), lines, colors);
        } else if (hoverItem >= 0 && hoverItem < you.items().size()) {
            AutoItem item = AutoItems.get(you.items().get(hoverItem));
            if (item != null) {
                lines.add(item.name + (item.isComponent() ? "  (component)"
                        : item.isRelic() ? "  (elemental relic)" : ""));
                colors.add(item.color);
                lines.add(item.statLine());
                colors.add(new Color(200, 205, 222));
                if (item.isComponent()) {
                    lines.add("Click, then click a unit to equip.");
                    lines.add("Two components combine on a unit.");
                    colors.add(new Color(150, 156, 178));
                    colors.add(new Color(150, 156, 178));
                } else if (item.isRelic()) {
                    lines.add("Rewires the holder's elemental affinity.");
                    colors.add(new Color(150, 156, 178));
                }
            }
        } else if (hoverUnit != null) {
            tooltipForDef(hoverUnit.def(), hoverUnit.star, hoverUnit.items, lines, colors);
            if (hoverUnit.bonusTrait != null) {
                lines.add("Attuned: also counts as " + hoverUnit.bonusTrait.label);
                colors.add(hoverUnit.bonusTrait.color);
            }
            if (AutoUnits.WISP.equals(hoverUnit.key) && hoverUnit.star == 1) {
                lines.add("Drag onto a pair to fuse as its missing copy.");
                colors.add(new Color(180, 225, 245));
            }
        }
        if (lines.isEmpty()) return;

        int tw = 0;
        for (String line : lines) tw = Math.max(tw, target.textWidth(line, SANS_PLAIN_13));
        int th = lines.size() * 18 + 14;
        int mx = Math.min(viewportWidth - tw - 28, Math.max(8, lastMouseX + 16));
        int my = Math.min(viewportHeight - th - 8, Math.max(8, lastMouseY - th - 6));
        target.fillRoundRect(mx, my, tw + 20, th, 10, 10, new Color(12, 14, 24, 235));
        target.drawRoundRect(mx, my, tw + 20, th, 10, 10, new Color(90, 100, 140));
        int y = my + 20;
        for (int i = 0; i < lines.size(); i++) {
            target.drawText(lines.get(i), mx + 10, y,
                    new Font("SansSerif", i == 0 ? Font.BOLD : Font.PLAIN, 13), colors.get(i));
            y += 18;
        }
    }

    /** "Fire", "Fire + Cryo" — element names joined for tooltip lines. */
    private static String elementList(List<Element> elements) {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.label);
        }
        return sb.toString();
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
        if (!def.attackElements.isEmpty()) {
            lines.add("Attacks with " + elementList(def.attackElements));
            colors.add(def.attackElements.get(0).color);
        }
        if (!def.resistances.isEmpty()) {
            lines.add("Resists " + elementList(def.resistances));
            colors.add(new Color(150, 200, 170));
        }
        if (!def.weaknesses.isEmpty()) {
            lines.add("Weak to " + elementList(def.weaknesses));
            colors.add(new Color(235, 150, 130));
        }
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

    private void drawOverlays(DrawTarget target) {
        if (client.gameOver() != null) {
            AutoClient.GameOver over = client.gameOver();
            dim(target);
            drawCentered(target, over.winner() + " wins!", viewportWidth / 2, viewportHeight / 3,
                    SANS_BOLD_44, new Color(255, 214, 100));
            int y = viewportHeight / 3 + 56;
            for (String row : over.standings()) {
                drawCentered(target, row, viewportWidth / 2, y, SANS_PLAIN_20,
                        new Color(215, 220, 235));
                y += 30;
            }
            drawCentered(target, "Press Esc to return to the lobby",
                    viewportWidth / 2, y + 24, SANS_PLAIN_16, new Color(150, 156, 178));
            return;
        }
        if (!client.isConnected()) {
            dim(target);
            drawCentered(target, "Disconnected", viewportWidth / 2, viewportHeight / 2 - 20,
                    SANS_BOLD_28, new Color(235, 120, 105));
            drawCentered(target, String.valueOf(client.disconnectReason()),
                    viewportWidth / 2, viewportHeight / 2 + 12, SANS_PLAIN_17,
                    new Color(200, 205, 220));
            drawCentered(target, "Press Esc to leave", viewportWidth / 2, viewportHeight / 2 + 44,
                    SANS_PLAIN_17, new Color(150, 156, 178));
            return;
        }
        if (paused) {
            dim(target);
            drawCentered(target, "Paused", viewportWidth / 2, viewportHeight / 2 - 24, SANS_BOLD_30,
                    new Color(235, 238, 250));
            drawCentered(target, "The match keeps running online",
                    viewportWidth / 2, viewportHeight / 2 + 10, SANS_PLAIN_17,
                    new Color(190, 195, 214));
            // The keys as they are actually bound: this game's controls are
            // rebindable now (GameAction.Category.AUTO_BATTLER), and a prompt
            // naming a key nobody has any more is worse than no prompt.
            drawCentered(target, KeyBinds.label(GameAction.MENU_BACK) + " — resume   ·   "
                            + KeyBinds.label(GameAction.AUTO_LEAVE) + " — leave match",
                    viewportWidth / 2, viewportHeight / 2 + 40, SANS_PLAIN_17,
                    new Color(190, 195, 214));
        }
    }

    private void dim(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(8, 9, 16, 200));
    }

    private void drawCentered(DrawTarget target, String s, int cx, int y,
                              Font font, Color color) {
        target.drawText(s, cx - target.textWidth(s, font) / 2, y, font, color);
    }

    private static String trim(DrawTarget target, Font font, String s, int maxWidth) {
        if (target.textWidth(s, font) <= maxWidth) return s;
        String out = s;
        while (out.length() > 1 && target.textWidth(out + "…", font) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
