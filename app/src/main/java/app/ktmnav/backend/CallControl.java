package app.ktmnav.backend;

/**
 * Incoming-call state, answer/silence commands and the caller banner. Pure Java; Android access goes
 * through {@link Telecom}. Caller names are shown on the dash only and never logged.
 */
public final class CallControl {

    public static final String BANNER_OWNER = "call";
    public static final long RINGING_BANNER_MS = 45_000L;
    public static final long UNTRACKED_BANNER_MS = 20_000L;
    static final int MAX_NAME_LENGTH = 32;

    public static final String RESULT_ANSWERED = "Answered call";
    public static final String RESULT_SILENCED = "Silenced ringer";
    public static final String RESULT_PERMISSION = "Phone permission needed";
    public static final String RESULT_NOT_RINGING = "No ringing call";
    public static final String RESULT_ANSWER_FAILED = "Could not answer call";
    public static final String RESULT_SILENCE_FAILED = "Could not silence ringer";

    public enum State { UNKNOWN, IDLE, RINGING, OFFHOOK }

    public interface Telecom {
        boolean canReadPhoneState();

        boolean canAnswerCalls();

        boolean acceptRingingCall();

        boolean silenceRinger();

        void restoreRinger();
    }

    private final Telecom telecom;
    private final BannerSink banner;
    private State state = State.UNKNOWN;
    private String callerName;
    private boolean bannerShown;

    public CallControl(Telecom telecom, BannerSink banner) {
        this.telecom = telecom;
        this.banner = banner;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean isRinging() {
        return state == State.RINGING;
    }

    public boolean hasPhonePermission() {
        return telecom != null && telecom.canReadPhoneState();
    }

    public void onCallStateChanged(State newState) {
        State next = newState != null ? newState : State.UNKNOWN;
        String show = null;
        boolean clear = false;
        boolean restore = false;
        synchronized (this) {
            State previous = state;
            state = next;
            if (next == State.RINGING) {
                if (previous != State.RINGING || !bannerShown) {
                    show = bannerText(callerName);
                    bannerShown = true;
                }
            } else {
                restore = previous == State.RINGING;
                clear = bannerShown;
                bannerShown = false;
                callerName = null;
            }
        }
        if (restore && telecom != null) {
            telecom.restoreRinger();
        }
        if (banner != null) {
            if (show != null) {
                banner.show(BANNER_OWNER, show, BannerSink.PRIORITY_CALL, RINGING_BANNER_MS);
            } else if (clear) {
                banner.clear(BANNER_OWNER);
            }
        }
    }

    public void onIncomingCallNotification(String name) {
        String show;
        long hold;
        synchronized (this) {
            if (state == State.OFFHOOK) {
                return;
            }
            callerName = sanitizeName(name);
            if (state == State.RINGING) {
                hold = RINGING_BANNER_MS;
            } else if (state == State.UNKNOWN) {
                hold = UNTRACKED_BANNER_MS;
            } else {
                return;
            }
            show = bannerText(callerName);
            bannerShown = true;
        }
        if (banner != null) {
            banner.show(BANNER_OWNER, show, BannerSink.PRIORITY_CALL, hold);
        }
    }

    public void onIncomingCallNotificationRemoved() {
        boolean clear;
        synchronized (this) {
            if (state == State.RINGING) {
                return;
            }
            clear = bannerShown;
            bannerShown = false;
            callerName = null;
        }
        if (clear && banner != null) {
            banner.clear(BANNER_OWNER);
        }
    }

    public String answer() {
        if (telecom == null || !telecom.canReadPhoneState()) {
            return RESULT_PERMISSION;
        }
        if (!isRinging()) {
            return RESULT_NOT_RINGING;
        }
        if (!telecom.canAnswerCalls()) {
            return RESULT_PERMISSION;
        }
        return telecom.acceptRingingCall() ? RESULT_ANSWERED : RESULT_ANSWER_FAILED;
    }

    public String silence() {
        if (telecom == null || !telecom.canReadPhoneState()) {
            return RESULT_PERMISSION;
        }
        if (!isRinging()) {
            return RESULT_NOT_RINGING;
        }
        return telecom.silenceRinger() ? RESULT_SILENCED : RESULT_SILENCE_FAILED;
    }

    static String bannerText(String name) {
        return name == null ? "Incoming call" : "Call: " + name;
    }

    static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH).trim() : cleaned;
    }
}
