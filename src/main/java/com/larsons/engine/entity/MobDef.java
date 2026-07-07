package com.larsons.engine.entity;

import java.awt.Color;

/**
 * One mob species definition. Ported from the Side-Scroller engine, where each
 * mob was its own class ({@code ZombieMob}, {@code WolfMob}, …23 files);
 * merged into a data row so games add species by registering data (see
 * {@link MobRegistry}). Sprites are procedural (body/accent colours + shape
 * hints) since the engine ships no image assets.
 *
 * @param key         stable string key ("zombie"), stored in levels + wire
 * @param displayName palette name
 * @param body        main sprite colour
 * @param accent      secondary sprite colour (eyes/belly)
 * @param size        world-pixel size (square)
 * @param speed       walk speed, px/sec
 * @param maxHealth   hit points
 * @param damage      contact/attack damage to players
 * @param temperament HOSTILE chases players; NEUTRAL retaliates only;
 *                    PASSIVE flees when hurt
 * @param detectRange world px within which hostiles notice a player
 * @param attackRange world px within which an attack lands
 * @param flying      ignores gravity (bat, dragon)
 */
public record MobDef(String key, String displayName, Color body, Color accent,
                     double size, double speed, double maxHealth, double damage,
                     Temperament temperament, double detectRange,
                     double attackRange, boolean flying) {

    /** Ported from the side-scroller's mob registry categories. */
    public enum Temperament { HOSTILE, NEUTRAL, PASSIVE }

    public static MobDef hostile(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp, double dmg) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.HOSTILE, 220, 34, false);
    }

    public static MobDef passive(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp) {
        return new MobDef(key, name, body, accent, size, speed, hp, 0,
                Temperament.PASSIVE, 160, 0, false);
    }

    public static MobDef neutral(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp, double dmg) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.NEUTRAL, 180, 30, false);
    }
}
