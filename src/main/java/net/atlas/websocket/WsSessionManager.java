// src/main/java/net/atlas/websocket/WsSessionManager.java
package net.atlas.websocket;

import net.atlas.auth.AdminSession;
import org.java_websocket.WebSocket;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WsSessionManager {

    private static final Logger LOGGER = Logger.getLogger("ATLAS-WsSession");

    private final Map<WebSocket, AdminSession> authenticatedSockets = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> telemetrySubscriptions = new ConcurrentHashMap<>();

    public void registerAuthenticated(WebSocket conn, AdminSession session) {
        authenticatedSockets.put(conn, session);
    }

    public boolean isAuthenticated(WebSocket conn) {
        return authenticatedSockets.containsKey(conn);
    }

    public AdminSession getSession(WebSocket conn) {
        return authenticatedSockets.get(conn);
    }

    public void remove(WebSocket conn) {
        authenticatedSockets.remove(conn);
        telemetrySubscriptions.remove(conn);
    }

    public void setTelemetrySubscription(WebSocket conn, String dimension) {
        telemetrySubscriptions.put(conn, dimension);
    }

    public String getTelemetrySubscription(WebSocket conn) {
        return telemetrySubscriptions.get(conn);
    }

    /**
     * Broadcasts a text message to all authenticated sockets.
     * Uses a snapshot of the keyset to prevent ConcurrentModificationException
     * when sockets disconnect mid-iteration.
     */
    public void broadcast(String message) {
        for (Map.Entry<WebSocket, AdminSession> entry : authenticatedSockets.entrySet()) {
            WebSocket conn = entry.getKey();
            try {
                if (conn.isOpen()) {
                    conn.send(message);
                }
            } catch (Exception e) {
                // Socket closed between isOpen() check and send(); safe to ignore
                LOGGER.log(Level.FINE, "Failed to broadcast to socket, removing", e);
                authenticatedSockets.remove(conn);
                telemetrySubscriptions.remove(conn);
            }
        }
    }

    public void sendBinary(WebSocket conn, ByteBuffer buffer) {
        try {
            if (conn != null && conn.isOpen()) {
                conn.send(buffer);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to send binary data", e);
        }
    }

    public void closeAllSessions(String reason) {
        for (WebSocket conn : authenticatedSockets.keySet()) {
            try {
                if (conn.isOpen()) {
                    conn.close(1000, reason);
                }
            } catch (Exception ignored) {
            }
        }
        authenticatedSockets.clear();
        telemetrySubscriptions.clear();
    }

    public int getConnectedCount() {
        return authenticatedSockets.size();
    }
}
