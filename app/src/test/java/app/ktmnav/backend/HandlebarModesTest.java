package app.ktmnav.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.ktmnav.backend.HandlebarMappings.Action;
import app.ktmnav.backend.HandlebarMappings.Gesture;
import app.ktmnav.backend.HandlebarMappings.Mode;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class HandlebarModesTest {

    private final Map<String, String> prefs = new HashMap<>();

    private Action r(Mode mode, String button, Gesture gesture) {
        return HandlebarMappings.resolveFor(mode, button, gesture, false, prefs::get);
    }

    private Action ringing(Mode mode, String button, Gesture gesture) {
        return HandlebarMappings.resolveFor(mode, button, gesture, true, prefs::get);
    }

    @Test
    public void gesturePreferenceKeys() {
        assertEquals("handlebar_action_UP", HandlebarMappings.preferenceKey("UP", Gesture.SINGLE));
        assertEquals("handlebar_action_UP_DOUBLE", HandlebarMappings.preferenceKey("UP", Gesture.DOUBLE));
        assertEquals("handlebar_action_SET_LONG", HandlebarMappings.preferenceKey("SET", Gesture.LONG));
        assertEquals(HandlebarMappings.preferenceKey("BACK"), HandlebarMappings.preferenceKey("BACK", Gesture.SINGLE));
        assertNull(HandlebarMappings.preferenceKey("UP", null));
        assertNull(HandlebarMappings.preferenceKey("CRUISE", Gesture.DOUBLE));
    }

    @Test
    public void customModeKeepsLegacySingleDefaultsAndStoredValues() {
        assertEquals(Action.SCROLL_UP, r(Mode.CUSTOM, "UP", Gesture.SINGLE));
        assertEquals(Action.SCROLL_DOWN, r(Mode.CUSTOM, "DOWN", Gesture.SINGLE));
        assertEquals(Action.CLICK_FOCUSED, r(Mode.CUSTOM, "SET", Gesture.SINGLE));
        assertEquals(Action.BACK, r(Mode.CUSTOM, "BACK", Gesture.SINGLE));
        prefs.put("handlebar_action_UP", "VOLUME_UP");
        prefs.put("handlebar_action_SET", "NONE");
        assertEquals(Action.VOLUME_UP, r(Mode.CUSTOM, "UP", Gesture.SINGLE));
        assertEquals(Action.NONE, r(Mode.CUSTOM, "SET", Gesture.SINGLE));
        prefs.put("handlebar_action_DOWN", "NOT_AN_ACTION");
        assertEquals(Action.NONE, r(Mode.CUSTOM, "DOWN", Gesture.SINGLE));
    }

    @Test
    public void customDoubleAndLongDefaultsAreUnbound() {
        for (String b : HandlebarMappings.buttons()) {
            assertEquals(Action.NONE, HandlebarMappings.resolve(b, Gesture.DOUBLE, null));
            assertEquals(Action.NONE, HandlebarMappings.resolve(b, Gesture.LONG, null));
            assertFalse(HandlebarMappings.hasDoubleBinding(Mode.CUSTOM, b, false, prefs::get));
        }
    }

    @Test
    public void customDoubleAndLongBindingsResolve() {
        prefs.put("handlebar_action_SET_DOUBLE", "MEDIA_NEXT");
        prefs.put("handlebar_action_BACK_LONG", "OPEN_KTMNAV");
        assertEquals(Action.MEDIA_NEXT, r(Mode.CUSTOM, "SET", Gesture.DOUBLE));
        assertTrue(HandlebarMappings.hasDoubleBinding(Mode.CUSTOM, "SET", false, prefs::get));
        assertEquals(Action.OPEN_KTMNAV, r(Mode.CUSTOM, "BACK", Gesture.LONG));
    }

    @Test
    public void unboundLongFallsBackToSingle() {
        assertEquals(Action.CLICK_FOCUSED, r(Mode.CUSTOM, "SET", Gesture.LONG));
        assertEquals(Action.BACK, r(Mode.CONTROLLER, "BACK", Gesture.LONG));
        assertEquals(Action.MEDIA_PLAY_PAUSE, r(Mode.MUSIC, "SET", Gesture.LONG));
    }

    @Test
    public void reservedLongPressesInEveryModeExceptOff() {
        for (Mode mode : new Mode[]{Mode.CUSTOM, Mode.MUSIC, Mode.CONTROLLER}) {
            assertEquals(Action.CYCLE_MODE, r(mode, "UP", Gesture.LONG));
            assertEquals(Action.OPEN_NAV_APP, r(mode, "DOWN", Gesture.LONG));
        }
        prefs.put("handlebar_action_UP_LONG", "VOLUME_UP");
        assertEquals("Reserved gestures ignore stored values", Action.CYCLE_MODE, r(Mode.CUSTOM, "UP", Gesture.LONG));
        assertTrue(HandlebarMappings.isReserved("UP", Gesture.LONG));
        assertFalse(HandlebarMappings.isReserved("SET", Gesture.LONG));
        assertFalse(HandlebarMappings.isReserved("UP", Gesture.SINGLE));
    }

    @Test
    public void musicPreset() {
        assertEquals(Action.VOLUME_UP, r(Mode.MUSIC, "UP", Gesture.SINGLE));
        assertEquals(Action.VOLUME_DOWN, r(Mode.MUSIC, "DOWN", Gesture.SINGLE));
        assertEquals(Action.MEDIA_NEXT, r(Mode.MUSIC, "UP", Gesture.DOUBLE));
        assertEquals(Action.MEDIA_PREVIOUS, r(Mode.MUSIC, "DOWN", Gesture.DOUBLE));
        assertEquals(Action.MEDIA_PLAY_PAUSE, r(Mode.MUSIC, "SET", Gesture.SINGLE));
        assertEquals(Action.NONE, r(Mode.MUSIC, "BACK", Gesture.SINGLE));
        assertTrue(HandlebarMappings.hasDoubleBinding(Mode.MUSIC, "UP", false, prefs::get));
        assertTrue(HandlebarMappings.hasDoubleBinding(Mode.MUSIC, "DOWN", false, prefs::get));
        assertFalse(HandlebarMappings.hasDoubleBinding(Mode.MUSIC, "SET", false, prefs::get));
        prefs.put("handlebar_action_UP", "SCROLL_UP");
        assertEquals("Presets ignore custom prefs", Action.VOLUME_UP, r(Mode.MUSIC, "UP", Gesture.SINGLE));
    }

    @Test
    public void controllerPreset() {
        assertEquals(Action.FOCUS_PREVIOUS, r(Mode.CONTROLLER, "UP", Gesture.SINGLE));
        assertEquals(Action.FOCUS_NEXT, r(Mode.CONTROLLER, "DOWN", Gesture.SINGLE));
        assertEquals(Action.CLICK_FOCUSED, r(Mode.CONTROLLER, "SET", Gesture.SINGLE));
        assertEquals(Action.LONG_CLICK_FOCUSED, r(Mode.CONTROLLER, "SET", Gesture.LONG));
        assertEquals(Action.BACK, r(Mode.CONTROLLER, "BACK", Gesture.SINGLE));
        assertEquals(Action.NONE, r(Mode.CONTROLLER, "BACK", Gesture.DOUBLE));
        for (String b : HandlebarMappings.buttons()) {
            assertFalse(HandlebarMappings.hasDoubleBinding(Mode.CONTROLLER, b, false, prefs::get));
        }
    }

    @Test
    public void offIgnoresEverything() {
        for (String b : HandlebarMappings.buttons()) {
            for (Gesture g : Gesture.values()) {
                assertEquals(Action.NONE, r(Mode.OFF, b, g));
                assertEquals(Action.NONE, ringing(Mode.OFF, b, g));
            }
            assertFalse(HandlebarMappings.hasDoubleBinding(Mode.OFF, b, false, prefs::get));
        }
    }

    @Test
    public void ringingOverridesSetAndBack() {
        prefs.put("handlebar_action_SET_DOUBLE", "MEDIA_NEXT");
        for (Mode mode : new Mode[]{Mode.CUSTOM, Mode.MUSIC, Mode.CONTROLLER}) {
            for (Gesture g : Gesture.values()) {
                assertEquals(Action.ANSWER_CALL, ringing(mode, "SET", g));
                assertEquals(Action.SILENCE_CALL, ringing(mode, "BACK", g));
            }
            assertFalse("Answer must not wait for a double tap", HandlebarMappings.hasDoubleBinding(mode, "SET", true, prefs::get));
            assertFalse(HandlebarMappings.hasDoubleBinding(mode, "BACK", true, prefs::get));
        }
        assertEquals(Action.VOLUME_UP, ringing(Mode.MUSIC, "UP", Gesture.SINGLE));
        assertEquals(Action.CYCLE_MODE, ringing(Mode.MUSIC, "UP", Gesture.LONG));
    }

    @Test
    public void modeCycleAndPreferenceParsing() {
        assertEquals(Mode.MUSIC, Mode.CUSTOM.next());
        assertEquals(Mode.CONTROLLER, Mode.MUSIC.next());
        assertEquals(Mode.CUSTOM, Mode.CONTROLLER.next());
        assertEquals(Mode.CUSTOM, Mode.fromPreference(null));
        assertEquals(Mode.CUSTOM, Mode.fromPreference("music"));
        assertEquals(Mode.MUSIC, Mode.fromPreference("MUSIC"));
        assertEquals(Mode.OFF, Mode.fromPreference("OFF"));
        assertEquals("handlebar_mode", HandlebarMappings.PREF_MODE);
    }

    @Test
    public void newActionFlags() {
        assertTrue(Action.LONG_CLICK_FOCUSED.requiresAccessibility());
        assertFalse(Action.OPEN_NAV_APP.requiresAccessibility());
        assertTrue(Action.ANSWER_CALL.requiresPhonePermission());
        assertTrue(Action.SILENCE_CALL.requiresPhonePermission());
        assertFalse(Action.CYCLE_MODE.requiresPhonePermission());
        assertEquals(Action.OPEN_NAV_APP, HandlebarMappings.resolve("DOWN", "OPEN_NAV_APP"));
    }

    @Test
    public void invalidInputsResolveToNone() {
        assertEquals(Action.NONE, HandlebarMappings.resolveFor(null, "UP", Gesture.SINGLE, false, prefs::get));
        assertEquals(Action.NONE, r(Mode.CUSTOM, "CRUISE", Gesture.SINGLE));
        assertEquals(Action.NONE, r(Mode.CUSTOM, "UP", null));
        assertEquals(Action.SCROLL_UP, HandlebarMappings.resolveFor(Mode.CUSTOM, "UP", Gesture.SINGLE, false, null));
    }
}
