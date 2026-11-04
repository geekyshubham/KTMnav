package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class IdleTimingTest {

    @Test
    public void testIdleTimingAndLabelsParity() {
        // Original TurnIt IDLE_DRAW_DELAY_MS is exactly 4500ms
        Assert.assertEquals(4500L, IdleDisplayManager.IDLE_DRAW_DELAY_MS);

        // The ETA slot renders only bare HH:MM, so idle screens leave it empty (sent with visibility OFF)
        Assert.assertEquals("", IdleDisplayManager.IDLE_ETA_LABEL);

        // Placeholders match original TurnIt IdleDisplayController
        Assert.assertEquals("--:--", IdleDisplayManager.ETA_PLACEHOLDER);
        Assert.assertEquals("N/A", IdleDisplayManager.REMAINING_PLACEHOLDER);
    }

    @Test
    public void testMediaDashTextFormatting() {
        NowPlaying full = new NowPlaying("Song Title", "Artist Name");
        Assert.assertEquals("Song Title - Artist Name", full.toDashText());

        NowPlaying titleOnly = new NowPlaying("Song Title", null);
        Assert.assertEquals("Song Title", titleOnly.toDashText());

        NowPlaying artistOnly = new NowPlaying(null, "Artist Name");
        Assert.assertEquals("Artist Name", artistOnly.toDashText());

        NowPlaying empty = new NowPlaying("   ", "");
        Assert.assertEquals("", empty.toDashText());
    }

    @Test
    public void testIdleMediaDelayDistinctFromBackgroundTimeout() {
        // Preference idle_timeout (typically 30s) is for low power / disconnect.
        // Media idle draw MUST use fixed 4.5s delay.
        long backgroundTimeout = 30000L;
        Assert.assertNotEquals(backgroundTimeout, IdleDisplayManager.IDLE_DRAW_DELAY_MS);
        Assert.assertEquals(4500L, IdleDisplayManager.IDLE_DRAW_DELAY_MS);
    }
}
