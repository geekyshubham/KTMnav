package app.ktmnav.backend;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class CallNotificationsTest {

    @After
    public void tearDown() {
        FeatureHooks.reset();
    }

    @Test
    public void testIncomingCallDetection() {
        String dialer = "com.google.android.dialer";
        Assert.assertTrue(CallNotifications.isIncomingCall(dialer, "call", 1, false));
        Assert.assertFalse(CallNotifications.isIncomingCall(dialer, "call", 2, true));
        Assert.assertTrue(CallNotifications.isIncomingCall("com.samsung.android.incallui", "call", 0, true));
        Assert.assertFalse(CallNotifications.isIncomingCall(dialer, "call", 0, false));
        Assert.assertFalse(CallNotifications.isIncomingCall(dialer, "msg", 1, true));
        Assert.assertFalse(CallNotifications.isIncomingCall(MessageMirror.WHATSAPP, "call", 1, true));
        Assert.assertFalse(CallNotifications.isIncomingCall(null, "call", 1, true));
    }

    @Test
    public void testRepeatSuppression() {
        CallNotifications calls = new CallNotifications();
        Assert.assertTrue(calls.shouldForward("k1", "Asha", 0));
        Assert.assertFalse(calls.shouldForward("k1", "Asha", 4_999));
        Assert.assertTrue(calls.shouldForward("k1", "Asha", 10_000));
        Assert.assertTrue(calls.shouldForward("k2", "Ravi", 10_001));
        Assert.assertTrue(calls.onRemoved("k2"));
        Assert.assertFalse(calls.onRemoved("k2"));
        Assert.assertTrue(calls.shouldForward("k2", "Ravi", 10_002));
    }

    @Test
    public void testFeatureHooksRegistry() {
        Assert.assertNull(FeatureHooks.getBannerSink());
        Assert.assertNull(FeatureHooks.getCallNotificationSink());
        MessageMirrorTest.FakeSink sink = new MessageMirrorTest.FakeSink();
        CallNotificationSink callSink = callerName -> { };
        FeatureHooks.setBannerSink(sink);
        FeatureHooks.setCallNotificationSink(callSink);
        Assert.assertSame(sink, FeatureHooks.getBannerSink());
        Assert.assertSame(callSink, FeatureHooks.getCallNotificationSink());
        callSink.onCallNotificationRemoved();
    }

    @Test
    public void testOngoingCallReplacesRingingNotification() {
        String dialer = "com.google.android.dialer";
        Assert.assertTrue(CallNotifications.isOngoingCall(dialer, "call", 2, false));
        Assert.assertTrue(CallNotifications.isOngoingCall(dialer, "call", 0, false));
        Assert.assertFalse(CallNotifications.isOngoingCall(dialer, "call", 1, false));
        Assert.assertFalse(CallNotifications.isOngoingCall(dialer, "call", 0, true));
        Assert.assertFalse(CallNotifications.isOngoingCall(dialer, "msg", 2, false));
        Assert.assertFalse(CallNotifications.isOngoingCall(MessageMirror.WHATSAPP, "call", 2, false));

        CallNotifications calls = new CallNotifications();
        Assert.assertFalse("no ringing notification seen yet", calls.onAnsweredInPlace("k1"));
        Assert.assertTrue(calls.shouldForward("k1", "Asha", 0));
        Assert.assertFalse("other keys do not clear the banner", calls.onAnsweredInPlace("k2"));
        Assert.assertTrue(calls.onAnsweredInPlace("k1"));
        Assert.assertFalse("cleared once", calls.onAnsweredInPlace("k1"));
    }

    @Test
    public void testUntrackedBannerClearsWhenCallIsAnswered() {
        FakeBanner banners = new FakeBanner();
        CallControl control = new CallControl(null, banners);
        CallNotifications calls = new CallNotifications();
        CallNotificationSink sink = new CallNotificationSink() {
            @Override
            public void onIncomingCallNotification(String callerName) {
                control.onIncomingCallNotification(callerName);
            }

            @Override
            public void onCallNotificationRemoved() {
                control.onIncomingCallNotificationRemoved();
            }
        };
        Assert.assertTrue(calls.shouldForward("k1", "Asha", 0));
        sink.onIncomingCallNotification("Asha");
        Assert.assertTrue(calls.onAnsweredInPlace("k1"));
        sink.onCallNotificationRemoved();
        Assert.assertEquals(java.util.Arrays.asList("show call 'Call: Asha' p" + BannerSink.PRIORITY_CALL + " " + CallControl.UNTRACKED_BANNER_MS,
                "clear call"), banners.log);
    }
}
