package app.ktmnav.backend;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.List;

/**
 * Executes phone-side handlebar actions (app navigation, volume, media playback, and accessibility UI).
 * Never throws runtime exceptions; returns human-readable English results.
 */
public final class HandlebarController {

    public static final String PREF_PREFERRED_NAV_APP = "preferred_nav_app";
    public static final String GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final String PREFS_NAME = "ktmnav";

    private final Context context;

    public HandlebarController(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    public static boolean isAccessibilityConnected() {
        return HandlebarAccessibilityService.isConnected();
    }

    public String execute(HandlebarMappings.Action action) {
        if (action == null || action == HandlebarMappings.Action.NONE) {
            return "Ignored";
        }
        if (context == null) {
            return "Context unavailable";
        }

        try {
            switch (action) {
                case OPEN_KTMNAV:
                    return openApp();
                case OPEN_NAV_APP:
                    return openNavApp();
                case CYCLE_MODE:
                case ANSWER_CALL:
                case SILENCE_CALL:
                    return "Handled by handlebar router";
                case VOLUME_UP:
                    return adjustVolume(AudioManager.ADJUST_RAISE);
                case VOLUME_DOWN:
                    return adjustVolume(AudioManager.ADJUST_LOWER);
                case MEDIA_PLAY_PAUSE:
                    return sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play / pause");
                case MEDIA_NEXT:
                    return sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track");
                case MEDIA_PREVIOUS:
                    return sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track");
                case BACK:
                case SCROLL_UP:
                case SCROLL_DOWN:
                case FOCUS_NEXT:
                case FOCUS_PREVIOUS:
                case CLICK_FOCUSED:
                case LONG_CLICK_FOCUSED:
                    return executeAccessibility(action);
                default:
                    return "Unknown action: " + action.name();
            }
        } catch (Throwable t) {
            return "Execution error: " + t.getMessage();
        }
    }

    private String openApp() {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent == null) {
                return "Launch intent not found";
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(intent);
            return "Requested KTMnav";
        } catch (Exception e) {
            return "Could not open KTMnav: " + e.getMessage();
        }
    }

    private String openNavApp() {
        String preferred = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_PREFERRED_NAV_APP, null);
        PackageManager pm = context.getPackageManager();
        boolean preferredSet = isPackageName(preferred) && !GOOGLE_MAPS_PACKAGE.equals(preferred);
        if (preferredSet) {
            Intent intent = pm.getLaunchIntentForPackage(preferred);
            if (intent != null) {
                return launch(intent, "Requested navigation app");
            }
        }
        Intent maps = pm.getLaunchIntentForPackage(GOOGLE_MAPS_PACKAGE);
        if (maps != null) {
            return launch(maps, preferredSet ? "Requested Google Maps (preferred app unavailable)" : "Requested Google Maps");
        }
        // No Google Maps either: fall back to whichever catalogue app is installed.
        for (NavApps.App app : NavApps.ALL) {
            for (String pkg : app.packages) {
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    return launch(intent, "Requested " + app.name);
                }
            }
        }
        return "Navigation app not installed";
    }

    private String launch(Intent intent, String result) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
            return result;
        } catch (Exception e) {
            return "Could not open navigation app";
        }
    }

    static boolean isPackageName(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private String adjustVolume(int direction) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return "Audio service unavailable";
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            return direction == AudioManager.ADJUST_RAISE ? "Volume increased" : "Volume decreased";
        } catch (Exception e) {
            return "Volume adjustment failed: " + e.getMessage();
        }
    }

    private String sendMediaKey(int keyCode, String actionName) {
        MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) {
            return "Media service unavailable";
        }

        List<MediaController> controllers;
        try {
            ComponentName listenerComponent = new ComponentName(context, NavListener.class);
            controllers = msm.getActiveSessions(listenerComponent);
        } catch (SecurityException e) {
            return "Notification access required for media controls";
        } catch (Exception e) {
            return "Failed to query media sessions: " + e.getMessage();
        }

        if (controllers == null || controllers.isEmpty()) {
            return "No active media session";
        }

        MediaController targetController = null;
        for (MediaController mc : controllers) {
            PlaybackState state = mc.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                targetController = mc;
                break;
            }
        }

        if (targetController == null) {
            for (MediaController mc : controllers) {
                PlaybackState state = mc.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PAUSED) {
                    targetController = mc;
                    break;
                }
            }
        }

        if (targetController == null) {
            targetController = controllers.get(0);
        }

        long eventTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);

        boolean downSent = targetController.dispatchMediaButtonEvent(down);
        boolean upSent = targetController.dispatchMediaButtonEvent(up);

        if (!downSent && !upSent) {
            return "Media event rejected by player";
        }
        if (!downSent || !upSent) {
            return "Media event partially rejected by player";
        }
        return "Media command sent: " + actionName;
    }

    private String executeAccessibility(HandlebarMappings.Action action) {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            return "Phone is locked";
        }

        if (!isAccessibilityConnected()) {
            return "Accessibility service not connected";
        }

        return HandlebarAccessibilityService.perform(action);
    }
}
