package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DashBannerArbiterTest {

    private static final String CLEAR = "<clear>";

    private FakeDashScheduler clock;
    private final List<String> frames = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private final List<BccuProtocol.NotificationIcon> icons = new ArrayList<>();
    private DashBannerArbiter arbiter;

    @Before
    public void setUp() {
        clock = new FakeDashScheduler();
        arbiter = new DashBannerArbiter(clock, new DashBannerArbiter.Sink() {
            @Override
            public void showFrame(String text, BccuProtocol.NotificationIcon icon) {
                frames.add(text);
                icons.add(icon);
                times.add(clock.now());
            }

            @Override
            public void clearBanner() {
                frames.add(CLEAR);
                icons.add(null);
                times.add(clock.now());
            }
        });
    }

    @Test
    public void shortTextHoldsDefaultFiveSecondsThenClears() {
        Assert.assertTrue(arbiter.show("a", "Hello", DashBannerArbiter.PRIORITY_MESSAGE, 0));
        Assert.assertEquals(List.of("Hello"), frames);
        clock.advance(4999);
        Assert.assertEquals(1, frames.size());
        clock.advance(1);
        Assert.assertEquals(List.of("Hello", CLEAR), frames);
        Assert.assertEquals(Long.valueOf(5000), times.get(1));
        Assert.assertFalse(arbiter.isActive());
    }

    @Test
    public void customHoldAndExactlySixteenCharsStayStatic() {
        Assert.assertTrue(arbiter.show("a", "1234567890123456", DashBannerArbiter.PRIORITY_INFO, 2000));
        clock.advance(2000);
        Assert.assertEquals(List.of("1234567890123456", CLEAR), frames);
    }

    @Test
    public void longTextScrollsTwoCyclesAt320msThenClears() {
        String text = "ABCDEFGHIJKLMNOPQRST";
        Assert.assertTrue(arbiter.show("a", text, DashBannerArbiter.PRIORITY_TRACK, 0));
        clock.advance(60000);
        int framesPerCycle = text.length() + 3;
        Assert.assertEquals(framesPerCycle * 2 + 1, frames.size());
        Assert.assertEquals("ABCDEFGHIJKLMNOP", frames.get(0));
        Assert.assertEquals("BCDEFGHIJKLMNOPQ", frames.get(1));
        Assert.assertEquals("   ABCDEFGHIJKLM", frames.get(20));
        Assert.assertEquals(frames.get(0), frames.get(framesPerCycle));
        for (int i = 0; i < framesPerCycle * 2; i++) {
            Assert.assertEquals(16, frames.get(i).length());
            Assert.assertEquals(Long.valueOf(i * 320L), times.get(i));
        }
        Assert.assertEquals(CLEAR, frames.get(frames.size() - 1));
        Assert.assertEquals(Long.valueOf(framesPerCycle * 2 * 320L), times.get(times.size() - 1));
    }

    @Test
    public void priorityRules() {
        Assert.assertTrue(arbiter.show("speed", "52 km/h", DashBannerArbiter.PRIORITY_INFO, 0));
        Assert.assertTrue(arbiter.show("msg", "Hi there", DashBannerArbiter.PRIORITY_MESSAGE, 0));
        Assert.assertFalse("INFO never pre-empts a message", arbiter.show("speed", "53 km/h", DashBannerArbiter.PRIORITY_INFO, 0));
        Assert.assertFalse(arbiter.show("track", "Song", DashBannerArbiter.PRIORITY_TRACK, 0));
        Assert.assertTrue("equal priority pre-empts", arbiter.show("msg2", "Second", DashBannerArbiter.PRIORITY_MESSAGE, 0));
        Assert.assertTrue(arbiter.show("call", "Call: Ravi", DashBannerArbiter.PRIORITY_CALL, 0));
        Assert.assertFalse(arbiter.show("msg", "Third", DashBannerArbiter.PRIORITY_MESSAGE, 0));
        Assert.assertTrue(arbiter.show("alert", "OVERSPEED 92", DashBannerArbiter.PRIORITY_ALERT, 0));
        Assert.assertFalse(arbiter.show("call", "Call: Ravi", DashBannerArbiter.PRIORITY_CALL, 0));
        Assert.assertEquals(List.of("52 km/h", "Hi there", "Second", "Call: Ravi", "OVERSPEED 92"), frames);
        Assert.assertEquals("alert", arbiter.getActiveOwner());
        clock.advance(5000);
        Assert.assertEquals(CLEAR, frames.get(frames.size() - 1));
        Assert.assertEquals("one clear only: earlier timers were cancelled by pre-emption", 6, frames.size());
        Assert.assertTrue(arbiter.show("speed", "54 km/h", DashBannerArbiter.PRIORITY_INFO, 0));
    }

    @Test
    public void ownerMayReplaceItsOwnBannerAndPreemptionStopsMarquee() {
        Assert.assertTrue(arbiter.show("call", "Incoming call from a long caller name", DashBannerArbiter.PRIORITY_CALL, 0));
        Assert.assertTrue(arbiter.show("call", "Call ended", DashBannerArbiter.PRIORITY_INFO, 1000));
        clock.advance(10000);
        Assert.assertEquals(List.of("Incoming call fr", "Call ended", CLEAR), frames);
    }

    @Test
    public void clearOnlyByOwner() {
        arbiter.show("msg", "Hello", DashBannerArbiter.PRIORITY_MESSAGE, 0);
        arbiter.clear("other");
        Assert.assertTrue(arbiter.isActive());
        arbiter.clear("msg");
        Assert.assertFalse(arbiter.isActive());
        clock.advance(10000);
        Assert.assertEquals(List.of("Hello", CLEAR), frames);
        arbiter.clear("msg");
        Assert.assertEquals(2, frames.size());
    }

    @Test
    public void textIsAsciiCleanedAndIconsCoerced() {
        Assert.assertFalse(arbiter.show("x", "🚀🚀", DashBannerArbiter.PRIORITY_ALERT, 0));
        Assert.assertTrue(arbiter.show("x", "Café 🚀", DashBannerArbiter.PRIORITY_MESSAGE, 0, BccuProtocol.NotificationIcon.INFORMATION));
        Assert.assertEquals("Cafe", frames.get(0));
        Assert.assertEquals(BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT, icons.get(0));
        Assert.assertTrue(arbiter.show("x", "Reroute", DashBannerArbiter.PRIORITY_MESSAGE, 0, BccuProtocol.NotificationIcon.NOTIFICATION_REROUTING));
        Assert.assertEquals(BccuProtocol.NotificationIcon.NOTIFICATION_REROUTING, icons.get(1));
    }

    @Test
    public void marqueeDisabledShowsTruncatedStaticText() {
        arbiter.setMarqueeEnabled(false);
        arbiter.show("x", "ABCDEFGHIJKLMNOPQRST", DashBannerArbiter.PRIORITY_MESSAGE, 0);
        clock.advance(5000);
        Assert.assertEquals(List.of("ABCDEFGHIJKLMNOP", CLEAR), frames);
    }

    @Test
    public void resetCancelsWithoutWriting() {
        arbiter.show("x", "ABCDEFGHIJKLMNOPQRST", DashBannerArbiter.PRIORITY_MESSAGE, 0);
        arbiter.reset();
        clock.advance(60000);
        Assert.assertEquals(1, frames.size());
        Assert.assertEquals(0, clock.pending());
    }

    @Test
    public void inFlightMarqueeFrameIsWrittenBeforeANewerBanner() throws Exception {
        FakeDashScheduler clk = new FakeDashScheduler();
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean blockNext = new AtomicBoolean(false);
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DashBannerArbiter a = new DashBannerArbiter(clk, new DashBannerArbiter.Sink() {
            @Override
            public void showFrame(String text, BccuProtocol.NotificationIcon icon) {
                if (blockNext.compareAndSet(true, false)) {
                    inWrite.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                written.add(text);
            }

            @Override
            public void clearBanner() {
                written.add(CLEAR);
            }
        });
        Assert.assertTrue(a.show("track", "A long scrolling track title", DashBannerArbiter.PRIORITY_TRACK, 0));
        blockNext.set(true);
        Thread ticker = new Thread(() -> clk.advance(DashBannerArbiter.MARQUEE_STEP_MS));
        ticker.start();
        Assert.assertTrue(inWrite.await(5, TimeUnit.SECONDS));
        Thread caller = new Thread(() -> a.show("call", "Call: Asha", DashBannerArbiter.PRIORITY_CALL, 0));
        caller.start();
        caller.join(200);
        Assert.assertTrue("the newer banner waits for the in-flight frame", caller.isAlive());
        release.countDown();
        caller.join(5000);
        ticker.join(5000);
        Assert.assertEquals("Call: Asha", written.get(written.size() - 1));
        Assert.assertEquals("call", a.getActiveOwner());
        clk.advance(DashBannerArbiter.MARQUEE_STEP_MS * 4);
        Assert.assertEquals("no stale marquee frame after the new banner", "Call: Asha", written.get(written.size() - 1));
    }
}
