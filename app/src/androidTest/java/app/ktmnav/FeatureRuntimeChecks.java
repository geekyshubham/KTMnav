package app.ktmnav;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import app.ktmnav.backend.BannerSink;
import app.ktmnav.backend.Bridge;
import app.ktmnav.backend.CallNotificationSink;
import app.ktmnav.backend.FeatureHooks;
import app.ktmnav.backend.MessageMirror;
import app.ktmnav.backend.ride.RideBannerSink;
import app.ktmnav.backend.ride.RidePrefs;
import app.ktmnav.backend.ride.RideServices;
import app.ktmnav.backend.ride.RideStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Exercises messages, calls, overspeed and ride recording against real emulator system apps and GPS. */
final class FeatureRuntimeChecks {
    private final Instrumentation runner;
    private final Consumer<String> stage;
    private final List<String> results = new ArrayList<>();

    FeatureRuntimeChecks(Instrumentation runner, Consumer<String> stage) {
        this.runner = runner;
        this.stage = stage;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + "\nPassed so far: " + results);
        results.add(message);
    }

    private void waitFor(BooleanSupplier condition, long timeoutMs, String failure) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError(failure + "\nPassed so far: " + results);
    }

    private static final class Recorder implements BannerSink, RideBannerSink {
        final List<String> shown = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean show(String owner, String text, int priority, long holdMs) {
            shown.add(owner + "|" + priority + "|" + text);
            return true;
        }

        @Override
        public void clear(String owner) {
            shown.add(owner + "|clear");
        }

        boolean has(String owner, String fragment) {
            synchronized (shown) {
                for (String s : shown) if (s.startsWith(owner + "|") && s.contains(fragment)) return true;
            }
            return false;
        }
    }

    String run() throws Exception {
        Context context = runner.getTargetContext();
        Bridge bridge = Bridge.get(context);
        SharedPreferences prefs = bridge.getPrefs();
        stage.accept("rebind_listener");
        waitFor(app.ktmnav.backend.NavListener::isConnected, 30000, "Notification listener did not bind after instrumentation restart");
        check(true, "KTMnav notification listener is connected");
        messages(prefs);
        calls(context, bridge, prefs);
        ride(context, prefs);
        return "PASS: feature runtime checks\n" + String.join("\n", results)
                + "\nNo physical motorcycle connected; banners were captured before the dash transport.\n";
    }

    private void messages(SharedPreferences prefs) throws Exception {
        BannerSink original = FeatureHooks.getBannerSink();
        Recorder recorder = new Recorder();
        try {
            prefs.edit().putBoolean(MessageMirror.PREF_ENABLED, true)
                    .putStringSet(MessageMirror.PREF_APPS, new HashSet<>(Collections.singletonList("com.google.android.apps.messaging")))
                    .putBoolean(MessageMirror.PREF_HIDE_CONTENT, false).remove(MessageMirror.PREF_QUIET_UNTIL).commit();
            FeatureHooks.setBannerSink(recorder);
            stage.accept("sms");
            waitFor(() -> recorder.has(MessageMirror.OWNER, "Hello rider"), 45000,
                    "Real SMS notification from Google Messages did not reach the dash banner path: " + recorder.shown);
            check(true, "Real SMS from the emulator's Google Messages is mirrored as a MESSAGE banner");
            prefs.edit().putBoolean(MessageMirror.PREF_HIDE_CONTENT, true).commit();
            int before = recorder.shown.size();
            stage.accept("sms_hidden");
            waitFor(() -> recorder.shown.size() > before, 45000, "Second SMS was not mirrored");
            check(!recorder.shown.get(recorder.shown.size() - 1).contains("Second secret"), "Hide-content setting keeps message text off the dash");
            prefs.edit().putBoolean(MessageMirror.PREF_ENABLED, false).commit();
            int afterDisable = recorder.shown.size();
            stage.accept("sms_disabled");
            Thread.sleep(6000);
            check(recorder.shown.size() == afterDisable, "Disabled message mirroring ignores new SMS");
        } finally {
            FeatureHooks.setBannerSink(original);
            prefs.edit().putBoolean(MessageMirror.PREF_ENABLED, false).remove(MessageMirror.PREF_APPS)
                    .remove(MessageMirror.PREF_HIDE_CONTENT).commit();
        }
    }

    private void press(Bridge bridge, int mask) {
        runner.runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            bridge.getHandlebarRouter().onMask(mask, t);
            bridge.getHandlebarRouter().onMask(0, t + 60);
        });
    }

    private void calls(Context context, Bridge bridge, SharedPreferences prefs) throws Exception {
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        CallNotificationSink original = FeatureHooks.getCallNotificationSink();
        List<String> callers = Collections.synchronizedList(new ArrayList<>());
        try {
            prefs.edit().putBoolean("handlebar_mappings_enabled", true).putString("handlebar_mode", "CUSTOM").commit();
            check(bridge.getCallStateMonitor().start(), "Call state monitor starts with phone permission granted");
            FeatureHooks.setCallNotificationSink(new CallNotificationSink() {
                @Override
                public void onIncomingCallNotification(String callerName) {
                    callers.add(String.valueOf(callerName));
                    original.onIncomingCallNotification(callerName);
                }

                @Override
                public void onCallNotificationRemoved() {
                    original.onCallNotificationRemoved();
                }
            });
            stage.accept("call");
            waitFor(() -> bridge.getCallControl().isRinging(), 30000, "Emulator incoming call was not seen as ringing");
            check(true, "Incoming emulator call is detected as ringing");
            waitFor(() -> !callers.isEmpty(), 15000, "Dialer's incoming-call notification was not detected");
            check(true, "The real dialer's incoming-call notification is detected for the caller banner");
            press(bridge, 0x1);
            waitFor(() -> telephony.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK, 15000,
                    "SET while ringing did not answer the call; last result: " + bridge.getLastHandlebarActionResult());
            check(true, "SET while ringing answers the real call (" + bridge.getLastHandlebarActionResult() + ")");
            stage.accept("call_end");
            waitFor(() -> telephony.getCallState() == TelephonyManager.CALL_STATE_IDLE, 15000, "Call did not end");
            waitFor(() -> !bridge.getCallControl().isRinging(), 5000, "Call control stayed ringing");
            stage.accept("call");
            waitFor(() -> bridge.getCallControl().isRinging(), 30000, "Second incoming call was not seen as ringing");
            press(bridge, 0x2);
            waitFor(() -> String.valueOf(bridge.getLastHandlebarActionResult()).toLowerCase(java.util.Locale.ROOT).contains("silenc"), 10000,
                    "BACK while ringing did not silence; last result: " + bridge.getLastHandlebarActionResult());
            check(telephony.getCallState() == TelephonyManager.CALL_STATE_RINGING, "BACK while ringing silences without answering (" + bridge.getLastHandlebarActionResult() + ")");
            stage.accept("call_end");
            waitFor(() -> telephony.getCallState() == TelephonyManager.CALL_STATE_IDLE, 15000, "Second call did not end");
        } finally {
            FeatureHooks.setCallNotificationSink(original);
            prefs.edit().putBoolean("handlebar_mappings_enabled", false).remove("handlebar_mode").commit();
        }
    }

    private void feedGps(Context context, int seconds) {
        android.location.LocationManager lm = context.getSystemService(android.location.LocationManager.class);
        String provider = android.location.LocationManager.GPS_PROVIDER;
        try {
            lm.addTestProvider(provider, new android.location.provider.ProviderProperties.Builder()
                    .setHasSpeedSupport(true).setHasBearingSupport(true).setHasAltitudeSupport(true)
                    .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_HIGH).build());
            lm.setTestProviderEnabled(provider, true);
            double lat = 12.9716, lon = 77.5946;
            float speedMs = 100f / 3.6f;
            for (int i = 0; i < seconds; i++) {
                lat += speedMs / 111320.0;
                android.location.Location l = new android.location.Location(provider);
                l.setLatitude(lat);
                l.setLongitude(lon);
                l.setAltitude(920);
                l.setSpeed(speedMs);
                l.setAccuracy(5f);
                l.setTime(System.currentTimeMillis());
                l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                lm.setTestProviderLocation(provider, l);
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {
        } finally {
            try {
                lm.removeTestProvider(provider);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void ride(Context context, SharedPreferences prefs) throws Exception {
        Recorder recorder = new Recorder();
        RideServices services = new RideServices();
        RideStore store = null;
        int existing = RideServices.storeFor(context).list().size();
        android.app.Activity activity = runner.startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            prefs.edit().putBoolean(RidePrefs.OVERSPEED_ENABLED, true).putInt(RidePrefs.OVERSPEED_LIMIT_KMH, 80)
                    .putString(RidePrefs.RIDE_RECORDING, RidePrefs.MODE_MANUAL).commit();
            check(RideServices.hasLocationPermission(context), "Fine location permission is granted for ride tools");
            services.start(context, recorder);
            services.onBikeConnected();
            services.startManualRecording();
            stage.accept("gps");
            Thread feeder = new Thread(() -> feedGps(context, 35));
            feeder.start();
            waitFor(() -> recorder.has("overspeed", "OVERSPEED 1"), 40000, "Overspeed banner not produced from GPS speed: " + recorder.shown);
            check(true, "GPS speed above the limit produces the OVERSPEED alert banner");
            waitFor(services::isRecording, 5000, "Manual ride recording did not start: " + services.getStatus());
            Thread.sleep(8000);
            services.stopManualRecording();
            feeder.join(40000);
            store = services.getStore();
            RideStore s = store;
            waitFor(() -> !services.isRecording() && s.list().size() > existing, 10000, "Recorded ride file not listed");
            RideStore.Ride ride = s.list().get(0);
            check(ride.stats.points >= 3 && ride.stats.distanceM > 50, "Manual recording writes a GPX ride with " + ride.stats.points + " points and "
                    + Math.round(ride.stats.distanceM) + " m");
            check(ride.stats.maxKmh > 80, "Ride stats record the GPS speed (max " + Math.round(ride.stats.maxKmh) + " km/h)");
            check(s.delete(ride.file), "Recorded test ride can be deleted");
        } finally {
            runner.runOnMainSync(activity::finish);
            services.stop();
            prefs.edit().remove(RidePrefs.OVERSPEED_ENABLED).remove(RidePrefs.OVERSPEED_LIMIT_KMH).remove(RidePrefs.RIDE_RECORDING).commit();
        }
    }
}
