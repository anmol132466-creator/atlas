// src/main/java/net/atlas/map/listeners/WorldSpawnListener.java
package net.atlas.map.listeners;

import com.google.gson.JsonObject;
import net.atlas.AtlasPlugin;
import net.atlas.websocket.AtlasWebSocketServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.SpawnChangeEvent;

public final class WorldSpawnListener implements Listener {

    private final AtlasPlugin plugin;
    private final AtlasWebSocketServer webSocketServer;

    public WorldSpawnListener(AtlasPlugin plugin, AtlasWebSocketServer webSocketServer) {
        this.plugin = plugin;
        this.webSocketServer = webSocketServer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnChange(SpawnChangeEvent event) {
        World world = event.getWorld();
        Location newSpawn = world.getSpawnLocation();

        JsonObject packet = new JsonObject();
        packet.addProperty("type", "WORLD_SPAWN_UPDATE");
        packet.addProperty("world", world.getName());
        packet.addProperty("dimension", world.getKey().asString());
        packet.addProperty("spawnX", newSpawn.getBlockX());
        packet.addProperty("spawnY", newSpawn.getBlockY());
        packet.addProperty("spawnZ", newSpawn.getBlockZ());

        if (webSocketServer != null) {
            webSocketServer.broadcastJson(packet.toString());
        }
        plugin.getLogger().info("World spawn dynamically updated in " + world.getName() + " to ("
                + newSpawn.getBlockX() + ", " + newSpawn.getBlockY() + ", " + newSpawn.getBlockZ() + "). Telemetry broadcast dispatched.");
    }
}
