package app.ktmnav.backend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import app.ktmnav.backend.obd.ObdManager;
import app.ktmnav.backend.ride.RideServices;

/**
 * Connects handlebar gestures, call control, dash banners, ride tools and OBD-II to the Bridge lifecycle.
 */
final class FeatureWiring {
    private final Bridge bridge;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    final BannerSink banner;
    final CallControl callControl;
    final CallStateMonitor callStateMonitor;
    final HandlebarRouter handlebarRouter;
    final RideServices rideServices = new RideServices();
    final ObdManager obd;
    private final HandlebarMaskListener maskListener;

    FeatureWiring(Bridge bridge, Context context, HandlebarController controller,
                  HandlebarRouter.ResultListener results, HandlebarRouter.LegacyBackHandler legacyBack) {
        this.bridge = bridge;
        this.context = context;
        this.banner = new BannerSink() {
            @Override
            public boolean show(String owner, String text, int priority, long holdMs) {
                return bridge.showDashBanner(owner, text, priority, holdMs);
            }

            @Override
            public void clear(String owner) {
                bridge.clearDashBanner(owner);
            }
        };
        this.callControl = new CallControl(CallStateMonitor.telecom(context), banner);
        this.callStateMonitor = new CallStateMonitor(context, callControl);
        this.handlebarRouter = new HandlebarRouter(HandlebarRouter.prefsOf(bridge.getPrefs()), new MainThreadScheduler(),
                controller::execute, callControl, banner, results, legacyBack);
        this.maskListener = (mask, uptimeMs) -> {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handlebarRouter.onMask(mask, uptimeMs);
            } else {
                mainHandler.post(() -> handlebarRouter.onMask(mask, uptimeMs));
            }
        };
        this.obd = new ObdManager(context, bridge.getPrefs());
    }

    void start() {
        FeatureHooks.setBannerSink(banner);
        FeatureHooks.setCallNotificationSink(new CallNotificationSink() {
            @Override
            public void onIncomingCallNotification(String callerName) {
                callControl.onIncomingCallNotification(callerName);
            }

            @Override
            public void onCallNotificationRemoved() {
                callControl.onIncomingCallNotificationRemoved();
            }
        });
        rideServices.start(context, new app.ktmnav.backend.ride.RideBannerSink() {
            @Override
            public boolean show(String owner, String text, int priority, long holdMs) {
                return banner.show(owner, text, priority, holdMs);
            }

            @Override
            public void clear(String owner) {
                banner.clear(owner);
            }
        });
        obd.setBannerSink(banner::show);
        obd.settingsChanged();
        bridge.addHandlebarMaskListener(maskListener);
        callStateMonitor.start();
    }

    void onBikeConnected() {
        rideServices.onBikeConnected();
        callStateMonitor.start();
    }

    void onBikeDisconnected() {
        rideServices.onBikeDisconnected();
        mainHandler.post(handlebarRouter::reset);
    }

    void onGuidance(NavUpdate update) {
        rideServices.onGuidance(update);
    }

    void onGuidanceEnded() {
        rideServices.onGuidanceEnded();
    }

    void settingsChanged() {
        rideServices.settingsChanged();
        obd.settingsChanged();
        callStateMonitor.start();
    }
}
