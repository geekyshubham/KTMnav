package app.ktmnav.backend;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.List;

/**
 * Starts turn-by-turn guidance in the rider's chosen navigation app from inside KTMnav. Tries the
 * app's own deep link first, then google.navigation and geo: forms, and finally just opens the app.
 * Locus Map is driven through its bundled API so guidance starts without a search step.
 */
public final class NavLauncher {
    private static final String TAG = "KTMnav.NavLaunch";
    public static final String PREF_APP = HandlebarController.PREF_PREFERRED_NAV_APP;
    public static final String PREF_TWO_WHEELER = "nav_two_wheeler";
    public static final String PREFS_NAME = "ktmnav";

    public static final class Result {
        public final boolean started;
        public final String message;

        Result(boolean started, String message) {
            this.started = started;
            this.message = message;
        }
    }

    private NavLauncher() {
    }

    /** The installed package of {@code app}, or null when none of its variants is installed. */
    public static String installedPackage(Context context, NavApps.App app) {
        if (context == null || app == null) return null;
        PackageManager pm = context.getPackageManager();
        for (String pkg : app.packages) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** The rider's chosen app when it is installed, otherwise the first installed catalogue app. */
    public static NavApps.App preferredApp(Context context, SharedPreferences prefs) {
        String pkg = prefs != null ? prefs.getString(PREF_APP, null) : null;
        NavApps.App chosen = NavApps.byPackage(pkg);
        if (chosen != null && installedPackage(context, chosen) != null) return chosen;
        for (NavApps.App a : NavApps.ALL) if (installedPackage(context, a) != null) return a;
        return NavApps.ALL.get(0);
    }

    public static Result navigate(Context context, NavApps.App app, NavDestination destination) {
        if (context == null || app == null || destination == null) return new Result(false, "Nothing to navigate to");
        String pkg = installedPackage(context, app);
        if (pkg == null) return new Result(false, app.name + " is not installed");
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean twoWheeler = prefs.getBoolean(PREF_TWO_WHEELER, false);

        if (NavApps.LOCUS.equals(app.id) && startLocus(context, destination)) {
            return new Result(true, "Started " + app.name);
        }
        PackageManager pm = context.getPackageManager();
        List<NavApps.Candidate> candidates = NavApps.candidates(app, pkg, destination, twoWheeler);
        for (NavApps.Candidate c : candidates) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(c.uri));
            if (!c.pkg.isEmpty()) intent.setPackage(c.pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (intent.resolveActivity(pm) == null) continue;
                context.startActivity(intent);
                AppLog.i(TAG, "Started " + app.name + " with " + c.uri.replaceAll("(?<=[=/|])[^&?/|]+", "…"));
                return new Result(true, "Started " + app.name);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "Could not start " + app.name + " with " + c.uri.substring(0, Math.min(24, c.uri.length())), e);
            }
        }
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return new Result(false, app.name + " cannot be opened");
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launch);
            return new Result(true, "Opened " + app.name + "; enter the destination there");
        } catch (RuntimeException e) {
            return new Result(false, "Could not open " + app.name);
        }
    }

    private static boolean startLocus(Context context, NavDestination d) {
        try {
            if (d.isPoint()) {
                locus.api.android.ActionBasics.INSTANCE.actionStartNavigation(context, d.title(), d.point.lat, d.point.lon);
            } else {
                locus.api.android.ActionBasics.INSTANCE.actionStartNavigation(context, d.query);
            }
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "Locus navigation API unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Destination carried by a share or geo: intent sent to KTMnav, or null. */
    public static NavDestination fromIntent(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text == null) return null;
                String s = text.toString().trim();
                // Shared places are usually "Name\nhttps://maps.app.goo.gl/..."; keep the link when it carries coordinates.
                NavDestination fromLink = NavDestination.of(s);
                if (fromLink != null && fromLink.isPoint()) {
                    String first = s.split("\n")[0].trim();
                    return first.startsWith("http") ? fromLink : fromLink.withLabel(first);
                }
                String firstLine = s.split("\n")[0].trim();
                return NavDestination.of(firstLine.isEmpty() ? s : firstLine);
            }
            if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
                Uri data = intent.getData();
                if (!"geo".equalsIgnoreCase(data.getScheme())) return null;
                return fromGeo(data.toString());
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Unreadable destination intent", e);
        }
        return null;
    }

    /**
     * Destination in a geo: URI. "geo:lat,lon", "geo:lat,lon?q=lat,lon(Label)" and "geo:0,0?q=lat,lon" become
     * points; "geo:0,0?q=Some place" becomes a text search. geo: URIs are opaque, so the query is read by hand.
     */
    static NavDestination fromGeo(String raw) {
        if (raw == null) return null;
        String query = null;
        int q = raw.indexOf("?q=");
        if (q < 0) q = raw.indexOf("&q=");
        if (q >= 0) {
            String rest = raw.substring(q + 3);
            int amp = rest.indexOf('&');
            if (amp >= 0) rest = rest.substring(0, amp);
            query = decode(rest).trim();
        }
        if (query != null && !query.isEmpty()) {
            // "lat,lon(Label)" carries the place name in parentheses after the coordinates.
            String name = null;
            String core = query;
            int paren = query.indexOf('(');
            if (paren > 0 && query.endsWith(")")) {
                name = query.substring(paren + 1, query.length() - 1).trim();
                core = query.substring(0, paren).trim();
                if (name.isEmpty()) name = null;
            }
            NavDestination fromQuery = NavDestination.of(core);
            if (fromQuery != null && fromQuery.isPoint()) {
                return name != null ? fromQuery.withLabel(name) : fromQuery;
            }
            NavDestination point = NavDestination.of(raw.substring(0, q));
            if (point != null && point.isPoint() && !raw.startsWith("geo:0,0") && !raw.startsWith("geo:0.0,0.0")) {
                return fromQuery != null ? point.withLabel(core) : point;
            }
            return fromQuery;
        }
        NavDestination point = NavDestination.of(raw);
        return point != null && point.isPoint() && !raw.startsWith("geo:0,0") && !raw.startsWith("geo:0.0,0.0") ? point : null;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%20"), java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
