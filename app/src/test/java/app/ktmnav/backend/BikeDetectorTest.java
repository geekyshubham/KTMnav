package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for BikeDetector zero-power BLE scan arming prerequisites and generation routing.
 */
public class BikeDetectorTest {

    @Test
    public void testNullContextHandling() {
        Assert.assertFalse(BikeDetector.arm(null));
        // Disarm should be completely safe with null
        BikeDetector.disarm(null);
        Assert.assertFalse(BikeDetector.isArmed());
    }

    @Test
    public void testGenerationGating() {
        // Gen2 bikes use RFCOMM Bluetooth Classic and must never arm BLE scanning
        Assert.assertEquals(BikeGeneration.GEN2, BikeGeneration.from("GEN2"));
        // Gen3 bikes use BLE and are eligible for offloaded filtered scanning
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("GEN3"));
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("GEN1"));
    }
}
