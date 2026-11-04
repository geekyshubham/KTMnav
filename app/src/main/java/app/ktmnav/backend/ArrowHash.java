package app.ktmnav.backend;

import java.util.Arrays;

/**
 * Hash generator for pixel arrays of maneuver arrow icons.
 */
public final class ArrowHash {

    private ArrowHash() {
    }

    public static int of(int[] pixels) {
        return Arrays.hashCode(pixels);
    }
}
