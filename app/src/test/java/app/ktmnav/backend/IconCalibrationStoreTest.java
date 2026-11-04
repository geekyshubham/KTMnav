package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class IconCalibrationStoreTest {

    private static final int N = ArrowIcons.SAMPLE_SIZE;
    private static final String MAPS = GoogleMapsNavSource.PACKAGE_NAME;

    static final class MemoryStorage implements IconCalibrationStore.Storage {
        String json;

        @Override
        public String load() {
            return json;
        }

        @Override
        public void save(String value) {
            json = value;
        }
    }

    static int[] arrow(int variant) {
        int[] px = new int[N * N];
        for (int y = 20; y < 46; y++) {
            for (int x = 21; x < 27; x++) {
                px[y * N + x] = 0xFFFFFFFF;
            }
        }
        for (int i = 0; i < 18; i++) {
            int x;
            int y;
            switch (variant) {
                case 0: x = 24 - i; y = 22; break;
                case 1: x = 24 + i; y = 22; break;
                case 2: x = 24; y = 22 - i; break;
                case 3: x = 24 - i; y = 22 - i; break;
                default: x = 24 + i; y = 22 - i; break;
            }
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int xx = Math.max(0, Math.min(N - 1, x + dx));
                    int yy = Math.max(0, Math.min(N - 1, y + dy));
                    px[yy * N + xx] = 0xFFFFFFFF;
                }
            }
        }
        return px;
    }

    @Test
    public void testFingerprintStableAndDistinct() {
        String left = ArrowFingerprint.of(arrow(0));
        Assert.assertNotNull(left);
        Assert.assertEquals(ArrowFingerprint.BITS / 4, left.length());
        Assert.assertEquals(left, ArrowFingerprint.of(arrow(0)));
        Assert.assertEquals(0, ArrowFingerprint.distance(left, ArrowFingerprint.of(arrow(0))));
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                int d = ArrowFingerprint.distance(ArrowFingerprint.of(arrow(a)), ArrowFingerprint.of(arrow(b)));
                Assert.assertTrue("variants " + a + "/" + b + " too close: " + d, d > 3 * ArrowFingerprint.MATCH_TOLERANCE);
            }
        }
    }

    @Test
    public void testSinglePixelNoiseTolerance() {
        int[] base = arrow(1);
        String hash = ArrowFingerprint.of(base);

        int[] flatNoise = base.clone();
        flatNoise[2 * N + 2] = 0xFFFFFFFF;
        Assert.assertEquals(hash, ArrowFingerprint.of(flatNoise));

        for (int i = 0; i < base.length; i += 7) {
            int[] noisy = base.clone();
            noisy[i] = noisy[i] == 0 ? 0xFFFFFFFF : 0;
            int d = ArrowFingerprint.distance(hash, ArrowFingerprint.of(noisy));
            Assert.assertTrue("pixel " + i + " distance " + d, d <= ArrowFingerprint.MATCH_TOLERANCE);
        }
    }

    @Test
    public void testFingerprintRejectsBlankAndBadInput() {
        Assert.assertNull(ArrowFingerprint.of(null));
        Assert.assertNull(ArrowFingerprint.of(new int[N * N]));
        Assert.assertNull(ArrowFingerprint.of(new int[10]));
        int[] opaque = new int[N * N];
        java.util.Arrays.fill(opaque, 0xFF202020);
        Assert.assertNull(ArrowFingerprint.of(opaque));
        for (int y = 10; y < 30; y++) {
            opaque[y * N + 24] = 0xFFFFFFFF;
            opaque[y * N + 25] = 0xFFFFFFFF;
            opaque[y * N + 26] = 0xFFFFFFFF;
        }
        Assert.assertNotNull(ArrowFingerprint.of(opaque));
        Assert.assertEquals(Integer.MAX_VALUE, ArrowFingerprint.distance("ab", "abc"));
    }

    @Test
    public void testLabelLookupForgetAndPersistence() {
        MemoryStorage storage = new MemoryStorage();
        IconCalibrationStore store = new IconCalibrationStore(storage);
        int[] left = arrow(0);
        Assert.assertNull(store.lookup(MAPS, left));

        String hash = store.noteCapture(MAPS, left, TurnIcon.LIGHT_LEFT, ManeuverResolver.Source.MODEL, 1000L);
        Assert.assertEquals(1, store.recentUnknown().size());
        Assert.assertTrue(store.label(hash, TurnIcon.QUITE_LEFT));
        Assert.assertEquals(TurnIcon.QUITE_LEFT, store.lookup(MAPS, left));
        Assert.assertTrue(store.recentUnknown().isEmpty());
        Assert.assertNull(store.lookup("com.other.nav", left));

        int[] noisy = left.clone();
        noisy[22 * N + 30] = 0;
        Assert.assertEquals(TurnIcon.QUITE_LEFT, store.lookup(MAPS, noisy));
        Assert.assertNull(store.lookup(MAPS, arrow(1)));

        IconCalibrationStore reloaded = new IconCalibrationStore(storage);
        Assert.assertEquals(TurnIcon.QUITE_LEFT, reloaded.lookup(MAPS, left));
        Assert.assertEquals(1, reloaded.entries().size());
        Assert.assertEquals(MAPS, reloaded.entries().get(0).pkg);

        Assert.assertTrue(reloaded.forget(hash));
        Assert.assertFalse(reloaded.forget(hash));
        Assert.assertNull(reloaded.lookup(MAPS, left));
        Assert.assertNull(new IconCalibrationStore(storage).lookup(MAPS, left));
    }

    @Test
    public void testLabelWithoutCaptureAppliesToAnyPackageAndClearAll() {
        MemoryStorage storage = new MemoryStorage();
        IconCalibrationStore store = new IconCalibrationStore(storage);
        String hash = ArrowFingerprint.of(arrow(2));
        Assert.assertTrue(store.label(hash, TurnIcon.GO_STRAIGHT));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, store.lookup("com.any.app", arrow(2)));
        Assert.assertFalse(store.label(hash, TurnIcon.UNKNOWN));
        Assert.assertFalse(store.label("nothex", TurnIcon.GO_STRAIGHT));
        Assert.assertFalse(store.label(hash, null));
        store.clearAll();
        Assert.assertNull(store.lookup("com.any.app", arrow(2)));
        Assert.assertTrue(new IconCalibrationStore(storage).entries().isEmpty());
    }

    @Test
    public void testRecentRingKeepsTwelveNewestFirst() {
        IconCalibrationStore store = new IconCalibrationStore(new MemoryStorage());
        for (int i = 0; i < 15; i++) {
            int[] px = arrow(i % 5);
            px[i] = 0xFFFFFFFF;
            px[(i + 7) * N + 1] = 0xFFFFFFFF;
            store.noteCapture("pkg" + i, px, null, ManeuverResolver.Source.NONE, i);
        }
        List<IconCalibrationStore.Capture> recent = store.recentUnknown();
        Assert.assertEquals(IconCalibrationStore.MAX_RECENT, recent.size());
        Assert.assertEquals("pkg14", recent.get(0).pkg);
        Assert.assertEquals(N * N, recent.get(0).pixels.length);

        store.noteCapture("pkg14", arrowWithNoise(14), TurnIcon.KEEP_LEFT, ManeuverResolver.Source.TEXT_WEAK, 99);
        Assert.assertEquals(2, store.recentUnknown().get(0).count);
        Assert.assertEquals(TurnIcon.KEEP_LEFT, store.recentUnknown().get(0).suggestion);
        Assert.assertNull(store.noteCapture(MAPS, new int[N * N], null, ManeuverResolver.Source.NONE, 1));
    }

    @Test
    public void testCorruptStorageIsIgnored() {
        MemoryStorage storage = new MemoryStorage();
        storage.json = "{not json";
        Assert.assertTrue(new IconCalibrationStore(storage).entries().isEmpty());
        storage.json = "{\"v\":1,\"entries\":[{\"p\":\"x\",\"h\":\"00\",\"i\":\"KEEP_LEFT\"},{\"p\":\"x\",\"h\":\""
                + ArrowFingerprint.of(arrow(0)) + "\",\"i\":\"NOT_AN_ICON\"}]}";
        Assert.assertTrue(new IconCalibrationStore(storage).entries().isEmpty());
    }

    @Test
    public void testResolverPriority() {
        ManeuverText.Result exact = ManeuverText.parse(false, "Turn left onto MG Road");
        ManeuverText.Result weak = ManeuverText.parse(false, "Make a U-turn");
        ManeuverResolver.Fallback model = () -> TurnIcon.HEAVY_RIGHT;
        ManeuverResolver.Fallback none = () -> null;

        Assert.assertEquals(TurnIcon.QUITE_LEFT, ManeuverResolver.resolve(exact, TurnIcon.KEEP_RIGHT, model).icon);
        Assert.assertEquals(ManeuverResolver.Source.TEXT, ManeuverResolver.resolve(exact, null, model).source);
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, ManeuverResolver.resolve(weak, TurnIcon.KEEP_RIGHT, model).icon);
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, ManeuverResolver.resolve(null, TurnIcon.KEEP_RIGHT, model).icon);
        ManeuverResolver.Resolution m = ManeuverResolver.resolve(weak, null, model);
        Assert.assertEquals(TurnIcon.HEAVY_RIGHT, m.icon);
        Assert.assertEquals(ManeuverResolver.Source.MODEL, m.source);
        ManeuverResolver.Resolution w = ManeuverResolver.resolve(weak, null, none);
        Assert.assertEquals(TurnIcon.UTURN_LEFT, w.icon);
        Assert.assertEquals(ManeuverResolver.Source.TEXT_WEAK, w.source);
        Assert.assertNull(ManeuverResolver.resolve(null, null, none).icon);
        Assert.assertEquals(ManeuverResolver.Source.NONE, ManeuverResolver.resolve(null, TurnIcon.UNKNOWN, () -> TurnIcon.UNKNOWN).source);

        Assert.assertEquals(TurnIcon.KEEP_RIGHT, ManeuverResolver.resolveForSource(exact, TurnIcon.KEEP_RIGHT, TurnIcon.GO_STRAIGHT).icon);
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, ManeuverResolver.resolveForSource(exact, null, TurnIcon.GO_STRAIGHT).icon);
        Assert.assertEquals(TurnIcon.QUITE_LEFT, ManeuverResolver.resolveForSource(exact, null, null).icon);
        Assert.assertEquals(TurnIcon.UTURN_LEFT, ManeuverResolver.resolveForSource(weak, null, TurnIcon.UNKNOWN).icon);
    }

    private static int[] arrowWithNoise(int i) {
        int[] px = arrow(i % 5);
        px[i] = 0xFFFFFFFF;
        px[(i + 7) * N + 1] = 0xFFFFFFFF;
        return px;
    }
}
