package app.ktmnav.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HandlebarControllerTest {

    @Test
    public void preferredNavPackageValidation() {
        assertTrue(HandlebarController.isPackageName("com.google.android.apps.maps"));
        assertTrue(HandlebarController.isPackageName("net.osmand.plus"));
        assertFalse(HandlebarController.isPackageName(null));
        assertFalse(HandlebarController.isPackageName(""));
        assertFalse(HandlebarController.isPackageName("maps"));
        assertFalse(HandlebarController.isPackageName("com.example/.Main"));
        assertFalse(HandlebarController.isPackageName("1com.example"));
    }

    @Test
    public void routerOnlyActionsAreNotExecutedByController() {
        HandlebarController controller = new HandlebarController(null);
        assertEquals("Ignored", controller.execute(HandlebarMappings.Action.NONE));
        assertEquals("Context unavailable", controller.execute(HandlebarMappings.Action.ANSWER_CALL));
    }
}
