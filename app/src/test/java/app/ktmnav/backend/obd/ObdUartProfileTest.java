package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class ObdUartProfileTest {

    private static final int W = ObdUartProfile.PROP_WRITE;
    private static final int WNR = ObdUartProfile.PROP_WRITE_NO_RESPONSE;
    private static final int N = ObdUartProfile.PROP_NOTIFY;
    private static final UUID NUS = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");

    private static Map<UUID, Integer> chars(Object... pairs) {
        Map<UUID, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            UUID u = pairs[i] instanceof UUID ? (UUID) pairs[i] : ObdUartProfile.uuid16((Integer) pairs[i]);
            m.put(u, (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void prefersFff0OverOthers() {
        Map<UUID, Map<UUID, Integer>> s = new LinkedHashMap<>();
        s.put(ObdUartProfile.uuid16(0xFFE0), chars(0xFFE1, W | N));
        s.put(ObdUartProfile.uuid16(0xFFF0), chars(0xFFF1, N, 0xFFF2, WNR | W));
        ObdUartProfile.Choice c = ObdUartProfile.choose(s);
        assertEquals(ObdUartProfile.uuid16(0xFFF0), c.service);
        assertEquals(ObdUartProfile.uuid16(0xFFF2), c.write);
        assertEquals(ObdUartProfile.uuid16(0xFFF1), c.notify);
    }

    @Test
    public void hm10SharedCharacteristic() {
        Map<UUID, Map<UUID, Integer>> s = new LinkedHashMap<>();
        s.put(ObdUartProfile.uuid16(0xFFE0), chars(0xFFE1, WNR | N));
        ObdUartProfile.Choice c = ObdUartProfile.choose(s);
        assertEquals(c.write, c.notify);
    }

    @Test
    public void leLinkAndNordic() {
        Map<UUID, Map<UUID, Integer>> s = new LinkedHashMap<>();
        s.put(ObdUartProfile.uuid16(0x18F0), chars(0x2AF0, N, 0x2AF1, W));
        assertEquals(ObdUartProfile.uuid16(0x2AF1), ObdUartProfile.choose(s).write);
        Map<UUID, Map<UUID, Integer>> n = new LinkedHashMap<>();
        n.put(NUS, chars(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), WNR,
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), N));
        assertEquals(NUS, ObdUartProfile.choose(n).service);
    }

    @Test
    public void genericFallbackSkipsStandardServicesAndAmbiguity() {
        UUID custom = UUID.fromString("0000AE30-0000-1000-8000-00805F9B34FB");
        UUID ambiguous = UUID.fromString("0000BE30-0000-1000-8000-00805F9B34FB");
        Map<UUID, Map<UUID, Integer>> s = new LinkedHashMap<>();
        s.put(ObdUartProfile.uuid16(0x1800), chars(0x2A00, W | N));
        s.put(ambiguous, chars(0xBE01, W, 0xBE02, W, 0xBE03, N));
        s.put(custom, chars(0xAE01, WNR, 0xAE02, N));
        ObdUartProfile.Choice c = ObdUartProfile.choose(s);
        assertEquals(custom, c.service);
        assertEquals(ObdUartProfile.uuid16(0xAE01), c.write);
    }

    @Test
    public void noUartReturnsNull() {
        Map<UUID, Map<UUID, Integer>> s = new LinkedHashMap<>();
        s.put(ObdUartProfile.uuid16(0x180A), chars(0x2A29, 0x02));
        assertNull(ObdUartProfile.choose(s));
        Map<UUID, Map<UUID, Integer>> wrongProps = new LinkedHashMap<>();
        wrongProps.put(ObdUartProfile.uuid16(0xFFF0), chars(0xFFF1, 0x02, 0xFFF2, 0x02));
        assertNull(ObdUartProfile.choose(wrongProps));
    }

    @Test
    public void adapterNames() {
        for (String n : new String[]{"OBDII", "V-LINK", "vLink", "Vgate iCar Pro", "IOS-Vlink", "Viecar", "LELink",
                "KONNWEI KW902", "ELM327 v1.5", "OBD BLE"}) {
            assertTrue(n, ObdUartProfile.isLikelyAdapterName(n));
        }
        assertFalse(ObdUartProfile.isLikelyAdapterName("KTM 390 Duke"));
        assertFalse(ObdUartProfile.isLikelyAdapterName(null));
        assertTrue(ObdUartProfile.offersKnownService(Arrays.asList(ObdUartProfile.uuid16(0xFFF0))));
        assertFalse(ObdUartProfile.offersKnownService(Arrays.asList(ObdUartProfile.uuid16(0x180D))));
    }

    @Test
    public void chunksTo20Bytes() {
        byte[] data = new byte[45];
        List<byte[]> chunks = ObdUartProfile.chunk(data, 20);
        assertEquals(3, chunks.size());
        assertEquals(20, chunks.get(0).length);
        assertEquals(5, chunks.get(2).length);
        assertEquals(1, ObdUartProfile.chunk("010C\r".getBytes(), 20).size());
        assertTrue(ObdUartProfile.prefersNoResponse(WNR));
        assertFalse(ObdUartProfile.prefersNoResponse(W | WNR));
    }

    @Test
    public void sppUuid() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", ObdUartProfile.SPP_UUID.toString());
    }

    @Test
    public void dashBannerFormatsOnlyFreshValues() {
        ObdSnapshot snap = new ObdSnapshot.Builder().state("Connected").connected(true)
                .value(ObdPids.Pid.RPM, 4000, 1000L).value(ObdPids.Pid.SPEED, 40, 1000L).build(1000L);
        GearEstimator.Profile p = GearEstimator.KTM_390_DUKE_GEN3;
        assertEquals("40 km/h  G4", ObdDashBanner.text(snap, 2500L, p));
        assertNull(ObdDashBanner.text(snap, 4001L, p));
        ObdSnapshot idle = new ObdSnapshot.Builder().connected(true)
                .value(ObdPids.Pid.RPM, 1200, 1000L).value(ObdPids.Pid.SPEED, 3, 1000L).build(1000L);
        assertEquals("3 km/h  G-", ObdDashBanner.text(idle, 1500L, p));
        ObdSnapshot down = snap.toBuilder().connected(false).build(1000L);
        assertNull(ObdDashBanner.text(down, 1500L, p));
    }
}
