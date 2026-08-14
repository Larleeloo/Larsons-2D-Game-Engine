package com.larsons.engine.render;

import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.autobattler.Element;
import com.larsons.engine.character.CharacterPicker;
import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.crafting.RecipeRegistry;
import com.larsons.engine.demo.MiniGameHudAccess;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.DecorPainter;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.SpriteCanvas;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.profile.ProfileOverlay;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.ContainerPanel;
import com.larsons.engine.ui.CraftingPanel;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.SpriteEditorPanel;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.SurfaceDecor;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A fixed catalogue of pictures the engine can draw, rendered from fixed
 * inputs so that "did this change what the player sees?" has a mechanical
 * answer.
 *
 * <p><b>Why this exists, and why it exists now.</b> Job B ports roughly forty
 * files from {@code Graphics2D} to {@link com.larsons.engine.graphics.draw.DrawTarget}.
 * Every one of those ports is claimed to be a pure translation, and every one
 * of them is a chance to move something by a pixel, drop a colour's alpha, or
 * reorder two draws that happen to overlap. Reviewing that by eye across forty
 * files does not work. Rendering the same scene before and after and
 * subtracting does.
 *
 * <p><b>Everything here is fixed on purpose.</b> Size, seed, clock, level
 * contents, window geometry. A golden that flaps is worse than no golden: it
 * gets regenerated on the first failure and then it is a picture of the bug.
 * The specific hazards, all of which bit during authoring:
 * <ul>
 *   <li>{@code Particles} seeds its RNG from the system clock, so the goldens
 *       drive it through {@link Particles#Particles(long)} instead.</li>
 *   <li>{@code TerrainCache} quantises liquid animation to 12 fps, so the
 *       clock used here ({@link #CLOCK}) lands exactly on a frame boundary
 *       rather than between two.</li>
 *   <li>Sprite factories cache by key, so a frame that draws a sprite must
 *       draw the same sprite whichever order the catalogue runs in.</li>
 * </ul>
 *
 * <p><b>Fonts are the one thing that cannot be fixed from in here.</b> Glyph
 * rasterisation is a property of the JDK and the host's font configuration, so
 * a golden generated on one machine will not match to 0.00 on another with a
 * different font stack. Rather than loosen the bar to a tolerance that would
 * also swallow real regressions, the goldens carry a
 * {@link #fontFingerprint() fingerprint} of the fonts they were drawn with,
 * and the test skips — loudly — when the fingerprint does not match. That
 * follows the convention the suite already uses for its three display-
 * dependent tests: skip by design rather than fail by accident.
 */
public final class GoldenFrames {

    private GoldenFrames() {}

    /** Where references live, relative to the project root. */
    public static final String RESOURCE_DIR = "src/test/resources/golden";

    /**
     * The animation clock every frame is drawn at.
     *
     * <p>A multiple of 1/12 s: {@code TerrainCache.ANIM_FPS} quantises liquid
     * animation to twelve frames a second, and a clock landing between two of
     * those would put the goldens on whichever side floating-point rounding
     * happened to fall — the definition of a flapping test.
     */
    public static final double CLOCK = 24.0 / 12.0;

    /** Seed for everything procedural, so the same art is generated every run. */
    public static final long SEED = 0x9E3779B97F4A7C15L;

    /** The colour the surface starts as, matching {@code Java2DRenderer}'s clear. */
    private static final Color CLEAR = new Color(24, 28, 38);

    /** One named picture, drawn at a fixed size from fixed inputs. */
    public record Frame(String name, int width, int height, Painter painter) {}

    /**
     * What a frame does with the target it is handed.
     *
     * <p>{@link DrawTarget}, not {@link Java2DTarget}: as of B2 every painter
     * in this catalogue draws through the seam and none of them reaches for a
     * {@code Graphics2D}. That makes the catalogue itself backend-neutral —
     * the same scenes can be recorded through a {@code RecordingTarget} to
     * count draw calls, and rendered through a {@code GlTarget} in B8 to
     * compare pixels. That comparison is the reason this interface is worth
     * widening now rather than when B8 needs it.
     */
    @FunctionalInterface
    public interface Painter {
        void paint(DrawTarget target);
    }

    /**
     * Render one frame exactly as the shipping renderer would.
     *
     * <p>The two rendering hints are not decoration: {@code Java2DRenderer}
     * sets antialiasing on and interpolation to nearest-neighbour on every
     * frame it composes, so a golden drawn without them would be a picture of
     * something the game never shows. Several painters set the antialiasing
     * hint themselves today and will stop when they are ported — setting it
     * here is what makes that a no-op rather than a regression.
     */
    public static BufferedImage render(Frame frame) {
        BufferedImage image = new BufferedImage(
                frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(CLEAR);
            g.fillRect(0, 0, frame.width(), frame.height());
            frame.painter().paint(new Java2DTarget(g, frame.width(), frame.height()));
        } finally {
            g.dispose();
        }
        return image;
    }

    // --- the catalogue ---------------------------------------------------------

    /**
     * Every golden frame. Ordered world-first so a failure list reads as
     * "the world moved" or "a widget moved" at a glance.
     */
    public static List<Frame> all() {
        List<Frame> frames = new ArrayList<>();

        // The three level formats, each with terrain, liquid, decor, surface
        // decor and an entity — the four things B3's scene ports move.
        frames.add(new Frame("world-side-scroll", 480, 320,
                t -> paintWorld(t, LevelFormat.SIDE_SCROLLER)));
        frames.add(new Frame("world-top-down", 480, 320,
                t -> paintWorld(t, LevelFormat.TOP_DOWN)));
        frames.add(new Frame("world-isometric", 480, 320,
                t -> paintWorld(t, LevelFormat.ISOMETRIC)));
        frames.add(new Frame("world-crowd", 480, 320, GoldenFrames::paintCrowd));

        // B2, in the order B2 ports them.
        frames.add(new Frame("parallax-background", 800, 600, GoldenFrames::paintParallax));
        frames.add(new Frame("cutscene", 480, 320, GoldenFrames::paintCutscene));
        frames.add(new Frame("crafting-panel", 640, 400, GoldenFrames::paintCraftingPanel));
        frames.add(new Frame("character-picker", 640, 400, GoldenFrames::paintCharacterPicker));
        frames.add(new Frame("main-menu", 640, 400, GoldenFrames::paintMenu));
        frames.add(new Frame("auto-sprites", 320, 200, GoldenFrames::paintAutoSprites));
        frames.add(new Frame("container-panel", 960, 420, GoldenFrames::paintContainerPanel));
        frames.add(new Frame("particles", 480, 320, GoldenFrames::paintParticles));
        frames.add(new Frame("sprite-editor", 800, 520, GoldenFrames::paintSpriteEditor));
        frames.add(new Frame("config-form", 640, 400, GoldenFrames::paintConfigForm));
        frames.add(new Frame("profile-overlay", 400, 400, GoldenFrames::paintProfileOverlay));
        frames.add(new Frame("minigame-hud", 480, 320, GoldenFrames::paintMiniGameHud));

        return frames;
    }

    // --- world -----------------------------------------------------------------

    /**
     * A level with something of everything the world painters draw: solid
     * ground, a stacked wall, a liquid pool, a hole, background and foreground
     * decor, surface decor on an open face, and a mob standing on the floor.
     */
    private static Level worldLevel(LevelFormat format) {
        Level level = Level.empty("golden", 14, 10, 32);
        level.setFormat(format);
        level.background = new Color(30, 38, 54);

        int dirt = level.blocks.get("dirt").id();
        int grass = level.blocks.get("grass").id();
        int stone = level.blocks.get("stone").id();
        int water = level.blocks.get("water").id();

        for (int row = 5; row < level.height; row++) {
            for (int col = 0; col < level.width; col++) {
                level.setTile(col, row, row == 5 ? grass : dirt);
            }
        }
        // A hole, so the "bare ground" case is drawn too.
        level.setTile(6, 5, 0);
        level.setTile(6, 6, 0);
        // A pool, so liquid surfaces and their animation are drawn.
        level.setTile(9, 5, water);
        level.setTile(10, 5, water);
        // A stacked wall, so the layered formats lift something and cast a
        // shadow — the only draw in the frame that is a filled Shape.
        level.setTile(3, 6, Level.LAYER_UPPER, stone);
        level.setTile(4, 6, Level.LAYER_UPPER, stone);

        level.entities.add(new Level.EntitySpawn(
                DecorPainter.BACKGROUND_KIND, "pine_tree", 2 * 32, 5 * 32));
        level.entities.add(new Level.EntitySpawn(
                DecorPainter.FOREGROUND_KIND, "bush", 12 * 32, 5 * 32));
        level.surfaceDecor.add(new SurfaceDecor.Placement(
                7, 5, SurfaceDecor.Face.UP, "grass_tuft", false,
                SurfaceDecor.Visibility.OPEN_FACE));

        return level;
    }

    /** The world phases in the order {@code PlayScene} runs them. */
    private static void paintWorld(DrawTarget target, LevelFormat format) {
        Level level = worldLevel(format);
        Camera camera = new Camera(level.perspective, target.width(), target.height());
        camera.tileSize = level.tileSize;
        camera.centerOn(level.width * 32 / 2.0, level.height * 32 / 2.0);

        target.clear(level.background.getRGB());

        int[] bounds = {0, 0, level.width - 1, level.height - 1};
        DepthPass raised = DepthPass.of(level.perspective);

        DecorPainter.draw(target, level, camera, false, CLOCK, raised);
        SurfaceDecorPainter.draw(target, level, camera, bounds, false, CLOCK, raised);
        TerrainPainter.draw(target, level, camera, bounds, CLOCK, raised, null);

        // One mob, drawn the way the entity phase draws one: a baked sprite
        // blitted at the projected foot position.
        BufferedImage mob = EntitySprites.mob(
                MobRegistry.standard().get("slime"), 28, Facing.SOUTH_EAST);
        int[] at = new int[2];
        camera.worldToScreen(7 * 32.0, 5 * 32.0, at);
        int depth = at[1];
        raised.at(depth, () -> target.drawImage(mob, at[0] - 14, at[1] - 28, 28, 28));

        SurfaceDecorPainter.draw(target, level, camera, bounds, true, CLOCK, raised);
        DecorPainter.draw(target, level, camera, true, CLOCK, raised);
        raised.flush();
    }

    /**
     * The entity phase at a density the shipped sample level never reaches:
     * thirty mobs, eight dropped items and six projectiles over a top-down
     * floor.
     *
     * <p><b>Why the catalogue needed this.</b> B5's verification asks for the
     * merge ratio "on the same recorded frame from a busy level", and until now
     * the busiest frame in the catalogue drew <em>one</em> mob. Entities are
     * 3.85 ms of the M1 Air's 11.49 ms scene stage — the largest single thing
     * B5 is aimed at — and a measurement taken on a frame with three sprites in
     * it cannot say anything about them. This is the frame where the number
     * means something.
     *
     * <p><b>And why it is not a benchmark written to flatter the atlas.</b> It
     * draws each entity the way {@code PlayScene} draws one, interleaving
     * included: a healthy mob is a single sprite, a hurt one is a sprite
     * followed by a tint and two health-bar rectangles, and a dropped item is a
     * shadow oval and a rarity gradient <em>before</em> its sprite. Those breaks
     * are what a real crowd looks like to a batcher, and leaving them out would
     * have produced a bigger number and a false one. Draws go through a
     * {@link DepthPass} for the same reason: the engine sorts entity sprites by
     * depth before it emits them, so the order measured here is the order the
     * backend really sees.
     */
    private static void paintCrowd(DrawTarget target) {
        Level level = Level.empty("crowd", 15, 10, 32);
        level.setFormat(LevelFormat.TOP_DOWN);
        level.background = new Color(30, 38, 54);
        int grass = level.blocks.get("grass").id();
        for (int row = 0; row < level.height; row++) {
            for (int col = 0; col < level.width; col++) level.setTile(col, row, grass);
        }

        Camera camera = new Camera(level.perspective, target.width(), target.height());
        camera.tileSize = level.tileSize;
        camera.centerOn(level.width * 32 / 2.0, level.height * 32 / 2.0);

        target.clear(level.background.getRGB());
        int[] bounds = {0, 0, level.width - 1, level.height - 1};
        DepthPass raised = DepthPass.of(level.perspective);
        TerrainPainter.draw(target, level, camera, bounds, CLOCK, raised, null);

        List<MobDef> mobs = MobRegistry.standard().all();
        List<ItemDef> items = ItemRegistry.standard().all();
        List<ProjectileDef> shots = ProjectileRegistry.standard().all();
        Facing[] facings = Facing.values();
        int[] at = new int[2];

        // Thirty mobs on a lattice, cycling kind and facing so consecutive
        // sprites are genuinely different textures — which is the case the
        // atlas exists for, and the case a row of identical slimes would hide.
        for (int i = 0; i < 30; i++) {
            MobDef def = mobs.get(i % mobs.size());
            Facing facing = facings[(i * 3) % facings.length];
            BufferedImage sprite = EntitySprites.mob(def, 32, facing);
            double wx = (1 + (i % 10) * 1.35) * 32;
            double wy = (1.5 + (i / 10) * 2.4) * 32;
            camera.worldToScreen(wx, wy, at);
            int x = at[0], y = at[1];
            boolean hurt = i % 7 == 3;      // a few, as in any real fight
            raised.at(y, () -> {
                target.drawImage(sprite, x - 14, y - 28, 28, 28);
                if (hurt) {
                    target.fillRect(x - 14, y - 28, 28, 28, 0x40FF3020);
                    target.fillRect(x - 14, y - 35, 28, 4, 0xC0201820);
                    target.fillRect(x - 14, y - 35, 17, 4, 0xC030C040);
                }
            });
        }

        // Drops, with the shadow and rarity halo the real one draws first.
        for (int i = 0; i < 8; i++) {
            // A wide stride through the registry rather than its first eight,
            // which are all block swatches: eight near-identical grey squares
            // would golden a picture in which a swapped sprite is invisible.
            ItemDef def = items.get((i * 13) % items.size());
            BufferedImage sprite = EntitySprites.item(def, 16);
            camera.worldToScreen((2 + i * 1.6) * 32, 8.2 * 32, at);
            int x = at[0], y = at[1];
            Color rarity = def.rarity().color;
            raised.at(y, () -> {
                target.fillOval(x, y + 12, 16, 8, 0x50000000);
                target.fillRadialGradient(x + 8, y + 8, 18, HALO_STOPS, new int[]{
                        new Color(rarity.getRed(), rarity.getGreen(), rarity.getBlue(), 110).getRGB(),
                        new Color(rarity.getRed(), rarity.getGreen(), rarity.getBlue(), 46).getRGB(),
                        new Color(rarity.getRed(), rarity.getGreen(), rarity.getBlue(), 0).getRGB()});
                target.drawImage(sprite, x, y, 16, 16);
            });
        }

        // Projectiles in flight — sprites with nothing between them.
        for (int i = 0; i < 6; i++) {
            BufferedImage sprite = EntitySprites.projectile(shots.get(i % shots.size()), 12);
            camera.worldToScreen((3 + i * 1.9) * 32, 3.1 * 32, at);
            int x = at[0], y = at[1];
            raised.at(y, () -> target.drawImage(sprite, x, y, 12, 12));
        }

        raised.flush();
    }

    /** The rarity halo's stops, matching {@code PlayScene.HALO_STOPS}. */
    private static final float[] HALO_STOPS = {0f, 0.55f, 1f};

    // --- shared painters and widgets ------------------------------------------

    /**
     * The backdrop at a viewport tall enough to show a ridgeline.
     *
     * <p>The layers' {@code baseY} offsets are measured up from the bottom and
     * go as high as 330, so below about 600px the nearest ridge sits entirely
     * above the top edge and fills the whole frame — a golden of one flat
     * colour, which would pass for ever and prove nothing. The camera offset is
     * a non-multiple of the 1024px layer width on purpose, so the horizontal
     * tiling seam is inside the frame and a change to the wrap arithmetic shows.
     */
    private static void paintParallax(DrawTarget target) {
        ParallaxBackground bg = new ParallaxBackground(new Color(46, 62, 92), SEED);
        target.clear(bg.background().getRGB());
        bg.render(target, 6210, 400, target.width(), target.height());
    }

    private static void paintCutscene(DrawTarget target) {
        // No sprite sheets ship with the engine, so both actors resolve to the
        // procedural placeholder figure — which is exactly the path a player
        // without art sees, and the one worth goldening.
        Cutscene cutscene = new Cutscene("arrival", "Arrival");
        cutscene.actors.add(new Cutscene.Actor("hero", "Wren", 48));
        cutscene.actors.add(new Cutscene.Actor("stranger", "The Stranger", 48));
        cutscene.steps.add(new Cutscene.Step(
                Cutscene.Op.SHOW, "hero", "idle", 180, 200, 0));
        cutscene.steps.add(new Cutscene.Step(
                Cutscene.Op.SHOW, "stranger", "idle", 300, 200, 0));
        cutscene.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "hero",
                "We should not have come this far north with the winter turning.",
                0, 0, 4));

        CutscenePlayer player = new CutscenePlayer(cutscene, 240, 160);
        player.update(0.5);   // past the bar ease-in, inside the first caption

        Camera camera = new Camera(
                com.larsons.engine.graphics.Perspective.SIDE_SCROLL,
                target.width(), target.height());
        camera.centerOn(240, 160);

        CutscenePainter.drawActors(target, camera, player);
        CutscenePainter.drawOverlay(target, target.width(), target.height(), player);
    }

    private static void paintCraftingPanel(DrawTarget target) {
        ItemRegistry items = ItemRegistry.standard();
        Inventory inv = new Inventory(items);
        inv.add("wood", 12);
        inv.add("stone", 4);
        CraftingPanel panel = new CraftingPanel(
                com.larsons.engine.crafting.Recipe.STATION_CRAFTING,
                RecipeRegistry.standard(), items);
        panel.render(target, target.width(), target.height(), inv, CLOCK);
    }

    private static void paintCharacterPicker(DrawTarget target) {
        List<CharacterProfile> roster = List.of(
                new CharacterProfile("scout", "Scout"),
                new CharacterProfile("warden", "Warden"),
                new CharacterProfile("alchemist", "Alchemist"));
        CharacterPicker picker = new CharacterPicker(roster, "Frostmarch", "warden");
        picker.render(target, target.width(), target.height());
    }

    private static void paintMenu(DrawTarget target) {
        Menu menu = new Menu("Larsons Engine")
                .subtitle("a rather long subtitle that has to be shortened to fit")
                .add("Play", () -> {})
                .add("Create", () -> {})
                .add("Multiplayer", () -> {})
                .add("Settings", () -> {})
                .add("Quit", () -> {});
        menu.render(target, target.width(), target.height());
    }

    private static void paintAutoSprites(DrawTarget target) {
        // The baked unit art is most of what AutoSprites is, so the frame draws
        // some of it rather than goldening the two overlay helpers alone.
        int x = 12;
        // The first three of the roster, by its own order — naming keys here
        // would make the golden fail the day a unit is renamed, which is not a
        // rendering regression.
        for (var def : AutoUnits.roster().subList(0, 3)) {
            target.drawImage(AutoSprites.unit(def, 56, true), x, 16);
            target.drawImage(AutoSprites.unit(def, 56, false), x, 84);
            x += 64;
        }

        AutoSprites.drawElementPips(target,
                List.of(Element.values()[0], Element.values()[1], Element.values()[2]),
                240, 30, 14);
        AutoSprites.drawStars(target, 3, 240, 90, 20);
        AutoSprites.drawStars(target, 2, 240, 140, 14);
    }

    private static void paintContainerPanel(DrawTarget target) {
        ItemRegistry items = ItemRegistry.standard();
        Level level = Level.empty("golden", 8, 8, 32);
        level.setTile(2, 2, level.blocks.get("chest").id());
        List<ItemStack> box = level.openContainer(2, 2);
        box.add(new ItemStack("wood", 24));
        box.add(new ItemStack("stone", 7));
        box.add(new ItemStack("iron_ore", 1));

        ContainerPanel panel = new ContainerPanel(level, 2, 2, "Chest", items);
        // A panel starts in OPENING at zero openness and draws nothing at all;
        // goldening that would golden a blank screen. Run it past OPEN_TIME so
        // the reference is the panel as a player sees it.
        panel.tick(0.5);
        panel.render(target, target.width(), target.height(), CLOCK);
    }

    private static void paintParticles(DrawTarget target) {
        // Seeded, or every run is a different picture. This is the reason
        // Particles gained a seeded constructor.
        Particles fx = new Particles(SEED);
        fx.burst(120, 120, new Color(255, 200, 90), 24, Particles.Style.EMBERS);
        fx.burst(300, 180, new Color(120, 200, 255), 20, Particles.Style.SHARDS);
        fx.burst(220, 240, new Color(200, 120, 255), 18, Particles.Style.RING);
        for (int i = 0; i < 12; i++) fx.update(1.0 / 60.0);

        Camera camera = new Camera(
                com.larsons.engine.graphics.Perspective.SIDE_SCROLL,
                target.width(), target.height());
        camera.centerOn(target.width() / 2.0, target.height() / 2.0);
        fx.render(target, camera);
    }

    private static void paintSpriteEditor(DrawTarget target) {
        SpriteCanvas canvas = new SpriteCanvas(16, 16);
        // A recognisable pattern, so a pixel that moves is visible rather than
        // hidden in an empty canvas.
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (((x / 2) + (y / 2)) % 2 == 0) {
                    canvas.set(x, y, new Color(200, 90 + x * 8, 60 + y * 8).getRGB());
                }
            }
        }
        SpriteEditorPanel panel = new SpriteEditorPanel(
                "Sprite Editor", "sprites/hero.png", canvas);
        panel.render(target, target.width(), target.height());
    }

    private static void paintConfigForm(DrawTarget target) {
        boolean[] fullscreen = {true};
        int[] volume = {70};
        double[] gamma = {1.15};
        String[] name = {"levels/frostmarch.json"};

        ConfigForm form = new ConfigForm("Settings");
        form.addNote("Changes apply immediately and are saved on exit.");
        form.addToggle("Fullscreen", () -> fullscreen[0], v -> fullscreen[0] = v);
        form.addSlider("Master volume", () -> volume[0], v -> volume[0] = v, 0, 100);
        form.addDouble("Gamma", () -> gamma[0], v -> gamma[0] = v, 0.5, 2.0, 0.05);
        form.addText("Level path", () -> name[0], v -> name[0] = v, 64);
        form.addAction("Reset to defaults", () -> {});
        form.render(target, target.width(), target.height());
    }

    /**
     * A frame profile with every branch of the overlay populated: all six
     * stages, a shader-pass list, and a draw-call line.
     *
     * <p>Built as a literal rather than by driving a real {@link FrameProfiler}.
     * The profiler measures {@code System.nanoTime()}, so feeding it fabricated
     * start times still produces a different picture every run — the
     * determinism check caught exactly that, which is what it is for. What the
     * overlay is responsible for is turning a snapshot into pixels, so the
     * snapshot is the fixed input and the profiler is not in the picture.
     *
     * <p>{@link DeviceProfile} is fixed for the same reason one step further
     * out: the overlay prints the machine's core count, so
     * {@code DeviceProfile.detect()} would golden the build agent.
     */
    private static void paintProfileOverlay(DrawTarget target) {
        double[] mean = {2.11, 11.49, 1.04, 3.71, 0.18, 0.00};
        double[] p99 = {15.27, 14.02, 2.20, 9.74, 0.51, 0.00};
        List<FrameProfiler.Stats> stages = new ArrayList<>();
        for (FrameProfiler.Stage stage : FrameProfiler.Stage.values()) {
            int i = stage.ordinal();
            stages.add(new FrameProfiler.Stats(stage.label(), 600,
                    mean[i], mean[i], p99[i] * 0.9, p99[i], p99[i] * 1.1));
        }
        FrameProfiler.Snapshot snapshot = new FrameProfiler.Snapshot(
                600, 3600, 60, 1000.0 / 60, stages,
                List.of(new FrameProfiler.Stats("bloom", 600, 0.62, 0.60, 0.80, 0.91, 1.02),
                        new FrameProfiler.Stats("vignette", 600, 0.14, 0.13, 0.19, 0.22, 0.30)),
                List.of(),
                new FrameProfiler.Draws(4820, 611, 1240, 380), false,
                new FrameProfiler.Steps(2.0, 2, 4, 8));

        DeviceProfile device = new DeviceProfile("Mac OS X", "14.4", "aarch64", 8,
                4096, "21.0.10", "Eclipse Adoptium", "Metal", 2560, 1600, 60, 2.0);

        ProfileOverlay.draw(target, snapshot, device, 58.0);
    }

    private static void paintMiniGameHud(DrawTarget target) {
        Level level = Level.empty("golden", 12, 9, 32);
        Block grass = level.blocks.get("grass");
        for (int col = 0; col < level.width; col++) {
            for (int row = 5; row < level.height; row++) level.setTile(col, row, grass.id());
        }
        Camera camera = new Camera(level.perspective, target.width(), target.height());
        camera.tileSize = level.tileSize;
        camera.centerOn(level.width * 32 / 2.0, level.height * 32 / 2.0);

        MiniGameView view = new MiniGameView();
        view.mode = MiniGameConfig.Mode.CTF;
        view.teams = 2;
        view.scoreLimit = 3;
        view.scores = new int[]{2, 1};
        view.timeLeft = 74.5;
        view.teamOf.put(0, 0);
        view.flags.add(new MiniGameView.FlagView(0, 0, 2 * 32, 5 * 32, -1));
        view.flags.add(new MiniGameView.FlagView(1, 1, 9 * 32, 5 * 32, 0));

        MiniGameHudAccess.drawWorld(target, camera, level, view, CLOCK);
        MiniGameHudAccess.drawHud(target, target.width(), target.height(), view, 0);
    }

    /**
     * The same frame recorded rather than rasterised, so its draw calls can be
     * counted.
     *
     * <p>This is the instrument the whole GPU case rests on: a frame of ten
     * thousand operations that collapse into thirty batches is a frame a GL
     * backend transforms, and one that collapses into eight thousand is a
     * frame that needs its art atlasing first (B5). Having it here, over the
     * same fixed scenes as the pixel comparison, means the before-and-after
     * numbers B5 has to publish come from scenes that cannot drift between the
     * two measurements.
     */
    public static RecordingTarget record(Frame frame) {
        RecordingTarget target = new RecordingTarget(frame.width(), frame.height());
        frame.painter().paint(target);
        return target;
    }

    // --- environment fingerprint ----------------------------------------------

    /**
     * A digest of how this JVM rasterises the fonts the goldens use.
     *
     * <p>Two machines that agree on this will agree on the goldens; two that
     * do not never will, however correct both are. Recording it beside the
     * PNGs is what lets the comparison assert exact equality on the machine
     * that generated them and skip honestly everywhere else, instead of
     * splitting the difference with a tolerance that would also hide a real
     * one-pixel shift.
     */
    public static String fontFingerprint() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        StringBuilder sb = new StringBuilder();
        try {
            for (String family : new String[]{"SansSerif", Font.MONOSPACED, "Serif"}) {
                for (int style : new int[]{Font.PLAIN, Font.BOLD}) {
                    for (int size : new int[]{11, 13, 16, 26}) {
                        java.awt.FontMetrics fm =
                                g.getFontMetrics(new Font(family, style, size));
                        sb.append(fm.stringWidth(
                                        "The quick brown fox 0123456789 →×·…"))
                                .append(':').append(fm.getAscent())
                                .append(':').append(fm.getHeight()).append(' ');
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
