package com.larsons.engine.autobattler;

import com.larsons.engine.util.Json;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's personal board cosmetics: a colour scheme for the tiles and
 * backdrop, an optional background image, and decorative props (plants,
 * statues, lanterns...) in slots that start on the board's rim but can be
 * dragged anywhere around it — and reskinned with imported images per slot.
 * Purely client-side presentation — combat, placement, and replication never
 * read it, so customisation can't touch gameplay.
 *
 * <p>Persists as {@code board_theme.json} next to {@code skins.json} in the
 * player's game files ({@link #save()}/{@link #load}); the customisation menu
 * edits the live {@link #active()} instance so changes apply immediately.
 */
public final class BoardTheme {

    /** A decorative prop kind for a rim slot; NONE leaves the slot empty. */
    public enum Prop { NONE, PLANT, STATUE, LANTERN, BANNER, MUSHROOM, CRYSTAL, FOUNTAIN }

    /** A named board colour scheme: tiles, planning tint, backdrop, edge glow. */
    public static final class Scheme {
        public final String name;
        public final Color tileA, tileB;
        /** Tile colours for the player's own half during planning. */
        public final Color ownA, ownB;
        public final Color bgTop, bgBottom;
        public final Color edge;

        Scheme(String name, Color tileA, Color tileB, Color ownA, Color ownB,
               Color bgTop, Color bgBottom, Color edge) {
            this.name = name;
            this.tileA = tileA;
            this.tileB = tileB;
            this.ownA = ownA;
            this.ownB = ownB;
            this.bgTop = bgTop;
            this.bgBottom = bgBottom;
            this.edge = edge;
        }
    }

    /** The built-in colour schemes; index 0 is the classic default. */
    public static final List<Scheme> SCHEMES = List.of(
            new Scheme("Midnight",
                    new Color(40, 46, 66), new Color(46, 52, 74),
                    new Color(48, 60, 86), new Color(54, 66, 94),
                    new Color(14, 16, 28), new Color(26, 24, 44),
                    new Color(90, 110, 170)),
            new Scheme("Verdant Grove",
                    new Color(36, 52, 40), new Color(42, 60, 46),
                    new Color(44, 68, 50), new Color(50, 76, 56),
                    new Color(12, 20, 14), new Color(22, 34, 24),
                    new Color(110, 175, 110)),
            new Scheme("Ember Court",
                    new Color(58, 38, 34), new Color(66, 44, 38),
                    new Color(74, 52, 42), new Color(82, 58, 46),
                    new Color(24, 12, 10), new Color(40, 22, 16),
                    new Color(210, 125, 70)),
            new Scheme("Frozen Keep",
                    new Color(38, 50, 66), new Color(44, 58, 76),
                    new Color(48, 66, 88), new Color(54, 74, 96),
                    new Color(10, 16, 26), new Color(20, 30, 44),
                    new Color(130, 190, 235)),
            new Scheme("Royal Arcana",
                    new Color(48, 38, 66), new Color(56, 44, 76),
                    new Color(62, 50, 88), new Color(70, 56, 96),
                    new Color(18, 12, 30), new Color(32, 22, 48),
                    new Color(170, 130, 220)),
            new Scheme("Sunset Bazaar",
                    new Color(70, 56, 40), new Color(78, 64, 46),
                    new Color(86, 70, 50), new Color(94, 78, 56),
                    new Color(30, 18, 14), new Color(52, 34, 24),
                    new Color(235, 180, 100)));

    /** How many prop slots ring the board. */
    public static final int PROP_SLOTS = 8;

    /**
     * Default rim positions of the prop slots in board-cell coordinates
     * (fractional, just outside the 8x8 grid), matching {@link #SLOT_LABELS}
     * by index. Each slot also carries a player-set offset, so decorations
     * can be dragged anywhere around (or onto) the board.
     */
    public static final double[][] PROP_CELLS = {
            {-0.7, -0.7}, {8.7, -0.7}, {-0.7, 8.7}, {8.7, 8.7},
            {4.0, -1.0}, {-1.0, 4.0}, {9.0, 4.0}, {4.0, 9.0}
    };

    /** How far (in cells) a decoration may be dragged from the board. */
    public static final double PROP_MIN_COORD = -2.5;
    public static final double PROP_MAX_COORD = 10.5;

    public static final String[] SLOT_LABELS = {
            "Far corner", "Right corner", "Left corner", "Near corner",
            "Far-right edge", "Far-left edge", "Near-right edge", "Near-left edge"
    };

    /** Default on-disk location, next to the skins the same menu family edits. */
    public static final String DEFAULT_DIR = "src/main/resources/skins";
    public static final String FILE_NAME = "board_theme.json";

    private static BoardTheme active;

    private int schemeIndex;
    /** Background image path (classpath-first), or empty for none. */
    private String background = "";
    private final Prop[] props = new Prop[PROP_SLOTS];
    /** Player-set placement offsets from {@link #PROP_CELLS}, in cells. */
    private final double[][] propOffsets = new double[PROP_SLOTS][2];
    /** Custom image path per slot (drawn instead of the procedural prop). */
    private final String[] propImages = new String[PROP_SLOTS];
    private final Path dir;

    public BoardTheme() {
        this(Path.of(DEFAULT_DIR));
    }

    public BoardTheme(Path dir) {
        this.dir = dir;
        for (int i = 0; i < props.length; i++) {
            props[i] = Prop.NONE;
            propImages[i] = "";
        }
    }

    /** The live theme every board render reads; loaded from disk on first use. */
    public static synchronized BoardTheme active() {
        if (active == null) active = load(Path.of(DEFAULT_DIR));
        return active;
    }

    public Scheme scheme() {
        return SCHEMES.get(Math.floorMod(schemeIndex, SCHEMES.size()));
    }

    public int schemeIndex() {
        return Math.floorMod(schemeIndex, SCHEMES.size());
    }

    public void cycleScheme() {
        schemeIndex = Math.floorMod(schemeIndex + 1, SCHEMES.size());
    }

    public String background() {
        return background;
    }

    public void setBackground(String path) {
        background = path == null ? "" : path.trim();
    }

    public Prop prop(int slot) {
        return props[Math.floorMod(slot, PROP_SLOTS)];
    }

    public void cycleProp(int slot) {
        Prop[] all = Prop.values();
        int i = Math.floorMod(slot, PROP_SLOTS);
        props[i] = all[(props[i].ordinal() + 1) % all.length];
    }

    /** A slot renders when it has a prop kind or a custom image. */
    public boolean propVisible(int slot) {
        int i = Math.floorMod(slot, PROP_SLOTS);
        return props[i] != Prop.NONE || !propImages[i].isEmpty();
    }

    /** The slot's current board-cell column (default position + drag offset). */
    public double propCol(int slot) {
        int i = Math.floorMod(slot, PROP_SLOTS);
        return PROP_CELLS[i][0] + propOffsets[i][0];
    }

    /** The slot's current board-cell row (default position + drag offset). */
    public double propRow(int slot) {
        int i = Math.floorMod(slot, PROP_SLOTS);
        return PROP_CELLS[i][1] + propOffsets[i][1];
    }

    /** Place a slot at an absolute board-cell position (clamped near the board). */
    public void placeProp(int slot, double col, double row) {
        int i = Math.floorMod(slot, PROP_SLOTS);
        propOffsets[i][0] = clampCoord(col) - PROP_CELLS[i][0];
        propOffsets[i][1] = clampCoord(row) - PROP_CELLS[i][1];
    }

    /** Nudge a slot by fractional cells (arrow-key editing). */
    public void nudgeProp(int slot, double dc, double dr) {
        placeProp(slot, propCol(slot) + dc, propRow(slot) + dr);
    }

    private static double clampCoord(double v) {
        return Math.max(PROP_MIN_COORD, Math.min(PROP_MAX_COORD, v));
    }

    /** The slot's custom image path, or empty when it uses the procedural prop. */
    public String propImage(int slot) {
        return propImages[Math.floorMod(slot, PROP_SLOTS)];
    }

    public void setPropImage(int slot, String path) {
        propImages[Math.floorMod(slot, PROP_SLOTS)] = path == null ? "" : path.trim();
    }

    public void reset() {
        schemeIndex = 0;
        background = "";
        for (int i = 0; i < props.length; i++) {
            props[i] = Prop.NONE;
            propImages[i] = "";
            propOffsets[i][0] = 0;
            propOffsets[i][1] = 0;
        }
    }

    public Path file() {
        return dir.resolve(FILE_NAME);
    }

    /** Persist to {@code board_theme.json} (the file is rewritten wholesale). */
    public Path save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scheme", scheme().name);
        root.put("background", background);
        List<Object> list = new ArrayList<>(props.length);
        for (Prop p : props) list.add(p.name());
        root.put("props", list);
        List<Object> offsets = new ArrayList<>(PROP_SLOTS);
        for (double[] off : propOffsets) {
            offsets.add(List.of(round2(off[0]), round2(off[1])));
        }
        root.put("offsets", offsets);
        root.put("images", new ArrayList<Object>(List.of(propImages)));
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), Json.stringify(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file();
    }

    /** Load from {@code dir}; an absent/unreadable file is just the default theme. */
    public static BoardTheme load(Path dir) {
        BoardTheme theme = new BoardTheme(dir);
        Path file = theme.file();
        if (!Files.exists(file)) return theme;
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(file)));
            String scheme = root.get("scheme") instanceof String s ? s : "";
            for (int i = 0; i < SCHEMES.size(); i++) {
                if (SCHEMES.get(i).name.equalsIgnoreCase(scheme)) theme.schemeIndex = i;
            }
            theme.setBackground(root.get("background") instanceof String s ? s : "");
            if (root.get("props") instanceof List<?> list) {
                for (int i = 0; i < PROP_SLOTS && i < list.size(); i++) {
                    if (list.get(i) instanceof String name) {
                        try {
                            theme.props[i] = Prop.valueOf(name);
                        } catch (IllegalArgumentException ignored) {
                            // unknown prop names stay NONE
                        }
                    }
                }
            }
            if (root.get("offsets") instanceof List<?> list) {
                for (int i = 0; i < PROP_SLOTS && i < list.size(); i++) {
                    if (list.get(i) instanceof List<?> pair && pair.size() >= 2
                            && pair.get(0) instanceof Number dc
                            && pair.get(1) instanceof Number dr) {
                        theme.propOffsets[i][0] = dc.doubleValue();
                        theme.propOffsets[i][1] = dr.doubleValue();
                    }
                }
            }
            if (root.get("images") instanceof List<?> list) {
                for (int i = 0; i < PROP_SLOTS && i < list.size(); i++) {
                    if (list.get(i) instanceof String path) theme.propImages[i] = path;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("BoardTheme: unreadable " + file + " (" + e.getMessage() + ")");
        }
        return theme;
    }

    private static double round2(double v) {
        return Math.rint(v * 100) / 100.0;
    }
}
