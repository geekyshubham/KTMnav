package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Observes CoMaps turn-by-turn navigation ContentProvider.
 * Free integration without paywalls.
 */
public final class CoMapsNavSource {
    public static final String PACKAGE_NAME = "app.comaps.google";
    static final String[] PACKAGES = {
            PACKAGE_NAME,
            "app.comaps.fdroid",
            "app.comaps",
            "app.comaps.huawei"
    };

    static final long SILENCE_TIMEOUT_MS = 6000;
    private static final String TAG = "KTMnav.CoMaps";

    private static final String COL_SESSION_STATE = "session_state";
    private static final String COL_CAR_DIRECTION = "car_direction";
    private static final String COL_EXIT_NUM = "exit_num";
    private static final String COL_DIST_TO_TURN = "dist_to_turn";
    private static final String COL_DIST_TO_TARGET = "dist_to_target";
    private static final String COL_TOTAL_TIME_SECONDS = "total_time_seconds";
    private static final String COL_CURRENT_STREET = "current_street";
    private static final String COL_NEXT_STREET = "next_street";

    private static final String[] PROJECTION = {
            COL_SESSION_STATE,
            COL_CAR_DIRECTION,
            COL_EXIT_NUM,
            COL_DIST_TO_TURN,
            COL_DIST_TO_TARGET,
            COL_TOTAL_TIME_SECONDS,
            COL_CURRENT_STREET,
            COL_NEXT_STREET
    };

    private final Context appContext;
    private final Handler obsHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver observer;

    private volatile boolean active;
    private Uri liveUri;
    private boolean guiding;
    private NavUpdate lastSent;

    private final Runnable silenceTimeout = new Runnable() {
        @Override
        public void run() {
            onSilence();
        }
    };

    private final Runnable permRetryRunnable = new Runnable() {
        @Override
        public void run() {
            registerAndPrime();
        }
    };

    public CoMapsNavSource(Context context) {
        this.appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("KTMnav.CoMapsObs");
        thread.start();
        this.obsHandler = new Handler(thread.getLooper());
        this.observer = new ContentObserver(obsHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onTick();
            }
        };
    }

    public static String installedPackage(Context context) {
        PackageManager pm = context.getPackageManager();
        String first = null;
        for (String pkg : PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                if (pm.resolveContentProvider(pkg + ".provider.navigation", 0) != null) {
                    return pkg;
                }
                if (first == null) {
                    first = pkg;
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return first;
    }

    public static String permissionName(String pkg) {
        return pkg + ".permission.READ_NAVIGATION_DATA";
    }

    public static boolean isPermissionGranted(Context context) {
        String pkg = installedPackage(context);
        return pkg != null && context.checkSelfPermission(permissionName(pkg)) == PackageManager.PERMISSION_GRANTED;
    }

    public synchronized void start() {
        active = true;
        obsHandler.removeCallbacks(permRetryRunnable);
        obsHandler.post(this::registerAndPrime);
    }

    public synchronized void stop() {
        active = false;
        obsHandler.removeCallbacks(permRetryRunnable);
        obsHandler.post(() -> {
            unregister();
            obsHandler.removeCallbacks(silenceTimeout);
            guiding = false;
            lastSent = null;
        });
    }

    private void registerAndPrime() {
        if (!active) return;
        String pkg = installedPackage(appContext);
        if (pkg == null) {
            pkg = PACKAGE_NAME;
        }

        SharedPreferences prefs = appContext.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled_" + pkg, true)) {
            Log.d(TAG, "CoMaps integration disabled in settings for " + pkg);
            unregister();
            return;
        }

        if (!isPermissionGranted(appContext)) {
            Log.d(TAG, "CoMaps not observable (pkg=" + pkg + ", permission missing); scheduling retry");
            unregister();
            if (active) {
                obsHandler.removeCallbacks(permRetryRunnable);
                obsHandler.postDelayed(permRetryRunnable, 5000L);
            }
            return;
        }

        obsHandler.removeCallbacks(permRetryRunnable);
        Uri uri = Uri.parse("content://" + pkg + ".provider.navigation/live");
        if (uri.equals(liveUri)) {
            onTick();
            return;
        }

        unregister();
        liveUri = uri;
        try {
            appContext.getContentResolver().registerContentObserver(liveUri, false, observer);
            Log.d(TAG, "Observing CoMaps at " + liveUri);
            onTick();
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot observe CoMaps provider", e);
            liveUri = null;
            if (active) {
                obsHandler.removeCallbacks(permRetryRunnable);
                obsHandler.postDelayed(permRetryRunnable, 5000L);
            }
        }
    }

    private void unregister() {
        if (liveUri != null) {
            try {
                appContext.getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {}
            liveUri = null;
            Log.d(TAG, "Stopped observing CoMaps");
        }
    }

    private void onTick() {
        if (!active || liveUri == null) {
            return;
        }
        obsHandler.removeCallbacks(silenceTimeout);
        obsHandler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS);

        Row row = queryRow(System.currentTimeMillis());
        if (row == null) {
            return;
        }

        if (row.ended) {
            clearGuidance();
            return;
        }

        if (row.update == null) {
            return;
        }

        guiding = true;
        if (row.update.equals(lastSent)) {
            return;
        }
        lastSent = row.update;

        final NavUpdate update = row.update;
        final String pkg = installedPackage(appContext);
        mainHandler.post(() -> Bridge.get(appContext).onNavGuidance(pkg != null ? pkg : PACKAGE_NAME, update));
    }

    private void onSilence() {
        if (guiding) {
            Log.d(TAG, "CoMaps went silent - clearing guidance");
            clearGuidance();
        }
    }

    private void clearGuidance() {
        obsHandler.removeCallbacks(silenceTimeout);
        if (guiding) {
            guiding = false;
            lastSent = null;
            final String pkg = installedPackage(appContext);
            mainHandler.post(() -> Bridge.get(appContext).onNavEnded(pkg != null ? pkg : PACKAGE_NAME));
        }
    }

    private Row queryRow(long now) {
        if (liveUri == null) return null;
        try (Cursor cursor = appContext.getContentResolver().query(liveUri, PROJECTION, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return parseRow(
                        getString(cursor, COL_SESSION_STATE),
                        getString(cursor, COL_CAR_DIRECTION),
                        getInt(cursor, COL_EXIT_NUM),
                        getString(cursor, COL_DIST_TO_TURN),
                        getString(cursor, COL_DIST_TO_TARGET),
                        getInt(cursor, COL_TOTAL_TIME_SECONDS),
                        getString(cursor, COL_CURRENT_STREET),
                        getString(cursor, COL_NEXT_STREET),
                        now
                );
            }
        } catch (Exception e) {
            Log.w(TAG, "CoMaps query failed", e);
        }
        return null;
    }

    private static String getString(Cursor cursor, String col) {
        int idx = cursor.getColumnIndex(col);
        return idx >= 0 ? cursor.getString(idx) : null;
    }

    private static int getInt(Cursor cursor, String col) {
        int idx = cursor.getColumnIndex(col);
        return idx >= 0 ? cursor.getInt(idx) : 0;
    }

    static Row parseRow(String sessionState, String carDirection, int exitNum,
                        String distToTurn, String distToTarget, int totalTimeSec,
                        String currentStreet, String nextStreet, long now) {
        if (CoMapsDirections.isTerminalState(sessionState)) {
            return Row.ended(carDirection);
        }

        TurnIcon icon = CoMapsDirections.icon(carDirection, exitNum);
        String cleanDist = NavTexts.blankToNull(distToTurn);
        if (icon == null && cleanDist == null) {
            return Row.none(carDirection);
        }

        String street = NavTexts.blankToNull(nextStreet);
        if (street == null) {
            street = NavTexts.blankToNull(currentStreet);
        }

        String eta = LocusActions.etaText(now, (long) totalTimeSec * 1000L);
        String targetDist = NavTexts.blankToNull(distToTarget);

        NavUpdate update = new NavUpdate(cleanDist, street, eta, targetDist, icon, null)
                .withRoundaboutExit(CoMapsDirections.roundaboutExit(carDirection, exitNum));

        return Row.guidance(carDirection, update);
    }

    static final class Row {
        final String carDirection;
        final boolean ended;
        final NavUpdate update;

        private Row(boolean ended, NavUpdate update, String carDirection) {
            this.ended = ended;
            this.update = update;
            this.carDirection = carDirection;
        }

        static Row ended(String dir) {
            return new Row(true, null, dir);
        }

        static Row none(String dir) {
            return new Row(false, null, dir);
        }

        static Row guidance(String dir, NavUpdate update) {
            return new Row(false, update, dir);
        }
    }
}
