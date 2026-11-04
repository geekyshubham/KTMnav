package app.ktmnav.backend;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps navigation instruction phrases (EN, DE, FR, ES, IT, NL, Hindi) to dash turn icons before any bitmap lookup.
 * Text-first ordering follows OpenDash KTM-Nav-GEN3 TurnIconHeuristic (MIT).
 */
public final class ManeuverText {

    public static final class Result {
        public final TurnIcon icon;
        public final boolean exact;
        public final int roundaboutExit;

        Result(TurnIcon icon, boolean exact, int roundaboutExit) {
            this.icon = icon;
            this.exact = exact;
            this.roundaboutExit = roundaboutExit;
        }

        @Override
        public String toString() {
            return icon + (exact ? "" : "?") + (roundaboutExit > 0 ? "#" + roundaboutExit : "");
        }
    }

    private enum Side { LEFT, RIGHT }

    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");

    private static final Set<String> LEFT = words("left", "links", "linksaf", "gauche", "izquierda", "sinistra",
            "baayen", "bayen", "baen", "baye", "baayein", "बाएँ", "बाएं", "बायें", "बाईं");
    private static final Set<String> RIGHT = words("right", "rechts", "rechtsaf", "droite", "derecha", "destra",
            "daayen", "dayen", "daen", "daye", "daayein", "दाएँ", "दाएं", "दायें", "दाईं");
    private static final Set<String> DEVANAGARI_SIDES = words("बाएँ", "बाएं", "बायें", "बाईं", "दाएँ", "दाएं", "दायें", "दाईं");

    private static final String[] THEN = phrases("then", "dann", "danach", "puis", "ensuite", "luego", "despues",
            "poi", "quindi", "daarna", "dan", "phir", "फिर");
    private static final String[] DESTINATION = phrases("destination", "arrive", "arrived", "arriving", "arrival",
            "ziel", "zielort", "erreicht", "arrivee", "arrivez", "destino", "llegado", "llegada",
            "arrivato", "arrivata", "arrivo", "destinazione", "bestemming", "aangekomen", "manzil", "gantavya",
            "मंज़िल", "मंजिल", "गंतव्य");
    private static final String[] UTURN = phrases("u turn", "uturn", "make a u", "wenden", "wende", "demi tour",
            "cambio de sentido", "media vuelta", "inversione", "inversione a u", "keer om", "omkeren", "keren",
            "यू टर्न", "यूटर्न", "u टर्न");
    private static final String[] ROUNDABOUT = phrases("roundabout", "rotary", "traffic circle", "kreisverkehr",
            "kreisel", "rond point", "giratoire", "rotonde", "rotonda", "glorieta", "rotatoria", "gol chakkar",
            "golchakkar", "गोल चक्कर", "गोलचक्कर");
    private static final String[] SHARP = phrases("sharp", "sharply", "hard", "scharf", "fortement", "serre",
            "bruscamente", "cerrada", "decisamente", "stretta", "nettamente", "scherp", "tez", "tezi", "teekha",
            "तेज़", "तेज", "तीव्र", "तीखा", "तीखे");
    private static final String[] SLIGHT = phrases("slight", "slightly", "bear", "soft", "leicht", "halb",
            "legerement", "ligeramente", "leggermente", "licht", "lichtjes", "flauw", "flauwe", "thoda", "thoda sa",
            "halka", "थोड़ा", "थोड़ी", "हल्का", "हल्के");
    private static final String[] KEEP = phrases("keep", "stay", "halten", "halte", "bleiben", "serrez", "restez",
            "gardez", "mantente", "mantengase", "mantenga", "mantener", "mantieni", "tieni", "resta", "houd", "blijf",
            "rahen", "rahein", "rahe", "रहें", "रहे", "रहिए");
    private static final String[] MIDDLE = phrases("middle", "center", "centre", "mitte", "mittleren", "mittlere",
            "milieu", "centro", "central", "centrale", "midden", "middelste", "beech", "बीच");
    private static final String[] MERGE = phrases("merge", "merging", "einfadeln", "auffahren", "rejoignez",
            "rejoindre", "inserez", "insertion", "incorporate", "incorporese", "incorporarse", "immettiti",
            "immettersi", "voeg in", "invoegen", "मर्ज");
    private static final String[] EXIT_NOUNS = phrases("exit", "ausfahrt", "abfahrt", "sortie", "salida", "uscita",
            "afslag", "afrit", "nikas", "निकास", "एग्जिट", "एग्ज़िट", "एक्जिट");
    private static final String[] RAMP = phrases("exit", "ramp", "off ramp", "slip road", "ausfahrt", "abfahrt",
            "sortie", "bretelle", "salida", "rampa", "uscita", "svincolo", "afrit", "afslag", "nikas", "निकास",
            "रैंप", "एग्जिट", "एग्ज़िट");
    private static final String[] TURN = phrases("turn", "turning", "abbiegen", "biegen", "tournez", "tourner",
            "tourne", "gira", "gire", "girar", "svolta", "svoltare", "svolti", "sla", "slaan", "afslaan", "linksaf",
            "rechtsaf", "mudein", "mude", "muden", "mudiye", "mudiyega", "मुड़ें", "मुड़े", "मुड़िए", "मुड़");
    private static final String[] STRAIGHT = phrases("straight", "continue", "continuing", "proceed", "head",
            "geradeaus", "weiter", "weiterfahren", "tout droit", "continuez", "continuer", "recto", "derecho",
            "sigue", "siga", "continua", "dritto", "diritto", "prosegui", "proseguire", "rechtdoor", "ga verder",
            "rijd verder", "seedha", "sidha", "seedhe", "सीधे", "सीधा", "aage");
    private static final Set<String> HARD_CONNECTORS = words("onto", "toward", "towards", "into");
    private static final Set<String> CONNECTORS = words("onto", "toward", "towards", "into", "auf", "richtung",
            "vers", "sur", "hacia", "verso", "naar", "richting");
    private static final Set<String> ARTICLES = words("the", "la", "le", "der", "die", "de", "il", "l");

    private static final Map<String, Integer> ORDINALS = new LinkedHashMap<>();
    private static final Pattern EXIT_BEFORE;
    private static final Pattern EXIT_AFTER;
    private static final Pattern EXIT_ORDINAL;

    static {
        String[][] table = {
                {"1", "first", "erste", "ersten", "premiere", "primera", "primer", "prima", "primo", "eerste", "pehla", "pehli", "pahla", "पहला", "पहले", "पहली"},
                {"2", "second", "zweite", "zweiten", "deuxieme", "seconde", "segunda", "segundo", "seconda", "secondo", "tweede", "doosra", "doosri", "dusra", "दूसरा", "दूसरे", "दूसरी"},
                {"3", "third", "dritte", "dritten", "troisieme", "tercera", "tercer", "terza", "terzo", "derde", "teesra", "teesri", "tisra", "तीसरा", "तीसरे", "तीसरी"},
                {"4", "fourth", "vierte", "vierten", "quatrieme", "cuarta", "cuarto", "quarta", "quarto", "vierde", "chautha", "chauthi", "चौथा", "चौथे", "चौथी"},
                {"5", "fifth", "funfte", "funften", "cinquieme", "quinta", "quinto", "vijfde", "paanchva", "पांचवां", "पाँचवें", "पांचवें"},
                {"6", "sixth", "sechste", "sechsten", "sixieme", "sexta", "sexto", "sesta", "sesto", "zesde", "छठा", "छठे"},
        };
        for (String[] row : table) {
            int n = Integer.parseInt(row[0]);
            for (int i = 1; i < row.length; i++) {
                ORDINALS.put(normalize(row[i]).trim(), n);
            }
        }
        StringBuilder nouns = new StringBuilder();
        for (String noun : EXIT_NOUNS) {
            if (nouns.length() > 0) {
                nouns.append('|');
            }
            nouns.append(Pattern.quote(noun));
        }
        StringBuilder ordinals = new StringBuilder();
        for (String word : ORDINALS.keySet()) {
            if (ordinals.length() > 0) {
                ordinals.append('|');
            }
            ordinals.append(Pattern.quote(word));
        }
        EXIT_BEFORE = Pattern.compile(" (\\d{1,2}) ?(?:st|nd|rd|th|e|er|re|eme|ere|a|o|ste|de)? (?:" + nouns + ") ");
        EXIT_ORDINAL = Pattern.compile(" (" + ordinals + ") (?:" + nouns + ") ");
        EXIT_AFTER = Pattern.compile(" (?:" + nouns + ") (?:nr |no |n |number |nummer )?(\\d{1,2}) ");
    }

    private ManeuverText() {
    }

    public static Result parse(String... fields) {
        return parse(defaultLeftHandTraffic(), fields);
    }

    public static Result parse(boolean leftHandTraffic, String... fields) {
        if (fields == null) {
            return null;
        }
        Result weak = null;
        for (String field : fields) {
            Result r = parseField(field, leftHandTraffic);
            if (r == null) {
                continue;
            }
            if (r.exact) {
                return r;
            }
            if (weak == null) {
                weak = r;
            }
        }
        return weak;
    }

    static Result parseField(String field, boolean leftHandTraffic) {
        if (field == null || field.trim().isEmpty()) {
            return null;
        }
        List<String> tokens = tokens(field);
        if (tokens.isEmpty() || startsWith(tokens, THEN)) {
            return null;
        }
        String full = join(tokens, tokens.size());
        if (containsAny(full, DESTINATION)) {
            return exact(TurnIcon.END);
        }
        String s = join(tokens, cutIndex(tokens));
        Side side = side(s);

        if (containsAny(s, ROUNDABOUT)) {
            return roundabout(s, side);
        }
        if (containsAny(s, UTURN)) {
            if (side != null) {
                return exact(side == Side.LEFT ? TurnIcon.UTURN_LEFT : TurnIcon.UTURN_RIGHT);
            }
            return weak(leftHandTraffic ? TurnIcon.UTURN_RIGHT : TurnIcon.UTURN_LEFT);
        }
        if (side != null && containsAny(s, SHARP)) {
            return exact(side == Side.LEFT ? TurnIcon.HEAVY_LEFT : TurnIcon.HEAVY_RIGHT);
        }
        if (side != null && containsAny(s, SLIGHT)) {
            return exact(side == Side.LEFT ? TurnIcon.LIGHT_LEFT : TurnIcon.LIGHT_RIGHT);
        }

        int keep = firstIndex(s, KEEP);
        int merge = firstIndex(s, MERGE);
        int ramp = firstIndex(s, RAMP);
        int turn = firstIndex(s, TURN);
        int straight = firstIndex(s, STRAIGHT);
        int first = min(keep, merge, ramp, turn, straight);
        if (first == Integer.MAX_VALUE) {
            return null;
        }
        if (first == keep) {
            if (side != null) {
                return exact(side == Side.LEFT ? TurnIcon.KEEP_LEFT : TurnIcon.KEEP_RIGHT);
            }
            if (containsAny(s, MIDDLE)) {
                return exact(TurnIcon.KEEP_MIDDLE);
            }
            return straight != Integer.MAX_VALUE ? exact(TurnIcon.GO_STRAIGHT) : null;
        }
        if (first == merge) {
            if (side != null) {
                return exact(side == Side.LEFT ? TurnIcon.ENTER_HIGHWAY_LEFT_LANE : TurnIcon.ENTER_HIGHWAY_RIGHT_LANE);
            }
            return weak(leftHandTraffic ? TurnIcon.ENTER_HIGHWAY_LEFT_LANE : TurnIcon.ENTER_HIGHWAY_RIGHT_LANE);
        }
        if (first == ramp) {
            if (side != null) {
                return exact(side == Side.LEFT ? TurnIcon.LEAVE_HIGHWAY_LEFT_LANE : TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE);
            }
            return weak(leftHandTraffic ? TurnIcon.LEAVE_HIGHWAY_LEFT_LANE : TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE);
        }
        if (first == turn) {
            if (side != null) {
                return exact(side == Side.LEFT ? TurnIcon.QUITE_LEFT : TurnIcon.QUITE_RIGHT);
            }
            return straight != Integer.MAX_VALUE ? exact(TurnIcon.GO_STRAIGHT) : null;
        }
        return side == null ? exact(TurnIcon.GO_STRAIGHT) : weak(TurnIcon.GO_STRAIGHT);
    }

    private static Result roundabout(String s, Side side) {
        int exit = roundaboutExit(s);
        if (exit > 0) {
            return new Result(RoundaboutGlyph.rightHandForExit(exit), true, exit);
        }
        if (containsAny(s, UTURN)) {
            return exact(TurnIcon.RAB_SECT_16_RH);
        }
        if (side == Side.RIGHT) {
            return exact(TurnIcon.RAB_SECT_4_RH);
        }
        if (side == Side.LEFT) {
            return exact(TurnIcon.RAB_SECT_12_RH);
        }
        if (containsAny(s, STRAIGHT)) {
            return exact(TurnIcon.RAB_SECT_8_RH);
        }
        return weak(TurnIcon.RAB_SECT_8_RH);
    }

    static int roundaboutExit(String s) {
        Matcher before = EXIT_BEFORE.matcher(s);
        if (before.find()) {
            return clampExit(before.group(1));
        }
        Matcher after = EXIT_AFTER.matcher(s);
        if (after.find()) {
            return clampExit(after.group(1));
        }
        Matcher ordinal = EXIT_ORDINAL.matcher(s);
        if (ordinal.find()) {
            Integer n = ORDINALS.get(ordinal.group(1));
            return n != null ? n : 0;
        }
        return 0;
    }

    private static int clampExit(String digits) {
        int n = Integer.parseInt(digits);
        return n >= 1 && n <= 16 ? n : 0;
    }

    public static boolean defaultLeftHandTraffic() {
        return isLeftHandTraffic(Locale.getDefault().getCountry());
    }

    private static final Set<String> LHT_COUNTRIES = new HashSet<>(Arrays.asList(
            "IN", "GB", "IE", "AU", "NZ", "JP", "ZA", "PK", "BD", "LK", "NP", "BT", "MY", "SG", "TH", "ID",
            "HK", "MO", "KE", "UG", "TZ", "ZM", "ZW", "MW", "MZ", "BW", "NA", "LS", "SZ", "MU", "CY", "MT",
            "JM", "TT", "BS", "BB", "GY", "SR", "FJ", "PG", "BN", "TL", "MV"));

    static boolean isLeftHandTraffic(String country) {
        return country != null && LHT_COUNTRIES.contains(country.toUpperCase(Locale.ROOT));
    }

    private static Side side(String s) {
        int left = Integer.MAX_VALUE;
        int right = Integer.MAX_VALUE;
        boolean devanagari = false;
        for (String w : DEVANAGARI_SIDES) {
            if (s.contains(" " + w + " ")) {
                devanagari = true;
                break;
            }
        }
        for (String w : LEFT) {
            if (!devanagari || DEVANAGARI_SIDES.contains(w)) {
                left = Math.min(left, indexOf(s, w));
            }
        }
        for (String w : RIGHT) {
            if (!devanagari || DEVANAGARI_SIDES.contains(w)) {
                right = Math.min(right, indexOf(s, w));
            }
        }
        if (left == Integer.MAX_VALUE && right == Integer.MAX_VALUE) {
            return null;
        }
        return left <= right ? Side.LEFT : Side.RIGHT;
    }

    private static int cutIndex(List<String> tokens) {
        boolean anchored = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (anchored && i > 0 && CONNECTORS.contains(t)
                    && (HARD_CONNECTORS.contains(t) || !sideFollows(tokens, i + 1))) {
                return i;
            }
            if (LEFT.contains(t) || RIGHT.contains(t) || isManeuverToken(t)) {
                anchored = true;
            }
        }
        return tokens.size();
    }

    private static boolean sideFollows(List<String> tokens, int i) {
        if (i < tokens.size() && ARTICLES.contains(tokens.get(i))) {
            i++;
        }
        return i < tokens.size() && (LEFT.contains(tokens.get(i)) || RIGHT.contains(tokens.get(i)));
    }

    private static boolean isManeuverToken(String t) {
        String padded = " " + t + " ";
        return containsAny(padded, ROUNDABOUT) || containsAny(padded, UTURN) || containsAny(padded, KEEP)
                || containsAny(padded, MERGE) || containsAny(padded, RAMP) || containsAny(padded, TURN)
                || containsAny(padded, STRAIGHT);
    }

    static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD);
        folded = COMBINING.matcher(folded).replaceAll("").toLowerCase(Locale.ROOT);
        return join(tokensOfFolded(folded), Integer.MAX_VALUE);
    }

    private static List<String> tokens(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return tokensOfFolded(COMBINING.matcher(folded).replaceAll("").toLowerCase(Locale.ROOT));
    }

    private static List<String> tokensOfFolded(String folded) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(folded);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String join(List<String> tokens, int end) {
        StringBuilder sb = new StringBuilder(" ");
        int limit = Math.min(end, tokens.size());
        for (int i = 0; i < limit; i++) {
            sb.append(tokens.get(i)).append(' ');
        }
        return sb.toString();
    }

    private static boolean startsWith(List<String> tokens, String[] phrases) {
        String head = " " + tokens.get(0) + " ";
        for (String p : phrases) {
            if (head.equals(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String padded, String[] phrases) {
        return firstIndex(padded, phrases) != Integer.MAX_VALUE;
    }

    private static int firstIndex(String padded, String[] phrases) {
        int best = Integer.MAX_VALUE;
        for (String p : phrases) {
            best = Math.min(best, indexOf(padded, p));
        }
        return best;
    }

    private static int indexOf(String padded, String phrase) {
        int i = padded.indexOf(" " + phrase + " ");
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static int min(int... values) {
        int m = Integer.MAX_VALUE;
        for (int v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static Result exact(TurnIcon icon) {
        return new Result(icon, true, 0);
    }

    private static Result weak(TurnIcon icon) {
        return new Result(icon, false, 0);
    }

    private static String[] phrases(String... raw) {
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = normalize(raw[i]).trim();
        }
        return out;
    }

    private static Set<String> words(String... raw) {
        Set<String> out = new HashSet<>();
        for (String w : raw) {
            out.add(normalize(w).trim());
        }
        return out;
    }
}
