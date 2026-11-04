package app.ktmnav.backend;

/**
 * Announces each new media track once on the dash banner, ignoring pause/resume and session re-attach of the same track.
 */
final class TrackChangeBanner {
    static final String OWNER = "track";
    static final long HOLD_MS = 6000L;

    private String lastAnnouncedKey;

    synchronized boolean onTrack(NowPlaying nowPlaying, boolean enabled, BannerSink sink) {
        if (nowPlaying == null || !enabled || sink == null) {
            return false;
        }
        String text = nowPlaying.toDashText();
        if (text.isEmpty()) {
            return false;
        }
        String key = nowPlaying.trackKey();
        if (key.equals(lastAnnouncedKey)) {
            return false;
        }
        if (!sink.show(OWNER, text, BannerSink.PRIORITY_TRACK, HOLD_MS)) {
            return false;
        }
        lastAnnouncedKey = key;
        return true;
    }

    synchronized void forget() {
        lastAnnouncedKey = null;
    }
}
