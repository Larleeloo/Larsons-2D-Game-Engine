package com.larsons.engine.evolution;

import java.awt.Color;

/**
 * One of the three coloured pixels a strand of DNA is built from.
 *
 * <p>Colour <em>is</em> the encoding in this simulator: red encodes hostility,
 * blue encodes altruism, and green is a wild card whose meaning depends on
 * where it sits in the strand (see {@link Phenotype}). An organism's visible
 * colour is the average of its nucleotides, so what a cell looks like under
 * the microscope is a direct read-out of what it does.
 */
public enum Nucleotide {

    /** Hostility: killing, digesting other cells, heat, sabotage. */
    R('R', new Color(226, 74, 66), "red"),
    /** The wild card: meaning comes from the slot it occupies. */
    G('G', new Color(96, 208, 108), "green"),
    /** Altruism: sharing, defending kin, communicating. */
    B('B', new Color(88, 132, 232), "blue");

    private final char code;
    private final Color color;
    private final String displayName;

    Nucleotide(char code, Color color, String displayName) {
        this.code = code;
        this.color = color;
        this.displayName = displayName;
    }

    public char code() { return code; }

    public Color color() { return color; }

    public String displayName() { return displayName; }

    /** Parse a single {@code R}/{@code G}/{@code B} character (case-insensitive). */
    public static Nucleotide of(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'R' -> R;
            case 'G' -> G;
            case 'B' -> B;
            default -> throw new IllegalArgumentException("Not a nucleotide: '" + c + "'");
        };
    }

    /** Whether {@code c} names a nucleotide (used to validate typed/loaded DNA). */
    public static boolean isCode(char c) {
        char u = Character.toUpperCase(c);
        return u == 'R' || u == 'G' || u == 'B';
    }
}
