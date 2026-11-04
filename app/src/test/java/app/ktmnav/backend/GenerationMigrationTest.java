package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;


/**
 * Unit tests for production generation normalization. Persistence, rollback and timeout
 * behavior run against the actual Android implementation in SmokeInstrumentation.
 */
public class GenerationMigrationTest {

    @Test
    public void testBikeGenerationResolutionAndNormalization() {
        // Canonical Gen3 mappings
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("GEN3"));
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("gen3"));
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("GEN1")); // Legacy label maps to Gen3
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("gen1"));
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from(null));   // Default is Gen3 per original TurnIt
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from(""));
        Assert.assertEquals(BikeGeneration.GEN3, BikeGeneration.from("UNKNOWN"));

        // Canonical Gen2 mappings
        Assert.assertEquals(BikeGeneration.GEN2, BikeGeneration.from("GEN2"));
        Assert.assertEquals(BikeGeneration.GEN2, BikeGeneration.from("gen2"));
        Assert.assertEquals(BikeGeneration.GEN2, BikeGeneration.from("  GEN2  "));

        // Normalization strings
        Assert.assertEquals("GEN3", BikeGeneration.normalize("GEN1"));
        Assert.assertEquals("GEN3", BikeGeneration.normalize("gen1"));
        Assert.assertEquals("GEN3", BikeGeneration.normalize(null));
        Assert.assertEquals("GEN3", BikeGeneration.normalize(""));
        Assert.assertEquals("GEN3", BikeGeneration.normalize("GEN3"));
        Assert.assertEquals("GEN2", BikeGeneration.normalize("GEN2"));
        Assert.assertEquals("GEN2", BikeGeneration.normalize("gen2"));
    }

    @Test
    public void testBridgeBikeGenerationNormalization() {
        Bridge.Bike legacyBike = new Bridge.Bike("02:00:00:4B:54:4D", "390 Duke", "GEN1");
        Assert.assertEquals("GEN3", legacyBike.generation);
        Assert.assertEquals(BikeGeneration.GEN3, legacyBike.getBikeGeneration());

        Bridge.Bike gen3Bike = new Bridge.Bike("02:00:00:4B:54:4E", "890 Adventure R", "GEN3");
        Assert.assertEquals("GEN3", gen3Bike.generation);
        Assert.assertEquals(BikeGeneration.GEN3, gen3Bike.getBikeGeneration());

        Bridge.Bike gen2Bike = new Bridge.Bike("02:00:00:4B:54:4F", "1290 Super Duke R", "GEN2");
        Assert.assertEquals("GEN2", gen2Bike.generation);
        Assert.assertEquals(BikeGeneration.GEN2, gen2Bike.getBikeGeneration());

        Bridge.Bike missingGen = new Bridge.Bike("02:00:00:4B:54:50", "Naked Bike", null);
        Assert.assertEquals("GEN3", missingGen.generation);
        Assert.assertEquals(BikeGeneration.GEN3, missingGen.getBikeGeneration());
    }

}
