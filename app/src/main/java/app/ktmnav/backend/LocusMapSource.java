package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Poller and provider integration for Locus Map navigation data.
 */
public final class LocusMapSource {
    public static final String PACKAGE_NAME = "menion.android.locus";
    public static final String PACKAGE_NAME_PRO = "menion.android.locus.pro";

    static final long POLL_ACTIVE_MS = 1000;
    static final long POLL_IDLE_MS = 3000;
    private static final String TAG = "KTMnav.LocusMap";

    private final Context appContext;
    private final Handler pollHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean polling;
    private boolean guiding;
    private NavUpdate lastSent;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pollTick();
        }
    };

    public LocusMapSource(Context context) {
        this.appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("KTMnav.LocusPoll");
        thread.start();
        this.pollHandler = new Handler(thread.getLooper());
    }

    public synchronized void start() {
        SharedPreferences prefs = appContext.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled_" + PACKAGE_NAME, true)) {
            Log.d(TAG, "Locus Map integration disabled in settings");
            stop();
            return;
        }

        if (polling) return;
        Log.d(TAG, "Starting Locus polling");
        polling = true;
        pollHandler.post(tick);
    }

    public synchronized void stop() {
        if (!polling) return;
        Log.d(TAG, "Stopping Locus polling");
        polling = false;
        pollHandler.removeCallbacks(tick);
        pollHandler.post(() -> {
            if (guiding) {
                guiding = false;
                lastSent = null;
                mainHandler.post(() -> Bridge.get(appContext).onNavEnded(PACKAGE_NAME));
            }
        });
    }

    private void pollTick() {
        if (!polling) return;

        SharedPreferences prefs = appContext.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled_" + PACKAGE_NAME, true)) {
            Log.d(TAG, "Locus Map disabled in settings - stopping polling");
            stop();
            return;
        }

        NavUpdate update = null;
        try {
            update = fetchUpdate(System.currentTimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "Locus poll tick failed", t);
        }

        boolean wasGuiding = guiding;
        if (update != null) {
            guiding = true;
            if (!update.equals(lastSent)) {
                lastSent = update;
                final NavUpdate finalUpdate = update;
                mainHandler.post(() -> Bridge.get(appContext).onNavGuidance(PACKAGE_NAME, finalUpdate));
            }
        } else if (wasGuiding) {
            guiding = false;
            lastSent = null;
            Log.d(TAG, "Locus navigation ended");
            mainHandler.post(() -> Bridge.get(appContext).onNavEnded(PACKAGE_NAME));
        }

        if (polling) {
            pollHandler.postDelayed(tick, guiding ? POLL_ACTIVE_MS : POLL_IDLE_MS);
        }
    }

    private NavUpdate fetchUpdate(long now) {
        // Try reflection for locus.api.android if present
        try {
            Class<?> locusUtilsClass = Class.forName("locus.api.android.utils.LocusUtils");
            Object locusUtilsInst = locusUtilsClass.getField("INSTANCE").get(null);
            Class<?> versionCodeClass = Class.forName("locus.api.android.objects.VersionCode");
            Object update13 = versionCodeClass.getField("UPDATE_13").get(null);

            Method getActiveVersion = locusUtilsClass.getMethod("getActiveVersion", Context.class, versionCodeClass);
            Object activeVersion = getActiveVersion.invoke(locusUtilsInst, appContext, update13);
            if (activeVersion == null) {
                return null;
            }

            Class<?> actionBasicsClass = Class.forName("locus.api.android.ActionBasics");
            Object actionBasicsInst = actionBasicsClass.getField("INSTANCE").get(null);
            Class<?> locusVersionClass = Class.forName("locus.api.android.objects.LocusVersion");
            Method getUpdateContainer = actionBasicsClass.getMethod("getUpdateContainer", Context.class, locusVersionClass);
            Object container = getUpdateContainer.invoke(actionBasicsInst, appContext, activeVersion);

            if (container != null) {
                return parseReflectedContainer(container, now);
            }
        } catch (ClassNotFoundException ignored) {
            // locus-api library not packaged on classpath
        } catch (Throwable t) {
            Log.d(TAG, "Reflection Locus fetch: " + t.getMessage());
        }

        return null;
    }

    private NavUpdate parseReflectedContainer(Object container, long now) {
        try {
            Class<?> c = container.getClass();
            int guideType = (int) c.getMethod("getGuideType").invoke(container);
            if (guideType != 3) { // 3 = GUIDE_TYPE_TRACK_NAVIGATION
                return null;
            }

            Object actionObj = c.getMethod("getGuideNavPoint1Action").invoke(container);
            PointRteAction action = null;
            if (actionObj != null) {
                int actionId = (int) actionObj.getClass().getMethod("getId").invoke(actionObj);
                action = PointRteAction.getActionById(actionId);
            }

            TurnIcon icon = LocusActions.icon(action);
            double dist = (double) c.getMethod("getGuideNavPoint1Dist").invoke(container);
            String distText = LocusActions.distanceText(dist);

            if (icon == null && distText == null) {
                return null;
            }

            String name = (String) c.getMethod("getGuideNavPoint1Name").invoke(container);
            long timeToFinish = (long) c.getMethod("getGuideTimeToFinish").invoke(container);
            double distToFinish = (double) c.getMethod("getGuideDistToFinish").invoke(container);

            return new NavUpdate(
                    distText,
                    NavTexts.blankToNull(name),
                    LocusActions.etaText(now, timeToFinish),
                    LocusActions.distanceText(distToFinish),
                    icon,
                    null
            ).withRoundaboutExit(LocusActions.roundaboutExit(action));
        } catch (Throwable t) {
            Log.w(TAG, "Failed parsing reflected container", t);
            return null;
        }
    }
}
