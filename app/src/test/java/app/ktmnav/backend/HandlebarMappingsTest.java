package app.ktmnav.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for HandlebarMappings contract:
 * resolution defaults, preference keys, invalid button and stored value handling,
 * accessibility and notification access requirements, and human labels.
 */
public final class HandlebarMappingsTest {

    @Test
    public void testButtonsContract() {
        String[] expected = new String[]{"UP", "DOWN", "SET", "BACK"};
        assertArrayEquals("Buttons array must match UP, DOWN, SET, BACK", expected, HandlebarMappings.buttons());
    }

    @Test
    public void testPreferenceKeys() {
        assertEquals("handlebar_action_UP", HandlebarMappings.preferenceKey("UP"));
        assertEquals("handlebar_action_DOWN", HandlebarMappings.preferenceKey("DOWN"));
        assertEquals("handlebar_action_SET", HandlebarMappings.preferenceKey("SET"));
        assertEquals("handlebar_action_BACK", HandlebarMappings.preferenceKey("BACK"));

        assertNull("Null button must return null preference key", HandlebarMappings.preferenceKey(null));
        assertNull("Lowercase button must return null preference key", HandlebarMappings.preferenceKey("up"));
        assertNull("Empty button must return null preference key", HandlebarMappings.preferenceKey(""));
        assertNull("Unknown button must return null preference key", HandlebarMappings.preferenceKey("CRUISE"));
    }

    @Test
    public void testResolveDefaultsWhenStoredNull() {
        assertEquals(HandlebarMappings.Action.SCROLL_UP, HandlebarMappings.resolve("UP", null));
        assertEquals(HandlebarMappings.Action.SCROLL_DOWN, HandlebarMappings.resolve("DOWN", null));
        assertEquals(HandlebarMappings.Action.CLICK_FOCUSED, HandlebarMappings.resolve("SET", null));
        assertEquals(HandlebarMappings.Action.BACK, HandlebarMappings.resolve("BACK", null));
    }

    @Test
    public void testResolveExplicitStoredActions() {
        assertEquals(HandlebarMappings.Action.VOLUME_UP, HandlebarMappings.resolve("UP", "VOLUME_UP"));
        assertEquals(HandlebarMappings.Action.VOLUME_DOWN, HandlebarMappings.resolve("DOWN", "VOLUME_DOWN"));
        assertEquals(HandlebarMappings.Action.MEDIA_PLAY_PAUSE, HandlebarMappings.resolve("SET", "MEDIA_PLAY_PAUSE"));
        assertEquals(HandlebarMappings.Action.MEDIA_NEXT, HandlebarMappings.resolve("UP", "MEDIA_NEXT"));
        assertEquals(HandlebarMappings.Action.MEDIA_PREVIOUS, HandlebarMappings.resolve("DOWN", "MEDIA_PREVIOUS"));
        assertEquals(HandlebarMappings.Action.OPEN_KTMNAV, HandlebarMappings.resolve("BACK", "OPEN_KTMNAV"));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("SET", "NONE"));
        assertEquals(HandlebarMappings.Action.FOCUS_NEXT, HandlebarMappings.resolve("DOWN", "FOCUS_NEXT"));
        assertEquals(HandlebarMappings.Action.FOCUS_PREVIOUS, HandlebarMappings.resolve("UP", "FOCUS_PREVIOUS"));
    }

    @Test
    public void testResolveInvalidStoredFallsBackToNone() {
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("UP", "INVALID_ACTION"));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("DOWN", "scroll_down"));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("SET", ""));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("BACK", "UNKNOWN"));
    }

    @Test
    public void testResolveUnknownButtonReturnsNone() {
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("CRUISE", "SCROLL_UP"));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("CRUISE", null));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve(null, "BACK"));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve(null, null));
        assertEquals(HandlebarMappings.Action.NONE, HandlebarMappings.resolve("up", "SCROLL_UP"));
    }

    @Test
    public void testRequiresAccessibilityContract() {
        assertTrue(HandlebarMappings.Action.BACK.requiresAccessibility());
        assertTrue(HandlebarMappings.Action.SCROLL_UP.requiresAccessibility());
        assertTrue(HandlebarMappings.Action.SCROLL_DOWN.requiresAccessibility());
        assertTrue(HandlebarMappings.Action.FOCUS_NEXT.requiresAccessibility());
        assertTrue(HandlebarMappings.Action.FOCUS_PREVIOUS.requiresAccessibility());
        assertTrue(HandlebarMappings.Action.CLICK_FOCUSED.requiresAccessibility());

        assertFalse(HandlebarMappings.Action.NONE.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.OPEN_KTMNAV.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.MEDIA_PLAY_PAUSE.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.MEDIA_NEXT.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.MEDIA_PREVIOUS.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.VOLUME_UP.requiresAccessibility());
        assertFalse(HandlebarMappings.Action.VOLUME_DOWN.requiresAccessibility());
    }

    @Test
    public void testRequiresNotificationAccessContract() {
        assertTrue(HandlebarMappings.Action.MEDIA_PLAY_PAUSE.requiresNotificationAccess());
        assertTrue(HandlebarMappings.Action.MEDIA_NEXT.requiresNotificationAccess());
        assertTrue(HandlebarMappings.Action.MEDIA_PREVIOUS.requiresNotificationAccess());

        assertFalse(HandlebarMappings.Action.NONE.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.OPEN_KTMNAV.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.BACK.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.SCROLL_UP.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.SCROLL_DOWN.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.FOCUS_NEXT.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.FOCUS_PREVIOUS.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.CLICK_FOCUSED.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.VOLUME_UP.requiresNotificationAccess());
        assertFalse(HandlebarMappings.Action.VOLUME_DOWN.requiresNotificationAccess());
    }

    @Test
    public void testActionLabels() {
        assertEquals("None", HandlebarMappings.Action.NONE.getLabel());
        assertEquals("Open KTMnav", HandlebarMappings.Action.OPEN_KTMNAV.getLabel());
        assertEquals("Back", HandlebarMappings.Action.BACK.getLabel());
        assertEquals("Scroll up", HandlebarMappings.Action.SCROLL_UP.getLabel());
        assertEquals("Scroll down", HandlebarMappings.Action.SCROLL_DOWN.getLabel());
        assertEquals("Focus next", HandlebarMappings.Action.FOCUS_NEXT.getLabel());
        assertEquals("Focus previous", HandlebarMappings.Action.FOCUS_PREVIOUS.getLabel());
        assertEquals("Click focused", HandlebarMappings.Action.CLICK_FOCUSED.getLabel());
        assertEquals("Play / pause", HandlebarMappings.Action.MEDIA_PLAY_PAUSE.getLabel());
        assertEquals("Next track", HandlebarMappings.Action.MEDIA_NEXT.getLabel());
        assertEquals("Previous track", HandlebarMappings.Action.MEDIA_PREVIOUS.getLabel());
        assertEquals("Volume up", HandlebarMappings.Action.VOLUME_UP.getLabel());
        assertEquals("Volume down", HandlebarMappings.Action.VOLUME_DOWN.getLabel());

        for (HandlebarMappings.Action action : HandlebarMappings.Action.values()) {
            assertNotNull("Action label must not be null for " + action.name(), action.getLabel());
            assertFalse("Action label must not be empty for " + action.name(), action.getLabel().trim().isEmpty());
        }
    }
}
