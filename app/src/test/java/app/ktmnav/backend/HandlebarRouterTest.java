package app.ktmnav.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.ktmnav.backend.HandlebarMappings.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public final class HandlebarRouterTest {

    private static final int UP = HandlebarGestureRecognizer.MASK_UP;
    private static final int DOWN = HandlebarGestureRecognizer.MASK_DOWN;
    private static final int SET = HandlebarGestureRecognizer.MASK_SET;
    private static final int BACK = HandlebarGestureRecognizer.MASK_BACK;

    private final Map<String, String> strings = new HashMap<>();
    private final Map<String, Boolean> booleans = new HashMap<>();
    private final List<Action> executed = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private final FakeBanner banner = new FakeBanner();
    private final FakeTelecom telecom = new FakeTelecom();
    private FakeScheduler scheduler;
    private CallControl calls;
    private HandlebarRouter router;
    private int legacyBacks;
    private long clock;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        calls = new CallControl(telecom, banner);
        booleans.put(HandlebarRouter.PREF_MAPPINGS_ENABLED, true);
        HandlebarRouter.Prefs prefs = new HandlebarRouter.Prefs() {
            @Override
            public String getString(String key) {
                return strings.get(key);
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                Boolean v = booleans.get(key);
                return v != null ? v : defaultValue;
            }

            @Override
            public void putString(String key, String value) {
                strings.put(key, value);
            }
        };
        router = new HandlebarRouter(prefs, scheduler, action -> {
            executed.add(action);
            return "ok";
        }, calls, banner, results::add, () -> {
            legacyBacks++;
            return "Requested KTMnav";
        });
    }

    private void tap(int mask) {
        press(mask, 60);
    }

    private void press(int mask, long holdMs) {
        clock += 10;
        scheduler.advanceTo(clock);
        router.onMask(mask, clock);
        clock += holdMs;
        scheduler.advanceTo(clock);
        router.onMask(0, clock);
    }

    private void idle() {
        clock += 1000;
        scheduler.advanceTo(clock);
    }

    @Test
    public void customModeSingleTapsUseLegacyKeysWithoutDelay() {
        strings.put("handlebar_action_UP", "VOLUME_UP");
        tap(UP);
        assertEquals(Arrays.asList(Action.VOLUME_UP), executed);
        assertEquals(Arrays.asList("UP -> Volume up: ok"), results);
        tap(SET);
        assertEquals(Action.CLICK_FOCUSED, executed.get(1));
    }

    @Test
    public void musicModeDoubleTapSkipsTrack() {
        strings.put(HandlebarMappings.PREF_MODE, "MUSIC");
        tap(UP);
        tap(UP);
        idle();
        tap(DOWN);
        idle();
        tap(SET);
        assertEquals(Arrays.asList(Action.MEDIA_NEXT, Action.VOLUME_DOWN, Action.MEDIA_PLAY_PAUSE), executed);
        assertEquals("UP double -> Next track: ok", results.get(0));
    }

    @Test
    public void controllerModeLongSetLongClicks() {
        strings.put(HandlebarMappings.PREF_MODE, "CONTROLLER");
        press(SET, 600);
        tap(BACK);
        tap(DOWN);
        assertEquals(Arrays.asList(Action.LONG_CLICK_FOCUSED, Action.BACK, Action.FOCUS_NEXT), executed);
        assertEquals("SET long -> Long-press focused: ok", results.get(0));
    }

    @Test
    public void longUpCyclesModesAndAnnouncesOnDash() {
        press(UP, 500);
        assertEquals("MUSIC", strings.get(HandlebarMappings.PREF_MODE));
        press(UP, 500);
        assertEquals("CONTROLLER", strings.get(HandlebarMappings.PREF_MODE));
        press(UP, 500);
        assertEquals("CUSTOM", strings.get(HandlebarMappings.PREF_MODE));
        assertEquals(Arrays.asList(
                "show handlebar 'Mode: Music' p0 2000",
                "show handlebar 'Mode: Controller' p0 2000",
                "show handlebar 'Mode: Custom' p0 2000"), banner.log);
        assertEquals("UP long -> Change button mode: Mode: Music", results.get(0));
        assertTrue(executed.isEmpty());
    }

    @Test
    public void longDownOpensNavApp() {
        press(DOWN, 450);
        assertEquals(Arrays.asList(Action.OPEN_NAV_APP), executed);
    }

    @Test
    public void offModeIgnoresButtonsIncludingCycle() {
        strings.put(HandlebarMappings.PREF_MODE, "OFF");
        press(UP, 500);
        tap(SET);
        assertTrue(executed.isEmpty());
        assertEquals("OFF", strings.get(HandlebarMappings.PREF_MODE));
        assertEquals(Arrays.asList("Ignored: button mode Off", "Ignored: button mode Off"), results);
    }

    @Test
    public void ringingCallOverridesSetAndBack() {
        strings.put("handlebar_action_SET_DOUBLE", "MEDIA_NEXT");
        calls.onCallStateChanged(CallControl.State.RINGING);
        tap(SET);
        assertEquals("Answer is immediate, no double-tap wait", Arrays.asList("SET -> Answer call: Answered call"), results);
        tap(BACK);
        assertEquals("BACK -> Silence ringer: Silenced ringer", results.get(1));
        assertEquals(1, telecom.accepted);
        assertEquals(1, telecom.silenced);
        assertTrue(executed.isEmpty());
    }

    @Test
    public void mappedAnswerWithoutCallIsHonest() {
        strings.put("handlebar_action_SET", "ANSWER_CALL");
        tap(SET);
        assertEquals(Arrays.asList("SET -> Answer call: No ringing call"), results);
        assertEquals(0, telecom.accepted);
    }

    @Test
    public void masterSwitchOffKeepsLegacyBack() {
        booleans.put(HandlebarRouter.PREF_MAPPINGS_ENABLED, false);
        strings.put(HandlebarMappings.PREF_MODE, "MUSIC");
        tap(UP);
        tap(BACK);
        press(UP, 500);
        assertTrue(executed.isEmpty());
        assertEquals(1, legacyBacks);
        assertEquals(Arrays.asList("Ignored: custom mappings disabled", "Requested KTMnav",
                "Ignored: custom mappings disabled"), results);
        assertEquals("MUSIC", strings.get(HandlebarMappings.PREF_MODE));
    }

    @Test
    public void resetDropsPendingDoubleTap() {
        strings.put(HandlebarMappings.PREF_MODE, "MUSIC");
        tap(UP);
        router.reset();
        idle();
        assertTrue(executed.isEmpty());
        assertTrue(results.isEmpty());
    }

    @Test
    public void executorExceptionIsReported() {
        HandlebarRouter failing = new HandlebarRouter(new HandlebarRouter.Prefs() {
            @Override
            public String getString(String key) {
                return null;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return true;
            }

            @Override
            public void putString(String key, String value) {
            }
        }, scheduler, action -> {
            throw new IllegalStateException("boom");
        }, null, null, results::add, null);
        failing.onMask(SET, 0);
        failing.onMask(0, 50);
        assertEquals(Arrays.asList("SET -> Click focused: Execution error"), results);
    }

    @Test
    public void ringingOverrideAppliesEvenWithCustomMappingsOff() {
        booleans.put(HandlebarRouter.PREF_MAPPINGS_ENABLED, false);
        calls.onCallStateChanged(CallControl.State.RINGING);
        tap(SET);
        tap(BACK);
        assertEquals(Arrays.asList("SET -> Answer call: Answered call", "BACK -> Silence ringer: Silenced ringer"), results);
        assertEquals(0, legacyBacks);
        assertEquals(1, telecom.accepted);
        assertEquals(1, telecom.silenced);
        tap(UP);
        assertEquals("other buttons keep the master-switch behaviour", "Ignored: custom mappings disabled", results.get(2));
    }

    @Test
    public void ringingOverrideRespectsModeOffAndPermission() {
        calls.onCallStateChanged(CallControl.State.RINGING);
        strings.put(HandlebarMappings.PREF_MODE, "OFF");
        tap(SET);
        assertEquals(Arrays.asList("Ignored: button mode Off"), results);
        strings.remove(HandlebarMappings.PREF_MODE);
        booleans.put(HandlebarRouter.PREF_MAPPINGS_ENABLED, false);
        telecom.phoneState = false;
        tap(BACK);
        assertEquals("without phone permission BACK stays the legacy return", 1, legacyBacks);
        assertEquals(0, telecom.accepted);
        assertEquals(0, telecom.silenced);
    }
}
