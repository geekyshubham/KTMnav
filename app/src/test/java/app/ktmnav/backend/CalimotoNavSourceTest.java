package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class CalimotoNavSourceTest {

    @Test
    public void testParseTextsBasicTurn() {
        NavUpdate update = CalimotoNavSource.parseTexts("In 250 m turn right", "Turn right onto Mountain Pass");
        Assert.assertNotNull(update);
        Assert.assertEquals("250 m", update.getDistanceText());
        Assert.assertEquals("Mountain Pass", update.getRoadText());
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, update.getTurnIcon());
    }

    @Test
    public void testParseTextsGerman() {
        NavUpdate update = CalimotoNavSource.parseTexts("In 1.2 km rechts abbiegen", "Rechts abbiegen auf Seestraße");
        Assert.assertNotNull(update);
        Assert.assertEquals("1.2 km", update.getDistanceText());
        Assert.assertEquals("Seestraße", update.getRoadText());
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, update.getTurnIcon());
    }

    @Test
    public void testParseRoundaboutEnglish() {
        NavUpdate update = CalimotoNavSource.parseTexts("In 150 m at the roundabout, take exit 2", "At the roundabout, take exit 2 onto Alpine Way");
        Assert.assertNotNull(update);
        Assert.assertEquals("150 m", update.getDistanceText());
        Assert.assertEquals("2 · Alpine Way", update.getRoadText());
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, update.getTurnIcon());
    }

    @Test
    public void testParseRoundaboutGerman() {
        NavUpdate update = CalimotoNavSource.parseTexts("In 300 m im Kreisverkehr Ausfahrt 3", "Im Kreisverkehr Ausfahrt 3 auf Bergstraße");
        Assert.assertNotNull(update);
        Assert.assertEquals("300 m", update.getDistanceText());
        Assert.assertEquals("3 · Bergstraße", update.getRoadText());
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, update.getTurnIcon());
    }

    @Test
    public void testResolveTurnIconDirectly() {
        Assert.assertEquals(TurnIcon.QUITE_LEFT, CalimotoNavSource.resolveTurnIcon("turn left"));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, CalimotoNavSource.resolveTurnIcon("make a sharp right"));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, CalimotoNavSource.resolveTurnIcon("make a slight left"));
        Assert.assertEquals(TurnIcon.KEEP_LEFT, CalimotoNavSource.resolveTurnIcon("stay left"));
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, CalimotoNavSource.resolveTurnIcon("keep right"));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, CalimotoNavSource.resolveTurnIcon("continue"));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, CalimotoNavSource.resolveTurnIcon("please make a u-turn"));
        Assert.assertEquals(TurnIcon.END, CalimotoNavSource.resolveTurnIcon("you've reached your destination"));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, CalimotoNavSource.resolveTurnIcon("scharf links abbiegen"));
        Assert.assertEquals(TurnIcon.ENTER_HIGHWAY_RIGHT_LANE, CalimotoNavSource.resolveTurnIcon("turn right and merge"));
        Assert.assertNull(CalimotoNavSource.resolveTurnIcon(null));
        Assert.assertNull(CalimotoNavSource.resolveTurnIcon("unrecognized phrase"));
    }

    @Test
    public void testRoundaboutExitOf() {
        Assert.assertEquals(2, CalimotoNavSource.roundaboutExitOf("at the roundabout, take exit 2"));
        Assert.assertEquals(4, CalimotoNavSource.roundaboutExitOf("in the roundabout, take exit 4"));
        Assert.assertEquals(1, CalimotoNavSource.roundaboutExitOf("im kreisverkehr ausfahrt 1"));
        Assert.assertEquals(0, CalimotoNavSource.roundaboutExitOf("turn right"));
        Assert.assertEquals(0, CalimotoNavSource.roundaboutExitOf(null));
    }

    @Test
    public void testNullAndBlankInputs() {
        Assert.assertNull(CalimotoNavSource.parseTexts(null, null));
        Assert.assertNull(CalimotoNavSource.parseTexts("   ", "   "));
        Assert.assertNull(CalimotoNavSource.parseTexts("unmatched", null));
    }
}
