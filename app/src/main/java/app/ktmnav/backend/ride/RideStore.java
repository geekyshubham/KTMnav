package app.ktmnav.backend.ride;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App-private GPX ride files under files/rides/.
 */
public final class RideStore {
    public static final String DIR = "rides";
    private static final Pattern NAME = Pattern.compile("ride-(\\d{8})-(\\d{6})(?:-\\d+)?\\.gpx");
    private static final int REPAIR_TAIL_BYTES = 64 * 1024;
    private static final long EMPTY_FILE_MAX_BYTES = 4096L;

    public enum Repair { OK, REPAIRED, DELETED, FAILED }

    public static final class Ride {
        public final File file;
        public final String name;
        public final long startMs;
        public final RideStats stats;
        public final boolean recording;

        Ride(File file, long startMs, RideStats stats, boolean recording) {
            this.file = file;
            this.name = file.getName();
            this.startMs = startMs;
            this.stats = stats;
            this.recording = recording;
        }
    }

    private static final class Cached {
        final long length;
        final long modified;
        final RideStats stats;

        Cached(long length, long modified, RideStats stats) {
            this.length = length;
            this.modified = modified;
            this.stats = stats;
        }
    }

    private final File dir;
    private final TimeZone zone;
    private final Map<String, Cached> cache = new HashMap<>();
    private volatile File active;

    public RideStore(File filesDir) {
        this(filesDir, TimeZone.getDefault());
    }

    public RideStore(File filesDir, TimeZone zone) {
        this.dir = new File(filesDir, DIR);
        this.zone = zone;
    }

    public File getDir() {
        return dir;
    }

    void setActive(File file) {
        active = file;
    }

    public File getActive() {
        return active;
    }

    public synchronized File newRideFile(long wallClockMs) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        fmt.setTimeZone(zone);
        String base = "ride-" + fmt.format(new Date(wallClockMs));
        File f = new File(dir, base + ".gpx");
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "-" + i + ".gpx");
        return f;
    }

    public List<File> files() {
        File[] found = dir.listFiles((d, n) -> NAME.matcher(n).matches());
        if (found == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(found));
        list.sort((a, b) -> {
            int c = b.getName().substring(0, 20).compareTo(a.getName().substring(0, 20));
            return c != 0 ? c : Long.compare(b.lastModified(), a.lastModified());
        });
        return list;
    }

    public List<Ride> list() {
        List<Ride> rides = new ArrayList<>();
        File current = active;
        for (File f : files()) {
            RideStats stats = stats(f);
            long start = stats.startMs != TrackPoint.NO_TIME ? stats.startMs : startFromName(f.getName());
            rides.add(new Ride(f, start, stats, f.equals(current)));
        }
        return rides;
    }

    public RideStats stats(File f) {
        long len = f.length();
        long mod = f.lastModified();
        synchronized (cache) {
            Cached c = cache.get(f.getPath());
            if (c != null && c.length == len && c.modified == mod) return c.stats;
        }
        RideStats stats;
        try {
            stats = RideStats.of(GpxParser.parse(f));
        } catch (IOException e) {
            stats = RideStats.EMPTY;
        }
        synchronized (cache) {
            cache.put(f.getPath(), new Cached(len, mod, stats));
        }
        return stats;
    }

    public List<TrackPoint> points(File f) throws IOException {
        if (!contains(f)) throw new IOException("Not a ride file");
        return GpxParser.parse(f);
    }

    public File exportFile(String name) {
        if (name == null || !NAME.matcher(name).matches()) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    public boolean delete(File f) {
        if (!contains(f) || f.equals(active)) return false;
        synchronized (cache) {
            cache.remove(f.getPath());
        }
        return f.delete();
    }

    public int repairAll() {
        int changed = 0;
        File current = active;
        for (File f : files()) {
            if (f.equals(current)) continue;
            Repair r = repair(f);
            if (r == Repair.REPAIRED || r == Repair.DELETED) changed++;
        }
        return changed;
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

    long startFromName(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) return TrackPoint.NO_TIME;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        fmt.setTimeZone(zone);
        try {
            Date d = fmt.parse(m.group(1) + m.group(2));
            return d != null ? d.getTime() : TrackPoint.NO_TIME;
        } catch (java.text.ParseException e) {
            return TrackPoint.NO_TIME;
        }
    }

    public static Repair repair(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = raf.length();
            int n = (int) Math.min(len, REPAIR_TAIL_BYTES);
            long base = len - n;
            byte[] buf = new byte[n];
            raf.seek(base);
            raf.readFully(buf);
            String tail = new String(buf, StandardCharsets.ISO_8859_1);
            if (tail.trim().endsWith("</gpx>")) return Repair.OK;
            int lastPoint = tail.lastIndexOf("</trkpt>");
            if (lastPoint < 0) {
                int seg = tail.lastIndexOf("<trkseg>");
                if (len <= EMPTY_FILE_MAX_BYTES && tail.indexOf("<trkpt") < 0) {
                    raf.close();
                    return f.delete() ? Repair.DELETED : Repair.FAILED;
                }
                if (seg < 0) return Repair.FAILED;
                long cut = base + seg + "<trkseg>".length();
                raf.setLength(cut);
                raf.seek(cut);
                raf.write(("\n" + GpxWriter.FOOTER).getBytes(StandardCharsets.UTF_8));
                return Repair.REPAIRED;
            }
            long cut = base + lastPoint + "</trkpt>".length();
            raf.setLength(cut);
            raf.seek(cut);
            raf.write(("\n" + GpxWriter.FOOTER).getBytes(StandardCharsets.UTF_8));
            return Repair.REPAIRED;
        } catch (IOException e) {
            return Repair.FAILED;
        }
    }
}
