package app.ktmnav.backend.ride;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * Incremental GPX 1.1 track writer. The closing tags are rewritten after every point,
 * so the file stays valid XML even if the process dies between fixes.
 */
public final class GpxWriter implements Closeable {
    public static final String NAMESPACE_EXT = "urn:ktmnav:gpx:1";
    public static final String FOOTER = "</trkseg></trk>\n</gpx>\n";
    private static final byte[] FOOTER_BYTES = FOOTER.getBytes(StandardCharsets.UTF_8);

    private final File file;
    private RandomAccessFile raf;
    private long position;
    private int points;

    private GpxWriter(File file, RandomAccessFile raf, long position) {
        this.file = file;
        this.raf = raf;
        this.position = position;
    }

    public static GpxWriter create(File file, String name) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        byte[] header = header(name).getBytes(StandardCharsets.UTF_8);
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.setLength(0);
            raf.write(header);
            raf.write(FOOTER_BYTES);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
        return new GpxWriter(file, raf, header.length);
    }

    public File getFile() {
        return file;
    }

    public synchronized int getPointCount() {
        return points;
    }

    public synchronized void append(TrackPoint p) throws IOException {
        if (raf == null) throw new IOException("Writer closed");
        byte[] point = pointXml(p).getBytes(StandardCharsets.UTF_8);
        raf.seek(position);
        raf.write(point);
        raf.write(FOOTER_BYTES);
        position += point.length;
        points++;
    }

    @Override
    public synchronized void close() throws IOException {
        if (raf == null) return;
        try {
            raf.setLength(position + FOOTER_BYTES.length);
            raf.getFD().sync();
        } finally {
            raf.close();
            raf = null;
        }
    }

    static String header(String name) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"KTMnav\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:kn=\"" + NAMESPACE_EXT + "\">\n"
                + "<trk><name>" + escape(name) + "</name><trkseg>\n";
    }

    static String pointXml(TrackPoint p) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon));
        if (p.hasEle()) sb.append(String.format(Locale.ROOT, "<ele>%.1f</ele>", p.ele));
        if (p.hasTime()) sb.append("<time>").append(Instant.ofEpochMilli(p.timeMs)).append("</time>");
        if (p.hasSpeed()) sb.append(String.format(Locale.ROOT, "<extensions><kn:speed>%.1f</kn:speed></extensions>", p.speedKmh));
        return sb.append("</trkpt>\n").toString();
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: if (c >= 0x20 || c == '\t') sb.append(c);
            }
        }
        return sb.toString();
    }
}
