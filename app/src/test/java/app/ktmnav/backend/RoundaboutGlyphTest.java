package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class RoundaboutGlyphTest {

    @Test
    public void testRightHandForExit() {
        // Exit 1 -> sector 4
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, RoundaboutGlyph.rightHandForExit(1));
        // Exit 2 -> sector 8
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, RoundaboutGlyph.rightHandForExit(2));
        // Exit 3 -> sector 12
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, RoundaboutGlyph.rightHandForExit(3));
        // Exit > 3 -> sector 14
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, RoundaboutGlyph.rightHandForExit(4));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, RoundaboutGlyph.rightHandForExit(5));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, RoundaboutGlyph.rightHandForExit(8));

        // Exit < 1 defaults to sector 8
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, RoundaboutGlyph.rightHandForExit(0));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, RoundaboutGlyph.rightHandForExit(-1));
    }

    @Test
    public void testToRightHandConversion() {
        // LH roundabouts map to corresponding RH roundabouts
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, RoundaboutGlyph.toRightHand(TurnIcon.RAB_SECT_4_LH));
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, RoundaboutGlyph.toRightHand(TurnIcon.RAB_SECT_8_LH));
        Assert.assertEquals(TurnIcon.RAB_SECT_12_RH, RoundaboutGlyph.toRightHand(TurnIcon.RAB_SECT_12_LH));
        Assert.assertEquals(TurnIcon.RAB_SECT_14_RH, RoundaboutGlyph.toRightHand(TurnIcon.RAB_SECT_14_LH));

        // Non-LH icons are returned unchanged
        Assert.assertEquals(TurnIcon.RAB_SECT_4_RH, RoundaboutGlyph.toRightHand(TurnIcon.RAB_SECT_4_RH));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, RoundaboutGlyph.toRightHand(TurnIcon.QUITE_LEFT));
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, RoundaboutGlyph.toRightHand(TurnIcon.QUITE_RIGHT));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, RoundaboutGlyph.toRightHand(TurnIcon.GO_STRAIGHT));
        Assert.assertNull(RoundaboutGlyph.toRightHand(null));
    }
}
