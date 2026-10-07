// src/main/java/net/atlas/config/WebServerConfig.java
package net.atlas.config;

public record WebServerConfig(
        String host,
        int httpPort,
        int websocketPort,
        String publicUrl,
        boolean isSslEnabled,
        boolean isSelfSignedSsl,
        String keystorePath,
        String keystorePassword,
        boolean isRateLimitEnabled,
        int rateLimitBurst,
        int rateLimitRefill
) {
    public String getHttpScheme() {
        return isSslEnabled ? "https://" : "http://";
    }

    public String getWsScheme() {
        return isSslEnabled ? "wss://" : "ws://";
    }
}
