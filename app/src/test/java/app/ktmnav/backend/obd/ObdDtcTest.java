package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ObdDtcTest {

    private static List<String> stored(String raw, Boolean can) {
        return ObdDtc.parse(Elm327Protocol.parse(raw, "03"), ObdDtc.STORED_RESPONSE_MODE, can);
    }

    @Test
    public void decodeSystems() {
        assertEquals("P0133", ObdDtc.decode(0x01, 0x33));
        assertEquals("C0123", ObdDtc.decode(0x41, 0x23));
        assertEquals("B1234", ObdDtc.decode(0x92, 0x34));
        assertEquals("U0100", ObdDtc.decode(0xC1, 0x00));
        assertEquals("P2ABC", ObdDtc.decode(0x2A, 0xBC));
        assertEquals("U3FFF", ObdDtc.decode(0xFF, 0xFF));
        assertNull(ObdDtc.decode(0x00, 0x00));
    }

    @Test
    public void nonCanPaddedSingleLine() {
        assertEquals(Arrays.asList("P0133", "P0244"), stored("43 01 33 02 44 00 00\r\r>", false));
    }

    @Test
    public void nonCanMultipleLinesDeduped() {
        assertEquals(Arrays.asList("P0133", "P0244", "P0171", "C0123"),
                stored("43 01 33 02 44 01 71\r43 41 23 01 33 00 00\r>", null));
    }

    @Test
    public void nonCanNoCodes() {
        assertTrue(stored("43 00 00 00 00 00 00\r>", false).isEmpty());
    }

    @Test
    public void canSingleFrameWithCount() {
        assertEquals(Arrays.asList("P0133", "U0100"), stored("43 02 01 33 C1 00\r>", true));
    }

    @Test
    public void canCountByteDetectedHeuristically() {
        assertEquals(Arrays.asList("P0133"), stored("430101 33\r>", null));
    }

    @Test
    public void canZeroCodes() {
        assertTrue(stored("43 00\r>", true).isEmpty());
    }

    @Test
    public void canMultiFrame() {
        String raw = "00B\r0: 43 05 01 33 02 44\r1: 01 71 C1 00 92 34 00\r\r>";
        assertEquals(Arrays.asList("P0133", "P0244", "P0171", "U0100", "B1234"), stored(raw, true));
    }

    @Test
    public void canWithHeaders() {
        assertEquals(Arrays.asList("P0301"), stored("7E8 04 43 01 03 01\r>", true));
    }

    @Test
    public void p0000IgnoredAndNoData() {
        assertTrue(stored("43 02 00 00 00 00\r>", true).isEmpty());
        assertTrue(stored("NO DATA\r>", null).isEmpty());
    }

    @Test
    public void pendingMode07() {
        List<String> pending = ObdDtc.parse(Elm327Protocol.parse("47 01 01 71\r>", "07"), ObdDtc.PENDING_RESPONSE_MODE, true);
        assertEquals(Arrays.asList("P0171"), pending);
    }

    @Test
    public void fakeEncoderRoundTrip() {
        for (String code : new String[]{"P0133", "C0123", "B1234", "U0100", "P2ABC"}) {
            String hex = FakeElm327.encodeDtc(code);
            assertEquals(code, ObdDtc.decode(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2), 16)));
        }
    }
}
