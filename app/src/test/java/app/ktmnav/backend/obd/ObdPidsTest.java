package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ObdPidsTest {

    private static double d(ObdPids.Pid pid, int... bytes) {
        return pid.decode(bytes);
    }

    @Test
    public void decoders() {
        assertEquals(1726.0, d(ObdPids.Pid.RPM, 0x1A, 0xF8), 1e-9);
        assertEquals(16383.75, d(ObdPids.Pid.RPM, 0xFF, 0xFF), 1e-9);
        assertEquals(50.0, d(ObdPids.Pid.SPEED, 0x32), 1e-9);
        assertEquals(255.0, d(ObdPids.Pid.SPEED, 0xFF), 1e-9);
        assertEquals(100.0, d(ObdPids.Pid.THROTTLE, 0xFF), 1e-9);
        assertEquals(50.196, d(ObdPids.Pid.LOAD, 0x80), 1e-3);
        assertEquals(-40.0, d(ObdPids.Pid.COOLANT, 0x00), 1e-9);
        assertEquals(90.0, d(ObdPids.Pid.COOLANT, 0x82), 1e-9);
        assertEquals(25.0, d(ObdPids.Pid.INTAKE, 0x41), 1e-9);
        assertEquals(110.0, d(ObdPids.Pid.OIL, 0x96), 1e-9);
        assertEquals(30.0, d(ObdPids.Pid.AMBIENT, 0x46), 1e-9);
        assertEquals(13.8, d(ObdPids.Pid.VOLTAGE, 0x35, 0xE8), 1e-9);
        assertEquals(40.0, d(ObdPids.Pid.FUEL, 0x66), 1e-9);
        assertEquals(754.0, d(ObdPids.Pid.RUN_TIME, 0x02, 0xF2), 1e-9);
        assertEquals(12.0, d(ObdPids.Pid.DISTANCE_MIL, 0x00, 0x0C), 1e-9);
        assertEquals(65535.0, d(ObdPids.Pid.DISTANCE_CLEARED, 0xFF, 0xFF), 1e-9);
    }

    @Test
    public void decoderRejectsShortOrNull() {
        assertNull(ObdPids.Pid.RPM.decode(new int[]{0x1A}));
        assertNull(ObdPids.Pid.SPEED.decode(new int[0]));
        assertNull(ObdPids.Pid.VOLTAGE.decode(null));
    }

    @Test
    public void byCode() {
        assertSame(ObdPids.Pid.OIL, ObdPids.Pid.byCode(0x5C));
        assertNull(ObdPids.Pid.byCode(0x99));
    }

    @Test
    public void supportedBitmask0100() {
        Set<Integer> s = ObdPids.decodeSupported(0x00, new int[]{0xBE, 0x3E, 0xB8, 0x11});
        assertEquals(Arrays.asList(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11,
                0x13, 0x14, 0x15, 0x1C, 0x20), new java.util.ArrayList<>(s));
        assertTrue(ObdPids.hasNextRange(0x00, s));
    }

    @Test
    public void supportedBitmask0120And0140() {
        Set<Integer> s20 = ObdPids.decodeSupported(0x20, new int[]{0x80, 0x02, 0x00, 0x01});
        assertEquals(Arrays.asList(0x21, 0x2F, 0x40), new java.util.ArrayList<>(s20));
        assertTrue(ObdPids.hasNextRange(0x20, s20));
        Set<Integer> s40 = ObdPids.decodeSupported(0x40, new int[]{0x44, 0x00, 0x00, 0x10});
        assertEquals(Arrays.asList(0x42, 0x46, 0x5C), new java.util.ArrayList<>(s40));
        assertFalse(ObdPids.hasNextRange(0x40, s40));
    }

    @Test
    public void supportedBitmaskShortIsEmpty() {
        assertTrue(ObdPids.decodeSupported(0x00, new int[]{0xFF}).isEmpty());
        assertTrue(ObdPids.decodeSupported(0x00, null).isEmpty());
    }

    @Test
    public void monitorStatus() {
        ObdPids.MonitorStatus on = ObdPids.decodeMonitorStatus(new int[]{0x83, 0x07, 0xE5, 0x00});
        assertTrue(on.milOn);
        assertEquals(3, on.dtcCount);
        ObdPids.MonitorStatus off = ObdPids.decodeMonitorStatus(new int[]{0x00, 0x07, 0x00, 0x00});
        assertFalse(off.milOn);
        assertEquals(0, off.dtcCount);
        assertNull(ObdPids.decodeMonitorStatus(new int[0]));
    }

    @Test
    public void pollableSplitsFastAndSlowAndSkipsUnknown() {
        Set<Integer> s = new java.util.TreeSet<>(Arrays.asList(0x0C, 0x0D, 0x05, 0x42, 0x99, 0x0B));
        List<ObdPids.Pid> fast = ObdPids.pollable(s, true);
        List<ObdPids.Pid> slow = ObdPids.pollable(s, false);
        assertEquals(Arrays.asList(ObdPids.Pid.RPM, ObdPids.Pid.SPEED), fast);
        assertEquals(Arrays.asList(ObdPids.Pid.COOLANT, ObdPids.Pid.VOLTAGE), slow);
    }
}
