package app.ktmnav.backend;

import java.util.Locale;

/**
 * Route actions compatible with Locus Map navigation points.
 * Matches locus-api-core PointRteAction enum definitions.
 */
public enum PointRteAction {
    UNDEFINED(Integer.MIN_VALUE, "undefined"),
    NO_MANEUVER(0, "no_maneuver"),
    CONTINUE_STRAIGHT(1, "straight"),
    NO_MANEUVER_NAME_CHANGE(2, "name_change"),
    LEFT_SLIGHT(3, "left_slight"),
    LEFT(4, "left"),
    LEFT_SHARP(5, "left_sharp"),
    RIGHT_SLIGHT(6, "right_slight"),
    RIGHT(7, "right"),
    RIGHT_SHARP(8, "right_sharp"),
    STAY_LEFT(9, "stay_left"),
    STAY_RIGHT(10, "stay_right"),
    STAY_STRAIGHT(11, "stay_straight"),
    U_TURN(12, "u-turn"),
    U_TURN_LEFT(13, "u-turn_left"),
    U_TURN_RIGHT(14, "u-turn_right"),
    EXIT_LEFT(15, "exit_left"),
    EXIT_RIGHT(16, "exit_right"),
    RAMP_ON_LEFT(17, "ramp_left"),
    RAMP_ON_RIGHT(18, "ramp_right"),
    RAMP_STRAIGHT(19, "ramp_straight"),
    MERGE_LEFT(20, "merge_left"),
    MERGE_RIGHT(21, "merge_right"),
    MERGE(22, "merge"),
    ENTER_STATE(23, "enter_state"),
    ARRIVE_DEST(24, "dest"),
    ARRIVE_DEST_LEFT(25, "dest_left"),
    ARRIVE_DEST_RIGHT(26, "dest_right"),
    ROUNDABOUT_EXIT_1(27, "roundabout_e1"),
    ROUNDABOUT_EXIT_2(28, "roundabout_e2"),
    ROUNDABOUT_EXIT_3(29, "roundabout_e3"),
    ROUNDABOUT_EXIT_4(30, "roundabout_e4"),
    ROUNDABOUT_EXIT_5(31, "roundabout_e5"),
    ROUNDABOUT_EXIT_6(32, "roundabout_e6"),
    ROUNDABOUT_EXIT_7(33, "roundabout_e7"),
    ROUNDABOUT_EXIT_8(34, "roundabout_e8"),
    PASS_PLACE(50, "pass_place");

    private final int id;
    private final String textId;

    PointRteAction(int id, String textId) {
        this.id = id;
        this.textId = textId;
    }

    public int getId() {
        return id;
    }

    public String getTextId() {
        return textId;
    }

    public static PointRteAction getActionById(int id) {
        for (PointRteAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        return UNDEFINED;
    }

    public static PointRteAction getActionByText(String text) {
        if (text == null || text.isEmpty()) {
            return UNDEFINED;
        }
        for (PointRteAction action : values()) {
            if (action.textId.equalsIgnoreCase(text)) {
                return action;
            }
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if ("turn-left".equals(s)) {
            return LEFT;
        }
        if ("turn-right".equals(s)) {
            return RIGHT;
        }
        return UNDEFINED;
    }

    public static PointRteAction getActionRoundabout(int exitNo) {
        if (exitNo < 1) {
            return ROUNDABOUT_EXIT_1;
        }
        if (exitNo > 8) {
            return ROUNDABOUT_EXIT_8;
        }
        return getActionById((ROUNDABOUT_EXIT_1.id - 1) + exitNo);
    }

    public static PointRteAction getActionByAngle(float angle) {
        if (angle < 30.0f) {
            return CONTINUE_STRAIGHT;
        }
        if (angle < 45.0f) {
            return RIGHT_SLIGHT;
        }
        if (angle < 120.0f) {
            return RIGHT;
        }
        if (angle < 170.0f) {
            return RIGHT_SHARP;
        }
        if (angle < 180.0f) {
            return U_TURN_RIGHT;
        }
        if (angle < 190.0f) {
            return U_TURN_LEFT;
        }
        if (angle < 240.0f) {
            return LEFT_SHARP;
        }
        if (angle < 315.0f) {
            return LEFT;
        }
        if (angle < 330.0f) {
            return LEFT_SLIGHT;
        }
        return CONTINUE_STRAIGHT;
    }
}
