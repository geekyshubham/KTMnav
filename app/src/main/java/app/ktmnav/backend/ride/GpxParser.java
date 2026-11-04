package app.ktmnav.backend.ride;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Streaming GPX point reader for recorded tracks (trkpt) and planned routes (rtept). Truncated files yield
 * the points read before the break.
 */
public final class GpxParser {
    private GpxParser() {
    }

    public static List<TrackPoint> parse(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return parse(in);
        }
    }

    public static List<TrackPoint> parse(InputStream in) throws IOException {
        Handler handler = new Handler();
        try {
            newParser().parse(in, handler);
        } catch (SAXException e) {
            if (handler.points.isEmpty() && !handler.sawRoot) throw new IOException("Not a GPX file", e);
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
        return handler.points;
    }

    private static SAXParser newParser() throws ParserConfigurationException, SAXException {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        trySet(f, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newSAXParser();
    }

    private static void trySet(SAXParserFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException | RuntimeException ignored) {
        }
    }

    static long parseTime(String s) {
        if (s == null) return TrackPoint.NO_TIME;
        String t = s.trim();
        try {
            return Instant.parse(t).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(t).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                return TrackPoint.NO_TIME;
            }
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Handler extends DefaultHandler {
        final List<TrackPoint> points = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean sawRoot;
        boolean inPoint;
        double lat;
        double lon;
        double ele;
        long time;
        float speed;

        private static String name(String localName, String qName) {
            if (localName != null && !localName.isEmpty()) return localName;
            int colon = qName.indexOf(':');
            return colon >= 0 ? qName.substring(colon + 1) : qName;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String n = name(localName, qName);
            text.setLength(0);
            if ("gpx".equals(n)) sawRoot = true;
            if ("trkpt".equals(n) || "rtept".equals(n)) {
                lat = parseDouble(attrs.getValue("lat"), Double.NaN);
                lon = parseDouble(attrs.getValue("lon"), Double.NaN);
                ele = Double.NaN;
                time = TrackPoint.NO_TIME;
                speed = Float.NaN;
                inPoint = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inPoint) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!inPoint) return;
            String n = name(localName, qName);
            switch (n) {
                case "ele": ele = parseDouble(text.toString(), Double.NaN); break;
                case "time": time = parseTime(text.toString()); break;
                case "speed":
                    if (GpxWriter.NAMESPACE_EXT.equals(uri)) speed = (float) parseDouble(text.toString(), Double.NaN);
                    else if (uri != null && uri.endsWith("GPX/1/0")) speed = (float) (parseDouble(text.toString(), Double.NaN) * 3.6);
                    break;
                case "trkpt":
                case "rtept":
                    inPoint = false;
                    boolean valid = !Double.isNaN(lat) && !Double.isNaN(lon)
                            && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0;
                    if (valid) points.add(new TrackPoint(lat, lon, ele, time, speed));
                    break;
                default:
            }
            text.setLength(0);
        }
    }
}
