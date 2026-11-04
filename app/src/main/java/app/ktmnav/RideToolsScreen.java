package app.ktmnav;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import app.ktmnav.backend.ride.RidePrefs;
import app.ktmnav.backend.ride.RideServices;
import app.ktmnav.backend.ride.Waypoint;
import app.ktmnav.backend.ride.WaypointLauncher;

final class RideToolsScreen extends FeatureScreen {
    static final String[] LOCATION = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    private static final String[] MODES = {RidePrefs.MODE_OFF, RidePrefs.MODE_WHILE_CONNECTED, RidePrefs.MODE_MANUAL};
    private static final String[] MODE_LABELS = {"Off", "While connected", "Manual"};

    RideToolsScreen(MainActivity activity) {
        super(activity);
    }

    private RideServices rides() {
        return a.bridge.getRideServices();
    }

    static String modeLabel(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return MODE_LABELS[i];
        return MODE_LABELS[0];
    }

    void show() {
        SharedPreferences prefs = a.prefs();
        LinearLayout b = body();
        TextView rideState = status(b);

        LinearLayout location = a.dataGroup(b, "Location");
        LinearLayout locationReady = readyRow(location, "Precise location · Ready");
        TextView locationState = status(location);
        TextView locationNote = note(location, "For the speed alert and ride recording. Rides stay on this phone.");
        Button allowLocation = action(location, "Allow location access", true, this::requestLocation);

        LinearLayout beeper = a.dataGroup(b, "Turn beeper");
        prefSwitch(beeper, "Beep before turns", "Faster beeps as a turn gets closer, on the turn’s side.", RidePrefs.BEEPER_ENABLED, false, null);
        TextView volumeLabel = a.rawLabel(fmt("Beeper volume: %d%%", RidePrefs.beeperVolume(prefs)), 15, a.text, false);
        a.gap(beeper, 8);
        beeper.addView(volumeLabel);
        SeekBar volume = new SeekBar(a);
        volume.setMax(100);
        volume.setProgress(RidePrefs.beeperVolume(prefs));
        volume.setMinimumHeight(a.dp(48));
        volume.setContentDescription(tr("Beeper volume"));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                volumeLabel.setText(fmt("Beeper volume: %d%%", value));
                if (user) prefs.edit().putInt(RidePrefs.BEEPER_VOLUME, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(RidePrefs.BEEPER_VOLUME, bar.getProgress()).apply();
                a.bridge.settingsChanged();
            }
        });
        beeper.addView(volume, new LinearLayout.LayoutParams(-1, a.dp(48)));
        prefSwitch(beeper, "Swap left and right", null, RidePrefs.BEEPER_SWAP_LR, false, null);
        action(beeper, "Preview beep", false, () -> {
            if (rides() == null) { a.toast("Ride tools are not ready yet. Try again in a moment."); return; }
            rides().previewBeep();
            a.toast("Playing a preview beep.");
        });

        LinearLayout speed = a.dataGroup(b, "Speed alert");
        prefSwitch(speed, "Alert above speed limit", "Tone and dashboard alert above your limit.", RidePrefs.OVERSPEED_ENABLED, false, null);
        a.gap(speed, 8);
        speed.addView(a.label("Speed limit (km/h)", 15, a.text, false));
        EditText limit = input("Speed limit (km/h)", String.valueOf(RidePrefs.overspeedLimit(prefs)));
        limit.setInputType(InputType.TYPE_CLASS_NUMBER);
        speed.addView(limit, new LinearLayout.LayoutParams(-1, -2));
        TextView limitError = error(speed);
        action(speed, "Save speed limit", false, () -> {
            int kmh;
            try { kmh = Integer.parseInt(limit.getText().toString().trim()); } catch (NumberFormatException e) { kmh = -1; }
            if (kmh < RidePrefs.MIN_OVERSPEED_LIMIT || kmh > RidePrefs.MAX_OVERSPEED_LIMIT) {
                showError(limit, limitError, tr("Enter a whole number from 20 to 250 km/h."));
                return;
            }
            clearError(limit, limitError);
            prefs.edit().putInt(RidePrefs.OVERSPEED_LIMIT_KMH, kmh).apply();
            a.bridge.settingsChanged();
            a.toast("Speed limit saved.");
        });

        LinearLayout record = a.dataGroup(b, "Ride recording");
        Button mode = a.button("", false, null);
        mode.setOnClickListener(v -> chooseMode(mode));
        mode.setText(fmt("Recording: %s", tr(modeLabel(RidePrefs.recordingMode(prefs)))));
        record.addView(mode, new LinearLayout.LayoutParams(-1, -2));
        Button manual = action(record, "Start recording", true, () -> {
            if (rides() == null) return;
            if (rides().isRecording()) { rides().stopManualRecording(); a.toast("Recording stopped. The ride is saved."); }
            else { rides().startManualRecording(); a.toast("Recording started."); }
        });
        action(record, "View rides", false, () -> new RidesScreen(a).show());

        LinearLayout waypoint = a.dataGroup(b, "Waypoint");
        Waypoint saved = WaypointLauncher.load(prefs);
        EditText where = input("Coordinates or Google Maps link", saved != null ? saved.toPref() : "");
        where.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        waypoint.addView(where, new LinearLayout.LayoutParams(-1, -2));
        TextView whereError = error(waypoint);
        a.gap(waypoint, 4);
        EditText name = input("Waypoint name (optional)", saved != null && saved.label != null ? saved.label : "");
        waypoint.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView savedState = status(waypoint);
        action(waypoint, "Save waypoint", false, () -> {
            if (!WaypointLauncher.save(prefs, where.getText().toString(), name.getText().toString())) {
                showError(where, whereError, tr("Not a location. Enter latitude and longitude like 47.2692, 11.4041, or paste a full Google Maps link. Short share links must be opened in Maps first."));
                return;
            }
            clearError(where, whereError);
            Waypoint w = WaypointLauncher.load(prefs);
            if (w != null) where.setText(w.toPref());
            a.toast("Waypoint saved.");
        });
        Button navigate = a.button("Navigate to waypoint", true, () -> {
            Waypoint w = WaypointLauncher.load(prefs);
            Intent intent = WaypointLauncher.intentFor(a, w);
            if (intent == null) return;
            try { a.startActivity(intent); } catch (ActivityNotFoundException e) { a.toast("No navigation app can open this waypoint."); }
        });
        Button clear = a.button("Clear waypoint", false, () -> {
            WaypointLauncher.clear(prefs);
            where.setText("");
            name.setText("");
            clearError(where, whereError);
            a.toast("Waypoint cleared.");
        });
        a.withIcon(navigate, R.drawable.ic_near_me);
        pair(waypoint, navigate, clear);

        AlertDialog dialog = sheet("Ride tools", b);
        showLive(dialog, () -> {
            RideServices r = rides();
            boolean fine = a.granted(LOCATION[0]);
            boolean coarse = a.granted(LOCATION[1]);
            setText(locationState, tr(fine ? "Location: Precise access allowed" : coarse ? "Location: Only approximate access allowed. Allow precise location." : "Location: Not allowed"));
            visible(locationReady, fine);
            visible(locationState, !fine);
            visible(locationNote, !fine);
            visible(allowLocation, !fine);
            String state = r != null ? r.getStatus() : RideServices.STATUS_OFF;
            setText(rideState, fmt("Status: %s", tr(state)));
            String current = RidePrefs.recordingMode(prefs);
            boolean recording = r != null && r.isRecording();
            setText(manual, tr(recording ? "Stop recording" : "Start recording"));
            boolean connectedRecording = recording && RidePrefs.MODE_WHILE_CONNECTED.equals(current) && a.bridge.isConnected();
            enable(manual, r != null && fine && !connectedRecording && (recording || !RidePrefs.MODE_OFF.equals(current)));
            Waypoint w = WaypointLauncher.load(prefs);
            setText(savedState, w == null ? tr("No waypoint saved.") : fmt("Saved waypoint: %s", (w.label != null ? w.label + " · " : "") + w.toPref()));
            enable(navigate, w != null);
            enable(clear, w != null);
        });
    }

    private EditText input(String hint, String initial) {
        EditText e = new EditText(a);
        e.setText(initial);
        e.setHint(tr(hint));
        e.setContentDescription(tr(hint));
        e.setSingleLine(true);
        e.setTextColor(a.text);
        e.setHintTextColor(a.muted);
        e.setMinHeight(a.dp(52));
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        return e;
    }

    private void showError(EditText field, TextView message, String value) {
        message.setText(value);
        message.setVisibility(View.VISIBLE);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(a.danger));
        field.setStateDescription(value);
    }

    private void clearError(EditText field, TextView message) {
        message.setVisibility(View.GONE);
        field.setBackgroundTintList(null);
        field.setStateDescription(null);
    }

    private void chooseMode(Button control) {
        String current = RidePrefs.recordingMode(a.prefs());
        int checked = 0;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(current)) checked = i;
        new AlertDialog.Builder(a).setTitle(tr("Ride recording"))
            .setSingleChoiceItems(a.translateAll(MODE_LABELS), checked, (d, n) -> {
                a.prefs().edit().putString(RidePrefs.RIDE_RECORDING, MODES[n]).apply();
                a.bridge.settingsChanged();
                control.setText(fmt("Recording: %s", tr(MODE_LABELS[n])));
                d.dismiss();
                if (!RidePrefs.MODE_OFF.equals(MODES[n]) && !a.granted(LOCATION[0])) requestLocation();
            }).setNegativeButton(tr("Cancel"), null).show();
    }

    private void requestLocation() {
        if (a.granted(LOCATION[0])) {
            a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.getPackageName())));
            return;
        }
        a.requestRuntime("Allow location access?",
            "KTMnav uses precise location only for the speed alert and to record rides you choose to record. Location stays on this phone and is never uploaded. Android asks for permission next.",
            LOCATION, MainActivity.REQUEST_LOCATION, null);
    }
}
