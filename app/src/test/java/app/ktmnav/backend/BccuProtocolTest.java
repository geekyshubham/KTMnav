package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class BccuProtocolTest {

    private final BccuProtocol proto = BccuProtocol.INSTANCE;

    @Test
    public void testTurnIconPayload() {
        byte[] p1 = proto.buildTurnIconPayload(TurnIcon.QUITE_RIGHT, BccuProtocol.Visibility.FULL);
        Assert.assertNotNull(p1);
        Assert.assertEquals(2, p1.length);
        Assert.assertEquals(3, p1[0]); // FULL = 3
        Assert.assertEquals(7, p1[1]); // QUITE_RIGHT = 7

        byte[] p2 = proto.buildTurnIconPayload(TurnIcon.GO_STRAIGHT, BccuProtocol.Visibility.HALF);
        Assert.assertNotNull(p2);
        Assert.assertEquals(2, p2.length);
        Assert.assertEquals(2, p2[0]); // HALF = 2
        Assert.assertEquals(2, p2[1]); // GO_STRAIGHT = 2

        // Null fallbacks: Visibility defaults to FULL(3), TurnIcon defaults to UNDEFINED(1)
        byte[] pNull = proto.buildTurnIconPayload(null, null);
        Assert.assertNotNull(pNull);
        Assert.assertEquals(2, pNull.length);
        Assert.assertEquals(3, pNull[0]);
        Assert.assertEquals(1, pNull[1]);
    }

    @Test
    public void testNavigationStatePayload() {
        // guidanceOn=true, gpsIconOn=false -> state=1
        byte[] p1 = proto.buildNavigationStatePayload(true, false, 10);
        Assert.assertNotNull(p1);
        Assert.assertEquals(2, p1.length);
        Assert.assertEquals(1, p1[0]);
        Assert.assertEquals(10, p1[1]);

        // guidanceOn=true, gpsIconOn=true -> state=3
        byte[] p2 = proto.buildNavigationStatePayload(true, true, 0);
        Assert.assertNotNull(p2);
        Assert.assertEquals(2, p2.length);
        Assert.assertEquals(3, p2[0]);
        Assert.assertEquals(0, p2[1]);

        // guidanceOn=false, gpsIconOn=true -> state=2
        byte[] p3 = proto.buildNavigationStatePayload(false, true, 5);
        Assert.assertNotNull(p3);
        Assert.assertEquals(2, p3.length);
        Assert.assertEquals(2, p3[0]);
        Assert.assertEquals(5, p3[1]);

        // guidanceOn=false, gpsIconOn=false -> state=0
        byte[] p4 = proto.buildNavigationStatePayload(false, false, 0);
        Assert.assertNotNull(p4);
        Assert.assertEquals(2, p4.length);
        Assert.assertEquals(0, p4[0]);
        Assert.assertEquals(0, p4[1]);
    }

    @Test
    public void testElipseFunction() {
        Assert.assertEquals("", BccuProtocol.elipse(null, 10));
        Assert.assertEquals("Main St", BccuProtocol.elipse("Main St", 10));
        Assert.assertEquals("Highway 101", BccuProtocol.elipse("Highway 101", 11));
        Assert.assertEquals("Highw...", BccuProtocol.elipse("Highway 101", 8));
        Assert.assertEquals("ABC", BccuProtocol.elipse("ABCDEF", 3));
        Assert.assertEquals("AB", BccuProtocol.elipse("ABCDEF", 2));
    }

    @Test
    public void testTurnDistancePayload() {
        byte[] p = proto.buildTurnDistancePayload("250 m", BccuProtocol.Visibility.FULL);
        Assert.assertNotNull(p);
        Assert.assertEquals(6, p.length); // 1 byte visibility + 5 bytes text
        Assert.assertEquals(3, p[0]);
        Assert.assertEquals("250 m", new String(p, 1, 5, StandardCharsets.UTF_8));

        // Max length 8 for distance label
        byte[] pLong = proto.buildTurnDistancePayload("1234567890", BccuProtocol.Visibility.FULL);
        Assert.assertEquals(9, pLong.length); // 1 byte visibility + 8 bytes text
        Assert.assertEquals(3, pLong[0]);
        Assert.assertEquals("12345...", new String(pLong, 1, 8, StandardCharsets.UTF_8));

        // Null distance text
        byte[] pNull = proto.buildTurnDistancePayload(null, null);
        Assert.assertEquals(1, pNull.length);
        Assert.assertEquals(3, pNull[0]);
    }

    @Test
    public void testTurnRoadPayload() {
        // Max length 32 for road label
        String longRoad = "A Very Long Road Name That Exceeds Thirty Two Characters Easily";
        byte[] p = proto.buildTurnRoadPayload(longRoad, BccuProtocol.Visibility.HALF);
        Assert.assertNotNull(p);
        Assert.assertEquals(33, p.length); // 1 byte visibility + 32 bytes text
        Assert.assertEquals(2, p[0]);
        String text = new String(p, 1, 32, StandardCharsets.UTF_8);
        Assert.assertTrue(text.endsWith("..."));
        Assert.assertEquals(32, text.length());
    }

    @Test
    public void testNotificationPayload() {
        byte[] p = proto.buildNotificationPayload(BccuProtocol.NotificationIcon.WARNING, "Speed Alert", BccuProtocol.Visibility.FULL);
        Assert.assertNotNull(p);
        Assert.assertEquals(13, p.length); // 1 byte vis + 1 byte icon + 11 bytes "Speed Alert"
        Assert.assertEquals(3, p[0]); // Visibility.FULL = 3
        Assert.assertEquals(5, p[1]); // NotificationIcon.WARNING = 5
        Assert.assertEquals("Speed Alert", new String(p, 2, 11, StandardCharsets.UTF_8));

        // Truncation to 16 characters max
        String longNotif = "Notification text exceeding 16 chars";
        byte[] pLong = proto.buildNotificationPayload(null, longNotif, null);
        Assert.assertEquals(18, pLong.length); // 1 vis + 1 icon + 16 text
        Assert.assertEquals(3, pLong[0]); // default FULL
        Assert.assertEquals(2, pLong[1]); // default NOTIFICATION_WAYPOINT = 2 (only 1 and 2 render on the Gen 3 banner)
        Assert.assertEquals("Notification tex", new String(pLong, 2, 16, StandardCharsets.UTF_8));
    }

    @Test
    public void testProtocolUuids() {
        Assert.assertEquals("71ced1ac-0700-44f5-9454-806ff70b3e02", BccuProtocol.MAIN_SERVICE.toString());
        Assert.assertEquals("71ced1ac-0701-44f5-9454-806ff70b3e02", BccuProtocol.AUTH_REQUEST.toString());
        Assert.assertEquals("71ced1ac-0702-44f5-9454-806ff70b3e02", BccuProtocol.AUTH_REPLY.toString());
        Assert.assertEquals("71ced1ac-0703-44f5-9454-806ff70b3e02", BccuProtocol.NAVIGATION_STATE.toString());
        Assert.assertEquals("71ced1ac-0704-44f5-9454-806ff70b3e02", BccuProtocol.TURN_ICON.toString());
        Assert.assertEquals("71ced1ac-0705-44f5-9454-806ff70b3e02", BccuProtocol.TURN_DISTANCE.toString());
        Assert.assertEquals("71ced1ac-0707-44f5-9454-806ff70b3e02", BccuProtocol.TURN_ROAD.toString());
        Assert.assertEquals("00002902-0000-1000-8000-00805f9b34fb", BccuProtocol.CCCD.toString());
    }
}
