// src/main/java/net/atlas/telemetry/PlayerTelemetryState.java
package net.atlas.telemetry;

import com.google.gson.JsonObject;

import java.util.UUID;

public record PlayerTelemetryState(
        UUID uuid,
        String name,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String dimension,
        double health,
        double maxHealth,
        boolean isSneaking,
        boolean isGliding
) {
    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid.toString());
        obj.addProperty("name", name);
        obj.addProperty("x", Math.round(x * 100.0) / 100.0);
        obj.addProperty("y", Math.round(y * 100.0) / 100.0);
        obj.addProperty("z", Math.round(z * 100.0) / 100.0);
        obj.addProperty("yaw", Math.round(yaw * 10.0f) / 10.0f);
        obj.addProperty("pitch", Math.round(pitch * 10.0f) / 10.0f);
        obj.addProperty("dimension", dimension);
        obj.addProperty("world", dimension);
        obj.addProperty("health", Math.round(health * 10.0) / 10.0);
        obj.addProperty("maxHealth", Math.round(maxHealth * 10.0) / 10.0);
        obj.addProperty("isSneaking", isSneaking);
        obj.addProperty("isGliding", isGliding);
        return obj;
    }
}
