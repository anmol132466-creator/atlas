// src/test/java/net/atlas/map/SeedBiomeGeneratorTest.java
package net.atlas.map;

import net.atlas.map.render.SeedBiomeGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SeedBiomeGeneratorTest {

    @Test
    public void testOverworldDeterministicGeneration() {
        SeedBiomeGenerator generator = new SeedBiomeGenerator();
        long seed = -123456789012345L;

        int[][] heights1 = new int[256][256];
        int[][] colors1 = new int[256][256];
        generator.generateBaseTile(seed, "minecraft:overworld", 0, 0, heights1, colors1);

        int[][] heights2 = new int[256][256];
        int[][] colors2 = new int[256][256];
        generator.generateBaseTile(seed, "minecraft:overworld", 0, 0, heights2, colors2);

        // Deterministic check
        for (int x = 0; x < 256; x += 16) {
            for (int z = 0; z < 256; z += 16) {
                assertEquals(heights1[x][z], heights2[x][z], "Heights must be identical for identical seed");
                assertEquals(colors1[x][z], colors2[x][z], "Colors must be identical for identical seed");
                assertTrue(heights1[x][z] >= 1 && heights1[x][z] <= 255, "Height must be within Minecraft world bounds");
                assertEquals(0xFF, (colors1[x][z] >> 24) & 0xFF, "Surface color must be fully opaque");
            }
        }
    }

    @Test
    public void testDifferentSeedsProduceDifferentTiles() {
        SeedBiomeGenerator generator = new SeedBiomeGenerator();
        int[][] heightsA = new int[256][256];
        int[][] colorsA = new int[256][256];
        generator.generateBaseTile(11111111L, "minecraft:overworld", 1, 1, heightsA, colorsA);

        int[][] heightsB = new int[256][256];
        int[][] colorsB = new int[256][256];
        generator.generateBaseTile(99999999L, "minecraft:overworld", 1, 1, heightsB, colorsB);

        boolean foundDifference = false;
        for (int x = 0; x < 256; x += 32) {
            for (int z = 0; z < 256; z += 32) {
                if (colorsA[x][z] != colorsB[x][z]) {
                    foundDifference = true;
                    break;
                }
            }
        }
        assertTrue(foundDifference, "Different seeds must produce different biome maps");
    }

    @Test
    public void testNetherGeneration() {
        SeedBiomeGenerator generator = new SeedBiomeGenerator();
        int[][] heights = new int[256][256];
        int[][] colors = new int[256][256];
        generator.generateBaseTile(42L, "minecraft:the_nether", 0, 0, heights, colors);

        for (int x = 0; x < 256; x += 32) {
            for (int z = 0; z < 256; z += 32) {
                assertTrue(heights[x][z] >= 31, "Nether terrain must be at or above lava sea level (31)");
                assertEquals(0xFF, (colors[x][z] >> 24) & 0xFF);
            }
        }
    }

    @Test
    public void testEndIslandGeneration() {
        SeedBiomeGenerator generator = new SeedBiomeGenerator();
        int[][] heights = new int[256][256];
        int[][] colors = new int[256][256];
        generator.generateBaseTile(777L, "minecraft:the_end", 0, 0, heights, colors);

        // Near center (0,0), main end island should exist
        assertTrue(heights[128][128] > 0, "Central End island should have land near center (0,0)");
    }

    @Test
    public void testSeaLevelConsistency() {
        SeedBiomeGenerator generator = new SeedBiomeGenerator();
        int[][] heights = new int[256][256];
        int[][] colors = new int[256][256];
        // Tile with significant ocean
        generator.generateBaseTile(12345L, "minecraft:overworld", 10, 10, heights, colors);

        for (int x = 0; x < 256; x += 16) {
            for (int z = 0; z < 256; z += 16) {
                // If it's at or below sea level, it must be exactly sea level (62) for ocean/river surfaces
                if (heights[x][z] <= SeedBiomeGenerator.SEA_LEVEL) {
                    assertEquals(SeedBiomeGenerator.SEA_LEVEL, heights[x][z], "Ocean and river surface water must be exactly sea level 62");
                }
            }
        }
    }
}
