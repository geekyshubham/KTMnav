package app.ktmnav.backend.obd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import app.ktmnav.backend.AppLog;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the optional OBD-II session: preferences, transport choice, one automatic retry, listeners and the
 * optional speed/gear dash banner. Disabled by default; read-only.
 */
public final class ObdManager {

    private static final String TAG = "ObdMgr";

    public static final String PREF_ENABLED = "obd_enabled";
    public static final String PREF_ADDRESS = "obd_adapter_address";
    public static final String PREF_NAME = "obd_adapter_name";
    public static final String PREF_TRANSPORT = "obd_adapter_transport";
    public static final String PREF_DASH_BANNER = "obd_dash_banner";
    public static final String PREF_GEAR_PROFILE = "obd_gear_profile";
    public static final String PREF_GEAR_HISTOGRAM = "obd_gear_histogram";
    public static final String PREF_GEAR_LEARNED = "obd_gear_learned";
    public static final String GEAR_PROFILE_AUTO = "auto";
    public static final String GEAR_PROFILE_LEARNED = "learned";
    private static final int GEAR_SAVE_EVERY = 100;
    public static final String TRANSPORT_BLE = "ble";
    public static final String TRANSPORT_SPP = "spp";

    private static final long RETRY_DELAY_MS = 3000L;

    public interface Listener {
        void onObdSnapshot(ObdSnapshot snapshot);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private ObdSession session;
    private ObdBannerSink bannerSink;
    private volatile ObdSnapshot snapshot = ObdSnapshot.EMPTY;
    private int retriesLeft = 0;
    private final GearLearner gearLearner;
    private final Object gearLock = new Object();
    private long lastLearnedRpmAt = Long.MIN_VALUE;
    private int unsavedGearSamples = 0;
    private boolean shutdown = false;

    private final Runnable retryRunnable = this::retryNow;
    private final Runnable bannerTick = new Runnable() {
        @Override
        public void run() {
            pushBanner();
            mainHandler.postDelayed(this, ObdDashBanner.PERIOD_MS);
        }
    };

    public ObdManager(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.gearLearner = GearLearner.restore(prefs.getString(PREF_GEAR_HISTOGRAM, null));
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
            l.onObdSnapshot(snapshot);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized void setBannerSink(ObdBannerSink sink) {
        this.bannerSink = sink;
    }

    public ObdSnapshot getSnapshot() {
        return snapshot;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public String getAdapterAddress() {
        return prefs.getString(PREF_ADDRESS, null);
    }

    public GearEstimator.Profile getGearProfile() {
        String id = prefs.getString(PREF_GEAR_PROFILE, GEAR_PROFILE_AUTO);
        GearEstimator.Profile learned = getLearnedProfile();
        if ((GEAR_PROFILE_AUTO.equals(id) || GEAR_PROFILE_LEARNED.equals(id)) && learned != null) {
            return learned;
        }
        return GearEstimator.profileById(id);
    }

    public GearEstimator.Profile getLearnedProfile() {
        double[] ratios = GearLearner.parseRatios(prefs.getString(PREF_GEAR_LEARNED, null));
        if (ratios == null) {
            return null;
        }
        return new GearEstimator.Profile(GEAR_PROFILE_LEARNED, "Learned from your rides", ratios, false,
                "Learned from steady rpm and speed readings on this motorcycle");
    }

    public int getGearLearningSamples() {
        synchronized (gearLock) {
            return gearLearner.samples();
        }
    }

    public void resetGearLearning() {
        synchronized (gearLock) {
            gearLearner.reset();
            unsavedGearSamples = 0;
            lastLearnedRpmAt = Long.MIN_VALUE;
            prefs.edit().remove(PREF_GEAR_HISTOGRAM).remove(PREF_GEAR_LEARNED).apply();
        }
    }

    private void feedGearLearner(ObdSnapshot snap) {
        ObdSnapshot.Value rpm = snap.get(ObdPids.Pid.RPM);
        ObdSnapshot.Value speed = snap.get(ObdPids.Pid.SPEED);
        if (rpm == null || speed == null || Math.abs(rpm.timestampMs - speed.timestampMs) > 1000L) {
            return;
        }
        synchronized (gearLock) {
            if (rpm.timestampMs == lastLearnedRpmAt) {
                return;
            }
            lastLearnedRpmAt = rpm.timestampMs;
            if (!gearLearner.add(rpm.value, speed.value, rpm.timestampMs) || ++unsavedGearSamples < GEAR_SAVE_EVERY) {
                return;
            }
            unsavedGearSamples = 0;
            SharedPreferences.Editor edit = prefs.edit().putString(PREF_GEAR_HISTOGRAM, gearLearner.serialize());
            double[] ratios = gearLearner.learn(GearEstimator.KTM_390_DUKE_GEN3.gears());
            if (ratios != null) {
                edit.putString(PREF_GEAR_LEARNED, GearLearner.formatRatios(ratios));
            }
            edit.apply();
        }
    }

    public synchronized void selectAdapter(String address, String name, String transport) {
        prefs.edit()
                .putString(PREF_ADDRESS, address)
                .putString(PREF_NAME, name)
                .putString(PREF_TRANSPORT, TRANSPORT_SPP.equals(transport) ? TRANSPORT_SPP : TRANSPORT_BLE)
                .apply();
        stopSession();
        settingsChanged();
    }

    public synchronized void forgetAdapter() {
        stopSession();
        prefs.edit().remove(PREF_ADDRESS).remove(PREF_NAME).remove(PREF_TRANSPORT).apply();
        publish(ObdSnapshot.EMPTY.toBuilder().state(isEnabled() ? "No adapter selected" : "Disabled").build(System.currentTimeMillis()));
    }

    public synchronized void settingsChanged() {
        if (shutdown) {
            return;
        }
        String address = getAdapterAddress();
        if (!isEnabled()) {
            stopSession();
            publish(ObdSnapshot.EMPTY);
        } else if (address == null || address.isEmpty()) {
            stopSession();
            publish(ObdSnapshot.EMPTY.toBuilder().state("No adapter selected").build(System.currentTimeMillis()));
        } else if (session == null || !session.isRunning()) {
            retriesLeft = 1;
            startSession();
        }
        updateBannerTimer();
    }

    public synchronized void connect() {
        if (shutdown || !isEnabled() || getAdapterAddress() == null) {
            return;
        }
        stopSession();
        retriesLeft = 1;
        startSession();
        updateBannerTimer();
    }

    public synchronized void disconnect() {
        stopSession();
        publish(snapshot.toBuilder().state("Disconnected").connected(false).build(System.currentTimeMillis()));
    }

    public synchronized void requestDtcRead() {
        if (session != null) {
            session.requestDtcRead();
        }
    }

    public synchronized void shutdown() {
        shutdown = true;
        stopSession();
        mainHandler.removeCallbacksAndMessages(null);
        listeners.clear();
        bannerSink = null;
    }

    private void startSession() {
        mainHandler.removeCallbacks(retryRunnable);
        String address = getAdapterAddress();
        String name = prefs.getString(PREF_NAME, address);
        ObdLink link = TRANSPORT_SPP.equals(prefs.getString(PREF_TRANSPORT, TRANSPORT_BLE))
                ? new ObdSppTransport(context, address, name)
                : new ObdBleTransport(context, address, name);
        final ObdSession s = new ObdSession(link, new ObdSession.Options(), System::currentTimeMillis, new ObdSession.Listener() {
            @Override
            public void onSnapshot(ObdSession from, ObdSnapshot snap) {
                if (isCurrent(from)) {
                    publish(snap);
                }
            }

            @Override
            public void onEnded(ObdSession from, String reason, boolean reachedConnected) {
                mainHandler.post(() -> onSessionEnded(from, reason));
            }
        });
        session = s;
        AppLog.i(TAG, "Starting OBD session with " + name);
        s.start();
    }

    private synchronized boolean isCurrent(ObdSession s) {
        return !shutdown && s == session;
    }

    private synchronized void onSessionEnded(ObdSession from, String reason) {
        if (!isCurrent(from) || "Stopped".equals(reason)) {
            return;
        }
        session = null;
        if (retriesLeft > 0 && isEnabled()) {
            retriesLeft--;
            AppLog.i(TAG, "OBD session ended (" + reason + "); retrying once");
            publish(snapshot.toBuilder().state(reason + " - retrying").connected(false).build(System.currentTimeMillis()));
            mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
        } else {
            publish(snapshot.toBuilder().state(reason).connected(false).build(System.currentTimeMillis()));
        }
    }

    private synchronized void retryNow() {
        if (shutdown || !isEnabled() || session != null || getAdapterAddress() == null) {
            return;
        }
        startSession();
    }

    private void stopSession() {
        mainHandler.removeCallbacks(retryRunnable);
        ObdSession s = session;
        session = null;
        if (s != null) {
            s.stop();
        }
    }

    private void publish(ObdSnapshot snap) {
        synchronized (gearLock) {
            snapshot = snap;
            feedGearLearner(snap);
            mainHandler.post(() -> {
                if (shutdown) {
                    return;
                }
                for (Listener l : listeners) {
                    l.onObdSnapshot(snap);
                }
            });
        }
    }

    private void updateBannerTimer() {
        mainHandler.removeCallbacks(bannerTick);
        if (isEnabled() && prefs.getBoolean(PREF_DASH_BANNER, false)) {
            mainHandler.postDelayed(bannerTick, ObdDashBanner.PERIOD_MS);
        }
    }

    private void pushBanner() {
        ObdBannerSink sink;
        synchronized (this) {
            sink = bannerSink;
        }
        if (sink == null || !prefs.getBoolean(PREF_DASH_BANNER, false)) {
            return;
        }
        String text = ObdDashBanner.text(snapshot, System.currentTimeMillis(), getGearProfile());
        if (text != null) {
            sink.show(ObdDashBanner.OWNER, text, ObdBannerSink.PRIORITY_INFO, ObdDashBanner.HOLD_MS);
        }
    }
}
