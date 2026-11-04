package app.ktmnav.backend;

import android.app.Notification;
import android.app.Person;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in mirroring of chat notifications to the dash banner. Message content is never logged or stored.
 * Filtering rules follow OpenDash KTM-Nav-GEN3 AppNotificationListener (MIT).
 */
public final class MessageMirror {
    private static final String TAG = "Messages";

    public static final String PREF_ENABLED = "messages_enabled";
    public static final String PREF_APPS = "message_apps";
    public static final String PREF_GROUPS = "message_groups";
    public static final String PREF_QUIET_UNTIL = "messages_quiet_until";
    public static final String PREF_HIDE_CONTENT = "messages_hide_content";

    public static final String OWNER = "message";
    public static final long HOLD_MS = 8000L;
    public static final long DEDUPE_WINDOW_MS = 10_000L;
    public static final long STALE_MS = 5 * 60_000L;
    public static final int MAX_BANNER_CHARS = 100;
    public static final String ELLIPSIS = "...";

    public static final String WHATSAPP = "com.whatsapp";
    public static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    public static final Map<String, String> DEFAULT_APPS;

    static {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put(WHATSAPP, "WhatsApp");
        apps.put(WHATSAPP_BUSINESS, "WhatsApp Business");
        apps.put("com.google.android.apps.messaging", "Google Messages");
        apps.put("com.samsung.android.messaging", "Samsung Messages");
        apps.put("org.telegram.messenger", "Telegram");
        apps.put("org.thoughtcrime.securesms", "Signal");
        DEFAULT_APPS = Collections.unmodifiableMap(apps);
    }

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern RENDERABLE = Pattern.compile("[A-Za-z0-9]");
    private static final MessageMirror SHARED = new MessageMirror();
    private static volatile String hiddenFormat = "Message from %s";
    private static volatile String testText = "KTMnav: Test message";

    public enum Outcome {
        SHOWN, DISABLED, APP_NOT_ALLOWED, GROUP_SUMMARY, ONGOING, CALL, GROUP, QUIET,
        OWN_MESSAGE, STALE, EMPTY, DUPLICATE, NO_SINK, SINK_REJECTED
    }

    public static final class Settings {
        final boolean enabled;
        final Set<String> apps;
        final boolean groups;
        final boolean hideContent;
        final long quietUntil;

        public Settings(boolean enabled, Set<String> apps, boolean groups, boolean hideContent, long quietUntil) {
            this.enabled = enabled;
            this.apps = apps != null ? apps : DEFAULT_APPS.keySet();
            this.groups = groups;
            this.hideContent = hideContent;
            this.quietUntil = quietUntil;
        }

        public static Settings from(SharedPreferences prefs) {
            if (prefs == null) {
                return new Settings(false, null, false, false, 0L);
            }
            Set<String> apps = prefs.getStringSet(PREF_APPS, null);
            return new Settings(
                    prefs.getBoolean(PREF_ENABLED, false),
                    apps != null ? new HashSet<>(apps) : null,
                    prefs.getBoolean(PREF_GROUPS, false),
                    prefs.getBoolean(PREF_HIDE_CONTENT, false),
                    prefs.getLong(PREF_QUIET_UNTIL, 0L));
        }
    }

    public static final class Message {
        final String pkg;
        String title;
        String text;
        String conversationTitle;
        String lastSender;
        String lastText;
        boolean lastFromSelf;
        long lastTimestamp;
        Boolean groupFlag;
        boolean groupSummary;
        boolean ongoing;
        String category;

        public Message(String pkg, String title, String text) {
            this.pkg = pkg;
            this.title = title;
            this.text = text;
        }

        Message conversation(String conversationTitle, Boolean groupFlag) {
            this.conversationTitle = conversationTitle;
            this.groupFlag = groupFlag;
            return this;
        }

        Message last(String sender, String text, boolean fromSelf, long timestamp) {
            this.lastSender = sender;
            this.lastText = text;
            this.lastFromSelf = fromSelf;
            this.lastTimestamp = timestamp;
            return this;
        }

        Message flags(boolean groupSummary, boolean ongoing, String category) {
            this.groupSummary = groupSummary;
            this.ongoing = ongoing;
            this.category = category;
            return this;
        }

        public static Message fromNotification(StatusBarNotification sbn) {
            Notification n = sbn.getNotification();
            Bundle extras = n.extras;
            Message m = new Message(sbn.getPackageName(),
                    NavTexts.charSequence(extras, Notification.EXTRA_TITLE),
                    NavTexts.charSequence(extras, Notification.EXTRA_TEXT));
            if (m.text == null) {
                m.text = NavTexts.charSequence(extras, Notification.EXTRA_BIG_TEXT);
            }
            Boolean groupFlag = extras != null && extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)
                    ? extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) : null;
            m.conversation(NavTexts.charSequence(extras, Notification.EXTRA_CONVERSATION_TITLE), groupFlag);
            m.flags((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                    sbn.isOngoing() || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0,
                    n.category);
            readLastMessage(m, extras);
            return m;
        }

        @SuppressWarnings("deprecation")
        private static void readLastMessage(Message m, Bundle extras) {
            if (extras == null) {
                return;
            }
            Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages == null || messages.length == 0 || !(messages[messages.length - 1] instanceof Bundle)) {
                return;
            }
            Bundle last = (Bundle) messages[messages.length - 1];
            CharSequence text = last.getCharSequence("text");
            CharSequence sender = last.getCharSequence("sender");
            Parcelable senderPerson = last.getParcelable("sender_person");
            if (sender == null && senderPerson instanceof Person) {
                sender = ((Person) senderPerson).getName();
            }
            String selfName = null;
            Parcelable self = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            if (self instanceof Person && ((Person) self).getName() != null) {
                selfName = ((Person) self).getName().toString();
            }
            boolean fromSelf = (sender == null && senderPerson == null)
                    || (sender != null && selfName != null && selfName.contentEquals(sender));
            m.last(sender != null ? sender.toString() : null, text != null ? text.toString() : null,
                    fromSelf, last.getLong("time", 0L));
        }
    }

    private final Map<String, Long> recent = new LinkedHashMap<>();

    MessageMirror() {
    }

    public static MessageMirror shared() {
        return SHARED;
    }

    public static void setTexts(String hiddenContentFormat, String testMessageText) {
        if (hiddenContentFormat != null && hiddenContentFormat.contains("%s")) {
            hiddenFormat = hiddenContentFormat;
        }
        if (testMessageText != null && !testMessageText.trim().isEmpty()) {
            testText = testMessageText;
        }
    }

    public Outcome onNotification(StatusBarNotification sbn, SharedPreferences prefs, BannerSink sink, long now) {
        Settings settings = Settings.from(prefs);
        if (!settings.enabled) {
            return Outcome.DISABLED;
        }
        if (sbn == null || sbn.getNotification() == null || !settings.apps.contains(sbn.getPackageName())) {
            return Outcome.APP_NOT_ALLOWED;
        }
        return process(Message.fromNotification(sbn), settings, sink, now);
    }

    public synchronized Outcome process(Message m, Settings s, BannerSink sink, long now) {
        Outcome outcome = filter(m, s, now);
        if (outcome == null) {
            String banner = format(m, s.groups && isGroup(m), s.hideContent);
            if (banner == null) {
                outcome = Outcome.EMPTY;
            } else if (isDuplicate(m.pkg, banner, now)) {
                outcome = Outcome.DUPLICATE;
            } else if (sink == null) {
                outcome = Outcome.NO_SINK;
            } else if (sink.show(OWNER, banner, BannerSink.PRIORITY_MESSAGE, HOLD_MS)) {
                remember(m.pkg, banner, now);
                outcome = Outcome.SHOWN;
            } else {
                outcome = Outcome.SINK_REJECTED;
            }
            AppLog.d(TAG, "Message from " + m.pkg + " (" + (banner != null ? banner.length() : 0) + " chars): " + outcome);
        } else if (m != null) {
            AppLog.d(TAG, "Skipped message from " + m.pkg + ": " + outcome);
        }
        return outcome;
    }

    static Outcome filter(Message m, Settings s, long now) {
        if (s == null || !s.enabled) {
            return Outcome.DISABLED;
        }
        if (m == null || m.pkg == null || !s.apps.contains(m.pkg)) {
            return Outcome.APP_NOT_ALLOWED;
        }
        if (m.groupSummary) {
            return Outcome.GROUP_SUMMARY;
        }
        if (Notification.CATEGORY_CALL.equals(m.category)) {
            return Outcome.CALL;
        }
        if (m.ongoing) {
            return Outcome.ONGOING;
        }
        if (!s.groups && isGroup(m)) {
            return Outcome.GROUP;
        }
        if (s.quietUntil > now) {
            return Outcome.QUIET;
        }
        if (m.lastText != null && m.lastFromSelf) {
            return Outcome.OWN_MESSAGE;
        }
        if (m.lastText != null && m.lastTimestamp > 0 && now - m.lastTimestamp > STALE_MS) {
            return Outcome.STALE;
        }
        return null;
    }

    static boolean isGroup(Message m) {
        if (Boolean.TRUE.equals(m.groupFlag)) {
            return true;
        }
        if (!isWhatsApp(m.pkg) || Boolean.FALSE.equals(m.groupFlag)) {
            return false;
        }
        if (blankToNull(m.conversationTitle) != null) {
            return true;
        }
        String title = m.title != null ? m.title : "";
        return title.contains(" @ ") || title.contains(": ");
    }

    private static boolean isWhatsApp(String pkg) {
        return WHATSAPP.equals(pkg) || WHATSAPP_BUSINESS.equals(pkg);
    }

    static String format(Message m, boolean asGroup, boolean hideContent) {
        String sender = clean(m.lastSender != null ? m.lastSender : m.title);
        String body = clean(m.lastText != null ? m.lastText : m.text);
        String who = sender;
        String conversation = clean(m.conversationTitle);
        if (asGroup && conversation != null && sender != null && !conversation.equalsIgnoreCase(sender)) {
            who = sender + " @ " + conversation;
        } else if (who == null) {
            who = conversation;
        }
        if (who == null || !renderable(who)) {
            who = appLabel(m.pkg);
        }
        if (body == null) {
            return null;
        }
        if (hideContent || !renderable(body)) {
            return truncate(String.format(Locale.ROOT, hiddenFormat, who), MAX_BANNER_CHARS);
        }
        String prefix = truncate(who, MAX_BANNER_CHARS / 2) + ": ";
        return prefix + truncate(body, MAX_BANNER_CHARS - prefix.length());
    }

    public synchronized boolean sendTest(BannerSink sink, boolean hideContent) {
        if (sink == null) {
            return false;
        }
        String text = hideContent ? String.format(Locale.ROOT, hiddenFormat, "KTMnav") : testText;
        return sink.show(OWNER, text, BannerSink.PRIORITY_MESSAGE, HOLD_MS);
    }

    public static void muteFor(SharedPreferences prefs, long minutes, long now) {
        if (prefs != null) {
            prefs.edit().putLong(PREF_QUIET_UNTIL, now + Math.max(0L, minutes) * 60_000L).apply();
        }
    }

    public static void unmute(SharedPreferences prefs) {
        if (prefs != null) {
            prefs.edit().remove(PREF_QUIET_UNTIL).apply();
        }
    }

    public static long quietRemainingMs(SharedPreferences prefs, long now) {
        if (prefs == null) {
            return 0L;
        }
        return Math.max(0L, prefs.getLong(PREF_QUIET_UNTIL, 0L) - now);
    }

    public static String appLabel(String pkg) {
        String label = pkg != null ? DEFAULT_APPS.get(pkg) : null;
        return label != null ? label : "Message";
    }

    synchronized void resetDedupe() {
        recent.clear();
    }

    private boolean isDuplicate(String pkg, String banner, long now) {
        prune(now);
        Long seen = recent.get(dedupeKey(pkg, banner));
        return seen != null && now - seen < DEDUPE_WINDOW_MS;
    }

    private void remember(String pkg, String banner, long now) {
        recent.put(dedupeKey(pkg, banner), now);
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() >= DEDUPE_WINDOW_MS || recent.size() > 32) {
                it.remove();
            }
        }
    }

    private static String dedupeKey(String pkg, String banner) {
        return pkg + "#" + Integer.toHexString(banner.hashCode()) + ":" + banner.length();
    }

    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String folded = COMBINING.matcher(Normalizer.normalize(s, Normalizer.Form.NFKD)).replaceAll("");
        return blankToNull(WHITESPACE.matcher(folded).replaceAll(" "));
    }

    static boolean renderable(String s) {
        return s != null && RENDERABLE.matcher(s).find();
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int limit = Math.max(1, max - ELLIPSIS.length());
        int cut = s.lastIndexOf(' ', limit);
        if (cut < limit / 2) {
            cut = limit;
        }
        return s.substring(0, cut).trim() + ELLIPSIS;
    }

    private static String blankToNull(String s) {
        return NavTexts.blankToNull(s);
    }
}
