package app.ktmnav.backend;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable metadata representation of currently playing media track.
 */
public final class NowPlaying {
    private final String title;
    private final String artist;

    public NowPlaying(String title, String artist) {
        this.title = title;
        this.artist = artist;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String toDashText() {
        boolean hasTitle = title != null && !title.trim().isEmpty();
        boolean hasArtist = artist != null && !artist.trim().isEmpty();

        if (hasTitle && hasArtist) {
            return title.trim() + " - " + artist.trim();
        }
        if (hasTitle) {
            return title.trim();
        }
        if (hasArtist) {
            return artist.trim();
        }
        return "";
    }

    public String trackKey() {
        return norm(title) + "\n" + norm(artist);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NowPlaying)) return false;
        NowPlaying that = (NowPlaying) o;
        return Objects.equals(title, that.title) && Objects.equals(artist, that.artist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist);
    }

    @Override
    public String toString() {
        return "NowPlaying{title='" + title + "', artist='" + artist + "'}";
    }
}
