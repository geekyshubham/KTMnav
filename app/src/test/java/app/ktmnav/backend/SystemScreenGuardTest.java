package app.ktmnav.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SystemScreenGuardTest {

    @Test
    public void blocksSystemUiAndPermissionDialogs() {
        assertTrue(SystemScreenGuard.isBlocked("com.android.systemui"));
        assertTrue(SystemScreenGuard.isBlocked("com.google.android.permissioncontroller"));
        assertTrue(SystemScreenGuard.isBlocked("com.android.permissioncontroller"));
        assertTrue(SystemScreenGuard.isBlocked("android"));
        assertTrue(SystemScreenGuard.isBlocked(null));
        assertTrue(SystemScreenGuard.isBlocked(" "));
    }

    @Test
    public void allowsOrdinaryApps() {
        assertFalse(SystemScreenGuard.isBlocked("app.ktmnav"));
        assertFalse(SystemScreenGuard.isBlocked("com.google.android.apps.maps"));
        assertFalse(SystemScreenGuard.isBlocked("android.example"));
        assertFalse(SystemScreenGuard.isBlocked("com.android.systemui.extra"));
    }

    @Test
    public void centreOfBounds() {
        assertArrayEquals(new float[]{150f, 75f}, SystemScreenGuard.centre(100, 50, 200, 100), 0f);
        assertNull(SystemScreenGuard.centre(10, 10, 10, 20));
        assertNull(SystemScreenGuard.centre(10, 30, 20, 20));
        assertNull(SystemScreenGuard.centre(-300, 0, -100, 10));
    }
}
