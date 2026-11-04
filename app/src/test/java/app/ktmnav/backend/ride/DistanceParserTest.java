package app.ktmnav.backend.ride;

import org.junit.Assert;
import org.junit.Test;

public class DistanceParserTest {

    @Test
    public void parsesMetricDistances() {
        Assert.assertEquals(350, DistanceParser.metres("350 m"));
        Assert.assertEquals(200, DistanceParser.metres("0.2 km"));
        Assert.assertEquals(1200, DistanceParser.metres("1.2 km"));
        Assert.assertEquals(350, DistanceParser.metres("In 350 m, turn left"));
        Assert.assertEquals(80, DistanceParser.metres("80m"));
        Assert.assertEquals(250, DistanceParser.metres("250 m"));
    }

    @Test
    public void parsesImperialDistances() {
        Assert.assertEquals(152, DistanceParser.metres("500 ft"));
        Assert.assertEquals(483, DistanceParser.metres("0.3 mi"));
        Assert.assertEquals(274, DistanceParser.metres("300 yd"));
        Assert.assertEquals(366, DistanceParser.metres("1,200 ft"));
    }

    @Test
    public void parsesLocaleCommas() {
        Assert.assertEquals(200, DistanceParser.metres("0,2 km"));
        Assert.assertEquals(1500, DistanceParser.metres("1,5 km"));
        Assert.assertEquals(1500, DistanceParser.metres("1.500 m"));
        Assert.assertEquals(2500, DistanceParser.metres("2.500,0 m"));
        Assert.assertEquals(300, DistanceParser.metres("300 м"));
    }

    @Test
    public void rejectsNonDistances() {
        Assert.assertEquals(DistanceParser.UNKNOWN, DistanceParser.metres(null));
        Assert.assertEquals(DistanceParser.UNKNOWN, DistanceParser.metres(""));
        Assert.assertEquals(DistanceParser.UNKNOWN, DistanceParser.metres("Main Street"));
        Assert.assertEquals(DistanceParser.UNKNOWN, DistanceParser.metres("5 min"));
        Assert.assertEquals(DistanceParser.UNKNOWN, DistanceParser.metres("12:45"));
    }
}
