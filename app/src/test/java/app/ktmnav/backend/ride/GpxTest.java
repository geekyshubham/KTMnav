package app.ktmnav.backend.ride;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GpxTest {
    private static final long T0 = 1_792_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<TrackPoint> sample() {
        return Arrays.asList(
                new TrackPoint(0, 0, 100, T0, Float.NaN),
                new TrackPoint(0, 0.01, 110, T0 + 60_000, Float.NaN),
                new TrackPoint(0, 0.01, 108, T0 + 120_000, Float.NaN),
                new TrackPoint(0, 0.02, 120, T0 + 180_000, Float.NaN));
    }

    @Test
    public void writeParseRoundTrip() throws IOException {
        File f = new File(tmp.getRoot(), "rides/ride-20261021-101500.gpx");
        GpxWriter w = GpxWriter.create(f, "Ride <Nandi> & \"hills\"");
        Assert.assertTrue(read(f).trim().endsWith("</gpx>"));
        Assert.assertTrue(GpxParser.parse(f).isEmpty());
        w.append(new TrackPoint(12.9715987, 77.5945627, 920.4, T0, 42.3f));
        Assert.assertTrue(read(f).trim().endsWith("</gpx>"));
        w.append(new TrackPoint(12.9720001, 77.5950002, Double.NaN, T0 + 1500, Float.NaN));
        Assert.assertEquals(1, GpxParser.parse(f).size() - 1);
        w.close();
        w.close();
        String xml = read(f);
        Assert.assertTrue(xml.contains("Ride &lt;Nandi&gt; &amp; &quot;hills&quot;"));
        Assert.assertTrue(xml.contains("version=\"1.1\""));
        Assert.assertTrue(xml.trim().endsWith("</gpx>"));
        Assert.assertEquals(1, xml.split("</gpx>", -1).length - 1);
        List<TrackPoint> pts = GpxParser.parse(f);
        Assert.assertEquals(2, pts.size());
        TrackPoint a = pts.get(0);
        Assert.assertEquals(12.9715987, a.lat, 1e-7);
        Assert.assertEquals(77.5945627, a.lon, 1e-7);
        Assert.assertEquals(920.4, a.ele, 1e-6);
        Assert.assertEquals(T0, a.timeMs);
        Assert.assertEquals(42.3f, a.speedKmh, 1e-4);
        TrackPoint b = pts.get(1);
        Assert.assertFalse(b.hasEle());
        Assert.assertFalse(b.hasSpeed());
        Assert.assertEquals(T0 + 1500, b.timeMs);
    }

    @Test
    public void parsesForeignGpxAndIgnoresDoctypeEntities() throws IOException {
        String gpx = "<?xml version=\"1.0\"?><gpx version=\"1.0\" xmlns=\"http://www.topografix.com/GPX/1/0\">"
                + "<trk><trkseg><trkpt lat=\"1.5\" lon=\"2.5\"><time>2026-10-21T10:00:00+05:30</time><speed>10</speed></trkpt>"
                + "<trkpt lat=\"95\" lon=\"2.5\"/></trkseg></trk></gpx>";
        List<TrackPoint> pts = GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(1, pts.size());
        Assert.assertEquals(36f, pts.get(0).speedKmh, 1e-4);
        Assert.assertEquals(GpxParser.parseTime("2026-10-21T04:30:00Z"), pts.get(0).timeMs);

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"><name>&x;</name></trkpt></trkseg></trk></gpx>";
        try {
            List<TrackPoint> r = GpxParser.parse(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)));
            Assert.assertTrue(r.size() <= 1);
        } catch (IOException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void statsFromKnownCoordinates() {
        Assert.assertEquals(111_195.0, RideStats.haversine(0, 0, 1, 0), 1.0);
        RideStats s = RideStats.of(sample());
        double leg = 2 * Math.PI * RideStats.EARTH_RADIUS_M * 0.01 / 360.0;
        Assert.assertEquals(2 * leg, s.distanceM, 0.01);
        Assert.assertEquals(180_000L, s.durationMs);
        Assert.assertEquals(120_000L, s.movingMs);
        Assert.assertEquals(leg / 60.0 * 3.6, s.maxKmh, 0.01);
        Assert.assertEquals(leg / 60.0 * 3.6, s.avgMovingKmh, 0.01);
        Assert.assertEquals(20.0, s.elevationGainM, 1e-9);
        Assert.assertEquals(4, s.points);
        Assert.assertEquals(T0, s.startMs);
    }

    @Test
    public void statsPreferRecordedSpeedAndHandleEmpty() {
        RideStats s = RideStats.of(Arrays.asList(
                new TrackPoint(0, 0, Double.NaN, T0, 10f),
                new TrackPoint(0, 0.001, Double.NaN, T0 + 10_000, 55.5f)));
        Assert.assertEquals(55.5, s.maxKmh, 1e-4);
        Assert.assertEquals(0.0, s.elevationGainM, 0);
        Assert.assertSame(RideStats.EMPTY, RideStats.of(null));
        Assert.assertEquals(0, RideStats.of(Arrays.asList(new TrackPoint(1, 1, Double.NaN, TrackPoint.NO_TIME, Float.NaN))).durationMs);
    }

    @Test
    public void repairsTruncatedFiles() throws IOException {
        File f = tmp.newFile("ride-20261021-090000.gpx");
        String header = GpxWriter.header("x");
        String p1 = GpxWriter.pointXml(sample().get(0));
        String p2 = GpxWriter.pointXml(sample().get(1));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write((header + p1 + p2 + "<trkpt lat=\"0.0").getBytes(StandardCharsets.UTF_8));
        }
        Assert.assertEquals(RideStore.Repair.REPAIRED, RideStore.repair(f));
        String xml = read(f);
        Assert.assertTrue(xml.endsWith(GpxWriter.FOOTER));
        Assert.assertFalse(xml.contains("<trkpt lat=\"0.0<"));
        Assert.assertEquals(2, GpxParser.parse(f).size());
        Assert.assertEquals(RideStore.Repair.OK, RideStore.repair(f));

        File noFooter = tmp.newFile("ride-20261021-090500.gpx");
        Files.write(noFooter.toPath(), (header + p1).getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(RideStore.Repair.REPAIRED, RideStore.repair(noFooter));
        Assert.assertEquals(1, GpxParser.parse(noFooter).size());

        File empty = tmp.newFile("ride-20261021-091000.gpx");
        Files.write(empty.toPath(), header.getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(RideStore.Repair.DELETED, RideStore.repair(empty));
        Assert.assertFalse(empty.exists());
    }

    @Test
    public void storeListsNewestFirstAndProtectsActive() throws IOException {
        TimeZone ist = TimeZone.getTimeZone("Asia/Kolkata");
        RideStore store = new RideStore(tmp.getRoot(), ist);
        File older = store.newRideFile(T0);
        try (GpxWriter w = GpxWriter.create(older, "a")) {
            for (TrackPoint p : sample()) w.append(p);
        }
        File newer = store.newRideFile(T0 + 3_600_000L);
        GpxWriter live = GpxWriter.create(newer, "b");
        live.append(sample().get(0));
        store.setActive(newer);
        File dup = store.newRideFile(T0);
        Assert.assertNotEquals(older, dup);
        Assert.assertTrue(dup.getName().endsWith("-2.gpx"));

        List<RideStore.Ride> rides = store.list();
        Assert.assertEquals(2, rides.size());
        Assert.assertEquals(newer, rides.get(0).file);
        Assert.assertTrue(rides.get(0).recording);
        Assert.assertEquals(older, rides.get(1).file);
        Assert.assertEquals(4, rides.get(1).stats.points);
        Assert.assertEquals(T0, rides.get(1).startMs);
        Assert.assertTrue(older.getName().matches("ride-\\d{8}-\\d{6}\\.gpx"));
        Assert.assertEquals(T0 / 1000 * 1000, store.startFromName(older.getName()));

        Assert.assertEquals(older, store.exportFile(older.getName()));
        Assert.assertNull(store.exportFile("../ride-20261021-101500.gpx"));
        Assert.assertNull(store.exportFile("notes.txt"));
        Assert.assertFalse(store.delete(newer));
        Assert.assertFalse(store.delete(new File(tmp.getRoot(), "outside.gpx")));
        Assert.assertTrue(store.delete(older));
        Assert.assertEquals(1, store.list().size());
        live.close();
        store.setActive(null);
        Assert.assertEquals(0, store.repairAll());
    }
}
