package app.ktmnav.backend;

/**
 * 256-bit perceptual difference hash of a square ARGB arrow glyph, tolerant to single-pixel noise.
 */
public final class ArrowFingerprint {
    public static final int BITS = 256;
    public static final int MATCH_TOLERANCE = 8;
    static final int MARGIN = 16;
    private static final int CELLS = 8;

    private ArrowFingerprint() {
    }

    public static String of(int[] argb) {
        if (argb == null) {
            return null;
        }
        int size = (int) Math.round(Math.sqrt(argb.length));
        if (size < CELLS + 1 || size * size != argb.length) {
            return null;
        }
        int[] ink = ink(argb);
        int[][] wide = average(ink, size, CELLS + 1, CELLS);
        int[][] tall = average(ink, size, CELLS, CELLS + 1);
        if (flat(wide)) {
            return null;
        }
        long[] words = new long[BITS / 64];
        int bit = 0;
        for (int y = 0; y < CELLS; y++) {
            for (int x = 0; x < CELLS; x++) {
                bit = compare(words, bit, wide[y][x], wide[y][x + 1]);
            }
        }
        for (int y = 0; y < CELLS; y++) {
            for (int x = 0; x < CELLS; x++) {
                bit = compare(words, bit, tall[y][x], tall[y + 1][x]);
            }
        }
        StringBuilder sb = new StringBuilder(BITS / 4);
        for (long w : words) {
            String hex = Long.toHexString(w);
            for (int i = hex.length(); i < 16; i++) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public static int distance(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            int x = Character.digit(a.charAt(i), 16);
            int y = Character.digit(b.charAt(i), 16);
            if (x < 0 || y < 0) {
                return Integer.MAX_VALUE;
            }
            d += Integer.bitCount(x ^ y);
        }
        return d;
    }

    private static int compare(long[] words, int bit, int a, int b) {
        if (a > b + MARGIN) {
            words[bit / 64] |= 1L << (63 - bit % 64);
        }
        if (b > a + MARGIN) {
            words[(bit + 1) / 64] |= 1L << (63 - (bit + 1) % 64);
        }
        return bit + 2;
    }

    private static int[] ink(int[] argb) {
        boolean opaque = true;
        for (int p : argb) {
            if ((p >>> 24) < 250) {
                opaque = false;
                break;
            }
        }
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            if (opaque) {
                out[i] = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
            } else {
                out[i] = p >>> 24;
            }
        }
        return out;
    }

    private static int[][] average(int[] ink, int size, int cols, int rows) {
        int[][] grid = new int[rows][cols];
        for (int gy = 0; gy < rows; gy++) {
            int y0 = gy * size / rows;
            int y1 = (gy + 1) * size / rows;
            for (int gx = 0; gx < cols; gx++) {
                int x0 = gx * size / cols;
                int x1 = (gx + 1) * size / cols;
                long sum = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        sum += ink[y * size + x];
                    }
                }
                int count = Math.max(1, (y1 - y0) * (x1 - x0));
                grid[gy][gx] = (int) (sum / count);
            }
        }
        return grid;
    }

    private static boolean flat(int[][] grid) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] row : grid) {
            for (int v : row) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return max - min <= MARGIN;
    }
}
