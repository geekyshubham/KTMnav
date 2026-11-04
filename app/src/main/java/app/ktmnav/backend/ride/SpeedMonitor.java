package app.ktmnav.backend.ride;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Looper;
import app.ktmnav.backend.AppLog;

/**
 * GPS overspeed monitor; also the live speed source for the turn beeper.
 * Based on OpenDash SpeedMonitor (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class SpeedMonitor implements LocationListener, TurnBeeper.SpeedSource {
    public static final String BANNER_OWNER = "overspeed";
    private static final String TAG = "SpeedMonitor";
    private static final long INTERVAL_MS = 1000L;
    private static final int ALARM_TONE_MS = 400;

    private final Context context;
    private final Looper looper;
    private final RideScheduler scheduler;
    private final RideBannerSink sink;
    private final OverspeedLogic logic = new OverspeedLogic();
    private final Runnable staleFix = this::onStaleFix;
    private volatile Float speedKmh;
    private volatile boolean running;
    private volatile long lastFixElapsedMs;
    private boolean alertsEnabled;
    private int limitKmh = RidePrefs.DEFAULT_OVERSPEED_LIMIT;
    private ToneGenerator tone;

    public SpeedMonitor(Context context, Looper looper, RideScheduler scheduler, RideBannerSink sink) {
        this.context = context;
        this.looper = looper;
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public void configure(boolean alertsEnabled, int limitKmh) {
        this.alertsEnabled = alertsEnabled;
        this.limitKmh = limitKmh;
        if (!alertsEnabled) apply(logic.reset());
    }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) return true;
        if (!RideServices.hasLocationPermission(context)) return false;
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return false;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, this, looper);
            running = true;
            AppLog.i(TAG, "Speed monitor started (limit " + limitKmh + " km/h)");
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "GPS updates unavailable: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (RuntimeException ignored) {
            }
        }
        scheduler.cancel(staleFix);
        apply(logic.reset());
        speedKmh = null;
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAlerting() {
        return logic.isAlerting();
    }

    public long lastFixElapsedMs() {
        return lastFixElapsedMs;
    }

    @Override
    public Float speedKmh() {
        return running ? speedKmh : null;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!running) return;
        lastFixElapsedMs = android.os.SystemClock.elapsedRealtime();
        if (!location.hasSpeed()) return;
        float kmh = location.getSpeed() * 3.6f;
        speedKmh = kmh;
        scheduler.cancel(staleFix);
        scheduler.postDelayed(staleFix, OverspeedLogic.STALE_FIX_MS);
        if (alertsEnabled) apply(logic.onSpeed(kmh, limitKmh, scheduler.now()));
    }

    @Override
    public void onProviderDisabled(String provider) {
        onStaleFix();
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    private void onStaleFix() {
        speedKmh = null;
        apply(logic.reset());
    }

    private void apply(OverspeedLogic.Action action) {
        if (action.clearBanner && sink != null) sink.clear(BANNER_OWNER);
        if (action.banner != null && sink != null) {
            sink.show(BANNER_OWNER, action.banner, RideBannerSink.PRIORITY_ALERT, OverspeedLogic.BANNER_HOLD_MS);
        }
        if (action.beep) {
            AppLog.i(TAG, action.banner != null ? action.banner : "Overspeed");
            alarmBeep();
        }
    }

    private void alarmBeep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALARM_TONE_MS);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Alarm tone unavailable: " + e.getMessage());
        }
    }
}
