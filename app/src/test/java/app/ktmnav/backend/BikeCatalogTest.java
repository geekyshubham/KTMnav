package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class BikeCatalogTest {

    @Test
    public void guessesStyleFromDashboardNames() {
        Assert.assertEquals(BikeCatalog.Style.NAKED, BikeCatalog.guess("KTM 390 DUKE"));
        Assert.assertEquals(BikeCatalog.Style.NAKED, BikeCatalog.guess("1290 Super Duke R"));
        Assert.assertEquals(BikeCatalog.Style.TOURING, BikeCatalog.guess("KTM 1290 Super Duke GT"));
        Assert.assertEquals(BikeCatalog.Style.TOURING, BikeCatalog.guess("890 SMT"));
        Assert.assertEquals(BikeCatalog.Style.ADVENTURE, BikeCatalog.guess("KTM_390_ADVENTURE"));
        Assert.assertEquals(BikeCatalog.Style.ADVENTURE, BikeCatalog.guess("Norden 901"));
        Assert.assertEquals(BikeCatalog.Style.SPORT, BikeCatalog.guess("RC 390"));
        Assert.assertEquals(BikeCatalog.Style.ENDURO, BikeCatalog.guess("690 Enduro R"));
        Assert.assertEquals(BikeCatalog.Style.ENDURO, BikeCatalog.guess("500 EXC-F"));
        Assert.assertEquals(BikeCatalog.Style.SUPERMOTO, BikeCatalog.guess("690 SMC R"));
        Assert.assertEquals(BikeCatalog.Style.SUPERMOTO, BikeCatalog.guess("Husqvarna 701 Supermoto"));
        Assert.assertEquals(BikeCatalog.Style.SCRAMBLER, BikeCatalog.guess("HSQ Svartpilen 401"));
        Assert.assertEquals(BikeCatalog.Style.CAFE, BikeCatalog.guess("Vitpilen 801"));
    }

    @Test
    public void unknownTextIsGeneric() {
        Assert.assertEquals(BikeCatalog.Style.GENERIC, BikeCatalog.guess(null));
        Assert.assertEquals(BikeCatalog.Style.GENERIC, BikeCatalog.guess(""));
        Assert.assertEquals(BikeCatalog.Style.GENERIC, BikeCatalog.guess("My Ride"));
        Assert.assertEquals(BikeCatalog.Style.GENERIC, BikeCatalog.guess("BCCU"));
        Assert.assertEquals(BikeCatalog.Style.GENERIC, BikeCatalog.Style.fromPreference("nonsense"));
        Assert.assertEquals(BikeCatalog.Style.ENDURO, BikeCatalog.Style.fromPreference("enduro"));
    }

    @Test
    public void catalogueLookupIsCaseInsensitiveAndComplete() {
        BikeCatalog.Model m = BikeCatalog.find("ktm 390 duke");
        Assert.assertNotNull(m);
        Assert.assertEquals("KTM", m.brand);
        Assert.assertEquals(BikeCatalog.Style.NAKED, m.style);
        Assert.assertNotNull(BikeCatalog.find("Svartpilen 401"));
        Assert.assertNull(BikeCatalog.find("Ducati Monster"));
        for (BikeCatalog.Model model : BikeCatalog.models()) {
            Assert.assertEquals(model.displayName(), model.style, BikeCatalog.guess(model.displayName()));
            Assert.assertNotEquals(model.displayName(), BikeCatalog.Style.GENERIC, model.style);
        }
    }
}
