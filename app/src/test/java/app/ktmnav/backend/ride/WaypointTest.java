package app.ktmnav.backend.ride;

import org.junit.Assert;
import org.junit.Test;

public class WaypointTest {

    private static void assertAt(Waypoint w, double lat, double lon) {
        Assert.assertNotNull(w);
        Assert.assertEquals(lat, w.lat, 1e-9);
        Assert.assertEquals(lon, w.lon, 1e-9);
    }

    @Test
    public void parsesPlainCoordinates() {
        assertAt(Waypoint.parse("12.97,77.59"), 12.97, 77.59);
        assertAt(Waypoint.parse(" 12.97 , 77.59 "), 12.97, 77.59);
        assertAt(Waypoint.parse("-33.8688 151.2093"), -33.8688, 151.2093);
        assertAt(Waypoint.parse("geo:12.97,77.59?z=15"), 12.97, 77.59);
    }

    @Test
    public void parsesGoogleMapsUrls() {
        assertAt(Waypoint.parse("https://www.google.com/maps/@12.9715987,77.5945627,15z"), 12.9715987, 77.5945627);
        assertAt(Waypoint.parse("https://maps.google.com/?q=12.97,77.59"), 12.97, 77.59);
        assertAt(Waypoint.parse("https://www.google.com/maps/search/?api=1&query=12.3052%2C76.6552"), 12.3052, 76.6552);
        assertAt(Waypoint.parse("https://www.google.com/maps/dir/?api=1&destination=13.3702,77.6835"), 13.3702, 77.6835);
        assertAt(Waypoint.parse("https://www.google.com/maps/place/Nandi+Hills/@13.37,77.68,14z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d13.3702!4d77.6835"),
                13.3702, 77.6835);
    }

    @Test
    public void rejectsInvalidInput() {
        Assert.assertNull(Waypoint.parse(null));
        Assert.assertNull(Waypoint.parse(""));
        Assert.assertNull(Waypoint.parse("Bengaluru"));
        Assert.assertNull(Waypoint.parse("91,77"));
        Assert.assertNull(Waypoint.parse("12.97,181"));
        Assert.assertNull(Waypoint.parse("https://maps.app.goo.gl/AbCdEf"));
        Assert.assertNull(Waypoint.parse("12.97,77.59,1"));
    }

    @Test
    public void buildsUris() {
        Waypoint w = Waypoint.parse("12.97,77.59", " Fuel stop & café ");
        Assert.assertEquals("12.970000,77.590000", w.toPref());
        Assert.assertEquals("google.navigation:q=12.970000,77.590000", w.navigationUri());
        Assert.assertEquals("geo:12.970000,77.590000?q=12.970000,77.590000(Fuel%20stop%20%26%20caf%C3%A9)", w.geoUri());
        Assert.assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000", new Waypoint(1, 2, "  ").geoUri());
        assertAt(Waypoint.parse(w.toPref()), 12.97, 77.59);
    }
}
