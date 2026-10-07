// src/test/java/net/atlas/auth/TokenManagerTest.java
package net.atlas.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TokenManagerTest {

    @Test
    public void testMasterTokenGenerated() {
        TokenManager manager = new TokenManager();
        assertNotNull(manager.getMasterConsoleToken());
        assertFalse(manager.getMasterConsoleToken().isBlank());
        assertTrue(manager.validateToken(manager.getMasterConsoleToken()));
    }

    @Test
    public void testLinkTokenLifecycle() {
        TokenManager manager = new TokenManager();
        String linkToken = manager.generateLinkToken("AdminUser", 60);

        assertTrue(manager.validateToken(linkToken));
        String consumed = manager.consumeToken(linkToken);
        assertEquals("AdminUser", consumed);

        // Cannot consume twice
        assertFalse(manager.validateToken(linkToken));
        assertNull(manager.consumeToken(linkToken));
    }

    @Test
    public void testExpiredToken() throws InterruptedException {
        TokenManager manager = new TokenManager();
        String fastExpireToken = manager.generateLinkToken("TestUser", 0); // 0s TTL

        Thread.sleep(10);
        assertFalse(manager.validateToken(fastExpireToken));
        assertNull(manager.consumeToken(fastExpireToken));
    }

    @Test
    public void testPersistentTokenLifecycle() {
        TokenManager manager = new TokenManager();
        String persistentToken = manager.getOrCreatePersistentLinkToken("AdminUser");

        assertTrue(manager.validateToken(persistentToken));
        assertEquals(persistentToken, manager.getOrCreatePersistentLinkToken("AdminUser"));

        // Consuming doesn't invalidate persistent tokens
        String consumed = manager.consumeToken(persistentToken);
        assertEquals("AdminUser", consumed);
        assertTrue(manager.validateToken(persistentToken));
        assertEquals("AdminUser", manager.consumeToken(persistentToken));

        // Revoking invalidates persistent tokens
        manager.revokeAllLinkTokens();
        assertFalse(manager.validateToken(persistentToken));
        assertNull(manager.consumeToken(persistentToken));
    }

    @Test
    public void testGenerateNewPersistentToken() {
        TokenManager manager = new TokenManager();
        String token1 = manager.getOrCreatePersistentLinkToken("CONSOLE");
        String token2 = manager.generateNewPersistentLinkToken("CONSOLE");

        assertNotEquals(token1, token2);
        assertTrue(manager.validateToken(token2));
        assertEquals(token2, manager.getOrCreatePersistentLinkToken("CONSOLE"));
    }
}
