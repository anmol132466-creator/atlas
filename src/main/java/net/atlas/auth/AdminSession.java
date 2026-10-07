// src/main/java/net/atlas/auth/AdminSession.java
package net.atlas.auth;

import java.util.concurrent.atomic.AtomicLong;

public final class AdminSession {

    private final String sessionId;
    private final String principal;
    private final long createdAt;
    private final long ttlMillis;
    private final AtomicLong lastAccessedAt;

    public AdminSession(String sessionId, String principal, long ttlMillis) {
        this.sessionId = sessionId;
        this.principal = principal;
        this.createdAt = System.currentTimeMillis();
        this.ttlMillis = ttlMillis;
        this.lastAccessedAt = new AtomicLong(createdAt);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPrincipal() {
        return principal;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt.get();
    }

    public void touch() {
        this.lastAccessedAt.set(System.currentTimeMillis());
    }

    public boolean isExpired() {
        return (System.currentTimeMillis() - lastAccessedAt.get()) > ttlMillis;
    }
}
