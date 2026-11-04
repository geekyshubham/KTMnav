package app.ktmnav;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;

abstract class FeatureScreen {
    static final long REFRESH_MS = 1000L;
    final MainActivity a;

    FeatureScreen(MainActivity activity) {
        a = activity;
    }

    String tr(String english) {
        return a.tr(english);
    }

    String fmt(String englishFormat, Object... args) {
        return String.format(Locale.getDefault(), tr(englishFormat), args);
    }

    boolean largeType() {
        return a.getResources().getConfiguration().fontScale >= 1.5f;
    }

    LinearLayout body() {
        LinearLayout b = a.column();
        b.setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(16));
        return b;
    }

    AlertDialog sheet(String title, LinearLayout body) {
        ScrollView scroller = new ScrollView(a);
        scroller.addView(body);
        return new AlertDialog.Builder(a).setTitle(tr(title)).setView(scroller).setPositiveButton(tr("Close"), null).create();
    }

    void showLive(AlertDialog dialog, Runnable refresh) {
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dialog.isShowing()) return;
            refresh.run();
            a.handler.postDelayed(tick[0], REFRESH_MS);
        };
        dialog.setOnShowListener(d -> tick[0].run());
        dialog.setOnDismissListener(d -> a.handler.removeCallbacks(tick[0]));
        dialog.show();
    }

    TextView title(LinearLayout parent, String english) {
        TextView v = a.label(english, 21, a.text, true);
        parent.addView(v);
        a.gap(parent, 8);
        return v;
    }

    TextView note(LinearLayout parent, String english) {
        TextView v = a.label(english, 14, a.muted, false);
        parent.addView(v);
        return v;
    }

    TextView status(LinearLayout parent) {
        TextView v = a.rawLabel("", 15, a.text, false);
        v.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(v);
        return v;
    }

    TextView error(LinearLayout parent) {
        TextView v = a.rawLabel("", 14, a.danger, false);
        v.setVisibility(View.GONE);
        v.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        parent.addView(v);
        return v;
    }

    Button action(LinearLayout parent, String english, boolean primary, Runnable run) {
        a.gap(parent, 8);
        Button b = a.button(english, primary, run);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    LinearLayout pair(LinearLayout parent, Button left, Button right) {
        a.gap(parent, 8);
        boolean stack = largeType();
        LinearLayout r = stack ? a.column() : a.row();
        if (stack) {
            r.addView(left, new LinearLayout.LayoutParams(-1, -2));
            a.gap(r, 8);
            r.addView(right, new LinearLayout.LayoutParams(-1, -2));
        } else {
            a.grow(r, left);
            r.addView(new View(a), new LinearLayout.LayoutParams(a.dp(8), 1));
            a.grow(r, right);
        }
        parent.addView(r);
        return r;
    }

    LinearLayout itemRow(LinearLayout parent, View copy, Button button) {
        a.gap(parent, 8);
        boolean stack = largeType() || a.getResources().getConfiguration().screenWidthDp < 340;
        LinearLayout r = stack ? a.column() : a.row();
        if (stack) {
            r.addView(copy);
            a.gap(r, 8);
            r.addView(button, new LinearLayout.LayoutParams(-1, -2));
        } else {
            a.grow(r, copy);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.leftMargin = a.dp(8);
            r.addView(button, p);
        }
        parent.addView(r);
        return r;
    }

    LinearLayout copy(String rawTitle, String rawSubtitle) {
        LinearLayout c = a.column();
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(a.dp(48));
        c.addView(a.rawLabel(rawTitle, 16, a.text, true));
        if (rawSubtitle != null && !rawSubtitle.isEmpty()) c.addView(a.rawLabel(rawSubtitle, 13, a.muted, false));
        return c;
    }

    Switch switchRow(LinearLayout parent, String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout r = a.row();
        r.setMinimumHeight(a.dp(56));
        LinearLayout c = a.column();
        c.setPadding(0, a.dp(8), a.dp(12), a.dp(8));
        c.addView(a.label(title, 16, a.text, false));
        if (subtitle != null) c.addView(a.label(subtitle, 13, a.muted, false));
        a.grow(r, c);
        Switch s = new Switch(a);
        s.setMinHeight(a.dp(48));
        s.setMinWidth(a.dp(48));
        s.setContentDescription(tr(title));
        s.setChecked(checked);
        s.setOnCheckedChangeListener(listener);
        r.setBackground(a.ripple(null, 12));
        r.setOnClickListener(v -> s.toggle());
        r.addView(s);
        parent.addView(r);
        return s;
    }

    LinearLayout readyRow(LinearLayout parent, String english) {
        LinearLayout r = a.row();
        r.setMinimumHeight(a.dp(40));
        r.addView(a.icon(R.drawable.ic_check_circle, a.success, 20), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        TextView t = a.label(english, 15, a.text, false);
        t.setPadding(a.dp(10), 0, 0, 0);
        a.grow(r, t);
        r.setVisibility(View.GONE);
        parent.addView(r);
        return r;
    }

    static void visible(View v, boolean visible) {
        int target = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != target) v.setVisibility(target);
    }

    Switch prefSwitch(LinearLayout parent, String title, String subtitle, String key, boolean fallback, Runnable after) {
        return switchRow(parent, title, subtitle, a.prefs().getBoolean(key, fallback), (v, on) -> {
            a.prefs().edit().putBoolean(key, on).apply();
            a.bridge.settingsChanged();
            if (after != null) after.run();
        });
    }

    void confirm(String title, String message, String positive, Runnable run) {
        new AlertDialog.Builder(a).setTitle(tr(title)).setMessage(tr(message))
            .setNegativeButton(tr("Cancel"), null)
            .setPositiveButton(tr(positive), (d, w) -> run.run()).show();
    }

    boolean installed(String pkg) {
        try {
            a.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    String appName(String pkg) {
        if (pkg == null || "*".equals(pkg)) return tr("Any app");
        try {
            return a.getPackageManager().getApplicationLabel(a.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
    }

    static void setText(TextView v, String value) {
        if (!value.contentEquals(v.getText())) v.setText(value);
    }

    void mono(TextView v) {
        v.setTypeface(Typeface.MONOSPACE);
    }
}
