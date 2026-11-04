package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class DashTextTest {

    @Test
    public void asciiCleanVectors() {
        Assert.assertEquals("Cafe Strasse MG Road", DashText.asciiClean("Café Straße 🚀  MG Road"));
        Assert.assertEquals("Unter den Linden", DashText.asciiClean("Ünter den Linden"));
        Assert.assertEquals("Oresund", DashText.asciiClean("Øresund"));
        Assert.assertEquals("Ring Road", DashText.asciiClean("  Ring\tRoad\n"));
        Assert.assertEquals("a b", DashText.asciiClean("a b"));
        Assert.assertEquals("Mahatma Gandhi Marg - Exit 3", DashText.asciiClean("Mahatma Gandhi Marg – Exit 3"));
        Assert.assertEquals("\"Quoted\" 'text'...", DashText.asciiClean("“Quoted” ‘text’…"));
        Assert.assertEquals("", DashText.asciiClean("महात्मा गांधी मार्ग"));
        Assert.assertEquals("MG Road", DashText.asciiClean("महात्मा MG Road"));
        Assert.assertEquals("", DashText.asciiClean(null));
        Assert.assertEquals("", DashText.asciiClean("🚀🚀"));
    }

    @Test
    public void asciiOnlyKeepsSpacingForMarqueeFrames() {
        Assert.assertEquals("   Abec  x", DashText.asciiOnly("   Abéc 🚀x"));
        Assert.assertEquals("", DashText.asciiOnly(null));
    }

    @Test
    public void cleaningHappensBeforeLengthLimit() {
        byte[] p = BccuProtocol.INSTANCE.buildTurnInfoPayload("Ünterführung Straße 12 Süd", BccuProtocol.Visibility.FULL);
        Assert.assertEquals(17, p.length);
        Assert.assertEquals("Unterfuhrung ...", new String(p, 1, 16, StandardCharsets.US_ASCII));

        byte[] road = BccuProtocol.INSTANCE.buildTurnRoadPayload("Café 🚀 Road", BccuProtocol.Visibility.FULL);
        Assert.assertEquals("Cafe Road", new String(road, 1, road.length - 1, StandardCharsets.US_ASCII));

        byte[] banner = BccuProtocol.INSTANCE.buildNotificationPayload(null, "Grüße aus München!", BccuProtocol.Visibility.FULL);
        Assert.assertEquals(18, banner.length);
        for (int i = 2; i < banner.length; i++) {
            Assert.assertTrue((banner[i] & 0xFF) >= 0x20 && (banner[i] & 0xFF) <= 0x7E);
        }
    }

    @Test
    public void etaNormalisation() {
        Assert.assertEquals("02:12", DashText.normalizeEta("2:12 am"));
        Assert.assertEquals("14:12", DashText.normalizeEta("2:12 PM"));
        Assert.assertEquals("00:05", DashText.normalizeEta("12:05 a.m."));
        Assert.assertEquals("12:30", DashText.normalizeEta("12:30 pm"));
        Assert.assertEquals("14:12", DashText.normalizeEta("14:12 Uhr"));
        Assert.assertEquals("14:12", DashText.normalizeEta("14h12"));
        Assert.assertEquals("09:05", DashText.normalizeEta("ETA 9:05"));
        Assert.assertEquals("23:59", DashText.normalizeEta("Arrive 23:59 · 12 km"));
        Assert.assertEquals("10:42", DashText.normalizeEta("10:42"));
        Assert.assertNull(DashText.normalizeEta("KTMnav"));
        Assert.assertNull(DashText.normalizeEta("--:--"));
        Assert.assertNull(DashText.normalizeEta("1 h 20 min"));
        Assert.assertNull(DashText.normalizeEta("25:00"));
        Assert.assertNull(DashText.normalizeEta("13:00 pm"));
        Assert.assertNull(DashText.normalizeEta("12:60"));
        Assert.assertNull(DashText.normalizeEta("123:45"));
        Assert.assertNull(DashText.normalizeEta(""));
        Assert.assertNull(DashText.normalizeEta(null));
    }

    @Test
    public void gen2JsonStringsAreCleaned() throws Exception {
        org.json.JSONObject g = Gen2Frames.guidance(1, "Café Straße 🚀", "250", "m", "QUITE_RIGHT", "14:12", null, null, "Grüße");
        org.json.JSONObject ui = g.getJSONObject("UpdateUI");
        Assert.assertEquals("Cafe Strasse", ui.getJSONObject("TurnRoad").getString("Text"));
        Assert.assertEquals("Grusse", ui.getJSONObject("NotificationText").getString("Text"));
        org.json.JSONObject blank = Gen2Frames.guidance(2, "🚀", null, null, null, null, null, null, null);
        Assert.assertEquals("off", blank.getJSONObject("UpdateUI").getJSONObject("TurnRoad").getString("Visibility"));
    }
}
