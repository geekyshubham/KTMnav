package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class ManeuverTextTest {

    private static final Object[][] EXACT = {
            {"Turn left onto MG Road", TurnIcon.QUITE_LEFT},
            {"Turn right", TurnIcon.QUITE_RIGHT},
            {"In 200 m, turn right onto NH 44", TurnIcon.QUITE_RIGHT},
            {"Turn left onto Right Bank Road", TurnIcon.QUITE_LEFT},
            {"Sharp left onto Church Street", TurnIcon.HEAVY_LEFT},
            {"Turn sharp right toward Left Bank", TurnIcon.HEAVY_RIGHT},
            {"Slight left toward NH 48", TurnIcon.LIGHT_LEFT},
            {"Bear right onto Ring Road", TurnIcon.LIGHT_RIGHT},
            {"Keep left at the fork", TurnIcon.KEEP_LEFT},
            {"Keep right to continue on NH 48", TurnIcon.KEEP_RIGHT},
            {"Stay in the middle lane", TurnIcon.KEEP_MIDDLE},
            {"Keep straight", TurnIcon.GO_STRAIGHT},
            {"Make a U-turn on the right", TurnIcon.UTURN_RIGHT},
            {"Make a U-turn to the left", TurnIcon.UTURN_LEFT},
            {"At the roundabout, take the 1st exit onto MG Road", TurnIcon.RAB_SECT_4_RH},
            {"At the roundabout, take the 2nd exit", TurnIcon.RAB_SECT_8_RH},
            {"At the roundabout, take the third exit toward Airport", TurnIcon.RAB_SECT_12_RH},
            {"At the roundabout, take the 5th exit", TurnIcon.RAB_SECT_14_RH},
            {"At the roundabout, turn right onto Left Street", TurnIcon.RAB_SECT_4_RH},
            {"At the roundabout, turn left", TurnIcon.RAB_SECT_12_RH},
            {"At the roundabout, continue straight", TurnIcon.RAB_SECT_8_RH},
            {"Merge left onto the motorway", TurnIcon.ENTER_HIGHWAY_LEFT_LANE},
            {"Take the exit on the left toward Mysore", TurnIcon.LEAVE_HIGHWAY_LEFT_LANE},
            {"Use the right lane to take the ramp to NH 75", TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE},
            {"Use the left 2 lanes to turn left onto Hosur Road", TurnIcon.QUITE_LEFT},
            {"Continue onto Outer Ring Road", TurnIcon.GO_STRAIGHT},
            {"Continue straight to stay on NH 44", TurnIcon.GO_STRAIGHT},
            {"Head north on Brigade Road toward Church Street", TurnIcon.GO_STRAIGHT},
            {"Your destination is on the left", TurnIcon.END},
            {"Arrive at destination, on the right", TurnIcon.END},
            {"You have arrived", TurnIcon.END},
            {"Turn right to stay on NH 44", TurnIcon.QUITE_RIGHT},
            {"Links abbiegen auf Hauptstraße", TurnIcon.QUITE_LEFT},
            {"Leicht rechts abbiegen", TurnIcon.LIGHT_RIGHT},
            {"Scharf links abbiegen", TurnIcon.HEAVY_LEFT},
            {"Rechts halten Richtung A9", TurnIcon.KEEP_RIGHT},
            {"Im Kreisverkehr die 2. Ausfahrt nehmen", TurnIcon.RAB_SECT_8_RH},
            {"Geradeaus weiterfahren", TurnIcon.GO_STRAIGHT},
            {"Sie haben Ihr Ziel erreicht", TurnIcon.END},
            {"Tournez à gauche sur Rue de la Paix", TurnIcon.QUITE_LEFT},
            {"Tournez légèrement à droite", TurnIcon.LIGHT_RIGHT},
            {"Serrez à droite", TurnIcon.KEEP_RIGHT},
            {"Au rond-point, prenez la 3e sortie vers Lyon", TurnIcon.RAB_SECT_12_RH},
            {"Continuez tout droit", TurnIcon.GO_STRAIGHT},
            {"Gira a la izquierda hacia Calle Mayor", TurnIcon.QUITE_LEFT},
            {"Gira ligeramente a la derecha", TurnIcon.LIGHT_RIGHT},
            {"Mantente a la izquierda", TurnIcon.KEEP_LEFT},
            {"En la rotonda, toma la segunda salida", TurnIcon.RAB_SECT_8_RH},
            {"En la rotonda, toma la 1.ª salida", TurnIcon.RAB_SECT_4_RH},
            {"Continúa recto", TurnIcon.GO_STRAIGHT},
            {"Svolta a destra in Via Roma", TurnIcon.QUITE_RIGHT},
            {"Svolta leggermente a sinistra", TurnIcon.LIGHT_LEFT},
            {"Mantieni la sinistra", TurnIcon.KEEP_LEFT},
            {"Alla rotonda prendi la seconda uscita", TurnIcon.RAB_SECT_8_RH},
            {"Continua dritto", TurnIcon.GO_STRAIGHT},
            {"Sla linksaf naar de Hoofdweg", TurnIcon.QUITE_LEFT},
            {"Sla scherp rechtsaf", TurnIcon.HEAVY_RIGHT},
            {"Houd rechts aan", TurnIcon.KEEP_RIGHT},
            {"Neem op de rotonde de 2e afslag", TurnIcon.RAB_SECT_8_RH},
            {"Ga rechtdoor", TurnIcon.GO_STRAIGHT},
            {"Bestemming bereikt", TurnIcon.END},
            {"बाएं मुड़ें", TurnIcon.QUITE_LEFT},
            {"दाएँ मुड़ें", TurnIcon.QUITE_RIGHT},
            {"थोड़ा बाएं मुड़ें", TurnIcon.LIGHT_LEFT},
            {"दाएं रहें", TurnIcon.KEEP_RIGHT},
            {"सीधे चलते रहें", TurnIcon.GO_STRAIGHT},
            {"गोल चक्कर पर, दूसरे निकास से बाहर निकलें", TurnIcon.RAB_SECT_8_RH},
            {"अपनी मंज़िल पर पहुंचें", TurnIcon.END},
            {"Right Bank Road पर बाएं मुड़ें", TurnIcon.QUITE_LEFT},
            {"Baayen mudein", TurnIcon.QUITE_LEFT},
            {"Daayen mudein", TurnIcon.QUITE_RIGHT},
            {"Seedha chalein", TurnIcon.GO_STRAIGHT},
            {"Gol chakkar par teesra exit lein", TurnIcon.RAB_SECT_12_RH},
            {"Gira hacia la izquierda", TurnIcon.QUITE_LEFT},
            {"Sla af naar links", TurnIcon.QUITE_LEFT},
            {"Merge left onto Right Bank Expressway", TurnIcon.ENTER_HIGHWAY_LEFT_LANE},
    };

    @Test
    public void testExactPhraseVectors() {
        Assert.assertTrue(EXACT.length >= 40);
        for (Object[] row : EXACT) {
            ManeuverText.Result r = ManeuverText.parse(false, (String) row[0]);
            Assert.assertNotNull("No result for: " + row[0], r);
            Assert.assertEquals("Icon for: " + row[0], row[1], r.icon);
            Assert.assertTrue("Expected exact for: " + row[0], r.exact);
        }
    }

    @Test
    public void testWeakPhrasesDependOnTrafficSide() {
        assertWeak("Make a U-turn", false, TurnIcon.UTURN_LEFT);
        assertWeak("Make a U-turn", true, TurnIcon.UTURN_RIGHT);
        assertWeak("Bitte wenden", false, TurnIcon.UTURN_LEFT);
        assertWeak("Faites demi-tour", true, TurnIcon.UTURN_RIGHT);
        assertWeak("यू-टर्न लें", true, TurnIcon.UTURN_RIGHT);
        assertWeak("Merge onto NH 48", false, TurnIcon.ENTER_HIGHWAY_RIGHT_LANE);
        assertWeak("Merge onto Left Bank Expressway", true, TurnIcon.ENTER_HIGHWAY_LEFT_LANE);
        assertWeak("Take exit 12 toward Right Bank", false, TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE);
        assertWeak("Take the ramp onto NH 48", true, TurnIcon.LEAVE_HIGHWAY_LEFT_LANE);
        assertWeak("Enter the roundabout", false, TurnIcon.RAB_SECT_8_RH);
    }

    @Test
    public void testNoManeuverReturnsNull() {
        Assert.assertNull(ManeuverText.parse(false, "110 m"));
        Assert.assertNull(ManeuverText.parse(false, "towards 1st Cross Rd"));
        Assert.assertNull(ManeuverText.parse(false, "Right Bank Road"));
        Assert.assertNull(ManeuverText.parse(false, ""));
        Assert.assertNull(ManeuverText.parse(false, (String) null));
        Assert.assertNull(ManeuverText.parse(false, (String[]) null));
        Assert.assertNull(ManeuverText.parse(false, "Then turn right"));
        Assert.assertNull(ManeuverText.parse(false, "Dann rechts abbiegen"));
    }

    @Test
    public void testFieldOrderPrefersFirstExactMatch() {
        ManeuverText.Result r = ManeuverText.parse(false, "350 m", "Turn left onto MG Road", "Then turn right");
        Assert.assertEquals(TurnIcon.QUITE_LEFT, r.icon);
        Assert.assertTrue(r.exact);

        ManeuverText.Result weakThenExact = ManeuverText.parse(false, "Make a U-turn", "Turn right onto MG Road");
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, weakThenExact.icon);

        ManeuverText.Result onlyWeak = ManeuverText.parse(false, "200 m", "Make a U-turn");
        Assert.assertEquals(TurnIcon.UTURN_LEFT, onlyWeak.icon);
        Assert.assertFalse(onlyWeak.exact);
    }

    @Test
    public void testRoundaboutExitNumberIsReported() {
        Assert.assertEquals(3, ManeuverText.parse(false, "At the roundabout, take the 3rd exit").roundaboutExit);
        Assert.assertEquals(2, ManeuverText.parse(false, "At the roundabout, take exit 2").roundaboutExit);
        Assert.assertEquals(0, ManeuverText.parse(false, "At the roundabout, turn left").roundaboutExit);
        Assert.assertEquals(0, ManeuverText.roundaboutExit(" in 200 m at the roundabout "));
    }

    @Test
    public void testLeftHandTrafficCountries() {
        Assert.assertTrue(ManeuverText.isLeftHandTraffic("IN"));
        Assert.assertTrue(ManeuverText.isLeftHandTraffic("gb"));
        Assert.assertFalse(ManeuverText.isLeftHandTraffic("DE"));
        Assert.assertFalse(ManeuverText.isLeftHandTraffic(""));
        Assert.assertFalse(ManeuverText.isLeftHandTraffic(null));
    }

    private static void assertWeak(String phrase, boolean lht, TurnIcon expected) {
        ManeuverText.Result r = ManeuverText.parse(lht, phrase);
        Assert.assertNotNull("No result for: " + phrase, r);
        Assert.assertEquals("Icon for: " + phrase, expected, r.icon);
        Assert.assertFalse("Expected weak for: " + phrase, r.exact);
    }
}
