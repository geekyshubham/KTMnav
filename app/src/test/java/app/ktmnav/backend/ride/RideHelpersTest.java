package app.ktmnav.backend.ride;

import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class RideHelpersTest {

    @Test
    public void recordingPolicy() {
        Assert.assertFalse(RidePrefs.shouldRecord("off", true, true));
        Assert.assertFalse(RidePrefs.shouldRecord(null, true, false));
        Assert.assertFalse(RidePrefs.shouldRecord("bogus", true, true));
        Assert.assertTrue(RidePrefs.shouldRecord("while_connected", true, false));
        Assert.assertFalse(RidePrefs.shouldRecord("while_connected", false, false));
        Assert.assertTrue(RidePrefs.shouldRecord("manual", false, true));
        Assert.assertFalse(RidePrefs.shouldRecord("manual", true, false));
        Assert.assertEquals(RidePrefs.MIN_OVERSPEED_LIMIT, RidePrefs.clampLimit(0));
        Assert.assertEquals(100, RidePrefs.clampVolume(150));
    }

    @Test
    public void projectionFitsBoundsKeepingAspect() {
        List<TrackPoint> pts = Arrays.asList(
                new TrackPoint(0, 0, Double.NaN, 0, 10f),
                new TrackPoint(0, 0.02, Double.NaN, 1000, 50f),
                new TrackPoint(0.01, 0.02, Double.NaN, 2000, 100f));
        float[] xy = TrackProjection.project(pts, 220, 220, 10);
        Assert.assertEquals(10f, xy[0], 1e-3);
        Assert.assertEquals(160f, xy[1], 1e-2);
        Assert.assertEquals(210f, xy[2], 1e-3);
        Assert.assertEquals(210f, xy[4], 1e-3);
        Assert.assertEquals(60f, xy[5], 1e-2);
        float[] single = TrackProjection.project(Arrays.asList(pts.get(0)), 100, 100, 10);
        Assert.assertEquals(50f, single[0], 1e-3);
        Assert.assertEquals(50f, single[1], 1e-3);
        Assert.assertEquals(0, TrackProjection.project(null, 100, 100, 10).length);
    }

    @Test
    public void speedBandsAndFormatting() {
        Assert.assertEquals(0, TrackProjection.band(10f));
        Assert.assertEquals(1, TrackProjection.band(45f));
        Assert.assertEquals(2, TrackProjection.band(75f));
        Assert.assertEquals(3, TrackProjection.band(120f));
        float[] s = TrackProjection.speeds(Arrays.asList(
                new TrackPoint(0, 0, Double.NaN, 0, Float.NaN),
                new TrackPoint(0, 0.001, Double.NaN, 10_000, Float.NaN)));
        Assert.assertEquals(0f, s[0], 0f);
        Assert.assertEquals(40.03f, s[1], 0.05f);
        Assert.assertEquals("850 m", RideFormat.distance(850));
        Assert.assertEquals("12.3 km", RideFormat.distance(12_345));
        Assert.assertEquals("1 h 05 min", RideFormat.duration(65 * 60_000L));
        Assert.assertEquals("Ride track, no points recorded", RideFormat.describe(RideStats.EMPTY));
    }
}
