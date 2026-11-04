package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class GearLearnerTest {

    private static final double[] TRUE_RATIOS = {173.4, 129.5, 97.9, 80.9, 69.4, 60.7};

    private static long ride(GearLearner learner, int samplesPerGear, long startMs, Random random) {
        long t = startMs;
        for (int g = 0; g < TRUE_RATIOS.length; g++) {
            double speed = 20 + g * 15;
            for (int i = 0; i < samplesPerGear; i++) {
                speed += (random.nextDouble() - 0.5);
                double rpm = speed * TRUE_RATIOS[g] * (1 + (random.nextDouble() - 0.5) * 0.02);
                learner.add(rpm, speed, t);
                t += 250;
            }
            t += 5000;
        }
        return t;
    }

    @Test
    public void learnsSixGearsFromSteadyRiding() {
        GearLearner learner = new GearLearner();
        ride(learner, 120, 0, new Random(7));
        double[] learned = learner.learn(6);
        assertNotNull(learned);
        for (int g = 0; g < 6; g++) {
            assertEquals("gear " + (g + 1), TRUE_RATIOS[g], learned[g], TRUE_RATIOS[g] * 0.03);
        }
        Integer gear = GearEstimator.estimate(5000.0, 5000.0 / TRUE_RATIOS[3], new GearEstimator.Profile("l", "l", learned, false, "t"));
        assertEquals(Integer.valueOf(4), gear);
    }

    @Test
    public void needsEnoughSamples() {
        GearLearner learner = new GearLearner();
        ride(learner, 40, 0, new Random(3));
        assertTrue(learner.samples() < GearLearner.MIN_SAMPLES);
        assertNull(learner.learn(6));
    }

    @Test
    public void ignoresUnsteadyLowSpeedAndClutchSamples() {
        GearLearner learner = new GearLearner();
        assertFalse(learner.add(3000, 40, 0));
        assertFalse(learner.add(3000, 48, 250));
        assertFalse(learner.add(1200, 48, 500));
        assertFalse(learner.add(4000, 10, 750));
        assertFalse(learner.add(4000, 10.5, 5000));
        assertTrue(learner.add(4000, 40, 5250) || learner.add(4000, 40.2, 5500));
    }

    @Test
    public void missingGearClusterDoesNotProduceWrongTable() {
        GearLearner learner = new GearLearner();
        Random random = new Random(11);
        long t = 0;
        for (int g = 0; g < 5; g++) {
            double speed = 25 + g * 15;
            for (int i = 0; i < 150; i++) {
                learner.add(speed * TRUE_RATIOS[g], speed, t);
                speed += (random.nextDouble() - 0.5);
                t += 250;
            }
            t += 5000;
        }
        assertNull(learner.learn(6));
    }

    @Test
    public void histogramSurvivesSerialisation() {
        GearLearner learner = new GearLearner();
        ride(learner, 120, 0, new Random(5));
        GearLearner restored = GearLearner.restore(learner.serialize());
        assertEquals(learner.samples(), restored.samples());
        assertNotNull(restored.learn(6));
        assertEquals(0, GearLearner.restore("garbage").samples());
    }

    @Test
    public void ratiosRoundTrip() {
        double[] parsed = GearLearner.parseRatios(GearLearner.formatRatios(TRUE_RATIOS));
        assertNotNull(parsed);
        assertEquals(6, parsed.length);
        assertEquals(60.7, parsed[5], 0.05);
        assertNull(GearLearner.parseRatios("1,x"));
        assertNull(GearLearner.parseRatios("1,-2"));
    }
}
