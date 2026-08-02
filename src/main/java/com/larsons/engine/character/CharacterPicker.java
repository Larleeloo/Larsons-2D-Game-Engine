package com.larsons.engine.character;

import com.larsons.engine.graphics.DirectionalSprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The "who are you playing as?" screen shown when a level starts: a row of
 * cards, one per character profile the level's creator put on its roster, each
 * showing the character's sprite and their traits. Arrow keys or the mouse
 * choose, Enter or a click confirms.
 *
 * <p>It is a self-contained widget rather than a scene so the same picker
 * appears in "load level" play and in the creative editor's play-test — a
 * creator testing a level picks from the same roster a player will see.
 *
 * <p>A roster with a single character needs no decision, so
 * {@link #needed(List)} says the picker can be skipped entirely.
 */
public final class CharacterPicker {

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 26);
    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font BODY_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font HINT_FONT = new Font("SansSerif", Font.PLAIN, 13);

    private static final int CARD_W = 176;
    private static final int CARD_H = 268;
    private static final int CARD_GAP = 16;

    // Hoisted: every card drew the same dozen colours and allocated all of
    // them again per card, per frame. See RENDER_PLAN's B2 note — this buys
    // allocation, not batching.
    private static final int SCRIM = new Color(12, 14, 22, 235).getRGB();
    private static final int TITLE = Color.WHITE.getRGB();
    private static final int SUBTITLE = new Color(150, 155, 175).getRGB();
    private static final int HINT = new Color(160, 165, 185).getRGB();
    private static final int CARD_PICKED = new Color(38, 44, 66).getRGB();
    private static final int CARD_PLAIN = new Color(24, 27, 38).getRGB();
    private static final int EDGE_PICKED = new Color(120, 190, 255).getRGB();
    private static final int EDGE_PLAIN = new Color(60, 66, 86).getRGB();
    private static final int STAT_LABEL = new Color(140, 146, 168).getRGB();
    private static final int STAT_VALUE = new Color(226, 230, 244).getRGB();
    private static final int RULE = new Color(255, 255, 255, 28).getRGB();
    private static final int NO_ULTIMATE = new Color(110, 115, 135).getRGB();

    private final List<CharacterProfile> roster;
    private final String levelName;
    private int index;
    private boolean done;
    /** Card hit boxes, recomputed each render and hit-tested the frame after. */
    private final Rectangle[] cards;
    private double clock;

    public CharacterPicker(List<CharacterProfile> roster, String levelName,
                           String preferredKey) {
        this.roster = roster;
        this.levelName = levelName == null ? "" : levelName;
        this.cards = new Rectangle[roster.size()];
        for (int i = 0; i < cards.length; i++) cards[i] = new Rectangle();
        for (int i = 0; i < roster.size(); i++) {
            if (roster.get(i).key.equals(preferredKey)) index = i;
        }
    }

    /** Whether a roster actually asks the player to choose. */
    public static boolean needed(List<CharacterProfile> roster) {
        return roster != null && roster.size() > 1;
    }

    /** The character currently highlighted (never {@code null} for a live picker). */
    public CharacterProfile selected() {
        return roster.isEmpty() ? CharacterProfile.defaultProfile()
                : roster.get(Math.max(0, Math.min(index, roster.size() - 1)));
    }

    /** Whether the player has confirmed their choice. */
    public boolean done() {
        return done;
    }

    /** Advance the picker; returns true on the frame the choice is confirmed. */
    public boolean update(double dt, InputManager input) {
        if (done) return false;
        clock += dt;
        if (roster.isEmpty()) {
            done = true;
            return true;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_LEFT)
                || KeyBinds.pressed(input, GameAction.MOVE_LEFT)) {
            index = (index - 1 + roster.size()) % roster.size();
        }
        if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)
                || KeyBinds.pressed(input, GameAction.MOVE_RIGHT)) {
            index = (index + 1) % roster.size();
        }
        // Number keys jump straight to a character — the hotbar binds, so a
        // player who moved those keys picks with the keys they moved them to.
        for (int i = 0; i < Math.min(GameAction.hotbarCount(), roster.size()); i++) {
            if (KeyBinds.pressed(input, GameAction.hotbar(i))) index = i;
        }
        int mx = input.getMouseX(), my = input.getMouseY();
        boolean click = input.isMouseJustPressed();
        for (int i = 0; i < cards.length; i++) {
            if (!cards[i].contains(mx, my)) continue;
            index = i;
            if (click) {
                done = true;
                return true;
            }
        }
        if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
            done = true;
            return true;
        }
        return false;
    }

    public void render(DrawTarget target, int viewportW, int viewportH) {
        target.fillRect(0, 0, viewportW, viewportH, SCRIM);

        String title = "Choose your character";
        target.drawText(title, (viewportW - target.textWidth(title, TITLE_FONT)) / 2,
                viewportH / 2 - 170, TITLE_FONT, TITLE);
        if (!levelName.isEmpty()) {
            target.drawText(levelName,
                    (viewportW - target.textWidth(levelName, HINT_FONT)) / 2,
                    viewportH / 2 - 146, HINT_FONT, SUBTITLE);
        }

        // The whole row is centred; a roster wider than the window scrolls so
        // the highlighted card stays on screen.
        int rowW = roster.size() * CARD_W + Math.max(0, roster.size() - 1) * CARD_GAP;
        int startX = (viewportW - rowW) / 2;
        if (rowW > viewportW - 40) {
            startX = viewportW / 2 - CARD_W / 2 - index * (CARD_W + CARD_GAP);
        }
        int top = viewportH / 2 - CARD_H / 2;
        for (int i = 0; i < roster.size(); i++) {
            int x = startX + i * (CARD_W + CARD_GAP);
            cards[i].setBounds(x, top, CARD_W, CARD_H);
            drawCard(target, roster.get(i), x, top, i == index);
        }

        String hint = "← → or click to choose · Enter to start";
        target.drawText(hint, (viewportW - target.textWidth(hint, HINT_FONT)) / 2,
                top + CARD_H + 44, HINT_FONT, HINT);
    }

    private void drawCard(DrawTarget target, CharacterProfile p, int x, int y, boolean picked) {
        target.fillRoundRect(x, y, CARD_W, CARD_H, 14, 14,
                picked ? CARD_PICKED : CARD_PLAIN);
        target.drawRoundRect(x, y, CARD_W, CARD_H, 14, 14,
                picked ? EDGE_PICKED : EDGE_PLAIN, picked ? 2.5f : 1.2f);

        // The character as they will actually look in-game: their assigned
        // sheet if they have one, else the pre-generated directional art —
        // walking on the highlighted card, standing still on the others.
        int art = 84;
        PlayerSprites.Frame frame = PlayerSprites.directionalFrame(p.key,
                picked ? "walk" : "idle", Facing.SOUTH_EAST,
                picked ? clock : 0, art, p.body);
        BufferedImage img = frame.image();
        int ix = x + (CARD_W - art) / 2, iy = y + 14;
        if (frame.mirrored()) {
            target.drawImage(img, ix + art, iy, -art, art);
        } else {
            target.drawImage(img, ix, iy, art, art);
        }

        target.drawText(p.name, x + (CARD_W - target.textWidth(p.name, NAME_FONT)) / 2,
                y + art + 34, NAME_FONT, TITLE);

        int line = y + art + 56;
        line = statLine(target, x, line, "Speed", (int) Math.round(p.speed * 100) + "%");
        line = statLine(target, x, line, "Health", String.valueOf((int) Math.round(p.maxHealth)));
        line = statLine(target, x, line, "Mana", String.valueOf((int) Math.round(p.maxMana)));
        line = statLine(target, x, line, "Stamina",
                String.valueOf((int) Math.round(p.maxStamina)));
        line = statLine(target, x, line, "Jumps",
                p.airJumps == 0 ? "ground only"
                        : p.airJumps == 1 ? "double" : (p.airJumps + 1) + "×");
        line = statLine(target, x, line, "Sprint", p.sprintEnabled ? "yes" : "no");

        // The ultimate closes the card, centred and set off by a rule.
        Ultimate u = p.ultimate();
        int ultY = y + CARD_H - 14;
        // The card's own stroke width, not 1: Graphics2D keeps the last stroke
        // set, so this rule was always drawn with the width the card border
        // above it chose. Passing 1f here would be "obviously right" and would
        // thin the rule under the selected card.
        target.drawLine(x + 14, ultY - 18, x + CARD_W - 14, ultY - 18,
                RULE, picked ? 2.5f : 1.2f);
        String ultName = u != null ? u.name() : "no ultimate";
        target.drawText(ultName,
                x + (CARD_W - target.textWidth(ultName, BODY_FONT)) / 2, ultY,
                BODY_FONT, u != null ? u.color().getRGB() : NO_ULTIMATE);
    }

    /** One "label   value" row; returns the next row's baseline. */
    private static int statLine(DrawTarget target, int x, int baseline,
                                String label, String value) {
        target.drawText(label, x + 14, baseline, BODY_FONT, STAT_LABEL);
        target.drawText(value, x + CARD_W - 14 - target.textWidth(value, BODY_FONT),
                baseline, BODY_FONT, STAT_VALUE);
        return baseline + 16;
    }

    /**
     * The icon a palette swatch shows for a profile: their idle sprite, with a
     * facing hint so a directional character reads as one.
     */
    public static BufferedImage icon(CharacterProfile p, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        BufferedImage assigned = Skins.frame(
                PlayerSprites.characterStateKey(p.key, "idle"), 0);
        g.drawImage(assigned != null ? assigned
                : DirectionalSprites.frame(size, p.body, Facing.SOUTH_EAST, 0),
                0, 0, size, size, null);
        DirectionalSprites.drawFacingHint(g, size, Facing.SOUTH_EAST, p.accent);
        g.dispose();
        return out;
    }
}
