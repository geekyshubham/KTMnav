package app.ktmnav.backend;

import java.util.HashMap;
import java.util.Map;

/**
 * Arrow hash lookup and roundabout exit mapping for Organic Maps.
 * Based on TurnIt OrganicMapsArrows evidence.
 */
public final class OrganicMapsArrows {

    private static final Map<Integer, TurnIcon> BY_HASH = new HashMap<>();
    private static final Map<Integer, Integer> EXIT_BY_HASH = new HashMap<>();

    static {
        BY_HASH.put(1175128587, TurnIcon.QUITE_RIGHT);
        BY_HASH.put(-117021572, RoundaboutGlyph.rightHandForExit(2));
        BY_HASH.put(-1707729307, RoundaboutGlyph.rightHandForExit(3));
        BY_HASH.put(171650717, TurnIcon.QUITE_LEFT);
        BY_HASH.put(656681198, TurnIcon.LIGHT_LEFT);
        BY_HASH.put(613276588, TurnIcon.END);
        BY_HASH.put(967090251, TurnIcon.GO_STRAIGHT);
        BY_HASH.put(-658627802, TurnIcon.LIGHT_RIGHT);
        BY_HASH.put(1658589293, RoundaboutGlyph.rightHandForExit(1));
        BY_HASH.put(-1473966237, TurnIcon.END);

        EXIT_BY_HASH.put(1658589293, 1);
        EXIT_BY_HASH.put(-117021572, 2);
        EXIT_BY_HASH.put(-1707729307, 3);
    }

    private OrganicMapsArrows() {
    }

    public static TurnIcon lookup(int hash) {
        return BY_HASH.get(hash);
    }

    public static int exitFor(int hash) {
        return EXIT_BY_HASH.getOrDefault(hash, 0);
    }
}
