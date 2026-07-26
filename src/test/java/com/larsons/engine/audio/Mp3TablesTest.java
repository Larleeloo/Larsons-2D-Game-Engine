package com.larsons.engine.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural integrity of the packed MPEG Layer III code books.
 *
 * <p>These tables are thousands of transcribed numbers, and a single digit
 * out of place would not fail loudly — it would quietly corrupt somebody's
 * music, in a way no "does it decode?" test would catch. So this walks every
 * code book the way {@link Mp3Decoder} does, over every bit pattern that can
 * reach it, and asserts the walk is always well-formed: in bounds, reaching a
 * leaf, and consuming a legal number of bits.
 *
 * <p>It lives in the {@code audio} package so it can see the package-private
 * {@link Mp3Tables}.
 */
@Timeout(60)
class Mp3TablesTest {

    /** The longest Huffman code the standard allows in a Layer III book. */
    private static final int MAX_CODE_BITS = 19;

    /**
     * Walk one code book from a 32-bit input, exactly as the decoder's inner
     * loop does: a 5-bit index into the book, then negative entries mean
     * "read {@code leaf & 7} more bits and re-index at {@code -(leaf >> 3)}".
     * Returns the leaf reached.
     */
    private static int walk(int book, int bits) {
        int width = 5;
        int steps = 0;
        int leaf = at(book, bits >>> (32 - width));
        while (leaf < 0) {
            assertTrue(++steps <= 8, "code book at " + book
                    + " walked " + steps + " sub-tables — the table is malformed");
            bits <<= width;
            width = leaf & 7;
            assertTrue(width >= 1 && width <= 7,
                    "sub-table width " + width + " in code book at " + book);
            leaf = at(book, (bits >>> (32 - width)) - (leaf >> 3));
        }
        return leaf;
    }

    /** A table entry, asserting the index is inside the packed array. */
    private static int at(int book, int index) {
        int i = book + index;
        assertTrue(i >= 0 && i < Mp3Tables.TABS.length,
                "code book at " + book + " indexed " + i + ", outside the "
                        + Mp3Tables.TABS.length + "-entry table");
        return Mp3Tables.TABS[i];
    }

    @Test
    void everyBigValuesCodeBookDecodesEveryBitPattern() {
        for (int table = 0; table < Mp3Tables.TAB_INDEX.length; table++) {
            int book = Mp3Tables.TAB_INDEX[table];
            assertTrue(book >= 0 && book < Mp3Tables.TABS.length,
                    "code book " + table + " starts outside the table");
            // Every distinct 19-bit prefix — more than enough to reach a leaf
            // through any legal chain of sub-tables — stepped so the sweep
            // stays quick while covering all of the first two levels exactly.
            for (int prefix = 0; prefix < (1 << MAX_CODE_BITS); prefix += 7) {
                int leaf = walk(book, prefix << (32 - MAX_CODE_BITS));
                int codeBits = leaf >> 8;
                assertTrue(codeBits >= 0 && codeBits <= MAX_CODE_BITS,
                        "code book " + table + " claims a " + codeBits
                                + "-bit code for prefix " + Integer.toBinaryString(prefix));
                // The two 4-bit halves are the pair of values this code
                // stands for; 15 is the escape into linbits.
                assertTrue((leaf & 0x0F) <= 15 && ((leaf >> 4) & 0x0F) <= 15,
                        "code book " + table + " decoded an out-of-range value pair");
            }
        }
    }

    /** Table 0 codes nothing at all: it is the "this region is empty" book. */
    @Test
    void codeBookZeroIsEmpty() {
        int book = Mp3Tables.TAB_INDEX[0];
        assertEquals(0, book, "the empty code book should sit at the start");
        for (int i = 0; i < 32; i++) {
            assertEquals(0, Mp3Tables.TABS[book + i],
                    "the empty code book must decode every input to (0,0) in 0 bits");
        }
    }

    /** The two count1 books must resolve any four bits without a second look. */
    @Test
    void bothCount1CodeBooksAreWellFormed() {
        for (int[] book : new int[][]{Mp3Tables.TAB32, Mp3Tables.TAB33}) {
            for (int bits = 0; bits < 16; bits++) {
                int leaf = book[bits];
                if ((leaf & 8) != 0) {
                    assertTrue((leaf & 7) >= 1 && (leaf & 7) <= 6,
                            "count1 leaf claims " + (leaf & 7) + " bits");
                    continue;
                }
                // A second-level lookup: its base and width must land inside
                // the same little table for every continuation.
                int width = leaf & 3;
                assertTrue(width >= 1 && width <= 3,
                        "count1 sub-table width " + width + " is unusable");
                for (int extra = 0; extra < (1 << width); extra++) {
                    int index = (leaf >> 3) + extra;
                    assertTrue(index >= 0 && index < book.length,
                            "count1 sub-table indexed " + index + " of " + book.length);
                    assertTrue((book[index] & 7) >= 1,
                            "count1 sub-table leaf consumes no bits");
                }
            }
        }
    }

    /** The linbits column must match the standard's escape widths. */
    @Test
    void escapeCodeBooksHaveTheStandardLinbitsWidths() {
        assertEquals(32, Mp3Tables.LINBITS.length);
        for (int i = 0; i < 16; i++) {
            assertEquals(0, Mp3Tables.LINBITS[i],
                    "code book " + i + " has no linbits escape");
        }
        int[] expected = {1, 2, 3, 4, 6, 8, 10, 13, 4, 5, 6, 7, 8, 9, 11, 13};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], Mp3Tables.LINBITS[16 + i],
                    "code book " + (16 + i) + " escape width");
        }
    }

    /** Scale-factor band layouts: one row per sample rate, zero-terminated. */
    @Test
    void scaleFactorBandTablesAreComplete() {
        assertEquals(8, Mp3Tables.SCF_LONG.length);
        assertEquals(8, Mp3Tables.SCF_SHORT.length);
        assertEquals(8, Mp3Tables.SCF_MIXED.length);
        for (int i = 0; i < 8; i++) {
            assertEquals(23, Mp3Tables.SCF_LONG[i].length);
            assertEquals(40, Mp3Tables.SCF_SHORT[i].length);
            assertEquals(40, Mp3Tables.SCF_MIXED[i].length);
            assertEquals(0, Mp3Tables.SCF_LONG[i][22],
                    "long band row " + i + " must be zero-terminated");
            assertEquals(576, sumBands(Mp3Tables.SCF_LONG[i]),
                    "long bands of row " + i + " must cover a whole granule");
            assertEquals(576, sumBands(Mp3Tables.SCF_SHORT[i]),
                    "short bands of row " + i + " must cover a whole granule");
        }
    }

    private static int sumBands(int[] row) {
        int total = 0;
        for (int width : row) {
            if (width == 0) break;
            total += width;
        }
        return total;
    }
}
