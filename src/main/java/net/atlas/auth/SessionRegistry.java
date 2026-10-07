// src/main/java/net/atlas/auth/SessionRegistry.java
package net.atlas.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final long DEFAULT_SESSION_TTL_MS = 24L * 60L * 60L * 1000L; // 24 Hours

    private final Map<String, AdminSession> sessions = new ConcurrentHashMap<>();

    public AdminSession createSession(String principal) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String sessionId = URL_ENCODER.encodeToString(bytes);

        AdminSession session = new AdminSession(sessionId, principal, DEFAULT_SESSION_TTL_MS);
        sessions.put(sessionId, session);
        cleanExpiredSessions();
        return session;
    }

    public AdminSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        AdminSession session = sessions.get(sessionId);
        if (session != null) {
            if (session.isExpired()) {
                sessions.remove(sessionId);
                return null;
            }
            session.touch();
            return session;
        }
        return null;
    }

    public boolean isValid(String sessionId) {
        return getSession(sessionId) != null;
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void invalidateAll() {
        sessions.clear();
    }

    public int getActiveSessionCount() {
        cleanExpiredSessions();
        return sessions.size();
    }

    private void cleanExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}
