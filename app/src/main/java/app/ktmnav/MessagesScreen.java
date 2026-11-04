package app.ktmnav;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import app.ktmnav.backend.BannerSink;
import app.ktmnav.backend.FeatureHooks;
import app.ktmnav.backend.MessageMirror;
import app.ktmnav.backend.TrackBannerMonitor;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class MessagesScreen extends FeatureScreen {
    MessagesScreen(MainActivity activity) {
        super(activity);
    }

    void show() {
        SharedPreferences prefs = a.prefs();
        LinearLayout b = body();
        Switch[] master = new Switch[1];
        master[0] = switchRow(b, "Show messages on dashboard", "People nearby can read them. Processed on this phone only.",
            prefs.getBoolean(MessageMirror.PREF_ENABLED, false), (v, on) -> {
                if (on && !prefs.getBoolean(MessageMirror.PREF_ENABLED, false)) {
                    boolean[] accepted = {false};
                    new AlertDialog.Builder(a).setTitle(tr("Show messages on your dashboard?"))
                        .setMessage(tr("Sender names and message text will be visible on the bike screen while you ride. Turn on “Hide message text” to show only the sender. Notification access is required so KTMnav can read incoming messages."))
                        .setNegativeButton(tr("Cancel"), null)
                        .setPositiveButton(tr("Turn on"), (d, w) -> {
                            accepted[0] = true;
                            prefs.edit().putBoolean(MessageMirror.PREF_ENABLED, true).apply();
                            a.bridge.settingsChanged();
                            a.toast("Messages will show on your dashboard.");
                        })
                        .setOnDismissListener(d -> { if (!accepted[0]) master[0].setChecked(false); })
                        .show();
                } else if (!on) {
                    prefs.edit().putBoolean(MessageMirror.PREF_ENABLED, false).apply();
                    a.bridge.settingsChanged();
                }
            });

        LinearLayout access = a.dataGroup(b, "Notification access");
        LinearLayout accessReady = readyRow(access, "Ready");
        TextView accessState = status(access);
        Button openAccess = action(access, "Open notification access", false, a::openNotificationAccess);

        LinearLayout apps = a.dataGroup(b, "Apps");
        Set<String> stored = prefs.getStringSet(MessageMirror.PREF_APPS, null);
        Set<String> chosen = new HashSet<>(stored != null ? stored : MessageMirror.DEFAULT_APPS.keySet());
        for (Map.Entry<String, String> app : MessageMirror.DEFAULT_APPS.entrySet()) {
            CheckBox box = new CheckBox(a);
            boolean exists = installed(app.getKey());
            box.setText(app.getValue() + "\n" + tr(exists ? "Installed" : "Not installed"));
            box.setTextColor(a.text);
            box.setTextSize(15);
            box.setMinHeight(a.dp(52));
            box.setChecked(chosen.contains(app.getKey()));
            box.setOnCheckedChangeListener((v, on) -> {
                Set<String> current = prefs.getStringSet(MessageMirror.PREF_APPS, null);
                Set<String> next = new HashSet<>(current != null ? current : MessageMirror.DEFAULT_APPS.keySet());
                if (on) next.add(app.getKey()); else next.remove(app.getKey());
                prefs.edit().putStringSet(MessageMirror.PREF_APPS, next).apply();
                a.bridge.settingsChanged();
            });
            a.gap(apps, 4);
            apps.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout privacy = a.dataGroup(b, "What is shown");
        prefSwitch(privacy, "Include group chats", "When off, only one-to-one chats appear.", MessageMirror.PREF_GROUPS, false, null);
        prefSwitch(privacy, "Hide message text", "Show only “Message from” and the sender’s name.", MessageMirror.PREF_HIDE_CONTENT, false, null);

        LinearLayout quiet = a.dataGroup(b, "Quick mute");
        TextView quietState = status(quiet);
        Button mute30 = a.button("Mute 30 min", false, () -> mute(30));
        Button mute60 = a.button("Mute 60 min", false, () -> mute(60));
        pair(quiet, mute30, mute60);
        Button unmute = action(quiet, "Unmute", false, () -> {
            MessageMirror.unmute(prefs);
            a.bridge.settingsChanged();
            a.toast("Messages unmuted.");
        });

        LinearLayout media = a.dataGroup(b, "Music");
        prefSwitch(media, "Track changes on dashboard", "Show song and artist when the track changes.", TrackBannerMonitor.PREF_TRACK_BANNER, true, null);

        LinearLayout test = a.dataGroup(b, "Test");
        TextView testState = note(test, "");
        Button send = action(test, "Send test message", false, () -> {
            BannerSink sink = FeatureHooks.getBannerSink();
            if (sink == null) sink = a.bridge.getBannerSink();
            boolean sent = MessageMirror.shared().sendTest(sink, prefs.getBoolean(MessageMirror.PREF_HIDE_CONTENT, false));
            a.toast(sent ? "Test message sent to your dashboard." : "Could not send the test message. Check the motorcycle connection.");
        });

        AlertDialog dialog = sheet("Messages on dashboard", b);
        showLive(dialog, () -> {
            boolean ready = a.notificationAccess();
            visible(accessReady, ready);
            visible(accessState, !ready);
            visible(openAccess, !ready);
            setText(accessState, tr(ready ? "Notification access: Ready" : "Notification access needed"));
            long left = MessageMirror.quietRemainingMs(prefs, System.currentTimeMillis());
            setText(quietState, left > 0 ? fmt("Muted · %d min left", (left + 59_999L) / 60_000L) : tr("Not muted"));
            enable(unmute, left > 0);
            boolean connected = a.bridge.isConnected();
            enable(send, connected);
            visible(testState, !connected);
            setText(testState, tr("Connect your motorcycle to send a test message."));
        });
    }

    private void mute(int minutes) {
        MessageMirror.muteFor(a.prefs(), minutes, System.currentTimeMillis());
        a.bridge.settingsChanged();
        a.toast(minutes == 30 ? "Messages muted for 30 minutes." : "Messages muted for 60 minutes.");
    }
}
