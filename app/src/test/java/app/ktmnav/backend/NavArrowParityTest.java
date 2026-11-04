package app.ktmnav.backend;

import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import static org.junit.Assert.*;

/** Complete hash table vectors extracted as data from the supplied recovered Java. */
public final class NavArrowParityTest {
    @Test public void everyRecoveredArrowHashRetainsItsOriginalMeaning() throws Exception {
        JSONObject sources;
        try (InputStream stream = getClass().getResourceAsStream("/original-arrow-vectors.json")) {
            assertNotNull(stream);
            sources = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        int examined = 0;
        Iterator<String> sourceNames = sources.keys();
        while (sourceNames.hasNext()) {
            String source = sourceNames.next();
            JSONObject entries = sources.getJSONObject(source);
            Iterator<String> hashes = entries.keys();
            while (hashes.hasNext()) {
                String hashText = hashes.next(); int hash = Integer.parseInt(hashText);
                TurnIcon actual;
                switch (source) {
                    case "KurvigerArrows": actual = KurvigerArrows.lookup(hash); break;
                    case "OrganicMapsArrows": actual = OrganicMapsArrows.lookup(hash); break;
                    case "OsmAndArrows": actual = OsmAndArrows.lookup(hash); break;
                    case "TomTomArrows": actual = TomTomArrows.lookup(hash); break;
                    default: throw new AssertionError(source);
                }
                assertNotNull(source + " missing original hash " + hash, actual);
                assertEquals(source + " hash " + hash, entries.getString(hashText), actual.name());
                examined++;
            }
        }
        assertEquals("Expected complete literal-icon subset from the four recovered tables", 39, examined);
    }
}
