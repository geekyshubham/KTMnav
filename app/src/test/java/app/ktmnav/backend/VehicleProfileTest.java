package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class VehicleProfileTest {

    @Test
    public void classification() {
        VehicleProfile gen3 = VehicleProfile.fromServices(Arrays.asList(BccuProtocol.MAIN_SERVICE, BccuProtocol.PRPC_SERVICE,
                BccuProtocol.RCM_SERVICE, BccuProtocol.BASE_SERVICE));
        Assert.assertEquals(VehicleProfile.LABEL_GEN3_NAV, gen3.getLabel());
        Assert.assertTrue(gen3.isSupported());

        VehicleProfile legacy = VehicleProfile.fromServices(Arrays.asList(BccuProtocol.MAIN_SERVICE, BccuProtocol.RCM_SERVICE));
        Assert.assertEquals(VehicleProfile.LABEL_LEGACY_NAV, legacy.getLabel());
        Assert.assertEquals(VehicleProfile.LABEL_GEN3_NAV, legacy.withDeviceInfo("KTM BCCU Gen3", null, null, null, null).getLabel());

        VehicleProfile prpcOnly = VehicleProfile.fromServices(Arrays.asList(BccuProtocol.PRPC_SERVICE, BccuProtocol.BASE_SERVICE));
        Assert.assertEquals(VehicleProfile.LABEL_PRPC_ONLY, prpcOnly.getLabel());
        Assert.assertFalse(prpcOnly.isSupported());
        Assert.assertEquals("model says Gen 3 but without 0700 it cannot navigate", VehicleProfile.LABEL_PRPC_ONLY,
                prpcOnly.withDeviceInfo("BCCU Gen 3", null, null, null, null).getLabel());

        Assert.assertEquals(VehicleProfile.LABEL_UNRECOGNISED, VehicleProfile.fromServices(Collections.emptyList()).getLabel());
        Assert.assertEquals(VehicleProfile.LABEL_UNRECOGNISED, VehicleProfile.fromServices(null).getLabel());
    }

    @Test
    public void describeListsServicesAndDeviceInfo() {
        VehicleProfile p = VehicleProfile.fromServices(Arrays.asList(BccuProtocol.MAIN_SERVICE, BccuProtocol.RCM_SERVICE))
                .withDeviceInfo("390 DUKE", "KTM", "1.2", null, "  ");
        String d = p.describe();
        Assert.assertTrue(d.contains("model=390 DUKE"));
        Assert.assertTrue(d.contains("sw=?"));
        Assert.assertTrue(d.contains("[0700 0100 ]"));
        Assert.assertTrue(d.endsWith(VehicleProfile.LABEL_LEGACY_NAV));
    }
}
