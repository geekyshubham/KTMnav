package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Watches the playing media session and shows "Title - Artist" once per track change (pref "track_banner").
 */
public final class TrackBannerMonitor implements NowPlayingSource.Listener {
    private static final String TAG = "TrackBanner";
    private static final String PREFS_NAME = "ktmnav";

    public static final String PREF_TRACK_BANNER = "track_banner";
    public static final long SETTLE_MS = 1200L;

    private static TrackBannerMonitor instance;

    private final SharedPreferences prefs;
    private final NowPlayingSource source;
    private final TrackChangeBanner banner = new TrackChangeBanner();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NowPlaying pending;
    private boolean running;

    private final Runnable emit = this::emitPending;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (PREF_TRACK_BANNER.equals(key)) {
            applySettings();
        }
    };

    private TrackBannerMonitor(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.source = new NowPlayingSource(app, this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    public static synchronized TrackBannerMonitor get(Context context) {
        if (instance == null) {
            instance = new TrackBannerMonitor(context);
        }
        return instance;
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean(PREF_TRACK_BANNER, true);
    }

    public synchronized void start() {
        running = true;
        applySettings();
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacks(emit);
        pending = null;
        source.stop();
    }

    public synchronized void applySettings() {
        if (running && isEnabled(prefs)) {
            source.start();
        } else {
            handler.removeCallbacks(emit);
            pending = null;
            source.stop();
        }
    }

    @Override
    public synchronized void onNowPlayingChanged(NowPlaying nowPlaying) {
        pending = nowPlaying;
        handler.removeCallbacks(emit);
        if (nowPlaying != null) {
            handler.postDelayed(emit, SETTLE_MS);
        }
    }

    private synchronized void emitPending() {
        NowPlaying current = pending;
        pending = null;
        try {
            if (banner.onTrack(current, running && isEnabled(prefs), FeatureHooks.getBannerSink())) {
                AppLog.d(TAG, "Track banner shown (" + current.toDashText().length() + " chars)");
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "Track banner failed", t);
        }
    }
}
