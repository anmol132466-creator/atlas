// src/main/java/net/atlas/telemetry/TelemetryEngine.java
package net.atlas.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.atlas.config.TelemetryConfig;
import net.atlas.websocket.AtlasWebSocketServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class TelemetryEngine {

    private final Plugin plugin;
    private final TelemetryConfig config;
    private final AtlasWebSocketServer webSocketServer;

    private final Map<UUID, PlayerTelemetryState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> dirtyFlags = new ConcurrentHashMap<>();
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private int bukkitTaskId = -1;

    public TelemetryEngine(Plugin plugin, TelemetryConfig config, AtlasWebSocketServer webSocketServer) {
        this.plugin = plugin;
        this.config = config;
        this.webSocketServer = webSocketServer;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    /**
     * Starts the telemetry broadcast loop using Bukkit's synchronous scheduler
     * to ensure all player API calls happen on the main server thread.
     */
    public void start() {
        if (bukkitTaskId != -1) {
            return;
        }

        long intervalTicks = Math.max(1L, config.getIntervalMillis() / 50L);
        bukkitTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickAndBroadcast, intervalTicks, intervalTicks);

        if (bukkitTaskId == -1) {
            plugin.getLogger().severe("Failed to schedule telemetry broadcast task!");
        }
    }

    public void stop() {
        if (bukkitTaskId != -1) {
            Bukkit.getScheduler().cancelTask(bukkitTaskId);
            bukkitTaskId = -1;
        }
        playerStates.clear();
        dirtyFlags.clear();
    }

    /**
     * Updates a player's telemetry state. MUST be called from the main server thread.
     */
    public void updatePlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        var healthAttr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = healthAttr != null ? healthAttr.getValue() : 20.0;

        PlayerTelemetryState state = new PlayerTelemetryState(
                uuid,
                player.getName(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                player.getLocation().getPitch(),
                canonicalizeDimension(player.getWorld()),
                player.getHealth(),
                maxHealth,
                player.isSneaking(),
                player.isGliding()
        );

        playerStates.put(uuid, state);
        dirtyFlags.put(uuid, Boolean.TRUE);
    }

    public void removePlayer(UUID uuid) {
        playerStates.remove(uuid);
        dirtyFlags.remove(uuid);

        if (webSocketServer != null) {
            JsonObject quitPayload = new JsonObject();
            quitPayload.addProperty("type", "PLAYER_QUIT");
            quitPayload.addProperty("uuid", uuid.toString());
            webSocketServer.broadcastJson(quitPayload.toString());
        }
    }

    /**
     * Forces a sync of all online players. MUST be called from the main server thread.
     */
    public void syncAllOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                updatePlayer(p);
            }
        }
    }

    /**
     * Returns a JSON array of all online players' telemetry state for the given dimension.
     * Reads from cached state only (no Bukkit API calls), so safe from any thread.
     */
    public JsonArray getAllOnlinePlayersJson(String dimension) {
        JsonArray batch = new JsonArray();
        for (PlayerTelemetryState state : playerStates.values()) {
            if (dimension == null || dimension.isBlank() || isSameDimension(state.dimension(), dimension)) {
                batch.add(state.toJsonObject());
            }
        }
        return batch;
    }

    public static boolean isSameDimension(String w1, String w2) {
        if (w1 == null || w2 == null) return true;
        return normalizeDimension(w1).equals(normalizeDimension(w2));
    }

    public static String canonicalizeDimension(org.bukkit.World world) {
        if (world == null) return "minecraft:overworld";
        if (world.getEnvironment() == org.bukkit.World.Environment.NETHER || world.getName().toLowerCase().contains("nether")) {
            return "minecraft:the_nether";
        }
        if (world.getEnvironment() == org.bukkit.World.Environment.THE_END || world.getName().toLowerCase().contains("end")) {
            return "minecraft:the_end";
        }
        String key = world.getKey().asString();
        return canonicalizeDimensionName(key);
    }

    public static String canonicalizeDimensionName(String name) {
        if (name == null || name.isBlank()) return "minecraft:overworld";
        String clean = name.toLowerCase().trim();
        if (clean.contains("nether")) return "minecraft:the_nether";
        if (clean.contains("end")) return "minecraft:the_end";
        if (clean.equals("world") || clean.equals("minecraft:world") || clean.equals("minecraft:overworld")) {
            return "minecraft:overworld";
        }
        if (!clean.contains(":")) {
            return "minecraft:" + clean;
        }
        return clean;
    }

    public static String normalizeDimension(String dim) {
        if (dim == null || dim.isBlank()) return "overworld";
        String d = dim.toLowerCase().replace("minecraft:", "").replace("the_", "");
        if (d.contains("nether")) return "nether";
        if (d.contains("end")) return "end";
        return "overworld";
    }

    /**
     * Runs on the main server thread via Bukkit's sync scheduler.
     * Collects telemetry from online players and broadcasts to WebSocket clients.
     */
    private void tickAndBroadcast() {
        if (webSocketServer == null) {
            return;
        }

        try {
            // Refresh telemetry for all online players (safe: runs on main thread)
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != null && p.isOnline()) {
                    updatePlayer(p);
                }
            }

            // Prune states for players who are no longer online
            playerStates.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);

            if (playerStates.isEmpty()) {
                return;
            }

            JsonArray batch = new JsonArray();
            int count = 0;
            int maxBatch = config.maxBatchSize();
            boolean fullSync = (tickCounter.incrementAndGet() % 15 == 0);

            if (fullSync) {
                for (PlayerTelemetryState state : playerStates.values()) {
                    batch.add(state.toJsonObject());
                    dirtyFlags.put(state.uuid(), Boolean.FALSE);
                    count++;
                    if (count >= maxBatch) {
                        break;
                    }
                }
            } else {
                for (Map.Entry<UUID, Boolean> entry : dirtyFlags.entrySet()) {
                    if (Boolean.TRUE.equals(entry.getValue())) {
                        UUID uuid = entry.getKey();
                        PlayerTelemetryState state = playerStates.get(uuid);
                        if (state != null) {
                            batch.add(state.toJsonObject());
                            dirtyFlags.put(uuid, Boolean.FALSE);
                            count++;
                            if (count >= maxBatch) {
                                break;
                            }
                        }
                    }
                }
            }

            if (!batch.isEmpty()) {
                JsonObject root = new JsonObject();
                root.addProperty("type", "TELEMETRY_BATCH");
                root.addProperty("timestamp", System.currentTimeMillis());
                root.add("players", batch);

                webSocketServer.broadcastJson(root.toString());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error in telemetry broadcast tick", e);
        }
    }
}
