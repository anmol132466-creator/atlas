// src/main/java/net/atlas/telemetry/listeners/ChatSurveillanceListener.java
package net.atlas.telemetry.listeners;

import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.atlas.telemetry.TelemetryEngine;
import net.atlas.websocket.AtlasWebSocketServer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.UUID;

public final class ChatSurveillanceListener implements Listener {

    private final AtlasWebSocketServer webSocketServer;
    private static final PlainTextComponentSerializer PLAIN_SERIALIZER = PlainTextComponentSerializer.plainText();
    private volatile long lastPaperChatTime = 0L;
    private volatile UUID lastPaperChatUuid = null;

    public ChatSurveillanceListener(AtlasWebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
    }

    /**
     * Primary modern Paper 1.21.1+ asynchronous chat event handler.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        if (webSocketServer == null) return;

        Player player = event.getPlayer();
        String message = PLAIN_SERIALIZER.serialize(event.message());
        UUID uuid = player.getUniqueId();

        lastPaperChatTime = System.currentTimeMillis();
        lastPaperChatUuid = uuid;

        dispatchChatMessage(player, message);
    }

    /**
     * Fallback legacy chat listener for plugins that dispatch AsyncPlayerChatEvent.
     * Includes deduplication so duplicate packets are never sent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (webSocketServer == null) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (uuid.equals(lastPaperChatUuid) && (now - lastPaperChatTime) < 500L) {
            return; // Handled by modern Paper AsyncChatEvent
        }

        dispatchChatMessage(player, event.getMessage());
    }

    private void dispatchChatMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) return;

        Location loc = player.getLocation();
        String dimension = TelemetryEngine.canonicalizeDimension(player.getWorld());

        JsonObject packet = new JsonObject();
        packet.addProperty("type", "CHAT_MESSAGE");
        packet.addProperty("uuid", player.getUniqueId().toString());
        packet.addProperty("player", player.getName());
        packet.addProperty("message", message.trim());
        packet.addProperty("dimension", dimension);
        packet.addProperty("x", Math.round(loc.getX() * 10.0) / 10.0);
        packet.addProperty("y", Math.round(loc.getY() * 10.0) / 10.0);
        packet.addProperty("z", Math.round(loc.getZ() * 10.0) / 10.0);
        packet.addProperty("timestamp", System.currentTimeMillis());

        webSocketServer.broadcastJson(packet.toString());
    }
}
