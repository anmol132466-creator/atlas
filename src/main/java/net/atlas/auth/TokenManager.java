// src/main/java/net/atlas/auth/TokenManager.java
package net.atlas.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class TokenManager {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String masterConsoleToken;
    private final Map<String, EphemeralToken> activeLinkTokens = new ConcurrentHashMap<>();
    private final AtomicReference<String> activePersistentToken = new AtomicReference<>(null);

    public TokenManager() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        this.masterConsoleToken = URL_ENCODER.encodeToString(bytes);
    }

    public String getMasterConsoleToken() {
        return masterConsoleToken;
    }

    public String getOrCreatePersistentLinkToken(String issuer) {
        String existing = activePersistentToken.get();
        if (existing != null && validateToken(existing)) {
            return existing;
        }
        return generateNewPersistentLinkToken(issuer);
    }

    public String generateNewPersistentLinkToken(String issuer) {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        String token = URL_ENCODER.encodeToString(bytes);
        activeLinkTokens.put(token, new EphemeralToken(token, issuer, Long.MAX_VALUE, true));
        activePersistentToken.set(token);
        return token;
    }

    public String generateLinkToken(String issuer, long ttlSeconds) {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        String token = URL_ENCODER.encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L);
        activeLinkTokens.put(token, new EphemeralToken(token, issuer, expiresAt, false));
        cleanExpiredTokens();
        return token;
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.equals(masterConsoleToken)) {
            return true;
        }
        EphemeralToken ephemeral = activeLinkTokens.get(token);
        if (ephemeral != null) {
            if (ephemeral.persistent() || System.currentTimeMillis() <= ephemeral.expiresAt()) {
                return true;
            } else {
                activeLinkTokens.remove(token);
            }
        }
        return false;
    }

    public String consumeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.equals(masterConsoleToken)) {
            return "CONSOLE";
        }
        EphemeralToken ephemeral = activeLinkTokens.get(token);
        if (ephemeral != null) {
            if (ephemeral.persistent() || System.currentTimeMillis() <= ephemeral.expiresAt()) {
                if (!ephemeral.persistent()) {
                    activeLinkTokens.remove(token);
                }
                return ephemeral.issuer();
            } else {
                activeLinkTokens.remove(token);
            }
        }
        return null;
    }

    public String getIssuer(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.equals(masterConsoleToken)) return "CONSOLE";
        EphemeralToken ephemeral = activeLinkTokens.get(token);
        if (ephemeral != null && (ephemeral.persistent() || System.currentTimeMillis() <= ephemeral.expiresAt())) {
            return ephemeral.issuer();
        }
        return null;
    }

    public void revokeAllLinkTokens() {
        activePersistentToken.set(null);
        activeLinkTokens.clear();
    }

    public void revokeLinkToken(String token) {
        if (token != null) {
            activeLinkTokens.remove(token);
            activePersistentToken.compareAndSet(token, null);
        }
    }

    private void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        activeLinkTokens.entrySet().removeIf(entry -> !entry.getValue().persistent() && entry.getValue().expiresAt() < now);
    }

    public record EphemeralToken(String token, String issuer, long expiresAt, boolean persistent) {
        public EphemeralToken(String token, String issuer, long expiresAt) {
            this(token, issuer, expiresAt, false);
        }
    }
}
