package app.ktmnav.backend;

import java.util.function.Function;

/**
 * Handlebar button action mappings, gesture-aware preference keys, button modes and resolution.
 * Pure Java class with zero Android platform dependencies.
 */
public final class HandlebarMappings {

    public enum Action {
        NONE("None"),
        OPEN_KTMNAV("Open KTMnav"),
        BACK("Back"),
        SCROLL_UP("Scroll up"),
        SCROLL_DOWN("Scroll down"),
        FOCUS_NEXT("Focus next"),
        FOCUS_PREVIOUS("Focus previous"),
        CLICK_FOCUSED("Click focused"),
        MEDIA_PLAY_PAUSE("Play / pause"),
        MEDIA_NEXT("Next track"),
        MEDIA_PREVIOUS("Previous track"),
        VOLUME_UP("Volume up"),
        VOLUME_DOWN("Volume down"),
        LONG_CLICK_FOCUSED("Long-press focused"),
        OPEN_NAV_APP("Open navigation app"),
        CYCLE_MODE("Change button mode"),
        ANSWER_CALL("Answer call"),
        SILENCE_CALL("Silence ringer");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public boolean requiresAccessibility() {
            return this == BACK
                    || this == SCROLL_UP
                    || this == SCROLL_DOWN
                    || this == FOCUS_NEXT
                    || this == FOCUS_PREVIOUS
                    || this == CLICK_FOCUSED
                    || this == LONG_CLICK_FOCUSED;
        }

        public boolean requiresNotificationAccess() {
            return this == MEDIA_PLAY_PAUSE
                    || this == MEDIA_NEXT
                    || this == MEDIA_PREVIOUS;
        }

        public boolean requiresPhonePermission() {
            return this == ANSWER_CALL || this == SILENCE_CALL;
        }
    }

    public enum Gesture {
        SINGLE("", "tap"),
        DOUBLE("_DOUBLE", "double tap"),
        LONG("_LONG", "long press");

        private final String keySuffix;
        private final String label;

        Gesture(String keySuffix, String label) {
            this.keySuffix = keySuffix;
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum Mode {
        CUSTOM("Custom"),
        MUSIC("Music"),
        CONTROLLER("Controller"),
        OFF("Off");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public Mode next() {
            switch (this) {
                case CUSTOM:
                    return MUSIC;
                case MUSIC:
                    return CONTROLLER;
                default:
                    return CUSTOM;
            }
        }

        public static Mode fromPreference(String stored) {
            if (stored == null) {
                return CUSTOM;
            }
            for (Mode mode : values()) {
                if (mode.name().equals(stored)) {
                    return mode;
                }
            }
            return CUSTOM;
        }
    }

    public static final String PREF_MODE = "handlebar_mode";

    private static final String[] BUTTONS = new String[]{"UP", "DOWN", "SET", "BACK"};
    private static final String PREF_PREFIX = "handlebar_action_";

    private HandlebarMappings() {
    }

    public static String[] buttons() {
        return BUTTONS.clone();
    }

    public static String preferenceKey(String button) {
        if (!isValidButton(button)) {
            return null;
        }
        return PREF_PREFIX + button;
    }

    public static String preferenceKey(String button, Gesture gesture) {
        if (!isValidButton(button) || gesture == null) {
            return null;
        }
        return PREF_PREFIX + button + gesture.keySuffix;
    }

    public static Action resolve(String button, String stored) {
        if (!isValidButton(button)) {
            return Action.NONE;
        }
        if (stored == null) {
            switch (button) {
                case "UP":
                    return Action.SCROLL_UP;
                case "DOWN":
                    return Action.SCROLL_DOWN;
                case "SET":
                    return Action.CLICK_FOCUSED;
                case "BACK":
                    return Action.BACK;
                default:
                    return Action.NONE;
            }
        }
        return parse(stored);
    }

    public static Action resolve(String button, Gesture gesture, String stored) {
        if (!isValidButton(button) || gesture == null) {
            return Action.NONE;
        }
        if (gesture == Gesture.SINGLE) {
            return resolve(button, stored);
        }
        return stored == null ? Action.NONE : parse(stored);
    }

    /** Long-press UP and DOWN are fixed in every mode except OFF so the rider can always change mode. */
    public static boolean isReserved(String button, Gesture gesture) {
        return gesture == Gesture.LONG && ("UP".equals(button) || "DOWN".equals(button));
    }

    public static Action preset(Mode mode, String button, Gesture gesture) {
        if (mode == null || !isValidButton(button) || gesture == null) {
            return Action.NONE;
        }
        if (mode == Mode.MUSIC) {
            switch (button) {
                case "UP":
                    return gesture == Gesture.DOUBLE ? Action.MEDIA_NEXT
                            : gesture == Gesture.SINGLE ? Action.VOLUME_UP : Action.NONE;
                case "DOWN":
                    return gesture == Gesture.DOUBLE ? Action.MEDIA_PREVIOUS
                            : gesture == Gesture.SINGLE ? Action.VOLUME_DOWN : Action.NONE;
                case "SET":
                    return gesture == Gesture.SINGLE ? Action.MEDIA_PLAY_PAUSE : Action.NONE;
                default:
                    return Action.NONE;
            }
        }
        if (mode == Mode.CONTROLLER) {
            switch (button) {
                case "UP":
                    return gesture == Gesture.SINGLE ? Action.FOCUS_PREVIOUS : Action.NONE;
                case "DOWN":
                    return gesture == Gesture.SINGLE ? Action.FOCUS_NEXT : Action.NONE;
                case "SET":
                    return gesture == Gesture.SINGLE ? Action.CLICK_FOCUSED
                            : gesture == Gesture.LONG ? Action.LONG_CLICK_FOCUSED : Action.NONE;
                case "BACK":
                    return gesture == Gesture.SINGLE ? Action.BACK : Action.NONE;
                default:
                    return Action.NONE;
            }
        }
        return Action.NONE;
    }

    /**
     * Resolves the action for a recognised gesture. Order: OFF ignores everything; a ringing call
     * turns SET into answer and BACK into silence; reserved long presses; the mode binding; an
     * unbound long press falls back to the single-tap binding.
     */
    public static Action resolveFor(Mode mode, String button, Gesture gesture, boolean ringing,
            Function<String, String> stored) {
        if (mode == null || mode == Mode.OFF || !isValidButton(button) || gesture == null) {
            return Action.NONE;
        }
        if (ringing && "SET".equals(button)) {
            return Action.ANSWER_CALL;
        }
        if (ringing && "BACK".equals(button)) {
            return Action.SILENCE_CALL;
        }
        if (isReserved(button, gesture)) {
            return "UP".equals(button) ? Action.CYCLE_MODE : Action.OPEN_NAV_APP;
        }
        Action action = binding(mode, button, gesture, stored);
        if (action == Action.NONE && gesture == Gesture.LONG) {
            action = binding(mode, button, Gesture.SINGLE, stored);
        }
        return action;
    }

    /** True only when a double tap would do something, so other buttons fire without delay. */
    public static boolean hasDoubleBinding(Mode mode, String button, boolean ringing,
            Function<String, String> stored) {
        if (mode == null || mode == Mode.OFF || !isValidButton(button)) {
            return false;
        }
        if (ringing && ("SET".equals(button) || "BACK".equals(button))) {
            return false;
        }
        return binding(mode, button, Gesture.DOUBLE, stored) != Action.NONE;
    }

    private static Action binding(Mode mode, String button, Gesture gesture, Function<String, String> stored) {
        if (mode == Mode.CUSTOM) {
            String value = stored != null ? stored.apply(preferenceKey(button, gesture)) : null;
            return resolve(button, gesture, value);
        }
        return preset(mode, button, gesture);
    }

    private static Action parse(String stored) {
        try {
            return Action.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Action.NONE;
        }
    }

    private static boolean isValidButton(String button) {
        if (button == null) {
            return false;
        }
        for (String b : BUTTONS) {
            if (b.equals(button)) {
                return true;
            }
        }
        return false;
    }
}
