package app.ktmnav.backend.ride;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import app.ktmnav.backend.AppLog;
import java.io.File;
import java.io.IOException;

/**
 * GPS ride recorder writing GPX 1.1 into app-private storage.
 * Based on OpenDash RouteRecorder (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class RouteRecorder implements LocationListener, TurnBeeper.SpeedSource {
    private static final String TAG = "RouteRecorder";
    public static final long FAST_INTERVAL_MS = 1000L;
    public static final float FAST_MIN_DISTANCE_M = 2f;
    public static final long SLOW_INTERVAL_MS = 5000L;
    public static final float SLOW_MIN_DISTANCE_M = 10f;
    public static final float MAX_ACCURACY_M = 50f;
    private static final long SPEED_FRESH_MS = 10_000L;

    private final Context context;
    private final Handler handler;
    private final RideStore store;
    private BroadcastReceiver powerReceiver;
    private GpxWriter writer;
    private boolean charging;
    private volatile Float lastSpeedKmh;
    private volatile long lastFixElapsedMs;
    private volatile String error;

    public RouteRecorder(Context context, Handler handler, RideStore store) {
        this.context = context;
        this.handler = handler;
        this.store = store;
    }

    public boolean isRecording() {
        return writer != null;
    }

    public File getCurrentFile() {
        GpxWriter w = writer;
        return w != null ? w.getFile() : null;
    }

    public String getError() {
        return error;
    }

    public long lastFixElapsedMs() {
        return lastFixElapsedMs;
    }

    @Override
    public Float speedKmh() {
        if (writer == null || android.os.SystemClock.elapsedRealtime() - lastFixElapsedMs > SPEED_FRESH_MS) return null;
        return lastSpeedKmh;
    }

    public void setRecording(boolean record) {
        if (record && writer == null) begin();
        else if (!record && writer != null) end();
    }

    private void begin() {
        if (!RideServices.hasLocationPermission(context)) {
            error = RideServices.STATUS_PERMISSION;
            return;
        }
        try {
            File f = store.newRideFile(System.currentTimeMillis());
            writer = GpxWriter.create(f, f.getName().replace(".gpx", ""));
            store.setActive(f);
            error = null;
        } catch (IOException e) {
            error = "Cannot create ride file";
            AppLog.w(TAG, error + ": " + e.getMessage());
            writer = null;
            return;
        }
        registerPower();
        requestUpdates();
        AppLog.i(TAG, "Recording started: " + writer.getFile().getName() + (charging ? " (1 s)" : " (5 s)"));
    }

    private void end() {
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (RuntimeException ignored) {
            }
        }
        unregisterPower();
        GpxWriter w = writer;
        writer = null;
        store.setActive(null);
        lastSpeedKmh = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException e) {
            AppLog.w(TAG, "Closing ride failed: " + e.getMessage());
        }
        if (w.getPointCount() == 0 && w.getFile().delete()) {
            AppLog.i(TAG, "Recording stopped with no points; file removed");
        } else {
            AppLog.i(TAG, "Recording stopped: " + w.getFile().getName() + ", " + w.getPointCount() + " points");
        }
    }

    @SuppressLint("MissingPermission")
    private void requestUpdates() {
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) {
            error = RideServices.STATUS_NO_GPS;
            return;
        }
        charging = isCharging();
        try {
            lm.removeUpdates(this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    charging ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS,
                    charging ? FAST_MIN_DISTANCE_M : SLOW_MIN_DISTANCE_M, this, handler.getLooper());
        } catch (RuntimeException e) {
            error = RideServices.STATUS_NO_GPS;
            AppLog.w(TAG, "GPS updates unavailable: " + e.getMessage());
        }
    }

    private boolean isCharging() {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }

    private void registerPower() {
        if (powerReceiver != null) return;
        powerReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (writer != null && charging != isCharging()) requestUpdates();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(powerReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(powerReceiver, filter, null, handler);
            }
        } catch (RuntimeException e) {
            powerReceiver = null;
        }
    }

    private void unregisterPower() {
        if (powerReceiver == null) return;
        try {
            context.unregisterReceiver(powerReceiver);
        } catch (RuntimeException ignored) {
        }
        powerReceiver = null;
    }

    @Override
    public void onLocationChanged(Location location) {
        GpxWriter w = writer;
        if (w == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_M) return;
        float speed = location.hasSpeed() ? location.getSpeed() * 3.6f : Float.NaN;
        lastSpeedKmh = Float.isNaN(speed) ? null : speed;
        lastFixElapsedMs = android.os.SystemClock.elapsedRealtime();
        TrackPoint p = new TrackPoint(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN, location.getTime(), speed);
        try {
            w.append(p);
        } catch (IOException e) {
            error = "Ride file write failed";
            AppLog.w(TAG, error + ": " + e.getMessage());
            end();
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        lastSpeedKmh = null;
    }
}
