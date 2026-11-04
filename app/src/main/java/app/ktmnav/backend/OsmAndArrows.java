package app.ktmnav.backend;

import java.util.HashMap;
import java.util.Map;

/**
 * Arrow hash lookup table for OsmAnd navigation icons.
 * Based on TurnIt OsmAndArrows evidence.
 */
public final class OsmAndArrows {

    private static final Map<Integer, TurnIcon> BY_HASH = new HashMap<>();

    static {
        BY_HASH.put(-1902801009, TurnIcon.QUITE_LEFT);
        BY_HASH.put(1117514489, TurnIcon.QUITE_LEFT);
        BY_HASH.put(-829445375, TurnIcon.QUITE_LEFT);
        BY_HASH.put(-1270134762, TurnIcon.QUITE_RIGHT);
        BY_HASH.put(-1647226623, TurnIcon.QUITE_RIGHT);
        BY_HASH.put(958810625, TurnIcon.HEAVY_RIGHT);
        BY_HASH.put(1570324737, TurnIcon.UTURN_LEFT);
        BY_HASH.put(482803357, TurnIcon.UTURN_LEFT);
    }

    private OsmAndArrows() {
    }

    public static TurnIcon lookup(int hash) {
        return BY_HASH.get(hash);
    }
}
