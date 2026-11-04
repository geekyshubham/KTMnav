package app.ktmnav.backend;

import android.content.Context;

/**
 * Picks the turn icon from instruction text, user calibration and the bitmap fallback in a fixed order.
 */
public final class ManeuverResolver {

    public enum Source { TEXT, CALIBRATION, MODEL, TEXT_WEAK, NONE }

    public interface Fallback {
        TurnIcon get();
    }

    public static final class Resolution {
        public final TurnIcon icon;
        public final Source source;

        Resolution(TurnIcon icon, Source source) {
            this.icon = icon;
            this.source = source;
        }
    }

    private ManeuverResolver() {
    }

    public static Resolution resolve(ManeuverText.Result text, TurnIcon calibrated, Fallback fallback) {
        if (text != null && text.exact && usable(text.icon)) {
            return new Resolution(text.icon, Source.TEXT);
        }
        if (usable(calibrated)) {
            return new Resolution(calibrated, Source.CALIBRATION);
        }
        TurnIcon model = fallback != null ? fallback.get() : null;
        if (usable(model)) {
            return new Resolution(model, Source.MODEL);
        }
        if (text != null && usable(text.icon)) {
            return new Resolution(text.icon, Source.TEXT_WEAK);
        }
        return new Resolution(model, Source.NONE);
    }

    public static Resolution resolveForSource(ManeuverText.Result text, TurnIcon calibrated, TurnIcon sourceIcon) {
        if (usable(calibrated)) {
            return new Resolution(calibrated, Source.CALIBRATION);
        }
        if (usable(sourceIcon)) {
            return new Resolution(sourceIcon, Source.MODEL);
        }
        if (text != null && usable(text.icon)) {
            return new Resolution(text.icon, text.exact ? Source.TEXT : Source.TEXT_WEAK);
        }
        return new Resolution(sourceIcon, Source.NONE);
    }

    static boolean usable(TurnIcon icon) {
        return icon != null && icon != TurnIcon.UNKNOWN;
    }

    static NavUpdate refine(Context context, String pkg, NavUpdate update) {
        if (update == null) {
            return null;
        }
        int[] pixels = update.getArrowPixels();
        IconCalibrationStore calibration = pixels != null ? IconCalibrationStore.get(context) : null;
        TurnIcon calibrated = calibration != null ? calibration.lookup(pkg, pixels) : null;
        TurnIcon sourceIcon = update.getTurnIcon();
        ManeuverText.Result text = usable(calibrated) || usable(sourceIcon)
                ? null : ManeuverText.parse(update.getDistanceText(), update.getRoadText());
        Resolution r = resolveForSource(text, calibrated, sourceIcon);
        if (calibration != null && r.source != Source.CALIBRATION && r.source != Source.MODEL) {
            calibration.noteCapture(pkg, pixels, r.icon, r.source, System.currentTimeMillis());
        }
        return r.icon == sourceIcon ? update : update.withArrow(r.icon, pixels);
    }
}
