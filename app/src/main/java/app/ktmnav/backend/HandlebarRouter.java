package app.ktmnav.backend;

import android.content.SharedPreferences;

/**
 * Glue from RCM masks to phone actions: gesture recognition, mode/call-aware resolution, execution
 * and a one-line result for the UI. Mode changes are announced on the dash through {@link BannerSink}.
 */
public final class HandlebarRouter implements HandlebarMaskListener {

    public static final String PREF_MAPPINGS_ENABLED = "handlebar_mappings_enabled";
    public static final String BANNER_OWNER = "handlebar";
    public static final long MODE_BANNER_MS = 2_000L;

    public interface Prefs {
        String getString(String key);

        boolean getBoolean(String key, boolean defaultValue);

        void putString(String key, String value);
    }

    public interface ActionExecutor {
        String execute(HandlebarMappings.Action action);
    }

    public interface ResultListener {
        void onHandlebarResult(String summary);
    }

    public interface LegacyBackHandler {
        String handleBack();
    }

    private final Prefs prefs;
    private final ActionExecutor executor;
    private final CallControl calls;
    private final BannerSink banner;
    private final ResultListener results;
    private final LegacyBackHandler legacyBack;
    private final HandlebarGestureRecognizer recognizer;

    public HandlebarRouter(Prefs prefs, HandlebarGestureRecognizer.Scheduler scheduler, ActionExecutor executor,
            CallControl calls, BannerSink banner, ResultListener results, LegacyBackHandler legacyBack) {
        this.prefs = prefs;
        this.executor = executor;
        this.calls = calls;
        this.banner = banner;
        this.results = results;
        this.legacyBack = legacyBack;
        this.recognizer = new HandlebarGestureRecognizer(scheduler, this::allowsDouble, this::onGesture);
    }

    public static Prefs prefsOf(SharedPreferences shared) {
        return new Prefs() {
            @Override
            public String getString(String key) {
                return key == null ? null : shared.getString(key, null);
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return shared.getBoolean(key, defaultValue);
            }

            @Override
            public void putString(String key, String value) {
                shared.edit().putString(key, value).apply();
            }
        };
    }

    @Override
    public void onMask(int mask, long uptimeMs) {
        recognizer.onMask(mask, uptimeMs);
    }

    public void reset() {
        recognizer.reset();
    }

    public HandlebarMappings.Mode currentMode() {
        return HandlebarMappings.Mode.fromPreference(prefs.getString(HandlebarMappings.PREF_MODE));
    }

    public HandlebarMappings.Action resolve(String button, HandlebarMappings.Gesture gesture) {
        return HandlebarMappings.resolveFor(currentMode(), button, gesture, isRinging(), prefs::getString);
    }

    public String cycleMode() {
        HandlebarMappings.Mode next = currentMode().next();
        prefs.putString(HandlebarMappings.PREF_MODE, next.name());
        String text = "Mode: " + next.getLabel();
        if (banner != null) {
            banner.show(BANNER_OWNER, text, BannerSink.PRIORITY_INFO, MODE_BANNER_MS);
        }
        return text;
    }

    static String describe(String button, HandlebarMappings.Gesture gesture) {
        switch (gesture) {
            case DOUBLE:
                return button + " double";
            case LONG:
                return button + " long";
            default:
                return button;
        }
    }

    private boolean mappingsEnabled() {
        return prefs.getBoolean(PREF_MAPPINGS_ENABLED, false);
    }

    private boolean isRinging() {
        return calls != null && calls.isRinging();
    }

    private boolean allowsDouble(String button) {
        return mappingsEnabled()
                && HandlebarMappings.hasDoubleBinding(currentMode(), button, isRinging(), prefs::getString);
    }

    /** While ringing, SET answers and BACK silences whenever phone permission is granted and the mode is not Off. */
    private HandlebarMappings.Action ringingOverride(String button) {
        if (!isRinging() || !calls.hasPhonePermission() || currentMode() == HandlebarMappings.Mode.OFF) {
            return null;
        }
        if ("SET".equals(button)) {
            return HandlebarMappings.Action.ANSWER_CALL;
        }
        if ("BACK".equals(button)) {
            return HandlebarMappings.Action.SILENCE_CALL;
        }
        return null;
    }

    private void onGesture(String button, HandlebarMappings.Gesture gesture) {
        String summary;
        HandlebarMappings.Action ringAction = ringingOverride(button);
        if (ringAction != null) {
            summary = describe(button, gesture) + " -> " + ringAction.getLabel() + ": " + run(ringAction);
        } else if (!mappingsEnabled()) {
            if ("BACK".equals(button) && legacyBack != null) {
                summary = legacyBack.handleBack();
            } else {
                summary = "Ignored: custom mappings disabled";
            }
        } else if (currentMode() == HandlebarMappings.Mode.OFF) {
            summary = "Ignored: button mode Off";
        } else {
            HandlebarMappings.Action action = resolve(button, gesture);
            summary = describe(button, gesture) + " -> " + action.getLabel() + ": " + run(action);
        }
        if (results != null) {
            results.onHandlebarResult(summary);
        }
    }

    private String run(HandlebarMappings.Action action) {
        try {
            switch (action) {
                case CYCLE_MODE:
                    return cycleMode();
                case ANSWER_CALL:
                    return calls != null ? calls.answer() : CallControl.RESULT_PERMISSION;
                case SILENCE_CALL:
                    return calls != null ? calls.silence() : CallControl.RESULT_PERMISSION;
                default:
                    return executor != null ? executor.execute(action) : "Ignored";
            }
        } catch (RuntimeException e) {
            return "Execution error";
        }
    }
}
