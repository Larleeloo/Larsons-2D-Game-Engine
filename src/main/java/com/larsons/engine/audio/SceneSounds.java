package com.larsons.engine.audio;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The sounds that come from <em>watching</em> the world rather than from a
 * single event: footsteps while the player walks, the swim loop while they
 * are in water, a sustained ultimate's hum, the roar of a meteor on its way
 * down, the ambience under a cave, and the level's music.
 *
 * <p>One-shot events — a block breaking, a mob dying, a shot being fired —
 * are played where they happen with
 * {@link com.larsons.engine.config.GameContext#sound(String)}. This class
 * exists for everything that has to be tracked frame to frame: it remembers
 * what the player was doing last tick, so it can tell "landed" from "still on
 * the ground" and "just went under" from "still swimming", and it owns the
 * looping voices so they can be stopped when the state ends.
 *
 * <p>The play scene and creative mode's play-test each keep one, so a level
 * sounds identical whether it is being played or tested.
 *
 * <p>Every sound it reaches for falls back gracefully: a footstep asks for
 * the block underfoot first ({@code block/stone/step}), then the player's own
 * step ({@code player/walk}), and lands on silence if the pack supplies
 * neither — which is the default for all of it.
 */
public final class SceneSounds {

    /** Seconds between footsteps at a walk; sprinting is quicker. */
    private static final double WALK_STEP_INTERVAL = 0.42;
    private static final double RUN_STEP_INTERVAL = 0.28;
    private static final double SWIM_STROKE_INTERVAL = 0.75;
    /** Seconds between a walking mob's footsteps. */
    private static final double MOB_STEP_INTERVAL = 0.5;
    /** Seconds between a mob's idle noises, so a crowd doesn't chatter. */
    private static final double MOB_IDLE_INTERVAL = 6.0;
    /** Mobs beyond this many screen-halves away are not heard at all. */
    private static final double MOB_EARSHOT = 1.6;

    // The ambience keys, built once: ambience() is called every frame and
    // almost always wants the same one it wanted last frame.
    private static final String AMBIENT_DAY = SoundKeys.ambient("day");
    private static final String AMBIENT_NIGHT = SoundKeys.ambient("night");
    private static final String AMBIENT_CAVE = SoundKeys.ambient("cave");

    /** The character whose voice is used; blank is the default player. */
    private String characterKey = "";
    private boolean enabled = true;

    // Player state as of the previous frame, so changes can be spotted.
    private String lastState = "";
    private boolean wasSwimming;
    private boolean wasAirborne;
    private boolean wasSprinting;
    private boolean wasUltCharged;
    private String activeUltimate = "";
    private double lastHealth = -1;
    private double stepTimer;

    // Looping voices this owns and must stop when the state they belong to
    // ends. A voice is dropped by the mixer on its own once stopped.
    private SoundMixer.Voice swimVoice;
    private SoundMixer.Voice ultVoice;
    private SoundMixer.Voice ambientVoice;
    private String ambientKey = "";
    private final Map<Integer, SoundMixer.Voice> flightVoices = new HashMap<>();
    /** Each mob's last seen AI state, so entering a state can be heard. */
    private final Map<Integer, Mob.AIState> mobStates = new HashMap<>();
    private double mobStepTimer;
    private double mobIdleTimer;

    /** Whose voice to use for the player's own sounds. */
    public void setCharacter(String key) {
        this.characterKey = key == null ? "" : key;
    }

    /** Master gate, pushed from the profile's audio toggle each frame. */
    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) stopLoops();
    }

    /**
     * Watch one frame of play and make whatever noise it calls for.
     *
     * @param actionState what the player is doing, from
     *                    {@link com.larsons.engine.graphics.PlayerSprites#actionState}
     * @param shots       the projectiles in the air (may be empty), whose
     *                    flight loops are started and stopped here
     * @param halfWidth   half the visible world width, for panning; {@code 0}
     *                    to leave everything centred
     */
    public void update(double dt, PlayerState me, Level level, GameProfile profile,
                       String actionState, List<Projectile> shots, List<Mob> mobs,
                       double halfWidth) {
        if (!enabled || me == null || level == null) return;
        boolean swimming = "swim".equals(actionState);
        boolean airborne = "jump".equals(actionState) || "fall".equals(actionState);
        boolean sprinting = "run".equals(actionState);

        footsteps(dt, me, level, profile, actionState, swimming, sprinting);
        water(swimming, me, level, profile);
        landing(airborne, me, level, profile);
        sprint(sprinting);
        health(me);
        ultimate(me);
        flight(shots, me, halfWidth);
        mobs(dt, mobs, me, halfWidth);

        lastState = actionState;
        wasSwimming = swimming;
        wasAirborne = airborne;
        wasSprinting = sprinting;
    }

    /**
     * The level's music, and the ambience that plays under it. Safe to call
     * every frame: asking for what is already playing does nothing.
     */
    public void ambience(Level level, boolean night, boolean underground) {
        if (!enabled) {
            Sounds.stopMusic();
            return;
        }
        Sounds.music(level == null ? "music/level" : level.musicKey());
        String want = underground ? AMBIENT_CAVE : night ? AMBIENT_NIGHT : AMBIENT_DAY;
        if (want.equals(ambientKey) && ambientVoice != null && ambientVoice.isPlaying()) return;
        if (ambientVoice != null) ambientVoice.stop();
        ambientKey = want;
        ambientVoice = Sounds.play(want, 0.6);
    }

    /** Footsteps, timed to the gait — quicker sprinting, slower swimming. */
    private void footsteps(double dt, PlayerState me, Level level, GameProfile profile,
                           String state, boolean swimming, boolean sprinting) {
        boolean stepping = swimming
                || (me.moving && ("walk".equals(state) || "run".equals(state)));
        if (!stepping) {
            // Reset just short of the interval, so the first step of a walk
            // lands almost at once rather than after a silent beat.
            stepTimer = Math.max(0, interval(swimming, sprinting) - 0.08);
            return;
        }
        stepTimer += dt;
        double interval = interval(swimming, sprinting);
        if (stepTimer < interval) return;
        stepTimer -= interval;
        if (swimming) {
            Sounds.playFirst(0.6, character("swim"), SoundKeys.player("swim"));
            return;
        }
        // The block underfoot gets first say, so a stone floor and a wooden
        // walkway sound different without either needing a player sound.
        Block under = blockUnder(me, level, profile);
        String generic = sprinting ? "run" : "walk";
        if (under != null) {
            Sounds.playFirst(0.5, SoundKeys.block(under.key(), "step"),
                    character(generic), SoundKeys.player(generic));
        } else {
            Sounds.playFirst(0.5, character(generic), SoundKeys.player(generic));
        }
    }

    private static double interval(boolean swimming, boolean sprinting) {
        return swimming ? SWIM_STROKE_INTERVAL
                : sprinting ? RUN_STEP_INTERVAL : WALK_STEP_INTERVAL;
    }

    /** Entering and leaving a liquid, and the loop while under it. */
    private void water(boolean swimming, PlayerState me, Level level, GameProfile profile) {
        if (swimming == wasSwimming) return;
        Block liquid = liquidAt(me, level, profile);
        if (swimming) {
            String splash = liquid != null ? SoundKeys.block(liquid.key(), "splash") : "";
            Sounds.playFirst(1.0, splash, character("swim_enter"),
                    SoundKeys.player("swim_enter"));
            if (swimVoice != null) swimVoice.stop();
            // The liquid's own loop if it has one, else the player's swim
            // sound looping — either way it stops when they surface.
            String loop = liquid != null ? SoundKeys.block(liquid.key(), "swim") : "";
            swimVoice = Sounds.playLoopFirst(0.5, loop, SoundKeys.player("swim"));
        } else {
            Sounds.playFirst(0.8, character("swim_exit"), SoundKeys.player("swim_exit"));
            if (swimVoice != null) swimVoice.stop();
            swimVoice = null;
        }
    }

    /** Touching down after a jump or a fall. */
    private void landing(boolean airborne, PlayerState me, Level level, GameProfile profile) {
        if (airborne || !wasAirborne) return;
        Block under = blockUnder(me, level, profile);
        String step = under != null ? SoundKeys.block(under.key(), "step") : "";
        Sounds.playFirst(0.9, character("land"), SoundKeys.player("land"), step);
    }

    private void sprint(boolean sprinting) {
        if (sprinting && !wasSprinting) {
            Sounds.playFirst(0.7, character("sprint_start"), SoundKeys.player("sprint_start"));
        }
    }

    /** Being hurt, and dying — spotted from the health bar moving. */
    private void health(PlayerState me) {
        if (lastHealth < 0) {
            lastHealth = me.health;
            return;
        }
        if (me.health < lastHealth - 0.01) {
            if (me.health <= 0) {
                Sounds.playFirst(1.0, character("die"), SoundKeys.player("die"));
            } else {
                Sounds.playFirst(1.0, character("hurt"), SoundKeys.player("hurt"));
            }
        } else if (me.health > lastHealth + 0.01 && lastHealth <= 0) {
            Sounds.playFirst(1.0, character("respawn"), SoundKeys.player("respawn"));
        }
        lastHealth = me.health;
    }

    /**
     * An ultimate's whole arc: the meter filling, the cast, the sustained
     * body of the effect, and the effect ending. The cast itself is played by
     * whoever fires it (which knows whether it succeeded); this owns the
     * "charged" chime and the loop.
     */
    private void ultimate(PlayerState me) {
        boolean charged = me.ultimateEnabled && me.ultCharge >= 1 - 1e-6;
        if (charged && !wasUltCharged) {
            Sounds.playFirst(0.8, SoundKeys.ultimate(me.ultimateKey, "charged"),
                    character("ult_charged"), SoundKeys.player("ult_charged"));
        }
        wasUltCharged = charged;

        boolean running = me.ultActive > 0 && !me.ultActiveKey.isEmpty();
        if (running && !me.ultActiveKey.equals(activeUltimate)) {
            if (ultVoice != null) ultVoice.stop();
            activeUltimate = me.ultActiveKey;
            ultVoice = Sounds.playLoopFirst(0.7,
                    SoundKeys.ultimate(activeUltimate, "loop"), SoundKeys.player("ult_loop"));
        } else if (!running && !activeUltimate.isEmpty()) {
            if (ultVoice != null) ultVoice.stop();
            ultVoice = null;
            Sounds.playFirst(0.8, SoundKeys.ultimate(activeUltimate, "end"),
                    SoundKeys.player("ult_end"));
            activeUltimate = "";
        }
    }

    /**
     * The sound of a shot on its way: started when a projectile appears and
     * stopped when it lands, which is what gives a called-down meteor its
     * falling roar between the cast and the crash.
     */
    private void flight(List<Projectile> shots, PlayerState me, double halfWidth) {
        if (shots == null) {
            stopFlights();
            return;
        }
        for (Projectile p : shots) {
            if (p.dead() || flightVoices.containsKey(p.id)) continue;
            SoundMixer.Voice v = Sounds.playLoop(
                    SoundKeys.projectile(p.def.key(), "flight"), 0.6,
                    pan(p.x - me.x, halfWidth));
            // Remember the id either way: a null voice (no audio for this
            // shot) must not be retried every frame it is in the air.
            flightVoices.put(p.id, v);
        }
        if (flightVoices.isEmpty()) return;
        for (Iterator<Map.Entry<Integer, SoundMixer.Voice>> it =
                flightVoices.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, SoundMixer.Voice> e = it.next();
            if (stillFlying(shots, e.getKey())) continue;
            if (e.getValue() != null) e.getValue().stop();
            it.remove();
        }
    }

    /**
     * The mobs: a cry as one spawns, its lunge as it goes for the player, its
     * footfalls while it closes, its death, and the occasional idle noise
     * from the ones nearby. Everything is positioned and faded by distance,
     * so a horde off-screen is a murmur rather than a wall of sound.
     */
    private void mobs(double dt, List<Mob> mobs, PlayerState me, double halfWidth) {
        if (mobs == null || mobs.isEmpty()) {
            mobStates.clear();
            return;
        }
        mobStepTimer -= dt;
        mobIdleTimer -= dt;
        boolean stepDue = mobStepTimer <= 0;
        boolean idleDue = mobIdleTimer <= 0;
        boolean stepped = false;
        boolean idled = false;

        double reach = halfWidth > 0 ? halfWidth * MOB_EARSHOT : Double.MAX_VALUE;
        for (Mob m : mobs) {
            double dx = m.x - me.x;
            double dy = m.y - me.y;
            // Out of earshot: still tracked (so a kill offscreen isn't heard
            // as a spawn when it wanders back), just never sounded.
            boolean audible = Math.hypot(dx, dy) <= reach;

            Mob.AIState was = mobStates.put(m.id, m.state);
            if (was == null && audible) {
                Sounds.playAt(SoundKeys.mob(m.def.key(), "spawn"), dx, dy, halfWidth, 0.8);
            } else if (was != m.state && audible) {
                if (m.state == Mob.AIState.ATTACK) {
                    Sounds.playAt(SoundKeys.mob(m.def.key(), "attack"), dx, dy, halfWidth);
                } else if (m.state == Mob.AIState.DEAD) {
                    Sounds.playAt(SoundKeys.mob(m.def.key(), "death"), dx, dy, halfWidth);
                }
            }
            if (!audible || m.dead()) continue;
            // One mob per tick makes each noise, so ten slimes chasing you
            // are a patter of footsteps rather than ten simultaneous ones.
            if (stepDue && !stepped && m.state != Mob.AIState.IDLE) {
                stepped = true;
                Sounds.playAt(SoundKeys.mob(m.def.key(), "step"), dx, dy, halfWidth, 0.4);
            } else if (idleDue && !idled && m.state == Mob.AIState.IDLE) {
                idled = true;
                Sounds.playAt(SoundKeys.mob(m.def.key(), "idle"), dx, dy, halfWidth, 0.5);
            }
        }
        if (stepDue) mobStepTimer = MOB_STEP_INTERVAL;
        if (idleDue) mobIdleTimer = MOB_IDLE_INTERVAL;
        // Mobs that are gone (killed and cleared) stop being remembered.
        for (Iterator<Integer> it = mobStates.keySet().iterator(); it.hasNext(); ) {
            if (!stillPresent(mobs, it.next())) it.remove();
        }
    }

    private static boolean stillFlying(List<Projectile> shots, int id) {
        for (Projectile p : shots) {
            if (p.id == id && !p.dead()) return true;
        }
        return false;
    }

    private static boolean stillPresent(List<Mob> mobs, int id) {
        for (Mob m : mobs) {
            if (m.id == id) return true;
        }
        return false;
    }

    private static double pan(double dx, double halfWidth) {
        if (halfWidth <= 0) return 0;
        return Math.max(-1, Math.min(1, dx / halfWidth));
    }

    /** This character's key for an action state, or the plain player's. */
    private String character(String state) {
        return SoundKeys.character(characterKey, state);
    }

    /** The block the player is standing on, or {@code null} over open air. */
    private static Block blockUnder(PlayerState me, Level level, GameProfile profile) {
        double size = me.hitSize(profile.playerSize);
        int col = (int) Math.floor((me.x + size / 2.0) / level.tileSize);
        int row = (int) Math.floor((me.y + size + 2) / level.tileSize);
        return level.blockAt(col, row);
    }

    /** The liquid the player is in, or {@code null} when they are dry. */
    private static Block liquidAt(PlayerState me, Level level, GameProfile profile) {
        double size = me.hitSize(profile.playerSize);
        return level.liquidAt((int) Math.floor((me.x + size / 2.0) / level.tileSize),
                (int) Math.floor((me.y + size / 2.0) / level.tileSize));
    }

    /** Stop every loop this owns — leaving a level, or ending a play-test. */
    public void stopLoops() {
        if (swimVoice != null) swimVoice.stop();
        if (ultVoice != null) ultVoice.stop();
        if (ambientVoice != null) ambientVoice.stop();
        swimVoice = ultVoice = ambientVoice = null;
        ambientKey = "";
        activeUltimate = "";
        stopFlights();
    }

    private void stopFlights() {
        for (SoundMixer.Voice v : flightVoices.values()) {
            if (v != null) v.stop();
        }
        flightVoices.clear();
    }

    /**
     * Silence everything and forget the previous frame — so re-entering a
     * level doesn't fire "landed" or "hurt" from the state it was left in,
     * and no one-shot carries over into whatever comes next. Both the play
     * scene and the play-test tear down with this one call, so neither can
     * leave something ringing that the other stops.
     */
    public void reset() {
        stopLoops();
        Sounds.stopAll();
        lastState = "";
        wasSwimming = wasAirborne = wasSprinting = wasUltCharged = false;
        lastHealth = -1;
        stepTimer = 0;
        mobStates.clear();
        mobStepTimer = mobIdleTimer = 0;
    }

}
