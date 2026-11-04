package app.ktmnav.backend.ride;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

public final class WaypointLauncher {
    public static final String MAPS_PACKAGE = "com.google.android.apps.maps";

    private WaypointLauncher() {
    }

    public static Waypoint load(SharedPreferences prefs) {
        if (prefs == null) return null;
        return Waypoint.parse(prefs.getString(RidePrefs.WAYPOINT, null), prefs.getString(RidePrefs.WAYPOINT_LABEL, null));
    }

    public static boolean save(SharedPreferences prefs, String input, String label) {
        Waypoint w = Waypoint.parse(input, label);
        if (prefs == null || w == null) return false;
        SharedPreferences.Editor e = prefs.edit().putString(RidePrefs.WAYPOINT, w.toPref());
        if (w.label != null) e.putString(RidePrefs.WAYPOINT_LABEL, w.label);
        else e.remove(RidePrefs.WAYPOINT_LABEL);
        e.apply();
        return true;
    }

    public static void clear(SharedPreferences prefs) {
        if (prefs != null) prefs.edit().remove(RidePrefs.WAYPOINT).remove(RidePrefs.WAYPOINT_LABEL).apply();
    }

    public static Intent intentFor(Context context, String latLon) {
        Waypoint w = Waypoint.parse(latLon);
        return w == null ? null : intentFor(context, w);
    }

    public static Intent intentFor(Context context, Waypoint w) {
        if (w == null) return null;
        PackageManager pm = context != null ? context.getPackageManager() : null;
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse(w.navigationUri())).setPackage(MAPS_PACKAGE);
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pm != null && nav.resolveActivity(pm) != null) return nav;
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse(w.geoUri()));
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return geo;
    }
}
