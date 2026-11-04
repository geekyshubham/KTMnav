package app.ktmnav.backend;

import app.ktmnav.backend.ride.Waypoint;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * A place the rider wants to go: free text (address, place name) or coordinates parsed from a
 * coordinate pair, a geo: URI or a Google Maps link. Immutable; serialised to JSON for recents and favourites.
 */
public final class NavDestination {
    public static final int MAX_QUERY_LENGTH = 400;

    public final String query;
    public final Waypoint point;
    public final String label;
    public final long usedAt;

    private NavDestination(String query, Waypoint point, String label, long usedAt) {
        this.query = query;
        this.point = point;
        this.label = label;
        this.usedAt = usedAt;
    }

    /** Returns null for blank input. Coordinates and map links become a point; anything else is a text query. */
    public static NavDestination of(String text, String label, long usedAt) {
        if (text == null) return null;
        String q = text.trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return null;
        if (q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        String cleanLabel = label == null || label.trim().isEmpty() ? null : label.trim();
        Waypoint point = Waypoint.parse(q, cleanLabel);
        return new NavDestination(point != null ? point.toPref() : q, point, cleanLabel, usedAt);
    }

    public static NavDestination of(String text) {
        return of(text, null, 0L);
    }

    public NavDestination withLabel(String newLabel) {
        return new NavDestination(query, point != null ? new Waypoint(point.lat, point.lon, newLabel) : null, newLabel == null || newLabel.trim().isEmpty() ? null : newLabel.trim(), usedAt);
    }

    public NavDestination withUsedAt(long when) {
        return new NavDestination(query, point, label, when);
    }

    public boolean isPoint() {
        return point != null;
    }

    /** What the rider sees in lists: the label when set, otherwise the query or formatted coordinates. */
    public String title() {
        if (label != null) return label;
        return query;
    }

    public String subtitle() {
        if (label == null) return isPoint() ? "Coordinates" : "Search";
        return query;
    }

    /** Stable identity for de-duplication: same coordinates or same text, ignoring case. */
    public String key() {
        return isPoint() ? point.toPref() : query.toLowerCase(Locale.ROOT);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("q", query);
            if (label != null) o.put("l", label);
            if (usedAt > 0) o.put("t", usedAt);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static NavDestination fromJson(JSONObject o) {
        if (o == null) return null;
        return of(o.optString("q", ""), o.optString("l", null), o.optLong("t", 0L));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NavDestination && key().equals(((NavDestination) other).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return label != null ? label + " (" + query + ")" : query;
    }
}
