package app.ktmnav.backend.ride;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RouteStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String GPX = "<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
            + "<rte><name>Alps</name><rtept lat=\"47.0\" lon=\"11.0\"><ele>900</ele></rtept><rtept lat=\"47.1\" lon=\"11.1\"/><rtept lat=\"47.2\" lon=\"11.3\"/></rte></gpx>";

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void importsListsAndDeletesRoutes() throws IOException {
        RouteStore store = new RouteStore(folder.getRoot());
        RouteStore.Route r = store.importGpx(in(GPX), "Alps Day 1.GPX");
        Assert.assertEquals("route-alps-day-1.gpx", r.name);
        Assert.assertEquals("alps day 1", r.title);
        Assert.assertEquals(3, r.stats.points);
        Assert.assertTrue(r.stats.distanceM > 20_000);
        RouteStore.Route second = store.importGpx(in(GPX), "Alps Day 1");
        Assert.assertEquals("route-alps-day-1-2.gpx", second.name);
        List<RouteStore.Route> list = store.list();
        Assert.assertEquals(2, list.size());
        List<TrackPoint> points = store.points(list.get(0).file);
        Assert.assertEquals(3, points.size());
        Assert.assertNotNull(store.exportFile(r.name));
        Assert.assertNull(store.exportFile("../secret.gpx"));
        Assert.assertTrue(store.delete(r.file));
        Assert.assertEquals(1, store.list().size());
        Assert.assertFalse(store.delete(folder.newFile("outside.gpx")));
    }

    @Test
    public void rejectsFilesWithoutPoints() throws IOException {
        RouteStore store = new RouteStore(folder.getRoot());
        try {
            store.importGpx(in("<gpx version=\"1.1\"><wpt lat=\"1\" lon=\"2\"/></gpx>"), "empty");
            Assert.fail("expected rejection");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("No route points"));
        }
        try {
            store.importGpx(in("hello world"), "text");
            Assert.fail("expected rejection");
        } catch (IOException expected) {
            // not a GPX file
        }
        Assert.assertTrue(store.list().isEmpty());
        String[] leftovers = store.getDir().list();
        Assert.assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void namesAreSanitised() {
        RouteStore store = new RouteStore(folder.getRoot());
        Assert.assertEquals("route-route.gpx", store.fileFor("///").getName());
        Assert.assertEquals("route-weekend-ride.gpx", store.fileFor("Weekend ride.gpx").getName());
        Assert.assertEquals("", RouteStore.titleOf("route-.gpx"));
        Assert.assertEquals("tour", RouteStore.titleOf("tour.gpx"));
    }
}
