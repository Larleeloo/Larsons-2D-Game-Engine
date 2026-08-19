package com.larsons.engine.graphics;

/**
 * A 2D camera that supports both of the engine's perspectives (requirement #2).
 *
 * <p>World coordinates are mapped to the screen in two steps:
 * <ol>
 *   <li>a per-perspective "planar" projection (identity for the side view; a
 *       turned and foreshortened ground plane for {@link Perspective#THREE_D}),</li>
 *   <li>then zoom and centering on the camera focus.</li>
 * </ol>
 * Because the projection is the only thing that changes between perspectives,
 * the same tile/sprite drawing code renders correctly in the side view and at
 * every heading and tilt of the 3D one.
 *
 * <p><b>The world lands on a pixel lattice the camera cannot move, and that is
 * the fix for the shimmer.</b> The obvious way to write step 2 is to round the
 * whole thing at once — {@code round((world - camera) * zoom + viewport/2)} —
 * and that is what this did. It is wrong in a way that only shows up in motion.
 * The camera is a {@code double} and slides continuously, so every object
 * crosses its own rounding boundary at its own moment: at {@code zoom = 1.7} a
 * 32-unit tile is 54.4 pixels wide, and as the view pans each tile is
 * alternately rounded to 54 and to 55 while its neighbour is not. Neighbouring
 * blocks slide against one another by a pixel, over and over, for as long as the
 * camera moves. That is the "blocks jitter slightly" a player reports, and it is
 * a defect in the projection rather than in either rasteriser — both backends
 * draw exactly what this told them to.
 *
 * <p>So the rounding is split in two:
 *
 * <pre>
 *   screen = round(world * zoom)  +  round(viewport/2 - camera * zoom)
 *            \_________________/     \___________________________/
 *             the world's lattice     one offset, once, per frame
 *             — no camera term        — the same for everything
 * </pre>
 *
 * <p>The first term has no camera in it at all, so the pixel distance between
 * any two things in the world is fixed forever; the second is a single integer
 * that the whole scene translates by. The picture moves as one rigid sheet
 * instead of shivering against itself.
 *
 * <p><b>This is not a new idea here — it is
 * {@link TerrainCache}'s, applied to everything else.</b> That class worked the
 * argument out for baked floor chunks, for exactly this symptom ("as the view
 * moved a fraction of a pixel the chunks slid against one another and the
 * terrain visibly shook") and fixed it the same way. What it could not fix was
 * everything it deliberately does not cache: stacked blocks, mobs, dropped
 * items, decor and particles all go through the live sweep, which kept the old
 * arithmetic. A steady floor with shivering blocks on top of it is precisely
 * what was left.
 *
 * <p><b>What it costs.</b> Two roundings instead of one, so the whole scene can
 * sit up to a pixel from where a single rounding would have put it — including
 * the camera's own focus point, which is no longer guaranteed to land exactly at
 * the viewport centre. That error is <em>uniform</em>: everything shares it, so
 * nothing moves relative to anything else, which is the only property the eye
 * can see. A static half-pixel offset is invisible; a moving one is the bug.
 *
 * <p><b>It stays correct if the camera ever rotates.</b> The split works because
 * {@link #planar} is linear, so it can be applied to the world point and the
 * camera point separately and subtracted afterwards. A yaw belongs inside
 * {@code planar}, on both, and the arithmetic below does not change. This is not
 * snapping geometry to a screen-aligned grid, which would be wrong for a turned
 * view; it is quantising the world in its own space. That prediction is now
 * discharged rather than hoped for: {@link #setYaw} puts the rotation inside
 * {@code planar}, and {@code CameraYawTest} asserts the rigid-sheet property at
 * all eight headings.
 *
 * <h2>Yaw</h2>
 *
 * <p>{@link #yaw()} is the compass heading the camera faces, in radians,
 * <em>clockwise from world north</em> — north being −y, which is up the screen
 * at heading zero. The projection therefore rotates the world by the
 * <em>inverse</em>: turn the camera right and the world swings left, which is
 * what a camera does. At {@code yaw = π/2} the camera looks east, so world east
 * is the direction that now points up the screen.
 *
 * <p><b>Rotation belongs to the 3D view only.</b> {@link Perspective#SIDE_SCROLL}
 * ignores yaw entirely: the screen there <em>is</em> the vertical plane, +y is
 * the direction gravity pulls rather than a ground-plane axis, and there is no
 * vertical axis on screen to turn around. Rotating it would tip the world over
 * rather than turn it. {@link Perspective#THREE_D} rotates, on the ground plane,
 * because the camera turns around the world's vertical axis and not around the
 * screen's.
 *
 * <h2>Pitch</h2>
 *
 * <p>{@link #pitch()} is how high the camera stands over the floor it is looking
 * at, in radians: {@link #MAX_PITCH} is straight down and {@link #MIN_PITCH} is
 * flat on the floor. Unlike the yaw, which rests only on the eight compass
 * points, it moves <em>freely</em> — a player holding the look keys sweeps the
 * camera up and over the world and stops wherever they like.
 *
 * <p>Two numbers carry it into the picture, and they are the whole of the tilt:
 *
 * <pre>
 *   ground depth × sin(pitch)     how far a step north travels up the screen
 *   height       × cos(pitch)     how far a block's top rises above its floor
 * </pre>
 *
 * <p>That is an orthographic camera raised over a plane, and it is why the two
 * plan views this engine used to keep apart are one view here. Bring the camera
 * most of the way down and the floor squashes while blocks stand tall — the
 * oblique three-quarter picture that used to be a separate "isometric" format.
 * Take it to the top of its travel and the floor opens out while the blocks
 * flatten into their own top faces — the old "top-down" format. Nothing switched;
 * the camera moved.
 *
 * <p><b>The lift never quite reaches zero</b> ({@link #MIN_LIFT}). Straight down
 * is exactly where {@code cos(pitch)} vanishes, and a wall drawn zero pixels tall
 * is a wall a player cannot see: the geometry that makes a plan-view level
 * readable — one layer is floor, two is a wall — would stop being visible at the
 * one angle a player is most likely to build from. The floor keeps a block's
 * face on screen at the cost of the projection being cavalier rather than
 * strictly orthographic in the last fifteen degrees of travel, which is the
 * trade every game with a straight-down view makes.
 *
 * <h2>The other end: a camera flat on the floor</h2>
 *
 * <p>At {@link #MIN_PITCH} the ground's depth axis multiplies by zero and the
 * whole floor collapses onto one screen row. {@link #sliced()} says so, and it
 * changes three things rather than being a curiosity at the end of a range:
 *
 * <ul>
 *   <li><b>The picture becomes a side elevation.</b> Screen x is the distance
 *       across the view and screen y is height alone, at full length — so what
 *       is drawn is the world cut open along the line the camera looks down,
 *       and the level reads as a platformer of whatever slice the focus is
 *       standing in. A floor tile has no area at all there, which is why the
 *       ground stops being drawn and the standing geometry is all that is
 *       left.</li>
 *   <li><b>Depth stops being a screen row.</b> Everything projects to the same
 *       one, so a painter that sorts on screen rows has nothing to sort by.
 *       {@link #depthOf} is the key it sorts on instead: the distance along the
 *       view axis, which is what "nearer" meant all along and which the screen
 *       row was only ever a foreshortened copy of.</li>
 *   <li><b>The projection stops being invertible</b>, because a screen point is
 *       now a whole line of the world. {@link #inversePlanar} answers with the
 *       depth component dropped and {@link #screenToWorld} puts it back at the
 *       focus's own depth — the slice you are looking at, which is the one
 *       answer a click could sensibly mean.</li>
 * </ul>
 *
 * <p>What is <em>in front</em> of the focus would otherwise be drawn over it,
 * which at this angle is half the level; culling it is the painter's job (see
 * {@code TerrainPainter}), and it is what makes the cut a cut.
 *
 * <h2>The board diamond</h2>
 *
 * <p>{@link #useBoardDiamond} replaces the tilt with a fixed diamond: one world
 * tile projects to an {@code isoTileWidth × isoTileHeight} rhombus, whatever the
 * pitch says. It is <em>not</em> a level format and no level uses it — it is for
 * the board games (the auto battler's arena), which want one unchanging
 * projection rather than a camera a player can move. A board is not a world.
 */
public class Camera {
    /**
     * The angle between two adjacent compass points — 45° in radians, and one
     * press of the rotate key once C8 binds it.
     */
    public static final double EIGHTH_TURN = Math.PI / 4;

    /**
     * The lowest the camera may be brought over the floor: flat on it, looking
     * straight along the ground. See {@link #sliced()} — this is a view of its
     * own rather than the end of a range nobody visits.
     */
    public static final double MIN_PITCH = 0;

    /**
     * How far above the floor {@link #tilt} refuses to rest — the band between
     * a fully edge-on camera and a usable raking one, which a held key crosses
     * in a single step in either direction.
     *
     * <p><b>Why the tilt has a detent at the bottom and the yaw does not.</b>
     * Between 0° and this angle the floor is foreshortened past reading — a
     * tile is a couple of pixels deep — while the world in front of you is
     * still drawn over you, because it is only at 0° that "in front" becomes a
     * clean cut ({@link #sliced()}). It is the one part of the camera's travel
     * with nothing to recommend it, so a player sweeping the key drops through
     * it rather than stopping in it, and one press off the floor comes back out
     * the other side.
     *
     * <p>{@link #setPitch} has no detent: a level may lock its camera anywhere
     * it likes, and a test may place it anywhere. This is about what a held key
     * does, in the same way {@link #turn} is and {@link #setYaw} is not.
     */
    public static final double SLICE_DETENT = Math.toRadians(8);

    /** Straight down: the camera directly over the floor it is looking at. */
    public static final double MAX_PITCH = Math.toRadians(90);

    /**
     * Where a 3D camera starts: high enough that the floor plan reads, low
     * enough that walls stand up in it and the world is obviously not flat.
     */
    public static final double DEFAULT_PITCH = Math.toRadians(60);

    /**
     * The tilt at and above which the world reads as being seen from
     * <em>overhead</em> rather than from over the shoulder — 75°, at which the
     * ground has opened to within four percent of its full depth and a block
     * stands barely a quarter of its own height.
     *
     * <p>Consumed by {@code PlayerSprites}, which draws the character from a
     * different pool of art on either side of it: past this angle you are
     * looking at the top of someone's head, and art drawn for a shoulder-height
     * camera is the wrong picture of them.
     */
    public static final double OVERHEAD_PITCH = Math.toRadians(75);

    /** How fast the look keys sweep the tilt, radians per second. */
    public static final double TILT_SPEED = Math.toRadians(60);

    /** The least a block's height may be drawn at; see the class note. */
    public static final double MIN_LIFT = 0.2;

    /** Focus position in world coordinates (the point centred on screen). */
    public double x, y;
    public double zoom = 1.0;
    public int viewportWidth, viewportHeight;

    private Perspective perspective;

    /**
     * The camera's heading, radians clockwise from world north; see the class
     * note. Private rather than public like {@link #x} because the projection
     * reads its cosine and sine on the hot path (four corners per tile) and
     * {@link #setYaw} is what keeps those two in step with it.
     */
    private double yaw;
    /**
     * The heading the camera is turning towards — the compass point {@link #turn}
     * aimed it at, which {@link #stepYaw} eases {@link #yaw} onto. Stored on the
     * camera because it is view state: per client, never networked (C10).
     */
    private double targetYaw;

    // cos/sin of yaw, maintained by setYaw. See snap() for why they are not
    // simply Math.cos/Math.sin of it.
    private double cosYaw = 1.0, sinYaw = 0.0;

    /**
     * How high the camera stands over the floor, radians; see the class note.
     * Private for the same reason {@link #yaw} is — the projection reads its
     * sine on the hot path, and {@link #setPitch} is what keeps the two in step.
     */
    private double pitch = DEFAULT_PITCH;

    // sin/cos of pitch: the ground's foreshortening and the height's lift.
    private double sinPitch = snap(Math.sin(DEFAULT_PITCH));
    private double cosPitch = snap(Math.cos(DEFAULT_PITCH));

    /**
     * Whether this camera projects the ground into a fixed diamond instead of
     * tilting over it — the board games' projection, never a level's. See the
     * class note and {@link #useBoardDiamond}.
     */
    private boolean boardDiamond;

    /**
     * Where the level lets this camera stand. Free until a level says
     * otherwise, and consulted by the two verbs a player drives ({@link #turn},
     * {@link #tilt}) and the two a level does ({@link #setYaw},
     * {@link #setPitch}) — so there is no way to place the camera somewhere the
     * level forbids, whichever of the four does the placing.
     */
    private CameraLock lock = CameraLock.free();

    /**
     * The compass point the camera is settled on or turning to, 0–7.
     *
     * <p>Kept as a whole number rather than derived from {@link #targetYaw},
     * because the alternative is adding 45° to a {@code double} once per press
     * for the length of a session: after enough turns the "exact multiple of
     * 45°" that {@link #setYaw}'s snapping depends on stops being exact, and
     * the four cardinal headings quietly stop being axis swaps. An index cannot
     * drift.
     */
    private int heading;

    /** Where the snap in flight started and ends, as absolute angles. */
    private double turnFrom, turnTo;
    /** Seconds into the snap in flight, and whether there is one. */
    private double turnElapsed;
    private boolean turning;
    /**
     * One press taken during a snap, {@code -1}/{@code 0}/{@code +1} — the
     * decision C8 asked to be made and recorded: <b>queue one, drop the
     * rest</b>. A held key then turns the world one step at a time for as long
     * as it is held, which reads as responsive; queueing every press instead
     * would spin the world for seconds after the key came up, and blending two
     * turns at once would leave the camera resting between compass points,
     * which is the one thing an eight-point camera may never do.
     */
    private int queued;

    // Isometric projection parameters: one world tile (tileSize units on each
    // axis) projects to a diamond this many pixels wide/tall.
    public double isoTileWidth = 64;
    public double isoTileHeight = 32;
    /** World units per tile; used by the isometric projection. */
    public double tileSize = 32;

    public Camera(Perspective perspective, int viewportWidth, int viewportHeight) {
        this.perspective = perspective;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    public Perspective getPerspective() { return perspective; }

    public void setPerspective(Perspective p) { this.perspective = p; }

    public void setViewport(int w, int h) {
        this.viewportWidth = w;
        this.viewportHeight = h;
    }

    /**
     * How high the thing the camera is following is standing, in world units —
     * where it <em>is</em>, as opposed to where the camera has decided to key
     * its picture ({@link #restHeight}).
     *
     * <p>Zero everywhere the focus is on the floor, which is every level that
     * has not switched its height axis on and every side-scroller ever. It is
     * kept apart from {@link #centerOn} because it is not a world position: a
     * player climbing a tower has not moved on the plane at all, and the camera
     * still has to follow them up.
     */
    private double focusHeight;

    /**
     * The height the projection is actually keyed to — what {@link #elevation()}
     * is computed from.
     *
     * <p><b>Two numbers rather than one, because "follow the player's height"
     * and "hold the ground still" are both requirements and they disagree
     * during a jump.</b> Keyed rigidly to the body, the camera rises the instant
     * the body does: the player hangs motionless in the middle of the screen and
     * the whole world drops away underneath them and comes back, every hop, and
     * every step up a staircase. That reads as the ground moving rather than as
     * the character jumping, which is precisely backwards — the jump is the
     * thing that moved.
     *
     * <p>So in {@link HeightFollow#EASED} this trails the focus: it does not
     * move at all while the body stays within {@link #FOLLOW_SLACK_BLOCKS} of
     * it (a hop's whole arc, and the first steps of a climb), and closes the
     * remainder smoothly when a real climb takes the body past that. The lift
     * is still one uniform offset applied to the whole picture, so the "world
     * moves as one rigid sheet" property the class note is about is untouched:
     * this changes <em>when</em> the sheet moves, not whether it shears.
     */
    private double restHeight;

    /** Whether anything has been followed yet; the first follow places rather than eases. */
    private boolean followed;

    /** How the camera's height keeps up with what it follows. */
    public enum HeightFollow {
        /**
         * The camera's height <em>is</em> the focus's height, every frame — a
         * cutscene's mark, a test, a camera being placed. What the engine has
         * always done.
         */
        RIGID,
        /**
         * The camera's height trails the focus, ignoring anything inside the
         * slack and gliding over the rest — what a played level wants, so a
         * jump moves the player and not the ground.
         */
        EASED
    }

    /**
     * How far the focus may rise or fall before the camera goes with it, in
     * blocks.
     *
     * <p>Three, because a hop is under two ({@code HOP_SPEED²/2·HOP_GRAVITY} is
     * about 1.8 blocks) and a staircase should be climbable a step or two
     * without the view lurching. Past it, the camera has to move or a player
     * climbing a tower walks off the top of their own screen.
     */
    public static final double FOLLOW_SLACK_BLOCKS = 3;

    /**
     * A height change no body could have made in one step, in blocks — a
     * placement rather than a movement.
     *
     * <p>A door, a respawn, a teleport, a test putting the camera somewhere:
     * those are not climbs and easing into them would show the world sliding
     * from wherever the last level left the camera. Falling at terminal speed
     * covers a small fraction of a block per tick, so nothing a body does
     * reaches this.
     */
    public static final double FOLLOW_PLACE_BLOCKS = 2;

    /** How long the camera takes to close the height it has fallen behind by. */
    public static final double FOLLOW_SECONDS = 0.28;

    private HeightFollow heightFollow = HeightFollow.RIGID;

    /**
     * Screen pixels of lift per world unit of height, before zoom — how far up
     * the screen something rises by being one unit above the floor.
     *
     * <p><b>This is the tilt's half of the projection</b>, and asking the camera
     * is what keeps every painter's idea of "up" the same one. An orthographic
     * camera raised {@link #pitch()} above the floor draws a vertical world
     * edge at {@code cos(pitch)} of its length: lower the camera and blocks
     * stand up, raise it and they flatten into their own top faces. The floor
     * at {@link #MIN_LIFT} is why they never flatten <em>away</em> — see the
     * class note.
     *
     * <p>A board diamond keeps the fixed relationship its projection implies:
     * the vertical edge of a cube in a diamond {@link #isoTileWidth} wide is
     * <em>half that width</em> — the classic 64&times;64 block sprite standing
     * on a 64&times;32 top face. That agrees with the tile size at 32 px only
     * because the diamond is twice it; widen the diamond and the naive formula
     * draws rhomboids instead of cubes.
     *
     * <p>Zero in a side view, which has no height axis to lift along.
     */
    public double liftScale() {
        if (perspective == Perspective.SIDE_SCROLL) return 0;
        if (boardDiamond) {
            return tileSize <= 0 ? 0 : (isoTileWidth / 2.0) / tileSize;
        }
        return Math.max(MIN_LIFT, cosPitch);
    }

    /**
     * Key the camera's picture to {@code z} world units above the floor,
     * immediately — the placement form of the height axis.
     */
    public void setFocusHeight(double z) {
        this.focusHeight = z;
        this.restHeight = z;
        this.followed = true;
    }

    /** How high the thing being followed is standing, in world units. */
    public double focusHeight() { return focusHeight; }

    /** The height the picture is keyed to, in world units; see {@link #restHeight}. */
    public double restHeight() { return restHeight; }

    /**
     * The focus's lift, in pre-zoom screen pixels.
     *
     * <p><b>Derived rather than stored, and that is a fix rather than a
     * tidy-up.</b> It used to be a screen distance set at the moment something
     * was followed — which meant it was measured against the tilt the camera
     * had <em>then</em>. Raising or lowering the camera changes how far a block
     * of height lifts something ({@link #liftScale}), so every frame that
     * tilted while a player stood on a tower drew that tower's height at the
     * previous frame's scale: the world jumped as the key was held, and jumped
     * further the higher the player was standing. Asking for the answer in
     * world units and converting at the moment of use cannot go out of step
     * with the tilt, because there is nothing left to go out of step.
     */
    public double elevation() {
        return restHeight * liftScale();
    }

    /** How the camera's height keeps up with what it follows. */
    public HeightFollow heightFollow() { return heightFollow; }

    /**
     * Choose how the height axis follows: rigidly (the default — a cutscene's
     * mark, a placed camera) or eased with slack (a played level; see
     * {@link #restHeight}).
     */
    public void setHeightFollow(HeightFollow mode) {
        this.heightFollow = mode == null ? HeightFollow.RIGID : mode;
    }

    /**
     * Advance an {@linkplain HeightFollow#EASED eased} follow by {@code dt}
     * seconds: nothing while the focus is inside the slack, and a smooth close
     * of whatever is beyond it.
     *
     * <p>A no-op in {@link HeightFollow#RIGID}, so a scene may call it
     * unconditionally.
     */
    public void stepFollow(double dt) {
        if (heightFollow != HeightFollow.EASED || dt <= 0 || !followed) return;
        double slack = Math.max(0, tileSize) * FOLLOW_SLACK_BLOCKS;
        double gap = focusHeight - restHeight;
        double over = Math.abs(gap) - slack;
        if (over <= 0) return;
        // Close the part that is past the slack, over FOLLOW_SECONDS. The
        // fraction is clamped so a long frame closes it rather than
        // overshooting into an oscillation.
        double closed = over * Math.min(1, dt / FOLLOW_SECONDS);
        restHeight += Math.signum(gap) * closed;
    }

    public void centerOn(double wx, double wy) {
        this.x = wx;
        this.y = wy;
    }

    /**
     * Follow a body: centred on {@code (wx, wy)} on the plane and carried up
     * the screen by however far {@code z} has lifted it off the floor.
     *
     * <p><b>One call, because two calls were the bug.</b> Following something
     * is two facts — where it is on the ground and how high it is standing —
     * and they were set from two different places, at two different moments,
     * by whichever call sites remembered. The results were exactly what that
     * arrangement predicts: the play scene set the lift once per <em>simulation
     * step</em> while re-centring on the plane once per <em>frame</em>, so the
     * ground scrolled smoothly and the height axis juddered at the beat
     * between the two rates; the creative play-test never set the lift at all,
     * so climbing walked the player off the top of the screen; and a cutscene
     * kept whatever lift the player happened to have been standing at.
     *
     * <p>None of those are visible while a column is eight blocks tall, which
     * is why all three survived. At {@value com.larsons.engine.level.Level#MAX_LAYERS}
     * they are the difference between a camera that follows a climbing player
     * and one that loses them. Taking both numbers together is what makes them
     * impossible to set out of step; use {@link #frameOn} for a focus that is
     * not a body.
     *
     * @param z the focus's height in world units — {@code 0} on the floor
     */
    public void follow(double wx, double wy, double z) {
        centerOn(wx, wy);
        // Measured against the last height this body was reported at, not
        // against where the camera has settled: an eased camera is *meant* to
        // be trailing the body by up to the slack, and testing that gap would
        // call every ordinary climb a teleport and snap on it.
        boolean place = !followed || heightFollow == HeightFollow.RIGID
                || Math.abs(z - focusHeight) > Math.max(0, tileSize) * FOLLOW_PLACE_BLOCKS;
        focusHeight = z;
        followed = true;
        // A door, a respawn, a teleport — or a rigid follow, which is every one
        // of them by definition. Easing into a jump the body could not have
        // made would show the world sliding in from wherever it was last.
        if (place) restHeight = z;
    }

    /**
     * Frame a point that is not a body — a cutscene's mark, a level's spawn,
     * an editor's free camera. The same as {@link #centerOn} except that it
     * says so about the height axis: whatever the camera was following before,
     * this point is on the floor, and a frame inherited from the last thing
     * followed is a frame nobody chose.
     */
    public void frameOn(double wx, double wy) {
        centerOn(wx, wy);
        setFocusHeight(0);
    }

    /**
     * Project the ground into a fixed {@code tileWidth × tileHeight} diamond
     * rather than tilting over it — the board games' projection. Also sets
     * {@link #isoTileWidth}/{@link #isoTileHeight}, since a diamond with no
     * dimensions is not one.
     *
     * <p>One way in and no way out: a camera is a board camera or a world
     * camera for its whole life, and a board that could be un-boarded is a
     * projection that can change under a layout measured against it.
     */
    public void useBoardDiamond(double tileWidth, double tileHeight) {
        this.boardDiamond = true;
        this.isoTileWidth = tileWidth;
        this.isoTileHeight = tileHeight;
    }

    /** Whether this camera draws a fixed board diamond; see {@link #useBoardDiamond}. */
    public boolean boardDiamond() { return boardDiamond; }

    /** How high the camera stands over the floor, radians; see the class note. */
    public double pitch() { return pitch; }

    /** {@link #pitch()} in degrees, which is what a HUD and a player think in. */
    public double pitchDegrees() { return Math.toDegrees(pitch); }

    /**
     * Whether the tilt reaches the picture at all here. False in a side view,
     * which has no floor to stand over, and on a board, whose diamond is fixed.
     */
    public boolean tilts() {
        return perspective != Perspective.SIDE_SCROLL && !boardDiamond;
    }

    /**
     * Raise or lower the camera to {@code radians} over the floor, clamped to
     * [{@link #MIN_PITCH}, {@link #MAX_PITCH}].
     *
     * <p>Stored whatever the perspective, like {@link #setYaw}: a camera can be
     * carried between levels of different kinds without a tilt silently
     * disappearing, and the projection simply ignores it where it means nothing.
     */
    public void setPitch(double radians) {
        double want = Math.max(MIN_PITCH, Math.min(MAX_PITCH, radians));
        this.pitch = lock.clampPitch(want);
        this.sinPitch = snap(Math.sin(this.pitch));
        this.cosPitch = snap(Math.cos(this.pitch));
    }

    /**
     * The tilt a level opens at, from the degrees it was authored with: that
     * angle, or {@link #DEFAULT_PITCH} when it has none.
     *
     * <p>A level saved before the camera could tilt carries {@code 0}, and zero
     * is not a tilt anyone could have chosen — a camera flat on the floor sees
     * nothing — so it can stand for "unwritten" without ambiguity. Here rather
     * than in each scene because both of them load levels and one of them
     * getting this wrong would open half the game at the wrong angle.
     */
    public static double pitchFor(double authoredDegrees) {
        return authoredDegrees > 0 ? Math.toRadians(authoredDegrees) : DEFAULT_PITCH;
    }

    /**
     * Move the camera {@code radians} up (positive, toward straight down) or
     * down (negative, toward edge-on) — what a held look key does, once per
     * frame. Unlike {@link #turn} there is nothing to queue and nothing to
     * animate: the tilt <em>is</em> the animation, because it rests wherever
     * the player stops rather than at eight fixed points.
     */
    public void tilt(double radians) {
        if (!tilts() || radians == 0) return;
        setPitch(detent(pitch + radians, radians > 0));
    }

    /**
     * {@link #SLICE_DETENT} applied to a tilt a key is on its way to: anything
     * under it drops to the floor, and leaving the floor upward clears it in
     * one step. Only the key goes through this; see the constant.
     */
    private static double detent(double want, boolean rising) {
        if (want <= MIN_PITCH) return MIN_PITCH;
        if (want >= SLICE_DETENT) return want;
        return rising ? SLICE_DETENT : MIN_PITCH;
    }

    /**
     * Whether the camera is flat on the floor, so the ground's depth axis has
     * collapsed onto a single screen row. See the class note — this is a view
     * of its own, and the three things it changes are listed there.
     *
     * <p>Exact rather than "nearly flat": {@link #MIN_PITCH} is where a held
     * key comes to rest and {@link #SLICE_DETENT} is what keeps a player from
     * stopping just above it, so the condition is reached by doing the obvious
     * thing rather than by landing on a tolerance.
     */
    public boolean sliced() {
        return tilts() && sinPitch == 0;
    }

    /**
     * How far along the view axis a ground point lies, in world units —
     * <em>positive toward the camera</em>, so a larger number is nearer.
     *
     * <p>This is what "nearer" has always meant on a plane. The painters used
     * to ask for the projected screen row instead, which is this multiplied by
     * {@code sin(pitch)} and shifted: the same ordering at every tilt the
     * camera used to have, and no ordering at all at the one it has now. The
     * screen row was a copy of this number, not the thing itself.
     */
    public double viewDepth(double wx, double wy) {
        if (perspective == Perspective.SIDE_SCROLL) return wy;
        if (boardDiamond) {
            // The diamond folds both world axes into its screen row, and that
            // fold *is* its depth — the board is drawn from a fixed angle and
            // has no separate view axis to measure along.
            return planar(wx, wy)[1];
        }
        return -wx * sinYaw + wy * cosYaw;
    }

    /**
     * A displacement of {@code depth} world units along the view axis, in world
     * coordinates — the inverse of {@link #viewDepth} applied to a distance
     * rather than a point.
     *
     * <p>Positive is toward the camera, matching {@link #viewDepth}, so a
     * negative distance is the direction "further away" points in the world.
     * What a caller reaching behind the focus needs, and the one place the view
     * axis is written out as a world vector.
     */
    public double[] inverseViewAxis(double depth) {
        if (perspective == Perspective.SIDE_SCROLL) return new double[]{0, depth};
        return new double[]{depth * -sinYaw, depth * cosYaw};
    }

    /**
     * {@link #viewDepth} as the whole-number key a {@link DepthPass} sorts on,
     * at this camera's zoom.
     *
     * <p>Quantised for the same reason the projection is: two things a hair
     * apart in depth should tie rather than flicker between orders as the
     * camera moves. At the zoom it is taken at, one unit is one screen pixel of
     * the unforeshortened plane.
     */
    public int depthOf(double wx, double wy) {
        double d = viewDepth(wx, wy) * zoom;
        if (d > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (d < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(d);
    }

    /**
     * Whether the camera has been raised far enough that the world reads as
     * seen from overhead — {@link #OVERHEAD_PITCH} or higher. What decides
     * which pool of art a character is drawn from.
     */
    public boolean overhead() {
        // The tolerance is what makes "75 degrees" mean 75 degrees: the tilt
        // arrives as a sum of per-frame steps, so the frame that lands on the
        // threshold lands a few ulps either side of it.
        return tilts() && pitch >= OVERHEAD_PITCH - 1e-9;
    }

    /** The camera's heading, radians clockwise from world north. */
    public double yaw() { return yaw; }

    /**
     * Put the camera at {@code radians} immediately, clockwise from world
     * north, cancelling any snap in flight.
     *
     * <p>Has no effect on the picture in {@link Perspective#SIDE_SCROLL} — the
     * value is still stored, so a camera can be carried between levels of
     * different formats without a heading silently disappearing, but the
     * projection ignores it. See the class note.
     *
     * <p>This is the teleport: loading a level at its authoring heading (C9),
     * or a test placing the camera. {@link #turn} is what a player does, and
     * the two must not be in flight at once — a set that left the animation
     * running would be overwritten by it a frame later.
     */
    public void setYaw(double radians) {
        int want = Math.floorMod((int) Math.round(radians / EIGHTH_TURN), 8);
        int allowed = lock.nearestHeading(want);
        // Placed at the compass point rather than at the angle asked for when
        // the level has ruled that heading out — the nearest one it does allow,
        // exactly, so the camera still rests square to the world.
        double at = allowed == want ? radians : allowed * EIGHTH_TURN;
        placeYaw(at);
        this.targetYaw = at;
        this.heading = allowed;
        this.turning = false;
        this.queued = 0;
    }

    /** Where the level lets this camera stand; never {@code null}. */
    public CameraLock lock() { return lock; }

    /**
     * Put this camera under a level's rules, and bring it inside them now
     * rather than at the next press: a camera left at a heading or tilt the new
     * level forbids is a camera showing a picture that level says it does not
     * have.
     */
    public void setLock(CameraLock lock) {
        this.lock = lock == null ? CameraLock.free() : lock;
        setYaw(heading * EIGHTH_TURN);
        setPitch(pitch);
    }

    /** The heading itself, with none of the bookkeeping {@link #setYaw} does. */
    private void placeYaw(double radians) {
        this.yaw = radians;
        this.cosYaw = snap(Math.cos(radians));
        this.sinYaw = snap(Math.sin(radians));
    }

    /** The heading {@link #yaw} is easing towards. */
    public double targetYaw() { return targetYaw; }

    /** The compass point the camera is settled on or turning to, 0–7. */
    public int heading() { return heading; }

    /** Whether a snap is in flight — the camera is between compass points. */
    public boolean turning() { return turning; }

    /**
     * How long one eighth-turn takes.
     *
     * <p>Short enough to feel like a response to a key rather than a cutscene,
     * long enough to read as a camera turning rather than the world being
     * replaced. The animation is the whole point of the step: without it an
     * eight-point camera is a teleport, and a player loses track of which way
     * they were facing.
     */
    public static final double SNAP_SECONDS = 0.22;

    /**
     * Turn one compass point — the press of a rotate key.
     *
     * <p>{@code eighths} is a direction rather than an amount: any positive
     * value turns the camera one point clockwise (to the player's right, so the
     * world swings left), any negative value one point anticlockwise. Pressing
     * during a snap queues at most one more; see {@link #queued}.
     */
    public void turn(int eighths) {
        if (!rotates() || eighths == 0) return;
        // A level that allows one heading has no turn to make; one that allows
        // some of the eight turns between those, which is why this asks the
        // lock for the next heading rather than testing the one it wanted.
        if (lock.headingLocked()) return;
        int step = eighths > 0 ? 1 : -1;
        if (turning) {
            if (queued == 0) queued = step;
            return;
        }
        beginTurn(step);
    }

    /** How many compass points a {@code step}-wards turn from {@code from} covers. */
    private static int eighthsBetween(int from, int to, int step) {
        int n = Math.floorMod((to - from) * (step >= 0 ? 1 : -1), 8);
        return n == 0 ? 8 : n;
    }

    private void beginTurn(int step) {
        int from = heading;
        heading = lock.nextHeading(heading, step);
        if (heading == from) return;
        targetYaw = heading * EIGHTH_TURN;
        turnFrom = yaw;
        // The angle actually eased to, which is not targetYaw when the turn
        // crosses north: going clockwise from 315° the camera must travel
        // forward to 360° and not backwards through seven eighths of a circle
        // to 0°. They are the same heading and only one of them is the way
        // round the player pressed for.
        //
        // How far, rather than one eighth: a level that forbids a heading makes
        // the camera step over it, and an animation that travelled one eighth
        // regardless would end up somewhere neither heading is.
        turnTo = yaw + step * EIGHTH_TURN * eighthsBetween(from, heading, step);
        turnElapsed = 0;
        turning = true;
    }

    /**
     * Advance the snap by {@code dt} seconds.
     *
     * <p><b>The heading is assigned rather than integrated at the end of a
     * turn.</b> Easing toward a target and stopping when the difference is
     * small enough leaves the camera resting a hair off a compass point, and
     * that hair is the difference between {@code TerrainCache} baking the floor
     * and refusing to (C3), between a tile texture being an upright blit and a
     * warp (C4), and between the cardinal headings being exact axis swaps and
     * being rotations by 6e-17 radians (C1). So the last frame of a snap sets
     * {@code yaw} to the compass point itself, from the whole-number heading,
     * and every consumer of "is this camera square to the world" gets an exact
     * answer instead of a nearly one.
     *
     * <p><b>{@link #yaw()} can therefore step by a whole turn at the instant a
     * snap settles, and the picture does not move.</b> Turning anticlockwise
     * from north the animation runs 0° → −45°, and the heading it lands on is
     * seven eighths, so the number jumps from −45° to 315° in the frame it
     * arrives. They are the same direction; {@link #snap} gives them the same
     * cosine and sine to the last bit, and everything downstream reads either
     * those or the heading rounded to an eighth. It is the representation
     * wrapping, not the camera — but anything measuring how far the camera
     * turned by subtracting two yaws has to fold the difference into a half
     * turn, and the alternative (letting the number run on unbounded) is what
     * {@link #heading} exists to avoid.
     */
    public void stepYaw(double dt) {
        if (!turning || dt <= 0) return;
        turnElapsed += dt;
        while (turning && turnElapsed >= SNAP_SECONDS) {
            double carry = turnElapsed - SNAP_SECONDS;
            placeYaw(heading * EIGHTH_TURN);
            targetYaw = yaw;
            turning = false;
            if (queued != 0) {
                int next = queued;
                queued = 0;
                beginTurn(next);
                turnElapsed = carry;
            }
        }
        if (turning) {
            double t = turnElapsed / SNAP_SECONDS;
            placeYaw(turnFrom + (turnTo - turnFrom) * ease(t));
        }
    }

    /**
     * Smoothstep: the turn starts and ends at rest. A linear sweep stops dead
     * at the compass point and reads as the world being yanked; easing both
     * ends is what makes it read as a camera someone is turning.
     */
    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Whether yaw reaches the picture at all in this perspective. */
    public boolean rotates() { return perspective != Perspective.SIDE_SCROLL; }

    /**
     * The heading the picture is actually drawn at: {@link #yaw()} where the
     * perspective turns, and zero where it does not.
     *
     * <p>What anything outside this class should ask when it wants to turn
     * something with the camera — a sprite's apparent direction, an input
     * vector, a ground-plane offset. {@link #yaw()} is what the camera was
     * <em>told</em>, and a side-scroller can be told a heading it does not use;
     * asking the wrong one of the two turns a side-scroller's sprites while its
     * world stays put, which is the least obvious way to get this wrong and the
     * easiest to write.
     */
    public double viewYaw() { return rotates() ? yaw : 0; }

    /**
     * A cosine or sine rounded to the exact value the heading means.
     *
     * <p>{@code Math.cos(Math.PI / 2)} is 6.1e-17, not zero. Left alone, a
     * quarter turn would be a rotation by 6.1e-17 radians rather than an exact
     * axis swap, and the projection at the four cardinal headings would no
     * longer be the unrotated one with its axes exchanged. That costs nothing
     * visible — 6.1e-17 rad over a large level is 1e-11 px — but the exactness
     * is worth having for the headings the camera actually rests at: it is what
     * lets a golden frame at 90° be the transpose of one at 0°, and what keeps
     * the world's pixel lattice (see the class note) exactly the lattice it was.
     */
    private static double snap(double v) {
        if (Math.abs(v) < 1e-12) return 0.0;
        if (Math.abs(v - 1.0) < 1e-12) return 1.0;
        if (Math.abs(v + 1.0) < 1e-12) return -1.0;
        return v;
    }

    /**
     * Planar projection of a world point, before zoom/centering: the whole of
     * the perspective and the yaw, and none of the camera, the zoom or the
     * rounding.
     *
     * <p>Public because the rotated view needs it. The visible region of the
     * world stops being a rectangle of cells the moment the camera turns, so
     * anything deciding what to sweep has to project the corners itself.
     */
    public double[] planar(double wx, double wy) {
        if (perspective == Perspective.SIDE_SCROLL) return new double[]{wx, wy};

        // Yaw first, on the ground plane: the camera turns around the world's
        // vertical axis, not around the screen's. The rotation is the inverse
        // of the heading — the camera turns right, the world swings left.
        double rx = wx * cosYaw + wy * sinYaw;
        double ry = -wx * sinYaw + wy * cosYaw;

        if (boardDiamond) {
            double tx = rx / tileSize;
            double ty = ry / tileSize;
            return new double[]{
                    (tx - ty) * (isoTileWidth / 2.0),
                    (tx + ty) * (isoTileHeight / 2.0)
            };
        }
        // Then the tilt, on the turned plane: the depth axis is the one the
        // camera is looking along, so that is the one it foreshortens.
        return new double[]{rx, ry * sinPitch};
    }

    /** The exact inverse of {@link #planar}, undone in the opposite order. */
    public double[] inversePlanar(double px, double py) {
        if (perspective == Perspective.SIDE_SCROLL) return new double[]{px, py};

        double rx, ry;
        if (boardDiamond) {
            double a = px / (isoTileWidth / 2.0);
            double b = py / (isoTileHeight / 2.0);
            rx = (a + b) / 2.0 * tileSize;
            ry = (b - a) / 2.0 * tileSize;
        } else {
            rx = px;
            // A collapsed floor carries no depth: every depth projects to the
            // same row, so the row says nothing about it and the honest answer
            // is none of it. That makes this the projection's pseudo-inverse
            // rather than its inverse — still exact on the component that
            // survived, and still linear, which is what the callers that use it
            // on a *direction* rather than a point depend on (a step of zero is
            // the right step: at this tilt a raised block draws over its own
            // cell and no other). {@link #screenToWorld} is where a point gets
            // its depth back.
            ry = sinPitch == 0 ? 0 : py / sinPitch;
        }
        return new double[]{
                rx * cosYaw - ry * sinYaw,
                rx * sinYaw + ry * cosYaw
        };
    }

    public int worldToScreenX(double wx, double wy) {
        double[] p = planar(wx, wy);
        double[] c = planar(x, y);
        return place(p[0], c[0], viewportWidth, false);
    }

    public int worldToScreenY(double wx, double wy) {
        double[] p = planar(wx, wy);
        double[] c = planar(x, y);
        return place(p[1], c[1], viewportHeight, true);
    }

    /**
     * One axis of step 2: a projected world coordinate placed on the pixel
     * lattice, plus the camera's single offset onto it.
     *
     * <p>The class note has the argument for why this is two roundings rather
     * than one. The arithmetic is in {@code long} because the first term is a
     * whole-world coordinate rather than a screen-relative one — a point far off
     * the edge of a large level projects to a number a screen never holds, and
     * callers do ask about those when they are deciding what to cull.
     *
     * @param planar       the world point, projected, before zoom
     * @param planarCamera the camera's focus, projected the same way
     * @param viewport     the viewport's extent on this axis
     */
    /**
     * The camera's single offset onto the pixel lattice, for one axis.
     *
     * <p>Extracted so the forward projection and {@link #screenToWorld} cannot
     * arrive at different numbers. They used to spell the same expression out
     * twice, which was safe only while the expression stayed one line: the
     * elevation term below would have gone into the forward direction and not
     * the inverse, and a click would have landed a whole tower's worth of
     * pixels from the block under the cursor.
     *
     * <p><b>Which axis this is, is a fact about the axis and not about how big
     * it happens to be.</b> This used to decide by asking whether the extent it
     * had been handed equalled {@link #viewportHeight} — true for the vertical
     * axis, and also true for the horizontal one <em>whenever the window was
     * square</em>. A square window therefore applied the focus's lift to
     * {@code x} as well as to {@code y}, and the whole world slid sideways as a
     * player climbed. Nothing caught it because nothing had a reason to: the
     * lift is zero in every level that has not switched its height axis on, and
     * a square viewport is unusual outside a test. Raising the ceiling makes
     * both of those ordinary.
     */
    private long offsetFor(double planarCamera, int viewport, boolean vertical) {
        long offset = Math.round(viewport / 2.0 - planarCamera * zoom);
        // The focus's own lift, on the axis that carries it. Applied to the
        // camera's offset rather than to each point, so the whole world slides
        // together and the pixel lattice the class note is about is untouched.
        if (vertical) offset += Math.round(elevation() * zoom);
        return offset;
    }

    private int place(double planar, double planarCamera, int viewport, boolean vertical) {
        long lattice = Math.round(planar * zoom);
        long offset = offsetFor(planarCamera, viewport, vertical);
        long screen = lattice + offset;
        // Saturating rather than wrapping. A clipped-off coordinate draws
        // nothing, which is what was wanted; a wrapped one draws it on the
        // opposite side of the screen.
        if (screen > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (screen < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) screen;
    }

    /**
     * Project a world point into {@code out[0]} (screen x) and {@code out[1]}
     * (screen y) with no allocation — one projection instead of the two that
     * separate {@link #worldToScreenX}/{@link #worldToScreenY} calls cost.
     * This is the hot path for tile rendering (four corners per tile).
     */
    public void worldToScreen(double wx, double wy, int[] out) {
        double px, py, cx, cy;
        if (perspective == Perspective.SIDE_SCROLL) {
            px = wx;
            py = wy;
            cx = x;
            cy = y;
        } else {
            // The same two steps as planar(), inlined for both points at once.
            // They must stay the same two steps: this is the tile path and
            // planar() is the picking path, and a disagreement between them is
            // a mouse click landing on the wrong block.
            double rx = wx * cosYaw + wy * sinYaw;
            double ry = -wx * sinYaw + wy * cosYaw;
            double rcx = x * cosYaw + y * sinYaw;
            double rcy = -x * sinYaw + y * cosYaw;
            if (boardDiamond) {
                double hw = isoTileWidth / 2.0, hh = isoTileHeight / 2.0;
                double tx = rx / tileSize, ty = ry / tileSize;
                px = (tx - ty) * hw;
                py = (tx + ty) * hh;
                double ctx = rcx / tileSize, cty = rcy / tileSize;
                cx = (ctx - cty) * hw;
                cy = (ctx + cty) * hh;
            } else {
                px = rx;
                py = ry * sinPitch;
                cx = rcx;
                cy = rcy * sinPitch;
            }
        }
        out[0] = place(px, cx, viewportWidth, false);
        out[1] = place(py, cy, viewportHeight, true);
    }

    /**
     * A world-plane displacement in projected (pre-zoom) screen units — the
     * projection applied to a direction rather than to a point. A vector on the
     * floor keeps pointing the same way across the floor when the camera turns
     * the grid into a diamond, which is what a cast shadow or any other
     * ground-plane direction needs.
     *
     * <p>Yaw comes free here, and by construction rather than by luck: rotation
     * is linear and fixes the origin, so a direction routed through this one
     * swings with the camera without this method knowing a camera can turn.
     */
    public double[] planarDelta(double dx, double dy) {
        double[] a = planar(0, 0);
        double[] b = planar(dx, dy);
        return new double[]{b[0] - a[0], b[1] - a[1]};
    }

    /**
     * Inverse mapping: screen pixel back to world coordinates.
     *
     * <p>Inverted against the same camera offset the forward direction adds,
     * rather than against the un-rounded {@code viewport/2 - camera * zoom} it
     * approximates. The two differ by less than a pixel, and less than a pixel
     * is exactly the size of the disagreement that puts a placed block in the
     * wrong cell when a creative-mode stroke lands on a tile boundary. The
     * remaining error is the lattice rounding in the forward direction, which no
     * inverse can undo and which is sub-pixel by construction.
     */
    public double[] screenToWorld(int sx, int sy) {
        double[] c = planar(x, y);
        double px = (sx - offsetFor(c[0], viewportWidth, false)) / zoom;
        double py = (sy - offsetFor(c[1], viewportHeight, true)) / zoom;
        double[] w = inversePlanar(px, py);
        if (sliced()) {
            // The screen point is a whole line of the world here, so this is a
            // choice rather than an inverse — and the choice is the slice the
            // camera is looking at, which is the only one a click can mean. The
            // pseudo-inverse left the point at depth zero; carrying it along the
            // view axis to the focus's depth is what puts it on that slice.
            double depth = viewDepth(x, y);
            w[0] += depth * -sinYaw;
            w[1] += depth * cosYaw;
        }
        return w;
    }
}
