package com.larsons.engine.world;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;

/**
 * One <em>surface decoration</em> definition: a small visual detail attached
 * to a <b>face</b> of a block — tall grass on top of grass blocks, moss
 * hanging from an underside, twigs sticking out of a log's side, icicles,
 * cobwebs… Unlike free-standing {@link Decor}, surface decor is anchored to a
 * specific tile face and follows that block around: mine the block and the
 * decoration disappears with it.
 *
 * <p>Each painted instance ({@link Placement}) carries three toggles chosen in
 * the creative editor:
 * <ul>
 *   <li><b>Face</b> — which side of the block it sits on (up/down/left/right);
 *       definitions restrict which faces make sense via {@link #allowedFaces}.</li>
 *   <li><b>Visibility</b> — draw always, only while the face is <em>open</em>
 *       (not touching another block), or only while it is <em>closed</em>
 *       (buried against a neighbour) — so grass hides when you build over it
 *       and roots only show inside seams.</li>
 *   <li><b>Layer</b> — background (behind the player) or foreground (in
 *       front), like the free-standing decor layers.</li>
 * </ul>
 *
 * @param key          stable string key stored in level JSON
 * @param name         palette display name
 * @param style        which procedural painter draws it
 * @param primary      main colour
 * @param secondary    accent colour
 * @param allowedFaces faces this decoration may attach to
 * @param defaultForeground layer new placements default to
 */
public record SurfaceDecor(String key, String name, Style style, Color primary,
                           Color secondary, Set<Face> allowedFaces,
                           boolean defaultForeground) {

    /** A block face a surface decoration can attach to. */
    public enum Face {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

        /** Offset to the neighbouring cell across this face. */
        public final int dc, dr;

        Face(int dc, int dr) {
            this.dc = dc;
            this.dr = dr;
        }
    }

    /** When a placed decoration is visible, relative to its face's neighbour. */
    public enum Visibility {
        /** Draw regardless of what touches the face. */
        ALWAYS,
        /** Only while the face is exposed (neighbour cell not solid). */
        OPEN_FACE,
        /** Only while the face is covered (neighbour cell solid). */
        CLOSED_FACE
    }

    /** The procedural silhouettes surface decorations can take. */
    public enum Style { GRASS_TUFT, FLOWERS, HANGING_MOSS, ICICLES, TWIGS,
        MUSHROOMS, COBWEB, ROOTS, CRYSTALS, DRIP }

    public SurfaceDecor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("SurfaceDecor key required");
        }
        if (primary == null) primary = Color.GRAY;
        if (secondary == null) secondary = primary.darker();
        if (allowedFaces == null || allowedFaces.isEmpty()) {
            allowedFaces = EnumSet.allOf(Face.class);
        } else {
            allowedFaces = EnumSet.copyOf(allowedFaces);
        }
    }

    public boolean allows(Face f) {
        return allowedFaces.contains(f);
    }

    /**
     * One painted instance: decoration {@code key} on the {@code face} of the
     * block at (col,row). Lives in {@code Level.surfaceDecor} and serializes
     * with the level.
     */
    public record Placement(int col, int row, Face face, String key,
                            boolean foreground, Visibility visibility) {}
}
