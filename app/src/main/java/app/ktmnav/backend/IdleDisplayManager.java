package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages dashboard display when navigation is quiet or finished.
 * Ports original TurnIt IdleDisplayController behavior (fixed 4500ms delay, track dash text) while
 * retaining CLOCK and CUSTOM extensions. The ETA slot only renders bare HH:MM, so idle screens send an
 * empty ETA (visibility OFF) instead of the app name.
 */
public final class IdleDisplayManager implements NowPlayingSource.Listener {
    private static final String TAG = "KTMnav.IdleDisplay";
    private static final String PREFS_NAME = "ktmnav";

    public static final long IDLE_DRAW_DELAY_MS = 4500L;
    public static final String IDLE_ETA_LABEL = "";
    public static final String ETA_PLACEHOLDER = "--:--";
    public static final String REMAINING_PLACEHOLDER = "N/A";

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NowPlayingSource nowPlayingSource;

    private boolean navActive;
    private boolean showingIdle;
    private NowPlaying currentMedia;

    private final Runnable drawIdleRunnable = new Runnable() {
        @Override
        public void run() {
            drawIdleNow();
        }
    };

    public IdleDisplayManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.nowPlayingSource = new NowPlayingSource(appContext, this);
        // Do NOT call applySettings() here: Bridge constructor must finish assigning its singleton instance first.
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void onNavGuidance(NavUpdate update) {
        navActive = true;
        showingIdle = false;
        handler.removeCallbacks(drawIdleRunnable);

        Bridge bridge = Bridge.get(appContext);
        bridge.sendGuidanceToBike(
                update.getDistanceText(),
                update.getRoadText(),
                update.getEtaText() != null ? update.getEtaText() : ETA_PLACEHOLDER,
                update.getRemainingDistanceText() != null ? update.getRemainingDistanceText() : REMAINING_PLACEHOLDER,
                update.getTurnIcon()
        );
    }

    public synchronized void onNavEnded() {
        navActive = false;
        showingIdle = false;
        handler.removeCallbacks(drawIdleRunnable);

        Bridge bridge = Bridge.get(appContext);
        bridge.clearBikeGuidance();

        scheduleIdleDraw();
    }

    public synchronized void onConnectionStateChanged(ConnectionState state) {
        if (state != ConnectionState.CONNECTED) {
            showingIdle = false;
            handler.removeCallbacks(drawIdleRunnable);
            nowPlayingSource.stop();
        } else {
            applySettings();
        }
    }

    public synchronized void applySettings() {
        String mode = prefs().getString("idle_mode", "NONE");
        if ("MEDIA".equalsIgnoreCase(mode)) {
            nowPlayingSource.start();
        } else {
            nowPlayingSource.stop();
            currentMedia = null;
        }

        if ("NONE".equalsIgnoreCase(mode)) {
            handler.removeCallbacks(drawIdleRunnable);
            if (showingIdle) {
                showingIdle = false;
                // Switching to NONE clears any prior idle display without erasing an active real route
                if (!navActive && Bridge.get(appContext).isConnected()) {
                    Bridge.get(appContext).clearBikeGuidance();
                }
            }
            return;
        }

        if (!navActive && Bridge.get(appContext).isConnected()) {
            if (showingIdle) {
                drawIdleNow();
            } else {
                scheduleIdleDraw();
            }
        }
    }

    public synchronized String getCurrentMediaText() {
        return currentMedia != null ? currentMedia.toDashText() : null;
    }

    public synchronized NowPlaying getCurrentMedia() {
        return currentMedia;
    }

    public NowPlayingSource getNowPlayingSource() {
        return nowPlayingSource;
    }

    public synchronized boolean isNavActive() {
        return navActive;
    }

    public synchronized boolean isShowingIdle() {
        return showingIdle;
    }

    @Override
    public synchronized void onNowPlayingChanged(NowPlaying nowPlaying) {
        this.currentMedia = nowPlaying;
        Bridge.get(appContext).notifyChanged();

        if (navActive) {
            return;
        }

        String mode = prefs().getString("idle_mode", "NONE");
        if (!"MEDIA".equalsIgnoreCase(mode)) {
            return;
        }

        if (nowPlaying == null || nowPlaying.toDashText().isEmpty()) {
            handler.removeCallbacks(drawIdleRunnable);
            if (showingIdle) {
                showingIdle = false;
                Bridge.get(appContext).clearBikeGuidance();
            }
            return;
        }

        if (showingIdle) {
            Bridge.get(appContext).sendGuidanceToBike(null, nowPlaying.toDashText(), IDLE_ETA_LABEL, null, TurnIcon.UNDEFINED);
        } else {
            Bridge.get(appContext).clearBikeGuidance();
            scheduleIdleDraw();
        }
    }

    private void scheduleIdleDraw() {
        handler.removeCallbacks(drawIdleRunnable);
        String mode = prefs().getString("idle_mode", "NONE");
        if ("NONE".equalsIgnoreCase(mode)) {
            return;
        }

        if ("MEDIA".equalsIgnoreCase(mode)) {
            if (currentMedia == null || currentMedia.toDashText().isEmpty()) {
                return;
            }
            handler.postDelayed(drawIdleRunnable, IDLE_DRAW_DELAY_MS);
        } else {
            // Extensions: CLOCK and CUSTOM modes use idle_timeout preference
            long delay = prefs().getLong("idle_timeout", 30000L);
            handler.postDelayed(drawIdleRunnable, Math.max(1000L, delay));
        }
    }

    private synchronized void drawIdleNow() {
        if (navActive || !Bridge.get(appContext).isConnected()) {
            return;
        }

        String mode = prefs().getString("idle_mode", "NONE");
        Bridge bridge = Bridge.get(appContext);

        if ("CLOCK".equalsIgnoreCase(mode)) {
            String time = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            String date = new SimpleDateFormat("EEE, d MMM", Locale.ROOT).format(new Date());
            bridge.sendGuidanceToBike(null, time, IDLE_ETA_LABEL, date, TurnIcon.UNDEFINED);
            showingIdle = true;
            // Refresh clock every 10 seconds while idle
            handler.removeCallbacks(drawIdleRunnable);
            handler.postDelayed(drawIdleRunnable, 10000L);
        } else if ("MEDIA".equalsIgnoreCase(mode)) {
            if (currentMedia != null && !currentMedia.toDashText().isEmpty()) {
                bridge.sendGuidanceToBike(null, currentMedia.toDashText(), IDLE_ETA_LABEL, null, TurnIcon.UNDEFINED);
                showingIdle = true;
            } else {
                showingIdle = false;
                bridge.clearBikeGuidance();
            }
        } else if ("CUSTOM".equalsIgnoreCase(mode)) {
            String custom = prefs().getString("custom_text", "KTMnav");
            if (custom.trim().isEmpty()) {
                custom = "KTMnav";
            }
            bridge.sendGuidanceToBike(null, custom, IDLE_ETA_LABEL, null, TurnIcon.UNDEFINED);
            showingIdle = true;
        } else {
            showingIdle = false;
            bridge.clearBikeGuidance();
        }
    }
}
