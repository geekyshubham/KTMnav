package app.ktmnav.backend.obd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class Elm327ProtocolTest {

    private static int[] rpm(String raw) {
        return Elm327Protocol.findMode01Payload(Elm327Protocol.parse(raw, "010C"), 0x0C);
    }

    private static void assertRpm(String raw, double expected) {
        assertEquals(expected, ObdPids.Pid.RPM.decode(rpm(raw)), 0.001);
    }

    @Test
    public void plainSpaced() {
        assertRpm("41 0C 1A F8 \r\r>", 1726.0);
    }

    @Test
    public void compactNoSpaces() {
        assertRpm("410C1AF8\r\r>", 1726.0);
    }

    @Test
    public void echoLineStripped() {
        assertRpm("010C\r41 0C 1A F8\r\r>", 1726.0);
    }

    @Test
    public void echoConcatenatedStripped() {
        assertRpm("010C410C0FA0\r>", 1000.0);
    }

    @Test
    public void searchingOwnLine() {
        assertRpm("SEARCHING...\r41 0C 1A F8\r\r>", 1726.0);
    }

    @Test
    public void searchingSameLine() {
        assertRpm("SEARCHING...410C1AF8\r>", 1726.0);
    }

    @Test
    public void echoThenSearchingConcatenated() {
        assertRpm("010CSEARCHING...\r410C1AF8\r>", 1726.0);
    }

    @Test
    public void busInitOkLine() {
        assertRpm("BUS INIT: ...OK\r41 0C 1A F8\r>", 1726.0);
    }

    @Test
    public void busInitOkSameLine() {
        assertRpm("BUS INIT: ...OK41 0C 1A F8\r>", 1726.0);
    }

    @Test
    public void busInitError() {
        Elm327Protocol.Response r = Elm327Protocol.parse("BUS INIT: ...ERROR\r\r>", "010C");
        assertEquals(Elm327Protocol.Status.ERROR, r.status);
        assertNull(Elm327Protocol.findMode01Payload(r, 0x0C));
    }

    @Test
    public void lineFeedsTolerated() {
        assertRpm("\r\n41 0C 1A F8\r\n\r\n>", 1726.0);
    }

    @Test
    public void lowerCaseTolerated() {
        assertRpm("41 0c 1a f8\r>", 1726.0);
    }

    @Test
    public void multiEcuTakesFirstValid() {
        assertRpm("41 0C 0F A0\r41 0C 1F 40\r\r>", 1000.0);
    }

    @Test
    public void multiEcuSkipsOtherPid() {
        assertRpm("41 0D 32\r41 0C 0F A0\r>", 1000.0);
    }

    @Test
    public void canHeader11Bit() {
        assertRpm("7E8 04 41 0C 1A F8\r>", 1726.0);
    }

    @Test
    public void canHeader11BitPadded() {
        assertRpm("7E804410C1AF8AAAAAA\r>", 1726.0);
    }

    @Test
    public void canHeader11BitTwoEcus() {
        assertRpm("7E9 04 41 0C 0F A0\r7E8 04 41 0C 1A F8\r>", 1000.0);
    }

    @Test
    public void canHeader29Bit() {
        assertRpm("18 DA F1 10 04 41 0C 1A F8\r>", 1726.0);
    }

    @Test
    public void kwpHeaderWithChecksum() {
        assertRpm("48 6B 10 41 0C 1A F8 C8\r>", 1726.0);
    }

    @Test
    public void noData() {
        Elm327Protocol.Response r = Elm327Protocol.parse("NO DATA\r\r>", "015C");
        assertEquals(Elm327Protocol.Status.NO_DATA, r.status);
        assertNull(Elm327Protocol.findMode01Payload(r, 0x5C));
    }

    @Test
    public void searchingThenNoData() {
        assertEquals(Elm327Protocol.Status.NO_DATA, Elm327Protocol.parse("SEARCHING...\rNO DATA\r>", "0100").status);
    }

    @Test
    public void questionMark() {
        Elm327Protocol.Response r = Elm327Protocol.parse("?\r\r>", "ATAT1");
        assertEquals(Elm327Protocol.Status.ERROR, r.status);
        assertEquals("?", r.error);
    }

    @Test
    public void unableToConnect() {
        Elm327Protocol.Response r = Elm327Protocol.parse("SEARCHING...\rUNABLE TO CONNECT\r\r>", "0100");
        assertEquals(Elm327Protocol.Status.ERROR, r.status);
        assertEquals("UNABLE TO CONNECT", r.error);
    }

    @Test
    public void canError() {
        assertEquals("CAN ERROR", Elm327Protocol.parse("CAN ERROR\r>", "010C").error);
    }

    @Test
    public void stopped() {
        Elm327Protocol.Response r = Elm327Protocol.parse("STOPPED\r>", "010C");
        assertEquals(Elm327Protocol.Status.ERROR, r.status);
        assertEquals("STOPPED", r.error);
    }

    @Test
    public void bufferFull() {
        assertEquals("BUFFER FULL", Elm327Protocol.parse("BUFFER FULL\r>", "010C").error);
    }

    @Test
    public void okWithEcho() {
        assertEquals(Elm327Protocol.Status.OK, Elm327Protocol.parse("ATE0\rOK\r\r>", "ATE0").status);
    }

    @Test
    public void okConcatenatedEcho() {
        assertEquals(Elm327Protocol.Status.OK, Elm327Protocol.parse("ATE0OK\r>", "ATE0").status);
    }

    @Test
    public void atzBanner() {
        Elm327Protocol.Response r = Elm327Protocol.parse("ATZ\r\r\rELM327 v1.5\r\r>", "ATZ");
        assertEquals(Elm327Protocol.Status.DATA, r.status);
        assertEquals("ELM327V1.5", r.lines.get(0));
    }

    @Test
    public void emptyResponse() {
        assertEquals(Elm327Protocol.Status.EMPTY, Elm327Protocol.parse("\r\r>", "010C").status);
        assertEquals(Elm327Protocol.Status.EMPTY, Elm327Protocol.parse(null, "010C").status);
    }

    @Test
    public void nulBytesAndPromptOnly() {
        assertRpm("\r41 0C 1A F8\r>", 1726.0);
    }

    @Test
    public void truncatedPayloadRejectedByDecoder() {
        assertNull(ObdPids.Pid.RPM.decode(rpm("41 0C 1A\r>")));
    }

    @Test
    public void protocolNumber() {
        assertEquals("A6", Elm327Protocol.parseProtocolNumber(Elm327Protocol.parse("A6\r\r>", "ATDPN")));
        assertEquals("5", Elm327Protocol.parseProtocolNumber(Elm327Protocol.parse("5\r>", "ATDPN")));
        assertTrue(Elm327Protocol.isCanProtocol("A6"));
        assertTrue(Elm327Protocol.isCanProtocol("8"));
        assertFalse(Elm327Protocol.isCanProtocol("A5"));
        assertFalse(Elm327Protocol.isCanProtocol("3"));
        assertEquals("ISO 15765-4 CAN 11-bit 500k (auto)", Elm327Protocol.describeProtocol("A6"));
    }

    @Test
    public void supportedMaskWithSearching() {
        int[] mask = Elm327Protocol.findMode01Payload(
                Elm327Protocol.parse("0100\rSEARCHING...\r41 00 BE 3E B8 11\r\r>", "0100"), 0x00);
        assertArrayEquals(new int[]{0xBE, 0x3E, 0xB8, 0x11}, mask);
    }

    @Test
    public void isoTpMultiFrameAssembled() {
        Elm327Protocol.Response r = Elm327Protocol.parse(
                "00A\r0: 43 04 01 33 02 44\r1: 01 71 C1 23 00 00 00\r\r>", "03");
        java.util.List<int[]> payloads = Elm327Protocol.findModePayloads(r, 0x43);
        assertEquals(1, payloads.size());
        assertEquals(0x04, payloads.get(0)[0]);
    }

    @Test
    public void commandBuilderAppendsCr() {
        assertEquals("010C\r", Elm327Protocol.build("01 0c"));
        assertEquals("ATZ\r", Elm327Protocol.build("atz"));
        assertEquals("010D", Elm327Protocol.mode01(0x0D));
    }

    @Test
    public void builderRejectsForbiddenModes() {
        String[] forbidden = {"04", "0400", "08", "0801", "2F", "2F0101", "10", "1003", "11", "1101", "14", "14FFFF",
                "22F190", "27", "2701", "2E", "2EF190", "31", "3101", "3E", "3E00", "05", "06", "0A", "02", "0200",
                "", "XYZ", "01", "010", "010C0D", "0300", "07FF", "0904", "0900", "0A01", "FF"};
        for (String cmd : forbidden) {
            assertFalse("must reject " + cmd, Elm327Protocol.isAllowed(cmd));
            try {
                Elm327Protocol.build(cmd);
                fail("build accepted " + cmd);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("not permitted"));
            }
        }
    }

    @Test
    public void builderRejectsNonWhitelistedAt() {
        String[] forbidden = {"ATSH7E0", "ATSH 7DF", "ATCRA7E8", "ATMA", "ATBD", "ATWS", "ATD", "ATPP0CSV01", "ATSP6",
                "ATBRD23", "ATCAF0", "ATFI", "ATSI", "ATH1", "ATE1", "ATRV", "STPX", "STI", "AT", "ATZ1", "ATCM7FF"};
        for (String cmd : forbidden) {
            assertFalse("must reject " + cmd, Elm327Protocol.isAllowed(cmd));
        }
    }

    @Test
    public void builderAllowsOnlyReadModes() {
        String[] allowed = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1", "ATDPN", "ATI",
                "0100", "010C", "01FF", "03", "07", "0902"};
        for (String cmd : allowed) {
            assertTrue("must allow " + cmd, Elm327Protocol.isAllowed(cmd));
        }
        for (String cmd : Elm327Protocol.INIT_SEQUENCE) {
            assertTrue(Elm327Protocol.isAllowed(cmd));
        }
    }

    @Test
    public void everyModeByteOutsideReadSetRejected() {
        for (int mode = 0; mode <= 0xFF; mode++) {
            String m = Elm327Protocol.hex2(mode);
            boolean anyAllowed = Elm327Protocol.isAllowed(m) || Elm327Protocol.isAllowed(m + "00")
                    || Elm327Protocol.isAllowed(m + "02") || Elm327Protocol.isAllowed(m + "0C");
            boolean readMode = mode == 0x01 || mode == 0x03 || mode == 0x07 || mode == 0x09;
            assertEquals("mode " + m, readMode, anyAllowed);
        }
    }
}
