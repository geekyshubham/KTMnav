package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class MessageMirrorTest {

    static final class FakeSink implements BannerSink {
        final List<String> shown = new ArrayList<>();
        final List<Integer> priorities = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean show(String owner, String text, int priority, long holdMs) {
            if (!accept) {
                return false;
            }
            shown.add(owner + "|" + text);
            priorities.add(priority);
            return true;
        }

        @Override
        public void clear(String owner) {
        }
    }

    private static final long NOW = 1_700_000_000_000L;
    private static final String WA = MessageMirror.WHATSAPP;
    private static final String GMSG = "com.google.android.apps.messaging";

    private MessageMirror mirror;
    private FakeSink sink;

    @Before
    public void setUp() {
        mirror = new MessageMirror();
        sink = new FakeSink();
    }

    private static MessageMirror.Settings on() {
        return new MessageMirror.Settings(true, null, false, false, 0L);
    }

    private static MessageMirror.Message msg(String pkg, String title, String text) {
        return new MessageMirror.Message(pkg, title, text);
    }

    @Test
    public void testDefaultsAreOptIn() {
        MessageMirror.Settings off = new MessageMirror.Settings(false, null, false, false, 0L);
        Assert.assertEquals(MessageMirror.Outcome.DISABLED, mirror.process(msg(WA, "Asha", "Hi"), off, sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.DISABLED, MessageMirror.Settings.from(null).enabled
                ? MessageMirror.Outcome.SHOWN : MessageMirror.Outcome.DISABLED);
        Assert.assertTrue(sink.shown.isEmpty());
        Assert.assertEquals(6, MessageMirror.DEFAULT_APPS.size());
        Assert.assertTrue(MessageMirror.DEFAULT_APPS.containsKey("org.thoughtcrime.securesms"));
        Assert.assertTrue(MessageMirror.DEFAULT_APPS.containsKey("com.whatsapp.w4b"));
    }

    @Test
    public void testShowsAllowedMessage() {
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "On my way"), on(), sink, NOW));
        Assert.assertEquals(Arrays.asList("message|Asha: On my way"), sink.shown);
        Assert.assertEquals(Integer.valueOf(BannerSink.PRIORITY_MESSAGE), sink.priorities.get(0));
    }

    @Test
    public void testAllowlist() {
        Assert.assertEquals(MessageMirror.Outcome.APP_NOT_ALLOWED,
                mirror.process(msg("com.example.mail", "Boss", "Report"), on(), sink, NOW));
        MessageMirror.Settings onlyGoogle = new MessageMirror.Settings(true, new HashSet<>(Arrays.asList(GMSG)), false, false, 0L);
        Assert.assertEquals(MessageMirror.Outcome.APP_NOT_ALLOWED, mirror.process(msg(WA, "Asha", "Hi"), onlyGoogle, sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(GMSG, "Ravi", "Hi"), onlyGoogle, sink, NOW));
        MessageMirror.Settings none = new MessageMirror.Settings(true, new HashSet<>(), false, false, 0L);
        Assert.assertEquals(MessageMirror.Outcome.APP_NOT_ALLOWED, mirror.process(msg(GMSG, "Ravi", "Hi"), none, sink, NOW));
    }

    @Test
    public void testSummaryOngoingAndCallAreSkipped() {
        Assert.assertEquals(MessageMirror.Outcome.GROUP_SUMMARY,
                mirror.process(msg(WA, "WhatsApp", "5 messages from 3 chats").flags(true, false, "msg"), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.ONGOING,
                mirror.process(msg(WA, "WhatsApp", "Checking for new messages").flags(false, true, null), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.CALL,
                mirror.process(msg(WA, "Asha", "Incoming voice call").flags(false, false, "call"), on(), sink, NOW));
        Assert.assertTrue(sink.shown.isEmpty());
    }

    @Test
    public void testGroupFilteringAndOptIn() {
        MessageMirror.Message flagged = msg("org.telegram.messenger", "Riders Club", "Ride at 6")
                .conversation("Riders Club", Boolean.TRUE).last("Vik", "Ride at 6", false, NOW);
        Assert.assertEquals(MessageMirror.Outcome.GROUP, mirror.process(flagged, on(), sink, NOW));

        Assert.assertEquals(MessageMirror.Outcome.GROUP,
                mirror.process(msg(WA, "Family: Mom", "Dinner?"), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.GROUP,
                mirror.process(msg(WA, "Mom @ Family", "Dinner?"), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.GROUP,
                mirror.process(msg(WA, "Mom", "Dinner?").conversation("Family", null), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN,
                mirror.process(msg(WA, "Asha (2 messages)", "Hi").conversation(null, Boolean.FALSE), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN,
                mirror.process(msg("org.telegram.messenger", "Note: Asha", "Hi").conversation(null, Boolean.FALSE), on(), sink, NOW));

        MessageMirror.Settings groups = new MessageMirror.Settings(true, null, true, false, 0L);
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(flagged, groups, sink, NOW));
        Assert.assertEquals("message|Vik @ Riders Club: Ride at 6", sink.shown.get(sink.shown.size() - 1));
    }

    @Test
    public void testQuietUntil() {
        MessageMirror.Settings quiet = new MessageMirror.Settings(true, null, false, false, NOW + 30 * 60_000L);
        Assert.assertEquals(MessageMirror.Outcome.QUIET, mirror.process(msg(WA, "Asha", "Hi"), quiet, sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN,
                mirror.process(msg(WA, "Asha", "Hi"), quiet, sink, NOW + 30 * 60_000L));
        MessageMirror.Settings expired = new MessageMirror.Settings(true, null, false, false, NOW - 1);
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Ravi", "Hi"), expired, sink, NOW));
    }

    @Test
    public void testHideContent() {
        MessageMirror.Settings hidden = new MessageMirror.Settings(true, null, false, true, 0L);
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "Secret plan"), hidden, sink, NOW));
        Assert.assertEquals("message|Message from Asha", sink.shown.get(0));
        Assert.assertFalse(sink.shown.get(0).contains("Secret"));
    }

    @Test
    public void testDedupeWithinTenSeconds() {
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "Hi"), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.DUPLICATE, mirror.process(msg(WA, "Asha", "Hi"), on(), sink, NOW + 9_999));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "Hi again"), on(), sink, NOW + 5_000));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(GMSG, "Asha", "Hi"), on(), sink, NOW + 5_000));
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "Hi"), on(), sink, NOW + 10_000));
        Assert.assertEquals(4, sink.shown.size());
    }

    @Test
    public void testRejectedBannerIsNotRememberedForDedupe() {
        sink.accept = false;
        Assert.assertEquals(MessageMirror.Outcome.SINK_REJECTED, mirror.process(msg(WA, "Asha", "Hi"), on(), sink, NOW));
        sink.accept = true;
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(msg(WA, "Asha", "Hi"), on(), sink, NOW + 1));
        Assert.assertEquals(MessageMirror.Outcome.NO_SINK, mirror.process(msg(WA, "Ravi", "Hi"), on(), null, NOW));
    }

    @Test
    public void testMessagingStyleLastMessageOwnAndStale() {
        MessageMirror.Message style = msg(GMSG, "Ravi", "older text").last("Ravi", "Reached the toll", false, NOW - 1000);
        Assert.assertEquals(MessageMirror.Outcome.SHOWN, mirror.process(style, on(), sink, NOW));
        Assert.assertEquals("message|Ravi: Reached the toll", sink.shown.get(0));

        MessageMirror.Message own = msg(GMSG, "Ravi", "ok").last(null, "ok", true, NOW);
        Assert.assertEquals(MessageMirror.Outcome.OWN_MESSAGE, mirror.process(own, on(), sink, NOW));

        MessageMirror.Message stale = msg(GMSG, "Ravi", "x").last("Ravi", "Old news", false, NOW - MessageMirror.STALE_MS - 1);
        Assert.assertEquals(MessageMirror.Outcome.STALE, mirror.process(stale, on(), sink, NOW));
    }

    @Test
    public void testEmptyText() {
        Assert.assertEquals(MessageMirror.Outcome.EMPTY, mirror.process(msg(WA, "Asha", "   "), on(), sink, NOW));
        Assert.assertEquals(MessageMirror.Outcome.EMPTY, mirror.process(msg(WA, "Asha", null), on(), sink, NOW));
    }

    @Test
    public void testFormatting() {
        Assert.assertEquals("Asha: Line one line two",
                MessageMirror.format(msg(WA, " Asha ", "Line one\nline   two"), false, false));
        Assert.assertEquals("Jose: Cafe manana",
                MessageMirror.format(msg(WA, "José", "Café mañana"), false, false));
        Assert.assertEquals("Message from Asha",
                MessageMirror.format(msg(WA, "Asha", "😀😀"), false, false));
        Assert.assertEquals("Message from Asha",
                MessageMirror.format(msg(WA, "Asha", "नमस्ते"), false, false));
        Assert.assertEquals("WhatsApp: Hello",
                MessageMirror.format(msg(WA, "राहुल", "Hello"), false, false));
        Assert.assertEquals("Message: Hello",
                MessageMirror.format(msg("com.example", null, "Hello"), false, false));
    }

    @Test
    public void testLongTextIsTruncated() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("word").append(i).append(' ');
        }
        String out = MessageMirror.format(msg(WA, "Asha", body.toString()), false, false);
        Assert.assertTrue(out.length() <= MessageMirror.MAX_BANNER_CHARS);
        Assert.assertTrue(out.startsWith("Asha: word0 word1"));
        Assert.assertTrue(out.endsWith("..."));
        Assert.assertFalse(out.contains("word39"));

        String longName = "Abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnop";
        String named = MessageMirror.format(msg(WA, longName, "Hi"), false, false);
        Assert.assertTrue(named.length() <= MessageMirror.MAX_BANNER_CHARS);
        Assert.assertTrue(named.endsWith("...: Hi"));

        String unbroken = MessageMirror.truncate("x".repeat(300), 20);
        Assert.assertEquals(20, unbroken.length());
    }

    @Test
    public void testSendTest() {
        Assert.assertTrue(mirror.sendTest(sink, false));
        Assert.assertTrue(mirror.sendTest(sink, false));
        Assert.assertEquals("message|KTMnav: Test message", sink.shown.get(0));
        Assert.assertTrue(mirror.sendTest(sink, true));
        Assert.assertEquals("message|Message from KTMnav", sink.shown.get(2));
        Assert.assertFalse(mirror.sendTest(null, false));
    }
}
