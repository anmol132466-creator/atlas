// src/main/java/net/atlas/config/TelemetryConfig.java
package net.atlas.config;

public record TelemetryConfig(
        int broadcastFrequencyHz,
        int maxBatchSize
) {
    public long getIntervalMillis() {
        int hz = Math.max(1, Math.min(60, broadcastFrequencyHz));
        return 1000L / hz;
    }
}
