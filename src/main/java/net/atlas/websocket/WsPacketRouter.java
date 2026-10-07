// src/main/java/net/atlas/websocket/WsPacketRouter.java
package net.atlas.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.atlas.auth.AdminSession;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;
import net.atlas.moderation.ModerationDispatcher;
import net.atlas.offline.OfflinePlayerService;
import net.atlas.offline.model.ActionStatus;
import net.atlas.offline.model.PendingAction;
import net.atlas.telemetry.LiveInspectorService;
import net.atlas.voice.SpyglassManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.java_websocket.WebSocket;

import java.util.UUID;
import java.util.logging.Level;

public final class WsPacketRouter {

    private final Plugin plugin;
    private final WsSessionManager sessionManager;
    private final TokenManager tokenManager;
    private final SessionRegistry sessionRegistry;
    private final ModerationDispatcher moderationDispatcher;
    private final LiveInspectorService inspectorService;
    private final OfflinePlayerService offlinePlayerService;
    private final SpyglassManager spyglassManager;
    private volatile net.atlas.voice.VoiceTransmitterService voiceTransmitterService;
    private volatile net.atlas.telemetry.TelemetryEngine telemetryEngine;

    public WsPacketRouter(Plugin plugin, WsSessionManager sessionManager, TokenManager tokenManager,
                          SessionRegistry sessionRegistry, ModerationDispatcher moderationDispatcher,
                          LiveInspectorService inspectorService, OfflinePlayerService offlinePlayerService,
                          SpyglassManager spyglassManager) {
        this(plugin, sessionManager, tokenManager, sessionRegistry, moderationDispatcher,
                inspectorService, offlinePlayerService, spyglassManager, null);
    }

    public WsPacketRouter(Plugin plugin, WsSessionManager sessionManager, TokenManager tokenManager,
                          SessionRegistry sessionRegistry, ModerationDispatcher moderationDispatcher,
                          LiveInspectorService inspectorService, OfflinePlayerService offlinePlayerService,
                          SpyglassManager spyglassManager,
                          net.atlas.voice.VoiceTransmitterService voiceTransmitterService) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.tokenManager = tokenManager;
        this.sessionRegistry = sessionRegistry;
        this.moderationDispatcher = moderationDispatcher;
        this.inspectorService = inspectorService;
        this.offlinePlayerService = offlinePlayerService;
        this.spyglassManager = spyglassManager;
        this.voiceTransmitterService = voiceTransmitterService;
    }

    public void setVoiceTransmitterService(net.atlas.voice.VoiceTransmitterService voiceTransmitterService) {
        this.voiceTransmitterService = voiceTransmitterService;
    }

    public void setTelemetryEngine(net.atlas.telemetry.TelemetryEngine telemetryEngine) {
        this.telemetryEngine = telemetryEngine;
    }

    public void handleBinaryMessage(WebSocket conn, java.nio.ByteBuffer blob) {
        if (conn == null || !conn.isOpen() || blob == null || blob.remaining() < 2) return;
        if (!sessionManager.isAuthenticated(conn)) return;

        byte magic = blob.get();
        if (magic == 0x0C) { // Voice Transmission Frame
            byte mode = blob.get(); // 0 = Broadcast, 1 = Target Player, 2 = Area
            UUID targetUuid = null;
            double areaX = 0, areaY = 64, areaZ = 0, areaRad = 50;
            if (mode == 1) {
                if (blob.remaining() < 16) return;
                long most = blob.getLong();
                long least = blob.getLong();
                targetUuid = new UUID(most, least);
            } else if (mode == 2) {
                if (blob.remaining() < 28) return;
                areaX = blob.getDouble();
                areaY = blob.getDouble();
                areaZ = blob.getDouble();
                areaRad = blob.getFloat();
            }
            byte isPcmByte = blob.get();
            boolean isPcm = (isPcmByte == 1);
            byte[] audio = new byte[blob.remaining()];
            blob.get(audio);

            if (voiceTransmitterService != null) {
                if (mode == 0) {
                    voiceTransmitterService.broadcastAudio(audio, isPcm);
                } else if (mode == 1 && targetUuid != null) {
                    voiceTransmitterService.transmitToPlayer(targetUuid, audio, isPcm);
                } else if (mode == 2) {
                    voiceTransmitterService.transmitToArea("minecraft:overworld", areaX, areaY, areaZ, areaRad, audio, isPcm);
                }
            }
        }
    }

    public void handleMessage(WebSocket conn, String message) {
        if (conn == null || !conn.isOpen()) return;
        if (message == null || message.isBlank()) {
            sendError(conn, "Empty packet received");
            return;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            sendError(conn, "Malformed JSON packet format");
            return;
        }

        String type = (root.has("type") && !root.get("type").isJsonNull()) ? root.get("type").getAsString().toUpperCase() : "";

        // Authentication Handshake
        if (type.equals("AUTH")) {
            handleAuth(conn, root);
            return;
        }

        // Enforce Authentication Barrier
        if (!sessionManager.isAuthenticated(conn)) {
            sendError(conn, "Unauthorized connection. Send AUTH frame first.");
            conn.close(4401, "Unauthorized");
            return;
        }

        AdminSession session = sessionManager.getSession(conn);
        String issuer = (session != null) ? session.getPrincipal() : "Admin";

        switch (type) {
            case "SUBSCRIBE_TELEMETRY" -> handleSubscribeTelemetry(conn, root);
            case "INSPECT_PLAYER" -> handleInspectPlayer(conn, root);
            case "INSPECT_OFFLINE_PLAYER" -> handleInspectOfflinePlayer(conn, root);
            case "FETCH_OFFLINE_ROSTER" -> handleFetchOfflineRoster(conn);
            case "EXECUTE_ACTION" -> handleExecuteAction(conn, root, issuer);
            case "EXECUTE_COMMAND" -> handleExecuteCommand(conn, root, issuer);
            case "QUEUE_OFFLINE_ACTION" -> handleQueueOfflineAction(conn, root, issuer);
            case "FETCH_PENDING_ACTIONS" -> handleFetchPendingActions(conn);
            case "CANCEL_PENDING_ACTION" -> handleCancelPendingAction(conn, root);
            case "VOICE_TRANSMIT" -> handleVoiceTransmit(conn, root);
            case "LISTEN_START" -> handleListenStart(conn, root);
            case "LISTEN_STOP" -> handleListenStop(conn);
            case "PING" -> handlePing(conn, root);
            case "FETCH_CONFIG" -> handleFetchConfig(conn);
            case "UPDATE_CONFIG" -> handleUpdateConfig(conn, root);
            default -> sendError(conn, "Unknown packet type: " + type);
        }
    }

    // --- Authentication ---

    private void handleAuth(WebSocket conn, JsonObject root) {
        String token = root.has("token") ? root.get("token").getAsString() : "";
        AdminSession session = sessionRegistry.getSession(token);

        if (session == null) {
            String principal = tokenManager.consumeToken(token);
            if (principal == null && tokenManager.validateToken(token)) {
                principal = "MASTER";
            }
            if (principal != null) {
                session = sessionRegistry.createSession(principal);
            }
        }

        if (session != null) {
            sessionManager.registerAuthenticated(conn, session);
            JsonObject authSuccess = new JsonObject();
            authSuccess.addProperty("type", "AUTH_SUCCESS");
            authSuccess.addProperty("serverVersion", Bukkit.getVersion());
            authSuccess.addProperty("serverTps", 20.0);
            authSuccess.addProperty("voiceHooked", plugin.getConfig().getBoolean("voice.enabled", true));
            authSuccess.addProperty("sessionId", session.getSessionId());

            org.bukkit.World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (mainWorld != null) {
                net.atlas.AtlasPlugin atlas = net.atlas.AtlasPlugin.getInstance();
                net.atlas.config.MapConfig.WorldSpawnConfig cfgSpawn = (atlas != null && atlas.getAtlasConfig() != null) ? atlas.getAtlasConfig().map().worldSpawn() : null;
                int spawnX = (cfgSpawn != null && cfgSpawn.enabled()) ? cfgSpawn.x() : mainWorld.getSpawnLocation().getBlockX();
                int spawnY = (cfgSpawn != null && cfgSpawn.enabled()) ? cfgSpawn.y() : mainWorld.getSpawnLocation().getBlockY();
                int spawnZ = (cfgSpawn != null && cfgSpawn.enabled()) ? cfgSpawn.z() : mainWorld.getSpawnLocation().getBlockZ();

                authSuccess.addProperty("spawnX", spawnX);
                authSuccess.addProperty("spawnY", spawnY);
                authSuccess.addProperty("spawnZ", spawnZ);
                authSuccess.addProperty("dimension", mainWorld.getKey().asString());
                authSuccess.addProperty("seed", String.valueOf(mainWorld.getSeed()));
                String mcVer = "Unknown";
                try {
                    mcVer = Bukkit.getMinecraftVersion();
                } catch (Throwable t) {
                    mcVer = Bukkit.getVersion();
                }
                authSuccess.addProperty("minecraftVersion", mcVer);
            }
            sendSafe(conn, authSuccess.toString());

            // Immediately push online player telemetry snapshot
            if (telemetryEngine != null) {
                String initialDim = (mainWorld != null) ? mainWorld.getKey().asString() : "minecraft:overworld";
                JsonObject batch = new JsonObject();
                batch.addProperty("type", "TELEMETRY_BATCH");
                batch.addProperty("timestamp", System.currentTimeMillis());
                batch.add("players", telemetryEngine.getAllOnlinePlayersJson(initialDim));
                sendSafe(conn, batch.toString());
            }
        } else {
            JsonObject authFail = new JsonObject();
            authFail.addProperty("type", "AUTH_FAILURE");
            authFail.addProperty("reason", "Invalid or expired token");
            sendSafe(conn, authFail.toString());
            conn.close(4401, "Authentication failed");
        }
    }

    // --- Telemetry ---

    private void handleSubscribeTelemetry(WebSocket conn, JsonObject root) {
        String dim = root.has("dimension") ? root.get("dimension").getAsString() : "minecraft:overworld";
        sessionManager.setTelemetrySubscription(conn, dim);

        if (telemetryEngine != null && conn.isOpen()) {
            JsonObject batch = new JsonObject();
            batch.addProperty("type", "TELEMETRY_BATCH");
            batch.addProperty("timestamp", System.currentTimeMillis());
            batch.add("players", telemetryEngine.getAllOnlinePlayersJson(dim));
            sendSafe(conn, batch.toString());
        }
    }

    // --- Player Inspection ---

    private void handleInspectPlayer(WebSocket conn, JsonObject root) {
        if (!root.has("uuid")) {
            sendError(conn, "INSPECT_PLAYER requires 'uuid' field");
            return;
        }
        UUID uuid = parseUuidSafe(root.get("uuid").getAsString());
        if (uuid == null) {
            sendError(conn, "Invalid UUID format in INSPECT_PLAYER");
            return;
        }
        inspectorService.inspectPlayerAsync(uuid).thenAccept(data -> {
            if (data != null && conn.isOpen()) {
                sendSafe(conn, data.toString());
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Error inspecting player " + uuid, ex);
            sendError(conn, "Failed to inspect player: " + ex.getMessage());
            return null;
        });
    }

    private void handleInspectOfflinePlayer(WebSocket conn, JsonObject root) {
        if (!root.has("uuid")) {
            sendError(conn, "INSPECT_OFFLINE_PLAYER requires 'uuid' field");
            return;
        }
        UUID uuid = parseUuidSafe(root.get("uuid").getAsString());
        if (uuid == null) {
            sendError(conn, "Invalid UUID format in INSPECT_OFFLINE_PLAYER");
            return;
        }
        offlinePlayerService.loadOfflinePlayerDataAsync(uuid).thenAccept(data -> {
            if (conn.isOpen()) {
                JsonObject resp = new JsonObject();
                resp.addProperty("type", "OFFLINE_INSPECT_DATA");
                resp.add("profile", data.toJsonObject());
                sendSafe(conn, resp.toString());
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Error loading offline player data for " + uuid, ex);
            sendError(conn, "Failed to load offline player data: " + ex.getMessage());
            return null;
        });
    }

    private void handleFetchOfflineRoster(WebSocket conn) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (JsonObject p : offlinePlayerService.getRecentOfflineRoster()) {
            array.add(p);
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "OFFLINE_ROSTER");
        resp.add("players", array);
        sendSafe(conn, resp.toString());
    }

    // --- Moderation ---

    private void handleExecuteAction(WebSocket conn, JsonObject root, String issuer) {
        moderationDispatcher.executeAction(root, issuer).thenAccept(ack -> {
            sendSafe(conn, ack.toString());
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Error executing moderation action", ex);
            sendError(conn, "Failed to execute action: " + ex.getMessage());
            return null;
        });
    }

    private void handleExecuteCommand(WebSocket conn, JsonObject root, String issuer) {
        JsonObject cmdPayload = new JsonObject();
        cmdPayload.addProperty("action", "COMMAND");
        if (root.has("command")) cmdPayload.add("command", root.get("command"));
        if (root.has("target")) cmdPayload.add("target", root.get("target"));
        if (root.has("targetName")) cmdPayload.add("targetName", root.get("targetName"));
        if (root.has("queueIfOffline")) cmdPayload.add("queueIfOffline", root.get("queueIfOffline"));

        moderationDispatcher.executeAction(cmdPayload, issuer).thenAccept(ack -> {
            JsonObject response = new JsonObject();
            response.addProperty("type", "COMMAND_RESULT");
            response.add("result", ack);
            sendSafe(conn, response.toString());

            JsonObject actionAck = new JsonObject();
            actionAck.addProperty("type", "ACTION_ACK");
            actionAck.addProperty("action", "COMMAND");
            actionAck.addProperty("success", ack.has("success") && ack.get("success").getAsBoolean());
            if (ack.has("offlineQueued")) actionAck.addProperty("offlineQueued", ack.get("offlineQueued").getAsBoolean());
            if (ack.has("message")) actionAck.addProperty("message", ack.get("message").getAsString());
            if (ack.has("target")) actionAck.addProperty("target", ack.get("target").getAsString());
            sendSafe(conn, actionAck.toString());
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Error executing console command", ex);
            sendError(conn, "Failed to execute console command: " + ex.getMessage());
            return null;
        });
    }

    private void handleVoiceTransmit(WebSocket conn, JsonObject root) {
        if (voiceTransmitterService == null) {
            sendError(conn, "Voice transmission module is not attached or Simple Voice Chat is disabled.");
            return;
        }

        String mode = root.has("mode") ? root.get("mode").getAsString().toUpperCase() : "BROADCAST";
        boolean isPcm = !root.has("format") || "PCM".equalsIgnoreCase(root.get("format").getAsString());
        if (!root.has("data")) {
            sendError(conn, "Missing audio payload in VOICE_TRANSMIT frame");
            return;
        }

        try {
            byte[] audio = java.util.Base64.getDecoder().decode(root.get("data").getAsString());
            if ("BROADCAST".equals(mode)) {
                voiceTransmitterService.broadcastAudio(audio, isPcm);
            } else if ("PLAYER".equals(mode) && root.has("targetUuid")) {
                UUID targetUuid = parseUuidSafe(root.get("targetUuid").getAsString());
                if (targetUuid != null) {
                    voiceTransmitterService.transmitToPlayer(targetUuid, audio, isPcm);
                }
            } else if ("AREA".equals(mode)) {
                String dim = root.has("dimension") ? root.get("dimension").getAsString() : "minecraft:overworld";
                double x = root.has("x") ? root.get("x").getAsDouble() : 0.0;
                double y = root.has("y") ? root.get("y").getAsDouble() : 64.0;
                double z = root.has("z") ? root.get("z").getAsDouble() : 0.0;
                double radius = root.has("radius") ? root.get("radius").getAsDouble() : 50.0;
                voiceTransmitterService.transmitToArea(dim, x, y, z, radius, audio, isPcm);
            }
        } catch (Exception e) {
            sendError(conn, "Failed to process voice transmission packet: " + e.getMessage());
        }
    }

    private void handleQueueOfflineAction(WebSocket conn, JsonObject root, String issuer) {
        if (!root.has("targetUuid") || !root.has("action")) {
            sendError(conn, "QUEUE_OFFLINE_ACTION requires 'targetUuid' and 'action' fields");
            return;
        }

        UUID targetUuid = parseUuidSafe(root.get("targetUuid").getAsString());
        if (targetUuid == null) {
            sendError(conn, "Invalid UUID format in QUEUE_OFFLINE_ACTION");
            return;
        }

        String actionType = root.get("action").getAsString();
        if (actionType == null || actionType.isBlank()) {
            sendError(conn, "Action type cannot be empty in QUEUE_OFFLINE_ACTION");
            return;
        }

        String targetName = null;
        if (root.has("targetName")) {
            targetName = root.get("targetName").getAsString();
        } else {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetUuid);
            if (op != null && op.getName() != null) targetName = op.getName();
        }

        JsonObject params = root.has("parameters") ? root.getAsJsonObject("parameters") : new JsonObject();
        String actionId = "act_" + Long.toHexString(System.currentTimeMillis()) + "_" +
                Integer.toHexString(targetUuid.hashCode() & 0xFFFF);

        PendingAction action = new PendingAction(
                actionId,
                targetUuid,
                targetName,
                actionType,
                params,
                issuer,
                System.currentTimeMillis(),
                ActionStatus.PENDING
        );

        net.atlas.AtlasPlugin.getInstance().getPendingActionLedger().queueAction(action);
        net.atlas.AtlasPlugin.getInstance().broadcastPendingActions();

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ACTION_ACK");
        ack.addProperty("action", "QUEUE_OFFLINE_ACTION");
        ack.addProperty("success", true);
        ack.addProperty("actionId", actionId);
        ack.addProperty("message", "Queued offline action " + actionType + " for player " + (targetName != null ? targetName : targetUuid));
        sendSafe(conn, ack.toString());
    }

    private void handleFetchPendingActions(WebSocket conn) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (net.atlas.offline.model.PendingAction act : net.atlas.AtlasPlugin.getInstance().getPendingActionLedger().getAllPendingActions()) {
            array.add(act.toJsonObject());
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "PENDING_ACTIONS_LIST");
        resp.add("actions", array);
        sendSafe(conn, resp.toString());
    }

    private void handleCancelPendingAction(WebSocket conn, JsonObject root) {
        if (!root.has("actionId")) {
            sendError(conn, "CANCEL_PENDING_ACTION requires 'actionId' field");
            return;
        }
        String actionId = root.get("actionId").getAsString();
        boolean cancelled = net.atlas.AtlasPlugin.getInstance().getPendingActionLedger().cancelAction(actionId);
        if (cancelled) {
            net.atlas.AtlasPlugin.getInstance().broadcastPendingActions();
        }
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ACTION_ACK");
        ack.addProperty("action", "CANCEL_PENDING_ACTION");
        ack.addProperty("success", cancelled);
        ack.addProperty("message", cancelled ? "Cancelled pending action " + actionId : "Action not found or already executed");
        sendSafe(conn, ack.toString());
    }

    // --- Spyglass / Voice ---

    private void handleListenStart(WebSocket conn, JsonObject root) {
        double x = root.has("x") ? root.get("x").getAsDouble() : 0.0;
        double y = root.has("y") ? root.get("y").getAsDouble() : 64.0;
        double z = root.has("z") ? root.get("z").getAsDouble() : 0.0;
        double radius = root.has("radius") ? root.get("radius").getAsDouble() : 25.0;
        String dimension = root.has("dimension") ? root.get("dimension").getAsString() : "minecraft:overworld";

        String subscriberId = (conn.getRemoteSocketAddress() != null)
                ? conn.getRemoteSocketAddress().toString()
                : "sub-" + conn.hashCode();
        conn.setAttachment(subscriberId);
        spyglassManager.registerSubscription(subscriberId, dimension, x, y, z, radius, buffer -> {
            sessionManager.sendBinary(conn, buffer);
        });

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "SPYGLASS_STATUS");
        ack.addProperty("active", true);
        ack.addProperty("dimension", dimension);
        ack.addProperty("x", x);
        ack.addProperty("y", y);
        ack.addProperty("z", z);
        ack.addProperty("radius", radius);
        sendSafe(conn, ack.toString());
    }

    private void handleListenStop(WebSocket conn) {
        String subscriberId = conn.getAttachment();
        if (subscriberId == null && conn.getRemoteSocketAddress() != null) {
            subscriberId = conn.getRemoteSocketAddress().toString();
        }
        if (subscriberId != null) {
            spyglassManager.removeSubscription(subscriberId);
        }

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "SPYGLASS_STATUS");
        ack.addProperty("active", false);
        sendSafe(conn, ack.toString());
    }

    // --- Utility ---

    private void handlePing(WebSocket conn, JsonObject root) {
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "PONG");
        pong.addProperty("clientTime", root.has("time") ? root.get("time").getAsLong() : 0L);
        pong.addProperty("serverTime", System.currentTimeMillis());
        sendSafe(conn, pong.toString());
    }

    private void handleFetchConfig(WebSocket conn) {
        JsonObject configResp = new JsonObject();
        configResp.addProperty("type", "CONFIG_DATA");
        configResp.addProperty("renderUnloaded", plugin.getConfig().getBoolean("map.render-unloaded-chunks", true));
        configResp.addProperty("hillshadingIntensity", plugin.getConfig().getDouble("map.hillshading-intensity", 0.12));
        configResp.addProperty("telemetryHz", plugin.getConfig().getInt("telemetry.broadcast-frequency-hz", 15));
        configResp.addProperty("maxSpyglassRadius", plugin.getConfig().getDouble("voice.max-spyglass-radius", 100.0));
        sendSafe(conn, configResp.toString());
    }

    private void handleUpdateConfig(WebSocket conn, JsonObject root) {
        if (root.has("renderUnloaded")) {
            plugin.getConfig().set("map.render-unloaded-chunks", root.get("renderUnloaded").getAsBoolean());
        }
        if (root.has("hillshadingIntensity")) {
            double intensity = root.get("hillshadingIntensity").getAsDouble();
            // Clamp to valid range
            intensity = Math.max(0.0, Math.min(1.0, intensity));
            plugin.getConfig().set("map.hillshading-intensity", intensity);
        }
        if (root.has("telemetryHz")) {
            int hz = root.get("telemetryHz").getAsInt();
            // Clamp to sane range (1-60 Hz)
            hz = Math.max(1, Math.min(60, hz));
            plugin.getConfig().set("telemetry.broadcast-frequency-hz", hz);
        }
        plugin.saveConfig();

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "CONFIG_UPDATED");
        ack.addProperty("success", true);
        ack.addProperty("message", "Server configurations updated successfully.");
        sendSafe(conn, ack.toString());
    }

    // --- Helpers ---

    /**
     * Safely parses a UUID string without throwing.
     * Returns null for any malformed input.
     */
    private UUID parseUuidSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Sends a text message only if the connection is open.
     * Catches and logs any transmission errors.
     */
    private void sendSafe(WebSocket conn, String message) {
        try {
            if (conn != null && conn.isOpen()) {
                conn.send(message);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Failed to send WebSocket message", e);
        }
    }

    private void sendError(WebSocket conn, String errorMsg) {
        JsonObject err = new JsonObject();
        err.addProperty("type", "ERROR");
        err.addProperty("message", errorMsg);
        sendSafe(conn, err.toString());
    }
}
