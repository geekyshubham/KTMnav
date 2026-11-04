package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class TomTomArrowsTest {

    @Test
    public void testKnownArrowHashes() {
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, TomTomArrows.lookup(2097022476));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, TomTomArrows.lookup(385054297));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, TomTomArrows.lookup(1506236594));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, TomTomArrows.lookup(-1904252653));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, TomTomArrows.lookup(-2003222442));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, TomTomArrows.lookup(-14846204));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, TomTomArrows.lookup(970810216));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, TomTomArrows.lookup(960866504));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, TomTomArrows.lookup(1484777465));
        Assert.assertEquals(TurnIcon.END, TomTomArrows.lookup(1933815519));
        Assert.assertEquals(TurnIcon.END, TomTomArrows.lookup(-1667933858));
        Assert.assertEquals(TurnIcon.END, TomTomArrows.lookup(1346204041));

        // Roundabout hashes
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, TomTomArrows.lookup(-49302981));
        Assert.assertEquals(TurnIcon.RAB_SECT_6_RH, TomTomArrows.lookup(1402751437));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, TomTomArrows.lookup(227907998));
        Assert.assertEquals(TurnIcon.RAB_SECT_10_RH, TomTomArrows.lookup(-826679603));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, TomTomArrows.lookup(-1323461244));
        Assert.assertEquals(TurnIcon.RAB_SECT_16_RH, TomTomArrows.lookup(-1121596958));
    }

    @Test
    public void testUnknownHash() {
        Assert.assertNull(TomTomArrows.lookup(0));
        Assert.assertNull(TomTomArrows.lookup(123456789));
    }

    @Test
    public void testFromReferenceName() {
        // Standard maneuvers
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, TomTomArrows.fromReferenceName("ic_decision_point_arrow_straight.png"));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, TomTomArrows.fromReferenceName("ic_decision_point_arrow_bear_left"));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, TomTomArrows.fromReferenceName("ic_decision_point_arrow_bear_right"));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, TomTomArrows.fromReferenceName("ic_decision_point_arrow_left"));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, TomTomArrows.fromReferenceName("ic_decision_point_arrow_right"));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, TomTomArrows.fromReferenceName("sharp_left"));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, TomTomArrows.fromReferenceName("sharp_right"));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, TomTomArrows.fromReferenceName("uturn_left"));
        Assert.assertEquals(TurnIcon.UTURN_RIGHT, TomTomArrows.fromReferenceName("uturn_right"));

        // Roundabouts RH
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, TomTomArrows.fromReferenceName("roundaboutright"));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, TomTomArrows.fromReferenceName("roundaboutcross"));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, TomTomArrows.fromReferenceName("roundaboutleft"));
        Assert.assertEquals(TurnIcon.RAB_SECT_16_RH, TomTomArrows.fromReferenceName("roundaboutaround"));

        // Roundabouts UK / LH
        Assert.assertEquals(TurnIcon.RAB_SECT_4_LH, TomTomArrows.fromReferenceName("roundaboutright_uk"));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_LH, TomTomArrows.fromReferenceName("roundaboutcross_uk"));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_LH, TomTomArrows.fromReferenceName("roundaboutleft_uk"));

        // Fallbacks
        Assert.assertNull(TomTomArrows.fromReferenceName(null));
        Assert.assertNull(TomTomArrows.fromReferenceName("unknown_maneuver"));
    }
}
