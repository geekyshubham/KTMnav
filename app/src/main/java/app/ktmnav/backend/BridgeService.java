package app.ktmnav.backend;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service to maintain motorcycle Bluetooth bridge connection in background.
 */
public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    public static final String ACTION_START = "app.ktmnav.action.START";
    public static final String ACTION_STOP = "app.ktmnav.action.STOP";
    public static final String CHANNEL_ID = "ktmnav_connection";
    public static final int NOTIFICATION_ID = 101;

    private static volatile BridgeService instance;
    private static volatile boolean foregroundIncludesLocation;
    private static volatile boolean stopRequested;
    private static final AtomicInteger pendingStarts = new AtomicInteger();

    private String lastTitle = "KTMnav";
    private String lastText = "Connecting";

    public static boolean isLocationForeground() {
        return instance != null && foregroundIncludesLocation;
    }

    public static void start(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("desired_connect", false)) {
            AppLog.d(TAG, "Not starting BridgeService: desired_connect is false");
            return;
        }
        if (!prefs.getBoolean("keep_background", true)) {
            AppLog.d(TAG, "Not starting BridgeService: keep_background is false");
            return;
        }
        Bridge.Bike active = Bridge.get(context).getActiveBike();
        if (active == null) {
            AppLog.d(TAG, "Not starting BridgeService: no active bike configured");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d(TAG, "Not starting BridgeService: BLUETOOTH_CONNECT permission missing");
                return;
            }
        }

        stopRequested = false;
        pendingStarts.incrementAndGet();
        try {
            Intent intent = new Intent(context, BridgeService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            pendingStarts.updateAndGet(n -> Math.max(0, n - 1));
            AppLog.w(TAG, "Cannot start BridgeService", e);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        if (pendingStarts.get() > 0) {
            // stopService before startForeground would crash the app; onStartCommand promotes then stops.
            stopRequested = true;
            AppLog.d(TAG, "BridgeService stop deferred until the pending foreground start is handled");
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}

        BridgeService svc = instance;
        if (svc != null) {
            try {
                svc.stopForeground(STOP_FOREGROUND_REMOVE);
                svc.stopSelf();
            } catch (Exception ignored) {}
        }

        try {
            context.stopService(new Intent(context, BridgeService.class));
        } catch (Exception e) {
            AppLog.w(TAG, "Cannot stop BridgeService", e);
        }
    }

    public static void refreshForegroundType(Context context) {
        BridgeService svc = instance;
        if (svc == null || foregroundIncludesLocation || !app.ktmnav.backend.ride.RideServices.hasLocationPermission(context)) {
            return;
        }
        if (svc.enterForeground(svc.buildNotification(svc.lastTitle, svc.lastText), true)) {
            AppLog.i(TAG, "Foreground service promoted to include location");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean lastPending = pendingStarts.updateAndGet(n -> Math.max(0, n - 1)) == 0;
        boolean stopNow = stopRequested;
        if (stopNow && lastPending) {
            stopRequested = false;
        }

        SharedPreferences prefs = getSharedPreferences("ktmnav", MODE_PRIVATE);
        boolean desiredConnect = prefs.getBoolean("desired_connect", false);
        boolean keepBackground = prefs.getBoolean("keep_background", true);
        Bridge bridge = Bridge.get(this);
        Bridge.Bike active = bridge.getActiveBike();

        boolean hasBluetoothPerm = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasBluetoothPerm = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        boolean explicitStop = intent != null && ACTION_STOP.equals(intent.getAction());
        boolean shouldRun = !explicitStop && !stopNow && desiredConnect && keepBackground && active != null && hasBluetoothPerm;

        String title = active != null ? active.name : "KTMnav";
        String text = active == null ? "Stopping" : bridge.isConnected() ? "Connected to " + active.name : "Connecting to " + active.name;
        lastTitle = title;
        lastText = text;

        boolean promoted = enterForeground(buildNotification(title, text), shouldRun);
        if (!shouldRun || !promoted) {
            if (!promoted) {
                AppLog.w(TAG, "BridgeService could not enter the foreground; stopping");
            }
            stopForegroundQuietly();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!bridge.isConnected() && !"Connecting".equals(bridge.getState()) && !"Authenticating".equals(bridge.getState())) {
            AppLog.i(TAG, "Process restarted: restoring desired connection");
            bridge.connect();
        }

        return START_STICKY;
    }

    private boolean enterForeground(Notification notification, boolean allowLocation) {
        if (allowLocation && app.ktmnav.backend.ride.RideServices.hasLocationPermission(this)) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                foregroundIncludesLocation = true;
                return true;
            } catch (RuntimeException e) {
                AppLog.w(TAG, "Location foreground type refused (" + e.getClass().getSimpleName() + "); using connectedDevice only");
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            foregroundIncludesLocation = false;
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Failed startForeground in BridgeService: " + e.getMessage());
            foregroundIncludesLocation = false;
            return false;
        }
    }

    private void stopForegroundQuietly() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {}
        foregroundIncludesLocation = false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        SharedPreferences prefs = getSharedPreferences("ktmnav", MODE_PRIVATE);
        if (!prefs.getBoolean("keep_background", true)) {
            AppLog.i(TAG, "Task removed with keep_background=false; disconnecting and stopping service");
            Bridge.get(this).disconnect();
            stopForegroundQuietly();
            stopSelf();
        } else if (!Bridge.get(this).isConnected()) {
            AppLog.i(TAG, "Task removed while disconnected - suspending active transport and arming passive detection");
            Bridge.get(this).suspendAndArmBackground();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        foregroundIncludesLocation = false;
        super.onDestroy();
    }

    public static void update(Context context, String title, String text) {
        BridgeService svc = instance;
        if (svc != null) {
            svc.lastTitle = title;
            svc.lastText = text;
            try {
                NotificationManager nm = svc.getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, svc.buildNotification(title, text));
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Failed to update notification: " + e.getMessage());
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "KTMnav Connection",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active connection status to your motorcycle");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                launchIntent != null ? launchIntent : new Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int iconRes = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
        if (iconRes == 0) {
            iconRes = android.R.drawable.ic_dialog_info;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setContentIntent(pi)
                .setOngoing(true);

        return builder.build();
    }
}
