package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * NotificationListenerService that listens for navigation updates from supported third-party apps.
 * Also provides diagnostic maneuver arrow capture matching TurnIt CAPTURE_MANEUVERS / capture_maneuvers,
 * and routes chat messages, dialer call notifications and track changes to the hooks in FeatureHooks.
 */
public class NavListener extends NotificationListenerService {
    private static final String TAG = "KTMnav.NavListener";
    private static final String PREFS_NAME = "ktmnav";

    public static final String CAPTURE_DIR = "nav_capture";
    public static final String CAPTURE_MARKER = "CAPTURE_MANEUVERS";
    public static final String PREF_CAPTURE_MANEUVERS = "capture_maneuvers";
    public static final int MAX_CAPTURES = 100;

    /**
     * Navigation apps re-post their ongoing notification when the next turn changes; the removal of the
     * old one arrives first. Guidance is only ended when nothing new follows within this window.
     */
    public static final long REMOVAL_DEBOUNCE_MS = 2000L;

    private final Map<String, NavSource> sources = new HashMap<>();
    private final Map<String, Runnable> pendingRemovals = new HashMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final CallNotifications calls = new CallNotifications();

    @Override
    public void onCreate() {
        super.onCreate();
        register(new GoogleMapsNavSource());
        register(new TomTomNavSource());
        register(new OsmAndNavSource(OsmAndNavSource.PACKAGE_FREE));
        register(new OsmAndNavSource(OsmAndNavSource.PACKAGE_PLUS));
        register(new KurvigerNavSource());
        register(new CalimotoNavSource());
        register(new OrganicMapsNavSource());
        register(new WazeNavSource());
    }

    private void register(NavSource source) {
        sources.put(source.getPackageName(), source);
    }

    private boolean isPackageEnabled(String pkg) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("enabled_" + pkg, true);
    }

    boolean isCaptureEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean prefEnabled = prefs.getBoolean(PREF_CAPTURE_MANEUVERS, false);
        File marker = new File(getFilesDir(), CAPTURE_MARKER);
        return prefEnabled || marker.exists();
    }

    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        stopTrackBanner();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        connected = false;
        stopTrackBanner();
        super.onDestroy();
    }

    private void stopTrackBanner() {
        try {
            TrackBannerMonitor.get(this).stop();
        } catch (Throwable t) {
            Log.w(TAG, "Error stopping track banner monitor", t);
        }
    }

    @Override
    public void onListenerConnected() {
        connected = true;
        Log.i(TAG, "Notification listener connected, listening for: " + sources.keySet());
        try {
            TrackBannerMonitor.get(this).start();
        } catch (Throwable t) {
            Log.w(TAG, "Error starting track banner monitor", t);
        }
        try {
            Bridge.get(this).onNotificationListenerConnected();
        } catch (Throwable t) {
            Log.w(TAG, "Error notifying bridge of listener connection", t);
        }

        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && sources.containsKey(sbn.getPackageName())) {
                        onNotificationPosted(sbn);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error replaying active notifications", t);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        NavSource source = sources.get(pkg);
        if (source == null) {
            onOtherNotificationPosted(sbn);
            return;
        }

        if (!isPackageEnabled(pkg)) {
            Log.d(TAG, "Dropping navigation from disabled source: " + pkg);
            return;
        }

        try {
            NavUpdate update = source.parse(this, sbn);
            if (update == null) {
                return;
            }
            if (!GoogleMapsNavSource.PACKAGE_NAME.equals(pkg)) {
                update = ManeuverResolver.refine(this, pkg, update);
            }
            cancelPendingRemoval(pkg);

            Log.d(TAG, "Guidance from " + pkg + ": " + LogRedact.nav(update));
            Bridge.get(this).onNavGuidance(pkg, update);
            captureArrowIfEnabled(update);
        } catch (RuntimeException e) {
            Log.w(TAG, "Malformed notification from " + pkg + ", ignoring", e);
        } catch (Throwable t) {
            Log.w(TAG, "Unexpected error parsing notification from " + pkg, t);
        }
    }

    private void onOtherNotificationPosted(StatusBarNotification sbn) {
        try {
            if (CallNotifications.isDialer(sbn.getPackageName())) {
                calls.onPosted(sbn, FeatureHooks.getCallNotificationSink(), System.currentTimeMillis());
                return;
            }
            MessageMirror.shared().onNotification(sbn, getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    FeatureHooks.getBannerSink(), System.currentTimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "Error handling notification from " + sbn.getPackageName(), t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (CallNotifications.isDialer(pkg)) {
            try {
                calls.onRemoved(sbn, FeatureHooks.getCallNotificationSink());
            } catch (Throwable t) {
                Log.w(TAG, "Error handling call notification removal", t);
            }
            return;
        }

        NavSource source = sources.get(pkg);
        if (source != null) {
            try {
                if (source.isGuidanceRemoval(sbn)) {
                    Log.d(TAG, "Nav notification removed from " + pkg + "; ending guidance unless it is re-posted");
                    scheduleRemoval(pkg);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error handling notification removal for " + pkg, t);
            }
        }
    }

    private void scheduleRemoval(String pkg) {
        cancelPendingRemoval(pkg);
        Runnable end = () -> {
            synchronized (pendingRemovals) {
                pendingRemovals.remove(pkg);
            }
            try {
                Bridge.get(this).onNavEnded(pkg);
            } catch (Throwable t) {
                Log.w(TAG, "Error ending guidance for " + pkg, t);
            }
        };
        synchronized (pendingRemovals) {
            pendingRemovals.put(pkg, end);
        }
        handler.postDelayed(end, REMOVAL_DEBOUNCE_MS);
    }

    private void cancelPendingRemoval(String pkg) {
        Runnable pending;
        synchronized (pendingRemovals) {
            pending = pendingRemovals.remove(pkg);
        }
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
    }

    void captureArrowIfEnabled(NavUpdate navUpdate) {
        if (navUpdate == null) {
            return;
        }
        int[] arrowPixels = navUpdate.getArrowPixels();
        if (arrowPixels == null) {
            return;
        }
        if (!isCaptureEnabled()) {
            return;
        }
        try {
            File captureDir = new File(getFilesDir(), CAPTURE_DIR);
            if (!captureDir.exists() && !captureDir.mkdirs()) {
                Log.w(TAG, "Capture dir not creatable: " + captureDir);
                return;
            }
            File[] existing = captureDir.listFiles();
            if (existing != null && existing.length >= MAX_CAPTURES) {
                return;
            }
            String label = captureLabel(navUpdate);
            File file = new File(captureDir, label + "__" + ArrowHash.of(arrowPixels) + ".png");
            if (file.exists()) {
                return;
            }
            ArrowIcons.savePng(file, arrowPixels);
            Log.i(TAG, "Captured maneuver arrow: " + file.getName());
        } catch (Throwable t) {
            Log.w(TAG, "Maneuver arrow capture failed", t);
        }
    }

    static String captureLabel(NavUpdate navUpdate) {
        String labelText;
        if (navUpdate.getRoadText() != null && !navUpdate.getRoadText().trim().isEmpty()) {
            labelText = navUpdate.getRoadText();
        } else if (navUpdate.getDistanceText() != null && !navUpdate.getDistanceText().trim().isEmpty()) {
            labelText = navUpdate.getDistanceText();
        } else {
            labelText = "unlabeled";
        }
        String sanitized = labelText.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "unlabeled";
        }
        return sanitized.length() > 40 ? sanitized.substring(0, 40) : sanitized;
    }
}
