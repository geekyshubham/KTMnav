package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class OrganicMapsArrowsTest {

    @Test
    public void testKnownArrowHashes() {
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, OrganicMapsArrows.lookup(1175128587));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, OrganicMapsArrows.lookup(171650717));
        Assert.assertEquals(TurnIcon.LIGHT_LEFT, OrganicMapsArrows.lookup(656681198));
        Assert.assertEquals(TurnIcon.LIGHT_RIGHT, OrganicMapsArrows.lookup(-658627802));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, OrganicMapsArrows.lookup(967090251));
        Assert.assertEquals(TurnIcon.END, OrganicMapsArrows.lookup(613276588));
        Assert.assertEquals(TurnIcon.END, OrganicMapsArrows.lookup(-1473966237));

        // Roundabout hashes via RoundaboutGlyph
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, OrganicMapsArrows.lookup(1658589293));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, OrganicMapsArrows.lookup(-117021572));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, OrganicMapsArrows.lookup(-1707729307));
    }

    @Test
    public void testUnknownHash() {
        Assert.assertNull(OrganicMapsArrows.lookup(0));
        Assert.assertNull(OrganicMapsArrows.lookup(123456789));
    }

    @Test
    public void testExitFor() {
        Assert.assertEquals(1, OrganicMapsArrows.exitFor(1658589293));
        Assert.assertEquals(2, OrganicMapsArrows.exitFor(-117021572));
        Assert.assertEquals(3, OrganicMapsArrows.exitFor(-1707729307));
        Assert.assertEquals(0, OrganicMapsArrows.exitFor(1175128587));
        Assert.assertEquals(0, OrganicMapsArrows.exitFor(0));
    }
}
