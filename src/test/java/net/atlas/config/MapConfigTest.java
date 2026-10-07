// src/test/java/net/atlas/config/MapConfigTest.java
package net.atlas.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MapConfigTest {

    @Test
    public void testWorldSpawnConfigCreation() {
        MapConfig.WorldSpawnConfig spawn = new MapConfig.WorldSpawnConfig(true, "minecraft:overworld", 100, 64, -200);
        assertTrue(spawn.enabled());
        assertEquals("minecraft:overworld", spawn.dimension());
        assertEquals(100, spawn.x());
        assertEquals(64, spawn.y());
        assertEquals(-200, spawn.z());
    }

    @Test
    public void testMapConfigWithSpawn() {
        MapConfig.WorldSpawnConfig spawn = new MapConfig.WorldSpawnConfig(false, "minecraft:overworld", 0, 64, 0);
        MapConfig mapConfig = new MapConfig(
                "minecraft:overworld",
                256,
                true,
                4,
                false,
                -1000, 1000, -1000, 1000,
                true,
                0.12,
                spawn
        );

        assertNotNull(mapConfig.worldSpawn());
        assertFalse(mapConfig.worldSpawn().enabled());
        assertTrue(mapConfig.isRenderUnloadedChunks());
        assertTrue(mapConfig.isWithinBoundingBox(500, 500));
        assertTrue(mapConfig.isTileWithinBoundingBox(1, 1));
    }
}
