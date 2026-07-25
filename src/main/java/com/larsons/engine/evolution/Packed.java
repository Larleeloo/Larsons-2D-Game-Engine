package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The compact encoding a save uses for everything there is a lot of: the cells,
 * the food, the bodies, the waste, and the lists of strands the books keep.
 *
 * <p>A single dish can hold {@value Dish#MAX_ORGANISMS} cells and
 * {@value Dish#MAX_ORBS} orbs, and a lab can hold a shelf of dishes. Written as
 * one JSON object per item — a name and a full-precision double for every field
 * — a cell costs about 300 bytes and an orb about 90, so a busy lab writes
 * megabytes of punctuation to describe a few thousand dots in some jelly. This
 * class exists to make that cheap without giving up a save you can open and
 * read.
 *
 * <p>Four things do the work:
 * <ul>
 *   <li><b>A row instead of an object.</b> Field names are written once for the
 *       whole list, in the block's {@code format} line, rather than once per
 *       item; each item is then a single line of space-separated values.</li>
 *   <li><b>Numbers quantised to what the simulation can actually tell apart.</b>
 *       A cell's position is meaningful to a hundredth of a dish unit — far
 *       finer than a pixel at full zoom — not to the seventeen digits
 *       {@code Double.toString} prints.</li>
 *   <li><b>A dictionary for repeated text.</b> A crowded dish is nearly always a
 *       bloom of a handful of strands, so the DNA is written once each and the
 *       cells reference it by number. The more successful a strand is, the more
 *       it pays off.</li>
 *   <li><b>Trailing defaults dropped.</b> Most cells are in no colony, carry no
 *       venom and remember nothing, so most rows simply stop early.</li>
 * </ul>
 *
 * <p>A packed block is still plain JSON, and it still says what it holds:
 * <pre>
 * "organisms": {
 *   "format": "id strand x y vx vy energy age generation colony venom memory(x,y)...",
 *   "strands": ["GGGG", "GRGGGRRGB"],
 *   "rows": ["1 0 -12.35 88.12 3.46 -1.23 43.2 55.6 12", ...]
 * }
 * </pre>
 *
 * <p>Nothing here is required to read an older save: every list that can be
 * packed is still accepted in the array-of-objects form the game used to write
 * (see {@code Dish.fromMap}), so a save from an earlier build loads and is
 * re-written packed the next time the game saves.
 */
public final class Packed {

    /**
     * Decimals kept for anything measured in dish units — positions and
     * velocities. A dish is {@value Dish#DEFAULT_RADIUS} units across and the
     * microscope zooms to 4×, so a hundredth of a unit is a twenty-fifth of a
     * pixel: quantising here is invisible and costs nothing anyone can see.
     */
    public static final int SPACE = 2;
    /**
     * Decimals kept for amounts and clocks — energy, age, seconds left. A cell
     * divides at {@value Organism#REPLICATE_AT} energy, so hundredths are three
     * orders of magnitude finer than any decision made from them.
     */
    public static final int AMOUNT = 2;
    /**
     * Decimals kept for remembered food locations. A cell treats two spots
     * within 24 units as the same place, so whole units are already far more
     * precision than the memory itself has.
     */
    public static final int COARSE = 0;

    private Packed() {}

    // --- writing ----------------------------------------------------------------------

    /**
     * One row under construction. Fields are appended in the order the block's
     * {@code format} line names them; a field given a default is remembered as
     * droppable, and any run of droppable fields at the end of the row is left
     * out entirely.
     */
    public static final class Row {

        private final List<String> fields = new ArrayList<>(8);
        /** How many fields have to be written: everything past here is default. */
        private int keep;

        /** Append a whole number that is always written. */
        public Row add(long v) {
            return required(Long.toString(v));
        }

        /** Append a whole number, droppable while it is still {@code dflt}. */
        public Row add(long v, long dflt) {
            fields.add(Long.toString(v));
            if (v != dflt) keep = fields.size();
            return this;
        }

        /** Append a number rounded to {@code decimals}, always written. */
        public Row num(double v, int decimals) {
            return required(text(v, decimals));
        }

        /** Append a rounded number, droppable while it rounds to {@code dflt}. */
        public Row num(double v, int decimals, double dflt) {
            String s = text(v, decimals);
            fields.add(s);
            if (!s.equals(text(dflt, decimals))) keep = fields.size();
            return this;
        }

        /**
         * Append a word. Rows are split on whitespace, so any is replaced with
         * {@code _}; an empty word is written as {@code -} so it cannot swallow
         * the fields after it.
         */
        public Row word(String s) {
            if (s == null || s.isEmpty()) return required("-");
            return required(s.trim().replaceAll("\\s+", "_"));
        }

        /** Append an {@code x,y} point rounded to {@code decimals}. */
        public Row point(double x, double y, int decimals) {
            return required(text(x, decimals) + "," + text(y, decimals));
        }

        private Row required(String s) {
            fields.add(s);
            keep = fields.size();
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep; i++) {
                if (i > 0) sb.append(' ');
                sb.append(fields.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * A number as a row writes it: rounded, never in scientific notation, and
     * with no decimal point at all when it lands on a whole number — which,
     * after rounding, most of them do.
     */
    public static String text(double v, int decimals) {
        if (!Double.isFinite(v)) return "0";
        double scale = Math.pow(10, Math.max(0, decimals));
        double r = Math.round(v * scale) / scale;
        if (r == 0) return "0"; // also folds -0 away
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return Long.toString((long) r);
        String s = String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", r);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    // --- blocks -----------------------------------------------------------------------

    /** A packed block: what the fields are, and a row per item. */
    public static Map<String, Object> block(String format, List<String> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", format);
        m.put("rows", rows);
        return m;
    }

    /** A packed block with a dictionary the rows index into. */
    public static Map<String, Object> block(String format, String dictionary,
                                            Collection<String> words, List<String> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", format);
        m.put(dictionary, new ArrayList<>(words));
        m.put("rows", rows);
        return m;
    }

    /** Whether a saved value is a packed block rather than a list of objects. */
    public static boolean isBlock(Object o) {
        return o instanceof Map<?, ?> m && m.get("rows") instanceof List<?>;
    }

    /** The rows of a block; empty for anything that is not one. */
    public static List<String> rows(Object block) {
        List<String> out = new ArrayList<>();
        if (block instanceof Map<?, ?> m && m.get("rows") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** A block's dictionary, by name; empty when it has none. */
    public static List<String> words(Object block, String dictionary) {
        if (block instanceof Map<?, ?> m) return wordList(m.get(dictionary));
        return new ArrayList<>();
    }

    /**
     * A list of words, read from either form: the single space-separated string
     * they are written as now, or the JSON array an older save used.
     */
    public static List<String> wordList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof String s) {
            for (String w : s.trim().split("\\s+")) {
                if (!w.isEmpty()) out.add(w);
            }
        } else if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e != null) out.add(String.valueOf(e));
            }
        }
        return out;
    }

    /** Words as one line — how a flat list of strands is written. */
    public static String joinWords(Collection<String> words) {
        return String.join(" ", words);
    }

    // --- reading ----------------------------------------------------------------------

    /**
     * Reads a row field by field. A row that is short (because its tail was
     * default), empty, or hand-edited into nonsense yields defaults rather than
     * throwing — a save with one bad line should lose that line, not the lab.
     */
    public static final class Reader {

        private final String[] fields;
        private int at;

        public Reader(String row) {
            String s = row == null ? "" : row.trim();
            fields = s.isEmpty() ? new String[0] : s.split("\\s+");
        }

        public boolean hasNext() { return at < fields.length; }

        public String nextWord(String dflt) {
            return at < fields.length ? fields[at++] : dflt;
        }

        public double nextDouble(double dflt) {
            String s = nextWord(null);
            if (s == null) return dflt;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return dflt;
            }
        }

        public int nextInt(int dflt) {
            return (int) nextLong(dflt);
        }

        public long nextLong(long dflt) {
            String s = nextWord(null);
            if (s == null) return dflt;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
    }

    /** An {@code x,y} point, or {@code null} if the field is not one. */
    public static double[] point(String field) {
        if (field == null) return null;
        int comma = field.indexOf(',');
        if (comma <= 0 || comma == field.length() - 1) return null;
        try {
            return new double[]{
                    Double.parseDouble(field.substring(0, comma)),
                    Double.parseDouble(field.substring(comma + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- the colony-combination lists the catalog and the history both keep ------------

    /** Combination signatures with the moment each was first seen, packed. */
    public static Map<String, Object> signatures(Map<String, Long> stamped) {
        List<String> rows = new ArrayList<>(stamped.size());
        for (Map.Entry<String, Long> e : stamped.entrySet()) {
            rows.add(new Row().word(e.getKey()).add(e.getValue() == null ? 0 : e.getValue())
                    .toString());
        }
        return block("signature at", rows);
    }

    /**
     * Read those back from either form: the packed block, or the array of
     * {@code {"signature": …, "at": …}} objects an older save wrote.
     */
    public static void readSignatures(Object saved, Map<String, Long> into) {
        if (isBlock(saved)) {
            for (String row : rows(saved)) {
                Reader r = new Reader(row);
                String signature = r.nextWord("");
                if (signature.isEmpty() || "-".equals(signature)) continue;
                into.put(signature, r.nextLong(0));
            }
            return;
        }
        if (saved instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("signature") != null) {
                    Object at = m.get("at");
                    into.put(String.valueOf(m.get("signature")),
                            at instanceof Number n ? n.longValue() : 0L);
                }
            }
        }
    }
}
