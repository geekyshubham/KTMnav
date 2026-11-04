package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class WazeNavSourceTest {

    private static long at(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void turnWithDistanceAndMinutes() {
        NavUpdate u = WazeNavSource.parseTexts(Arrays.asList("In 300 m turn right onto MG Road", "12 min · 4.2 km"), 0, 0, at(10, 0));
        Assert.assertNotNull(u);
        Assert.assertEquals("300 m", u.getDistanceText());
        Assert.assertEquals("turn right onto MG Road", u.getRoadText());
        Assert.assertEquals(TurnIcon.QUITE_RIGHT, u.getTurnIcon());
        Assert.assertEquals("10:12", u.getEtaText());
    }

    @Test
    public void arrivalClockAndProgressRemaining() {
        NavUpdate u = WazeNavSource.parseTexts(Arrays.asList("Keep left", "Arrive 2:05 pm"), 1500, 9000, 0L);
        Assert.assertEquals(TurnIcon.KEEP_LEFT, u.getTurnIcon());
        Assert.assertEquals("14:05", u.getEtaText());
        Assert.assertEquals("7.5 km", u.getRemainingDistanceText());
        Assert.assertNull(u.getDistanceText());
    }

    @Test
    public void roundaboutExitBecomesRightHandSectorWithExitText() {
        NavUpdate u = WazeNavSource.parseTexts(Collections.singletonList("At the roundabout, take the 2nd exit onto Ring Road."), 0, 0, 0L);
        Assert.assertEquals(TurnIcon.RAB_SECT_8_RH, u.getTurnIcon());
        Assert.assertTrue(u.getRoadText(), u.getRoadText().contains("Ring Road"));
        Assert.assertTrue(u.getRoadText(), u.getRoadText().startsWith("2"));
    }

    @Test
    public void placeholdersWhenOnlyADistanceIsShown() {
        NavUpdate u = WazeNavSource.parseTexts(Arrays.asList("500 ft", "Waze"), 0, 0, 0L);
        Assert.assertEquals("500 ft", u.getDistanceText());
        Assert.assertEquals("Continue on route", u.getRoadText());
        Assert.assertNull(u.getTurnIcon());
        NavUpdate end = WazeNavSource.parseTexts(Collections.singletonList("Arriving at destination"), 0, 0, 0L);
        Assert.assertEquals(TurnIcon.END, end.getTurnIcon());
        Assert.assertEquals("Arriving at destination", end.getRoadText());
    }

    @Test
    public void uTurnsForksAndLanes() {
        Assert.assertEquals(TurnIcon.UTURN_LEFT, WazeNavSource.iconFor("make a u-turn"));
        Assert.assertEquals(TurnIcon.UTURN_RIGHT, WazeNavSource.iconFor("u-turn to the right"));
        Assert.assertEquals(TurnIcon.KEEP_RIGHT, WazeNavSource.iconFor("at the fork keep right"));
        Assert.assertEquals(TurnIcon.CHANGE_LINE, WazeNavSource.iconFor("merge onto nh 48"));
        Assert.assertEquals(TurnIcon.HEAVY_LEFT, WazeNavSource.iconFor("sharp left"));
        Assert.assertEquals(TurnIcon.GO_STRAIGHT, WazeNavSource.iconFor("continue straight"));
        Assert.assertNull(WazeNavSource.iconFor("mg road"));
    }

    @Test
    public void emptyAndUnusableInputs() {
        Assert.assertNull(WazeNavSource.parseTexts(null, 0, 0, 0L));
        Assert.assertNull(WazeNavSource.parseTexts(Collections.emptyList(), 0, 0, 0L));
        List<String> texts = Arrays.asList("1.2 km", "Waze");
        Assert.assertEquals("1.2 km", WazeNavSource.parseTexts(texts, 0, 0, 0L).getDistanceText());
        Assert.assertNull(WazeNavSource.remainingFor(10, 5));
        Assert.assertEquals("250 m", WazeNavSource.remainingFor(750, 1000));
    }
}
