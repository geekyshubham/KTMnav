package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class Gen2IconsTest {

    @Test
    public void testStandardIconMappings() {
        Assert.assertEquals("GO_STRAIGHT", Gen2Icons.gen2Name(TurnIcon.GO_STRAIGHT));
        Assert.assertEquals("QUITE_LEFT", Gen2Icons.gen2Name(TurnIcon.QUITE_LEFT));
        Assert.assertEquals("QUITE_RIGHT", Gen2Icons.gen2Name(TurnIcon.QUITE_RIGHT));
        Assert.assertEquals("LIGHT_LEFT", Gen2Icons.gen2Name(TurnIcon.LIGHT_LEFT));
        Assert.assertEquals("LIGHT_RIGHT", Gen2Icons.gen2Name(TurnIcon.LIGHT_RIGHT));
        Assert.assertEquals("HEAVY_LEFT", Gen2Icons.gen2Name(TurnIcon.HEAVY_LEFT));
        Assert.assertEquals("HEAVY_RIGHT", Gen2Icons.gen2Name(TurnIcon.HEAVY_RIGHT));
        Assert.assertEquals("UTURN_LEFT", Gen2Icons.gen2Name(TurnIcon.UTURN_LEFT));
        Assert.assertEquals("UTURN_RIGHT", Gen2Icons.gen2Name(TurnIcon.UTURN_RIGHT));
        Assert.assertEquals("KEEP_LEFT", Gen2Icons.gen2Name(TurnIcon.KEEP_LEFT));
        Assert.assertEquals("KEEP_RIGHT", Gen2Icons.gen2Name(TurnIcon.KEEP_RIGHT));
        Assert.assertEquals("LEAVE_HIGHWAY_LEFT_LANE", Gen2Icons.gen2Name(TurnIcon.LEAVE_HIGHWAY_LEFT_LANE));
        Assert.assertEquals("LEAVE_HIGHWAY_RIGHT_LANE", Gen2Icons.gen2Name(TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE));
        Assert.assertEquals("END", Gen2Icons.gen2Name(TurnIcon.END));
        Assert.assertEquals("FERRY", Gen2Icons.gen2Name(TurnIcon.FERRY));
    }

    @Test
    public void testAliasIconMappings() {
        // KEEP_MIDDLE, START, and HEAD_TO map to GO_STRAIGHT
        Assert.assertEquals("GO_STRAIGHT", Gen2Icons.gen2Name(TurnIcon.KEEP_MIDDLE));
        Assert.assertEquals("GO_STRAIGHT", Gen2Icons.gen2Name(TurnIcon.START));
        Assert.assertEquals("GO_STRAIGHT", Gen2Icons.gen2Name(TurnIcon.HEAD_TO));

        // Highway entry and keep right map to KEEP_RIGHT
        Assert.assertEquals("KEEP_RIGHT", Gen2Icons.gen2Name(TurnIcon.ENTER_HIGHWAY_RIGHT_LANE));
        Assert.assertEquals("KEEP_RIGHT", Gen2Icons.gen2Name(TurnIcon.HIGHWAY_KEEP_RIGHT));

        // Highway entry and keep left map to KEEP_LEFT
        Assert.assertEquals("KEEP_LEFT", Gen2Icons.gen2Name(TurnIcon.ENTER_HIGHWAY_LEFT_LANE));
        Assert.assertEquals("KEEP_LEFT", Gen2Icons.gen2Name(TurnIcon.HIGHWAY_KEEP_LEFT));
    }

    @Test
    public void testUnsupportedIconsReturnNull() {
        Assert.assertNull(Gen2Icons.gen2Name(null));
        Assert.assertNull(Gen2Icons.gen2Name(TurnIcon.UNKNOWN));
        Assert.assertNull(Gen2Icons.gen2Name(TurnIcon.UNDEFINED));
        Assert.assertNull(Gen2Icons.gen2Name(TurnIcon.PASS_STATION));
        Assert.assertNull(Gen2Icons.gen2Name(TurnIcon.CHANGE_LINE));
    }

    @Test
    public void testRoundaboutSectors() {
        // Even sectors within range [4, 16] remain even
        Assert.assertEquals("RAB_SECT_4_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_4_RH));
        Assert.assertEquals("RAB_SECT_6_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_6_RH));
        Assert.assertEquals("RAB_SECT_8_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_8_RH));
        Assert.assertEquals("RAB_SECT_10_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_10_RH));
        Assert.assertEquals("RAB_SECT_12_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_12_RH));
        Assert.assertEquals("RAB_SECT_14_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_14_RH));
        Assert.assertEquals("RAB_SECT_16_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_16_RH));

        // Odd sectors get rounded up to next even number, clamped between 4 and 16
        Assert.assertEquals("RAB_SECT_4_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_1_RH));
        Assert.assertEquals("RAB_SECT_4_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_2_RH));
        Assert.assertEquals("RAB_SECT_4_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_3_RH));
        Assert.assertEquals("RAB_SECT_6_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_5_RH));
        Assert.assertEquals("RAB_SECT_8_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_7_RH));
        Assert.assertEquals("RAB_SECT_14_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_13_RH));
        Assert.assertEquals("RAB_SECT_16_RH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_15_RH));

        // Left-hand roundabouts
        Assert.assertEquals("RAB_SECT_4_LH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_4_LH));
        Assert.assertEquals("RAB_SECT_4_LH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_3_LH));
        Assert.assertEquals("RAB_SECT_8_LH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_8_LH));
        Assert.assertEquals("RAB_SECT_12_LH", Gen2Icons.gen2Name(TurnIcon.RAB_SECT_11_LH));
    }
}
