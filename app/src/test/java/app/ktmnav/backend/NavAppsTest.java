package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NavAppsTest {

    private static List<String> uris(String appId, NavDestination d, boolean twoWheeler) {
        NavApps.App app = NavApps.byId(appId);
        List<String> out = new ArrayList<>();
        for (NavApps.Candidate c : NavApps.candidates(app, null, d, twoWheeler)) out.add(c.uri);
        return out;
    }

    @Test
    public void destinationsParseCoordinatesLinksAndText() {
        NavDestination point = NavDestination.of("https://maps.google.com/?q=12.97,77.59", "Office", 5L);
        Assert.assertTrue(point.isPoint());
        Assert.assertEquals("12.970000,77.590000", point.query);
        Assert.assertEquals("Office", point.title());
        Assert.assertEquals("12.970000,77.590000", point.subtitle());
        NavDestination text = NavDestination.of("  Nandi   Hills, Karnataka ");
        Assert.assertFalse(text.isPoint());
        Assert.assertEquals("Nandi Hills, Karnataka", text.query);
        Assert.assertEquals("Search", text.subtitle());
        Assert.assertNull(NavDestination.of("   "));
        Assert.assertNull(NavDestination.of(null));
        Assert.assertEquals(NavDestination.of("nandi hills, karnataka"), text);
        Assert.assertEquals(NavDestination.of("12.97 77.59"), point);
    }

    @Test
    public void jsonRoundTripKeepsLabelAndTime() {
        NavDestination d = NavDestination.of("Fuel stop", "Home", 1234L);
        NavDestination back = NavDestination.fromJson(d.toJson());
        Assert.assertEquals(d, back);
        Assert.assertEquals("Home", back.label);
        Assert.assertEquals(1234L, back.usedAt);
        List<NavDestination> list = Arrays.asList(NavDestination.of("A", "Work", 1), NavDestination.of("B", "Zoo", 2), NavDestination.of("C", "Home", 3));
        List<NavDestination> parsed = NavHistory.parse(NavHistory.serialise(list));
        Assert.assertEquals(list, parsed);
        List<NavDestination> sorted = new ArrayList<>(parsed);
        NavHistory.sort(sorted);
        Assert.assertEquals("Home", sorted.get(0).label);
        Assert.assertEquals("Work", sorted.get(1).label);
        Assert.assertEquals("Zoo", sorted.get(2).label);
        Assert.assertTrue(NavHistory.parse("not json").isEmpty());
        Assert.assertTrue(NavHistory.parse(null).isEmpty());
    }

    @Test
    public void googleMapsStartsTurnByTurnForTextAndPoints() {
        NavDestination text = NavDestination.of("Nandi Hills & café");
        Assert.assertEquals("google.navigation:q=Nandi%20Hills%20%26%20caf%C3%A9&mode=d", uris(NavApps.GOOGLE_MAPS, text, false).get(0));
        Assert.assertEquals("google.navigation:q=Nandi%20Hills%20%26%20caf%C3%A9&mode=l", uris(NavApps.GOOGLE_MAPS, text, true).get(0));
        NavDestination point = NavDestination.of("12.97,77.59");
        List<String> list = uris(NavApps.GOOGLE_MAPS, point, false);
        Assert.assertEquals("google.navigation:q=12.970000,77.590000&mode=d", list.get(0));
        Assert.assertEquals("geo:12.970000,77.590000?q=12.970000,77.590000", list.get(list.size() - 1));
    }

    @Test
    public void everyAppEndsWithAGeoFallbackAndUsesItsOwnScheme() {
        NavDestination point = NavDestination.of("48.2082,16.3738", "Vienna", 0);
        NavDestination text = NavDestination.of("Grossglockner");
        for (NavApps.App app : NavApps.ALL) {
            List<NavApps.Candidate> forPoint = NavApps.candidates(app, null, point, false);
            Assert.assertFalse(app.id, forPoint.isEmpty());
            Assert.assertEquals(app.id, "geo:48.208200,16.373800?q=48.208200,16.373800(Vienna)", forPoint.get(forPoint.size() - 1).uri);
            Assert.assertEquals(app.id, app.canonicalPackage(), forPoint.get(0).pkg);
            List<NavApps.Candidate> forText = NavApps.candidates(app, "net.osmand.plus", text, false);
            Assert.assertEquals(app.id, "geo:0,0?q=Grossglockner", forText.get(forText.size() - 1).uri);
        }
        Assert.assertEquals("waze://?ll=48.208200,16.373800&navigate=yes", uris(NavApps.WAZE, point, false).get(0));
        Assert.assertEquals("waze://?q=Grossglockner&navigate=yes", uris(NavApps.WAZE, text, false).get(0));
        Assert.assertTrue(uris(NavApps.OSMAND, point, false).get(0).startsWith("osmand.api://navigate?dest_lat=48.208200&dest_lon=16.373800&dest_name=Vienna&profile=motorcycle"));
        Assert.assertTrue(uris(NavApps.OSMAND, text, false).get(0).startsWith("osmand.api://navigate_search?dest_search=Grossglockner"));
        Assert.assertEquals("google.navigation:q=48.208200,16.373800", uris(NavApps.TOMTOM, point, false).get(0));
        Assert.assertEquals("here.directions://v1.0/mylocation/48.208200,16.373800?m=d", uris(NavApps.HERE, point, false).get(0));
        Assert.assertEquals("com.sygic.aura://coordinate|16.373800|48.208200|drive", uris(NavApps.SYGIC, point, false).get(0));
        Assert.assertEquals("https://kurviger.de/?point=48.208200,16.373800&vehicle=motorcycle", uris(NavApps.KURVIGER, point, false).get(0));
        NavApps.Candidate osmandPlus = NavApps.candidates(NavApps.byId(NavApps.OSMAND), "net.osmand.plus", text, false).get(0);
        Assert.assertEquals("net.osmand.plus", osmandPlus.pkg);
    }

    @Test
    public void routesUseDirectionsForGoogleMapsAndTheEndPointElsewhere() {
        double[] lats = {47.0, 47.01, 47.02, 47.03, 47.04}, lons = {11.0, 11.01, 11.05, 11.06, 11.1};
        List<NavApps.Candidate> maps = NavApps.routeCandidates(NavApps.byId(NavApps.GOOGLE_MAPS), null, lats, lons, false);
        Assert.assertEquals("https://maps.google.com/maps?saddr=47.000000,11.000000&daddr=47.010000,11.010000+to:47.020000,11.050000+to:47.030000,11.060000+to:47.040000,11.100000&dirflg=d", maps.get(0).uri);
        Assert.assertEquals("google.navigation:q=47.040000,11.100000&mode=d", maps.get(1).uri);
        List<NavApps.Candidate> waze = NavApps.routeCandidates(NavApps.byId(NavApps.WAZE), null, lats, lons, true);
        Assert.assertEquals("waze://?ll=47.040000,11.100000&navigate=yes", waze.get(0).uri);
        Assert.assertTrue(NavApps.routeCandidates(NavApps.byId(NavApps.WAZE), null, new double[]{1}, new double[]{2}, false).isEmpty());

        // A long route keeps the nine sharpest bends, in route order.
        int n = 40;
        double[] la = new double[n], lo = new double[n];
        for (int i = 0; i < n; i++) { la[i] = 47.0 + i * 0.001; lo[i] = 11.0; }
        for (int i : new int[]{5, 12, 20, 33}) lo[i] = 11.01; // sharp zig-zags around these points
        List<Integer> via = NavApps.viaPoints(la, lo, 9);
        Assert.assertEquals(9, via.size());
        for (int i = 1; i < via.size(); i++) Assert.assertTrue(via.get(i) > via.get(i - 1));
        Assert.assertTrue(via.containsAll(Arrays.asList(5, 12, 20, 33)));
        Assert.assertEquals(Arrays.asList(1, 2, 3), NavApps.viaPoints(lats, lons, 9));
        Assert.assertEquals(90.0, NavApps.bearing(0, 0, 0, 1), 0.01);
        Assert.assertEquals(0.0, NavApps.bearing(0, 0, 1, 0), 0.01);
    }

    @Test
    public void geoUrisBecomeSearchesOrPoints() {
        NavDestination search = NavLauncher.fromGeo("geo:0,0?q=Chikmagalur%2C%20Karnataka");
        Assert.assertNotNull(search);
        Assert.assertFalse(search.isPoint());
        Assert.assertEquals("Chikmagalur, Karnataka", search.query);
        NavDestination plus = NavLauncher.fromGeo("geo:0,0?q=Nandi+Hills");
        Assert.assertEquals("Nandi Hills", plus.query);
        NavDestination labelled = NavLauncher.fromGeo("geo:12.97,77.59?q=12.97,77.59(Fuel%20stop)");
        Assert.assertTrue(labelled.isPoint());
        Assert.assertEquals("Fuel stop", labelled.label);
        Assert.assertEquals("12.970000,77.590000", labelled.query);
        NavDestination bare = NavLauncher.fromGeo("geo:12.97,77.59?z=14");
        Assert.assertTrue(bare.isPoint());
        Assert.assertEquals("12.970000,77.590000", bare.query);
        NavDestination zeroQuery = NavLauncher.fromGeo("geo:0,0?q=13.37,77.68");
        Assert.assertTrue(zeroQuery.isPoint());
        Assert.assertEquals("13.370000,77.680000", zeroQuery.query);
        NavDestination named = NavLauncher.fromGeo("geo:13.37,77.68?q=Nandi%20Hills");
        Assert.assertTrue(named.isPoint());
        Assert.assertEquals("Nandi Hills", named.label);
        Assert.assertNull(NavLauncher.fromGeo("geo:0,0"));
        Assert.assertNull(NavLauncher.fromGeo("geo:0,0?q="));
        Assert.assertNull(NavLauncher.fromGeo(null));
    }

    @Test
    public void catalogueLookups() {
        Assert.assertEquals("Google Maps", NavApps.byPackage("com.google.android.apps.maps").name);
        Assert.assertEquals("CoMaps", NavApps.byPackage("app.comaps.fdroid").name);
        Assert.assertEquals("Locus Map", NavApps.nameOf("menion.android.locus.pro"));
        Assert.assertEquals("com.unknown", NavApps.nameOf("com.unknown"));
        Assert.assertArrayEquals(new String[]{"net.osmand", "net.osmand.plus"}, NavApps.variants("net.osmand.plus"));
        Assert.assertArrayEquals(new String[]{"x.y"}, NavApps.variants("x.y"));
        Assert.assertNull(NavApps.byId("nope"));
        Assert.assertTrue(NavApps.byName("Waze").forwards);
        Assert.assertFalse(NavApps.byName("Sygic").forwards);
        Assert.assertTrue(NavApps.candidates(null, null, NavDestination.of("x"), false).isEmpty());
    }
}
