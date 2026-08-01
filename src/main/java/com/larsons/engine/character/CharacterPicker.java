package com.larsons.engine.character;

import com.larsons.engine.graphics.DirectionalSprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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

    public void render(Graphics2D g, int viewportW, int viewportH) {
        g.setColor(new Color(12, 14, 22, 235));
        g.fillRect(0, 0, viewportW, viewportH);

        g.setFont(TITLE_FONT);
        FontMetrics tfm = g.getFontMetrics();
        String title = "Choose your character";
        g.setColor(Color.WHITE);
        g.drawString(title, (viewportW - tfm.stringWidth(title)) / 2, viewportH / 2 - 170);
        if (!levelName.isEmpty()) {
            g.setFont(HINT_FONT);
            FontMetrics sfm = g.getFontMetrics();
            g.setColor(new Color(150, 155, 175));
            g.drawString(levelName, (viewportW - sfm.stringWidth(levelName)) / 2,
                    viewportH / 2 - 146);
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
            drawCard(g, roster.get(i), x, top, i == index);
        }

        g.setFont(HINT_FONT);
        FontMetrics hfm = g.getFontMetrics();
        String hint = "← → or click to choose · Enter to start";
        g.setColor(new Color(160, 165, 185));
        g.drawString(hint, (viewportW - hfm.stringWidth(hint)) / 2, top + CARD_H + 44);
    }

    private void drawCard(Graphics2D g, CharacterProfile p, int x, int y, boolean picked) {
        g.setColor(picked ? new Color(38, 44, 66) : new Color(24, 27, 38));
        g.fillRoundRect(x, y, CARD_W, CARD_H, 14, 14);
        g.setColor(picked ? new Color(120, 190, 255) : new Color(60, 66, 86));
        g.setStroke(new BasicStroke(picked ? 2.5f : 1.2f));
        g.drawRoundRect(x, y, CARD_W, CARD_H, 14, 14);

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
            g.drawImage(img, ix + art, iy, -art, art, null);
        } else {
            g.drawImage(img, ix, iy, art, art, null);
        }

        g.setFont(NAME_FONT);
        FontMetrics nfm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(p.name, x + (CARD_W - nfm.stringWidth(p.name)) / 2, y + art + 34);

        g.setFont(BODY_FONT);
        int line = y + art + 56;
        line = statLine(g, x, line, "Speed", (int) Math.round(p.speed * 100) + "%");
        line = statLine(g, x, line, "Health", String.valueOf((int) Math.round(p.maxHealth)));
        line = statLine(g, x, line, "Mana", String.valueOf((int) Math.round(p.maxMana)));
        line = statLine(g, x, line, "Stamina",
                String.valueOf((int) Math.round(p.maxStamina)));
        line = statLine(g, x, line, "Jumps",
                p.airJumps == 0 ? "ground only"
                        : p.airJumps == 1 ? "double" : (p.airJumps + 1) + "×");
        line = statLine(g, x, line, "Sprint", p.sprintEnabled ? "yes" : "no");

        // The ultimate closes the card, centred and set off by a rule.
        Ultimate u = p.ultimate();
        int ultY = y + CARD_H - 14;
        g.setColor(new Color(255, 255, 255, 28));
        g.drawLine(x + 14, ultY - 18, x + CARD_W - 14, ultY - 18);
        String ultName = u != null ? u.name() : "no ultimate";
        g.setColor(u != null ? u.color() : new Color(110, 115, 135));
        g.drawString(ultName, x + (CARD_W - g.getFontMetrics().stringWidth(ultName)) / 2,
                ultY);
    }

    /** One "label   value" row; returns the next row's baseline. */
    private static int statLine(Graphics2D g, int x, int baseline, String label, String value) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(140, 146, 168));
        g.drawString(label, x + 14, baseline);
        g.setColor(new Color(226, 230, 244));
        g.drawString(value, x + CARD_W - 14 - fm.stringWidth(value), baseline);
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
