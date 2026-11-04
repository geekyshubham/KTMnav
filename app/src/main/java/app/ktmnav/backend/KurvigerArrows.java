package app.ktmnav.backend;

import java.util.HashMap;
import java.util.Map;

/**
 * Arrow hash lookup and non-guidance marker detection for Kurviger.
 * Based on TurnIt KurvigerArrows evidence.
 */
public final class KurvigerArrows {

    private static final Map<Integer, TurnIcon> BY_HASH = new HashMap<>();
    private static final Map<Integer, Boolean> NON_GUIDANCE = new HashMap<>();

    static {
        BY_HASH.put(-1030405521, TurnIcon.QUITE_RIGHT);
        BY_HASH.put(-120869937, TurnIcon.QUITE_LEFT);
        BY_HASH.put(1793735227, TurnIcon.LIGHT_LEFT);
        BY_HASH.put(169803519, TurnIcon.LIGHT_RIGHT);
        BY_HASH.put(238330173, TurnIcon.HEAVY_RIGHT);
        BY_HASH.put(-2097633863, TurnIcon.UTURN_LEFT);
        BY_HASH.put(1274151701, TurnIcon.RAB_SECT_6_RH);
        BY_HASH.put(889647417, TurnIcon.RAB_SECT_10_RH);
        BY_HASH.put(-1033920, TurnIcon.RAB_SECT_8_RH);
        BY_HASH.put(1111332233, TurnIcon.RAB_SECT_8_RH);
        BY_HASH.put(1781226930, TurnIcon.RAB_SECT_12_RH);
        BY_HASH.put(1499062392, TurnIcon.RAB_SECT_4_RH);
        BY_HASH.put(-699449148, TurnIcon.RAB_SECT_3_RH);
        BY_HASH.put(-381065572, TurnIcon.RAB_SECT_13_RH);
        BY_HASH.put(1464775721, TurnIcon.RAB_SECT_16_RH);
        BY_HASH.put(-117021572, RoundaboutGlyph.rightHandForExit(2));
        BY_HASH.put(-1707729307, RoundaboutGlyph.rightHandForExit(3));
        BY_HASH.put(66608485, TurnIcon.END);

        NON_GUIDANCE.put(162366997, Boolean.TRUE);
    }

    private KurvigerArrows() {
    }

    public static TurnIcon lookup(int hash) {
        return BY_HASH.get(hash);
    }

    public static boolean isNonGuidance(int hash) {
        return NON_GUIDANCE.containsKey(hash);
    }
}
