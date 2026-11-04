package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class KurvigerArrowsTest {

    @Test
    public void testKnownArrowHashes() {
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, KurvigerArrows.lookup(-1030405521));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, KurvigerArrows.lookup(-120869937));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, KurvigerArrows.lookup(1793735227));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, KurvigerArrows.lookup(169803519));
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, KurvigerArrows.lookup(238330173));
        Assert.assertEquals(TurnIcon.UTURN_LEFT, KurvigerArrows.lookup(-2097633863));

        // Roundabout hashes
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, KurvigerArrows.lookup(1499062392));
        Assert.assertEquals(TurnIcon.RAB_SECT_6_RH, KurvigerArrows.lookup(1274151701));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, KurvigerArrows.lookup(-1033920));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, KurvigerArrows.lookup(1111332233));
        Assert.assertEquals(TurnIcon.RAB_SECT_10_RH, KurvigerArrows.lookup(889647417));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, KurvigerArrows.lookup(1781226930));
        Assert.assertEquals(TurnIcon.RAB_SECT_13_RH, KurvigerArrows.lookup(-381065572));
        Assert.assertEquals(TurnIcon.RAB_SECT_16_RH, KurvigerArrows.lookup(1464775721));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, KurvigerArrows.lookup(-117021572));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, KurvigerArrows.lookup(-1707729307));

        Assert.assertEquals(TurnIcon.END, KurvigerArrows.lookup(66608485));
    }

    @Test
    public void testUnknownHashes() {
        Assert.assertNull(KurvigerArrows.lookup(0));
        Assert.assertNull(KurvigerArrows.lookup(123456789));
    }

    @Test
    public void testNonGuidanceMarker() {
        Assert.assertTrue(KurvigerArrows.isNonGuidance(162366997));
        Assert.assertFalse(KurvigerArrows.isNonGuidance(0));
        Assert.assertFalse(KurvigerArrows.isNonGuidance(66608485));
    }
}
