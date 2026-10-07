// src/main/java/net/atlas/websocket/AtlasWebSocketServer.java
package net.atlas.websocket;

import net.atlas.auth.AdminSession;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;
import net.atlas.moderation.ModerationDispatcher;
import net.atlas.offline.OfflinePlayerService;
import net.atlas.telemetry.LiveInspectorService;
import net.atlas.voice.SpyglassManager;
import org.bukkit.plugin.Plugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.logging.Level;

public final class AtlasWebSocketServer extends WebSocketServer {

    private final Plugin plugin;
    private final TokenManager tokenManager;
    private final SessionRegistry sessionRegistry;
    private final WsSessionManager sessionManager;
    private final WsPacketRouter packetRouter;
    private final SpyglassManager spyglassManager;

    public AtlasWebSocketServer(Plugin plugin, String host, int port,
                                TokenManager tokenManager, SessionRegistry sessionRegistry,
                                ModerationDispatcher moderationDispatcher, LiveInspectorService inspectorService,
                                OfflinePlayerService offlinePlayerService, SpyglassManager spyglassManager) {
        this(plugin, host, port, tokenManager, sessionRegistry, moderationDispatcher,
                inspectorService, offlinePlayerService, spyglassManager, null);
    }

    public AtlasWebSocketServer(Plugin plugin, String host, int port,
                                TokenManager tokenManager, SessionRegistry sessionRegistry,
                                ModerationDispatcher moderationDispatcher, LiveInspectorService inspectorService,
                                OfflinePlayerService offlinePlayerService, SpyglassManager spyglassManager,
                                net.atlas.voice.VoiceTransmitterService voiceTransmitterService) {
        super(new InetSocketAddress(host, port));
        this.plugin = plugin;
        this.tokenManager = tokenManager;
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = new WsSessionManager();
        this.spyglassManager = spyglassManager;

        this.packetRouter = new WsPacketRouter(
                plugin,
                sessionManager,
                tokenManager,
                sessionRegistry,
                moderationDispatcher,
                inspectorService,
                offlinePlayerService,
                spyglassManager,
                voiceTransmitterService
        );

        setReuseAddr(true);
        setTcpNoDelay(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        String token = extractQueryParam(descriptor, "token");

        if (token != null) {
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
            }
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String subId = conn.getAttachment();
        if (subId != null) {
            spyglassManager.removeSubscription(subId);
        } else if (conn.getRemoteSocketAddress() != null) {
            spyglassManager.removeSubscription(conn.getRemoteSocketAddress().toString());
        }
        sessionManager.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            packetRouter.handleMessage(conn, message);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling WebSocket packet: " + e.getMessage(), e);
        }
    }

    @Override
    public void onMessage(WebSocket conn, java.nio.ByteBuffer blob) {
        try {
            packetRouter.handleBinaryMessage(conn, blob);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling binary WebSocket packet: " + e.getMessage(), e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            sessionManager.remove(conn);
        }
    }

    @Override
    public void onStart() {
        plugin.getLogger().info("A.T.L.A.S. WebSocket daemon started on port " + getPort());
    }

    public void broadcastJson(String json) {
        sessionManager.broadcast(json);
    }

    public void setTelemetryEngine(net.atlas.telemetry.TelemetryEngine telemetryEngine) {
        this.packetRouter.setTelemetryEngine(telemetryEngine);
    }

    public void setVoiceTransmitterService(net.atlas.voice.VoiceTransmitterService voiceTransmitterService) {
        this.packetRouter.setVoiceTransmitterService(voiceTransmitterService);
    }

    public int getConnectedClientsCount() {
        return sessionManager.getConnectedCount();
    }

    public void disconnectAllSessions(String reason) {
        sessionManager.closeAllSessions(reason);
    }

    private String extractQueryParam(String descriptor, String name) {
        if (descriptor == null || !descriptor.contains("?")) return null;
        String query = descriptor.substring(descriptor.indexOf("?") + 1);
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase(name)) {
                return pair[1];
            }
        }
        return null;
    }
}
