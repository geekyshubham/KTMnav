package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class ManeuverCaptureTest {

    @Test
    public void testCaptureConstants() {
        Assert.assertEquals("nav_capture", NavListener.CAPTURE_DIR);
        Assert.assertEquals("CAPTURE_MANEUVERS", NavListener.CAPTURE_MARKER);
        Assert.assertEquals("capture_maneuvers", NavListener.PREF_CAPTURE_MANEUVERS);
        Assert.assertEquals(100, NavListener.MAX_CAPTURES);
        Assert.assertEquals(48, ArrowIcons.SAMPLE_SIZE);
    }

    @Test
    public void testCaptureLabelSanitization() {
        // Road text takes precedence
        NavUpdate u1 = new NavUpdate("500 m", "Main St.", null, null, TurnIcon.LIGHT_LEFT, null);
        Assert.assertEquals("main_st", NavListener.captureLabel(u1));

        // Punctuation and spaces replaced with single underscores, trimmed
        NavUpdate u2 = new NavUpdate("1 km", "Exit 23B (North), I-95!", null, null, TurnIcon.QUITE_RIGHT, null);
        Assert.assertEquals("exit_23b_north_i_95", NavListener.captureLabel(u2));

        // When road text is null or empty, distance text is used
        NavUpdate u3 = new NavUpdate("250 m", null, null, null, TurnIcon.GO_STRAIGHT, null);
        Assert.assertEquals("250_m", NavListener.captureLabel(u3));

        NavUpdate u4 = new NavUpdate("300 m", "   ", null, null, TurnIcon.GO_STRAIGHT, null);
        Assert.assertEquals("300_m", NavListener.captureLabel(u4));

        // When both are absent, fallback is "unlabeled"
        NavUpdate u5 = new NavUpdate(null, null, null, null, TurnIcon.UNDEFINED, null);
        Assert.assertEquals("unlabeled", NavListener.captureLabel(u5));

        NavUpdate u6 = new NavUpdate("!!!???", "###$$$***", null, null, TurnIcon.UNDEFINED, null);
        Assert.assertEquals("unlabeled", NavListener.captureLabel(u6));

        // Capped at 40 characters
        String longRoad = "A very long road name that exceeds forty characters by a significant margin";
        NavUpdate u7 = new NavUpdate(null, longRoad, null, null, TurnIcon.HEAVY_LEFT, null);
        String label = NavListener.captureLabel(u7);
        Assert.assertTrue(label.length() <= 40);
        Assert.assertEquals(longRoad.toLowerCase().replaceAll("[^a-z0-9]+", "_").substring(0, 40), label);
    }

    @Test
    public void testArrowIconsSavePngValidation() {
        File dummyFile = new File("test.png");

        // Null file check
        try {
            ArrowIcons.savePng(null, new int[48 * 48]);
            Assert.fail("Expected IllegalArgumentException for null file");
        } catch (IllegalArgumentException | IOException expected) {
            // Success
        }

        // Null pixels check
        try {
            ArrowIcons.savePng(dummyFile, null);
            Assert.fail("Expected IllegalArgumentException for null pixels");
        } catch (IllegalArgumentException | IOException expected) {
            // Success
        }

        // Wrong dimension check (must be 48 * 48 = 2304)
        try {
            ArrowIcons.savePng(dummyFile, new int[100]);
            Assert.fail("Expected IllegalArgumentException for invalid pixel count");
        } catch (IllegalArgumentException | IOException expected) {
            // Success
        }
    }
}
