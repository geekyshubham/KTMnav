package app.ktmnav;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.ktmnav.backend.obd.GearEstimator;
import app.ktmnav.backend.obd.GearLearner;
import app.ktmnav.backend.obd.ObdAdapterScanner;
import app.ktmnav.backend.obd.ObdManager;
import app.ktmnav.backend.obd.ObdPids;
import app.ktmnav.backend.obd.ObdSnapshot;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ObdScreen extends FeatureScreen implements ObdManager.Listener {
    private static final long SCAN_MS = 12_000L;
    private static final long DTC_TIMEOUT_MS = 15_000L;
    private static final long GEAR_MAX_AGE_MS = 3_000L;
    private static final Object[][] LIVE = {
        {ObdPids.Pid.RPM, "Engine speed"},
        {ObdPids.Pid.SPEED, "Road speed"},
        {ObdPids.Pid.COOLANT, "Coolant temperature"},
        {ObdPids.Pid.INTAKE, "Intake air temperature"},
        {ObdPids.Pid.OIL, "Oil temperature"},
        {ObdPids.Pid.AMBIENT, "Ambient temperature"},
        {ObdPids.Pid.VOLTAGE, "Control module voltage"},
        {ObdPids.Pid.FUEL, "Fuel level"},
        {ObdPids.Pid.THROTTLE, "Throttle position"},
        {ObdPids.Pid.LOAD, "Engine load"}
    };

    private final ObdManager obd;
    private final ObdAdapterScanner scanner;
    private final Map<ObdPids.Pid, TextView> liveValues = new LinkedHashMap<>();
    private TextView stateView, adapterView, gearValue, protocolValue, updatedValue, liveNote, scanState, dtcView, learnState, disabledNote;
    private Button connect, forget, scan, pause, readCodes, profile, resetLearning;
    private LinearLayout results;
    private AlertDialog dialog;
    private boolean paused, scanning, dtcPending;
    private long dtcBaseline;
    private int found;
    private final Runnable dtcTimeout = () -> {
        if (!dtcPending) return;
        dtcPending = false;
        dtcView.setText(tr("No answer from the motorcycle. Check the ignition and the adapter, then try again."));
        refresh();
    };

    ObdScreen(MainActivity activity) {
        super(activity);
        obd = activity.bridge.getObd();
        scanner = new ObdAdapterScanner(activity);
    }

    void show() {
        if (obd == null) { a.toast("OBD-II is not ready yet. Try again in a moment."); return; }
        LinearLayout b = body();
        note(b, "Needs an ELM327 Bluetooth adapter and a KTM 6-pin to OBD-II lead. Read-only: it never clears fault codes or changes the motorcycle.");
        a.gap(b, 8);
        prefSwitch(b, "Use an OBD-II adapter", null, ObdManager.PREF_ENABLED, false, this::refresh);
        disabledNote = note(b, "Turn on the OBD-II adapter switch to scan, connect and read data.");

        LinearLayout adapter = a.dataGroup(b, "Selected adapter");
        adapterView = status(adapter);
        a.gap(adapter, 4);
        stateView = a.rawLabel("", 14, a.muted, false);
        stateView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        adapter.addView(stateView);
        connect = a.button("Connect", true, this::toggleConnection);
        forget = a.button("Forget adapter", false, () -> confirm("Forget this adapter?", "KTMnav disconnects and removes the saved adapter. You can scan for it again later.", "Forget", () -> {
            obd.forgetAdapter();
            a.toast("Adapter forgotten.");
            refresh();
        }));
        pair(adapter, connect, forget);

        LinearLayout find = a.dataGroup(b, "Find an adapter");
        note(find, "Ignition on. Pair Classic adapters in Android first.");
        scan = action(find, "Scan for adapters", false, this::toggleScan);
        a.gap(find, 8);
        scanState = status(find);
        results = a.column();
        find.addView(results);

        LinearLayout live = a.dataGroup(b, "Live data");
        liveNote = note(live, "");
        pause = action(live, "Pause live data", false, () -> { paused = !paused; refresh(); if (!paused) onObdSnapshot(obd.getSnapshot()); });
        a.gap(live, 12);
        for (Object[] row : LIVE) liveValues.put((ObdPids.Pid) row[0], reading(live, tr((String) row[1])));
        gearValue = reading(live, tr("Gear"));
        protocolValue = reading(live, tr("Protocol"));
        updatedValue = reading(live, tr("Last update"));

        LinearLayout codes = a.dataGroup(b, "Fault codes");
        readCodes = action(codes, "Read fault codes", false, this::readCodes);
        a.gap(codes, 8);
        dtcView = status(codes);
        dtcView.setTextIsSelectable(true);

        LinearLayout gear = a.dataGroup(b, "Gear estimate");
        note(gear, "Estimated from engine and road speed.");
        profile = action(gear, "", false, this::chooseProfile);
        a.gap(gear, 8);
        learnState = status(gear);
        resetLearning = action(gear, "Reset gear learning", false, () -> confirm("Reset gear learning?", "Learned gear ratios and collected samples are removed. Learning starts again on your next ride.", "Reset", () -> {
            obd.resetGearLearning();
            a.toast("Gear learning reset.");
            refresh();
        }));
        a.gap(gear, 8);
        prefSwitch(gear, "Show speed & gear on dash", null, ObdManager.PREF_DASH_BANNER, false, null);

        refreshDtc(obd.getSnapshot());
        dialog = sheet("OBD-II adapter (read-only)", b);
        dialog.setOnShowListener(d -> { obd.addListener(this); tick.run(); });
        dialog.setOnDismissListener(d -> {
            obd.removeListener(this);
            a.handler.removeCallbacks(tick);
            a.handler.removeCallbacks(dtcTimeout);
            if (scanning) scanner.stop();
        });
        dialog.show();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            refresh();
            a.handler.postDelayed(this, REFRESH_MS);
        }
    };

    private TextView reading(LinearLayout parent, String name) {
        parent.addView(a.rawLabel(name, 13, a.muted, false));
        TextView v = a.rawLabel(tr("Not reported"), 16, a.text, true);
        parent.addView(v);
        a.gap(parent, 10);
        return v;
    }

    @Override public void onObdSnapshot(ObdSnapshot snapshot) {
        if (dialog == null || !dialog.isShowing()) return;
        if (!paused) renderLive(snapshot);
        if (dtcPending && snapshot.dtcReadAtMs > dtcBaseline) {
            dtcPending = false;
            a.handler.removeCallbacks(dtcTimeout);
            refreshDtc(snapshot);
            a.toast("Fault codes read.");
        }
        refresh();
    }

    private boolean sessionActive(ObdSnapshot s) {
        String st = s.state == null ? "" : s.state;
        return s.connected || st.startsWith("Connecting") || st.startsWith("Initialising") || st.startsWith("No ECU") || st.endsWith("retrying");
    }

    private void refresh() {
        if (dialog == null) return;
        boolean enabled = obd.isEnabled();
        ObdSnapshot s = obd.getSnapshot();
        String address = obd.getAdapterAddress();
        boolean selected = address != null && !address.isEmpty();
        disabledNote.setVisibility(enabled ? View.GONE : View.VISIBLE);
        String name = a.prefs().getString(ObdManager.PREF_NAME, address);
        String transport = ObdManager.TRANSPORT_SPP.equals(a.prefs().getString(ObdManager.PREF_TRANSPORT, ObdManager.TRANSPORT_BLE)) ? tr("Bluetooth Classic") : tr("Bluetooth LE");
        setText(adapterView, selected ? name + "\n" + transport + " · " + address : tr("No adapter selected."));
        setText(stateView, fmt("Status: %s", obdState(s.state)));
        boolean active = sessionActive(s);
        setText(connect, tr(active ? "Disconnect" : "Connect"));
        enable(connect, enabled && selected);
        enable(forget, selected);
        enable(scan, enabled);
        setText(scan, tr(scanning ? "Stop scan" : "Scan for adapters"));
        enable(pause, enabled);
        setText(pause, tr(paused ? "Resume live data" : "Pause live data"));
        setText(liveNote, tr(!enabled ? "Live data appears after you turn on the adapter and connect." : paused ? "Live data paused. Values below are not updating." : s.connected ? "Live values update about once a second." : "Connect the adapter to see live values."));
        if (!paused) setText(updatedValue, s.updatedAtMs <= 0 || s.values.isEmpty() ? tr("No data yet") : fmt("%d s ago", Math.max(0L, (System.currentTimeMillis() - s.updatedAtMs) / 1000L)));
        enable(readCodes, enabled && s.connected && !dtcPending);
        String id = a.prefs().getString(ObdManager.PREF_GEAR_PROFILE, ObdManager.GEAR_PROFILE_AUTO);
        setText(profile, fmt("Gear profile: %s", profileLabel(id)));
        GearEstimator.Profile learned = obd.getLearnedProfile();
        setText(learnState, learned != null
            ? fmt("Learned ratios: %s rpm per km/h", GearLearner.formatRatios(learned.rpmPerKmh).replace(",", ", "))
            : fmt("Learning gears: %1$d / %2$d steady samples", obd.getGearLearningSamples(), GearLearner.MIN_SAMPLES));
        enable(resetLearning, learned != null || obd.getGearLearningSamples() > 0);
    }

    private void renderLive(ObdSnapshot s) {
        for (Map.Entry<ObdPids.Pid, TextView> e : liveValues.entrySet()) setText(e.getValue(), format(e.getKey(), s.get(e.getKey())));
        long now = System.currentTimeMillis();
        Integer g = s.estimatedGear(obd.getGearProfile(), now, GEAR_MAX_AGE_MS);
        setText(gearValue, (g == null ? "—" : String.valueOf(g)) + " " + tr("(estimated)"));
        String protocol = s.protocolDescription != null ? s.protocolDescription : s.protocol;
        String adapterInfo = s.elmVersion != null ? " · " + s.elmVersion : "";
        setText(protocolValue, protocol != null ? protocol + adapterInfo : tr("Not detected yet"));
    }

    private String format(ObdPids.Pid pid, ObdSnapshot.Value v) {
        if (v == null) return tr("Not reported");
        switch (pid) {
            case RPM: return fmt("%.0f rpm", v.value);
            case SPEED: return fmt("%.0f km/h", v.value);
            case VOLTAGE: return fmt("%.2f V", v.value);
            case COOLANT: case INTAKE: case OIL: case AMBIENT: return fmt("%.0f °C", v.value);
            default: return fmt("%.0f %%", v.value);
        }
    }

    String obdState(String state) {
        if (state == null) return tr("Disconnected");
        if (state.endsWith(" - retrying")) return obdState(state.substring(0, state.length() - 11)) + " · " + tr("retrying");
        if (state.startsWith("Connecting to ")) return fmt("Connecting to %s", state.substring(14));
        if (state.startsWith("Connected (")) return tr("Connected") + state.substring(9);
        return tr(state);
    }

    private void toggleConnection() {
        if (sessionActive(obd.getSnapshot())) {
            obd.disconnect();
            a.toast("Adapter disconnected.");
        } else {
            a.withBluetooth(() -> { obd.connect(); refresh(); });
        }
        refresh();
    }

    private void toggleScan() {
        if (scanning) { scanner.stop(); return; }
        a.withBluetooth(() -> {
            results.removeAllViews();
            found = 0;
            scanning = true;
            scanState.setText(tr("Scanning for adapters…"));
            for (ObdAdapterScanner.Candidate c : scanner.bondedCandidates()) addCandidate(c);
            scanner.start(SCAN_MS, new ObdAdapterScanner.Callback() {
                @Override public void onCandidate(ObdAdapterScanner.Candidate c) { addCandidate(c); }
                @Override public void onFinished(String error) {
                    scanning = false;
                    if (error != null) scanState.setText(fmt("Scan stopped: %s", tr(error)));
                    else if (found == 0) scanState.setText(tr("No adapters found. Check that the adapter is plugged in with the ignition on. Pair Classic adapters in Android Bluetooth settings first."));
                    else scanState.setText(fmt("Scan complete · %d found. Tap an adapter to select it.", found));
                    refresh();
                }
            });
            refresh();
        });
    }

    private void addCandidate(ObdAdapterScanner.Candidate c) {
        if (dialog == null || !dialog.isShowing()) return;
        for (int i = 0; i < results.getChildCount(); i++) if (c.address.equals(results.getChildAt(i).getTag())) return;
        found++;
        String type = tr(ObdManager.TRANSPORT_SPP.equals(c.transport) ? "Bluetooth Classic" : "Bluetooth LE") + (c.bonded ? " · " + tr("Paired") : "");
        String signal = c.rssi != 0 ? " · " + fmt("Signal %d dBm", c.rssi) : "";
        Button item = a.button("", false, () -> {
            obd.selectAdapter(c.address, c.name, c.transport);
            a.toast("Adapter selected.");
            refresh();
        });
        item.setText(c.name + "\n" + type + signal);
        item.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        item.setTag(c.address);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = a.dp(8);
        results.addView(item, p);
    }

    private void readCodes() {
        ObdSnapshot s = obd.getSnapshot();
        dtcBaseline = s.dtcReadAtMs;
        dtcPending = true;
        dtcView.setText(tr("Reading fault codes…"));
        obd.requestDtcRead();
        a.handler.removeCallbacks(dtcTimeout);
        a.handler.postDelayed(dtcTimeout, DTC_TIMEOUT_MS);
        refresh();
    }

    private void refreshDtc(ObdSnapshot s) {
        if (s.dtcReadAtMs <= 0) {
            dtcView.setText(tr("No fault-code read yet. Connect, then tap Read fault codes."));
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append(tr(s.milOn == null ? "Check-engine light (MIL): Unknown" : s.milOn ? "Check-engine light (MIL): On" : "Check-engine light (MIL): Off"));
        out.append("\n\n").append(s.storedDtcs.isEmpty() ? tr("No stored codes") : fmt("Stored codes: %s", String.join(", ", s.storedDtcs)));
        out.append("\n").append(s.pendingDtcs.isEmpty() ? tr("No pending codes") : fmt("Pending codes: %s", String.join(", ", s.pendingDtcs)));
        out.append("\n\n").append(fmt("Read at %s", DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(s.dtcReadAtMs))));
        dtcView.setText(out.toString());
    }

    private String profileLabel(String id) {
        if (ObdManager.GEAR_PROFILE_AUTO.equals(id)) return tr("Automatic (learned when ready)");
        if (ObdManager.GEAR_PROFILE_LEARNED.equals(id)) return tr("Learned from your rides");
        GearEstimator.Profile p = GearEstimator.profileById(id);
        return p.name + (p.verified ? "" : " · " + tr("unverified"));
    }

    private void chooseProfile() {
        GearEstimator.Profile learned = obd.getLearnedProfile();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add(ObdManager.GEAR_PROFILE_AUTO);
        labels.add(tr("Automatic (learned when ready)"));
        ids.add(ObdManager.GEAR_PROFILE_LEARNED);
        labels.add(tr("Learned from your rides") + "\n" + (learned != null ? GearLearner.formatRatios(learned.rpmPerKmh).replace(",", ", ") : tr("Not learned yet")));
        for (GearEstimator.Profile p : GearEstimator.PROFILES) {
            ids.add(p.id);
            labels.add(profileLabel(p.id));
        }
        String current = a.prefs().getString(ObdManager.PREF_GEAR_PROFILE, ObdManager.GEAR_PROFILE_AUTO);
        boolean learnedReady = learned != null;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public boolean areAllItemsEnabled() { return learnedReady; }
            @Override public boolean isEnabled(int position) { return learnedReady || position != 1; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                boolean on = isEnabled(position);
                v.setEnabled(on);
                v.setAlpha(on ? 1f : .5f);
                return v;
            }
        };
        new AlertDialog.Builder(a).setTitle(tr("Gear profile"))
            .setSingleChoiceItems(adapter, Math.max(0, ids.indexOf(current)), (d, n) -> {
                if (n == 1 && !learnedReady) return;
                a.prefs().edit().putString(ObdManager.PREF_GEAR_PROFILE, ids.get(n)).apply();
                a.bridge.settingsChanged();
                d.dismiss();
                refresh();
            }).setNegativeButton(tr("Cancel"), null).show();
    }
}
