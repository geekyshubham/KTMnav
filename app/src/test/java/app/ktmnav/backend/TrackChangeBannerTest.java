package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class TrackChangeBannerTest {

    @Test
    public void testAnnouncesEachTrackOnce() {
        MessageMirrorTest.FakeSink sink = new MessageMirrorTest.FakeSink();
        TrackChangeBanner banner = new TrackChangeBanner();
        NowPlaying a = new NowPlaying("Born to be Wild", "Steppenwolf");

        Assert.assertTrue(banner.onTrack(a, true, sink));
        Assert.assertFalse(banner.onTrack(new NowPlaying("Born to be Wild", "Steppenwolf"), true, sink));
        Assert.assertFalse(banner.onTrack(new NowPlaying("  born to be wild ", "STEPPENWOLF"), true, sink));
        Assert.assertFalse(banner.onTrack(null, true, sink));
        Assert.assertFalse(banner.onTrack(a, true, sink));
        Assert.assertTrue(banner.onTrack(new NowPlaying("Highway Star", "Deep Purple"), true, sink));
        Assert.assertTrue(banner.onTrack(a, true, sink));

        Assert.assertEquals(3, sink.shown.size());
        Assert.assertEquals("track|Born to be Wild - Steppenwolf", sink.shown.get(0));
        Assert.assertEquals(Integer.valueOf(BannerSink.PRIORITY_TRACK), sink.priorities.get(0));
    }

    @Test
    public void testReattachOfSameTrackIsSuppressed() {
        MessageMirrorTest.FakeSink sink = new MessageMirrorTest.FakeSink();
        TrackChangeBanner banner = new TrackChangeBanner();
        Assert.assertTrue(banner.onTrack(new NowPlaying("Song", "Artist"), true, sink));
        Assert.assertFalse(banner.onTrack(null, true, sink));
        Assert.assertFalse(banner.onTrack(new NowPlaying("Song", "Artist"), true, sink));
        Assert.assertTrue(banner.onTrack(new NowPlaying("Other", "Artist"), true, sink));
        Assert.assertEquals(2, sink.shown.size());
    }

    @Test
    public void testDisabledEmptyAndRejected() {
        MessageMirrorTest.FakeSink sink = new MessageMirrorTest.FakeSink();
        TrackChangeBanner banner = new TrackChangeBanner();
        NowPlaying song = new NowPlaying("Song", null);

        Assert.assertFalse(banner.onTrack(song, false, sink));
        Assert.assertFalse(banner.onTrack(new NowPlaying(" ", null), true, sink));
        Assert.assertFalse(banner.onTrack(song, true, null));
        sink.accept = false;
        Assert.assertFalse(banner.onTrack(song, true, sink));
        sink.accept = true;
        Assert.assertTrue(banner.onTrack(song, true, sink));
        Assert.assertEquals("track|Song", sink.shown.get(0));

        banner.forget();
        Assert.assertTrue(banner.onTrack(song, true, sink));
    }

    @Test
    public void testTrackKeyNormalisation() {
        Assert.assertEquals(new NowPlaying("A  B", "C").trackKey(), new NowPlaying(" a b ", "c").trackKey());
        Assert.assertNotEquals(new NowPlaying("AB", null).trackKey(), new NowPlaying("A", "B").trackKey());
    }
}
