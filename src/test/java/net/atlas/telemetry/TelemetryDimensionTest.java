package net.atlas.telemetry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TelemetryDimensionTest {

    @Test
    public void testCanonicalizeDimensionName() {
        assertEquals("minecraft:the_nether", TelemetryEngine.canonicalizeDimensionName("world_nether"));
        assertEquals("minecraft:the_nether", TelemetryEngine.canonicalizeDimensionName("minecraft:world_nether"));
        assertEquals("minecraft:the_nether", TelemetryEngine.canonicalizeDimensionName("the_nether"));
        assertEquals("minecraft:the_nether", TelemetryEngine.canonicalizeDimensionName("minecraft:the_nether"));
        assertEquals("minecraft:the_nether", TelemetryEngine.canonicalizeDimensionName("DIM-1_nether"));

        assertEquals("minecraft:the_end", TelemetryEngine.canonicalizeDimensionName("world_the_end"));
        assertEquals("minecraft:the_end", TelemetryEngine.canonicalizeDimensionName("minecraft:world_the_end"));
        assertEquals("minecraft:the_end", TelemetryEngine.canonicalizeDimensionName("the_end"));
        assertEquals("minecraft:the_end", TelemetryEngine.canonicalizeDimensionName("end"));
        assertEquals("minecraft:the_end", TelemetryEngine.canonicalizeDimensionName("minecraft:the_end"));

        assertEquals("minecraft:overworld", TelemetryEngine.canonicalizeDimensionName("world"));
        assertEquals("minecraft:overworld", TelemetryEngine.canonicalizeDimensionName("minecraft:world"));
        assertEquals("minecraft:overworld", TelemetryEngine.canonicalizeDimensionName("minecraft:overworld"));
        assertEquals("minecraft:overworld", TelemetryEngine.canonicalizeDimensionName(null));
        assertEquals("minecraft:overworld", TelemetryEngine.canonicalizeDimensionName(""));
    }

    @Test
    public void testNormalizeDimension() {
        assertEquals("nether", TelemetryEngine.normalizeDimension("minecraft:the_nether"));
        assertEquals("nether", TelemetryEngine.normalizeDimension("minecraft:world_nether"));
        assertEquals("nether", TelemetryEngine.normalizeDimension("world_nether"));

        assertEquals("end", TelemetryEngine.normalizeDimension("minecraft:the_end"));
        assertEquals("end", TelemetryEngine.normalizeDimension("minecraft:world_the_end"));
        assertEquals("end", TelemetryEngine.normalizeDimension("world_the_end"));

        assertEquals("overworld", TelemetryEngine.normalizeDimension("minecraft:overworld"));
        assertEquals("overworld", TelemetryEngine.normalizeDimension("world"));
        assertEquals("overworld", TelemetryEngine.normalizeDimension(null));
    }

    @Test
    public void testIsSameDimensionCrossPlatform() {
        assertTrue(TelemetryEngine.isSameDimension("minecraft:the_nether", "world_nether"));
        assertTrue(TelemetryEngine.isSameDimension("world_nether", "minecraft:the_nether"));
        assertTrue(TelemetryEngine.isSameDimension("minecraft:world_nether", "minecraft:the_nether"));

        assertTrue(TelemetryEngine.isSameDimension("minecraft:the_end", "world_the_end"));
        assertTrue(TelemetryEngine.isSameDimension("minecraft:world_the_end", "minecraft:the_end"));

        assertTrue(TelemetryEngine.isSameDimension("minecraft:overworld", "world"));
        assertTrue(TelemetryEngine.isSameDimension("minecraft:world", "minecraft:overworld"));

        assertFalse(TelemetryEngine.isSameDimension("minecraft:the_nether", "minecraft:overworld"));
        assertFalse(TelemetryEngine.isSameDimension("minecraft:the_end", "minecraft:the_nether"));
        assertFalse(TelemetryEngine.isSameDimension("world_the_end", "world"));
    }

    @Test
    public void testPlayerTelemetryStateBothDimensionAndWorldPresent() {
        UUID uuid = UUID.randomUUID();
        PlayerTelemetryState state = new PlayerTelemetryState(
                uuid,
                "Explorer",
                128.5,
                72.0,
                -256.25,
                90.0f,
                15.0f,
                "minecraft:the_nether",
                20.0,
                20.0,
                false,
                false
        );

        JsonObject json = state.toJsonObject();
        assertTrue(json.has("dimension"));
        assertTrue(json.has("world"));
        assertEquals("minecraft:the_nether", json.get("dimension").getAsString());
        assertEquals("minecraft:the_nether", json.get("world").getAsString());
        assertEquals("Explorer", json.get("name").getAsString());
    }
}
