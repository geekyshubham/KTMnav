package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class OsmAndNavSourceTest {

    @Test
    public void testKnownArrowHashes() {
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OsmAndArrows.lookup(-1902801009));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OsmAndArrows.lookup(1117514489));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OsmAndArrows.lookup(-829445375));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OsmAndArrows.lookup(-1270134762));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OsmAndArrows.lookup(-1647226623));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, OsmAndArrows.lookup(958810625));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, OsmAndArrows.lookup(1570324737));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, OsmAndArrows.lookup(482803357));
        Assert.assertNull(OsmAndArrows.lookup(0));
        Assert.assertNull(OsmAndArrows.lookup(123456789));
    }

    @Test
    public void testParseTextsEnglish() {
        // OsmAnd format: title contains "<distance>•<turn phrase>"
        NavUpdate u1 = OsmAndNavSource.parseTexts("300 m\u2022turn left and go", "Oxford Street\n10:42 · 12.4 km");
        Assert.assertNotNull(u1);
        Assert.assertEquals("300 m", u1.getDistanceText());
        Assert.assertEquals("Oxford Street", u1.getRoadText());
        Assert.assertEquals("10:42", u1.getEtaText());
        Assert.assertEquals("12.4 km", u1.getRemainingDistanceText());
    }

    @Test
    public void testParseTextsGerman() {
        NavUpdate u2 = OsmAndNavSource.parseTexts("500 m\u2022rechts abbiegen, dann", "Hauptstraße\n14:30 · 50 km");
        Assert.assertNotNull(u2);
        Assert.assertEquals("500 m", u2.getDistanceText());
        Assert.assertEquals("Hauptstraße", u2.getRoadText());
        Assert.assertEquals("14:30", u2.getEtaText());
        Assert.assertEquals("50 km", u2.getRemainingDistanceText());
    }

    @Test
    public void testResolveTurnIconAndPhrases() {
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OsmAndNavSource.resolveTurnIcon("turn left and go", null));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OsmAndNavSource.resolveTurnIcon("turn right and go", null));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, OsmAndNavSource.resolveTurnIcon("turn slightly left and go", null));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, OsmAndNavSource.resolveTurnIcon("turn slightly right and go", null));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, OsmAndNavSource.resolveTurnIcon("turn sharply left and go", null));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, OsmAndNavSource.resolveTurnIcon("turn sharply right and go", null));
        Assert.assertEquals(TurnIcon.KEEP_LEFT, OsmAndNavSource.resolveTurnIcon("keep left and go", null));
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, OsmAndNavSource.resolveTurnIcon("keep right and go", null));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, OsmAndNavSource.resolveTurnIcon("make u-turn and go", null));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, OsmAndNavSource.resolveTurnIcon("geradeaus", null));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OsmAndNavSource.resolveTurnIcon("links abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OsmAndNavSource.resolveTurnIcon("rechts abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, OsmAndNavSource.resolveTurnIcon("halb links abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, OsmAndNavSource.resolveTurnIcon("halb rechts abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, OsmAndNavSource.resolveTurnIcon("scharf links abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, OsmAndNavSource.resolveTurnIcon("scharf rechts abbiegen, dann", null));
        Assert.assertEquals(TurnIcon.KEEP_LEFT, OsmAndNavSource.resolveTurnIcon("links halten, dann", null));
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, OsmAndNavSource.resolveTurnIcon("rechts halten, dann", null));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, OsmAndNavSource.resolveTurnIcon("wenden", null));

        // Roundabout extraction from phrase
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, OsmAndNavSource.resolveTurnIcon("Take exit 2", null));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, OsmAndNavSource.resolveTurnIcon("Ausfahrt 3", null));

        // Arrow hash fallback
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OsmAndNavSource.resolveTurnIcon(null, -1270134762));
        Assert.assertNull(OsmAndNavSource.resolveTurnIcon(null, null));
    }

    @Test
    public void testTurnPhraseAndRoundaboutExit() {
        Assert.assertEquals("turn left and go", OsmAndNavSource.turnPhraseOf("300 m\u2022turn left and go"));
        Assert.assertNull(OsmAndNavSource.turnPhraseOf("300 m"));
        Assert.assertNull(OsmAndNavSource.turnPhraseOf(null));

        Assert.assertEquals(3, OsmAndNavSource.roundaboutExitOf("Take exit 3"));
        Assert.assertEquals(2, OsmAndNavSource.roundaboutExitOf("Ausfahrt 2"));
        Assert.assertEquals(0, OsmAndNavSource.roundaboutExitOf("Continue straight"));
        Assert.assertEquals(0, OsmAndNavSource.roundaboutExitOf(null));
    }
}
