package app.ktmnav;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.ktmnav.backend.NavApps;
import app.ktmnav.backend.NavLauncher;
import app.ktmnav.backend.ride.RideFormat;
import app.ktmnav.backend.ride.RideServices;
import app.ktmnav.backend.ride.RideStats;
import app.ktmnav.backend.ride.RideStore;
import app.ktmnav.backend.ride.RouteStore;
import app.ktmnav.backend.ride.TrackPoint;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

final class RidesScreen extends FeatureScreen {
    static final int[] BANDS = {0xFF43A047, 0xFFFBC02D, 0xFFFB8C00, 0xFFE53935};
    private static final String[] BAND_LABELS = {"Under 30 km/h", "30 to 60 km/h", "60 to 90 km/h", "90 km/h and faster"};
    private AlertDialog listDialog;

    RidesScreen(MainActivity activity) {
        super(activity);
    }

    private RideStore store() {
        RideServices services = a.bridge.getRideServices();
        RideStore s = services != null ? services.getStore() : null;
        return s != null ? s : RideServices.storeFor(a);
    }

    private RouteStore routes() {
        return new RouteStore(a.getFilesDir());
    }

    void show() {
        LinearLayout b = body();
        LinearLayout routeList = a.column();
        b.addView(routeList);
        LinearLayout list = a.column();
        b.addView(list);
        list.addView(a.label("Loading rides…", 15, a.muted, false));
        listDialog = sheet("Rides", b);
        listDialog.show();
        load(routeList, list);
    }

    private void load(LinearLayout routeList, LinearLayout list) {
        RideStore s = store();
        RouteStore r = routes();
        new Thread(() -> {
            List<RouteStore.Route> planned = r.list();
            List<RideStore.Ride> rides = s.list();
            a.handler.post(() -> { if (listDialog.isShowing()) { renderRoutes(routeList, list, planned); render(list, rides); } });
        }, "ktmnav-rides").start();
    }

    private void renderRoutes(LinearLayout routeList, LinearLayout list, List<RouteStore.Route> planned) {
        routeList.removeAllViews();
        LinearLayout group = a.dataGroup(routeList, "Planned routes");
        if (planned.isEmpty()) {
            note(group, "Import a GPX route from Kurviger, Calimoto or any planner, then start guidance to it from here. Files opened from other apps land here too.");
        }
        for (RouteStore.Route route : planned) {
            LinearLayout item = a.column();
            item.setId(R.id.route_row);
            item.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            item.setMinimumHeight(a.dp(52));
            item.setBackground(a.ripple(a.shape(a.surface, 12, false), 12));
            item.setFocusable(true);
            item.setClickable(true);
            item.addView(a.rawLabel(route.title.isEmpty() ? route.name : route.title, 16, a.text, true));
            String line = RideFormat.distance(route.stats.distanceM) + " · " + fmt("%d points", route.stats.points);
            item.addView(a.rawLabel(line, 14, a.muted, false));
            item.setContentDescription(route.title + ", " + line);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            item.setOnClickListener(v -> showRoute(route));
            a.gap(group, 8);
            group.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        a.withIcon(action(group, "Import GPX route", false, a::pickGpxRoute), R.drawable.ic_upload);
        a.gap(routeList, 8);
    }

    /** Detail sheet for an imported route with the route trace and a one-tap start in the chosen app. */
    void showRoute(RouteStore.Route route) {
        LinearLayout b = body();
        title(b, route.title.isEmpty() ? route.name : route.title);
        RideTrackView track = new RideTrackView(a);
        track.setColors(a.raised, BANDS, 0xFF1E88E5, a.text);
        b.addView(track, new LinearLayout.LayoutParams(-1, a.dp(220)));
        TextView loading = note(b, "Loading route…");
        LinearLayout stats = a.dataGroup(b, "Route");
        a.dataRow(stats, tr("Distance"), RideFormat.distance(route.stats.distanceM));
        a.dataRow(stats, tr("Elevation gain"), Math.round(route.stats.elevationGainM) + " m");
        a.dataRow(stats, tr("Track points"), String.valueOf(route.stats.points));
        NavApps.App app = a.preferredApp();
        String pkg = a.installedVariant(app.canonicalPackage());
        note(b, NavApps.GOOGLE_MAPS.equals(app.id)
            ? "Google Maps gets the start, up to nine of the sharpest bends and the end of the route. Other apps are sent to the end point."
            : "This app is sent to the route’s end point; only Google Maps accepts via-points from KTMnav.");
        Button navigate = a.withIcon(action(b, pkg != null ? "Navigate this route" : "Install the navigation app", true, null), R.drawable.ic_near_me);
        navigate.setEnabled(false);
        Button share = a.withIcon(action(b, "Share GPX", false, () -> share(route)), R.drawable.ic_share);
        Button delete = a.withIcon(action(b, "Delete route", false, null), R.drawable.ic_delete);
        AlertDialog dialog = sheet("Planned route", b);
        delete.setOnClickListener(v -> confirm("Delete this route?", "The GPX file is removed from this phone. This cannot be undone.", "Delete", () -> {
            if (routes().delete(route.file)) {
                a.toast("Route deleted.");
                dialog.dismiss();
                if (listDialog != null && listDialog.isShowing()) { listDialog.dismiss(); show(); }
            } else {
                a.toast("Could not delete this route.");
            }
        }));
        dialog.show();
        RouteStore r = routes();
        new Thread(() -> {
            List<TrackPoint> points;
            try { points = r.points(route.file); } catch (IOException e) { points = null; }
            List<TrackPoint> result = points;
            a.handler.post(() -> {
                if (!dialog.isShowing()) return;
                if (result == null || result.size() < 2) {
                    loading.setText(tr("No route points in this file."));
                    return;
                }
                track.setRide(result, route.stats);
                loading.setVisibility(View.GONE);
                navigate.setEnabled(true);
                navigate.setOnClickListener(v -> {
                    if (pkg == null) { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + app.canonicalPackage()))); return; }
                    double[] lats = new double[result.size()], lons = new double[result.size()];
                    for (int i = 0; i < result.size(); i++) { lats[i] = result.get(i).lat; lons[i] = result.get(i).lon; }
                    boolean twoWheeler = a.prefs().getBoolean(NavLauncher.PREF_TWO_WHEELER, false);
                    boolean started = false;
                    for (NavApps.Candidate c : NavApps.routeCandidates(app, pkg, lats, lons, twoWheeler)) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(c.uri)).setPackage(c.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (intent.resolveActivity(a.getPackageManager()) == null) continue;
                        try { a.startActivity(intent); started = true; break; } catch (RuntimeException ignored) {}
                    }
                    a.toast(started ? "Started " + app.name : "Could not start " + app.name);
                });
            });
        }, "ktmnav-route-track").start();
    }

    private void share(RouteStore.Route route) {
        Uri uri = Uri.parse("content://app.ktmnav.logs/routes/" + route.name);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(route.name, uri));
        try { a.startActivity(Intent.createChooser(send, tr("Share GPX"))); }
        catch (ActivityNotFoundException e) { a.toast("No app available to share this route."); }
    }

    private String date(long ms) {
        if (ms == TrackPoint.NO_TIME) return tr("Unknown date");
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms));
    }

    private void render(LinearLayout list, List<RideStore.Ride> rides) {
        list.removeAllViews();
        if (rides.isEmpty()) {
            LinearLayout empty = a.dataGroup(list, "No rides yet");
            note(empty, "Turn on recording in Ride tools.");
            return;
        }
        for (RideStore.Ride ride : rides) {
            RideStats st = ride.stats;
            LinearLayout item = a.column();
            item.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
            item.setMinimumHeight(a.dp(52));
            item.setBackground(a.ripple(a.shape(a.raised, 12, false), 12));
            item.setFocusable(true);
            item.setClickable(true);
            String when = date(ride.startMs);
            TextView head = a.rawLabel(ride.recording ? when + " · " + tr("Recording now") : when, 16, a.text, true);
            item.addView(head);
            String line1 = RideFormat.distance(st.distanceM) + " · " + RideFormat.duration(st.durationMs);
            String line2 = fmt("Max %s · Avg %s", RideFormat.speed(st.maxKmh), RideFormat.speed(st.avgMovingKmh));
            item.addView(a.rawLabel(line1, 15, a.text, false));
            item.addView(a.rawLabel(line2, 14, a.muted, false));
            item.setContentDescription(when + ", " + line1 + ", " + line2);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            item.setOnClickListener(v -> detail(ride, list));
            a.gap(list, 8);
            list.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void detail(RideStore.Ride ride, LinearLayout list) {
        RideStats st = ride.stats;
        LinearLayout b = body();
        title(b, date(ride.startMs));
        RideTrackView track = new RideTrackView(a);
        track.setColors(a.raised, BANDS, 0xFF1E88E5, a.text);
        b.addView(track, new LinearLayout.LayoutParams(-1, a.dp(220)));
        TextView loading = note(b, "Loading track…");
        LinearLayout legend = a.dataGroup(b, "Track colours");
        for (int i = 0; i < BANDS.length; i++) legendRow(legend, BANDS[i], tr(BAND_LABELS[i]), false);
        legendRow(legend, 0xFF1E88E5, tr("Start point"), true);
        legendRow(legend, a.text, tr("End point"), true);

        LinearLayout stats = a.dataGroup(b, "Ride statistics");
        a.dataRow(stats, tr("Distance"), RideFormat.distance(st.distanceM));
        a.dataRow(stats, tr("Duration"), RideFormat.duration(st.durationMs));
        a.dataRow(stats, tr("Moving time"), RideFormat.duration(st.movingMs));
        a.dataRow(stats, tr("Maximum speed"), RideFormat.speed(st.maxKmh));
        a.dataRow(stats, tr("Average moving speed"), RideFormat.speed(st.avgMovingKmh));
        a.dataRow(stats, tr("Elevation gain"), Math.round(st.elevationGainM) + " m");
        a.dataRow(stats, tr("Track points"), String.valueOf(st.points));

        Button share = a.withIcon(action(b, "Share GPX", true, () -> share(ride)), R.drawable.ic_share);
        Button delete = a.withIcon(action(b, "Delete ride", false, null), R.drawable.ic_delete);
        if (ride.recording) {
            enable(delete, false);
            a.gap(b, 4);
            note(b, "This ride is still recording. Stop recording before deleting it.");
        }
        AlertDialog dialog = sheet("Ride details", b);
        delete.setOnClickListener(v -> confirm("Delete this ride?", "The GPX file is removed from this phone. This cannot be undone.", "Delete", () -> {
            if (store().delete(ride.file)) {
                a.toast("Ride deleted.");
                dialog.dismiss();
                if (listDialog != null && listDialog.isShowing()) { listDialog.dismiss(); show(); }
            } else {
                a.toast("Could not delete this ride.");
            }
        }));
        dialog.show();
        RideStore s = store();
        new Thread(() -> {
            List<TrackPoint> points;
            try { points = s.points(ride.file); } catch (IOException e) { points = null; }
            List<TrackPoint> result = points;
            a.handler.post(() -> {
                if (!dialog.isShowing()) return;
                if (result == null || result.isEmpty()) {
                    loading.setText(tr("No track points in this ride."));
                } else {
                    track.setRide(result, st);
                    loading.setVisibility(View.GONE);
                }
                enable(share, true);
            });
        }, "ktmnav-ride-track").start();
    }

    private void legendRow(LinearLayout parent, int color, String label, boolean dot) {
        LinearLayout r = a.row();
        r.setMinimumHeight(a.dp(32));
        View swatch = new View(a);
        swatch.setBackground(a.shape(color, dot ? 8 : 2, false));
        swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout slot = a.row();
        slot.setGravity(android.view.Gravity.CENTER);
        slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        slot.addView(swatch, new LinearLayout.LayoutParams(a.dp(dot ? 14 : 24), a.dp(dot ? 14 : 6)));
        r.addView(slot, new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        TextView t = a.rawLabel(label, 14, a.text, false);
        t.setPadding(a.dp(12), 0, 0, 0);
        a.grow(r, t);
        parent.addView(r);
    }

    private void share(RideStore.Ride ride) {
        Uri uri = Uri.parse("content://app.ktmnav.logs/rides/" + ride.name);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(ride.name, uri));
        try { a.startActivity(Intent.createChooser(send, tr("Share GPX"))); }
        catch (ActivityNotFoundException e) { a.toast("No app available to share this ride."); }
    }
}
