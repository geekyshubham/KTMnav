package app.ktmnav.backend;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * Immutable dash classification from the GATT service table (0700 navigation, 0600 PRPC,
 * 0100 handlebar RCM, 0000 base/VIN) plus Device Information strings.
 * Behaviour follows Navigator Gen3 v0.3.0 VehicleProfile (MIT, Copyright (c) 2026 OpenDash contributors).
 */
public final class VehicleProfile {

    public static final String LABEL_GEN3_NAV = "Gen 3 BCCU (navigation)";
    public static final String LABEL_LEGACY_NAV = "BCCU dash (legacy nav)";
    public static final String LABEL_PRPC_ONLY = "BCCU dash (PRPC only)";
    public static final String LABEL_UNRECOGNISED = "Unrecognised dash";
    public static final String UNSUPPORTED_STATUS = "This dashboard does not offer the KTM navigation service";

    private final boolean hasNavigation;
    private final boolean hasPrpc;
    private final boolean hasRcm;
    private final boolean hasBase;
    private final String modelNumber;
    private final String manufacturer;
    private final String firmwareRevision;
    private final String hardwareRevision;
    private final String softwareRevision;

    public VehicleProfile(boolean hasNavigation, boolean hasPrpc, boolean hasRcm, boolean hasBase,
                          String modelNumber, String manufacturer, String firmwareRevision,
                          String hardwareRevision, String softwareRevision) {
        this.hasNavigation = hasNavigation;
        this.hasPrpc = hasPrpc;
        this.hasRcm = hasRcm;
        this.hasBase = hasBase;
        this.modelNumber = blankToNull(modelNumber);
        this.manufacturer = blankToNull(manufacturer);
        this.firmwareRevision = blankToNull(firmwareRevision);
        this.hardwareRevision = blankToNull(hardwareRevision);
        this.softwareRevision = blankToNull(softwareRevision);
    }

    public static VehicleProfile fromServices(Collection<UUID> services) {
        boolean nav = services != null && services.contains(BccuProtocol.MAIN_SERVICE);
        boolean prpc = services != null && services.contains(BccuProtocol.PRPC_SERVICE);
        boolean rcm = services != null && services.contains(BccuProtocol.RCM_SERVICE);
        boolean base = services != null && services.contains(BccuProtocol.BASE_SERVICE);
        return new VehicleProfile(nav, prpc, rcm, base, null, null, null, null, null);
    }

    public VehicleProfile withDeviceInfo(String model, String maker, String firmware, String hardware, String software) {
        return new VehicleProfile(hasNavigation, hasPrpc, hasRcm, hasBase,
                model != null ? model : modelNumber,
                maker != null ? maker : manufacturer,
                firmware != null ? firmware : firmwareRevision,
                hardware != null ? hardware : hardwareRevision,
                software != null ? software : softwareRevision);
    }

    public boolean isSupported() {
        return hasNavigation;
    }

    public boolean hasNavigation() {
        return hasNavigation;
    }

    public boolean hasPrpc() {
        return hasPrpc;
    }

    public boolean hasRcm() {
        return hasRcm;
    }

    public boolean hasBase() {
        return hasBase;
    }

    public String getModelNumber() {
        return modelNumber;
    }

    public String getLabel() {
        if (hasNavigation) {
            String model = modelNumber != null ? modelNumber.toLowerCase(Locale.ROOT) : "";
            boolean modelSaysGen3 = model.contains("bccu") || model.contains("gen 3") || model.contains("gen3");
            return (modelSaysGen3 || hasPrpc) ? LABEL_GEN3_NAV : LABEL_LEGACY_NAV;
        }
        return hasPrpc ? LABEL_PRPC_ONLY : LABEL_UNRECOGNISED;
    }

    public String describe() {
        return "model=" + orUnknown(modelNumber)
                + " fw=" + orUnknown(firmwareRevision)
                + " hw=" + orUnknown(hardwareRevision)
                + " sw=" + orUnknown(softwareRevision)
                + " mfr=" + orUnknown(manufacturer)
                + " [" + (hasNavigation ? "0700 " : "") + (hasPrpc ? "0600 " : "")
                + (hasRcm ? "0100 " : "") + (hasBase ? "0000" : "") + "] -> " + getLabel();
    }

    @Override
    public String toString() {
        return "VehicleProfile(" + describe() + ")";
    }

    private static String orUnknown(String s) {
        return s != null ? s : "?";
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
