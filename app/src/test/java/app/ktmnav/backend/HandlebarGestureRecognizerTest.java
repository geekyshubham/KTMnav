package app.ktmnav.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class HandlebarGestureRecognizerTest {

    private static final int UP = HandlebarGestureRecognizer.MASK_UP;
    private static final int DOWN = HandlebarGestureRecognizer.MASK_DOWN;
    private static final int SET = HandlebarGestureRecognizer.MASK_SET;
    private static final int BACK = HandlebarGestureRecognizer.MASK_BACK;

    private FakeScheduler scheduler;
    private final List<String> events = new ArrayList<>();
    private final List<Long> eventTimes = new ArrayList<>();
    private final Set<String> doubleButtons = new HashSet<>();
    private HandlebarGestureRecognizer recognizer;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        recognizer = new HandlebarGestureRecognizer(scheduler, doubleButtons::contains, (button, gesture) -> {
            events.add(button + ":" + gesture.name());
            eventTimes.add(scheduler.now());
        });
    }

    private void frame(int mask, long t) {
        scheduler.advanceTo(t);
        recognizer.onMask(mask, t);
    }

    @Test
    public void tapWithoutDoubleBindingEmitsSingleImmediatelyOnRelease() {
        frame(UP, 0);
        assertTrue(events.isEmpty());
        frame(0, 80);
        assertEquals(Arrays.asList("UP:SINGLE"), events);
        assertEquals(Long.valueOf(80), eventTimes.get(0));
        assertEquals(0, scheduler.activeTasks());
    }

    @Test
    public void tapWithDoubleBindingWaitsForWindowThenEmitsSingle() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 80);
        assertTrue(events.isEmpty());
        scheduler.advanceTo(80 + HandlebarGestureRecognizer.DOUBLE_TAP_WINDOW_MS - 1);
        assertTrue(events.isEmpty());
        scheduler.advanceTo(80 + HandlebarGestureRecognizer.DOUBLE_TAP_WINDOW_MS);
        assertEquals(Arrays.asList("UP:SINGLE"), events);
    }

    @Test
    public void secondReleaseWithin350msIsDouble() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 80);
        frame(UP, 200);
        frame(0, 420);
        assertEquals(Arrays.asList("UP:DOUBLE"), events);
        scheduler.advanceTo(2000);
        assertEquals(Arrays.asList("UP:DOUBLE"), events);
    }

    @Test
    public void twoTapsFourHundredApartAreTwoSingles() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 50);
        frame(UP, 400);
        frame(0, 450);
        scheduler.advanceTo(2000);
        assertEquals(Arrays.asList("UP:SINGLE", "UP:SINGLE"), events);
    }

    @Test
    public void lateSecondReleaseFlushesPendingSingleEvenIfTimerIsLate() {
        doubleButtons.add("SET");
        frame(SET, 0);
        recognizer.onMask(0, 50);
        recognizer.onMask(SET, 380);
        recognizer.onMask(0, 450);
        assertEquals(Arrays.asList("SET:SINGLE"), events);
        scheduler.advanceTo(2000);
        assertEquals(Arrays.asList("SET:SINGLE", "SET:SINGLE"), events);
    }

    @Test
    public void holdAtLeast400msIsLongAtRelease() {
        frame(SET, 0);
        frame(SET, 399);
        frame(SET, 800);
        assertTrue("LONG fires on release, not at the threshold", events.isEmpty());
        frame(0, 900);
        assertEquals(Arrays.asList("SET:LONG"), events);
    }

    @Test
    public void holdOf399msIsSingle() {
        frame(DOWN, 0);
        frame(0, 399);
        frame(DOWN, 1000);
        frame(0, 1400);
        assertEquals(Arrays.asList("DOWN:SINGLE", "DOWN:LONG"), events);
    }

    @Test
    public void heldButtonNeverRepeats() {
        frame(UP, 0);
        for (long t = 100; t <= 5000; t += 100) {
            frame(UP, t);
        }
        assertTrue(events.isEmpty());
        frame(0, 5100);
        frame(0, 5200);
        assertEquals(Arrays.asList("UP:LONG"), events);
    }

    @Test
    public void tapThenLongPressKeepsTheTap() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 50);
        frame(UP, 100);
        frame(0, 600);
        assertEquals(Arrays.asList("UP:SINGLE", "UP:LONG"), events);
    }

    @Test
    public void simultaneousButtonsAreIndependent() {
        frame(UP | DOWN, 0);
        frame(UP, 100);
        assertEquals(Arrays.asList("DOWN:SINGLE"), events);
        frame(0, 600);
        assertEquals(Arrays.asList("DOWN:SINGLE", "UP:LONG"), events);
    }

    @Test
    public void simultaneousReleaseEmitsBothInBitOrder() {
        frame(SET | BACK, 0);
        frame(0, 100);
        assertEquals(Arrays.asList("SET:SINGLE", "BACK:SINGLE"), events);
    }

    @Test
    public void doubleOnOneButtonDoesNotDelayAnother() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 50);
        frame(DOWN, 60);
        frame(0, 100);
        assertEquals(Arrays.asList("DOWN:SINGLE"), events);
        frame(UP, 150);
        frame(0, 200);
        assertEquals(Arrays.asList("DOWN:SINGLE", "UP:DOUBLE"), events);
    }

    @Test
    public void resetCancelsPendingDoubleAndHeldState() {
        doubleButtons.add("UP");
        frame(UP, 0);
        frame(0, 50);
        assertTrue(recognizer.hasPending());
        recognizer.reset();
        assertFalse(recognizer.hasPending());
        scheduler.advanceTo(1000);
        assertTrue(events.isEmpty());
        frame(SET, 1100);
        recognizer.reset();
        frame(0, 1200);
        assertTrue("Release after reset is not a gesture", events.isEmpty());
    }

    @Test
    public void staleTimerAfterResetIsIgnored() {
        List<Runnable> captured = new ArrayList<>();
        HandlebarGestureRecognizer r = new HandlebarGestureRecognizer(
                (task, delay) -> {
                    captured.add(task);
                    return () -> { };
                },
                b -> true,
                (b, g) -> events.add(b + ":" + g.name()));
        r.onMask(UP, 0);
        r.onMask(0, 50);
        r.reset();
        captured.get(0).run();
        assertTrue(events.isEmpty());
    }

    @Test
    public void activeBitsAboveNibbleAreIgnored() {
        frame(0xF0, 0);
        frame(0, 100);
        assertTrue(events.isEmpty());
    }

    @Test
    public void buttonNamesMatchBits() {
        assertEquals("SET", HandlebarGestureRecognizer.buttonName(0));
        assertEquals("BACK", HandlebarGestureRecognizer.buttonName(1));
        assertEquals("DOWN", HandlebarGestureRecognizer.buttonName(2));
        assertEquals("UP", HandlebarGestureRecognizer.buttonName(3));
    }
}
