package app.ktmnav.backend.ride;

import org.junit.Assert;
import org.junit.Test;

public class OverspeedLogicTest {

    @Test
    public void alertsAboveLimitWithBanner() {
        OverspeedLogic l = new OverspeedLogic();
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(79f, 80, 0));
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(80f, 80, 1000));
        OverspeedLogic.Action a = l.onSpeed(84.6f, 80, 2000);
        Assert.assertTrue(a.beep);
        Assert.assertEquals("OVERSPEED 85", a.banner);
        Assert.assertTrue(l.isAlerting());
    }

    @Test
    public void bannerClearsAtLimitWhileBeepHysteresisHolds() {
        OverspeedLogic l = new OverspeedLogic();
        l.onSpeed(90f, 80, 0);
        Assert.assertTrue(l.isBannerShown());
        OverspeedLogic.Action a = l.onSpeed(80f, 80, 500);
        Assert.assertTrue("at the limit the banner clears", a.clearBanner);
        Assert.assertNull(a.banner);
        Assert.assertTrue(l.isAlerting());
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(75f, 80, 1000));
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(70f, 80, 2000));
        Assert.assertSame("already cleared: re-arm needs no second clear", OverspeedLogic.Action.NONE, l.onSpeed(69.9f, 80, 3000));
        Assert.assertFalse(l.isAlerting());
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(78f, 80, 4000));
    }

    @Test
    public void beepIsRateLimitedToTenSeconds() {
        OverspeedLogic l = new OverspeedLogic();
        int beeps = 0;
        for (long t = 0; t < 30_000; t += 1000) if (l.onSpeed(95f, 80, t).beep) beeps++;
        Assert.assertEquals(3, beeps);
    }

    @Test
    public void reEnteringOverspeedInsideHysteresisKeepsRateLimit() {
        OverspeedLogic l = new OverspeedLogic();
        Assert.assertTrue(l.onSpeed(90f, 80, 0).beep);
        Assert.assertTrue(l.onSpeed(78f, 80, 1000).clearBanner);
        OverspeedLogic.Action a = l.onSpeed(85f, 80, 2000);
        Assert.assertFalse("same episode: no beep before the interval", a.beep);
        Assert.assertEquals("banner returns above the limit", "OVERSPEED 85", a.banner);
        Assert.assertTrue(l.onSpeed(85f, 80, 10_000).beep);
    }

    @Test
    public void rearmAfterHysteresisStartsAFreshEpisode() {
        OverspeedLogic l = new OverspeedLogic();
        Assert.assertTrue(l.onSpeed(90f, 80, 0).beep);
        Assert.assertTrue(l.onSpeed(60f, 80, 1000).clearBanner);
        Assert.assertFalse(l.isAlerting());
        OverspeedLogic.Action a = l.onSpeed(90f, 80, 2000);
        Assert.assertTrue("beeped is reset on re-arm", a.beep);
        Assert.assertEquals("OVERSPEED 90", a.banner);
        Assert.assertTrue(l.isAlerting());
    }

    @Test
    public void noBannerOrBeepInsideHysteresisBand() {
        OverspeedLogic l = new OverspeedLogic();
        l.onSpeed(85f, 80, 0);
        OverspeedLogic.Action a = l.onSpeed(78f, 80, 20_000);
        Assert.assertFalse(a.beep);
        Assert.assertNull("no ALERT banner below the limit", a.banner);
        Assert.assertTrue(a.clearBanner);
        Assert.assertSame(OverspeedLogic.Action.NONE, l.onSpeed(79f, 80, 25_000));
    }

    @Test
    public void bannerRefreshThrottled() {
        OverspeedLogic l = new OverspeedLogic();
        Assert.assertNotNull(l.onSpeed(90f, 80, 0).banner);
        Assert.assertNull(l.onSpeed(90.2f, 80, 1000).banner);
        Assert.assertNotNull(l.onSpeed(90.2f, 80, 2000).banner);
        Assert.assertEquals("OVERSPEED 92", l.onSpeed(92f, 80, 2500).banner);
    }

    @Test
    public void resetClearsOnlyWhenAlerting() {
        OverspeedLogic l = new OverspeedLogic();
        Assert.assertFalse(l.reset().clearBanner);
        l.onSpeed(100f, 80, 0);
        Assert.assertTrue(l.reset().clearBanner);
        Assert.assertFalse(l.isAlerting());
    }
}
