package app.ktmnav.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class CallControlTest {

    private final FakeTelecom telecom = new FakeTelecom();
    private final FakeBanner banner = new FakeBanner();
    private final CallControl calls = new CallControl(telecom, banner);

    @Test
    public void answerAndSilenceWhileRinging() {
        calls.onCallStateChanged(CallControl.State.RINGING);
        assertTrue(calls.isRinging());
        assertEquals("Answered call", calls.answer());
        assertEquals(1, telecom.accepted);
        assertEquals("Silenced ringer", calls.silence());
        assertEquals(1, telecom.silenced);
    }

    @Test
    public void honestResultsWithoutRingingCall() {
        assertEquals("No ringing call", calls.answer());
        calls.onCallStateChanged(CallControl.State.IDLE);
        assertEquals("No ringing call", calls.answer());
        assertEquals("No ringing call", calls.silence());
        calls.onCallStateChanged(CallControl.State.OFFHOOK);
        assertEquals("No ringing call", calls.answer());
        assertEquals(0, telecom.accepted);
        assertEquals(0, telecom.silenced);
    }

    @Test
    public void permissionResults() {
        calls.onCallStateChanged(CallControl.State.RINGING);
        telecom.answerPermission = false;
        assertEquals("Phone permission needed", calls.answer());
        assertEquals(0, telecom.accepted);
        telecom.phoneState = false;
        assertEquals("Phone permission needed", calls.answer());
        assertEquals("Phone permission needed", calls.silence());
        assertEquals("Phone permission needed", new CallControl(null, null).answer());
    }

    @Test
    public void failuresAreReported() {
        calls.onCallStateChanged(CallControl.State.RINGING);
        telecom.acceptResult = false;
        telecom.silenceResult = false;
        assertEquals("Could not answer call", calls.answer());
        assertEquals("Could not silence ringer", calls.silence());
    }

    @Test
    public void callerBannerWhileRingingAndClearedAfter() {
        calls.onCallStateChanged(CallControl.State.IDLE);
        assertTrue(banner.log.isEmpty());
        calls.onCallStateChanged(CallControl.State.RINGING);
        calls.onIncomingCallNotification("  Asha   Rider ");
        calls.onCallStateChanged(CallControl.State.OFFHOOK);
        assertEquals(Arrays.asList(
                "show call 'Incoming call' p3 45000",
                "show call 'Call: Asha Rider' p3 45000",
                "clear call"), banner.log);
        assertEquals(1, telecom.restored);
    }

    @Test
    public void nameBeforeRingingIsUsedWhenRingingStarts() {
        calls.onCallStateChanged(CallControl.State.IDLE);
        calls.onIncomingCallNotification("Sam");
        assertTrue(banner.log.isEmpty());
        calls.onCallStateChanged(CallControl.State.RINGING);
        calls.onCallStateChanged(CallControl.State.IDLE);
        calls.onCallStateChanged(CallControl.State.RINGING);
        assertEquals(Arrays.asList(
                "show call 'Call: Sam' p3 45000",
                "clear call",
                "show call 'Incoming call' p3 45000"), banner.log);
    }

    @Test
    public void ongoingCallNotificationIgnoredWhenOffhook() {
        calls.onCallStateChanged(CallControl.State.OFFHOOK);
        calls.onIncomingCallNotification("Sam");
        assertEquals(Collections.emptyList(), banner.log);
    }

    @Test
    public void untrackedStateShowsShortBannerAndClearsOnRemoval() {
        calls.onIncomingCallNotification("Sam");
        calls.onIncomingCallNotificationRemoved();
        calls.onIncomingCallNotificationRemoved();
        assertEquals(Arrays.asList("show call 'Call: Sam' p3 20000", "clear call"), banner.log);
        assertFalse(calls.isRinging());
    }

    @Test
    public void repeatedRingingDoesNotRepeatBanner() {
        calls.onCallStateChanged(CallControl.State.RINGING);
        calls.onCallStateChanged(CallControl.State.RINGING);
        assertEquals(1, banner.log.size());
    }

    @Test
    public void nameSanitising() {
        assertNull(CallControl.sanitizeName(null));
        assertNull(CallControl.sanitizeName(" \n "));
        assertEquals("A B", CallControl.sanitizeName("A\n\tB"));
        String longName = "Abcdefghijklmnopqrstuvwxyz0123456789";
        assertEquals(CallControl.MAX_NAME_LENGTH, CallControl.sanitizeName(longName).length());
    }
}
