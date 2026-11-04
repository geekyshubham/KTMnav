package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class NowPlayingTest {

    @Test
    public void testDashTextFormatting() {
        NowPlaying np1 = new NowPlaying("Born to be Wild", "Steppenwolf");
        Assert.assertEquals("Born to be Wild - Steppenwolf", np1.toDashText());

        NowPlaying np2 = new NowPlaying("Highway to Hell", null);
        Assert.assertEquals("Highway to Hell", np2.toDashText());

        NowPlaying np3 = new NowPlaying("", "Motorhead");
        Assert.assertEquals("Motorhead", np3.toDashText());

        NowPlaying np4 = new NowPlaying(null, null);
        Assert.assertEquals("", np4.toDashText());

        NowPlaying np5 = new NowPlaying("   ", "   ");
        Assert.assertEquals("", np5.toDashText());

        NowPlaying np6 = new NowPlaying("  Thunderstruck  ", "  AC/DC  ");
        Assert.assertEquals("Thunderstruck - AC/DC", np6.toDashText());
    }

    @Test
    public void testGettersAndToString() {
        NowPlaying np = new NowPlaying("Ace of Spades", "Motorhead");
        Assert.assertEquals("Ace of Spades", np.getTitle());
        Assert.assertEquals("Motorhead", np.getArtist());
        Assert.assertTrue(np.toString().contains("Ace of Spades"));
        Assert.assertTrue(np.toString().contains("Motorhead"));
    }

    @Test
    public void testEquality() {
        NowPlaying a = new NowPlaying("Song", "Artist");
        NowPlaying b = new NowPlaying("Song", "Artist");
        NowPlaying c = new NowPlaying("Other", "Artist");
        NowPlaying d = new NowPlaying("Song", "Other");
        NowPlaying e = new NowPlaying(null, null);
        NowPlaying f = new NowPlaying(null, null);

        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
        Assert.assertNotEquals(a, c);
        Assert.assertNotEquals(a, d);
        Assert.assertEquals(e, f);
        Assert.assertEquals(e.hashCode(), f.hashCode());
        Assert.assertNotEquals(a, e);
        Assert.assertNotEquals(a, null);
        Assert.assertNotEquals(a, "not-a-now-playing");
    }
}
