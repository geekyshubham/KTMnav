package app.ktmnav.backend;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue of navigation apps KTMnav knows, and the pure (no Android types) construction of the
 * URIs that start turn-by-turn guidance in each of them. The Android side (NavLauncher) turns the
 * ordered candidates into intents and uses the first one an installed app can handle.
 */
public final class NavApps {

    public static final class App {
        public final String id;
        public final String name;
        public final String[] packages;
        /** KTMnav can read this app's guidance and forward it to the dashboard. */
        public final boolean forwards;
        public final String monogram;

        App(String id, String name, boolean forwards, String... packages) {
            this.id = id;
            this.name = name;
            this.forwards = forwards;
            this.packages = packages;
            this.monogram = name.substring(0, 1).toUpperCase(Locale.ROOT);
        }

        public String canonicalPackage() {
            return packages[0];
        }

        public boolean owns(String pkg) {
            if (pkg == null) return false;
            for (String p : packages) if (p.equals(pkg)) return true;
            return false;
        }
    }

    /** One way to open a destination: a URI and the package that must handle it ("" = any app). */
    public static final class Candidate {
        public final String uri;
        public final String pkg;

        Candidate(String uri, String pkg) {
            this.uri = uri;
            this.pkg = pkg;
        }

        @Override
        public String toString() {
            return (pkg.isEmpty() ? "*" : pkg) + " " + uri;
        }
    }

    public static final String GOOGLE_MAPS = "google_maps";
    public static final String TOMTOM = "tomtom";
    public static final String OSMAND = "osmand";
    public static final String KURVIGER = "kurviger";
    public static final String CALIMOTO = "calimoto";
    public static final String ORGANIC_MAPS = "organic_maps";
    public static final String LOCUS = "locus";
    public static final String COMAPS = "comaps";
    public static final String WAZE = "waze";
    public static final String HERE = "here";
    public static final String SYGIC = "sygic";
    public static final String MAPY = "mapy";

    public static final List<App> ALL = Collections.unmodifiableList(Arrays.asList(
            new App(GOOGLE_MAPS, "Google Maps", true, "com.google.android.apps.maps"),
            new App(WAZE, "Waze", true, "com.waze"),
            new App(TOMTOM, "TomTom GO", true, "com.tomtom.gplay.navapp"),
            new App(OSMAND, "OsmAnd", true, "net.osmand", "net.osmand.plus"),
            new App(KURVIGER, "Kurviger", true, "com.kurviger.app"),
            new App(CALIMOTO, "Calimoto", true, "com.calimoto.calimoto"),
            new App(ORGANIC_MAPS, "Organic Maps", true, "app.organicmaps"),
            new App(LOCUS, "Locus Map", true, "menion.android.locus", "menion.android.locus.pro"),
            new App(COMAPS, "CoMaps", true, "app.comaps.google", "app.comaps.fdroid", "app.comaps", "app.comaps.huawei"),
            new App(HERE, "HERE WeGo", false, "com.here.app.maps"),
            new App(SYGIC, "Sygic", false, "com.sygic.aura"),
            new App(MAPY, "Mapy.cz", false, "cz.seznam.mapy")));

    private NavApps() {
    }

    public static App byId(String id) {
        for (App a : ALL) if (a.id.equals(id)) return a;
        return null;
    }

    public static App byPackage(String pkg) {
        for (App a : ALL) if (a.owns(pkg)) return a;
        return null;
    }

    public static App byName(String name) {
        for (App a : ALL) if (a.name.equals(name)) return a;
        return null;
    }

    public static String[] variants(String pkg) {
        App a = byPackage(pkg);
        return a != null ? a.packages.clone() : new String[]{pkg};
    }

    /** Display name for a package, falling back to the package itself. */
    public static String nameOf(String pkg) {
        App a = byPackage(pkg);
        return a != null ? a.name : pkg;
    }

    /**
     * Ordered ways to start guidance to {@code d} in {@code app}: the app's own deep link first,
     * then the widely supported google.navigation and geo: forms. {@code twoWheeler} asks Google Maps
     * for two-wheeler routing where it is offered.
     */
    public static List<Candidate> candidates(App app, String installedPackage, NavDestination d, boolean twoWheeler) {
        List<Candidate> out = new ArrayList<>();
        if (app == null || d == null) return out;
        String pkg = installedPackage != null ? installedPackage : app.canonicalPackage();
        String coords = d.isPoint() ? d.point.toPref() : null;
        String text = enc(d.query);
        String labelOrQuery = enc(d.label != null ? d.label : d.query);
        switch (app.id) {
            case GOOGLE_MAPS:
                out.add(new Candidate("google.navigation:q=" + (coords != null ? coords : text) + "&mode=" + (twoWheeler ? "l" : "d"), pkg));
                break;
            case WAZE:
                out.add(new Candidate(coords != null ? "waze://?ll=" + coords + "&navigate=yes" : "waze://?q=" + text + "&navigate=yes", pkg));
                out.add(new Candidate(coords != null ? "https://waze.com/ul?ll=" + coords + "&navigate=yes" : "https://waze.com/ul?q=" + text + "&navigate=yes", pkg));
                break;
            case OSMAND:
                if (coords != null) {
                    out.add(new Candidate("osmand.api://navigate?dest_lat=" + fmt(d.point.lat) + "&dest_lon=" + fmt(d.point.lon)
                            + "&dest_name=" + labelOrQuery + "&profile=motorcycle&force=true", pkg));
                } else {
                    out.add(new Candidate("osmand.api://navigate_search?dest_search=" + text + "&profile=motorcycle&force=true&show_search_results=true", pkg));
                }
                out.add(new Candidate("google.navigation:q=" + (coords != null ? coords : text), pkg));
                break;
            case TOMTOM:
                out.add(new Candidate("google.navigation:q=" + (coords != null ? coords : text), pkg));
                break;
            case HERE:
                if (coords != null) out.add(new Candidate("here.directions://v1.0/mylocation/" + coords + "?m=d", pkg));
                break;
            case SYGIC:
                if (coords != null) out.add(new Candidate("com.sygic.aura://coordinate|" + fmt(d.point.lon) + "|" + fmt(d.point.lat) + "|drive", pkg));
                break;
            case KURVIGER:
                if (coords != null) out.add(new Candidate("https://kurviger.de/?point=" + coords + "&vehicle=motorcycle", pkg));
                break;
            default:
                break;
        }
        out.add(new Candidate(geoUri(d), pkg));
        return out;
    }

    /**
     * Ways to follow a planned route (an imported GPX) in {@code app}: Google Maps gets a directions link
     * with the start, up to nine via-points chosen where the route bends most, and the end (RideConnect's
     * approach); every other app is sent to the final point, because none exposes a public multi-stop
     * intent. {@code lats}/{@code lons} are parallel arrays in route order.
     */
    public static List<Candidate> routeCandidates(App app, String installedPackage, double[] lats, double[] lons, boolean twoWheeler) {
        List<Candidate> out = new ArrayList<>();
        if (app == null || lats == null || lons == null || lats.length < 2 || lats.length != lons.length) return out;
        int last = lats.length - 1;
        String pkg = installedPackage != null ? installedPackage : app.canonicalPackage();
        if (GOOGLE_MAPS.equals(app.id)) {
            StringBuilder sb = new StringBuilder("https://maps.google.com/maps?saddr=").append(fmt(lats[0])).append(',').append(fmt(lons[0])).append("&daddr=");
            for (int i : viaPoints(lats, lons, 9)) {
                sb.append(fmt(lats[i])).append(',').append(fmt(lons[i])).append("+to:");
            }
            sb.append(fmt(lats[last])).append(',').append(fmt(lons[last])).append("&dirflg=").append(twoWheeler ? "l" : "d");
            out.add(new Candidate(sb.toString(), pkg));
        }
        NavDestination end = NavDestination.of(fmt(lats[last]) + "," + fmt(lons[last]), "Route end", 0L);
        out.addAll(candidates(app, installedPackage, end, twoWheeler));
        return out;
    }

    /** Indices (ascending) of the {@code max} interior points where the heading changes most. */
    static List<Integer> viaPoints(double[] lats, double[] lons, int max) {
        List<Integer> out = new ArrayList<>();
        int interior = lats.length - 2;
        if (interior <= 0) return out;
        if (interior <= max) {
            for (int i = 1; i <= interior; i++) out.add(i);
            return out;
        }
        double[] score = new double[lats.length];
        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < lats.length - 1; i++) {
            double d = Math.abs(bearing(lats[i - 1], lons[i - 1], lats[i], lons[i]) - bearing(lats[i], lons[i], lats[i + 1], lons[i + 1])) % 360.0;
            score[i] = d > 180.0 ? 360.0 - d : d;
            candidates.add(i);
        }
        candidates.sort((a, b) -> Double.compare(score[b], score[a]));
        List<Integer> top = new ArrayList<>(candidates.subList(0, max));
        Collections.sort(top);
        return top;
    }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** geo: form every map app understands: a labelled point, or a search for free text. */
    public static String geoUri(NavDestination d) {
        if (d.isPoint()) return d.point.geoUri();
        return "geo:0,0?q=" + enc(d.query);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
