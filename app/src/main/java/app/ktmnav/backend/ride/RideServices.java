package app.ktmnav.backend.ride;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import app.ktmnav.backend.AppLog;
import app.ktmnav.backend.NavUpdate;
import app.ktmnav.backend.TurnIcon;

/**
 * Entry point for the turn beeper, overspeed alert and ride recorder. All work runs on one
 * background thread; every public method is safe to call from any thread.
 */
public final class RideServices {
    private static final String TAG = "RideServices";
    public static final String STATUS_OFF = "Off";
    public static final String STATUS_PERMISSION = "Location permission needed";
    public static final String STATUS_NO_GPS = "GPS unavailable";
    public static final String STATUS_RECORDING = "Recording ride";
    public static final String STATUS_MONITORING = "Speed alert active";
    public static final String STATUS_WAITING = "Waiting for motorcycle";
    public static final String STATUS_READY = "Ready to record";
    public static final String STATUS_WAITING_GPS = "Waiting for GPS (open KTMnav)";
    public static final long DISCONNECT_GRACE_MS = 120_000L;
    public static final long GPS_STALE_MS = 30_000L;

    private HandlerThread thread;
    private Handler handler;
    private Context app;
    private SharedPreferences prefs;
    private RideStore store;
    private TurnBeeper beeper;
    private SpeedMonitor speed;
    private RouteRecorder recorder;
    private boolean bikeConnected;
    private boolean bikeRecent;
    private boolean manualActive;
    private volatile String status = STATUS_OFF;
    private volatile boolean permissionNeeded;
    private volatile boolean recording;
    private volatile boolean gpsActive;
    private volatile long gpsActiveSinceMs;
    private final Runnable graceEnded = () -> {
        bikeRecent = false;
        apply();
    };

    public static boolean hasLocationPermission(Context context) {
        return context != null && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static RideStore storeFor(Context context) {
        return new RideStore(context.getApplicationContext().getFilesDir());
    }

    public synchronized void start(Context context, RideBannerSink sink) {
        if (thread != null) return;
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(RidePrefs.PREFS, Context.MODE_PRIVATE);
        store = new RideStore(app.getFilesDir());
        thread = new HandlerThread("ktmnav-ride");
        thread.start();
        handler = new Handler(thread.getLooper());
        RideScheduler scheduler = new HandlerScheduler(handler);
        speed = new SpeedMonitor(app, thread.getLooper(), scheduler, sink);
        recorder = new RouteRecorder(app, handler, store);
        beeper = new TurnBeeper(scheduler, new AudioTrackBeepOutput(), this::currentSpeedKmh);
        handler.post(() -> {
            int repaired = store.repairAll();
            if (repaired > 0) AppLog.i(TAG, "Repaired " + repaired + " unfinished ride file(s)");
            apply();
        });
    }

    public synchronized void stop() {
        if (thread == null) return;
        TurnBeeper b = beeper;
        SpeedMonitor s = speed;
        RouteRecorder r = recorder;
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            b.release();
            s.stop();
            r.setRecording(false);
        });
        thread.quitSafely();
        thread = null;
        handler = null;
        recording = false;
        gpsActive = false;
        status = STATUS_OFF;
    }

    public void onBikeConnected() {
        post(() -> {
            handler.removeCallbacks(graceEnded);
            bikeConnected = true;
            bikeRecent = true;
            apply();
        });
    }

    public void onBikeDisconnected() {
        post(() -> {
            if (!bikeConnected) return;
            bikeConnected = false;
            handler.removeCallbacks(graceEnded);
            handler.postDelayed(graceEnded, DISCONNECT_GRACE_MS);
            apply();
        });
    }

    public void onGuidance(NavUpdate update) {
        if (update != null) onGuidance(update.getDistanceText(), update.getTurnIcon());
    }

    public void onGuidance(String distanceText, TurnIcon icon) {
        post(() -> beeper.onGuidance(distanceText, icon));
    }

    public void onGuidanceEnded() {
        post(() -> beeper.onGuidanceEnded());
    }

    public void settingsChanged() {
        post(this::apply);
    }

    public void startManualRecording() {
        post(() -> {
            manualActive = true;
            apply();
        });
    }

    public void stopManualRecording() {
        post(() -> {
            manualActive = false;
            apply();
        });
    }

    public void previewBeep() {
        post(() -> {
            apply();
            beeper.preview();
        });
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean needsLocationPermission() {
        return permissionNeeded;
    }

    /** Recording or alerting without a GPS fix for 30 s (e.g. backgrounded without location access) is reported honestly. */
    public String getStatus() {
        String s = status;
        if (gpsActive && (STATUS_RECORDING.equals(s) || STATUS_MONITORING.equals(s))
                && isGpsStale(SystemClock.elapsedRealtime(), gpsActiveSinceMs, lastFixElapsedMs())) {
            return STATUS_WAITING_GPS;
        }
        return s;
    }

    static boolean isGpsStale(long nowMs, long activeSinceMs, long lastFixMs) {
        return nowMs - Math.max(activeSinceMs, lastFixMs) > GPS_STALE_MS;
    }

    private long lastFixElapsedMs() {
        SpeedMonitor s = speed;
        RouteRecorder r = recorder;
        long fix = s != null ? s.lastFixElapsedMs() : 0L;
        return r != null ? Math.max(fix, r.lastFixElapsedMs()) : fix;
    }

    public synchronized RideStore getStore() {
        return store;
    }

    public Float currentSpeedKmh() {
        SpeedMonitor s = speed;
        RouteRecorder r = recorder;
        Float v = s != null ? s.speedKmh() : null;
        if (v == null && r != null) v = r.speedKmh();
        return v;
    }

    private synchronized void post(Runnable task) {
        if (handler != null) handler.post(task);
    }

    private void apply() {
        boolean perm = hasLocationPermission(app);
        beeper.configure(RidePrefs.beeperEnabled(prefs), RidePrefs.beeperVolume(prefs), RidePrefs.beeperSwap(prefs));
        boolean overspeedOn = RidePrefs.overspeedEnabled(prefs);
        speed.configure(overspeedOn, RidePrefs.overspeedLimit(prefs));
        boolean speedOk = false;
        if (overspeedOn && bikeConnected && perm) speedOk = speed.start();
        else speed.stop();
        String mode = RidePrefs.recordingMode(prefs);
        if (RidePrefs.MODE_OFF.equals(mode)) manualActive = false;
        recorder.setRecording(perm && RidePrefs.shouldRecord(mode, bikeRecent, manualActive));
        recording = recorder.isRecording();
        boolean wantsLocation = overspeedOn || !RidePrefs.MODE_OFF.equals(mode);
        permissionNeeded = wantsLocation && !perm;
        boolean active = recording || speedOk;
        if (active && !gpsActive) gpsActiveSinceMs = SystemClock.elapsedRealtime();
        gpsActive = active;
        status = computeStatus(mode, overspeedOn, speedOk);
    }

    private String computeStatus(String mode, boolean overspeedOn, boolean speedOk) {
        if (permissionNeeded) return STATUS_PERMISSION;
        if (recording) return recorder.getError() != null ? recorder.getError() : STATUS_RECORDING;
        if (overspeedOn && bikeConnected && !speedOk) return STATUS_NO_GPS;
        if (speedOk) return STATUS_MONITORING;
        if (overspeedOn || RidePrefs.MODE_WHILE_CONNECTED.equals(mode)) return STATUS_WAITING;
        if (RidePrefs.MODE_MANUAL.equals(mode)) return STATUS_READY;
        return STATUS_OFF;
    }
}
