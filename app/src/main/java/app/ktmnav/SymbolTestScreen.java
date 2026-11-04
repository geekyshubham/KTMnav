package app.ktmnav;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.ktmnav.backend.ArrowIcons;
import app.ktmnav.backend.BccuProtocol;
import app.ktmnav.backend.Bridge;
import app.ktmnav.backend.IconCalibrationStore;
import app.ktmnav.backend.TurnIcon;
import java.util.ArrayList;
import java.util.List;

final class SymbolTestScreen extends FeatureScreen {
    private static final int THUMB_BACKGROUND = 0xFF22272B;

    SymbolTestScreen(MainActivity activity) {
        super(activity);
    }

    private boolean gen3Connected() {
        Bridge.Bike bike = a.bridge.getActiveBike();
        return a.bridge.isConnected() && bike != null && "GEN3".equals(bike.generation);
    }

    void show() {
        LinearLayout b = body();
        note(b, "Use while parked.");
        a.gap(b, 8);
        TextView state = status(b);
        action(b, "Test turn symbols", false, this::turnSymbols);
        action(b, "Test banner icons", false, this::bannerIcons);
        Button end = action(b, "End symbol test", false, this::end);
        action(b, "Arrow calibration", true, this::calibration);
        AlertDialog dialog = sheet("Dashboard symbol test & calibration", b);
        showLive(dialog, () -> {
            boolean on = gen3Connected();
            setText(state, tr(on ? "Gen 3 dashboard connected. Symbols stay on the dash until you end the test." : "Connect a Gen 3 motorcycle to show symbols on the dashboard."));
            enable(end, on);
        });
    }

    private void end() {
        a.bridge.endSymbolTest();
        a.toast("Symbol test ended.");
    }

    private void turnSymbols() {
        LinearLayout b = body();
        TextView state = status(b);
        List<Button> buttons = new ArrayList<>();
        for (TurnIcon icon : TurnIcon.values()) {
            Button show = a.button("Show on dash", false, () -> {
                boolean sent = a.bridge.sendSymbolTest(icon.getBinary());
                a.toast(sent ? "Sent to the dashboard." : "Could not send. Connect a Gen 3 motorcycle.");
            });
            show.setContentDescription(fmt("Show %s on dash", icon.name()));
            LinearLayout copy = copy(icon.name(), fmt("ID %d", icon.getBinary()));
            mono((TextView) copy.getChildAt(0));
            itemRow(b, copy, show);
            buttons.add(show);
        }
        Button end = action(b, "End symbol test", false, this::end);
        buttons.add(end);
        AlertDialog dialog = sheet("Turn symbols", b);
        showLive(dialog, () -> liveState(state, buttons));
    }

    private void bannerIcons() {
        LinearLayout b = body();
        TextView state = status(b);
        List<Button> buttons = new ArrayList<>();
        for (BccuProtocol.NotificationIcon icon : BccuProtocol.NotificationIcon.values()) {
            Button show = a.button("Show on dash", false, () -> {
                boolean sent = a.bridge.sendBannerIconTest(icon.getBinary());
                a.toast(sent ? "Sent to the dashboard." : "Could not send. Connect a Gen 3 motorcycle.");
            });
            show.setContentDescription(fmt("Show %s on dash", icon.name()));
            LinearLayout copy = copy(icon.name(), fmt("ID %d", icon.getBinary()));
            mono((TextView) copy.getChildAt(0));
            itemRow(b, copy, show);
            buttons.add(show);
        }
        Button end = action(b, "End symbol test", false, this::end);
        buttons.add(end);
        AlertDialog dialog = sheet("Banner icons", b);
        showLive(dialog, () -> liveState(state, buttons));
    }

    private void liveState(TextView state, List<Button> buttons) {
        boolean on = gen3Connected();
        setText(state, tr(on ? "Gen 3 dashboard connected." : "Connect a Gen 3 motorcycle to show symbols on the dashboard."));
        for (Button button : buttons) enable(button, on);
    }

    private void calibration() {
        IconCalibrationStore store = IconCalibrationStore.get(a);
        LinearLayout b = body();
        note(b, "Assign a symbol to arrows KTMnav could not match.");
        LinearLayout recent = a.dataGroup(b, "Unrecognised arrows");
        LinearLayout saved = a.dataGroup(b, "Saved calibrations");
        Button clearAll = action(b, "Clear all calibrations", false, null);
        AlertDialog dialog = sheet("Arrow calibration", b);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            recent.removeViews(2, recent.getChildCount() - 2);
            saved.removeViews(2, saved.getChildCount() - 2);
            List<IconCalibrationStore.Capture> captures = store.recentUnknown();
            if (captures.isEmpty()) note(recent, "No unrecognised arrows yet. They appear after navigation shows an arrow KTMnav could not match.");
            for (IconCalibrationStore.Capture c : captures) captureRow(recent, store, c, refresh[0]);
            List<IconCalibrationStore.Entry> entries = store.entries();
            if (entries.isEmpty()) note(saved, "No saved calibrations.");
            for (IconCalibrationStore.Entry e : entries) {
                Button forget = a.button("Forget", false, () -> confirm("Forget this calibration?", "KTMnav goes back to its automatic match for this arrow.", "Forget", () -> {
                    store.forget(e.hash);
                    a.toast("Calibration forgotten.");
                    refresh[0].run();
                }));
                forget.setContentDescription(fmt("Forget calibration %s", e.icon.name()));
                LinearLayout copy = copy(e.icon.name(), appName(e.pkg) + " · " + e.hash.substring(0, Math.min(8, e.hash.length())));
                mono((TextView) copy.getChildAt(0));
                itemRow(saved, copy, forget);
            }
            enable(clearAll, !entries.isEmpty() || !captures.isEmpty());
        };
        clearAll.setOnClickListener(v -> confirm("Clear all calibrations?", "Every saved arrow calibration and the list of unrecognised arrows are removed. This cannot be undone.", "Clear all", () -> {
            store.clearAll();
            a.toast("All calibrations cleared.");
            refresh[0].run();
        }));
        refresh[0].run();
        dialog.show();
    }

    private void captureRow(LinearLayout parent, IconCalibrationStore store, IconCalibrationStore.Capture c, Runnable refresh) {
        a.gap(parent, 8);
        LinearLayout r = a.row();
        ImageView thumb = new ImageView(a);
        Bitmap bitmap = ArrowIcons.toBitmap(c.pixels);
        if (bitmap != null) {
            BitmapDrawable drawable = new BitmapDrawable(a.getResources(), bitmap);
            drawable.setFilterBitmap(false);
            thumb.setImageDrawable(drawable);
        }
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        thumb.setBackground(a.shape(THUMB_BACKGROUND, 8, true));
        thumb.setContentDescription(fmt("Unrecognised arrow from %s", appName(c.pkg)));
        r.addView(thumb, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
        String suggestion = c.suggestion != null && c.suggestion != TurnIcon.UNKNOWN ? fmt("Suggested: %s", c.suggestion.name()) : tr("No suggestion");
        LinearLayout info = copy(appName(c.pkg), suggestion + "\n" + fmt("Seen %d times", c.count));
        info.setPadding(a.dp(12), 0, 0, 0);
        a.grow(r, info);
        parent.addView(r);
        Button assign = action(parent, "Assign symbol", false, () -> assign(store, c, refresh));
        assign.setContentDescription(fmt("Assign symbol for arrow from %s", appName(c.pkg)));
    }

    private void assign(IconCalibrationStore store, IconCalibrationStore.Capture c, Runnable refresh) {
        List<TurnIcon> icons = new ArrayList<>();
        for (TurnIcon icon : TurnIcon.values()) if (icon != TurnIcon.UNKNOWN) icons.add(icon);
        String[] labels = new String[icons.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = icons.get(i).name();
            if (icons.get(i) == c.suggestion) checked = i;
        }
        new AlertDialog.Builder(a).setTitle(tr("Assign dashboard symbol"))
            .setSingleChoiceItems(labels, checked, (d, n) -> {
                d.dismiss();
                if (store.label(c.hash, icons.get(n))) a.toast(fmt("Arrow saved as %s.", icons.get(n).name()));
                else a.toast("Could not save this calibration.");
                refresh.run();
            }).setNegativeButton(tr("Cancel"), null).show();
    }
}
