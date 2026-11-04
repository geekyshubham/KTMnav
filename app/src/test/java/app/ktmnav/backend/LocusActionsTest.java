package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class LocusActionsTest {

    @Test
    public void testPointRteActionLookups() {
        Assert.assertEquals(PointRteAction.CONTINUE_STRAIGHT, PointRteAction.getActionById(1));
        Assert.assertEquals(PointRteAction.LEFT, PointRteAction.getActionById(4));
        Assert.assertEquals(PointRteAction.RIGHT, PointRteAction.getActionById(7));
        Assert.assertEquals(PointRteAction.UNDEFINED, PointRteAction.getActionById(-999));

        Assert.assertEquals(PointRteAction.LEFT, PointRteAction.getActionByText("left"));
        Assert.assertEquals(PointRteAction.LEFT, PointRteAction.getActionByText("turn-left"));
        Assert.assertEquals(PointRteAction.RIGHT, PointRteAction.getActionByText("turn-right"));
        Assert.assertEquals(PointRteAction.CONTINUE_STRAIGHT, PointRteAction.getActionByText("straight"));
        Assert.assertEquals(PointRteAction.UNDEFINED, PointRteAction.getActionByText(null));
        Assert.assertEquals(PointRteAction.UNDEFINED, PointRteAction.getActionByText(""));
    }

    @Test
    public void testPointRteActionRoundabout() {
        Assert.assertEquals(PointRteAction.ROUNDABOUT_EXIT_1, PointRteAction.getActionRoundabout(1));
        Assert.assertEquals(PointRteAction.ROUNDABOUT_EXIT_4, PointRteAction.getActionRoundabout(4));
        Assert.assertEquals(PointRteAction.ROUNDABOUT_EXIT_8, PointRteAction.getActionRoundabout(8));
        Assert.assertEquals(PointRteAction.ROUNDABOUT_EXIT_1, PointRteAction.getActionRoundabout(0));
        Assert.assertEquals(PointRteAction.ROUNDABOUT_EXIT_8, PointRteAction.getActionRoundabout(12));
    }

    @Test
    public void testPointRteActionByAngle() {
        Assert.assertEquals(PointRteAction.CONTINUE_STRAIGHT, PointRteAction.getActionByAngle(15.0f));
        Assert.assertEquals(PointRteAction.RIGHT_SLIGHT, PointRteAction.getActionByAngle(35.0f));
        Assert.assertEquals(PointRteAction.RIGHT, PointRteAction.getActionByAngle(90.0f));
        Assert.assertEquals(PointRteAction.RIGHT_SHARP, PointRteAction.getActionByAngle(150.0f));
        Assert.assertEquals(PointRteAction.U_TURN_RIGHT, PointRteAction.getActionByAngle(175.0f));
        Assert.assertEquals(PointRteAction.U_TURN_LEFT, PointRteAction.getActionByAngle(185.0f));
        Assert.assertEquals(PointRteAction.LEFT_SHARP, PointRteAction.getActionByAngle(210.0f));
        Assert.assertEquals(PointRteAction.LEFT, PointRteAction.getActionByAngle(270.0f));
        Assert.assertEquals(PointRteAction.LEFT_SLIGHT, PointRteAction.getActionByAngle(320.0f));
        Assert.assertEquals(PointRteAction.CONTINUE_STRAIGHT, PointRteAction.getActionByAngle(350.0f));
    }

    @Test
    public void testLocusActionToTurnIcon() {
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, LocusActions.icon(PointRteAction.CONTINUE_STRAIGHT));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, LocusActions.icon(PointRteAction.NO_MANEUVER));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, LocusActions.icon(PointRteAction.LEFT));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, LocusActions.icon(PointRteAction.RIGHT));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, LocusActions.icon(PointRteAction.LEFT_SLIGHT));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, LocusActions.icon(PointRteAction.RIGHT_SLIGHT));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, LocusActions.icon(PointRteAction.LEFT_SHARP));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, LocusActions.icon(PointRteAction.RIGHT_SHARP));
        Assert.assertEquals(TurnIcon.KEEP_LEFT, LocusActions.icon(PointRteAction.STAY_LEFT));
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, LocusActions.icon(PointRteAction.STAY_RIGHT));
        Assert.assertEquals(TurnIcon.KEEP_MIDDLE, LocusActions.icon(PointRteAction.STAY_STRAIGHT));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, LocusActions.icon(PointRteAction.U_TURN_LEFT));
        Assert.assertEquals(TurnIcon.UTURN_RIGHT, LocusActions.icon(PointRteAction.U_TURN_RIGHT));
        Assert.assertEquals(TurnIcon.LEAVE_HIGHWAY_LEFT_LANE, LocusActions.icon(PointRteAction.EXIT_LEFT));
        Assert.assertEquals(TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE, LocusActions.icon(PointRteAction.EXIT_RIGHT));
        Assert.assertEquals(TurnIcon.ENTER_HIGHWAY_LEFT_LANE, LocusActions.icon(PointRteAction.MERGE_LEFT));
        Assert.assertEquals(TurnIcon.ENTER_HIGHWAY_RIGHT_LANE, LocusActions.icon(PointRteAction.MERGE_RIGHT));
        Assert.assertEquals(TurnIcon.END, LocusActions.icon(PointRteAction.ARRIVE_DEST));
        Assert.assertEquals(TurnIcon.PASS_STATION, LocusActions.icon(PointRteAction.PASS_PLACE));

        // Roundabout exits mapped via RoundaboutGlyph
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, LocusActions.icon(PointRteAction.ROUNDABOUT_EXIT_1));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, LocusActions.icon(PointRteAction.ROUNDABOUT_EXIT_2));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, LocusActions.icon(PointRteAction.ROUNDABOUT_EXIT_3));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, LocusActions.icon(PointRteAction.ROUNDABOUT_EXIT_4));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, LocusActions.icon(PointRteAction.ROUNDABOUT_EXIT_8));

        Assert.assertNull(LocusActions.icon(null));
        Assert.assertNull(LocusActions.icon(PointRteAction.UNDEFINED));
    }

    @Test
    public void testRoundaboutExitExtraction() {
        Assert.assertEquals(1, LocusActions.roundaboutExit(PointRteAction.ROUNDABOUT_EXIT_1));
        Assert.assertEquals(4, LocusActions.roundaboutExit(PointRteAction.ROUNDABOUT_EXIT_4));
        Assert.assertEquals(5, LocusActions.roundaboutExit(PointRteAction.ROUNDABOUT_EXIT_5));
        Assert.assertEquals(8, LocusActions.roundaboutExit(PointRteAction.ROUNDABOUT_EXIT_8));
        Assert.assertEquals(0, LocusActions.roundaboutExit(PointRteAction.LEFT));
        Assert.assertEquals(0, LocusActions.roundaboutExit(null));
    }

    @Test
    public void testDistanceFormatting() {
        Assert.assertNull(LocusActions.distanceText(-10));
        Assert.assertNull(LocusActions.distanceText(0));
        Assert.assertEquals("450 m", LocusActions.distanceText(450.4));
        Assert.assertEquals("999 m", LocusActions.distanceText(999.4));
        Assert.assertEquals("1.5 km", LocusActions.distanceText(1500));
        Assert.assertEquals("9.9 km", LocusActions.distanceText(9940));
        Assert.assertEquals("12 km", LocusActions.distanceText(12400));
    }

    @Test
    public void testEtaFormatting() {
        Assert.assertNull(LocusActions.etaText(System.currentTimeMillis(), -5));
        Assert.assertNull(LocusActions.etaText(System.currentTimeMillis(), 0));

        long now = 1700000000000L;
        String eta = LocusActions.etaText(now, 3600000L);
        Assert.assertNotNull(eta);
        Assert.assertTrue(eta.matches("\\d{2}:\\d{2}"));
    }
}
