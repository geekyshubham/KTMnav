package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class TurnIconTest {

    @Test
    public void testAllBinaryCodesUniqueExceptUnknown() {
        Set<Integer> seen = new HashSet<>();
        for (TurnIcon icon : TurnIcon.values()) {
            if (icon == TurnIcon.UNKNOWN) continue;
            int binary = icon.getBinary();
            Assert.assertTrue("Duplicate binary code: " + binary + " on " + icon, seen.add(binary));
            Assert.assertEquals("Roundtrip fromBinary failed for " + icon, icon, TurnIcon.fromBinary(binary));
        }
    }

    @Test
    public void testOutOfRangeReturnsUnknown() {
        Assert.assertEquals(TurnIcon.UNKNOWN, TurnIcon.fromBinary(-1));
        Assert.assertEquals(TurnIcon.UNKNOWN, TurnIcon.fromBinary(0));
        Assert.assertEquals(TurnIcon.UNKNOWN, TurnIcon.fromBinary(999));
    }

    @Test
    public void testExactProtocolBinaryValues() {
        Assert.assertEquals(0, TurnIcon.UNKNOWN.getBinary());
        Assert.assertEquals(1, TurnIcon.UNDEFINED.getBinary());
        Assert.assertEquals(2, TurnIcon.GO_STRAIGHT.getBinary());
        Assert.assertEquals(3, TurnIcon.UTURN_RIGHT.getBinary());
        Assert.assertEquals(4, TurnIcon.UTURN_LEFT.getBinary());
        Assert.assertEquals(5, TurnIcon.KEEP_RIGHT.getBinary());
        Assert.assertEquals(6, TurnIcon.LIGHT_RIGHT.getBinary());
        Assert.assertEquals(7, TurnIcon.QUITE_RIGHT.getBinary());
        Assert.assertEquals(8, TurnIcon.HEAVY_RIGHT.getBinary());
        Assert.assertEquals(9, TurnIcon.KEEP_MIDDLE.getBinary());
        Assert.assertEquals(10, TurnIcon.KEEP_LEFT.getBinary());
        Assert.assertEquals(11, TurnIcon.LIGHT_LEFT.getBinary());
        Assert.assertEquals(12, TurnIcon.QUITE_LEFT.getBinary());
        Assert.assertEquals(13, TurnIcon.HEAVY_LEFT.getBinary());
        Assert.assertEquals(14, TurnIcon.ENTER_HIGHWAY_RIGHT_LANE.getBinary());
        Assert.assertEquals(15, TurnIcon.ENTER_HIGHWAY_LEFT_LANE.getBinary());
        Assert.assertEquals(16, TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE.getBinary());
        Assert.assertEquals(17, TurnIcon.LEAVE_HIGHWAY_LEFT_LANE.getBinary());
        Assert.assertEquals(18, TurnIcon.HIGHWAY_KEEP_RIGHT.getBinary());
        Assert.assertEquals(19, TurnIcon.HIGHWAY_KEEP_LEFT.getBinary());
        Assert.assertEquals(20, TurnIcon.START.getBinary());
        Assert.assertEquals(21, TurnIcon.END.getBinary());
        Assert.assertEquals(22, TurnIcon.FERRY.getBinary());
        Assert.assertEquals(23, TurnIcon.PASS_STATION.getBinary());
        Assert.assertEquals(24, TurnIcon.HEAD_TO.getBinary());
        Assert.assertEquals(25, TurnIcon.CHANGE_LINE.getBinary());

        // Roundabout right-hand sectors: 26 to 41
        Assert.assertEquals(26, TurnIcon.RAB_SECT_1_RH.getBinary());
        Assert.assertEquals(29, TurnIcon.RAB_SECT_4_RH.getBinary());
        Assert.assertEquals(33, TurnIcon.RAB_SECT_8_RH.getBinary());
        Assert.assertEquals(37, TurnIcon.RAB_SECT_12_RH.getBinary());
        Assert.assertEquals(41, TurnIcon.RAB_SECT_16_RH.getBinary());

        // Roundabout left-hand sectors: 42 to 57
        Assert.assertEquals(42, TurnIcon.RAB_SECT_1_LH.getBinary());
        Assert.assertEquals(45, TurnIcon.RAB_SECT_4_LH.getBinary());
        Assert.assertEquals(49, TurnIcon.RAB_SECT_8_LH.getBinary());
        Assert.assertEquals(53, TurnIcon.RAB_SECT_12_LH.getBinary());
        Assert.assertEquals(57, TurnIcon.RAB_SECT_16_LH.getBinary());
    }
}
