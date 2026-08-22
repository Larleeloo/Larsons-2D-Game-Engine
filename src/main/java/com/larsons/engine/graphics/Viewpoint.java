package com.larsons.engine.graphics;

/**
 * Where the player's camera stands — the {@code F5} cycle every 3D game has.
 *
 * <p><b>This is not {@link Perspective}, and the distinction is the whole
 * reason it is a separate enum.</b> A {@code Perspective} is a property of the
 * <em>level</em>: a {@code com.larsons.engine.level.LevelFormat} is not a view
 * of a world but a kind of world, and walking through a door into a level of
 * another format is how a game changes it. A {@code Viewpoint} is a property of
 * the <em>player</em>, chosen while playing, never networked, and thrown away
 * when they leave. One is what the world is; the other is where you are
 * standing to look at it.
 *
 * <p>All four stand the camera <em>in</em> the world and divide by depth —
 * {@link SolidPainter} through an {@link EyeCamera} — and what separates them
 * is where it stands and who turns it. Three are mouse-look views at or near
 * the player's own eyes. {@link #PLAN} is the fourth: the camera is lifted and
 * pulled back so the world is seen from above and behind, and it turns in eight
 * snapped steps rather than freely.
 *
 * <p><b>Why the plan view is drawn this way now.</b> It used to be the flat
 * {@link Camera}'s parallel projection — the picture every level in this engine
 * was authored through — and that is a fine way to <em>draw</em> a level and a
 * poor way to stand in one. A parallel projection has no viewpoint, so nothing
 * in it says how far away anything is; with a height axis and columns half a
 * dozen blocks deep, a player could not tell which floor they were standing on
 * or where their own character was among the towers drawn over it. The picture
 * did not reflect the player's position because it had no position to reflect.
 * Standing the same eye at a distance fixes that for nothing: it is the
 * renderer the other three views already use, at a longer arm.
 *
 * <p><b>Eight snap points, because the sprites have eight angles.</b> The
 * heading still comes from {@link Camera}, which turns in eighths of a circle
 * and animates between them — so the direction a character is drawn facing
 * ({@code Facing.asSeenFrom}) lands on one of the eight frames its art actually
 * has, instead of somewhere between two of them. That is the whole reason the
 * flat camera keeps owning this view's heading: the snapping is not a
 * limitation left over from the old projection, it is what makes the art line
 * up.
 *
 * <p><b>Only where there is a height axis.</b> A side-scroller's screen
 * <em>is</em> the vertical plane — there is no third axis to stand an eye in,
 * and no heading to look along — so {@link #availableIn} refuses the solid
 * modes there and the cycle collapses to {@link #PLAN} alone. That is the same
 * answer {@link Camera#rotates()} gives for the same reason.
 */
public enum Viewpoint {

    /**
     * Over the world, looking down at the player from a fixed arm's length —
     * the view a level is played in, and the engine's first stop. Turns in
     * eight snapped steps; aims with the pointer rather than a crosshair.
     */
    PLAN("Plan view", 11, true, false, false),

    /** Behind the eyes. The body is not drawn; the held object is. */
    FIRST_PERSON("First person", 0, false, false, true),

    /**
     * Over the shoulder, looking the way the player looks — Minecraft's
     * second F5 stop, and the one people actually play in.
     */
    THIRD_PERSON_BACK("Third person", 3.4, true, false, true),

    /**
     * In front, looking back at the player — Minecraft's third stop. The
     * camera stands where the player is looking and turns round, so you see
     * your own face.
     */
    THIRD_PERSON_FRONT("Third person, front", 3.4, true, true, true);

    private final String label;
    private final double distanceTiles;
    private final boolean showsSelf;
    private final boolean reversed;
    private final boolean freeLook;

    Viewpoint(String label, double distanceTiles, boolean showsSelf, boolean reversed,
              boolean freeLook) {
        this.label = label;
        this.distanceTiles = distanceTiles;
        this.showsSelf = showsSelf;
        this.reversed = reversed;
        this.freeLook = freeLook;
    }

    /** What the HUD calls this view. */
    public String label() { return label; }

    /**
     * Whether this view is drawn by {@link SolidPainter} through an
     * {@link EyeCamera} rather than by {@link Camera}'s flat projection.
     *
     * <p>True of all four now, which is the change the plan view's rebuild
     * made: a scene still branches on this to decide which renderer runs, and
     * the answer no longer depends on which stop of the cycle is held. It stays
     * a question rather than becoming a constant because a level without a
     * height axis has no eye to stand anywhere and is still drawn flat — see
     * {@link #availableIn}, and the scene's own {@code solidView()}, which is
     * where the two conditions meet.
     */
    public boolean solid() { return true; }

    /**
     * Whether the mouse steers this view's camera freely, rather than the
     * camera turning in snapped steps under the rotate keys.
     *
     * <p>The distinction {@link #solid} used to carry, and it is the one that
     * actually decides how a frame behaves: a free-look view hides the pointer,
     * draws a crosshair and aims down the middle of the screen; a snapped one
     * keeps the pointer, aims through the pixel under it, and takes its heading
     * from {@link Camera} so the eight sprite angles land square.
     */
    public boolean freeLook() { return freeLook; }

    /**
     * How far behind the eye the camera is pulled, in tiles. Zero in first
     * person, where the camera <em>is</em> the eye; a long arm in the plan
     * view, which is what makes it a plan view.
     */
    public double distanceTiles() { return distanceTiles; }

    /**
     * Whether the player's own body is drawn. False in first person only —
     * the same rule every 3D game follows, and for the same reason: a
     * billboard drawn at the camera's own position fills the screen.
     */
    public boolean showsSelf() { return showsSelf; }

    /**
     * Whether the camera looks back along the player's heading rather than
     * along it. True in {@link #THIRD_PERSON_FRONT} alone.
     */
    public boolean reversed() { return reversed; }

    /**
     * Whether this view can be used in a space with (or without) a height
     * axis. Only {@link #PLAN} survives a side-scroller; see the class note.
     */
    public boolean availableIn(boolean hasElevation) {
        return hasElevation || this == PLAN;
    }

    /**
     * The next stop in the cycle, skipping anything this space cannot show.
     *
     * <p>Written as a bounded walk rather than {@code (ordinal + 1) % length}
     * so a space that allows only one view returns that view instead of
     * looping forever, and so adding a mode later cannot introduce a stop the
     * cycle silently jumps over.
     */
    public Viewpoint next(boolean hasElevation) {
        return next(hasElevation, null);
    }

    /**
     * {@link #next(boolean)}, skipping anything {@code lock} rules out as well
     * — a level may switch the solid views off entirely, and then this cycle
     * collapses to {@link #PLAN} exactly as a side-scroller's does.
     *
     * <p>A {@code null} lock allows everything, which is what a scene with no
     * level loaded and every test that predates locking passes.
     */
    public Viewpoint next(boolean hasElevation, CameraLock lock) {
        Viewpoint[] all = values();
        for (int i = 1; i <= all.length; i++) {
            Viewpoint candidate = all[(ordinal() + i) % all.length];
            if (!candidate.availableIn(hasElevation)) continue;
            if (lock != null && !lock.allows(candidate)) continue;
            return candidate;
        }
        return this;
    }
}
