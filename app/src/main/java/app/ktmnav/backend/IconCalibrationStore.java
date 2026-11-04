package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User turn-icon calibration: arrow fingerprint per source package mapped to a TurnIcon, kept in app-private prefs.
 * Calibration flow follows OpenDash KTM-Nav-GEN3 IconHashCache / TurnCalibrationScreen (MIT).
 */
public final class IconCalibrationStore {
    private static final String TAG = "IconCalibration";
    private static final String PREFS_NAME = "ktmnav";

    public static final String PREF_KEY = "icon_calibration";
    public static final String ANY_PACKAGE = "*";
    public static final int MAX_RECENT = 12;
    public static final int MAX_ENTRIES = 256;

    public interface Storage {
        String load();

        void save(String json);
    }

    public static final class Entry {
        public final String pkg;
        public final String hash;
        public final TurnIcon icon;
        public final long labelledAt;

        Entry(String pkg, String hash, TurnIcon icon, long labelledAt) {
            this.pkg = pkg;
            this.hash = hash;
            this.icon = icon;
            this.labelledAt = labelledAt;
        }
    }

    public static final class Capture {
        public final String pkg;
        public final String hash;
        public final int[] pixels;
        public final TurnIcon suggestion;
        public final ManeuverResolver.Source suggestionSource;
        public final long lastSeenMs;
        public final int count;

        Capture(String pkg, String hash, int[] pixels, TurnIcon suggestion, ManeuverResolver.Source source,
                long lastSeenMs, int count) {
            this.pkg = pkg;
            this.hash = hash;
            this.pixels = pixels;
            this.suggestion = suggestion;
            this.suggestionSource = source;
            this.lastSeenMs = lastSeenMs;
            this.count = count;
        }
    }

    private static IconCalibrationStore instance;

    private final Storage storage;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ArrayDeque<Capture> recent = new ArrayDeque<>();

    public IconCalibrationStore(Storage storage) {
        this.storage = storage;
        load();
    }

    public static synchronized IconCalibrationStore get(Context context) {
        if (instance == null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            instance = new IconCalibrationStore(new Storage() {
                @Override
                public String load() {
                    return prefs.getString(PREF_KEY, null);
                }

                @Override
                public void save(String json) {
                    prefs.edit().putString(PREF_KEY, json).apply();
                }
            });
        }
        return instance;
    }

    public synchronized TurnIcon lookup(String pkg, int[] pixels) {
        return lookupHash(pkg, ArrowFingerprint.of(pixels));
    }

    public synchronized TurnIcon lookupHash(String pkg, String hash) {
        if (hash == null || entries.isEmpty()) {
            return null;
        }
        Entry exact = entries.get(key(pkg, hash));
        if (exact == null) {
            exact = entries.get(key(ANY_PACKAGE, hash));
        }
        if (exact != null) {
            return exact.icon;
        }
        Entry best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (Entry e : entries.values()) {
            if (!e.pkg.equals(pkg) && !ANY_PACKAGE.equals(e.pkg)) {
                continue;
            }
            int d = ArrowFingerprint.distance(hash, e.hash);
            if (d < bestDistance) {
                bestDistance = d;
                best = e;
                ambiguous = false;
            } else if (d == bestDistance && best != null && best.icon != e.icon) {
                ambiguous = true;
            }
        }
        if (best == null || ambiguous || bestDistance > ArrowFingerprint.MATCH_TOLERANCE) {
            return null;
        }
        return best.icon;
    }

    public synchronized String noteCapture(String pkg, int[] pixels, TurnIcon suggestion,
                                           ManeuverResolver.Source source, long now) {
        String hash = ArrowFingerprint.of(pixels);
        if (hash == null || pkg == null) {
            return null;
        }
        int count = 1;
        Iterator<Capture> it = recent.iterator();
        while (it.hasNext()) {
            Capture c = it.next();
            if (c.hash.equals(hash) && c.pkg.equals(pkg)) {
                count = c.count + 1;
                it.remove();
                break;
            }
        }
        recent.addFirst(new Capture(pkg, hash, pixels.clone(), suggestion, source, now, count));
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
        return hash;
    }

    public synchronized List<Capture> recentUnknown() {
        List<Capture> out = new ArrayList<>();
        for (Capture c : recent) {
            if (lookupHash(c.pkg, c.hash) == null) {
                out.add(c);
            }
        }
        return out;
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public synchronized boolean label(String hash, TurnIcon icon) {
        String pkg = ANY_PACKAGE;
        for (Capture c : recent) {
            if (c.hash.equals(hash)) {
                pkg = c.pkg;
                break;
            }
        }
        return label(pkg, hash, icon, System.currentTimeMillis());
    }

    public synchronized boolean label(String pkg, String hash, TurnIcon icon, long now) {
        if (hash == null || hash.length() != ArrowFingerprint.BITS / 4 || icon == null || icon == TurnIcon.UNKNOWN) {
            return false;
        }
        String p = pkg != null ? pkg : ANY_PACKAGE;
        entries.remove(key(p, hash));
        entries.put(key(p, hash), new Entry(p, hash, icon, now));
        while (entries.size() > MAX_ENTRIES) {
            Iterator<String> it = entries.keySet().iterator();
            it.next();
            it.remove();
        }
        save();
        AppLog.i(TAG, "Labelled arrow for " + p + " as " + icon.name());
        return true;
    }

    public synchronized boolean forget(String hash) {
        boolean removed = false;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (it.next().hash.equals(hash)) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized void clearAll() {
        entries.clear();
        recent.clear();
        save();
    }

    private static String key(String pkg, String hash) {
        return pkg + "/" + hash;
    }

    private void load() {
        String json = storage != null ? storage.load() : null;
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray list = new JSONObject(json).optJSONArray("entries");
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                TurnIcon icon = iconOf(o.optString("i", null));
                String pkg = o.optString("p", ANY_PACKAGE);
                String hash = o.optString("h", null);
                if (icon != null && hash != null && hash.length() == ArrowFingerprint.BITS / 4) {
                    entries.put(key(pkg, hash), new Entry(pkg, hash, icon, o.optLong("t", 0L)));
                }
            }
        } catch (JSONException e) {
            AppLog.w(TAG, "Ignoring unreadable icon calibration");
        }
    }

    private void save() {
        if (storage == null) {
            return;
        }
        try {
            JSONArray list = new JSONArray();
            for (Entry e : entries.values()) {
                list.put(new JSONObject().put("p", e.pkg).put("h", e.hash).put("i", e.icon.name()).put("t", e.labelledAt));
            }
            storage.save(new JSONObject().put("v", 1).put("entries", list).toString());
        } catch (JSONException e) {
            AppLog.w(TAG, "Failed to save icon calibration", e);
        }
    }

    private static TurnIcon iconOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            TurnIcon icon = TurnIcon.valueOf(name);
            return icon == TurnIcon.UNKNOWN ? null : icon;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
