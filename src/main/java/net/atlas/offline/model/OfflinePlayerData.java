// src/main/java/net/atlas/offline/model/OfflinePlayerData.java
package net.atlas.offline.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

public record OfflinePlayerData(
        UUID uuid,
        String lastKnownName,
        long lastSeen,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long playtimeTicks,
        JsonArray inventory,
        JsonArray enderChest
) {
    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid.toString());
        obj.addProperty("name", lastKnownName);
        obj.addProperty("lastSeen", lastSeen);

        JsonObject loc = new JsonObject();
        loc.addProperty("world", worldName);
        loc.addProperty("x", Math.round(x * 100.0) / 100.0);
        loc.addProperty("y", Math.round(y * 100.0) / 100.0);
        loc.addProperty("z", Math.round(z * 100.0) / 100.0);
        loc.addProperty("yaw", Math.round(yaw * 10.0f) / 10.0f);
        loc.addProperty("pitch", Math.round(pitch * 10.0f) / 10.0f);
        obj.add("location", loc);

        obj.addProperty("world", worldName);
        obj.addProperty("x", Math.round(x * 100.0) / 100.0);
        obj.addProperty("y", Math.round(y * 100.0) / 100.0);
        obj.addProperty("z", Math.round(z * 100.0) / 100.0);
        obj.addProperty("yaw", Math.round(yaw * 10.0f) / 10.0f);
        obj.addProperty("pitch", Math.round(pitch * 10.0f) / 10.0f);

        obj.addProperty("playtimeTicks", playtimeTicks);
        obj.addProperty("playtimeHours", Math.round((playtimeTicks / 72000.0) * 10.0) / 10.0);
        obj.add("inventory", inventory != null ? inventory : new JsonArray());
        obj.add("enderChest", enderChest != null ? enderChest : new JsonArray());

        return obj;
    }
}
