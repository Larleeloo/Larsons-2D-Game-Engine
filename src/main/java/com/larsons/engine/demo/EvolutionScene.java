package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.evolution.Ability;
import com.larsons.engine.evolution.BodyShape;
import com.larsons.engine.evolution.Dish;
import com.larsons.engine.evolution.DishTool;
import com.larsons.engine.evolution.EnergyOrb;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.Organism;
import com.larsons.engine.evolution.Phenotype;
import com.larsons.engine.evolution.ShopItem;
import com.larsons.engine.evolution.SpeciesRecord;
import com.larsons.engine.evolution.Trait;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The microscope: one Petri dish under the lens, the tool tray, the shop and
 * the read-outs.
 *
 * <p>The view pans and zooms over the slide exactly like a real stage, because
 * a dish is bigger than the screen at working magnification — the design
 * document asks for an area "that can be scanned like a microscope panning over
 * a slide". Everything the player does is a click into the gel with whichever
 * tool is held: drop food, drop a pillar, warm a corner, shine a light, spill
 * mutagen, or lift a single cell out on the spatula.
 *
 * <p>The slide's own light is drawn in world space — shadow pools, then a glow
 * for every spotlight, warm source and bioluminescent cell over the top — so
 * light emission is not decoration: the radius that lights the gel is the
 * radius the simulation forages by. Doing it in the scene rather than through
 * the engine's screen-space lighting pass is deliberate: that pass dims the
 * finished frame, HUD and all, and the instrument panel has to stay readable.
 * A gentle bloom from the shader chain is what makes the glow bloom.
 *
 * <p><b>Controls.</b> {@code 1}–{@code 9}/{@code 0} pick a tool, {@code `} or
 * {@code I} goes back to inspecting. Left-click uses the tool; right-drag (or
 * WASD/arrows) pans; the wheel zooms. {@code B} opens the shop, {@code K} the
 * reference book, {@code Tab} switches dish, {@code T} toggles the thermometer
 * overlay, {@code [} and {@code ]} work the time warp, {@code H} shows help and
 * {@code Esc} pauses.
 */
public class EvolutionScene extends AbstractScene {

    // --- palette ---------------------------------------------------------------

    private static final Color BG = new Color(9, 11, 16);
    private static final Color GEL = new Color(20, 30, 34);
    private static final Color GEL_EDGE = new Color(96, 132, 128);
    private static final Color PANEL = new Color(20, 24, 34, 235);
    private static final Color PANEL_EDGE = new Color(74, 86, 110);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color TEXT_DIM = new Color(146, 158, 180);
    private static final Color CREDIT = new Color(240, 208, 110);
    private static final Color GOOD = new Color(140, 220, 150);
    private static final Color WARN = new Color(235, 130, 120);
    private static final Color BODY_EDGE = new Color(8, 10, 14, 200);

    /** Corner scratch for {@link #polygon} and {@link #star}; see the note there. */
    private final int[] shapeXs = new int[10];
    private final int[] shapeYs = new int[10];

    // Every font this scene sets, hoisted out of the render methods that used
    // to rebuild them each frame.
    private static final Font SANS_BOLD_13 = new Font("SansSerif", Font.BOLD, 13);
    private static final Font SANS_BOLD_15 = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SANS_BOLD_16 = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SANS_BOLD_22 = new Font("SansSerif", Font.BOLD, 22);
    private static final Font SANS_BOLD_34 = new Font("SansSerif", Font.BOLD, 34);
    private static final Font SANS_PLAIN_12 = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font SANS_PLAIN_13 = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SANS_PLAIN_14 = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font SANS_PLAIN_15 = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font SANS_PLAIN_16 = new Font("SansSerif", Font.PLAIN, 16);

    /** What a click into the gel does. */
    private enum Tool {
        INSPECT("Inspect", "inspect a cell", null),
        FEED_SIMPLE("Energy", "simple energy", ShopItem.ENERGY_SIMPLE),
        FEED_COMPLEX("Complex", "complex energy", ShopItem.ENERGY_COMPLEX),
        COLONY("Colony", "starter colony", ShopItem.STARTER_COLONY),
        BARRIER("Barrier", "barrier pillars", ShopItem.BARRIER),
        HEAT("Heat", "exothermic sources", ShopItem.HEAT_SOURCE),
        COLD("Cold", "endothermic sinks", ShopItem.COLD_SOURCE),
        SPOTLIGHT("Light", "spotlights", ShopItem.SPOTLIGHT),
        MUTAGEN("Mutagen", "mutagen vials", ShopItem.MUTAGEN),
        TOOLKIT("Tools", "cell tool kits", ShopItem.TOOL_KIT),
        SPATULA("Spatula", "spatulas", ShopItem.TRANSFER_SPATULA);

        final String label;
        final String longName; // used in "out of ..." messages
        final ShopItem item;   // null for inspect, which costs nothing

        Tool(String label, String longName, ShopItem item) {
            this.label = label;
            this.longName = longName;
            this.item = item;
        }
    }

    private final GameContext ctx;
    private final EvolutionStore store;

    private EvolutionGame game;
    private Camera camera;
    private final Particles particles = new Particles();

    private Tool tool = Tool.INSPECT;
    private Organism selected;

    private boolean paused;
    private boolean showShop;
    private boolean showHelp;
    private boolean showTemperature;
    private boolean resetting;
    private Menu pauseMenu;
    private Menu resetMenu;

    private double autosaveTimer;
    private String status = "";
    private double statusAge;

    // Pointer state captured in update() so render() can agree with it.
    private int mouseX, mouseY;
    private boolean panning;
    private int panFromX, panFromY;
    private double panFromCamX, panFromCamY;

    private final List<Toast> toasts = new ArrayList<>();
    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Rectangle> toolRects = new ArrayList<>();
    private final List<Rectangle> shopRects = new ArrayList<>();
    private final int[] screen = new int[2];

    private static final class Toast {
        String text;
        Color color;
        double age;
    }

    /**
     * An expanding ring in the gel. Cell behaviour is otherwise invisible — an
     * attack, a donation, a signal going out and a body handing energy back all
     * look like nothing at all — so each one throws a ring the player can read
     * at a glance, colour-coded to the ability doing it.
     */
    private static final class Ripple {
        double x, y;
        double radius;      // world units the ring grows to
        double age;
        double life;
        Color color;
        float width;
    }

    private static final Font MONO_PLAIN_12 = new Font("Monospaced", Font.PLAIN, 12);
    public EvolutionScene(GameContext ctx) {
        this(ctx, new EvolutionStore());
    }

    public EvolutionScene(GameContext ctx, EvolutionStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    /** Take over an experiment created (or loaded) by the lobby. */
    public void adopt(EvolutionGame game) {
        this.game = game;
        this.selected = null;
    }

    public EvolutionGame game() { return game; }

    @Override
    public void onEnter() {
        paused = false;
        resetting = false;
        showShop = false;
        showHelp = false;
        autosaveTimer = 0;
        toasts.clear();
        ripples.clear();
        particles.clear();
        if (game == null) game = EvolutionGame.newGame(com.larsons.engine.evolution.Nucleotide.G);

        camera = new Camera(Perspective.TOP_DOWN, viewportWidth, viewportHeight);
        camera.zoom = Math.max(fitZoom(), 1.15);
        camera.centerOn(0, 0);

        // The dish's darkness is drawn in world space (see drawSlide), not through
        // the engine's screen-space lighting pass: that pass dims the whole frame,
        // which would take the tool tray, the read-outs and the shop down with the
        // gel. The HUD has to stay at full brightness to be readable, so the only
        // post-FX here is a gentle bloom that makes bioluminescence glow.
        ctx.lighting().setDarkness(0);
        ctx.overrideShaders(List.of(Shaders.bloom()), 0.32);

        pauseMenu = new Menu("Paused")
                .subtitle("The dish keeps its state while you are away")
                .theme(MenuTheme.dark())
                .add("Resume", () -> paused = false)
                .add("Save experiment", this::saveNow)
                .add("Reset the lab (respend your credits)", this::startReset)
                // The lab is saved on the way out, so leaving for the controls
                // screen and coming back costs the experiment nothing.
                .add("Controls (Key Binds)", () -> {
                    saveNow();
                    KeyBindsScene.open(scenes, "evolution");
                })
                .add("Save and quit to menu", () -> {
                    saveNow();
                    scenes.transitionTo("evolutionlobby");
                })
                .add("Quit without saving", () -> scenes.transitionTo("evolutionlobby"));
    }

    @Override
    public void onExit() {
        if (game != null) store.save(game); // never lose an experiment by leaving
        ctx.lighting().setDarkness(0);
        ctx.applyLiveSettings(); // hand the shader chain back to the game type
    }

    @Override
    public void onResize(int width, int height) {
        super.onResize(width, height);
        if (camera != null) camera.setViewport(width, height);
    }

    private double fitZoom() {
        double r = game.activeDish().radius;
        return Math.min(viewportWidth / (2.4 * r), (viewportHeight - 120) / (2.2 * r));
    }

    // --- update ------------------------------------------------------------------------

    @Override
    public void update(double dt, InputManager input) {
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();
        updateToasts(dt);
        if (statusAge > 0) statusAge -= dt;

        if (paused) {
            if (resetting) {
                if (KeyBinds.pressed(input, GameAction.MENU_BACK)) resetting = false;
                else resetMenu.update(dt, input);
            } else if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                paused = false;
            } else {
                pauseMenu.update(dt, input);
            }
            return;
        }
        if (showHelp) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                    || input.isKeyJustPressed(KeyEvent.VK_H)) showHelp = false;
            return;
        }
        if (showShop) {
            updateShop(input);
            return;
        }

        handleKeys(input);
        handleCamera(input);
        handleClicks(input);

        game.step(dt);
        for (EvolutionGame.Notice n : game.drainNotices()) toast(n.text(), n.color());
        spawnEventParticles();
        particles.update(dt);
        updateRipples(dt);
        refreshSelection();

        autosaveTimer += dt;
        if (autosaveTimer >= 90) {
            autosaveTimer = 0;
            store.save(game);
        }
    }

    private void handleKeys(InputManager input) {
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE)) {
            paused = true;
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_H)) showHelp = true;
        if (input.isKeyJustPressed(KeyEvent.VK_B)) showShop = true;
        if (input.isKeyJustPressed(KeyEvent.VK_K)) {
            store.save(game); // the book reads the catalog files, so flush first
            scenes.transitionTo("evolutioncatalog");
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_T)) {
            if (game.inventory().owns(ShopItem.THERMOMETER)) {
                showTemperature = !showTemperature;
            } else {
                setStatus("The thermometer is an instrument you have to buy (B)");
            }
        }
        if (input.isKeyJustPressed(KeyEvent.VK_TAB)) {
            game.cycleDish(1);
            selected = null;
            camera.zoom = Math.max(fitZoom(), 1.15);
            camera.centerOn(0, 0);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_OPEN_BRACKET)) timeWarp(-1);
        if (input.isKeyJustPressed(KeyEvent.VK_CLOSE_BRACKET)) timeWarp(1);

        Tool[] byKey = {Tool.SPATULA, Tool.FEED_SIMPLE, Tool.FEED_COMPLEX, Tool.COLONY,
                Tool.BARRIER, Tool.HEAT, Tool.COLD, Tool.SPOTLIGHT, Tool.MUTAGEN, Tool.TOOLKIT};
        for (int i = 0; i < byKey.length; i++) {
            if (input.isKeyJustPressed(KeyEvent.VK_0 + i)) selectTool(byKey[i]);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_I) || input.isKeyJustPressed(KeyEvent.VK_BACK_QUOTE)) {
            selectTool(Tool.INSPECT);
        }
    }

    private void timeWarp(int delta) {
        if (!game.inventory().owns(ShopItem.TIME_WARP)) {
            setStatus("The time warp dial is an instrument you have to buy (B)");
            return;
        }
        game.cycleTimeScale(delta);
        setStatus(String.format("Time warp %.2f×", game.timeScale()));
    }

    private void selectTool(Tool t) {
        tool = t;
        if (t != Tool.SPATULA && game.carried() != null) game.cancelCarry();
    }

    /** Pan with the right button or WASD/arrows; zoom on the wheel, around the cursor. */
    private void handleCamera(InputManager input) {
        double panSpeed = 420 / camera.zoom * (1.0 / 60);
        if (KeyBinds.down(input, GameAction.MOVE_LEFT)) camera.x -= panSpeed;
        if (KeyBinds.down(input, GameAction.MOVE_RIGHT)) camera.x += panSpeed;
        if (KeyBinds.down(input, GameAction.MOVE_UP)) camera.y -= panSpeed;
        if (KeyBinds.down(input, GameAction.MOVE_DOWN)) camera.y += panSpeed;

        if (input.isRightMouseJustPressed()) {
            panning = true;
            panFromX = mouseX;
            panFromY = mouseY;
            panFromCamX = camera.x;
            panFromCamY = camera.y;
        }
        if (panning) {
            if (!input.isRightMouseDown()) {
                panning = false;
            } else {
                camera.x = panFromCamX - (mouseX - panFromX) / camera.zoom;
                camera.y = panFromCamY - (mouseY - panFromY) / camera.zoom;
            }
        }

        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            double[] before = camera.screenToWorld(mouseX, mouseY);
            double min = fitZoom() * 0.75;
            camera.zoom = Math.max(min, Math.min(4.0, camera.zoom * Math.pow(0.88, wheel)));
            double[] after = camera.screenToWorld(mouseX, mouseY);
            camera.x += before[0] - after[0]; // keep the point under the cursor put
            camera.y += before[1] - after[1];
        }

        // Never let the stage wander off the slide entirely.
        double limit = game.activeDish().radius * 1.4;
        camera.x = Math.max(-limit, Math.min(limit, camera.x));
        camera.y = Math.max(-limit, Math.min(limit, camera.y));
    }

    private void handleClicks(InputManager input) {
        if (!input.isMouseJustPressed()) return;

        for (int i = 0; i < toolRects.size() && i < Tool.values().length; i++) {
            if (toolRects.get(i).contains(mouseX, mouseY)) {
                selectTool(Tool.values()[i]);
                return;
            }
        }
        if (mouseY < 44 || mouseY > viewportHeight - 78) return; // HUD chrome

        double[] world = camera.screenToWorld(mouseX, mouseY);
        applyTool(world[0], world[1]);
    }

    /** Use the held tool at a point in the gel, reporting why if it cannot be used. */
    private void applyTool(double wx, double wy) {
        Dish dish = game.activeDish();
        if (tool == Tool.INSPECT) {
            Organism hit = dish.organismAt(wx, wy, 22 / camera.zoom + 6);
            selected = hit;
            return;
        }
        if (tool == Tool.SPATULA) {
            useSpatula(wx, wy);
            return;
        }
        if (!dish.contains(wx, wy)) {
            setStatus("That is outside the dish");
            return;
        }
        boolean ok = switch (tool) {
            case FEED_SIMPLE -> game.feed(EnergyOrb.Kind.SIMPLE, wx, wy);
            case FEED_COMPLEX -> game.feed(EnergyOrb.Kind.COMPLEX, wx, wy);
            case COLONY -> game.placeStarterColony(wx, wy);
            case BARRIER -> game.placeBarrier(wx, wy);
            case HEAT -> game.placeHeatSource(wx, wy, false);
            case COLD -> game.placeHeatSource(wx, wy, true);
            case SPOTLIGHT -> game.placeSpotlight(wx, wy);
            case MUTAGEN -> game.placeMutagen(wx, wy);
            case TOOLKIT -> game.placeToolKit(wx, wy);
            default -> false;
        };
        if (ok) {
            camera.worldToScreen(wx, wy, screen);
            particles.burst(wx, wy, toolColor(tool), 8, Particles.Style.SPARKS);
        } else {
            setStatus("Out of " + tool.longName + " — buy more in the shop (B)");
        }
    }

    private void useSpatula(double wx, double wy) {
        if (game.carried() != null) {
            if (game.dropCarried(wx, wy)) {
                setStatus("Placed the organism");
            } else {
                setStatus("Drop it inside the dish");
            }
            return;
        }
        if (game.inventory().count(ShopItem.TRANSFER_SPATULA) <= 0) {
            setStatus("No spatulas left — buy one in the shop (B)");
            return;
        }
        Organism lifted = game.liftOrganism(wx, wy);
        if (lifted == null) {
            setStatus("Click directly on an organism to lift it");
        } else {
            setStatus("Lifted " + lifted.genome.sequence()
                    + " — switch dish with Tab, then click to place it");
        }
    }

    private void updateShop(InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE) || input.isKeyJustPressed(KeyEvent.VK_B)) {
            showShop = false;
            return;
        }
        if (!input.isMouseJustPressed()) return;
        ShopItem[] items = ShopItem.values();
        for (int i = 0; i < shopRects.size() && i < items.length; i++) {
            if (!shopRects.get(i).contains(mouseX, mouseY)) continue;
            ShopItem item = items[i];
            if (item.permanent() && game.inventory().owns(item)) {
                setStatus("You already own the " + item.displayName().toLowerCase());
            } else if (game.buy(item)) {
                for (EvolutionGame.Notice n : game.drainNotices()) toast(n.text(), n.color());
            } else {
                setStatus("Not enough credit for " + item.displayName());
            }
            return;
        }
    }

    /** Keep the inspector pointed at a living cell, or let it go when it dies. */
    private void refreshSelection() {
        if (selected == null) return;
        if (!selected.alive || !game.activeDish().organisms().contains(selected)) selected = null;
    }

    /**
     * Turn what the dish just did into something visible. Births, deaths and
     * meals are ambient; hunting, sharing, signalling, tool pickups and
     * decomposition each get a ring as well, because those are the behaviours a
     * player is actually trying to watch for.
     */
    private void spawnEventParticles() {
        for (Dish.Event e : game.drainPresentationEvents()) {
            switch (e.kind()) {
                case BIRTH -> particles.burst(e.x(), e.y(), e.color(), 6, Particles.Style.MOTES);
                case DEATH -> particles.burst(e.x(), e.y(), e.color(), 5, Particles.Style.DRIP);
                case KILL -> {
                    particles.burst(e.x(), e.y(), new Color(235, 90, 80), 16,
                            Particles.Style.BURST);
                    ripple(e.x(), e.y(), 46, 0.55, new Color(235, 90, 80), 3f);
                }
                case ATTACK -> {
                    particles.burst(e.x(), e.y(), e.color(), 7, Particles.Style.SPARKS);
                    ripple(e.x(), e.y(), 28, 0.35, e.color(), 2.5f);
                }
                case SHARE -> {
                    particles.burst(e.x(), e.y(), e.color(), 5, Particles.Style.EMBERS);
                    ripple(e.x(), e.y(), 30, 0.6, e.color(), 2f);
                }
                case BROADCAST -> ripple(e.x(), e.y(),
                        Math.max(60, e.radius()), 1.1, e.color(), 2f);
                case BIND -> {
                    particles.burst(e.x(), e.y(), new Color(200, 235, 150), 12,
                            Particles.Style.RING);
                    ripple(e.x(), e.y(), 60, 0.8, new Color(200, 235, 150), 3f);
                }
                case TOOL -> {
                    particles.burst(e.x(), e.y(), e.color(), 12, Particles.Style.SPARKS);
                    ripple(e.x(), e.y(), 40, 0.7, e.color(), 2.5f);
                }
                case DECAY -> {
                    // The orb that just appeared came from this body — say so.
                    particles.burst(e.x(), e.y(), EnergyOrb.Kind.SIMPLE.color(), 5,
                            Particles.Style.MOTES);
                    ripple(e.x(), e.y(), 20, 0.45, EnergyOrb.Kind.SIMPLE.color(), 1.5f);
                }
                case EAT -> { /* far too frequent to draw */ }
            }
        }
    }

    private void ripple(double x, double y, double radius, double life, Color color, float width) {
        if (ripples.size() > 80) return; // a busy dish must not drown in rings
        Ripple r = new Ripple();
        r.x = x;
        r.y = y;
        r.radius = radius;
        r.life = life;
        r.color = color;
        r.width = width;
        ripples.add(r);
    }

    private void updateRipples(double dt) {
        for (int i = ripples.size() - 1; i >= 0; i--) {
            Ripple r = ripples.get(i);
            r.age += dt;
            if (r.age >= r.life) ripples.remove(i);
        }
    }

    /** Draw the rings, each fading as it grows. */
    private void drawRipples(DrawTarget target) {
        for (Ripple r : ripples) {
            double t = Math.min(1, r.age / r.life);
            int rad = (int) Math.round(r.radius * (0.25 + 0.75 * t) * camera.zoom);
            if (rad < 2) continue;
            camera.worldToScreen(r.x, r.y, screen);
            int alpha = (int) (200 * (1 - t));
            target.drawOval(screen[0] - rad, screen[1] - rad, rad * 2, rad * 2,
                    new Color(r.color.getRed(), r.color.getGreen(), r.color.getBlue(), alpha),
                    r.width);
        }
    }

    /**
     * Light the gel in world space: a soft glow for every spotlight, warm source
     * and bioluminescent cell, drawn over the shadow patches so light visibly
     * eats into them. These are the same radii the simulation forages by, so
     * what looks lit is lit — and because it is drawn into the scene rather than
     * applied to the finished frame, none of it touches the HUD.
     */
    private void drawLightField(DrawTarget target, Dish dish) {
        target.pushAlpha(0.55f);
        for (Dish.Spotlight sp : dish.spotlights()) {
            camera.worldToScreen(sp.x, sp.y, screen);
            paintRadial(target, screen[0], screen[1], (int) (sp.radius * camera.zoom),
                    new Color(255, 250, 222, 150), new Color(255, 250, 222, 0));
        }
        for (Dish.HeatSource h : dish.heatSources()) {
            if (h.power <= 0) continue;
            camera.worldToScreen(h.x, h.y, screen);
            paintRadial(target, screen[0], screen[1], (int) (h.radius * 0.7 * camera.zoom),
                    new Color(255, 150, 90, 90), new Color(255, 150, 90, 0));
        }
        for (Organism o : dish.luminousOrganisms()) {
            camera.worldToScreen(o.x, o.y, screen);
            Color c = o.pheno.color();
            paintRadial(target, screen[0], screen[1], (int) (o.effectiveLightRadius() * camera.zoom),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 120),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 0));
        }
        target.popAlpha();
    }

    private void updateToasts(double dt) {
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            t.age += dt;
            if (t.age > 6) toasts.remove(i);
        }
    }

    private void toast(String text, Color color) {
        Toast t = new Toast();
        t.text = text;
        t.color = color;
        toasts.add(t);
        while (toasts.size() > 6) toasts.remove(0);
    }

    private void setStatus(String s) {
        status = s;
        statusAge = 3.5;
    }

    /**
     * Ask before resetting. Everything in the lab goes, so "keep" is the first
     * (and therefore default-selected) choice and a stray Enter backs out.
     */
    private void startReset() {
        resetMenu = new Menu("Reset the game?")
                .subtitle("Everything starts over — your discovery history is kept")
                .theme(MenuTheme.dark())
                .add("Cancel — keep this game", () -> resetting = false);
        for (com.larsons.engine.evolution.Nucleotide n
                : com.larsons.engine.evolution.Nucleotide.values()) {
            resetMenu.add("Start over from " + n.displayName(), () -> {
                game.resetExperiment(n);
                for (EvolutionGame.Notice notice : game.drainNotices()) {
                    toast(notice.text(), notice.color());
                }
                store.save(game);
                selected = null;
                resetting = false;
                paused = false;
                camera.zoom = Math.max(fitZoom(), 1.15);
                camera.centerOn(0, 0);
            });
        }
        resetting = true;
    }

    private void saveNow() {
        store.save(game);
        setStatus("Saved to " + store.saveFile());
        toast("Experiment saved", GOOD);
    }

    // --- render -------------------------------------------------------------------------

    @Override
    public void render(DrawTarget target, float alpha) {

        target.fillRect(0, 0, viewportWidth, viewportHeight, BG);
        if (game == null) return;

        Dish dish = game.activeDish();
        drawSlide(target, dish);
        if (showTemperature && game.inventory().owns(ShopItem.THERMOMETER)) {
            drawTemperature(target, dish);
        }
        drawLightField(target, dish);
        drawDishContents(target, dish);
        particles.render(target, camera);
        drawRipples(target);
        drawToolGhost(target, dish);

        drawTopBar(target, dish);
        drawToolTray(target);
        drawInspector(target);
        drawToasts(target);

        if (statusAge > 0) {
            drawCentered(target, status, viewportWidth / 2, viewportHeight - 88,
                    SANS_PLAIN_14, TEXT_DIM);
        }
        if (game.isGameOver()) drawGameOver(target);
        if (showShop) drawShop(target);
        if (showHelp) drawHelp(target);
        if (paused) {
            dim(target, 170);
            if (resetting) {
                resetMenu.render(target, viewportWidth, viewportHeight);
                drawCentered(target,
                        "Dishes, bench, instruments, credits and this game's catalog: all gone",
                        viewportWidth / 2, viewportHeight / 4 + 90, SANS_PLAIN_15, WARN);
                drawCentered(target, "Your history of " + game.history().speciesCount()
                                + " discovered organisms and "
                                + game.history().unlockedAchievements().size()
                                + " achievements is kept",
                        viewportWidth / 2, viewportHeight / 4 + 114, SANS_PLAIN_15, GOOD);
            } else {
                pauseMenu.render(target, viewportWidth, viewportHeight);
            }
        }
    }

    /** The glass: gel, rim, and the unlit pools that make light worth evolving. */
    private void drawSlide(DrawTarget target, Dish dish) {
        camera.worldToScreen(0, 0, screen);
        int cx = screen[0], cy = screen[1];
        int r = (int) Math.round(dish.radius * camera.zoom);

        target.fillOval(cx - r - 14, cy - r - 14, (r + 14) * 2, (r + 14) * 2, new Color(14, 18, 24));
        target.fillOval(cx - r, cy - r, r * 2, r * 2, GEL);

        // Shadowed patches of slide, drawn as soft dark discs.
        for (double[] p : dish.darkPatches()) {
            camera.worldToScreen(p[0], p[1], screen);
            int pr = (int) Math.round(p[2] * camera.zoom);
            if (pr <= 2) continue;
            paintRadial(target, screen[0], screen[1], pr,
                    new Color(0, 0, 0, 190), new Color(0, 0, 0, 0));
        }

        target.drawOval(cx - r, cy - r, r * 2, r * 2, GEL_EDGE, 3f);
        target.drawOval(cx - r + 6, cy - r + 6, (r - 6) * 2, (r - 6) * 2,
                new Color(140, 190, 185, 70));
    }

    /** The thermometer overlay: a coarse blue-to-red wash of the heat field. */
    private void drawTemperature(DrawTarget target, Dish dish) {
        int cells = 22;
        double step = dish.radius * 2.0 / cells;
        for (int gy = 0; gy < cells; gy++) {
            for (int gx = 0; gx < cells; gx++) {
                double wx = -dish.radius + (gx + 0.5) * step;
                double wy = -dish.radius + (gy + 0.5) * step;
                if (!dish.contains(wx, wy)) continue;
                double t = dish.temperatureAt(wx, wy);
                double n = Math.max(-1, Math.min(1, (t - Dish.AMBIENT_TEMP) / 18.0));
                int red = (int) (140 * Math.max(0, n));
                int blue = (int) (140 * Math.max(0, -n));
                camera.worldToScreen(wx - step / 2, wy - step / 2, screen);
                int px = screen[0], py = screen[1];
                int size = (int) Math.ceil(step * camera.zoom) + 1;
                target.fillRect(px, py, size, size, new Color(80 + red, 60, 80 + blue, 70));
            }
        }
    }

    private void drawDishContents(DrawTarget target, Dish dish) {
        // Mutagen clouds and spotlights sit under everything living.
        for (Dish.Mutagen m : dish.mutagens()) {
            camera.worldToScreen(m.x, m.y, screen);
            paintRadial(target, screen[0], screen[1], (int) (m.radius * camera.zoom),
                    new Color(190, 90, 200, 70), new Color(190, 90, 200, 0));
        }
        for (Dish.Spotlight s : dish.spotlights()) {
            camera.worldToScreen(s.x, s.y, screen);
            paintRadial(target, screen[0], screen[1], (int) (s.radius * camera.zoom),
                    new Color(255, 250, 220, 60), new Color(255, 250, 220, 0));
        }
        for (Dish.HeatSource h : dish.heatSources()) {
            camera.worldToScreen(h.x, h.y, screen);
            Color c = h.power > 0 ? new Color(255, 140, 80) : new Color(120, 200, 255);
            paintRadial(target, screen[0], screen[1], (int) (h.radius * camera.zoom),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 60),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 0));
            int rr = Math.max(4, (int) (9 * camera.zoom));
            target.fillRect(screen[0] - rr / 2, screen[1] - rr / 2, rr, rr, c);
        }

        for (Dish.Barrier b : dish.barriers()) {
            camera.worldToScreen(b.x, b.y, screen);
            int rr = (int) Math.round(b.radius * camera.zoom);
            target.fillOval(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2, new Color(58, 62, 74));
            target.drawOval(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2, new Color(96, 104, 122));
        }

        for (Dish.Waste w : dish.wastes()) {
            camera.worldToScreen(w.x, w.y, screen);
            int rr = Math.max(2, (int) (Math.min(9, 2 + w.amount * 0.12) * camera.zoom));
            target.fillOval(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2,
                    new Color(86, 74, 58, 150));
        }

        // Bodies are drawn as dimmed husks with a dashed halo, sized by how much
        // is left to dissolve — so a corpse reads as "this is still turning into
        // food" rather than as a speck of dirt.
        for (Dish.Corpse c : dish.corpses()) {
            camera.worldToScreen(c.x, c.y, screen);
            double left = Math.max(0.15, Math.min(1, c.energy / 40.0));
            int rr = Math.max(3, (int) ((4 + 5 * left) * camera.zoom));
            target.fillOval(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2, new Color(c.color.getRed() / 2, c.color.getGreen() / 2,
                    c.color.getBlue() / 2, 190));
            target.drawOval(screen[0] - rr - 3, screen[1] - rr - 3, (rr + 3) * 2, (rr + 3) * 2,
                    new Color(c.color.getRed(), c.color.getGreen(), c.color.getBlue(), 90));
        }

        for (EnergyOrb orb : dish.orbs()) {
            camera.worldToScreen(orb.x, orb.y, screen);
            int rr = Math.max(1, (int) Math.round(orb.radius() * camera.zoom));
            Color c = orb.kind.color();
            target.fillOval(screen[0] - rr - 2, screen[1] - rr - 2, rr * 2 + 4, rr * 2 + 4,
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
            target.fillOval(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2, c);
        }

        for (DishTool t : dish.tools()) {
            if (t.held()) continue;
            camera.worldToScreen(t.x, t.y, screen);
            int rr = Math.max(3, (int) (6 * camera.zoom));
            target.drawRect(screen[0] - rr, screen[1] - rr, rr * 2, rr * 2, t.kind.color());
            target.drawLine(screen[0] - rr, screen[1], screen[0] + rr, screen[1], t.kind.color());
        }

        drawColonyLinks(target, dish);
        for (Organism o : dish.organisms()) drawOrganism(target, o);

        // The cell riding the spatula hovers under the cursor.
        Organism carried = game.carried();
        if (carried != null) {
            drawBody(target, carried.pheno.shape(), mouseX, mouseY,
                    Math.max(5, carried.pheno.size() * camera.zoom), carried.pheno.color(), true);
        }
    }

    private void drawColonyLinks(DrawTarget target, Dish dish) {
        List<Organism> all = dish.organisms();
        for (int i = 0; i < all.size(); i++) {
            Organism a = all.get(i);
            if (a.colonyId < 0) continue;
            for (int j = i + 1; j < all.size(); j++) {
                Organism b = all.get(j);
                if (b.colonyId != a.colonyId) continue;
                double d = Math.hypot(a.x - b.x, a.y - b.y);
                if (d > 60) continue; // only draw links that read as a body
                camera.worldToScreen(a.x, a.y, screen);
                int ax = screen[0], ay = screen[1];
                camera.worldToScreen(b.x, b.y, screen);
                target.drawLine(ax, ay, screen[0], screen[1], new Color(200, 235, 150, 90), 1.5f);
            }
        }
    }

    private void drawOrganism(DrawTarget target, Organism o) {
        camera.worldToScreen(o.x, o.y, screen);
        int cx = screen[0], cy = screen[1];
        double r = Math.max(3, o.pheno.size() * camera.zoom);
        if (cx < -40 || cy < -40 || cx > viewportWidth + 40 || cy > viewportHeight + 40) return;

        double glow = o.effectiveLightRadius();
        if (glow > 0) {
            Color c = o.pheno.color();
            paintRadial(target, cx, cy, (int) (glow * camera.zoom * 0.75),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 70),
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 0));
        }

        Color body = o.pheno.color();
        if (o.hitFlash > 0) body = new Color(255, 235, 235);
        else if (o.venom > 0) body = blend(body, new Color(190, 90, 200), 0.45);
        drawBody(target, o.pheno.shape(), cx, cy, r, body, o == selected);

        // Fullness ring: a starving cell visibly runs down.
        if (r >= 5) {
            int ring = (int) (r + 3);
            target.drawArc(cx - ring, cy - ring, ring * 2, ring * 2, 90,
                    -(int) (360 * o.fullness()), new Color(255, 255, 255, 60));
        }
        // A cell carrying a tool wears it: a ring in the tool's colour plus a
        // marker, so "that one found the scalpel" is readable across the dish.
        if (o.tool != null) {
            int ring = (int) r + 5;
            target.drawOval(cx - ring, cy - ring, ring * 2, ring * 2, o.tool.kind.color(), 2f);
            target.fillRect(cx + ring - 3, cy - ring - 3, 6, 6, o.tool.kind.color());
        }
        // Hunters show a notch while their strike is off cooldown.
        if (o.pheno.has(Ability.PREDATION) && o.attackCooldown <= 0 && r >= 5) {
            target.drawLine(cx - (int) r, cy - (int) r - 4, cx, cy - (int) r - 8,
                    new Color(255, 120, 110, 200));
            target.drawLine(cx, cy - (int) r - 8, cx + (int) r, cy - (int) r - 4,
                    new Color(255, 120, 110, 200));
        }
    }

    /** Draw a body silhouette; shapes read differently at a glance on the slide. */
    private void drawBody(DrawTarget target, BodyShape shape, int cx, int cy, double r,
                          Color color, boolean highlight) {
        int ri = (int) Math.round(r);
        int corners = switch (shape) {
            case TRIANGLE -> polygon(cx, cy, r, 3, -Math.PI / 2);
            case PENTAGON -> polygon(cx, cy, r, 5, -Math.PI / 2);
            case HEXAGON -> polygon(cx, cy, r, 6, 0);
            case DIAMOND -> polygon(cx, cy, r, 4, 0);
            case STAR -> star(cx, cy, r);
            default -> 0;
        };
        switch (shape) {
            case SQUARE -> target.fillRect(cx - ri, cy - ri, ri * 2, ri * 2, color);
            case CIRCLE -> target.fillOval(cx - ri, cy - ri, ri * 2, ri * 2, color);
            case TRIANGLE, PENTAGON, HEXAGON, DIAMOND, STAR ->
                    target.fillPolygon(shapeXs, shapeYs, corners, color);
            case CROSS -> {
                int t = Math.max(2, ri / 2);
                target.fillRect(cx - t, cy - ri, t * 2, ri * 2, color);
                target.fillRect(cx - ri, cy - t, ri * 2, t * 2, color);
            }
        }
        switch (shape) {
            case SQUARE, CROSS -> target.drawRect(cx - ri, cy - ri, ri * 2, ri * 2, BODY_EDGE);
            case CIRCLE -> target.drawOval(cx - ri, cy - ri, ri * 2, ri * 2, BODY_EDGE);
            case TRIANGLE, PENTAGON, HEXAGON, DIAMOND, STAR ->
                    target.drawPolygon(shapeXs, shapeYs, corners, BODY_EDGE);
        }
        if (highlight) {
            target.drawOval(cx - ri - 5, cy - ri - 5, (ri + 5) * 2, (ri + 5) * 2,
                    new Color(255, 240, 180), 2f);
        }
    }

    /**
     * Write a regular polygon's corners into {@link #shapeXs}/{@link #shapeYs},
     * returning how many there are.
     *
     * <p>Scratch arrays rather than a returned {@code Polygon}: a dish can hold
     * hundreds of organisms, each body is drawn filled and then outlined, and
     * the old form allocated a {@code Polygon} — two arrays and an object — for
     * each of those two draws, every frame.
     */
    private int polygon(int cx, int cy, double r, int sides, double rotation) {
        for (int i = 0; i < sides; i++) {
            double a = rotation + i * 2 * Math.PI / sides;
            shapeXs[i] = (int) Math.round(cx + Math.cos(a) * r);
            shapeYs[i] = (int) Math.round(cy + Math.sin(a) * r);
        }
        return sides;
    }

    private int star(int cx, int cy, double r) {
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double rad = (i % 2 == 0) ? r : r * 0.45;
            shapeXs[i] = (int) Math.round(cx + Math.cos(a) * rad);
            shapeYs[i] = (int) Math.round(cy + Math.sin(a) * rad);
        }
        return 10;
    }

    /** A preview of where the held tool would land. */
    private void drawToolGhost(DrawTarget target, Dish dish) {
        if (tool == Tool.INSPECT || showShop || showHelp || paused) return;
        if (mouseY < 44 || mouseY > viewportHeight - 78) return;
        double[] w = camera.screenToWorld(mouseX, mouseY);
        boolean inside = dish.contains(w[0], w[1]);
        Color c = toolColor(tool);
        int r = switch (tool) {
            case BARRIER -> (int) (22 * camera.zoom);
            case HEAT, COLD -> (int) (130 * camera.zoom);
            case SPOTLIGHT -> (int) (170 * camera.zoom);
            case MUTAGEN -> (int) (150 * camera.zoom);
            default -> 10;
        };
        target.drawOval(mouseX - r, mouseY - r, r * 2, r * 2,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), inside ? 150 : 60));
        target.drawLine(mouseX - 7, mouseY, mouseX + 7, mouseY,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), inside ? 150 : 60));
        target.drawLine(mouseX, mouseY - 7, mouseX, mouseY + 7,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), inside ? 150 : 60));
    }

    private static Color toolColor(Tool t) {
        return switch (t) {
            case FEED_SIMPLE -> EnergyOrb.Kind.SIMPLE.color();
            case FEED_COMPLEX -> EnergyOrb.Kind.COMPLEX.color();
            case COLONY -> new Color(180, 220, 255);
            case BARRIER -> new Color(150, 158, 175);
            case HEAT -> new Color(255, 140, 80);
            case COLD -> new Color(120, 200, 255);
            case SPOTLIGHT -> new Color(255, 250, 210);
            case MUTAGEN -> new Color(200, 110, 210);
            case TOOLKIT -> new Color(235, 200, 110);
            case SPATULA -> new Color(210, 215, 230);
            case INSPECT -> TEXT;
        };
    }

    // --- HUD ---------------------------------------------------------------------------

    private void drawTopBar(DrawTarget target, Dish dish) {
        target.fillRect(0, 0, viewportWidth, 40, PANEL);
        target.drawLine(0, 40, viewportWidth, 40, PANEL_EDGE);

        target.drawText(game.credits() + " credits", 16, 26, SANS_BOLD_16, CREDIT);

        String dishes = dish.name + " (" + (game.activeDishIndex() + 1) + "/"
                + game.dishes().size() + ")";
        target.drawText(dishes, 150, 26, SANS_PLAIN_14, TEXT);
        target.drawText("pop " + dish.population() + " · " + dish.speciesCount() + " strands", 310,
                26, SANS_PLAIN_14, TEXT);
        target.drawText("this game: " + game.catalog().speciesCount() + " species · "
                + game.catalog().combinationCount() + " colonies", 470, 26, SANS_PLAIN_14, TEXT_DIM);
        target.drawText("history: " + game.history().speciesCount(), 700, 26, SANS_PLAIN_14,
                new Color(150, 170, 210));
        if (Math.abs(game.timeScale() - 1) > 0.001) {
            target.drawText(String.format("%.2f×", game.timeScale()), 810, 26, SANS_PLAIN_14, GOOD);
        }

        String hint = "B shop · K book · Tab dish · H help · Esc pause";
        target.drawText(hint, viewportWidth - target.textWidth(hint, SANS_PLAIN_14) - 16, 26,
                SANS_PLAIN_14, TEXT_DIM);
    }

    private void drawToolTray(DrawTarget target) {
        int trayH = 74;
        int y = viewportHeight - trayH;
        target.fillRect(0, y, viewportWidth, trayH, PANEL);
        target.drawLine(0, y, viewportWidth, y, PANEL_EDGE);

        toolRects.clear();
        Tool[] tools = Tool.values();
        int pad = 8;
        int w = Math.min(126, (viewportWidth - pad) / tools.length - pad);
        int x = pad;
        for (int i = 0; i < tools.length; i++) {
            Tool t = tools[i];
            Rectangle r = new Rectangle(x, y + 10, w, trayH - 22);
            toolRects.add(r);
            boolean active = t == tool;
            int count = t.item == null ? -1 : game.inventory().count(t.item);
            boolean empty = count == 0;

            target.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8,
                    active ? new Color(52, 62, 84) : new Color(30, 36, 50));
            target.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8,
                    active ? new Color(240, 210, 120) : PANEL_EDGE);

            target.fillRect(r.x + 8, r.y + 10, 10, 10, toolColor(t));

            target.drawText(clip(target, SANS_PLAIN_12, t.label, r.width - 32), r.x + 24, r.y + 20, SANS_PLAIN_12,
                    empty ? new Color(120, 100, 100) : TEXT);
            String sub = count < 0 ? "free" : ("×" + count);
            target.drawText(sub, r.x + 24, r.y + 38, SANS_PLAIN_12, empty ? WARN : TEXT_DIM);
            target.drawText(i == 0 ? "I" : String.valueOf(i % 10), r.x + r.width - 14, r.y + 38,
                    SANS_PLAIN_12, TEXT_DIM);
            x += w + pad;
        }
    }

    /**
     * The read-out for the selected cell. Without the DNA catalog scanner this
     * is deliberately a guess — colour, shape and condition, which is all a lens
     * can honestly tell you. The scanner is what turns it into an exact strand.
     */
    private void drawInspector(DrawTarget target) {
        if (selected == null) return;
        Organism o = selected;
        Phenotype p = o.pheno;
        boolean scanned = game.inventory().owns(ShopItem.DNA_SCANNER);

        int w = 306;
        int x = 14;
        int y = 54;
        int h = scanned ? 316 : 188;
        target.fillRoundRect(x, y, w, h, 10, 10, PANEL);
        target.drawRoundRect(x, y, w, h, 10, 10, PANEL_EDGE);

        int ty = y + 26;
        // Named from the permanent history: if you have ever seen this strand,
        // in any game, the scanner knows what it is.
        SpeciesRecord known = game.history().species(o.genome.sequence());
        target.drawText(scanned && known != null ? known.name : "Unidentified organism", x + 14, ty,
                SANS_BOLD_15, TEXT);
        ty += 22;

        target.drawText(p.shape().displayName() + " body · generation " + o.generation, x + 14, ty,
                SANS_PLAIN_13, TEXT_DIM);
        ty += 18;
        target.drawText(String.format("energy %.0f / %.0f · age %.0fs",
                o.energy, Organism.REPLICATE_AT, o.age), x + 14, ty, SANS_PLAIN_13, TEXT_DIM);
        ty += 18;
        target.drawText(o.colonyId >= 0 ? "bound into a colony" : "free-swimming", x + 14, ty,
                SANS_PLAIN_13, TEXT_DIM);
        ty += 22;

        target.fillRect(x + 14, ty - 10, 14, 14, p.color());
        target.drawText("expressed colour", x + 36, ty, SANS_PLAIN_13, TEXT_DIM);
        ty += 24;

        if (!scanned) {
            target.drawText("Buy the DNA catalog scanner to read", x + 14, ty, SANS_PLAIN_13,
                    new Color(200, 180, 120));
            target.drawText("this strand exactly instead of guessing.", x + 14, ty + 17,
                    SANS_PLAIN_13, new Color(200, 180, 120));
            return;
        }

        drawStrand(target, o.genome, x + 14, ty - 12, w - 28);
        ty += 20;
        target.drawText(o.genome.sequence(), x + 14, ty, MONO_PLAIN_12, TEXT);
        ty += 20;

        for (Trait t : Trait.values()) {
            double v = p.trait(t);
            if (v <= 0) continue;
            target.drawText(t.displayName(), x + 14, ty, SANS_PLAIN_12, TEXT_DIM);
            target.drawText(String.format("%.0f", v), x + w - 34, ty, SANS_PLAIN_12, GOOD);
            ty += 15;
            if (ty > y + h - 46) break;
        }
        if (!p.abilities().isEmpty() && ty < y + h - 18) {
            StringBuilder sb = new StringBuilder();
            for (Ability a : p.abilities()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.displayName().toLowerCase());
            }
            drawWrapped(target, sb.toString(), x + 14, ty + 6, w - 28, 15, 2,
                    SANS_PLAIN_12, new Color(200, 220, 255));
        }
    }

    /** A strand drawn as its actual coloured pixels — DNA you can read by eye. */
    private void drawStrand(DrawTarget target, Genome genome, int x, int y, int maxWidth) {
        int n = genome.length();
        int cell = Math.max(3, Math.min(12, maxWidth / Math.max(1, n)));
        for (int i = 1; i <= n; i++) {
            target.fillRect(x + (i - 1) * cell, y, cell - 1, 12, genome.at(i).color());
        }
    }

    private void drawToasts(DrawTarget target) {
        int y = 60;
        for (Toast t : toasts) {
            double fade = t.age > 5 ? 1 - (t.age - 5) : 1;
            int a = (int) Math.max(0, Math.min(255, 235 * fade));
            int w = target.textWidth(t.text, SANS_PLAIN_14) + 20;
            int x = viewportWidth - w - 16;
/*WAS setColor new Color(18, 22, 32, (int) (200 * fade))*/
            target.fillRoundRect(x, y - 16, w, 24, 6, 6, new Color(18, 22, 32, (int) (200 * fade)));
            target.drawText(t.text, x + 10, y, SANS_PLAIN_14,
                    new Color(t.color.getRed(), t.color.getGreen(), t.color.getBlue(), a));
            y += 28;
        }
    }

    /**
     * The shop. Rows carry the name and price only; the full description of
     * whichever row the pointer is over is written out, wrapped, in a panel at
     * the bottom. Squeezing every description into its row meant clipping them
     * at any sensible window width — this way nothing is ever cut off.
     */
    private void drawShop(DrawTarget target) {
        dim(target, 190);
        int w = Math.min(820, viewportWidth - 60);
        int h = Math.min(640, viewportHeight - 60);
        int x = (viewportWidth - w) / 2;
        int y = (viewportHeight - h) / 2;
        target.fillRoundRect(x, y, w, h, 12, 12, PANEL);
        target.drawRoundRect(x, y, w, h, 12, 12, PANEL_EDGE);

        target.drawText("Lab shop", x + 22, y + 34, SANS_BOLD_22, TEXT);
        String bal = game.credits() + " credits";
        target.drawText(bal, x + w - target.textWidth(bal, SANS_PLAIN_15) - 22, y + 34,
                SANS_PLAIN_15, CREDIT);

        int detailH = 74;
        int listBottom = y + h - detailH - 34;
        shopRects.clear();
        ShopItem[] items = ShopItem.values();
        int rowH = Math.max(24, Math.min(34, (listBottom - y - 54) / items.length));
        int ry = y + 54;
        ShopItem hovered = null;
        for (ShopItem item : items) {
            Rectangle r = new Rectangle(x + 14, ry, w - 28, rowH - 2);
            shopRects.add(r);
            boolean owned = item.permanent() && game.inventory().owns(item);
            boolean afford = game.canAfford(item) && !owned;
            boolean hover = r.contains(mouseX, mouseY);
            if (hover) hovered = item;
            int baseline = r.y + rowH * 2 / 3;

            if (hover) {
                target.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6,
                        afford ? new Color(48, 60, 84) : new Color(38, 42, 56));
            }
            target.drawText(item.displayName(), r.x + 12, baseline, SANS_PLAIN_14,
                    owned ? new Color(120, 170, 130) : (afford ? TEXT : new Color(120, 126, 142)));

            target.drawText(item.category().name().toLowerCase(), r.x + 250, baseline,
                    SANS_PLAIN_12, new Color(120, 130, 152));

            String right = owned ? "owned"
                    : (item.permanent() ? item.price() + "c" : item.price() + "c   have "
                    + game.inventory().count(item));
            target.drawText(right, r.x + r.width - target.textWidth(right, SANS_BOLD_13) - 12,
                    baseline, SANS_BOLD_13,
                    owned ? GOOD : (afford ? CREDIT : new Color(126, 108, 96)));
            ry += rowH;
        }

        // The description panel: full text, wrapped, never clipped.
        int dy = y + h - detailH - 26;
        target.fillRoundRect(x + 14, dy, w - 28, detailH, 8, 8, new Color(14, 17, 25));
        target.drawRoundRect(x + 14, dy, w - 28, detailH, 8, 8, PANEL_EDGE);
        if (hovered == null) {
            target.drawText("Point at an item to read what it does.", x + 28, dy + 26,
                    SANS_PLAIN_13, TEXT_DIM);
        } else {
            target.drawText(hovered.displayName(), x + 28, dy + 22, SANS_BOLD_13, TEXT);
            drawWrapped(target, hovered.description(), x + 28, dy + 42, w - 56, 17, 2,
                    SANS_PLAIN_13, TEXT_DIM);
        }

        drawCentered(target, "Click to buy · B or Esc to close", viewportWidth / 2, y + h - 10,
                SANS_PLAIN_13, TEXT_DIM);
    }

    private void drawHelp(DrawTarget target) {
        dim(target, 205);
        int w = Math.min(820, viewportWidth - 80);
        int h = Math.min(560, viewportHeight - 80);
        int x = (viewportWidth - w) / 2;
        int y = (viewportHeight - h) / 2;
        target.fillRoundRect(x, y, w, h, 12, 12, PANEL);
        target.drawRoundRect(x, y, w, h, 12, 12, PANEL_EDGE);

        target.drawText("How the dish works", x + 22, y + 36, SANS_BOLD_22, TEXT);

        String[] lines = {
                "Every organism is a strand of coloured DNA, and the strand is the whole animal.",
                "",
                "RED encodes hostility.  BLUE encodes altruism.  GREEN is a wild card whose meaning",
                "depends on the slot it sits in: a green in slot 3 is light emission of 3, a green in",
                "slot 1 is speed, slot 5 is digestion, and the wheel repeats every eight slots — so a",
                "longer strand reaches the same traits at higher magnitudes.",
                "",
                "The nucleotide AFTER a green modifies it: red doubles it, blue halves it but refines",
                "digestion, green adds one and chains on.  Every adjacent PAIR also unlocks an ability:",
                "RR predation · RG exothermy · RB venom · GG complex digestion · GB endothermy ·",
                "BR kin defence · BG broadcast · BB sharing.  Tool use and multicellularity need long",
                "strands (GRG late on; BBB in a strand of 14+).",
                "",
                "Cells eat, burn energy, and divide when they have banked enough — copying their DNA",
                "imperfectly.  Hunger, crowding, temperature and each other do the selecting.",
                "",
                "You earn credit for every strand that has never existed before, and for every new",
                "colony combination.  The more complex the discovery, the bigger the payment.",
                "",
                "Tools: 1-9 and 0 pick one, I inspects.  Left-click uses it, right-drag pans, wheel",
                "zooms.  B shop · K reference book · Tab next dish · T thermometer · [ ] time warp.",
        };
        int ly = y + 66;
        for (String line : lines) {
            target.drawText(line, x + 22, ly, SANS_PLAIN_13, line.startsWith("RED") || line.startsWith("The nucleotide")
                    ? new Color(210, 220, 240) : TEXT_DIM);
            ly += 18;
        }
        drawCentered(target, "H or Esc to close", viewportWidth / 2, y + h - 16,
                SANS_PLAIN_13, TEXT_DIM);
    }

    private void drawGameOver(DrawTarget target) {
        dim(target, 150);
        drawCentered(target, "Every dish has run out of life", viewportWidth / 2,
                viewportHeight / 2 - 24, SANS_BOLD_34, WARN);
        drawCentered(target, game.catalog().speciesCount() + " species and "
                        + game.catalog().combinationCount()
                        + " colony combinations found in this game",
                viewportWidth / 2, viewportHeight / 2 + 8, SANS_PLAIN_16, TEXT_DIM);
        drawCentered(target, "Your history keeps all " + game.history().speciesCount()
                        + " organisms you have ever discovered",
                viewportWidth / 2, viewportHeight / 2 + 34, SANS_PLAIN_16, TEXT_DIM);
        drawCentered(target, "Esc to pause — \"Reset the lab\" starts a fresh one whenever you like",
                viewportWidth / 2, viewportHeight / 2 + 60, SANS_PLAIN_16, TEXT_DIM);
    }

    // --- small drawing helpers ------------------------------------------------------------

    private void dim(DrawTarget target, int alpha) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(6, 8, 12, alpha));
    }

    /** The two-stop halo every glow in the gel is made of. */
    private static final float[] HALO_STOPS = {0f, 1f};

    private void paintRadial(DrawTarget target, int cx, int cy, int r, Color inner, Color outer) {
        if (r < 2) return;
        target.fillRadialGradient(cx, cy, r, HALO_STOPS,
                new int[]{inner.getRGB(), outer.getRGB()});
    }

    private void drawWrapped(DrawTarget target, String text, int x, int y, int width,
                             int lineHeight, int maxLines, Font font, Color color) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (target.textWidth(candidate, font) > width && line.length() > 0) {
                target.drawText(line.toString(), x, y + lines * lineHeight, font, color);
                if (++lines >= maxLines) return;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            target.drawText(line.toString(), x, y + lines * lineHeight, font, color);
        }
    }

    /** Trim a string to fit {@code width}, adding an ellipsis when it does not. */
    private static String clip(DrawTarget target, Font font, String s, int width) {
        if (target.textWidth(s, font) <= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 1 && target.textWidth(sb + "…", font) > width) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }

    private void drawCentered(DrawTarget target, String s, int cx, int cy,
                              Font font, Color color) {
        target.drawText(s, cx - target.textWidth(s, font) / 2, cy, font, color);
    }

    private static Color blend(Color a, Color b, double t) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
