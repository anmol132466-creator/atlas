// src/main/java/net/atlas/config/AtlasConfig.java
package net.atlas.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class AtlasConfig {

    private final WebServerConfig web;
    private final MapConfig map;
    private final TelemetryConfig telemetry;
    private final VoiceConfig voice;

    public AtlasConfig(FileConfiguration config) {
        this.web = new WebServerConfig(
                config.getString("web.host", "0.0.0.0"),
                config.getInt("web.http-port", 27994),
                config.getInt("web.websocket-port", 27994),
                config.getString("web.public-url", ""),
                config.getBoolean("web.ssl.enabled", false),
                config.getBoolean("web.ssl.self-signed", true),
                config.getString("web.ssl.keystore-path", "plugins/ATLAS/keystore.jks"),
                config.getString("web.ssl.keystore-password", "atlas_secure_pass"),
                config.getBoolean("web.rate-limit.enabled", true),
                config.getInt("web.rate-limit.burst-capacity", 60),
                config.getInt("web.rate-limit.refill-per-second", 5)
        );

        MapConfig.WorldSpawnConfig worldSpawnConfig = new MapConfig.WorldSpawnConfig(
                config.getBoolean("map.world-spawn.enabled", false),
                config.getString("map.world-spawn.dimension", "minecraft:overworld"),
                config.getInt("map.world-spawn.x", 0),
                config.getInt("map.world-spawn.y", 64),
                config.getInt("map.world-spawn.z", 0)
        );

        this.map = new MapConfig(
                config.getString("map.default-dimension", "minecraft:overworld"),
                config.getInt("map.max-memory-tiles", 256),
                config.getBoolean("map.disk-cache-enabled", true),
                config.getInt("map.max-concurrent-chunk-reads", 4),
                config.getBoolean("map.bounding-box.enabled", false),
                config.getInt("map.bounding-box.min-x", -10000),
                config.getInt("map.bounding-box.max-x", 10000),
                config.getInt("map.bounding-box.min-z", -10000),
                config.getInt("map.bounding-box.max-z", 10000),
                config.getBoolean("map.render-unloaded-chunks", true),
                config.getDouble("map.hillshading-intensity", 0.12),
                worldSpawnConfig
        );

        this.telemetry = new TelemetryConfig(
                config.getInt("telemetry.broadcast-frequency-hz", 15),
                config.getInt("telemetry.max-batch-size", 100)
        );

        this.voice = new VoiceConfig(
                config.getBoolean("voice.enabled", true),
                config.getDouble("voice.max-spyglass-radius", 100.0)
        );
    }

    public WebServerConfig web() {
        return web;
    }

    public MapConfig map() {
        return map;
    }

    public TelemetryConfig telemetry() {
        return telemetry;
    }

    public VoiceConfig voice() {
        return voice;
    }

    public record VoiceConfig(boolean enabled, double maxSpyglassRadius) {}
}
