package app.ktmnav.backend.ride;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Imported GPX routes under files/routes/: planned rides from Kurviger, Calimoto, Google My Maps or any
 * other planner, kept on the phone so the rider can start turn-by-turn guidance to them from KTMnav.
 * Files are validated with the streaming GPX parser before they are kept.
 */
public final class RouteStore {
    public static final String DIR = "routes";
    public static final long MAX_BYTES = 16L * 1024 * 1024;
    public static final int MAX_ROUTES = 50;
    private static final Pattern NAME = Pattern.compile("route-[a-z0-9][a-z0-9_-]{0,60}\\.gpx");

    public static final class Route {
        public final File file;
        public final String name;
        public final String title;
        public final RideStats stats;

        Route(File file, RideStats stats) {
            this.file = file;
            this.name = file.getName();
            this.title = titleOf(file.getName());
            this.stats = stats;
        }
    }

    private final File dir;

    public RouteStore(File filesDir) {
        this.dir = new File(filesDir, DIR);
    }

    public File getDir() {
        return dir;
    }

    /** Display title from a stored file name: "route-alps-day-1.gpx" becomes "alps day 1". */
    public static String titleOf(String fileName) {
        String base = fileName.startsWith("route-") ? fileName.substring("route-".length()) : fileName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".gpx")) base = base.substring(0, base.length() - 4);
        return base.replace('_', ' ').replace('-', ' ').trim();
    }

    /** Stored file name for a display name; collisions get a numeric suffix. */
    File fileFor(String displayName) {
        String base = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (base.endsWith(".gpx")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "route";
        if (base.length() > 48) base = base.substring(0, 48).replaceAll("-+$", "");
        File f = new File(dir, "route-" + base + ".gpx");
        for (int i = 2; f.exists(); i++) f = new File(dir, "route-" + base + "-" + i + ".gpx");
        return f;
    }

    /**
     * Copies a GPX stream into the store after checking it holds at least two track or route points.
     * Returns the stored route, or throws with a rider-readable message.
     */
    public synchronized Route importGpx(InputStream in, String displayName) throws IOException {
        if (in == null) throw new IOException("Nothing to import");
        if (list().size() >= MAX_ROUTES) throw new IOException("Route limit reached; delete a route first");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File temp = File.createTempFile("import", ".gpx", dir);
        try {
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            try (OutputStream out = new FileOutputStream(temp); InputStream src = new BufferedInputStream(in)) {
                int n;
                while ((n = src.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("GPX file is larger than 16 MB");
                    out.write(buffer, 0, n);
                }
            }
            List<TrackPoint> points = GpxParser.parse(temp);
            if (points.size() < 2) throw new IOException("No route points found in this GPX file");
            File target = fileFor(displayName);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return new Route(target, RideStats.of(points));
        } finally {
            if (temp.exists() && !temp.delete()) temp.deleteOnExit();
        }
    }

    public List<File> files() {
        File[] found = dir.listFiles((d, n) -> NAME.matcher(n).matches());
        if (found == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(found));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    public synchronized List<Route> list() {
        List<Route> routes = new ArrayList<>();
        for (File f : files()) {
            RideStats stats;
            try {
                stats = RideStats.of(GpxParser.parse(f));
            } catch (IOException e) {
                stats = RideStats.EMPTY;
            }
            routes.add(new Route(f, stats));
        }
        return routes;
    }

    public List<TrackPoint> points(File f) throws IOException {
        if (!contains(f)) throw new IOException("Not a route file");
        return GpxParser.parse(f);
    }

    public File exportFile(String name) {
        if (name == null || !NAME.matcher(name).matches()) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    public synchronized boolean delete(File f) {
        return contains(f) && f.delete();
    }

    public boolean contains(File f) {
        if (f == null) return false;
        try {
            File parent = f.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(dir.getCanonicalFile()) && NAME.matcher(f.getName()).matches();
        } catch (IOException e) {
            return false;
        }
    }
}
