package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class CoMapsDirectionsTest {

    @Test
    public void testIconMapping() {
        Assert.assertEquals(TurnIcon.QUITE_LEFT, CoMapsDirections.icon("TURN_LEFT", 0));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, CoMapsDirections.icon("TURN_RIGHT", 0));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, CoMapsDirections.icon("TURN_SLIGHT_LEFT", 0));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, CoMapsDirections.icon("TURN_SLIGHT_RIGHT", 0));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, CoMapsDirections.icon("TURN_SHARP_LEFT", 0));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, CoMapsDirections.icon("TURN_SHARP_RIGHT", 0));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, CoMapsDirections.icon("GO_STRAIGHT", 0));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, CoMapsDirections.icon("NO_TURN", 0));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, CoMapsDirections.icon("U_TURN_LEFT", 0));
        Assert.assertEquals(TurnIcon.UTURN_RIGHT, CoMapsDirections.icon("U_TURN_RIGHT", 0));
        Assert.assertEquals(TurnIcon.HEAD_TO, CoMapsDirections.icon("START_AT_THE_END_OF_STREET", 0));
        Assert.assertEquals(TurnIcon.LEAVE_HIGHWAY_LEFT_LANE, CoMapsDirections.icon("EXIT_HIGHWAY_TO_LEFT", 0));
        Assert.assertEquals(TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE, CoMapsDirections.icon("EXIT_HIGHWAY_TO_RIGHT", 0));
        Assert.assertEquals(TurnIcon.END, CoMapsDirections.icon("REACHED_YOUR_DESTINATION", 0));

        // Roundabout exits mapped via RoundaboutGlyph
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, CoMapsDirections.icon("ENTER_ROUND_ABOUT", 1));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, CoMapsDirections.icon("ENTER_ROUND_ABOUT", 2));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, CoMapsDirections.icon("ENTER_ROUND_ABOUT", 3));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, CoMapsDirections.icon("ENTER_ROUND_ABOUT", 4));

        // Non-maneuver roundabout events return null
        Assert.assertNull(CoMapsDirections.icon("LEAVE_ROUND_ABOUT", 0));
        Assert.assertNull(CoMapsDirections.icon("STAY_ON_ROUND_ABOUT", 0));

        // Unknown direction name returns UNKNOWN
        Assert.assertEquals(TurnIcon.UNKNOWN, CoMapsDirections.icon("SOME_UNKNOWN_DIRECTION", 0));
        Assert.assertNull(CoMapsDirections.icon(null, 0));
    }

    @Test
    public void testRoundaboutExit() {
        Assert.assertEquals(3, CoMapsDirections.roundaboutExit("ENTER_ROUND_ABOUT", 3));
        Assert.assertEquals(1, CoMapsDirections.roundaboutExit("LEAVE_ROUND_ABOUT", 1));
        Assert.assertEquals(2, CoMapsDirections.roundaboutExit("STAY_ON_ROUND_ABOUT", 2));
        Assert.assertEquals(0, CoMapsDirections.roundaboutExit("TURN_LEFT", 2));
        Assert.assertEquals(0, CoMapsDirections.roundaboutExit(null, 2));
        Assert.assertEquals(0, CoMapsDirections.roundaboutExit("ENTER_ROUND_ABOUT", 0));
    }

    @Test
    public void testTerminalState() {
        Assert.assertTrue(CoMapsDirections.isTerminalState("RouteFinished"));
        Assert.assertTrue(CoMapsDirections.isTerminalState("NoValidRoute"));
        Assert.assertFalse(CoMapsDirections.isTerminalState("Navigating"));
        Assert.assertFalse(CoMapsDirections.isTerminalState("Recalculating"));
        Assert.assertFalse(CoMapsDirections.isTerminalState(null));
    }
}
