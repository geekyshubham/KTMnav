package app.ktmnav.backend;

import java.util.ArrayList;
import java.util.List;

final class FakeBanner implements BannerSink {
    final List<String> log = new ArrayList<>();

    @Override
    public boolean show(String owner, String text, int priority, long holdMs) {
        log.add("show " + owner + " '" + text + "' p" + priority + " " + holdMs);
        return true;
    }

    @Override
    public void clear(String owner) {
        log.add("clear " + owner);
    }
}
