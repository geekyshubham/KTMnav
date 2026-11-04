package app.ktmnav.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Motorcycle styles and a catalogue of KTM, Husqvarna and GasGas models that ship a connected dashboard,
 * used for the Garage silhouettes. The style of an unlisted bike is guessed from its name or model string;
 * nothing here affects the Bluetooth link.
 */
public final class BikeCatalog {

    public enum Style {
        NAKED("Naked"),
        ADVENTURE("Adventure"),
        SPORT("Supersport"),
        ENDURO("Enduro"),
        SUPERMOTO("Supermoto"),
        TOURING("Sport touring"),
        SCRAMBLER("Scrambler"),
        CAFE("Café racer"),
        GENERIC("Motorcycle");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static Style fromPreference(String value) {
            if (value == null) return GENERIC;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }

    public static final class Model {
        public final String brand;
        public final String name;
        public final Style style;

        Model(String brand, String name, Style style) {
            this.brand = brand;
            this.name = name;
            this.style = style;
        }

        public String displayName() {
            return brand + " " + name;
        }
    }

    private static final List<Model> MODELS;

    static {
        List<Model> m = new ArrayList<>();
        String ktm = "KTM", hsq = "Husqvarna", gg = "GasGas";
        for (String n : new String[]{"125 Duke", "200 Duke", "250 Duke", "390 Duke", "790 Duke", "890 Duke", "990 Duke", "1290 Super Duke R", "1390 Super Duke R"}) {
            m.add(new Model(ktm, n, Style.NAKED));
        }
        for (String n : new String[]{"1290 Super Duke GT", "890 SMT"}) {
            m.add(new Model(ktm, n, Style.TOURING));
        }
        for (String n : new String[]{"250 Adventure", "390 Adventure", "790 Adventure", "890 Adventure", "890 Adventure R", "1290 Super Adventure S", "1290 Super Adventure R", "1390 Super Adventure"}) {
            m.add(new Model(ktm, n, Style.ADVENTURE));
        }
        for (String n : new String[]{"RC 125", "RC 200", "RC 390", "RC 8C"}) {
            m.add(new Model(ktm, n, Style.SPORT));
        }
        for (String n : new String[]{"690 Enduro R", "500 EXC-F", "450 EXC-F", "350 EXC-F", "300 EXC", "Freeride E-XC"}) {
            m.add(new Model(ktm, n, Style.ENDURO));
        }
        for (String n : new String[]{"690 SMC R", "450 SMR"}) {
            m.add(new Model(ktm, n, Style.SUPERMOTO));
        }
        for (String n : new String[]{"Svartpilen 125", "Svartpilen 250", "Svartpilen 401", "Svartpilen 801"}) {
            m.add(new Model(hsq, n, Style.SCRAMBLER));
        }
        for (String n : new String[]{"Vitpilen 125", "Vitpilen 250", "Vitpilen 401", "Vitpilen 801"}) {
            m.add(new Model(hsq, n, Style.CAFE));
        }
        m.add(new Model(hsq, "Norden 901", Style.ADVENTURE));
        m.add(new Model(hsq, "Norden 901 Expedition", Style.ADVENTURE));
        m.add(new Model(hsq, "701 Supermoto", Style.SUPERMOTO));
        m.add(new Model(hsq, "701 Enduro", Style.ENDURO));
        m.add(new Model(gg, "SM 700", Style.SUPERMOTO));
        m.add(new Model(gg, "ES 700", Style.ENDURO));
        MODELS = Collections.unmodifiableList(m);
    }

    private BikeCatalog() {
    }

    public static List<Model> models() {
        return MODELS;
    }

    /** Catalogue model whose display name matches exactly (case-insensitive), or null. */
    public static Model find(String displayName) {
        if (displayName == null) return null;
        String wanted = normalise(displayName);
        for (Model m : MODELS) {
            if (normalise(m.displayName()).equals(wanted) || normalise(m.name).equals(wanted)) return m;
        }
        return null;
    }

    /**
     * Guesses the style from a free-text model, dashboard name or Device Information model number.
     * Returns GENERIC when nothing in the text identifies a model family.
     */
    public static Style guess(String text) {
        if (text == null) return Style.GENERIC;
        String s = " " + normalise(text) + " ";
        if (s.isBlank()) return Style.GENERIC;
        Model exact = find(text);
        if (exact != null) return exact.style;
        if (containsAny(s, " super duke gt ", " smt ", " duke gt ")) return Style.TOURING;
        if (containsAny(s, " adventure ", " adv ", " norden ", " super adventure ", " 390adv ", " 890adv ")) return Style.ADVENTURE;
        if (containsAny(s, " svartpilen ", " svart ")) return Style.SCRAMBLER;
        if (containsAny(s, " vitpilen ", " vit ")) return Style.CAFE;
        if (containsAny(s, " smc ", " supermoto ", " sm 700 ", " sm700 ", " smr ", " 701 sm ")) return Style.SUPERMOTO;
        if (containsAny(s, " exc ", " exc-f ", " enduro ", " freeride ", " es 700 ", " es700 ", " e-xc ", " xc ")) return Style.ENDURO;
        if (containsAny(s, " rc ", " rc8 ", " rc390 ", " rc125 ", " rc200 ", " rc 8c ")) return Style.SPORT;
        if (containsAny(s, " duke ", " super duke ")) return Style.NAKED;
        return Style.GENERIC;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String normalise(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ");
    }
}
