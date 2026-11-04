package app.ktmnav.backend;

/**
 * Supported motorcycle dashboard generations matching original TurnIt specifications.
 * GEN2: Bluetooth Classic RFCOMM (1290 / 890 / 790 Gen 2 TFT)
 * GEN3: Bluetooth Low Energy BCCU encrypted protocol (formerly mislabeled GEN1)
 */
public enum BikeGeneration {
    GEN2,
    GEN3;

    public static final String CANONICAL_GEN2 = "GEN2";
    public static final String CANONICAL_GEN3 = "GEN3";

    /**
     * Resolves the generation from a string representation, defaulting to GEN3 for unknown/missing/GEN1.
     */
    public static BikeGeneration from(String val) {
        if (val == null) {
            return GEN3;
        }
        String trimmed = val.trim().toUpperCase();
        if ("GEN2".equals(trimmed)) {
            return GEN2;
        }
        // GEN3, GEN1 (legacy label), and any missing/unknown values map canonically to GEN3
        return GEN3;
    }

    /**
     * Normalizes any generation string to canonical "GEN2" or "GEN3".
     */
    public static String normalize(String val) {
        return from(val).name();
    }
}
