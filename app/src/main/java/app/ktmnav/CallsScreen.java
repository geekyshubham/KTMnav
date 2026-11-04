package app.ktmnav;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.ktmnav.backend.HandlebarMappings;
import app.ktmnav.backend.HandlebarRouter;

final class CallsScreen extends FeatureScreen {
    static final String[] PHONE = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS};

    CallsScreen(MainActivity activity) {
        super(activity);
    }

    void show() {
        LinearLayout b = body();
        LinearLayout ringing = a.dataGroup(b, "While ringing");
        ringing.addView(a.label("SET answers, BACK silences.", 16, a.text, false));
        a.gap(ringing, 4);
        TextView handlebarState = note(ringing, "");
        Button mappings = action(ringing, "Handlebar button mappings", false, a::handlebarMappings);

        LinearLayout phone = a.dataGroup(b, "Phone permissions");
        LinearLayout phoneReady = readyRow(phone, "Phone access · Ready");
        TextView phoneState = status(phone);
        TextView phoneNote = note(phone, "Used only to notice a ringing call and answer it when you press SET. No call log, nothing stored or shared.");
        Button request = action(phone, "Allow phone access", true, this::request);

        LinearLayout caller = a.dataGroup(b, "Caller name");
        LinearLayout callerReady = readyRow(caller, "Caller names · Ready");
        TextView accessState = status(caller);
        TextView callerNote = note(caller, "Needs notification access. Without it the dashboard shows “Incoming call”.");
        Button openAccess = action(caller, "Open notification access", false, a::openNotificationAccess);

        AlertDialog dialog = sheet("Calls", b);
        showLive(dialog, () -> {
            boolean read = a.granted(PHONE[0]), answer = a.granted(PHONE[1]);
            boolean monitoring = a.bridge.getCallStateMonitor() != null && a.bridge.getCallStateMonitor().isStarted();
            boolean phoneOk = read && answer && monitoring;
            visible(phoneReady, phoneOk);
            visible(phoneState, !phoneOk);
            visible(phoneNote, !(read && answer));
            visible(request, !(read && answer));
            setText(phoneState, tr(read ? "Read phone state: Allowed" : "Read phone state: Not allowed") + "\n"
                + tr(answer ? "Answer calls: Allowed" : "Answer calls: Not allowed") + "\n"
                + tr(monitoring ? "Call detection: Active" : "Call detection: Inactive"));
            boolean mapped = a.prefs().getBoolean(HandlebarRouter.PREF_MAPPINGS_ENABLED, false);
            boolean off = HandlebarMappings.Mode.fromPreference(a.prefs().getString(HandlebarMappings.PREF_MODE, null)) == HandlebarMappings.Mode.OFF;
            visible(mappings, !mapped || off);
            setText(handlebarState, tr(!mapped ? "Handlebar mappings are off. Turn them on so SET and BACK can control calls."
                : off ? "Button mode is Off. Choose another mode so SET and BACK can control calls."
                : "Handlebar mappings are on. SET and BACK control ringing calls."));
            boolean access = a.notificationAccess();
            visible(callerReady, access);
            visible(accessState, !access);
            visible(callerNote, !access);
            visible(openAccess, !access);
            setText(accessState, tr("Notification access needed"));
        });
    }

    private void request() {
        if (a.granted(PHONE[0]) && a.granted(PHONE[1])) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        a.requestRuntime("Allow phone access?",
            "Android will ask to let KTMnav make and manage phone calls. KTMnav uses this only to detect a ringing call and to answer it when you press SET on the handlebar. Call details are not stored or shared.",
            PHONE, MainActivity.REQUEST_PHONE, null);
    }
}
