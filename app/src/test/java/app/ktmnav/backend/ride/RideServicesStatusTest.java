package app.ktmnav.backend.ride;

import org.junit.Assert;
import org.junit.Test;

public class RideServicesStatusTest {

    @Test
    public void gpsIsStaleOnlyAfterThirtySecondsWithoutAFix() {
        Assert.assertFalse(RideServices.isGpsStale(30_000L, 0L, 0L));
        Assert.assertTrue(RideServices.isGpsStale(30_001L, 0L, 0L));
        Assert.assertFalse("a recent fix keeps the status active", RideServices.isGpsStale(100_000L, 0L, 80_000L));
        Assert.assertTrue(RideServices.isGpsStale(100_000L, 0L, 60_000L));
        Assert.assertFalse("a fix from an earlier session does not count against a new one",
                RideServices.isGpsStale(100_000L, 90_000L, 10_000L));
    }
}
