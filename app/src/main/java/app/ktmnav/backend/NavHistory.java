package app.ktmnav.backend;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recent destinations and named favourites (Home, Work, ...) kept in the app preferences as JSON.
 * Everything stays on the phone; nothing is uploaded.
 */
public final class NavHistory {
    public static final String PREF_RECENT = "nav_recent";
    public static final String PREF_FAVOURITES = "nav_favourites";
    public static final String HOME = "Home";
    public static final String WORK = "Work";
    public static final int MAX_RECENT = 10;
    public static final int MAX_FAVOURITES = 12;

    private NavHistory() {
    }

    public static List<NavDestination> recent(SharedPreferences prefs) {
        return read(prefs, PREF_RECENT);
    }

    public static List<NavDestination> favourites(SharedPreferences prefs) {
        return read(prefs, PREF_FAVOURITES);
    }

    /** Puts {@code d} at the top of the recents, dropping older duplicates and anything beyond MAX_RECENT. */
    public static void remember(SharedPreferences prefs, NavDestination d, long now) {
        if (prefs == null || d == null) return;
        List<NavDestination> list = new ArrayList<>(recent(prefs));
        list.removeIf(x -> x.equals(d));
        list.add(0, d.withUsedAt(now));
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        write(prefs, PREF_RECENT, list);
    }

    public static void forgetRecent(SharedPreferences prefs, NavDestination d) {
        if (prefs == null || d == null) return;
        List<NavDestination> list = new ArrayList<>(recent(prefs));
        if (list.removeIf(x -> x.equals(d))) write(prefs, PREF_RECENT, list);
    }

    public static void clearRecent(SharedPreferences prefs) {
        if (prefs != null) prefs.edit().remove(PREF_RECENT).apply();
    }

    /** Saves or replaces the favourite with this label. */
    public static boolean saveFavourite(SharedPreferences prefs, String label, NavDestination d) {
        if (prefs == null || d == null || label == null || label.trim().isEmpty()) return false;
        String clean = label.trim();
        List<NavDestination> list = new ArrayList<>(favourites(prefs));
        list.removeIf(x -> clean.equalsIgnoreCase(x.label));
        if (list.size() >= MAX_FAVOURITES) return false;
        list.add(0, d.withLabel(clean));
        sort(list);
        write(prefs, PREF_FAVOURITES, list);
        return true;
    }

    public static void removeFavourite(SharedPreferences prefs, String label) {
        if (prefs == null || label == null) return;
        List<NavDestination> list = new ArrayList<>(favourites(prefs));
        if (list.removeIf(x -> label.equalsIgnoreCase(x.label))) write(prefs, PREF_FAVOURITES, list);
    }

    public static NavDestination favourite(SharedPreferences prefs, String label) {
        for (NavDestination d : favourites(prefs)) if (label != null && label.equalsIgnoreCase(d.label)) return d;
        return null;
    }

    /** Home and Work first, then the rest alphabetically. */
    static void sort(List<NavDestination> list) {
        Collections.sort(list, (a, b) -> Integer.compare(rank(a.label), rank(b.label)) != 0
                ? Integer.compare(rank(a.label), rank(b.label))
                : String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label)));
    }

    private static int rank(String label) {
        if (HOME.equalsIgnoreCase(label)) return 0;
        if (WORK.equalsIgnoreCase(label)) return 1;
        return 2;
    }

    static List<NavDestination> parse(String json) {
        List<NavDestination> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                NavDestination d = NavDestination.fromJson(o);
                if (d != null && !list.contains(d)) list.add(d);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    static String serialise(List<NavDestination> list) {
        JSONArray array = new JSONArray();
        for (NavDestination d : list) array.put(d.toJson());
        return array.toString();
    }

    private static List<NavDestination> read(SharedPreferences prefs, String key) {
        if (prefs == null) return new ArrayList<>();
        return parse(prefs.getString(key, null));
    }

    private static void write(SharedPreferences prefs, String key, List<NavDestination> list) {
        prefs.edit().putString(key, serialise(list)).apply();
    }
}
