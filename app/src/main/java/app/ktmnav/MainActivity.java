package app.ktmnav;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import app.ktmnav.backend.BikeCatalog;
import app.ktmnav.backend.Bridge;
import app.ktmnav.backend.HandlebarMappings;
import app.ktmnav.backend.HandlebarController;
import app.ktmnav.backend.MessageMirror;
import app.ktmnav.backend.NavApps;
import app.ktmnav.backend.NavDestination;
import app.ktmnav.backend.NavHistory;
import app.ktmnav.backend.NavLauncher;
import app.ktmnav.backend.obd.ObdManager;
import app.ktmnav.backend.ride.RouteStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity implements Bridge.Listener {
    Bridge bridge;
    final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root, content;
    private ScrollView scroll;
    private TextView statusText, distanceView, roadView, sourceView, etaView, remainingView, guidanceHint, bikeStatus, liveTag;
    private View statusDot, statusChip, activePanel, idlePanel, nowForwarding;
    private ImageView sourceIcon, nowForwardingIcon;
    private TextView nowForwardingName;
    private DashView dash;
    private Button connectButton, scanButton;
    private ProgressBar scanProgress;
    private String renderedBikes = "", renderedSource = "";
    private int tab;
    private boolean preview, scanning, showAll, resumed, addMode;
    private long scanGeneration;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private final Map<String, FoundBike> discovered = new LinkedHashMap<>();
    private LinearLayout discoveredList;
    private TextView scanStatus;
    int bg, surface, raised, tile, text, muted, line, divider, accent, onAccent, accentText, accentIcon, danger, success;
    Typeface display, medium;
    static final int GUTTER = 16, RADIUS_CARD = 16, RADIUS_TILE = 12, ROW_MIN = 56, TILE = 40, ICON = 24;
    static final int REQUEST_BLUETOOTH = 20, REQUEST_ENABLE_BT = 21, REQUEST_COMAPS = 22, REQUEST_LOCATION = 23, REQUEST_PHONE = 24, REQUEST_NOTIFICATIONS = 25, REQUEST_IMPORT_GPX = 26;
    private final String[] tabs = {"Ride", "Garage", "Apps", "Settings"};
    private final int[] tabIcons = {R.drawable.ic_navigation, R.drawable.ic_two_wheeler, R.drawable.ic_apps, R.drawable.ic_settings};
    private NavDestination pendingDestination;
    private EditText whereField;
    private Button openNavButton;

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent); }

    @Override public void onCreate(Bundle state) {
        String initialTheme = getSharedPreferences("ktmnav", MODE_PRIVATE).getString("theme", "KTM");
        setTheme("LIGHT".equals(initialTheme) ? R.style.AppThemeLight : "HUSQVARNA".equals(initialTheme) ? R.style.AppThemeHusqvarna : R.style.AppTheme);
        super.onCreate(state);
        bridge = Bridge.get(this);
        if (state != null) { tab = state.getInt("tab"); preview = state.getBoolean("preview"); addMode = state.getBoolean("addMode"); }
        if (getIntent().getData() != null && "settings".equals(getIntent().getData().getHost())) tab = 3;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        display = Typeface.createFromAsset(getAssets(), "fonts/chakra_petch_semibold.ttf");
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(discoveryReceiver, filter);
        render();
        if (state == null && !prefs().getBoolean("welcome_complete", false)) handler.post(this::welcome);
        if (state == null) handleIncoming(getIntent());
    }

    /** Places shared to KTMnav and geo: links fill the Navigate card; GPX files become imported routes. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        NavDestination shared = NavLauncher.fromIntent(intent);
        if (shared != null) {
            pendingDestination = shared; preview = false; tab = 0; addMode = false;
            if (root != null) render();
            toast("Destination received. Tap Go to start guidance.");
            return;
        }
        Uri gpx = gpxUri(intent);
        if (gpx != null) importRoute(gpx, displayName(gpx));
    }
    private Uri gpxUri(Intent intent) {
        Uri uri = Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (uri == null || !(Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction()))) return null;
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) return null;
        String type = intent.getType(); String name = displayName(uri);
        boolean gpxType = type != null && (type.contains("gpx") || type.contains("xml") || type.equals("application/octet-stream"));
        boolean gpxName = name != null && name.toLowerCase(Locale.ROOT).endsWith(".gpx");
        return gpxType || gpxName ? uri : null;
    }
    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null && !n.isEmpty()) return n; }
        } catch (RuntimeException ignored) {}
        return uri.getLastPathSegment();
    }
    void importRoute(Uri uri, String name) {
        RouteStore store = new RouteStore(getFilesDir());
        new Thread(() -> {
            String outcome; RouteStore.Route route = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                route = store.importGpx(in, name != null ? name : "route");
                outcome = null;
            } catch (IOException | RuntimeException e) { outcome = e.getMessage() != null ? e.getMessage() : "Could not read this file."; }
            final String error = outcome; final RouteStore.Route imported = route;
            handler.post(() -> {
                if (error != null) { toast("Route not imported: " + tr(error)); return; }
                toast("Route imported: " + imported.title);
                new RidesScreen(this).showRoute(imported);
            });
        }, "ktmnav-route-import").start();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("tab", tab); out.putBoolean("preview", preview); out.putBoolean("addMode", addMode); }
    @Override protected void onResume() { super.onResume(); resumed = true; bridge.addListener(this); if (root != null) render(); }
    @Override protected void onPause() { resumed = false; bridge.removeListener(this); stopScan(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); try { unregisterReceiver(discoveryReceiver); } catch (IllegalArgumentException ignored) {} super.onDestroy(); }
    @Override public void onChanged() {
        handler.post(() -> {
            if (!resumed) return;
            rememberSource();
            updateStatusChip();
            if (tab == 0) updateRide();
            if (tab == 1 && !scanning && !renderedBikes.equals(bikeSnapshot())) render();
            if (tab == 2) { if (!renderedSource.equals(activeSource())) render(); }
        });
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (tab == 1 && addMode && !bridge.getBikes().isEmpty()) { stopScan(); addMode = false; render(); }
        else if (tab != 0) { switchTab(0); }
        else if (preview) { preview = false; render(); }
        else finishAfterTransition();
    }
    private void switchTab(int index) { stopScan(); if (index != 1) addMode = false; if (scroll != null) scroll.scrollTo(0, 0); tab = index; render(); }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    SharedPreferences prefs() { return bridge.getPrefs(); }
    boolean daylight() { return "LIGHT".equals(prefs().getString("theme", "KTM")); }
    private void palette() {
        String theme = prefs().getString("theme", "KTM");
        boolean light = "LIGHT".equals(theme), husqvarna = "HUSQVARNA".equals(theme);
        bg = Color.parseColor(light ? "#F4F5F6" : "#101214");
        surface = Color.parseColor(light ? "#FFFFFF" : "#191D20");
        raised = Color.parseColor(light ? "#E8ECEF" : "#22272B");
        text = Color.parseColor(light ? "#171B1E" : "#F2F4F5");
        muted = Color.parseColor(light ? "#4D5B65" : "#AAB5BC");
        line = Color.parseColor(light ? "#75848E" : "#71808A");
        divider = Color.parseColor(light ? "#DDE2E6" : "#2A3035");
        accent = Color.parseColor(husqvarna ? "#8AB4F8" : "#FF6600");
        onAccent = Color.parseColor("#101214");
        accentText = Color.parseColor(husqvarna ? "#8AB4F8" : light ? "#C24E00" : "#FF6600");
        accentIcon = Color.parseColor(husqvarna ? "#8AB4F8" : light ? "#E65C00" : "#FF6600");
        tile = Color.parseColor(light ? "#FFF1E8" : "#22272B");
        danger = Color.parseColor(light ? "#B3261E" : "#FFB4AB");
        success = Color.parseColor(light ? "#1E7B3A" : "#7DD99B");
    }
    GradientDrawable shape(int color, float radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1), line); return d;
    }
    RippleDrawable ripple(Drawable content, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x337F7F7F), content, content == null ? shape(Color.WHITE, radius, false) : null);
    }
    String tr(String value) { return UiText.translate(this, value); }
    TextView rawLabel(String value, int size, int color, boolean bold) { TextView v = label("", size, color, bold); v.setText(value); return v; }
    TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(tr(value)); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("tnum"); v.setLineSpacing(dp(2), 1);
        if (bold) v.setTypeface(size >= 20 ? display : medium); return v;
    }
    LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    void grow(LinearLayout parent, View v) { parent.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); }
    boolean largeType() { return getResources().getConfiguration().fontScale >= 1.5f; }
    LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(16), dp(16), dp(16), dp(16)); c.setBackground(shape(surface, RADIUS_CARD, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); parent.addView(c, p); return c;
    }
    ImageView icon(int res, int color, int size) {
        ImageView v = new ImageView(this); v.setImageResource(res); v.setImageTintList(ColorStateList.valueOf(color));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); v.setMinimumWidth(dp(size)); v.setMinimumHeight(dp(size)); return v;
    }
    FrameLayout iconTile(int res) {
        FrameLayout f = new FrameLayout(this); f.setId(R.id.row_icon); f.setBackground(shape(tile, RADIUS_TILE, false));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        f.addView(icon(res, accentIcon, ICON), new FrameLayout.LayoutParams(dp(ICON), dp(ICON), Gravity.CENTER)); return f;
    }
    Button button(String title, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(tr(title)); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(medium);
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{muted, primary ? onAccent : text}));
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setStateListAnimator(null); b.setElevation(0);
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled}, outline(divider));
        fill.addState(new int[]{}, primary ? shape(accent, 24, false) : outline(line));
        b.setBackground(ripple(fill, 24));
        b.setOnClickListener(v -> { if (action != null) action.run(); });
        if (primary) b.setId(R.id.primary_action);
        return b;
    }
    Button withIcon(Button b, int res) {
        Drawable d = getDrawable(res).mutate(); d.setTint(b.getCurrentTextColor()); d.setBounds(0, 0, dp(20), dp(20));
        b.setCompoundDrawablesRelative(d, null, null, null); b.setCompoundDrawablePadding(dp(8)); return b;
    }
    GradientDrawable outline(int stroke) { GradientDrawable d = shape(Color.TRANSPARENT, 24, false); d.setStroke(Math.max(1, dp(1)), stroke); return d; }
    Button textButton(String title, int iconRes, Runnable action) { return textButton(title, iconRes, accentText, action); }
    Button textButton(String title, int iconRes, int color, Runnable action) {
        Button b = new Button(this); b.setText(tr(title)); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(medium); b.setTextColor(color);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48)); b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null); b.setElevation(0); b.setBackground(ripple(null, 24));
        if (iconRes != 0) {
            Drawable d = getDrawable(iconRes).mutate(); d.setTint(color); d.setBounds(0, 0, dp(18), dp(18));
            b.setCompoundDrawablesRelative(d, null, null, null); b.setCompoundDrawablePadding(dp(6));
        }
        b.setOnClickListener(v -> action.run()); return b;
    }
    void fullButton(LinearLayout parent, String title, boolean primary, Runnable action) { parent.addView(button(title, primary, action), new LinearLayout.LayoutParams(-1, -2)); }
    TextView chip(String value, int color) {
        TextView c = label(value, 12, color, true); c.setPadding(dp(10), dp(4), dp(10), dp(4)); c.setBackground(shape(raised, 12, false)); c.setSingleLine(true); return c;
    }
    void section(LinearLayout parent, String title) {
        TextView h = label(title, 13, muted, true); h.setLetterSpacing(.03f); h.setPadding(dp(4), dp(16), dp(4), dp(8)); h.setAccessibilityHeading(true); parent.addView(h);
    }
    LinearLayout group(LinearLayout parent) {
        LinearLayout g = column(); g.setBackground(shape(surface, RADIUS_CARD, false)); g.setClipToOutline(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8); parent.addView(g, p); return g;
    }
    LinearLayout listRow(LinearLayout group, View lead, String title, String summary) {
        if (group.getChildCount() > 0) {
            View d = new View(this); d.setBackgroundColor(divider); d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))); p.setMarginStart(dp(lead == null ? 16 : 72)); group.addView(d, p);
        }
        LinearLayout r = row(); r.setId(R.id.settings_row); r.setMinimumHeight(dp(ROW_MIN)); r.setPadding(dp(16), dp(10), dp(12), dp(10));
        if (lead != null) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE), dp(TILE)); lp.setMarginEnd(dp(16)); r.addView(lead, lp); }
        LinearLayout copy = column(); copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = rawLabel(title, 16, text, false); copy.addView(t);
        if (summary != null && !summary.isEmpty()) { TextView s = rawLabel(summary, 13, muted, false); s.setMaxLines(largeType() ? 4 : 2); s.setEllipsize(TextUtils.TruncateAt.END); copy.addView(s); }
        grow(r, copy); group.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r;
    }
    LinearLayout navRow(LinearLayout group, int iconRes, String title, String summary, Runnable action) {
        LinearLayout r = listRow(group, iconRes == 0 ? null : iconTile(iconRes), tr(title), summary);
        ImageView chevron = icon(R.drawable.ic_chevron_right, muted, ICON); r.addView(chevron, new LinearLayout.LayoutParams(dp(ICON), dp(ICON)));
        r.setBackground(ripple(null, 0)); r.setOnClickListener(v -> action.run()); asButton(r); return r;
    }
    Switch switchRow(LinearLayout group, int iconRes, String title, String summary, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout r = listRow(group, iconRes == 0 ? null : iconTile(iconRes), tr(title), summary == null ? null : tr(summary));
        Switch s = new Switch(this); s.setChecked(checked); s.setClickable(false); s.setFocusable(false); s.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.setMarginStart(dp(8)); r.addView(s, sp);
        s.setOnCheckedChangeListener(listener);
        r.setContentDescription(tr(title)); r.setBackground(ripple(null, 0)); r.setOnClickListener(v -> s.toggle());
        String hint = summary == null ? null : tr(summary);
        r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Switch.class.getName()); info.setCheckable(true); info.setChecked(s.isChecked());
                if (hint != null) info.setHintText(hint);
            }
        });
        return s;
    }
    Switch prefRow(LinearLayout group, int iconRes, String title, String summary, String key, boolean fallback) {
        return switchRow(group, iconRes, title, summary, prefs().getBoolean(key, fallback), (v, on) -> { prefs().edit().putBoolean(key, on).apply(); bridge.settingsChanged(); });
    }
    void asButton(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); } });
    }
    private String bikeSnapshot() { return bridge.getBikes().toString(); }

    private void render() {
        renderedBikes = bikeSnapshot(); renderedSource = activeSource();
        int offset = scroll == null ? 0 : scroll.getScrollY();
        palette(); getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(surface);
        root = column(); root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        int width = getResources().getConfiguration().screenWidthDp;
        int gutter = Math.max(GUTTER, (width - 720) / 2);
        LinearLayout header = largeType() ? column() : row(); header.setPadding(dp(gutter), dp(12), dp(gutter), dp(8));
        TextView title = label(tabs[tab], 24, text, true); title.setId(R.id.tab_title); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); title.setAccessibilityHeading(true);
        if (largeType()) { header.addView(title); gap(header, 6); } else grow(header, title);
        LinearLayout chip = row(); chip.setId(R.id.status_chip); chip.setPadding(dp(12), dp(6), dp(12), dp(6)); chip.setMinimumHeight(dp(32)); chip.setBackground(shape(raised, 16, false));
        statusDot = new View(this); chip.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusText = label("", 13, text, true); statusText.setSingleLine(true); statusText.setEllipsize(TextUtils.TruncateAt.END); statusText.setPadding(dp(8), 0, 0, 0);
        statusText.setMaxWidth(dp(Math.max(160, width / 2))); chip.addView(statusText);
        chip.setFocusable(true); chip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); statusChip = chip;
        header.addView(chip, new LinearLayout.LayoutParams(-2, -2)); root.addView(header);
        updateStatusChip();
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = column(); content.setPadding(dp(gutter), dp(4), dp(gutter), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab == 0) ride(); else if (tab == 1) garage(); else if (tab == 2) navigationApps(); else settings();
        View rule = new View(this); rule.setBackgroundColor(divider); root.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        LinearLayout nav = largeType() ? column() : row(); nav.setPadding(dp(8), dp(6), dp(8), dp(6)); nav.setBackgroundColor(surface);
        LinearLayout navRow = nav;
        for (int n = 0; n < tabs.length; n++) {
            if (largeType() && n % 2 == 0) { navRow = row(); nav.addView(navRow); }
            final int index = n; boolean selected = n == tab;
            LinearLayout item = largeType() ? row() : column(); item.setId(R.id.nav_tab); item.setGravity(Gravity.CENTER); item.setPadding(dp(2), dp(4), dp(2), dp(4)); item.setMinimumHeight(dp(56));
            item.setBackground(ripple(null, 16));
            FrameLayout pill = new FrameLayout(this); pill.setBackground(shape(selected ? accent : Color.TRANSPARENT, 16, false));
            pill.addView(icon(tabIcons[n], selected ? onAccent : muted, ICON), new FrameLayout.LayoutParams(dp(ICON), dp(ICON), Gravity.CENTER));
            pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            item.addView(pill, new LinearLayout.LayoutParams(dp(56), dp(32)));
            TextView name = label(tabs[n], 12, selected ? text : muted, selected); name.setSingleLine(true); name.setGravity(Gravity.CENTER); name.setPadding(dp(4), dp(4), dp(4), 0); item.addView(name);
            item.setContentDescription(tr(tabs[n])); item.setSelected(selected); item.setFocusable(true);
            item.setOnClickListener(v -> switchTab(index));
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); info.setSelected(selected); } });
            navRow.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        if (offset > 0) scroll.post(() -> scroll.scrollTo(0, offset));
    }
    private void updateStatusChip() {
        if (statusText == null) return;
        String state = bridge.getState(); Bridge.Bike bike = bridge.getActiveBike(); String value; int dot;
        if (bridge.isConnected()) { value = tr("Connected") + (bike == null ? "" : " · " + bike.name); dot = success; }
        else if ("Connecting".equals(state) || "Authenticating".equals(state)) { value = tr(state) + "…"; dot = accentIcon; }
        else if ("Error".equals(state)) { value = tr("Error"); dot = danger; }
        else if (bike == null) { value = tr("No motorcycle"); dot = muted; }
        else { value = tr("Not connected"); dot = muted; }
        if (!value.contentEquals(statusText.getText())) statusText.setText(value);
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(dot); statusDot.setBackground(d);
        statusChip.setContentDescription(tr("Connection") + ": " + value);
    }

    int bikeIconRes(Bridge.Bike bike) { return bikeIconRes(bike == null ? BikeCatalog.Style.GENERIC : bike.getStyle()); }
    static int bikeIconRes(BikeCatalog.Style style) {
        switch (style) {
            case NAKED: return R.drawable.ic_bike_naked;
            case ADVENTURE: return R.drawable.ic_bike_adventure;
            case SPORT: return R.drawable.ic_bike_sport;
            case ENDURO: return R.drawable.ic_bike_enduro;
            case SUPERMOTO: return R.drawable.ic_bike_supermoto;
            case TOURING: return R.drawable.ic_bike_touring;
            case SCRAMBLER: return R.drawable.ic_bike_scrambler;
            case CAFE: return R.drawable.ic_bike_cafe;
            default: return R.drawable.ic_two_wheeler;
        }
    }
    FrameLayout bikeTile(Bridge.Bike bike, int size) {
        FrameLayout f = new FrameLayout(this); f.setId(R.id.bike_icon); f.setBackground(shape(tile, RADIUS_TILE, false));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int inner = Math.round(size * 0.8f);
        f.addView(icon(bikeIconRes(bike), accentIcon, inner), new FrameLayout.LayoutParams(dp(inner), dp(inner), Gravity.CENTER)); return f;
    }
    String bikeSummary(Bridge.Bike bike) {
        String gen = "GEN2".equals(bike.generation) ? "Gen 2" : "Gen 3";
        return bike.model != null ? bike.model + " · " + gen : gen;
    }

    private void ride() {
        Bridge.Bike active = bridge.getActiveBike();
        LinearLayout bike = card(content);
        LinearLayout head = row(); head.addView(bikeTile(active, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column(); copy.setPadding(dp(16), 0, 0, 0);
        TextView name = active == null ? label("No motorcycle yet", 18, text, true) : rawLabel(active.name, 18, text, true); name.setMaxLines(2); copy.addView(name);
        bikeStatus = label("", 13, muted, false); bikeStatus.setMaxLines(2); bikeStatus.setEllipsize(TextUtils.TruncateAt.END); copy.addView(bikeStatus);
        grow(head, copy); bike.addView(head); gap(bike, 16);
        connectButton = button(active == null ? "Add motorcycle" : "Connect", true, () -> {
            if (bridge.getActiveBike() == null) { goAddBike(); return; }
            if (bridge.isConnected() || "Connecting".equals(bridge.getState()) || "Authenticating".equals(bridge.getState())) bridge.disconnect();
            else withBluetooth(() -> { preview = false; bridge.connect(); render(); });
        });
        bike.addView(connectButton, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = card(content);
        LinearLayout active1 = column(); activePanel = active1; panel.addView(active1);
        LinearLayout caption = row(); caption.setMinimumHeight(dp(32));
        sourceIcon = new ImageView(this); sourceIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(ICON), dp(ICON)); ip.setMarginEnd(dp(10)); caption.addView(sourceIcon, ip);
        sourceView = label("", 15, text, true); sourceView.setSingleLine(true); sourceView.setEllipsize(TextUtils.TruncateAt.END); grow(caption, sourceView);
        liveTag = chip("", text); caption.addView(liveTag); active1.addView(caption);
        dash = new DashView(); active1.addView(dash, new LinearLayout.LayoutParams(-1, dp(136)));
        distanceView = label("—", 44, text, true); distanceView.setGravity(Gravity.CENTER); active1.addView(distanceView);
        roadView = label("", 18, text, true); roadView.setGravity(Gravity.CENTER); active1.addView(roadView); gap(active1, 4);
        guidanceHint = label("", 13, muted, false); guidanceHint.setGravity(Gravity.CENTER); guidanceHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); active1.addView(guidanceHint);
        gap(active1, 16); View rule = new View(this); rule.setBackgroundColor(divider); active1.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)))); gap(active1, 12);
        LinearLayout stats = row(); LinearLayout a = column(), b = column();
        a.addView(label("ARRIVAL", 11, muted, true)); etaView = label("—", 22, text, true); a.addView(etaView);
        b.addView(label("REMAINING", 11, muted, true)); remainingView = label("—", 22, text, true); b.addView(remainingView);
        grow(stats, a); grow(stats, b); active1.addView(stats);

        LinearLayout idle = column(); idlePanel = idle; idle.setGravity(Gravity.CENTER_HORIZONTAL); panel.addView(idle);
        idle.addView(iconTile(R.drawable.ic_route), new LinearLayout.LayoutParams(dp(48), dp(48))); gap(idle, 12);
        TextView none = label("No active route", 18, text, true); none.setGravity(Gravity.CENTER); idle.addView(none);
        TextView hint = label("Type a destination below or start navigation in your app.", 14, muted, false); hint.setGravity(Gravity.CENTER); idle.addView(hint); gap(idle, 16);
        navigateCard(idle);

        LinearLayout actions = column(); actions.setGravity(Gravity.CENTER_HORIZONTAL);
        openNavButton = textButton("Open navigation app", R.drawable.ic_map, () -> {
            NavApps.App app = NavApps.byName(bridge.getSource()); String pkg = app != null ? installedVariant(app.canonicalPackage()) : null;
            if (pkg != null) openApp(pkg, true);
        });
        openNavButton.setVisibility(View.GONE); actions.addView(openNavButton, new LinearLayout.LayoutParams(-2, -2));
        actions.addView(textButton(preview ? "Exit dashboard preview" : "Preview dashboard", R.drawable.ic_visibility, () -> { preview = !preview; render(); }), new LinearLayout.LayoutParams(-2, -2));
        if (bridge.isConnected()) actions.addView(textButton("Send test turn to dashboard", R.drawable.ic_turn_right, () -> new AlertDialog.Builder(this).setTitle(tr("Send a test turn?")).setMessage(tr("This sends an example turn to the connected motorcycle. Use it while parked.")).setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Send test"), (d, w) -> bridge.sendTestGuidance()).show()), new LinearLayout.LayoutParams(-2, -2));
        gap(panel, 4); panel.addView(actions);
        if (showRideSetup()) setupCard(content, true);
        updateRide();
    }
    String value(String s, String fallback) { return s == null || s.trim().isEmpty() ? fallback : s; }
    private boolean guiding() { return bridge.getDistance() != null || bridge.getRoad() != null; }
    private String activeSource() { String s = bridge.getSource(); return guiding() && s != null && !"DASHBOARD".equals(s) ? s : ""; }
    private void rememberSource() {
        String s = activeSource();
        if (!s.isEmpty() && appByName(s) != null && !s.equals(prefs().getString("ui_last_source", ""))) prefs().edit().putString("ui_last_source", s).apply();
    }
    private void updateRide() {
        if (distanceView == null || tab != 0) return;
        boolean guiding = guiding(), show = preview || guiding;
        activePanel.setVisibility(show ? View.VISIBLE : View.GONE); idlePanel.setVisibility(show ? View.GONE : View.VISIBLE);
        String source = preview ? "Google Maps" : value(bridge.getSource(), "DASHBOARD");
        sourceView.setText("DASHBOARD".equals(source) ? tr("Navigation") : source);
        Drawable d = sourceDrawable(source); sourceIcon.setImageDrawable(d); sourceIcon.setVisibility(d == null ? View.GONE : View.VISIBLE);
        liveTag.setText(tr(preview ? "Preview" : "Live"));
        distanceView.setText(preview ? "250 m" : value(bridge.getDistance(), "—"));
        roadView.setText(preview ? "Turn right onto Lakeside Road" : value(bridge.getRoad(), ""));
        etaView.setText(preview ? "10:42" : value(bridge.getEta(), "—"));
        remainingView.setText(preview ? "12.4 km" : value(bridge.getRemaining(), "—"));
        guidanceHint.setText(tr(preview ? "Preview only · no data sent to your bike" : bridge.isConnected() ? "Forwarding to your motorcycle" : "Navigation received · connect your motorcycle"));
        dash.turn = preview ? "RIGHT" : value(bridge.getTurn(), "IDLE"); dash.setContentDescription(tr("Dashboard direction: ") + dash.turn.toLowerCase(Locale.ROOT).replace('_', ' ')); dash.invalidate();
        if (openNavButton != null) {
            NavApps.App sourceApp = guiding && !preview ? NavApps.byName(source) : null;
            boolean canOpen = sourceApp != null && installedVariant(sourceApp.canonicalPackage()) != null;
            if (canOpen) setTextIfChanged(openNavButton, tr("Open ") + sourceApp.name);
            openNavButton.setVisibility(canOpen ? View.VISIBLE : View.GONE);
        }
        Bridge.Bike active = bridge.getActiveBike();
        String detail = tr(value(bridge.getStatusDetail(), bridge.getState()));
        bikeStatus.setText(active == null ? tr("Add one in Garage") : bikeSummary(active) + " · " + detail);
        connectButton.setText(tr(active == null ? "Add motorcycle" : bridge.isConnected() ? "Disconnect" : "Connecting".equals(bridge.getState()) || "Authenticating".equals(bridge.getState()) ? "Cancel connection" : "Connect"));
        updateStatusChip();
    }

    static final class SetupItem {
        final String id, title, summary, action; final int icon; final Runnable fix;
        SetupItem(String id, int icon, String title, String summary, String action, Runnable fix) { this.id = id; this.icon = icon; this.title = title; this.summary = summary; this.action = action; this.fix = fix; }
    }
    boolean adapterOn() { try { return adapter != null && adapter.isEnabled(); } catch (SecurityException e) { return true; } }
    boolean postNotificationsAllowed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean enabled = nm == null || nm.areNotificationsEnabled();
        return Build.VERSION.SDK_INT >= 33 ? granted(Manifest.permission.POST_NOTIFICATIONS) && enabled : enabled;
    }
    boolean batteryExempt() { PowerManager pm = getSystemService(PowerManager.class); return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName()); }
    List<SetupItem> setupItems() {
        List<SetupItem> items = new ArrayList<>();
        if (adapter != null && !btAllowed()) items.add(new SetupItem("nearby", R.drawable.ic_bluetooth, "Nearby devices", "To find and connect your motorcycle", "Allow", () -> withBluetooth(this::render)));
        else if (adapter != null && !adapterOn()) items.add(new SetupItem("bluetooth", R.drawable.ic_bluetooth_disabled, "Bluetooth is off", "Needed to connect", "Turn on", () -> withBluetooth(this::render)));
        if (!notificationAccess()) items.add(new SetupItem("access", R.drawable.ic_notifications, "Notification access", "To forward navigation", "Allow", this::openNotificationAccess));
        if (!postNotificationsAllowed()) items.add(new SetupItem("notify", R.drawable.ic_notifications_active, "Notifications", "Shows the connection status", "Allow", this::requestPostNotifications));
        if (!batteryExempt()) items.add(new SetupItem("battery", R.drawable.ic_battery_full, "Background activity", "Keeps the connection alive", "Allow", this::requestBatteryExemption));
        if (bridge.getBikes().isEmpty()) items.add(new SetupItem("bike", R.drawable.ic_two_wheeler, "Add your motorcycle", "Pair it in Garage", "Add", this::goAddBike));
        return items;
    }
    private String setupIds(List<SetupItem> items) { StringBuilder s = new StringBuilder(); for (SetupItem i : items) s.append(i.id).append(','); return s.toString(); }
    private boolean showRideSetup() {
        List<SetupItem> items = setupItems(); if (items.isEmpty()) return false;
        String hidden = prefs().getString("ui_ride_setup_hidden", null); if (hidden == null) return true;
        for (SetupItem i : items) if (!hidden.contains(i.id + ",")) return true;
        return false;
    }
    void setupCard(LinearLayout parent, boolean onRide) {
        List<SetupItem> items = setupItems(); if (items.isEmpty()) return;
        LinearLayout g;
        if (onRide) {
            g = group(parent); ((LinearLayout.LayoutParams) g.getLayoutParams()).bottomMargin = dp(12);
            LinearLayout head = row(); head.setPadding(dp(16), dp(8), dp(4), 0);
            TextView t = label("Setup", 16, text, true); t.setAccessibilityHeading(true); grow(head, t);
            head.addView(chip(String.format(Locale.getDefault(), tr("%d to do"), items.size()), text));
            ImageButton close = new ImageButton(this); close.setImageResource(R.drawable.ic_close); close.setImageTintList(ColorStateList.valueOf(muted)); close.setBackground(ripple(null, 24));
            close.setContentDescription(tr("Hide setup")); close.setOnClickListener(v -> { prefs().edit().putString("ui_ride_setup_hidden", setupIds(items)).apply(); render(); });
            head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); g.addView(head);
        } else {
            section(parent, "Setup"); g = group(parent);
        }
        g.setId(R.id.setup_card);
        for (SetupItem item : items) {
            LinearLayout r = listRow(g, iconTile(item.icon), tr(item.title), tr(item.summary)); r.setTag(item.id);
            Button fix = button(item.action, false, item.fix); fix.setId(R.id.setup_item); fix.setMinHeight(dp(40)); fix.setMinimumHeight(dp(40)); fix.setPadding(dp(16), 0, dp(16), 0);
            fix.setContentDescription(tr(item.action) + ": " + tr(item.title));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, dp(48)); fp.setMarginStart(dp(8)); r.addView(fix, fp);
        }
    }
    void openNotificationAccess() {
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, app.ktmnav.backend.NavListener.class).flattenToString());
        try { startActivity(detail); } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (ActivityNotFoundException ignored) { toast("Open notification access in Android settings."); }
        }
    }
    private void requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !prefs().getBoolean("ui_notify_asked", false)) {
            prefs().edit().putBoolean("ui_notify_asked", true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS); return;
        }
        openNotificationSettings();
    }
    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }
    private void requestBatteryExemption() {
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException | SecurityException e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (ActivityNotFoundException ignored) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        }
    }
    private void goAddBike() { tab = 1; addMode = true; if (scroll != null) scroll.scrollTo(0, 0); render(); withBluetooth(this::startScan); }
    private void welcome() {
        new AlertDialog.Builder(this).setTitle(tr("Welcome to KTMnav"))
            .setMessage(tr("Your navigation on your motorcycle’s dashboard. Follow the short setup to get ready."))
            .setPositiveButton(tr("Get started"), (dialog, which) -> { prefs().edit().putBoolean("welcome_complete", true).apply(); tab = 0; render(); })
            .setNeutralButton(tr("App language"), (dialog, which) -> chooseLanguage())
            .setNegativeButton(tr("Later"), (dialog, which) -> prefs().edit().putBoolean("welcome_complete", true).apply()).show();
    }

    private void garage() {
        List<Bridge.Bike> bikes = bridge.getBikes(); Bridge.Bike active = bridge.getActiveBike();
        if (!bikes.isEmpty()) {
            section(content, "Motorcycles"); LinearLayout g = group(content);
            for (Bridge.Bike bike : bikes) {
                boolean selected = active != null && active.address.equals(bike.address);
                LinearLayout r = listRow(g, bikeTile(bike, TILE), bike.name, bikeSummary(bike));
                if (selected) { TextView c = chip("Active", text); Drawable check = getDrawable(R.drawable.ic_check).mutate(); check.setTint(success); check.setBounds(0, 0, dp(14), dp(14)); c.setCompoundDrawablesRelative(check, null, null, null); c.setCompoundDrawablePadding(dp(4)); r.addView(c); }
                r.addView(icon(R.drawable.ic_chevron_right, muted, ICON), new LinearLayout.LayoutParams(dp(ICON), dp(ICON)));
                r.setBackground(ripple(null, 0)); r.setOnClickListener(v -> manageBike(bike)); asButton(r);
                r.setContentDescription(bike.name + ", " + bikeSummary(bike) + ", " + tr(bike.getStyle().getLabel()) + (selected ? ", " + tr("Active") : ""));
            }
            gap(content, 8);
        }
        if (!addMode && !bikes.isEmpty()) { content.addView(withIcon(button("Add motorcycle", true, () -> { addMode = true; render(); withBluetooth(this::startScan); }), R.drawable.ic_add), new LinearLayout.LayoutParams(-1, -2)); return; }
        if (!addMode) {
            LinearLayout empty = card(content); empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(iconTile(R.drawable.ic_two_wheeler), new LinearLayout.LayoutParams(dp(48), dp(48))); gap(empty, 12);
            TextView t = label("No motorcycles yet", 18, text, true); t.setGravity(Gravity.CENTER); empty.addView(t); gap(empty, 16);
            empty.addView(withIcon(button("Add motorcycle", true, () -> { addMode = true; render(); withBluetooth(this::startScan); }), R.drawable.ic_add), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout scan = card(content);
        LinearLayout head = row(); TextView t = label("Nearby motorcycles", 18, text, true); t.setAccessibilityHeading(true); grow(head, t);
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall); scanProgress.setIndeterminate(true); scanProgress.setIndeterminateTintList(ColorStateList.valueOf(accentIcon));
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE); scanProgress.setContentDescription(tr("Scanning"));
        head.addView(scanProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        scanButton = textButton(scanning ? "Stop scanning" : "Scan again", 0, () -> { if (scanning) stopScan(); else withBluetooth(this::startScan); }); head.addView(scanButton);
        scan.addView(head);
        scanStatus = label(scanning ? "Scanning nearby · 12 seconds" : "Turn the ignition on, then scan.", 13, muted, false); scanStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); scan.addView(scanStatus);
        discoveredList = column(); scan.addView(discoveredList); updateDiscovered();
        gap(scan, 8);
        LinearLayout showRow = column(); scan.addView(showRow);
        Switch all = switchRow(showRow, 0, "Show all Bluetooth devices", null, showAll, (v, c) -> { showAll = c; updateDiscovered(); });
        ((View) all.getParent()).setPadding(dp(4), 0, 0, 0);
        LinearLayout links = row(); links.addView(textButton("Pairing help", R.drawable.ic_info, this::pairingHelp));
        if (!bikes.isEmpty()) links.addView(textButton("Cancel", 0, () -> { stopScan(); addMode = false; render(); }));
        scan.addView(links);
    }
    private void pairingHelp() {
        new AlertDialog.Builder(this).setTitle(tr("Pairing help"))
            .setMessage(tr("Gen 3: encrypted Bluetooth LE. Open the dashboard Bluetooth menu for first pairing.\n\nGen 2: Bluetooth Classic. Enable Navigation in the dashboard Bluetooth menu. Close other motorcycle companion apps before connecting.")
                + "\n\n" + tr("Connection tips: ignition on and the dashboard awake; Connectivity or Bluetooth enabled in the dashboard menu; remove old, unused phones from the dashboard’s paired list; accept the pairing prompt on both the phone and the dashboard promptly; allow nearby devices and turn off battery saver for KTMnav."))
            .setNeutralButton(tr("Bluetooth settings"), (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
            .setPositiveButton(tr("Got it"), null).show();
    }
    private void manageBike(Bridge.Bike bike) {
        Bridge.Bike active = bridge.getActiveBike(); boolean selected = active != null && active.address.equals(bike.address);
        new AlertDialog.Builder(this).setTitle(bike.name).setItems(UiText.translate(this, new String[]{selected ? "Connect" : "Use this bike", "Rename", "Motorcycle model", "Set dashboard generation", "Forget motorcycle"}), (d, n) -> {
            if (n == 0) { bridge.selectBike(bike.address); withBluetooth(() -> { bridge.connect(); tab = 0; render(); }); }
            else if (n == 1) editText("Motorcycle name", bike.name, s -> { bridge.renameBike(bike.address, s); render(); });
            else if (n == 2) modelDialog(bike);
            else if (n == 3) generationDialog(bike.address, bike.name, bike.generation);
            else new AlertDialog.Builder(this).setTitle(tr("Forget ") + bike.name + "?").setMessage(tr("This removes its saved connection from KTMnav.")).setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Forget"), (a, b) -> { bridge.forgetBike(bike.address); render(); }).show();
        }).show();
    }
    /** Picks the catalogue model (or just a style) that draws this bike's silhouette; never touches the link. */
    private void modelDialog(Bridge.Bike bike) {
        LinearLayout body = dialogBody();
        body.addView(label("Choose your model, or a style if it is not listed. This only changes the picture.", 14, muted, false));
        AlertDialog[] dialog = new AlertDialog[1];
        java.util.function.Consumer<String> pick = model -> { bridge.setBikeModel(bike.address, model); if (dialog[0] != null) dialog[0].dismiss(); render(); };
        LinearLayout current = dataGroup(body, "Current");
        current.addView(rawLabel(bike.model != null ? bike.model : tr("Guessed from the dashboard name") + " · " + tr(bike.getStyle().getLabel()), 15, text, false));
        if (bike.model != null) { gap(current, 8); current.addView(button("Use the dashboard name", false, () -> pick.accept(null)), new LinearLayout.LayoutParams(-1, -2)); }
        section(body, "Styles"); LinearLayout styles = group(body);
        for (BikeCatalog.Style style : BikeCatalog.Style.values()) {
            if (style == BikeCatalog.Style.GENERIC) continue;
            LinearLayout r = listRow(styles, iconTile(bikeIconRes(style)), tr(style.getLabel()), null);
            r.setBackground(ripple(null, 0)); asButton(r); r.setOnClickListener(v -> pick.accept(style.name()));
        }
        String brand = null; LinearLayout g = null;
        for (BikeCatalog.Model m : BikeCatalog.models()) {
            if (!m.brand.equals(brand)) { brand = m.brand; section(body, brand); g = group(body); }
            LinearLayout r = listRow(g, iconTile(bikeIconRes(m.style)), m.name, tr(m.style.getLabel()));
            r.setBackground(ripple(null, 0)); asButton(r); r.setContentDescription(m.displayName()); r.setOnClickListener(v -> pick.accept(m.displayName()));
        }
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        dialog[0] = new AlertDialog.Builder(this).setTitle(tr("Motorcycle model")).setView(scroller).setNegativeButton(tr("Cancel"), null).create();
        dialog[0].show();
    }
    private void generationDialog(String address, String name, String suggested) {
        int[] choice = {"GEN2".equals(suggested) ? 1 : 0};
        new AlertDialog.Builder(this).setTitle(tr("Choose dashboard generation"))
            .setSingleChoiceItems(UiText.translate(this, new String[]{"Gen 3 · BLE", "Gen 2 · Classic"}), choice[0], (d, n) -> choice[0] = n)
            .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Connect"), (d, w) -> saveFound(address, name, choice[0] == 1 ? "GEN2" : "GEN3")).show();
    }
    private void saveFound(String address, String name, String gen) {
        stopScan();
        withBluetooth(() -> {
            bridge.connect(address, name, app.ktmnav.backend.BikeGeneration.from(gen));
            addMode = false; tab = 0; if (scroll != null) scroll.scrollTo(0, 0); render();
        });
    }
    private void startScan() {
        stopScan(); discovered.clear(); scanning = true; final long generation = ++scanGeneration;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) addDevice(device, "Paired");
            scanner = adapter.getBluetoothLeScanner(); if (scanner != null) scanner.startScan(scanCallback);
            adapter.startDiscovery();
            if (scanButton != null) scanButton.setText(tr("Stop scanning"));
            if (scanProgress != null) scanProgress.setVisibility(View.VISIBLE);
            if (scanStatus != null) scanStatus.setText(tr("Scanning nearby · 12 seconds"));
            handler.postDelayed(() -> { if (scanning && generation == scanGeneration) stopScan(); }, 12000);
        } catch (SecurityException | IllegalStateException ex) { stopScan(); toast("Bluetooth unavailable. Check nearby device permission and try again."); }
    }
    private void stopScan() {
        boolean was = scanning; scanning = false; scanGeneration++;
        try { if (scanner != null) scanner.stopScan(scanCallback); if (adapter != null && btAllowed() && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException | IllegalStateException ignored) {}
        if (scanProgress != null) scanProgress.setVisibility(View.GONE);
        if (was && scanButton != null) scanButton.setText(tr("Scan again"));
        if (was && scanStatus != null) scanStatus.setText(tr(discovered.isEmpty() ? "No devices found. Check ignition and pairing mode." : "Scan complete · choose your motorcycle below"));
    }
    private void addDevice(BluetoothDevice d, String type) { addDevice(d, type, -127); }
    private void addDevice(BluetoothDevice d, String type, int rssi) {
        if (!scanning) return;
        try {
            String name = d.getName(); if (name == null || name.trim().isEmpty()) name = "Unnamed device";
            FoundBike old = discovered.get(d.getAddress());
            if (old != null && name.equals("Unnamed device")) name = old.name;
            String observedType = old != null && old.type.equals("Bluetooth LE") ? old.type : type;
            discovered.put(d.getAddress(), new FoundBike(d.getAddress(), name, observedType, rssi == -127 && old != null ? old.rssi : rssi, guessGeneration(d, observedType))); updateDiscovered();
        } catch (SecurityException ignored) {}
    }
    private String guessGeneration(BluetoothDevice d, String type) {
        for (Bridge.Bike bike : bridge.getBikes()) if (bike.address.equals(d.getAddress())) return bike.generation;
        if ("Bluetooth LE".equals(type)) return "GEN3";
        // Only a Classic-only device is a Gen 2 (RFCOMM) dash. A Gen 3 dash paired in Android settings for calls/music
        // is dual-mode, and treating it as Gen 2 sends RFCOMM to a BLE-only navigation service (TurnIt: classic-only = Gen 2).
        try { int t = d.getType(); return t == BluetoothDevice.DEVICE_TYPE_LE || t == BluetoothDevice.DEVICE_TYPE_DUAL ? "GEN3" : "GEN2"; } catch (SecurityException e) { return "GEN2"; }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handler.post(() -> addDevice(r.getDevice(), "Bluetooth LE", r.getRssi())); }
        @Override public void onScanFailed(int error) { handler.post(() -> { stopScan(); if (scanStatus != null) scanStatus.setText(tr("Scan failed (") + error + "). Wait a moment and try again."); }); }
    };
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) { BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); if (d != null) addDevice(d, "Bluetooth Classic"); } }
    };
    private void updateDiscovered() {
        if (discoveredList == null || tab != 1) return; discoveredList.removeAllViews(); int shown = 0;
        List<FoundBike> devices = new ArrayList<>(discovered.values());
        devices.sort(Comparator.comparingInt((FoundBike d) -> dashboardNameRank(d.name)).thenComparing(Comparator.comparingInt((FoundBike d) -> d.rssi).reversed()).thenComparing(d -> d.address));
        LinearLayout g = null;
        for (FoundBike d : devices) {
            String lower = d.name.toLowerCase(Locale.ROOT);
            if (!showAll && !lower.contains("ktm") && !lower.contains("hsq") && !lower.contains("husq") && !lower.contains("bccu") && !lower.contains("my ride") && !lower.contains("norden") && !lower.contains("duke") && !lower.contains("vitpilen") && !lower.contains("svartpilen")) continue;
            if (g == null) { gap(discoveredList, 8); g = column(); g.setBackground(shape(raised, RADIUS_TILE, false)); g.setClipToOutline(true); discoveredList.addView(g, new LinearLayout.LayoutParams(-1, -2)); }
            String gen = "GEN2".equals(d.gen) ? "Gen 2" : "Gen 3";
            LinearLayout r = listRow(g, iconTile(R.drawable.ic_bluetooth), d.name, gen + " · " + tr(d.type));
            if (d.rssi != -127) {
                int bars = d.rssi > -70 ? R.drawable.ic_signal_cellular_alt : d.rssi > -85 ? R.drawable.ic_signal_cellular_alt_2_bar : R.drawable.ic_signal_cellular_alt_1_bar;
                r.addView(icon(bars, muted, ICON), new LinearLayout.LayoutParams(dp(ICON), dp(ICON)));
            }
            r.setContentDescription(d.name + ", " + gen + ", " + tr(d.type) + (d.rssi == -127 ? "" : ", " + tr("Signal") + " " + d.rssi + " dBm"));
            r.setBackground(ripple(null, 0)); asButton(r); r.setOnClickListener(v -> generationDialog(d.address, d.name, d.gen)); shown++;
        }
        if (shown == 0 && !discovered.isEmpty()) { gap(discoveredList, 8); discoveredList.addView(label("No matching motorcycles yet. Use ‘Show all’ if your dashboard has a different name.", 13, muted, false)); }
    }
    private static int dashboardNameRank(String name) { String lower = name.toLowerCase(Locale.ROOT); return lower.startsWith("ktm") || lower.startsWith("hsq") || lower.startsWith("husqvarna") ? 0 : lower.contains("ktm") || lower.contains("hsq") || lower.contains("husqvarna") ? 1 : 2; }
    private static final class FoundBike { final String address, name, type, gen; final int rssi; FoundBike(String a, String n, String t, int r, String g) { address = a; name = n; type = t; rssi = r; gen = g; } }
    private Runnable afterPermission;
    private View featuresSection;
    boolean btAllowed() { return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    void withBluetooth(Runnable action) {
        if (adapter == null) { toast("This device has no Bluetooth adapter."); return; }
        if (!btAllowed()) { afterPermission = action; requestPermissions(Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS} : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH); return; }
        try {
            if (!adapter.isEnabled()) { afterPermission = action; startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT); return; }
            action.run();
        } catch (SecurityException e) { afterPermission = null; toast("Nearby devices permission changed. Allow it in Android app settings and try again."); }
        catch (ActivityNotFoundException e) { afterPermission = null; toast("Enable Bluetooth in Android settings, then try again."); }
    }
    private Runnable afterRuntime;
    boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    String[] translateAll(String[] english) { return UiText.translate(this, english); }
    void requestRuntime(String title, String message, String[] permissions, int code, Runnable after) {
        new AlertDialog.Builder(this).setTitle(tr(title)).setMessage(tr(message)).setNegativeButton(tr("Not now"), null)
            .setPositiveButton(tr("Continue"), (d, w) -> { afterRuntime = after; requestPermissions(permissions, code); }).show();
    }
    private void runtimeResult(int code, String[] permissions) {
        boolean ok = code == REQUEST_LOCATION ? granted(Manifest.permission.ACCESS_FINE_LOCATION) : granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS);
        if (code == REQUEST_PHONE && bridge.getCallStateMonitor() != null) bridge.getCallStateMonitor().start();
        bridge.settingsChanged();
        Runnable after = afterRuntime; afterRuntime = null;
        boolean blocked = false;
        for (String permission : permissions) if (!granted(permission) && !shouldShowRequestPermissionRationale(permission)) blocked = true;
        if (ok) toast(code == REQUEST_LOCATION ? "Location access allowed." : "Phone access allowed.");
        else if (blocked) new AlertDialog.Builder(this).setTitle(tr("Permission not granted")).setMessage(tr("Android will not ask again. Open app settings, choose Permissions and allow access there."))
            .setNegativeButton(tr("Later"), null).setPositiveButton(tr("App settings"), (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))).show();
        else toast("Permission not granted. The feature stays off until you allow it.");
        if (after != null) after.run();
    }
    @Override public void onRequestPermissionsResult(int r, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(r, permissions, grants);
        if (r == REQUEST_LOCATION || r == REQUEST_PHONE) { runtimeResult(r, permissions); return; }
        if (r == REQUEST_NOTIFICATIONS) { if (!postNotificationsAllowed() && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings(); render(); return; }
        if (r == REQUEST_COMAPS) { toast(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED ? "CoMaps navigation permission allowed." : "CoMaps navigation permission was not granted. Enable it in Android app permissions."); render(); return; }
        if (r == REQUEST_BLUETOOTH) {
            Runnable a = afterPermission; afterPermission = null;
            if (btAllowed() && a != null) withBluetooth(a);
            else new AlertDialog.Builder(this).setTitle(tr("Nearby devices permission needed")).setMessage(tr("Allow nearby devices in Android app settings to find and connect your motorcycle.")).setNegativeButton(tr("Later"), null).setPositiveButton(tr("App settings"), (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))).show();
            render();
        }
    }
    @Override protected void onActivityResult(int r, int result, Intent data) {
        super.onActivityResult(r, result, data);
        if (r == REQUEST_ENABLE_BT) { Runnable a = afterPermission; afterPermission = null; if (result == RESULT_OK && a != null) a.run(); }
        if (r == REQUEST_IMPORT_GPX && result == RESULT_OK && data != null && data.getData() != null) importRoute(data.getData(), displayName(data.getData()));
    }
    void pickGpxRoute() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gpx+xml", "application/gpx", "application/xml", "text/xml", "application/octet-stream"});
        try { startActivityForResult(open, REQUEST_IMPORT_GPX); } catch (ActivityNotFoundException e) { toast("No file picker is available on this phone."); }
    }
    boolean notificationAccess() { String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners"); return enabled != null && Arrays.stream(enabled.split(":")).map(ComponentName::unflattenFromString).anyMatch(c -> c != null && getPackageName().equals(c.getPackageName())); }

    NavApps.App appByName(String name) { return NavApps.byName(name); }
    Drawable appIcon(String pkg) { if (pkg == null) return null; try { return getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException e) { return null; } }
    /** The installed app's own icon when it is on the phone, otherwise KTMnav's bundled icon for it. */
    Drawable appDrawable(NavApps.App app, String installedPkg) { Drawable d = appIcon(installedPkg); return d != null ? d : getDrawable(appIconRes(app)); }
    static int appIconRes(NavApps.App app) {
        if (app == null) return R.drawable.ic_app_generic;
        switch (app.id) {
            case NavApps.GOOGLE_MAPS: return R.drawable.ic_app_google_maps;
            case NavApps.WAZE: return R.drawable.ic_app_waze;
            case NavApps.TOMTOM: return R.drawable.ic_app_tomtom;
            case NavApps.OSMAND: return R.drawable.ic_app_osmand;
            case NavApps.KURVIGER: return R.drawable.ic_app_kurviger;
            case NavApps.CALIMOTO: return R.drawable.ic_app_calimoto;
            case NavApps.ORGANIC_MAPS: return R.drawable.ic_app_organic_maps;
            case NavApps.LOCUS: return R.drawable.ic_app_locus;
            case NavApps.COMAPS: return R.drawable.ic_app_comaps;
            case NavApps.HERE: return R.drawable.ic_app_here;
            case NavApps.SYGIC: return R.drawable.ic_app_sygic;
            case NavApps.MAPY: return R.drawable.ic_app_mapy;
            default: return R.drawable.ic_app_generic;
        }
    }
    private Drawable sourceDrawable(String source) { NavApps.App app = NavApps.byName(source); return app == null ? null : appDrawable(app, installedVariant(app.canonicalPackage())); }
    /** The app the Navigate card and the handlebar shortcut start: the chosen one, else the last one forwarded, else the first installed. */
    NavApps.App preferredApp() {
        NavApps.App chosen = NavApps.byPackage(prefs().getString(NavLauncher.PREF_APP, null));
        if (chosen != null && installedVariant(chosen.canonicalPackage()) != null) return chosen;
        NavApps.App last = NavApps.byName(prefs().getString("ui_last_source", ""));
        if (last != null && installedVariant(last.canonicalPackage()) != null) return last;
        return NavLauncher.preferredApp(this, prefs());
    }
    private boolean needsListener(NavApps.App app) { return !NavApps.LOCUS.equals(app.id) && !NavApps.COMAPS.equals(app.id); }

    // --- Navigate from KTMnav ---
    private void navigateCard(LinearLayout parent) {
        NavApps.App app = preferredApp(); String pkg = installedVariant(app.canonicalPackage());
        LinearLayout card = column(); card.setGravity(Gravity.START); card.setId(R.id.navigate_card);
        EditText where = new EditText(this); whereField = where; where.setId(R.id.navigate_field);
        where.setHint(tr("Where to?")); where.setContentDescription(tr("Destination")); where.setSingleLine(true); where.setTextColor(text); where.setHintTextColor(muted);
        where.setMinHeight(dp(52)); where.setImeOptions(EditorInfo.IME_ACTION_GO); where.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        where.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(NavDestination.MAX_QUERY_LENGTH)});
        Drawable search = getDrawable(R.drawable.ic_search).mutate(); search.setTint(muted); search.setBounds(0, 0, dp(22), dp(22));
        where.setCompoundDrawablesRelative(search, null, null, null); where.setCompoundDrawablePadding(dp(10));
        if (pendingDestination != null) { where.setText(pendingDestination.title().equals(pendingDestination.query) ? pendingDestination.query : pendingDestination.query); }
        where.setOnEditorActionListener((v, actionId, e) -> { if (actionId == EditorInfo.IME_ACTION_GO) { navigateFromField(); return true; } return false; });
        card.addView(where, new LinearLayout.LayoutParams(-1, -2)); gap(card, 8);
        LinearLayout controls = row();
        Button appButton = button(app.name, false, this::chooseNavigateApp); appButton.setId(R.id.navigate_app);
        Drawable d = appDrawable(app, pkg); d.setBounds(0, 0, dp(22), dp(22)); appButton.setCompoundDrawablesRelative(d, null, null, null); appButton.setCompoundDrawablePadding(dp(8));
        appButton.setContentDescription(tr("Navigation app: ") + app.name); appButton.setSingleLine(true); appButton.setEllipsize(TextUtils.TruncateAt.END);
        grow(controls, appButton); View space = new View(this); controls.addView(space, new LinearLayout.LayoutParams(dp(8), 1));
        Button go = withIcon(button(pkg != null ? "Go" : "Get app", true, pkg != null ? this::navigateFromField : () -> openApp(app.canonicalPackage(), false)), R.drawable.ic_near_me);
        go.setId(R.id.navigate_go); controls.addView(go, new LinearLayout.LayoutParams(-2, -2)); card.addView(controls);
        List<NavDestination> favourites = NavHistory.favourites(prefs());
        gap(card, 8); LinearLayout chips = row(); HorizontalScrollView chipScroll = new HorizontalScrollView(this); chipScroll.setHorizontalScrollBarEnabled(false); chipScroll.addView(chips);
        for (NavDestination fav : favourites) {
            int iconRes = NavHistory.HOME.equalsIgnoreCase(fav.label) ? R.drawable.ic_home : NavHistory.WORK.equalsIgnoreCase(fav.label) ? R.drawable.ic_work : R.drawable.ic_star;
            Button chip = textButton(fav.label, iconRes, () -> navigateTo(fav)); chip.setBackground(ripple(shape(raised, 24, false), 24)); chip.setContentDescription(tr("Navigate to ") + fav.label);
            chip.setOnLongClickListener(v -> { favouriteMenu(fav); return true; });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMarginEnd(dp(8)); chips.addView(chip, cp);
        }
        if (favourites.size() < NavHistory.MAX_FAVOURITES) {
            Button add = textButton(favourites.isEmpty() ? "Save home or work" : "Add favourite", R.drawable.ic_add, () -> saveFavouriteDialog(null)); add.setBackground(ripple(outline(line), 24)); chips.addView(add, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));
        List<NavDestination> recent = NavHistory.recent(prefs());
        if (!recent.isEmpty()) {
            gap(card, 4); LinearLayout head = row(); head.setMinimumHeight(dp(40)); TextView h = label("Recent", 13, muted, true); h.setAccessibilityHeading(true); grow(head, h);
            head.addView(textButton("Clear", 0, muted, () -> { NavHistory.clearRecent(prefs()); render(); })); card.addView(head);
            LinearLayout g = column(); g.setBackground(shape(raised, RADIUS_TILE, false)); g.setClipToOutline(true);
            for (int i = 0; i < Math.min(5, recent.size()); i++) {
                NavDestination r = recent.get(i);
                LinearLayout rowView = listRow(g, icon(R.drawable.ic_history, muted, ICON), r.title(), tr(r.subtitle()));
                rowView.setPadding(dp(12), dp(6), dp(12), dp(6)); rowView.setMinimumHeight(dp(48));
                rowView.setBackground(ripple(null, 0)); asButton(rowView); rowView.setContentDescription(tr("Navigate to ") + r.title());
                rowView.setOnClickListener(v -> navigateTo(r)); rowView.setOnLongClickListener(v -> { recentMenu(r); return true; });
            }
            card.addView(g, new LinearLayout.LayoutParams(-1, -2));
        }
        gap(card, 8);
        TextView note = label(pkg == null ? "Install the app to start guidance from here." : "Starts turn-by-turn guidance in the app. Your dashboard keeps showing the turns.", 12, muted, false);
        card.addView(note);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
    private void navigateFromField() {
        if (whereField == null) return;
        NavDestination d = NavDestination.of(whereField.getText().toString());
        if (d == null) { whereField.setError(tr("Enter a place, an address or coordinates.")); return; }
        navigateTo(d);
    }
    private void navigateTo(NavDestination d) {
        NavApps.App app = preferredApp();
        if (installedVariant(app.canonicalPackage()) == null) { toast(app.name + " " + tr("is not installed.")); openApp(app.canonicalPackage(), false); return; }
        NavLauncher.Result result = NavLauncher.navigate(this, app, d);
        if (result.started) {
            NavHistory.remember(prefs(), d, System.currentTimeMillis()); pendingDestination = null;
            if (whereField != null) whereField.setError(null);
        }
        toast(result.message);
    }
    private void chooseNavigateApp() {
        LinearLayout body = dialogBody(); body.addView(label("Also used for the handlebar long-press shortcut.", 13, muted, false));
        LinearLayout g = group(body); AlertDialog[] dialog = new AlertDialog[1]; int shown = 0;
        for (NavApps.App app : NavApps.ALL) {
            String pkg = installedVariant(app.canonicalPackage()); if (pkg == null) continue; shown++;
            ImageView i = new ImageView(this); i.setImageDrawable(appDrawable(app, pkg)); i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout r = listRow(g, i, app.name, tr(app.forwards ? "Turns forward to your dashboard" : "Guidance only on the phone"));
            r.setBackground(ripple(null, 0)); asButton(r); r.setOnClickListener(v -> { prefs().edit().putString(NavLauncher.PREF_APP, pkg).apply(); bridge.settingsChanged(); if (dialog[0] != null) dialog[0].dismiss(); render(); });
        }
        if (shown == 0) { toast("No supported navigation app is installed."); switchTab(2); return; }
        fullButton(body, "More navigation apps", false, () -> { if (dialog[0] != null) dialog[0].dismiss(); switchTab(2); });
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        dialog[0] = new AlertDialog.Builder(this).setTitle(tr("Navigation app")).setView(scroller).setNegativeButton(tr("Cancel"), null).create(); dialog[0].show();
    }
    private void saveFavouriteDialog(NavDestination preset) {
        String typed = whereField != null ? whereField.getText().toString() : "";
        NavDestination seed = preset != null ? preset : NavDestination.of(typed);
        boolean hasHome = NavHistory.favourite(prefs(), NavHistory.HOME) != null, hasWork = NavHistory.favourite(prefs(), NavHistory.WORK) != null;
        LinearLayout box = dialogBody();
        EditText name = new EditText(this); name.setHint(tr("Name")); name.setContentDescription(tr("Name")); name.setSingleLine(true); name.setText(!hasHome ? tr(NavHistory.HOME) : !hasWork ? tr(NavHistory.WORK) : "");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)}); box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        EditText place = new EditText(this); place.setHint(tr("Address or coordinates")); place.setContentDescription(tr("Address or coordinates")); place.setSingleLine(true); place.setText(seed != null ? seed.query : "");
        place.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(NavDestination.MAX_QUERY_LENGTH)}); gap(box, 8); box.addView(place, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("Save favourite")).setView(box).setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Save"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            NavDestination d = NavDestination.of(place.getText().toString()); String label = name.getText().toString().trim();
            if (label.isEmpty()) { name.setError(tr("Enter a name")); return; }
            if (d == null) { place.setError(tr("Enter a place, an address or coordinates.")); return; }
            if (!NavHistory.saveFavourite(prefs(), label, d)) { toast("Favourite limit reached."); return; }
            dialog.dismiss(); render();
        })); dialog.show();
    }
    private void favouriteMenu(NavDestination fav) {
        new AlertDialog.Builder(this).setTitle(fav.label).setItems(UiText.translate(this, new String[]{"Navigate", "Edit", "Remove favourite"}), (d, n) -> {
            if (n == 0) navigateTo(fav); else if (n == 1) saveFavouriteDialog(fav); else { NavHistory.removeFavourite(prefs(), fav.label); render(); }
        }).show();
    }
    private void recentMenu(NavDestination r) {
        new AlertDialog.Builder(this).setTitle(r.title()).setItems(UiText.translate(this, new String[]{"Navigate", "Save as favourite", "Remove from recent"}), (d, n) -> {
            if (n == 0) navigateTo(r); else if (n == 1) saveFavouriteDialog(r); else { NavHistory.forgetRecent(prefs(), r); render(); }
        }).show();
    }
    private void navigationApps() {
        boolean access = notificationAccess();
        if (!access) {
            LinearLayout banner = group(content); banner.setId(R.id.access_banner); ((LinearLayout.LayoutParams) banner.getLayoutParams()).bottomMargin = dp(12);
            LinearLayout r = listRow(banner, iconTile(R.drawable.ic_notifications), tr("Notification access needed"), tr("Needed for most navigation apps"));
            Button allow = button("Allow", true, this::openNotificationAccess); allow.setMinHeight(dp(40)); allow.setMinimumHeight(dp(40)); allow.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(48)); ap.setMarginStart(dp(8)); r.addView(allow, ap);
        }
        String current = activeSource();
        if (!current.isEmpty()) {
            LinearLayout live = card(content); live.setId(R.id.now_forwarding); nowForwarding = live;
            LinearLayout r = row(); ImageView i = new ImageView(this); i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); nowForwardingIcon = i;
            Drawable d = sourceDrawable(current); if (d != null) i.setImageDrawable(d); r.addView(i, new LinearLayout.LayoutParams(dp(TILE), dp(TILE)));
            LinearLayout copy = column(); copy.setPadding(dp(16), 0, 0, 0); copy.addView(label("Now forwarding", 12, muted, true));
            nowForwardingName = rawLabel(current, 16, text, true); copy.addView(nowForwardingName); grow(r, copy);
            View dot = new View(this); GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(success); dot.setBackground(g); r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            live.addView(r); live.setContentDescription(tr("Now forwarding") + ": " + current); live.setFocusable(true);
        }
        List<NavApps.App> installed = new ArrayList<>(), missing = new ArrayList<>();
        for (NavApps.App app : NavApps.ALL) (installedVariant(app.canonicalPackage()) != null ? installed : missing).add(app);
        if (!installed.isEmpty()) { section(content, "Installed"); LinearLayout g = group(content); for (NavApps.App app : installed) appRow(g, app, access, current); }
        if (!missing.isEmpty()) {
            boolean open = prefs().getBoolean("ui_more_apps_open", installed.isEmpty());
            LinearLayout header = row(); header.setMinimumHeight(dp(48)); header.setPadding(dp(4), dp(8), dp(4), 0); header.setBackground(ripple(null, 12));
            TextView h = label("More apps", 13, muted, true); h.setAccessibilityHeading(true); grow(header, h);
            header.addView(chip(String.valueOf(missing.size()), muted));
            header.addView(icon(open ? R.drawable.ic_keyboard_arrow_up : R.drawable.ic_keyboard_arrow_down, muted, ICON), new LinearLayout.LayoutParams(dp(ICON), dp(ICON)));
            header.setContentDescription(tr("More apps")); header.setFocusable(true);
            header.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); info.setStateDescription(tr(open ? "Expanded" : "Collapsed")); } });
            header.setOnClickListener(v -> { prefs().edit().putBoolean("ui_more_apps_open", !open).apply(); render(); });
            content.addView(header); gap(content, 8);
            if (open) { LinearLayout g = group(content); for (NavApps.App app : missing) appRow(g, app, access, current); }
        }
        gap(content, 4);
        content.addView(textButton("How navigation forwarding works", R.drawable.ic_help, daylight() ? text : accentText, () -> new AlertDialog.Builder(this).setTitle(tr("From route to dashboard"))
            .setMessage(tr("1. Add and connect your motorcycle in Garage.\n2. Allow notification access. CoMaps also needs its navigation permission.\n3. Start turn-by-turn navigation in a supported app, or type a destination on the Ride tab and tap Go.\n\nTurns, road names, distance and arrival time then appear on your dashboard. HERE WeGo, Sygic and Mapy.cz can be started from KTMnav but do not forward turns."))
            .setPositiveButton(tr("Got it"), null).show()), new LinearLayout.LayoutParams(-2, -2));
    }
    private void appRow(LinearLayout g, NavApps.App app, boolean access, String current) {
        String installed = installedVariant(app.canonicalPackage()); boolean exists = installed != null; String pkg = exists ? installed : app.canonicalPackage();
        boolean enabled = prefs().getBoolean("enabled_" + pkg, true);
        boolean comapsBlocked = exists && NavApps.COMAPS.equals(app.id) && !granted(pkg + ".permission.READ_NAVIGATION_DATA");
        String status = !exists ? "Not installed" : !app.forwards ? "Starts from Navigate · no dashboard turns" : !enabled ? "Off" : app.name.equals(current) ? "Forwarding now" : needsListener(app) && !access ? "Needs notification access" : comapsBlocked ? "Needs navigation permission" : "Forwarding";
        ImageView i = new ImageView(this); i.setId(exists && appIcon(pkg) != null ? R.id.app_icon_real : R.id.app_icon_bundled); i.setImageDrawable(appDrawable(app, exists ? pkg : null));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); if (!exists) i.setAlpha(0.72f);
        LinearLayout r = listRow(g, i, app.name, tr(status));
        if (app.name.equals(current)) ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1)).setTextColor(success);
        if (app.forwards) {
            Switch s = new Switch(this); s.setChecked(enabled); s.setEnabled(exists); s.setMinHeight(dp(48)); s.setMinWidth(dp(48));
            s.setContentDescription(tr("Forward navigation from " + app.name)); if (!exists) s.setStateDescription(tr("Not installed"));
            s.setOnCheckedChangeListener((v, checked) -> { SharedPreferences.Editor editor = prefs().edit(); for (String variant : variants(app.canonicalPackage())) editor.putBoolean("enabled_" + variant, checked); editor.apply(); bridge.settingsChanged(); render(); });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.setMarginStart(dp(8)); r.addView(s, sp);
        } else {
            TextView c = chip("Launch only", muted); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMarginStart(dp(8)); r.addView(c, cp);
        }
        r.setBackground(ripple(null, 0)); asButton(r);
        if (comapsBlocked && enabled) { r.setContentDescription(app.name + ", " + tr(status)); r.setOnClickListener(v -> requestPermissions(new String[]{pkg + ".permission.READ_NAVIGATION_DATA"}, REQUEST_COMAPS)); }
        else { r.setContentDescription((exists ? tr("Open " + app.name) : tr("Get " + app.name)) + ", " + tr(status)); r.setOnClickListener(v -> openApp(pkg, exists)); }
    }
    String[] variants(String pkg) { return NavApps.variants(pkg); }
    String installedVariant(String pkg) { for (String variant : variants(pkg)) try { getPackageManager().getPackageInfo(variant, 0); return variant; } catch (PackageManager.NameNotFoundException ignored) {} return null; }
    private void openApp(String pkg, boolean installed) { Intent i = installed ? getPackageManager().getLaunchIntentForPackage(pkg) : new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)); if (i == null) { toast("Open the navigation app from your launcher."); return; } try { startActivity(i); } catch (ActivityNotFoundException e) { toast("No app available to open this link."); } }

    private String onOff(boolean on) { return tr(on ? "On" : "Off"); }
    private void settings() {
        setupCard(content, false);
        section(content, "Connection"); LinearLayout c = group(content);
        prefRow(c, R.drawable.ic_sync, "Keep bridge in background", null, "keep_background", true);
        prefRow(c, R.drawable.ic_autorenew, "Auto-connect", null, "auto_connect", true);
        long[] waits = {30000, 60000, 120000, 300000}; String[] waitLabels = {"30 seconds", "1 minute", "2 minutes", "5 minutes"};
        int wait = Math.max(0, Arrays.binarySearch(waits, prefs().getLong("idle_timeout", 30000L)));
        navRow(c, R.drawable.ic_schedule, "Wait before low-power scan", tr(waitLabels[wait]), () -> new AlertDialog.Builder(this).setTitle(tr("Wait before low-power scan"))
            .setSingleChoiceItems(UiText.translate(this, waitLabels), wait, (d, n) -> { prefs().edit().putLong("idle_timeout", waits[n]).apply(); bridge.settingsChanged(); d.dismiss(); render(); }).setNegativeButton(tr("Cancel"), null).show());

        section(content, "Navigation"); LinearLayout nav = group(content);
        NavApps.App navApp = preferredApp();
        navRow(nav, R.drawable.ic_map, "Navigation app", navApp.name + (installedVariant(navApp.canonicalPackage()) == null ? " · " + tr("Not installed") : ""), this::chooseNavigateApp);
        prefRow(nav, R.drawable.ic_two_wheeler, "Two-wheeler routes in Google Maps", "Where Google offers them; otherwise driving routes", NavLauncher.PREF_TWO_WHEELER, false);
        navRow(nav, R.drawable.ic_star, "Favourite places", String.format(Locale.getDefault(), tr("%d saved"), NavHistory.favourites(prefs()).size()), () -> saveFavouriteDialog(null));
        navRow(nav, R.drawable.ic_history, "Clear recent destinations", null, () -> { NavHistory.clearRecent(prefs()); toast("Recent destinations cleared."); render(); });

        section(content, "Dashboard"); LinearLayout dash = group(content);
        String[] idleLabels = {"Off — restore dashboard", "Clock", "Now playing", "Custom message"}, idleValues = {"NONE", "CLOCK", "MEDIA", "CUSTOM"};
        int idle = Math.max(0, Arrays.asList(idleValues).indexOf(prefs().getString("idle_mode", "NONE")));
        navRow(dash, R.drawable.ic_timer, "Idle display", tr(idleLabels[idle]), () -> choose("Idle display", idleLabels, idleValues, "idle_mode"));
        navRow(dash, R.drawable.ic_edit, "Custom dashboard message", value(prefs().getString("custom_text", ""), tr("Not set")), () -> editText("Custom dashboard message", prefs().getString("custom_text", ""), s -> { prefs().edit().putString("custom_text", s).apply(); bridge.settingsChanged(); render(); }));
        navRow(dash, R.drawable.ic_person, "Welcome name", value(prefs().getString("user_name", ""), tr("Not set")), () -> editText("Welcome name", prefs().getString("user_name", ""), name -> { prefs().edit().putString("user_name", name).apply(); bridge.settingsChanged(); render(); }));
        prefRow(dash, R.drawable.ic_short_text, "Scroll long dashboard messages", null, "marquee_enabled", true);
        navRow(dash, R.drawable.ic_speed, "Dashboard data & controls", null, this::dashboardData);

        section(content, "Riding features"); featuresSection = content.getChildAt(content.getChildCount() - 1); LinearLayout f = group(content);
        navRow(f, R.drawable.ic_chat, "Messages on dashboard", onOff(prefs().getBoolean(MessageMirror.PREF_ENABLED, false)), () -> new MessagesScreen(this).show());
        navRow(f, R.drawable.ic_call, "Calls", null, () -> new CallsScreen(this).show());
        navRow(f, R.drawable.ic_route, "Ride tools", null, () -> new RideToolsScreen(this).show());
        navRow(f, R.drawable.ic_timeline, "Rides", null, () -> new RidesScreen(this).show());
        navRow(f, R.drawable.ic_car_repair, "OBD-II adapter (read-only)", onOff(prefs().getBoolean(ObdManager.PREF_ENABLED, false)), () -> new ObdScreen(this).show());
        navRow(f, R.drawable.ic_turn_right, "Dashboard symbol test & calibration", null, () -> new SymbolTestScreen(this).show());

        section(content, "Handlebar"); LinearLayout h = group(content);
        boolean mapped = prefs().getBoolean("handlebar_mappings_enabled", false);
        navRow(h, R.drawable.ic_tune, "Handlebar button mappings", mapped ? tr(handlebarMode().getLabel()) : tr("Off"), this::handlebarMappings);
        navRow(h, R.drawable.ic_sports_esports, "Gen 3 button lab", null, this::buttonLab);

        section(content, "Appearance"); LinearLayout a = group(content);
        String[] themeLabels = {"KTM · charcoal & orange", "Husqvarna · charcoal & blue", "Daylight · high contrast"}, themeValues = {"KTM", "HUSQVARNA", "LIGHT"};
        int theme = Math.max(0, Arrays.asList(themeValues).indexOf(prefs().getString("theme", "KTM")));
        navRow(a, R.drawable.ic_palette, "Theme", tr(themeLabels[theme]), () -> choose("Choose appearance", themeLabels, themeValues, "theme"));
        int lang = Math.max(0, Arrays.asList(UiText.tags()).indexOf(prefs().getString("app_language", "")));
        navRow(a, R.drawable.ic_language, "App language", tr(UiText.names()[lang]), this::chooseLanguage);

        section(content, "About & help"); LinearLayout s = group(content);
        navRow(s, R.drawable.ic_verified, "Included features", null, this::includedFeatures);
        navRow(s, R.drawable.ic_help, "Compatibility & navigation help", null, this::navigationHelp);
        navRow(s, R.drawable.ic_description, "View connection log", null, this::showLogs);
        navRow(s, R.drawable.ic_share, "Share diagnostic log", null, this::shareLogs);
        switchRow(s, R.drawable.ic_photo_camera, "Capture maneuver arrows", null, prefs().getBoolean("capture_maneuvers", false), (v, on) -> { prefs().edit().putBoolean("capture_maneuvers", on).apply(); bridge.settingsChanged(); render(); });
        if (prefs().getBoolean("capture_maneuvers", false)) navRow(s, R.drawable.ic_download, "Export maneuver captures", null, this::shareManeuvers);
        navRow(s, R.drawable.ic_policy, "Open-source licenses", null, this::licenses);
        navRow(s, R.drawable.ic_policy, "Privacy policy", null, this::privacyPolicy);
        navRow(s, R.drawable.ic_info, "About KTMnav", appVersion(), this::about);
    }
    private int findFeatures() { return featuresSection == null ? 0 : Math.max(0, featuresSection.getTop() - dp(8)); }
    private void chooseLanguage() {
        String[] tags = UiText.tags(); String[] names = UiText.names(); names[0] = tr(names[0]);
        int selected = Arrays.asList(tags).indexOf(prefs().getString("app_language", ""));
        new AlertDialog.Builder(this).setTitle(tr("App language"))
            .setSingleChoiceItems(names, Math.max(0, selected), (dialog, index) -> {
                prefs().edit().putString("app_language", tags[index]).apply(); dialog.dismiss(); recreate();
            }).setNegativeButton(tr("Cancel"), null).show();
    }
    LinearLayout dialogBody() { LinearLayout body = column(); body.setPadding(dp(24), dp(8), dp(24), dp(16)); return body; }
    private void navigationHelp() {
        LinearLayout body = dialogBody();
        String[][] guidance = {
            {"Gen 3: first pairing", "Open the motorcycle dashboard’s Bluetooth menu. The add-device prompt appears there for the first encrypted BLE pairing. Later reconnects can work from other dashboard screens."},
            {"Gen 2: Navigation switch", "Enable Navigation in the dashboard’s Bluetooth menu. Pair and connect the motorcycle in Android Bluetooth settings. Close the official KTM app or another dashboard companion if it holds the connection."},
            {"Google Maps", "Turn arrows come from the notification image. English notification formatting gives the most reliable arrival time and remaining distance."},
            {"TomTom GO", "Its navigation notification does not provide arrival time or remaining distance. Those fields stay empty or use placeholders on the motorcycle."},
            {"OsmAnd & Calimoto", "The original parser recognizes English and German maneuver phrases. In other languages some turn arrows can remain empty. Distance and road data may still be available."},
            {"Kurviger & Organic Maps", "Arrows are matched against known notification images. An unfamiliar maneuver image may have no recognized arrow. Organic Maps does not supply arrival time in its navigation notification."},
            {"Waze", "Turns are read from the English instruction text of the Waze notification. In other languages the arrow can stay empty while distance and road still show. Waze does not report arrival time in every notification."},
            {"Navigate from KTMnav", "Type a place, an address or coordinates on the Ride tab and tap Go to start turn-by-turn guidance in your chosen app. Places shared to KTMnav from other apps, geo: links and GPX files also arrive here. Guidance still reaches the dashboard through the app’s own notification."},
            {"Locus Map", "The bridge reads Locus navigation data directly while connected. Start turn-by-turn navigation in Locus Map 4; plain route guiding does not provide the same turn data. Notification access is not required."},
            {"CoMaps", "Grant the installed CoMaps app’s Read navigation data permission. Directions are read directly while connected; notification access is not required. The original integration expects CoMaps 2026.07.16 or newer. Start navigation, not route planning alone."},
            {"Roundabout exits", "For sources that provide an exit number rather than an arrow image, the arrow is an estimate based on a four-exit roundabout. The exit number shown before the road name comes from the navigation app."},
            {"Background connection", "Allow nearby devices and keep Bluetooth enabled. Background mode uses a visible Android notification while the bridge is active. Gen 3 can use filtered BLE detection when the motorcycle is absent. Android may also require a battery setting change on your phone."},
            {"Privacy & permissions", "Only supported navigation notifications and selected media-session metadata are processed. KTMnav has no network permission, account or billing. Android notification access and motorcycle pairing must be granted by you."}
        };
        for (String[] item : guidance) { TextView h = label(item[0], 16, text, true); h.setAccessibilityHeading(true); body.addView(h); gap(body, 4); body.addView(label(item[1], 14, muted, false)); gap(body, 16); }
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        new AlertDialog.Builder(this).setTitle(tr("Compatibility & navigation help")).setView(scroller).setPositiveButton(tr("Close"), null).show();
    }
    private void dashboardData() {
        LinearLayout body = dialogBody();
        body.addView(label("Values appear only when the motorcycle reports them.", 14, muted, false));
        LinearLayout readings = column(); body.addView(readings);
        Button send = button("Send dashboard message", false, () -> editText("Dashboard message", prefs().getString("custom_text", ""), message -> { if (!bridge.isConnected()) { toast("Connect a motorcycle first."); return; } bridge.sendDashboardNotification(message, app.ktmnav.backend.BccuProtocol.NotificationIcon.INFORMATION); }));
        Runnable refresh = () -> {
            send.setEnabled(bridge.isConnected());
            readings.removeAllViews();
            LinearLayout status = dataGroup(readings, "Connection");
            status.addView(rawLabel(tr(bridge.getState()), 20, text, true)); gap(status, 12);
            Integer signal = bridge.getSignalRssi(); dataRow(status, "Signal", signal == null ? tr("Unavailable") : signal + " dBm");
            dataRow(status, "Last handlebar event", value(bridge.getLastButton(), tr("None received")));
            dataRow(status, "Vehicle profile", value(bridge.getVehicleProfile(), tr("Not identified yet")));
            String telemetryStatus = bridge.getTelemetryStatus(); dataRow(status, "Telemetry status", telemetryStatus == null ? tr("Unavailable") : tr(telemetryStatus));
            LinearLayout vehicle = dataGroup(readings, "Vehicle information");
            Map<String, String> info = bridge.getVehicleInfo();
            if (info.isEmpty()) vehicle.addView(label("No vehicle information received.", 14, muted, false));
            else info.forEach((k, v) -> dataRow(vehicle, signalName(k), v));
            LinearLayout telemetry = dataGroup(readings, "Telemetry");
            Map<String, String> data = bridge.getTelemetry();
            Map<String, String> cruise = new LinkedHashMap<>(), other = new LinkedHashMap<>();
            data.forEach((k, v) -> (k.toLowerCase(Locale.ROOT).contains("cruise") ? cruise : other).put(k, v));
            if (other.isEmpty()) telemetry.addView(label("No telemetry received.", 14, muted, false));
            else other.forEach((k, v) -> dataRow(telemetry, signalName(k), v));
            if (!cruise.isEmpty()) {
                LinearLayout cruiseGroup = dataGroup(readings, "Cruise control status (read-only)");
                cruise.forEach((k, v) -> dataRow(cruiseGroup, signalName(k), v));
                cruiseGroup.addView(label("Shown as reported by the motorcycle. KTMnav cannot set or operate cruise control.", 13, muted, false));
            }
        };
        refresh.run(); gap(body, 16); fullButton(body, "Refresh dashboard data", false, refresh); gap(body, 8);
        body.addView(send, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        new AlertDialog.Builder(this).setTitle(tr("Dashboard data & controls")).setView(scroller).setPositiveButton(tr("Close"), null).show();
    }
    private void includedFeatures() {
        LinearLayout body = dialogBody();
        body.addView(label("No purchases, accounts or subscriptions.", 14, muted, false));
        LinearLayout sources = dataGroup(body, "Navigation integrations");
        sources.addView(label("Google Maps, Waze, TomTom GO, OsmAnd, Kurviger, Calimoto, Organic Maps, Locus Map and CoMaps forward turns. HERE WeGo, Sygic and Mapy.cz can be started from Navigate.", 14, text, false)); gap(sources, 8);
        LinearLayout bikes = dataGroup(body, "Multiple bikes");
        LinearLayout background = dataGroup(body, "Low-power background sync");
        LinearLayout added = dataGroup(body, "New in 1.5 · all free");
        String[] newFeatures = {"Navigate from KTMnav with favourites and recents", "Imported GPX routes", "Waze forwarding", "Faster reconnect after a dropped link", "Motorcycle silhouettes and bundled app icons"};
        for (String feature : newFeatures) { added.addView(label("• " + tr(feature), 14, text, false)); gap(added, 4); }
        LinearLayout earlier = dataGroup(body, "Since 1.3");
        String[] earlierFeatures = {"Messages on dashboard", "Calls", "Button modes & gestures", "Ride tools", "OBD-II adapter (read-only)", "Dashboard symbol test & calibration"};
        for (String feature : earlierFeatures) { earlier.addView(label("• " + tr(feature), 14, text, false)); gap(earlier, 4); }
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("Included features")).setView(scroller).setPositiveButton(tr("Close"), null).create();
        fullButton(sources, "Configure navigation apps", false, () -> { dialog.dismiss(); switchTab(2); });
        fullButton(bikes, "Open Garage", false, () -> { dialog.dismiss(); switchTab(1); });
        fullButton(background, "Connection settings", false, () -> { dialog.dismiss(); switchTab(3); });
        gap(added, 8); fullButton(added, "Open Riding features", false, () -> { dialog.dismiss(); tab = 3; render(); scroll.post(() -> scroll.scrollTo(0, findFeatures())); });
        dialog.show();
    }
    private static final String[] MODE_SUMMARIES = {
        "Your own action for each button, gesture by gesture.",
        "UP and DOWN change volume, double tap skips tracks, SET plays or pauses.",
        "UP and DOWN move focus, SET selects, long-press SET long-presses, BACK goes back.",
        "Handlebar buttons do nothing on the phone."
    };
    private HandlebarMappings.Mode handlebarMode() { return HandlebarMappings.Mode.fromPreference(prefs().getString(HandlebarMappings.PREF_MODE, null)); }
    void handlebarMappings() {
        LinearLayout body = dialogBody();
        body.addView(label("Gen 3 handlebar buttons. Set up while parked.", 14, muted, false)); gap(body, 8);
        LinearLayout enableGroup = column(); enableGroup.setBackground(shape(raised, RADIUS_TILE, false)); enableGroup.setClipToOutline(true); body.addView(enableGroup, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout mappings = column();
        Switch enabled = switchRow(enableGroup, 0, "Enable custom mappings", null, prefs().getBoolean("handlebar_mappings_enabled", false), (v, on) -> { prefs().edit().putBoolean("handlebar_mappings_enabled", on).apply(); mappings.removeAllViews(); populateMappings(mappings); bridge.settingsChanged(); });
        TextView state = label("", 13, muted, false); gap(body, 8); body.addView(state);
        LinearLayout modeGroup = dataGroup(body, "Button mode");
        Button mode = button("", false, () -> {}); modeGroup.addView(mode, new LinearLayout.LayoutParams(-1, -2)); gap(modeGroup, 8);
        TextView modeSummary = label("", 14, text, false); modeGroup.addView(modeSummary); gap(modeGroup, 8);
        modeGroup.addView(label("Long-press UP changes mode. Long-press DOWN opens navigation. While a call rings, SET answers and BACK silences.", 13, muted, false));
        body.addView(mappings);
        gap(body, 16); Button navApp = button("", false, () -> {}); body.addView(navApp, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout access = dataGroup(body, "Phone access");
        TextView screenReady = label("", 14, text, false); access.addView(screenReady);
        Button screen = button("Allow screen controls", false, () -> new AlertDialog.Builder(this)
            .setTitle(tr("Allow handlebar screen controls?"))
            .setMessage(tr("Android Accessibility access lets KTMnav inspect the current screen’s controls to scroll, move focus, select or long-press the focused item, and go back when you tap, double-tap or long-press a mapped handlebar button. If a control cannot be operated directly, KTMnav touches or long-presses its position on the screen instead. Screen text is not recorded or sent anywhere. Enable KTMnav handlebar controls in the next Android screen. You can turn access off there at any time."))
            .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Open Accessibility settings"), (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (ActivityNotFoundException e) { toast("Open Accessibility in Android settings."); }
            }).show());
        gap(access, 8); access.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        TextView restricted = label("If Android blocks access for this downloaded APK, open App info → More → Allow restricted settings, then return to Accessibility.", 13, muted, false); gap(access, 8); access.addView(restricted);
        TextView mediaReady = label("", 14, text, false); gap(access, 12); access.addView(mediaReady);
        Button media = button("Allow media controls", false, this::openNotificationAccess); gap(access, 8); access.addView(media, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 8); LinearLayout legacy = column(); legacy.setBackground(shape(raised, RADIUS_TILE, false)); legacy.setClipToOutline(true); body.addView(legacy, new LinearLayout.LayoutParams(-1, -2));
        prefRow(legacy, 0, "BACK opens KTMnav when mappings are off", null, "handlebar_return", true);
        gap(body, 12); fullButton(body, "Restore default mappings", false, () -> new AlertDialog.Builder(this).setTitle(tr("Restore default mappings?"))
            .setMessage(tr("UP scrolls up, DOWN scrolls down, SET selects the focused item, and BACK goes back. Double-tap and long-press assignments are cleared. Your enable switch, button mode and Android permissions stay as they are."))
            .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Restore"), (d, w) -> {
                SharedPreferences.Editor editor = prefs().edit(); for (String key : HandlebarMappings.buttons()) for (HandlebarMappings.Gesture g : HandlebarMappings.Gesture.values()) editor.remove(HandlebarMappings.preferenceKey(key, g)); editor.apply(); bridge.settingsChanged(); mappings.removeAllViews(); populateMappings(mappings);
            }).show());
        TextView last = label("", 13, muted, false); gap(body, 16); body.addView(last);
        gap(body, 8); body.addView(label("Controls your phone only, not the motorcycle or cruise control.", 13, muted, false));
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("Handlebar button mappings")).setView(scroller).setPositiveButton(tr("Close"), null).create();
        HandlebarMappings.Mode[] shown = {handlebarMode()};
        Runnable updateMode = () -> {
            HandlebarMappings.Mode m = handlebarMode();
            setTextIfChanged(mode, tr("Button mode: ") + tr(m.getLabel()));
            setTextIfChanged(modeSummary, tr(MODE_SUMMARIES[m.ordinal()]));
            setTextIfChanged(navApp, tr("Navigation app: ") + navAppName(prefs().getString(HandlebarController.PREF_PREFERRED_NAV_APP, null)));
            if (m != shown[0]) { shown[0] = m; mappings.removeAllViews(); populateMappings(mappings); }
        };
        mode.setOnClickListener(v -> {
            HandlebarMappings.Mode[] modes = HandlebarMappings.Mode.values(); String[] labels = new String[modes.length];
            for (int n = 0; n < modes.length; n++) labels[n] = tr(modes[n].getLabel()) + "\n" + tr(MODE_SUMMARIES[n]);
            new AlertDialog.Builder(this).setTitle(tr("Button mode")).setSingleChoiceItems(labels, handlebarMode().ordinal(), (d, n) -> {
                prefs().edit().putString(HandlebarMappings.PREF_MODE, modes[n].name()).apply(); bridge.settingsChanged(); d.dismiss(); updateMode.run();
            }).setNegativeButton(tr("Cancel"), null).show();
        });
        navApp.setOnClickListener(v -> chooseNavApp(updateMode));
        Runnable[] refresh = new Runnable[1]; refresh[0] = () -> {
            setTextIfChanged(state, tr(enabled.isChecked() ? "Custom mappings on · real presses run the actions below" : "Custom mappings off · saved assignments are kept"));
            boolean screenOk = HandlebarController.isAccessibilityConnected(), mediaOk = notificationAccess();
            setTextIfChanged(screenReady, tr("Screen controls: ") + tr(screenOk ? "Ready" : "Accessibility access needed"));
            setTextIfChanged(mediaReady, tr("Media controls: ") + tr(mediaOk ? "Ready" : "Notification access needed"));
            int screenVisibility = screenOk ? View.GONE : View.VISIBLE, mediaVisibility = mediaOk ? View.GONE : View.VISIBLE;
            if (screen.getVisibility() != screenVisibility) { screen.setVisibility(screenVisibility); restricted.setVisibility(screenVisibility); }
            if (media.getVisibility() != mediaVisibility) media.setVisibility(mediaVisibility);
            setTextIfChanged(last, tr("Last real button: ") + value(bridge.getLastButton(), tr("None received")) + "\n" + value(bridge.getLastHandlebarActionResult(), tr("No mapped action received yet.")));
            updateMode.run();
            if (dialog.isShowing()) handler.postDelayed(refresh[0], 750);
        };
        populateMappings(mappings); dialog.setOnShowListener(d -> refresh[0].run()); dialog.setOnDismissListener(d -> { handler.removeCallbacks(refresh[0]); if (tab == 3) render(); }); dialog.show();
    }
    private String navAppName(String pkg) {
        if (pkg == null || pkg.isEmpty() || HandlebarController.GOOGLE_MAPS_PACKAGE.equals(pkg)) return "Google Maps";
        return NavApps.nameOf(pkg);
    }
    private void chooseNavApp(Runnable after) {
        List<String> names = new ArrayList<>(), packages = new ArrayList<>();
        for (NavApps.App app : NavApps.ALL) { String installed = installedVariant(app.canonicalPackage()); if (installed != null) { names.add(app.name); packages.add(installed); } }
        if (packages.isEmpty()) { toast("No supported navigation app is installed. Long-press DOWN needs Google Maps or another navigation app."); return; }
        String current = prefs().getString(HandlebarController.PREF_PREFERRED_NAV_APP, HandlebarController.GOOGLE_MAPS_PACKAGE);
        new AlertDialog.Builder(this).setTitle(tr("Navigation app for long-press DOWN")).setSingleChoiceItems(names.toArray(new String[0]), packages.indexOf(current), (d, n) -> {
            prefs().edit().putString(HandlebarController.PREF_PREFERRED_NAV_APP, packages.get(n)).apply(); bridge.settingsChanged(); d.dismiss(); after.run();
        }).setNegativeButton(tr("Cancel"), null).show();
    }
    void setTextIfChanged(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    private void populateMappings(LinearLayout parent) {
        HandlebarMappings.Mode mode = handlebarMode();
        if (mode == HandlebarMappings.Mode.OFF) { gap(parent, 12); parent.addView(label("Button mode is Off. Real handlebar presses are ignored on the phone. Your custom assignments are kept.", 14, muted, false)); return; }
        if (mode != HandlebarMappings.Mode.CUSTOM) {
            LinearLayout preset = dataGroup(parent, "Assignments in this mode");
            for (String key : HandlebarMappings.buttons()) for (HandlebarMappings.Gesture g : HandlebarMappings.Gesture.values()) {
                HandlebarMappings.Action action = HandlebarMappings.isReserved(key, g) ? ("UP".equals(key) ? HandlebarMappings.Action.CYCLE_MODE : HandlebarMappings.Action.OPEN_NAV_APP) : HandlebarMappings.preset(mode, key, g);
                if (action != HandlebarMappings.Action.NONE) preset.addView(rawLabel(gestureName(key, g) + " · " + mappingLabel(action), 15, text, false));
            }
            gap(preset, 8); preset.addView(label("Switch to Custom to edit assignments. Your custom assignments are kept.", 13, muted, false));
            return;
        }
        for (String key : HandlebarMappings.buttons()) {
            for (HandlebarMappings.Gesture g : HandlebarMappings.Gesture.values()) {
                gap(parent, g == HandlebarMappings.Gesture.SINGLE ? 14 : 8); String pref = HandlebarMappings.preferenceKey(key, g);
                if (HandlebarMappings.isReserved(key, g)) {
                    Button fixed = button("", false, () -> {}); fixed.setText(gestureName(key, g) + " · " + mappingLabel("UP".equals(key) ? HandlebarMappings.Action.CYCLE_MODE : HandlebarMappings.Action.OPEN_NAV_APP) + " (" + tr("fixed") + ")");
                    fixed.setEnabled(false); fixed.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); parent.addView(fixed, new LinearLayout.LayoutParams(-1, -2)); continue;
                }
                HandlebarMappings.Action selected = HandlebarMappings.resolve(key, g, prefs().getString(pref, null));
                Button control = button("", false, () -> {
                    List<HandlebarMappings.Action> actions = new ArrayList<>();
                    for (HandlebarMappings.Action a : HandlebarMappings.Action.values()) if (a != HandlebarMappings.Action.ANSWER_CALL && a != HandlebarMappings.Action.SILENCE_CALL) actions.add(a);
                    String[] labels = new String[actions.size()]; int current = 0;
                    HandlebarMappings.Action value = HandlebarMappings.resolve(key, g, prefs().getString(pref, null));
                    for (int n = 0; n < labels.length; n++) { labels[n] = gestureActionLabel(g, actions.get(n)); if (actions.get(n) == value) current = n; }
                    new AlertDialog.Builder(this).setTitle(tr("Assign action: ") + gestureName(key, g)).setSingleChoiceItems(labels, current, (d, n) -> {
                        prefs().edit().putString(pref, actions.get(n).name()).apply(); bridge.settingsChanged(); d.dismiss(); parent.removeAllViews(); populateMappings(parent);
                    }).setNegativeButton(tr("Cancel"), null).show();
                });
                control.setText(gestureName(key, g) + " · " + gestureActionLabel(g, selected)); control.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }
    private String gestureName(String key, HandlebarMappings.Gesture g) { return g == HandlebarMappings.Gesture.SINGLE ? tr(key) : tr(key) + " " + tr(g.getLabel()); }
    private String gestureActionLabel(HandlebarMappings.Gesture g, HandlebarMappings.Action action) { return g == HandlebarMappings.Gesture.LONG && action == HandlebarMappings.Action.NONE ? tr("Same as tap") : mappingLabel(action); }
    private String mappingLabel(HandlebarMappings.Action action) {
        switch (action) {
            case NONE: return tr("No action");
            case BACK: return tr("Android Back");
            case FOCUS_NEXT: return tr("Next focus");
            case FOCUS_PREVIOUS: return tr("Previous focus");
            case CLICK_FOCUSED: return tr("Select focused item");
            case LONG_CLICK_FOCUSED: return tr("Long-press focused item");
            default: return tr(action.getLabel());
        }
    }
    private void buttonLab() {
        LinearLayout body = dialogBody();
        body.addView(chip("PHONE ONLY · SIMULATION", accentText)); gap(body, 12);
        body.addView(label("Duke 390 · Gen 3", 20, text, true)); gap(body, 4);
        body.addView(label("Preview only. Nothing is sent to the motorcycle.", 14, muted, false));
        LinearLayout preview = dataGroup(body, "Preview menu");
        String[] menu = {"Navigation", "Media", "Trip information"}; int[] selected = {0};
        TextView selection = label(menu[0], 21, text, true); preview.addView(selection);
        TextView event = label("No simulated button presses yet.", 14, muted, false); event.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); gap(body, 12); body.addView(event); gap(body, 12);
        java.util.function.Consumer<String> press = key -> {
            if ("UP".equals(key)) selected[0] = (selected[0] + menu.length - 1) % menu.length;
            if ("DOWN".equals(key)) selected[0] = (selected[0] + 1) % menu.length;
            if ("BACK".equals(key)) selected[0] = 0;
            selection.setText(tr(menu[selected[0]]));
            event.setText(tr("Simulated button: ") + key + ("SET".equals(key) ? " · " + tr("Selected") + ": " + tr(menu[selected[0]]) : ""));
        };
        for (String[] pair : new String[][]{{"UP", "DOWN"}, {"BACK", "SET"}}) {
            LinearLayout controls = row();
            grow(controls, button(pair[0], false, () -> press.accept(pair[0])));
            View space = new View(this); controls.addView(space, new LinearLayout.LayoutParams(dp(8), 1));
            grow(controls, button(pair[1], false, () -> press.accept(pair[1])));
            body.addView(controls); gap(body, 8);
        }
        gap(body, 8); body.addView(label("Cruise control is not supported over Bluetooth.", 13, muted, false));
        ScrollView scroller = new ScrollView(this); scroller.addView(body);
        new AlertDialog.Builder(this).setTitle(tr("Gen 3 button lab")).setView(scroller).setPositiveButton(tr("Close"), null).show();
    }
    LinearLayout dataGroup(LinearLayout parent, String title) {
        LinearLayout group = column(); group.setPadding(dp(16), dp(14), dp(16), dp(14)); group.setBackground(shape(raised, RADIUS_TILE, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(12); parent.addView(group, p);
        TextView h = label(title, 15, text, true); h.setAccessibilityHeading(true); group.addView(h); gap(group, 8); return group;
    }
    void dataRow(LinearLayout parent, String name, String value) {
        parent.addView(label(name, 13, muted, false)); TextView reading = rawLabel(value, 16, text, true); reading.setTextIsSelectable(true); parent.addView(reading); gap(parent, 10);
    }
    private static String signalName(String key) {
        switch (key) {
            case "ECU_Engine_Rpm": return "Engine RPM";
            case "ABS_Display_Front_Wheel_Speed": return "Front-wheel speed";
            case "ECU_Gear_Position": return "Gear";
            case "ECU_Water_Temperature": return "Coolant temperature";
            case "DASH_Fuel_Level": return "Fuel level";
            case "bCCU_Voltage": return "Bike voltage";
            case "ModelNumber": return "Model";
            case "SerialNumber": return "Serial number";
            case "HardwareRevision": return "Hardware version";
            case "FirmwareRevision": return "Firmware version";
            case "SoftwareRevision": return "Software version";
            default: return key;
        }
    }
    void choose(String title, String[] labels, String[] values, String key) { String current = prefs().getString(key, values[0]); int checked = Arrays.asList(values).indexOf(current); new AlertDialog.Builder(this).setTitle(tr(title)).setSingleChoiceItems(UiText.translate(this, labels), checked, (d, n) -> { prefs().edit().putString(key, values[n]).apply(); bridge.settingsChanged(); d.dismiss(); if ("theme".equals(key)) recreate(); else render(); }).setNegativeButton(tr("Cancel"), null).show(); }
    interface TextAction { void accept(String value); }
    void editText(String title, String initial, TextAction action) {
        EditText input = new EditText(this); input.setText(initial); input.setSingleLine(true); input.setHint(tr(title)); input.setContentDescription(tr(title)); input.setSelectAllOnFocus(true); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        LinearLayout box = column(); box.setPadding(dp(24), dp(8), dp(24), dp(8)); box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr(title)).setView(box).setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Save"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> { String s = input.getText().toString().trim(); if (s.isEmpty() && title.equals("Motorcycle name")) { input.setError(tr("Enter a name")); return; } action.accept(s); dialog.dismiss(); })); dialog.show();
    }
    String appVersion() {
        try { String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; return v == null ? "" : v; }
        catch (PackageManager.NameNotFoundException e) { return ""; }
    }
    private void showLogs() { TextView log = label(value(bridge.getLogs(), "No connection events yet."), 12, text, false); log.setTypeface(Typeface.MONOSPACE); log.setTextIsSelectable(true); log.setPadding(dp(20), dp(16), dp(20), dp(16)); ScrollView s = new ScrollView(this); s.addView(log); new AlertDialog.Builder(this).setTitle(tr("Connection log")).setView(s).setPositiveButton(tr("Close"), null).setNeutralButton(tr("Share"), (d, w) -> shareLogs()).show(); }
    private void shareLogs() {
        try { File file = new File(getCacheDir(), "ktmnav-diagnostics.txt"); String data = "KTMnav " + appVersion() + "\nAndroid " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + " " + Build.MODEL + "\nState: " + bridge.getState() + "\n\n" + bridge.getLogs(); try (FileOutputStream out = new FileOutputStream(file)) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
            Uri uri = Uri.parse("content://app.ktmnav.logs/diagnostics"); Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_STREAM, uri); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); i.setClipData(ClipData.newRawUri("KTMnav diagnostics", uri)); startActivity(Intent.createChooser(i, tr("Share diagnostics")));
        } catch (IOException | ActivityNotFoundException e) { toast("Couldn't share logs: " + e.getMessage()); }
    }
    private void shareManeuvers() {
        File folder = new File(getFilesDir(), "nav_capture");
        File[] files = folder.listFiles((directory, name) -> name.endsWith(".png"));
        if (files == null || files.length == 0) { toast("No maneuver captures yet. Enable capture and start navigation in a supported app."); return; }
        Arrays.sort(files, Comparator.comparing(File::getName));
        File archive = new File(getCacheDir(), "ktmnav-maneuvers.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 0; i < Math.min(files.length, 100); i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry(files[i].getName()));
                try (InputStream in = new FileInputStream(files[i])) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count); }
                zip.closeEntry();
            }
        } catch (IOException e) { toast("Could not export maneuver captures."); return; }
        Uri uri = Uri.parse("content://app.ktmnav.logs/maneuvers");
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("KTMnav maneuver captures", uri));
        try { startActivity(Intent.createChooser(share, tr("Export maneuver captures"))); }
        catch (ActivityNotFoundException e) { toast("No app available to share this archive."); }
    }
    private void about() { new AlertDialog.Builder(this).setTitle("KTMnav " + appVersion()).setMessage(tr("An independent navigation companion for compatible KTM and Husqvarna dashboards. Not affiliated with either manufacturer. No accounts, analytics or subscriptions; navigation and media data stay on your phone.")).setPositiveButton(tr("Close"), null).setNeutralButton(tr("Open-source licenses"), (d, w) -> licenses()).show(); }
    private void privacyPolicy() {
        String policy;
        try (InputStream in = getAssets().open("privacy-policy.txt")) {
            policy = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(java.util.stream.Collectors.joining("\n"));
        } catch (IOException e) { toast("Could not open privacy policy."); return; }
        TextView text = rawLabel(policy, 14, this.text, false); text.setTextIsSelectable(true); text.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Privacy policy").setView(scroll).setPositiveButton(tr("Close"), null).show();
    }
    private void licenses() {
        StringBuilder s = new StringBuilder(); try { for (String name : getAssets().list("licenses")) try (InputStream in = getAssets().open("licenses/" + name)) { s.append(name).append("\n\n").append(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(java.util.stream.Collectors.joining("\n"))).append("\n\n"); } } catch (IOException e) { s.append(e.getMessage()); }
        TextView v = rawLabel(tr("Uses OpenDash (MIT), Locus API (MIT), LiteRT (Apache 2.0), Material Symbols icons (Apache 2.0) and Chakra Petch (SIL Open Font License).") + "\n\n" + s, 13, text, false); v.setPadding(dp(20), dp(16), dp(20), dp(16)); v.setTextIsSelectable(true); ScrollView scroll = new ScrollView(this); scroll.addView(v); new AlertDialog.Builder(this).setTitle(tr("Open-source licenses")).setView(scroll).setPositiveButton(tr("Close"), null).show();
    }
    void toast(String message) { Toast.makeText(this, tr(message), Toast.LENGTH_LONG).show(); }

    private final class DashView extends View {
        String turn = "IDLE"; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DashView() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(line); canvas.drawCircle(cx, cy, dp(56), p);
            for (int n = 0; n < 36; n++) { double a = Math.toRadians(n * 10 - 90); float r = dp(60); canvas.drawLine(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, cx + (float) Math.cos(a) * (r + dp(n % 3 == 0 ? 5 : 2)), cy + (float) Math.sin(a) * (r + dp(n % 3 == 0 ? 5 : 2)), p); }
            canvas.save(); canvas.translate(cx, cy); canvas.scale(dp(1), dp(1)); p.setColor(accentIcon); p.setStrokeWidth(8); p.setStrokeCap(Paint.Cap.SQUARE); p.setStrokeJoin(Paint.Join.MITER); Path arrow = new Path(); String t = turn.toUpperCase(Locale.ROOT);
            if (t.equals("IDLE") || t.equals("NONE") || t.equals("UNKNOWN") || t.equals("UNDEFINED")) { p.setStyle(Paint.Style.FILL); p.setColor(muted); for (int n = -1; n <= 1; n++) canvas.drawCircle(n * 18, 0, 4, p); }
            else if (t.contains("ROUND") || t.startsWith("RAB_SECT_")) { canvas.drawArc(-22, -22, 22, 22, 80, 285, false, p); arrow.moveTo(11, -24); arrow.lineTo(22, -11); arrow.lineTo(8, -10); }
            else if (t.equals("END")) { p.setStyle(Paint.Style.FILL); canvas.drawCircle(0, 0, 24, p); p.setColor(surface); canvas.drawRect(-12, -12, 12, 12, p); }
            else if (t.contains("UTURN") || t.contains("U_TURN")) { arrow.moveTo(18, 30); arrow.lineTo(18, -10); arrow.cubicTo(18, -34, -18, -34, -18, -10); arrow.lineTo(-18, 9); arrow.moveTo(-30, -3); arrow.lineTo(-18, 9); arrow.lineTo(-6, -3); }
            else if (t.contains("RIGHT") || t.contains("LEFT")) { if (t.contains("LEFT")) canvas.scale(-1, 1); arrow.moveTo(-14, 32); arrow.lineTo(-14, 0); arrow.quadTo(-14, -12, -2, -12); arrow.lineTo(27, -12); arrow.moveTo(10, -29); arrow.lineTo(27, -12); arrow.lineTo(10, 5); }
            else { arrow.moveTo(0, 32); arrow.lineTo(0, -28); arrow.moveTo(-19, -9); arrow.lineTo(0, -28); arrow.lineTo(19, -9); }
            canvas.drawPath(arrow, p); canvas.restore(); p.setStyle(Paint.Style.STROKE);
        }
    }
}
