package com.larsons.engine.input;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a player can rebind, engine-wide.
 *
 * <p>Gameplay code never names a key: it asks {@link KeyBinds} whether an
 * action is down, and the controls menu decides what "down" means. Adding an
 * action here — with the keys it ships bound to — is all it takes for it to
 * appear in that menu, save with the rest, and be reassignable to any key or
 * mouse button.
 *
 * <p>Actions carry a {@link Category}, which is both how the controls menu is
 * grouped and the scope conflicts are reported in: the same button doing two
 * things in two different contexts is normal (the left mouse button attacks
 * while playing and paints while editing), while two actions in one context
 * fighting over a button is the mistake worth warning about.
 */
public enum GameAction {

    // --- movement (also the creative editor's camera pan) ------------------------
    MOVE_LEFT("move_left", "Move / Pan Left", Category.MOVEMENT,
            InputBinding.key(KeyEvent.VK_A), InputBinding.key(KeyEvent.VK_LEFT)),
    MOVE_RIGHT("move_right", "Move / Pan Right", Category.MOVEMENT,
            InputBinding.key(KeyEvent.VK_D), InputBinding.key(KeyEvent.VK_RIGHT)),
    MOVE_UP("move_up", "Move / Pan Up", Category.MOVEMENT,
            InputBinding.key(KeyEvent.VK_W), InputBinding.key(KeyEvent.VK_UP)),
    MOVE_DOWN("move_down", "Move / Pan Down", Category.MOVEMENT,
            InputBinding.key(KeyEvent.VK_S), InputBinding.key(KeyEvent.VK_DOWN)),
    JUMP("jump", "Jump", Category.MOVEMENT, InputBinding.key(KeyEvent.VK_SPACE)),
    SPRINT("sprint", "Sprint", Category.MOVEMENT, InputBinding.key(KeyEvent.VK_SHIFT)),

    // --- combat ------------------------------------------------------------------
    ATTACK("attack", "Attack / Mine / Shoot", Category.COMBAT,
            InputBinding.mouse(MouseEvent.BUTTON1)),
    PLACE("place", "Place Block", Category.COMBAT,
            InputBinding.mouse(MouseEvent.BUTTON3)),
    ULTIMATE("ultimate", "Ultimate Ability", Category.COMBAT,
            InputBinding.key(KeyEvent.VK_R)),
    GUARD("guard", "Raise Guard", Category.COMBAT, InputBinding.key(KeyEvent.VK_C)),
    PARRY("parry", "Parry", Category.COMBAT, InputBinding.key(KeyEvent.VK_V)),
    LUNGE("lunge", "Lunge", Category.COMBAT, InputBinding.key(KeyEvent.VK_X)),
    DASH("dash", "Dash", Category.COMBAT, InputBinding.key(KeyEvent.VK_Z)),

    // --- items & interaction ------------------------------------------------------
    INTERACT("interact", "Interact (doors, chests, mounts)", Category.ITEMS,
            InputBinding.key(KeyEvent.VK_E)),
    INVENTORY("inventory", "Open Inventory", Category.ITEMS,
            InputBinding.key(KeyEvent.VK_I)),
    DROP_ITEM("drop_item", "Drop One Item", Category.ITEMS, InputBinding.key(KeyEvent.VK_Q)),
    USE_ITEM("use_item", "Use Held Item", Category.ITEMS, InputBinding.key(KeyEvent.VK_F)),
    HOTBAR_1("hotbar_1", "Hotbar Slot 1", Category.ITEMS, InputBinding.key(KeyEvent.VK_1)),
    HOTBAR_2("hotbar_2", "Hotbar Slot 2", Category.ITEMS, InputBinding.key(KeyEvent.VK_2)),
    HOTBAR_3("hotbar_3", "Hotbar Slot 3", Category.ITEMS, InputBinding.key(KeyEvent.VK_3)),
    HOTBAR_4("hotbar_4", "Hotbar Slot 4", Category.ITEMS, InputBinding.key(KeyEvent.VK_4)),
    HOTBAR_5("hotbar_5", "Hotbar Slot 5", Category.ITEMS, InputBinding.key(KeyEvent.VK_5)),

    // --- camera --------------------------------------------------------------------
    ZOOM_IN("zoom_in", "Zoom In", Category.CAMERA, InputBinding.key(KeyEvent.VK_EQUALS)),
    ZOOM_OUT("zoom_out", "Zoom Out", Category.CAMERA, InputBinding.key(KeyEvent.VK_MINUS)),
    /*
     * The eight-point camera's two keys. RENDER_PLAN C8 suggested Q and E and
     * both were already taken — Q drops one of the held stack and E interacts
     * with doors, chests and mounts — which is what asking the enum rather than
     * the plan is for. Comma and period are free, sit beside each other under
     * the right hand, and are what Don't Starve binds camera rotation to, which
     * is the game §6.1 describes the feature from.
     *
     * A side-scroller ignores both: Camera.turn does nothing where the
     * projection has no vertical axis to turn around, so the keys are harmless
     * rather than hidden, and a player who rebinds them keeps the binding when
     * they walk through a door into a plan-view level.
     */
    ROTATE_LEFT("rotate_left", "Rotate Camera Left", Category.CAMERA,
            InputBinding.key(KeyEvent.VK_COMMA)),
    ROTATE_RIGHT("rotate_right", "Rotate Camera Right", Category.CAMERA,
            InputBinding.key(KeyEvent.VK_PERIOD)),
    /*
     * The view cycle: plan view, first person, third person behind, third
     * person in front. F5 because that is the key every player of a 3D game
     * already has in their fingers for exactly this, and because — unlike the
     * two above — nothing in this engine had claimed it.
     *
     * A side-scroller cycles nothing: its screen *is* the vertical plane, so
     * there is no third axis to stand an eye in (Viewpoint.availableIn). The
     * key is harmless there rather than hidden, on the same grounds the rotate
     * keys are: a player who rebinds it keeps the binding when they walk
     * through a door into a level that does have a height axis.
     */
    TOGGLE_VIEW("toggle_view", "First / Third Person View", Category.CAMERA,
            InputBinding.key(KeyEvent.VK_F5)),
    /*
     * The camera's vertical axis, in both views it has one.
     *
     * In the solid views these pitch the eye, for anyone who would rather not
     * steer with the mouse — and they are the only way to pitch it at all on a
     * setup with no mouse. In a 3D level's plan view they raise and lower the
     * camera over the floor (Camera.tilt), which is the companion to the
     * rotate keys above: those two turn the camera around the player, these
     * two carry it up and over them. Both are held rather than pressed, because
     * the tilt is free rather than snapped and a player stops it where they
     * want it.
     *
     * A side-scroller tilts nothing: its screen *is* the vertical plane, so
     * there is no floor to stand over. The keys are harmless there rather than
     * hidden, on the same grounds the rotate keys are.
     *
     * Page Up/Down are the editor's build-height keys, which is a different
     * category and so not a conflict.
     */
    LOOK_UP("look_up", "Look Up / Raise Camera", Category.CAMERA,
            InputBinding.key(KeyEvent.VK_HOME)),
    LOOK_DOWN("look_down", "Look Down / Lower Camera", Category.CAMERA,
            InputBinding.key(KeyEvent.VK_END)),

    // --- menus & interface ----------------------------------------------------------
    MENU_UP("menu_up", "Menu: Previous Item", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_UP), InputBinding.key(KeyEvent.VK_W)),
    MENU_DOWN("menu_down", "Menu: Next Item", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_DOWN), InputBinding.key(KeyEvent.VK_S)),
    MENU_LEFT("menu_left", "Menu: Decrease / Previous", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_LEFT)),
    MENU_RIGHT("menu_right", "Menu: Increase / Next", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_RIGHT)),
    MENU_SELECT("menu_select", "Menu: Select", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_ENTER), InputBinding.key(KeyEvent.VK_SPACE)),
    MENU_BACK("menu_back", "Menu: Back / Close", Category.INTERFACE,
            InputBinding.key(KeyEvent.VK_ESCAPE)),
    PAUSE("pause", "Pause Game", Category.INTERFACE, InputBinding.key(KeyEvent.VK_ESCAPE)),

    // --- creative editor --------------------------------------------------------------
    EDITOR_PAINT("editor_paint", "Paint / Select", Category.EDITOR,
            InputBinding.mouse(MouseEvent.BUTTON1)),
    EDITOR_ERASE("editor_erase", "Erase", Category.EDITOR,
            InputBinding.mouse(MouseEvent.BUTTON3)),
    EDITOR_PICK("editor_pick", "Pick Block Under Cursor", Category.EDITOR,
            InputBinding.mouse(MouseEvent.BUTTON2)),
    EDITOR_UNDO("editor_undo", "Undo", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_Z, InputBinding.CTRL)),
    EDITOR_REDO("editor_redo", "Redo", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_Y, InputBinding.CTRL),
            InputBinding.key(KeyEvent.VK_Z, InputBinding.CTRL | InputBinding.SHIFT)),
    EDITOR_SAVE("editor_save", "Save Level", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_S, InputBinding.CTRL)),
    EDITOR_LOAD("editor_load", "Load Level", Category.EDITOR, InputBinding.key(KeyEvent.VK_L)),
    EDITOR_NEW("editor_new", "New Level", Category.EDITOR, InputBinding.key(KeyEvent.VK_N)),
    EDITOR_PLAYTEST("editor_playtest", "Play-Test Level", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_P)),
    EDITOR_TOGGLE_GRID("editor_grid", "Toggle Grid", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_G)),
    EDITOR_NEXT_PALETTE("editor_palette", "Next Palette Category", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_TAB)),
    EDITOR_DECOR_LAYER("editor_decor_layer", "Foreground / Background Decor", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_B)),
    EDITOR_BRUSH_SMALLER("editor_brush_smaller", "Smaller Brush", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_OPEN_BRACKET)),
    EDITOR_BRUSH_BIGGER("editor_brush_bigger", "Bigger Brush", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_CLOSE_BRACKET)),
    // The landscape tools. A brush that stacks a block is one verb among five,
    // and the other four are what turn a tower into terrain (HEIGHT_PLAN.md E3).
    EDITOR_BUILD_TOOL("editor_build_tool", "Next Landscape Tool", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_H)),
    // The build height, which is the escape hatch for everything the "build
    // against the face you point at" rule makes awkward (E4).
    EDITOR_LAYER_UP("editor_layer_up", "Build Height Up", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_PAGE_UP)),
    EDITOR_LAYER_DOWN("editor_layer_down", "Build Height Down", Category.EDITOR,
            InputBinding.key(KeyEvent.VK_PAGE_DOWN)),

    // --- the auto battler ---------------------------------------------------------------
    //
    // The mini games used to read raw key codes, which meant three whole games
    // whose controls could not be looked up, let alone changed. They are
    // actions like any other now; each game's own category is what lets its
    // controls screen show its keys and nothing else (KeyBindsScene.open).
    AUTO_REROLL("auto_reroll", "Reroll Shop", Category.AUTO_BATTLER,
            InputBinding.key(KeyEvent.VK_D)),
    AUTO_BUY_XP("auto_buy_xp", "Buy Experience", Category.AUTO_BATTLER,
            InputBinding.key(KeyEvent.VK_F)),
    AUTO_SELL("auto_sell", "Sell Selected Unit", Category.AUTO_BATTLER,
            InputBinding.key(KeyEvent.VK_S)),
    AUTO_LEAVE("auto_leave", "Leave Game (while paused)", Category.AUTO_BATTLER,
            InputBinding.key(KeyEvent.VK_L)),

    // --- the deckbuilder -----------------------------------------------------------------
    DECK_REVEAL("deck_reveal", "Reveal Leader", Category.DECK,
            InputBinding.key(KeyEvent.VK_R)),
    DECK_END_TURN("deck_end_turn", "End Turn", Category.DECK,
            InputBinding.key(KeyEvent.VK_E)),
    DECK_HELP("deck_help", "Show / Hide Help", Category.DECK,
            InputBinding.key(KeyEvent.VK_H)),
    DECK_LEAVE("deck_leave", "Leave Game (while paused)", Category.DECK,
            InputBinding.key(KeyEvent.VK_L)),

    // --- the evolution simulator ----------------------------------------------------------
    EVO_HELP("evo_help", "Show / Hide Help", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_H)),
    EVO_SHOP("evo_shop", "Open / Close Shop", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_B)),
    EVO_CATALOG("evo_catalog", "Species Catalogue", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_K)),
    EVO_TEMPERATURE("evo_temperature", "Thermometer Overlay", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_T)),
    EVO_NEXT_DISH("evo_next_dish", "Next Dish", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_TAB)),
    EVO_SLOWER("evo_slower", "Slow Time", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_OPEN_BRACKET)),
    EVO_FASTER("evo_faster", "Speed Time", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_CLOSE_BRACKET)),
    EVO_INSPECT("evo_inspect", "Inspect Tool", Category.EVOLUTION,
            InputBinding.key(KeyEvent.VK_I), InputBinding.key(KeyEvent.VK_BACK_QUOTE));

    /**
     * How the controls menu is grouped, and the scope a conflict is reported in.
     *
     * <p>The first six are the engine's own: they are what the world, the
     * menus and the creative editor read, and they are shown together on the
     * controls screen the engine's menus open. The rest belong to one mini game
     * each — a separate game with a separate keyboard, reached from its own
     * screen ({@link Category#miniGames()}) — so a key doing one thing in the
     * auto battler and another in the world is not a conflict and is not
     * reported as one.
     */
    public enum Category {
        MOVEMENT("Movement", true),
        COMBAT("Combat", true),
        ITEMS("Items & Interaction", true),
        CAMERA("Camera", true),
        INTERFACE("Menus & Interface", true),
        EDITOR("Creative Editor", true),
        AUTO_BATTLER("Auto Battler", false),
        DECK("Council of Six", false),
        EVOLUTION("Evolution", false);

        private final String label;
        private final boolean engine;

        Category(String label, boolean engine) {
            this.label = label;
            this.engine = engine;
        }

        public String label() { return label; }

        /** Whether this is one of the engine's own groups rather than a mini game's. */
        public boolean engine() { return engine; }

        /** The engine's own groups, in declaration order. */
        public static List<Category> engineGroups() {
            return Arrays.stream(values()).filter(Category::engine).toList();
        }

        /** One group per mini game, in declaration order. */
        public static List<Category> miniGames() {
            return Arrays.stream(values()).filter(c -> !c.engine()).toList();
        }
    }

    /** The hotbar actions, in slot order (paired with {@code Inventory.HOTBAR}). */
    private static final GameAction[] HOTBAR = {
            HOTBAR_1, HOTBAR_2, HOTBAR_3, HOTBAR_4, HOTBAR_5
    };

    private static final Map<String, GameAction> BY_ID = new HashMap<>();

    static {
        for (GameAction a : values()) BY_ID.put(a.id, a);
    }

    private final String id;
    private final String label;
    private final Category category;
    private final List<InputBinding> defaults;

    GameAction(String id, String label, Category category, InputBinding... defaults) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.defaults = List.of(defaults);
    }

    /** Stable identifier used in the saved file (never the enum name). */
    public String id() { return id; }

    /** What the controls menu calls this action. */
    public String label() { return label; }

    public Category category() { return category; }

    /** The bindings this action ships with, primary first. */
    public List<InputBinding> defaults() { return defaults; }

    /** The action for hotbar slot {@code index} (0-based), or {@code null}. */
    public static GameAction hotbar(int index) {
        return index >= 0 && index < HOTBAR.length ? HOTBAR[index] : null;
    }

    /** How many hotbar slots have their own action. */
    public static int hotbarCount() { return HOTBAR.length; }

    /** Look an action up by its saved {@link #id}, or {@code null} if unknown. */
    public static GameAction byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** Every action in {@code category}, in declaration order. */
    public static List<GameAction> in(Category category) {
        return Arrays.stream(values()).filter(a -> a.category == category).toList();
    }
}
