package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class NavTextsTest {

    @Test
    public void testBlankToNull() {
        Assert.assertNull(NavTexts.blankToNull(null));
        Assert.assertNull(NavTexts.blankToNull(""));
        Assert.assertNull(NavTexts.blankToNull("   "));
        Assert.assertNull(NavTexts.blankToNull("\t \n \r "));
        Assert.assertEquals("Main St", NavTexts.blankToNull("  Main St  "));
    }

    @Test
    public void testNormalizeSpaces() {
        Assert.assertNull(NavTexts.normalizeSpaces(null));
        Assert.assertEquals("Turn left onto Broadway", NavTexts.normalizeSpaces("Turn   left  onto Broadway"));

        // Unicode whitespace categories (\p{Zs}) like non-breaking space
        Assert.assertEquals("Turn left onto Broadway", NavTexts.normalizeSpaces("Turn\u00A0left\u2002onto\u00A0Broadway"));
    }

    @Test
    public void testParseEtaAndRemainingDistance() {
        // Standard bullet separated: ETA · distance · duration
        String[] res1 = NavTexts.parseEtaAndRemainingDistance("10:42 · 12.4 km · 15 min");
        Assert.assertEquals("10:42", res1[0]);
        Assert.assertEquals("12.4 km", res1[1]);

        // Reversed segments with dash separator
        String[] res2 = NavTexts.parseEtaAndRemainingDistance("5.2 mi - 14:20");
        Assert.assertEquals("14:20", res2[0]);
        Assert.assertEquals("5.2 mi", res2[1]);

        // 12-hour AM/PM normalization to 24-hour HH:mm
        String[] res3 = NavTexts.parseEtaAndRemainingDistance("1:45 pm • 25 km");
        Assert.assertEquals("13:45", res3[0]);
        Assert.assertEquals("25 km", res3[1]);

        String[] res4 = NavTexts.parseEtaAndRemainingDistance("08:15 am | 300 m");
        Assert.assertEquals("08:15", res4[0]);
        Assert.assertEquals("300 m", res4[1]);

        String[] res5 = NavTexts.parseEtaAndRemainingDistance("12:30 am — 10 km");
        Assert.assertEquals("00:30", res5[0]);
        Assert.assertEquals("10 km", res5[1]);

        String[] res6 = NavTexts.parseEtaAndRemainingDistance("12:15 pm – 15 mi");
        Assert.assertEquals("12:15", res6[0]);
        Assert.assertEquals("15 mi", res6[1]);

        // Comma decimal distance
        String[] res7 = NavTexts.parseEtaAndRemainingDistance("09:00 · 4,5 km");
        Assert.assertEquals("09:00", res7[0]);
        Assert.assertEquals("4,5 km", res7[1]);

        // Only time
        String[] res8 = NavTexts.parseEtaAndRemainingDistance("11:30");
        Assert.assertEquals("11:30", res8[0]);
        Assert.assertNull(res8[1]);

        // Only distance
        String[] res9 = NavTexts.parseEtaAndRemainingDistance("250 m");
        Assert.assertNull(res9[0]);
        Assert.assertEquals("250 m", res9[1]);

        // Empty, blank, and null
        String[] empty = NavTexts.parseEtaAndRemainingDistance(null);
        Assert.assertNull(empty[0]);
        Assert.assertNull(empty[1]);

        String[] blank = NavTexts.parseEtaAndRemainingDistance("   ");
        Assert.assertNull(blank[0]);
        Assert.assertNull(blank[1]);

        String[] noMatch = NavTexts.parseEtaAndRemainingDistance("No time or distance information here");
        Assert.assertNull(noMatch[0]);
        Assert.assertNull(noMatch[1]);
    }

    @Test
    public void testWithRoundaboutExit() {
        Assert.assertEquals("2 · Main St", NavTexts.withRoundaboutExit("Main St", 2));
        Assert.assertEquals("3", NavTexts.withRoundaboutExit(null, 3));
        Assert.assertEquals("1", NavTexts.withRoundaboutExit("   ", 1));

        // Non-positive exits return road unmodified
        Assert.assertEquals("Highway 101", NavTexts.withRoundaboutExit("Highway 101", 0));
        Assert.assertEquals("Highway 101", NavTexts.withRoundaboutExit("Highway 101", -1));
        Assert.assertNull(NavTexts.withRoundaboutExit(null, 0));
    }
}
