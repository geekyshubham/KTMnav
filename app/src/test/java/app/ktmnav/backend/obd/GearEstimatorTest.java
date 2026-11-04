package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GearEstimatorTest {

    private static final GearEstimator.Profile DUKE = GearEstimator.KTM_390_DUKE_GEN3;

    @Test
    public void exactRatiosMatchEachGear() {
        double[] table = DUKE.rpmPerKmh;
        for (int i = 0; i < table.length; i++) {
            assertEquals(Integer.valueOf(i + 1), GearEstimator.estimate(table[i] * 40.0, 40.0, DUKE));
        }
    }

    @Test
    public void withinToleranceMatches() {
        assertEquals(Integer.valueOf(3), GearEstimator.estimate(124 * 1.10 * 50, 50.0, DUKE));
        assertEquals(Integer.valueOf(1), GearEstimator.estimate(225 * 0.90 * 20, 20.0, DUKE));
    }

    @Test
    public void clutchInOrSlippingReturnsNull() {
        assertNull(GearEstimator.estimate(9000.0, 20.0, DUKE));
        assertNull(GearEstimator.estimate(2000.0, 60.0, DUKE));
    }

    @Test
    public void lowSpeedOrLowRpmReturnsNull() {
        assertNull(GearEstimator.estimate(2000.0, 4.9, DUKE));
        assertNull(GearEstimator.estimate(1499.0, 10.0, DUKE));
        assertNull(GearEstimator.estimate(null, 50.0, DUKE));
        assertNull(GearEstimator.estimate(5000.0, null, DUKE));
        assertNull(GearEstimator.estimate(5000.0, 50.0, null));
    }

    @Test
    public void profileIsLabelledUnverifiedAndEstimated() {
        assertFalse(DUKE.verified);
        assertTrue(DUKE.source.startsWith("Unverified"));
        assertEquals("KTM 390 Duke (2024 Gen 3, 6-speed)", DUKE.name);
        assertEquals(6, DUKE.gears());
        assertEquals("4 (estimated)", GearEstimator.label(4));
        assertEquals("- (estimated)", GearEstimator.label(null));
        assertEquals(DUKE, GearEstimator.profileById("unknown"));
    }
}
