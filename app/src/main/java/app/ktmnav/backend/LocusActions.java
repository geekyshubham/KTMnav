package app.ktmnav.backend;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Translates Locus Map route actions and values into KTM dashboard glyphs and texts.
 */
public final class LocusActions {
    private static final String ROUNDABOUT_EXIT_PREFIX = "ROUNDABOUT_EXIT_";

    private LocusActions() {}

    public static TurnIcon icon(PointRteAction action) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case NO_MANEUVER:
            case NO_MANEUVER_NAME_CHANGE:
            case CONTINUE_STRAIGHT:
                return TurnIcon.GO_STRAIGHT;
            case LEFT_SLIGHT:
                return TurnIcon.LIGHT_LEFT;
            case LEFT:
                return TurnIcon.QUITE_LEFT;
            case LEFT_SHARP:
                return TurnIcon.HEAVY_LEFT;
            case RIGHT_SLIGHT:
                return TurnIcon.LIGHT_RIGHT;
            case RIGHT:
                return TurnIcon.QUITE_RIGHT;
            case RIGHT_SHARP:
                return TurnIcon.HEAVY_RIGHT;
            case STAY_LEFT:
                return TurnIcon.KEEP_LEFT;
            case STAY_RIGHT:
                return TurnIcon.KEEP_RIGHT;
            case STAY_STRAIGHT:
                return TurnIcon.KEEP_MIDDLE;
            case U_TURN:
            case U_TURN_LEFT:
                return TurnIcon.UTURN_LEFT;
            case U_TURN_RIGHT:
                return TurnIcon.UTURN_RIGHT;
            case EXIT_LEFT:
                return TurnIcon.LEAVE_HIGHWAY_LEFT_LANE;
            case EXIT_RIGHT:
                return TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE;
            case RAMP_ON_LEFT:
            case MERGE_LEFT:
                return TurnIcon.ENTER_HIGHWAY_LEFT_LANE;
            case RAMP_ON_RIGHT:
            case MERGE_RIGHT:
                return TurnIcon.ENTER_HIGHWAY_RIGHT_LANE;
            case RAMP_STRAIGHT:
            case MERGE:
                return TurnIcon.GO_STRAIGHT;
            case ARRIVE_DEST:
            case ARRIVE_DEST_LEFT:
            case ARRIVE_DEST_RIGHT:
                return TurnIcon.END;
            case PASS_PLACE:
                return TurnIcon.PASS_STATION;
            case ROUNDABOUT_EXIT_1:
                return RoundaboutGlyph.rightHandForExit(1);
            case ROUNDABOUT_EXIT_2:
                return RoundaboutGlyph.rightHandForExit(2);
            case ROUNDABOUT_EXIT_3:
                return RoundaboutGlyph.rightHandForExit(3);
            case ROUNDABOUT_EXIT_4:
                return RoundaboutGlyph.rightHandForExit(4);
            case ROUNDABOUT_EXIT_5:
                return RoundaboutGlyph.rightHandForExit(5);
            case ROUNDABOUT_EXIT_6:
                return RoundaboutGlyph.rightHandForExit(6);
            case ROUNDABOUT_EXIT_7:
                return RoundaboutGlyph.rightHandForExit(7);
            case ROUNDABOUT_EXIT_8:
                return RoundaboutGlyph.rightHandForExit(8);
            default:
                return null;
        }
    }

    public static int roundaboutExit(PointRteAction action) {
        if (action != null && action.name().startsWith(ROUNDABOUT_EXIT_PREFIX)) {
            try {
                return Integer.parseInt(action.name().substring(ROUNDABOUT_EXIT_PREFIX.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public static String distanceText(double meters) {
        if (meters <= 0.0d) {
            return null;
        }
        long rounded = Math.round(meters);
        if (rounded < 1000) {
            return rounded + " m";
        }
        double km = meters / 1000.0d;
        if (km < 9.95d) {
            return String.format(Locale.ROOT, "%.1f km", km);
        }
        return Math.round(km) + " km";
    }

    public static String etaText(long currentTimeMs, long timeToFinishMs) {
        if (timeToFinishMs <= 0) {
            return null;
        }
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(currentTimeMs + timeToFinishMs));
    }
}
