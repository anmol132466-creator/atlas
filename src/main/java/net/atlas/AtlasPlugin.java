// src/main/java/net/atlas/AtlasPlugin.java
package net.atlas;

import net.atlas.auth.RateLimiter;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;
import net.atlas.config.AtlasConfig;
import net.atlas.map.cache.TileDiskCache;
import net.atlas.map.cache.TileMemoryCache;
import net.atlas.map.render.PaletteRegistry;
import net.atlas.map.render.TileRenderer;
import net.atlas.moderation.ModerationDispatcher;
import net.atlas.moderation.command.AtlasCommand;
import net.atlas.offline.OfflinePlayerService;
import net.atlas.offline.PendingActionLedger;
import net.atlas.offline.listeners.OfflineJoinProcessor;
import net.atlas.telemetry.LiveInspectorService;
import net.atlas.telemetry.TelemetryEngine;
import net.atlas.telemetry.listeners.TelemetryLifecycleListener;
import net.atlas.telemetry.listeners.TelemetryMoveListener;
import net.atlas.voice.SpyglassManager;
import net.atlas.voice.VoiceBridge;
import net.atlas.web.EmbeddedHttpServer;
import net.atlas.web.PublicIpResolver;
import net.atlas.websocket.AtlasWebSocketServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

public final class AtlasPlugin extends JavaPlugin {

    private static AtlasPlugin instance;

    private AtlasConfig atlasConfig;
    private TokenManager tokenManager;
    private SessionRegistry sessionRegistry;
    private RateLimiter rateLimiter;

    private PaletteRegistry paletteRegistry;
    private TileMemoryCache tileMemoryCache;
    private TileDiskCache tileDiskCache;
    private TileRenderer tileRenderer;
    private PublicIpResolver publicIpResolver;

    private PendingActionLedger pendingActionLedger;
    private OfflinePlayerService offlinePlayerService;
    private LiveInspectorService liveInspectorService;
    private TelemetryEngine telemetryEngine;
    private ModerationDispatcher moderationDispatcher;

    private SpyglassManager spyglassManager;
    private VoiceBridge voiceBridge;

    private EmbeddedHttpServer httpServer;
    private AtlasWebSocketServer webSocketServer;
    private net.atlas.web.AtlasPortMultiplexer portMultiplexer;
    private boolean isSinglePortMode = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // 1. Initialize Directories
        Path dataDir = getDataFolder().toPath();
        Path cacheDir = dataDir.resolve("cache").resolve("tiles");
        Path playersDir = dataDir.resolve("data").resolve("players");
        try {
            Files.createDirectories(cacheDir);
            Files.createDirectories(playersDir);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to create ATLAS storage directories", e);
        }

        // 2. Load Strong Configurations
        this.atlasConfig = new AtlasConfig(getConfig());
        this.publicIpResolver = new PublicIpResolver(this);
        this.publicIpResolver.initAsync();

        // 3. Security, Authentication & Rate Limiting
        this.tokenManager = new TokenManager();
        this.sessionRegistry = new SessionRegistry();
        this.rateLimiter = new RateLimiter(
                atlasConfig.web().rateLimitBurst(),
                atlasConfig.web().rateLimitRefill()
        );

        // 4. Map & Tile Rendering Pipeline
        this.paletteRegistry = new PaletteRegistry(this);
        this.paletteRegistry.loadDefaultPalette();
        this.tileMemoryCache = new TileMemoryCache(atlasConfig.map().maxMemoryTiles());
        this.tileDiskCache = new TileDiskCache(cacheDir, atlasConfig.map().isDiskCacheEnabled());
        this.tileDiskCache.clear();
        this.tileRenderer = new TileRenderer(this, atlasConfig.map(), paletteRegistry, tileMemoryCache, tileDiskCache);

        // 5. Offline Data & Pending Actions Ledger
        File ledgerFile = dataDir.resolve("data").resolve("pending_actions.json").toFile();
        this.pendingActionLedger = new PendingActionLedger(ledgerFile);
        this.pendingActionLedger.load();
        this.offlinePlayerService = new OfflinePlayerService(this, playersDir);

        // 6. Moderation & Live Inspection
        this.liveInspectorService = new LiveInspectorService(this);
        this.moderationDispatcher = new ModerationDispatcher(this, pendingActionLedger, offlinePlayerService);

        // 7. Voice Surveillance ("The Spyglass")
        this.spyglassManager = new SpyglassManager(atlasConfig.voice().maxSpyglassRadius());
        this.voiceBridge = new VoiceBridge(this, spyglassManager);
        this.voiceBridge.attemptHook();

        // 8. Single-Port Multiplexing Detection & VoiceChat Port Conflict Protection
        int httpPort = atlasConfig.web().httpPort();
        int wsPort = atlasConfig.web().websocketPort();
        String bindHost = atlasConfig.web().host();

        int detectedVcPort = net.atlas.voice.VoicePluginHook.getDetectedVoiceChatPort();
        if (wsPort == 27995 || (detectedVcPort > 0 && wsPort == detectedVcPort)) {
            getLogger().warning("[A.T.L.A.S.] Configured websocket-port (" + wsPort + ") conflicts with Simple Voice Chat's UDP voice port (" + (detectedVcPort > 0 ? detectedVcPort : 27995) + ")!");
            getLogger().warning("[A.T.L.A.S.] Automatically activating unified Single-Port mode on HTTP port " + httpPort + " so Simple Voice Chat has port " + wsPort + " exclusively.");
            wsPort = httpPort;
        }

        boolean singlePortMode = (httpPort == wsPort);
        this.isSinglePortMode = singlePortMode;

        int internalHttpPort = httpPort;
        int internalWsPort = wsPort;
        String internalHttpHost = bindHost;
        String internalWsHost = bindHost;

        if (singlePortMode) {
            try {
                int[] freePorts = findTwoFreeLoopbackPorts();
                internalWsPort = freePorts[0];
                internalHttpPort = freePorts[1];
                internalWsHost = "127.0.0.1";
                internalHttpHost = "127.0.0.1";
                getLogger().info("Single-Port Mode detected: multiplexing HTTP and WebSockets on unified port " + httpPort);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Failed to reserve internal loopback ports for multiplexer", e);
            }
        }

        // 8b. WebSocket Daemon
        try {
            this.webSocketServer = new AtlasWebSocketServer(
                    this,
                    internalWsHost,
                    internalWsPort,
                    tokenManager,
                    sessionRegistry,
                    moderationDispatcher,
                    liveInspectorService,
                    offlinePlayerService,
                    spyglassManager,
                    this.voiceBridge != null ? this.voiceBridge.getTransmitterService() : null
            );
            this.webSocketServer.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to bind ATLAS WebSocket daemon", e);
        }

        // 9. Telemetry Engine
        this.telemetryEngine = new TelemetryEngine(this, atlasConfig.telemetry(), webSocketServer);
        if (this.webSocketServer != null) {
            this.webSocketServer.setTelemetryEngine(this.telemetryEngine);
        }
        this.telemetryEngine.start();
        Bukkit.getScheduler().runTask(this, () -> this.telemetryEngine.syncAllOnlinePlayers());

        // 10. HTTP Web Server (Virtual Threads)
        try {
            this.httpServer = new EmbeddedHttpServer(
                    this,
                    atlasConfig.web(),
                    tokenManager,
                    sessionRegistry,
                    rateLimiter,
                    tileRenderer
            );
            this.httpServer.start(internalHttpHost, internalHttpPort);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start ATLAS Embedded HTTP Server", e);
        }

        // 10b. Start Multiplexer if in single-port mode
        if (singlePortMode) {
            try {
                this.portMultiplexer = new net.atlas.web.AtlasPortMultiplexer(
                        this,
                        bindHost,
                        httpPort,
                        internalHttpPort,
                        internalWsPort
                );
                this.portMultiplexer.start();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to start ATLAS Port Multiplexer on port " + httpPort, e);
            }
        }

        // 11. Event Listeners
        Bukkit.getPluginManager().registerEvents(new TelemetryMoveListener(telemetryEngine), this);
        Bukkit.getPluginManager().registerEvents(new TelemetryLifecycleListener(telemetryEngine, offlinePlayerService, liveInspectorService), this);
        Bukkit.getPluginManager().registerEvents(new OfflineJoinProcessor(pendingActionLedger), this);
        Bukkit.getPluginManager().registerEvents(new net.atlas.map.listeners.BlockChangeListener(tileRenderer, webSocketServer), this);
        Bukkit.getPluginManager().registerEvents(new net.atlas.map.listeners.WorldSpawnListener(this, webSocketServer), this);
        Bukkit.getPluginManager().registerEvents(new net.atlas.telemetry.listeners.ChatSurveillanceListener(webSocketServer), this);

        // 11b. Enforce configured world spawn if enabled in config
        enforceConfiguredWorldSpawn();

        // 11b. Periodic Maintenance Task (RateLimiter bucket flush & voice decoder cleanup)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (this.rateLimiter != null) {
                this.rateLimiter.cleanup();
            }
            if (this.spyglassManager != null) {
                this.spyglassManager.cleanupIdleDecoders(30000L);
            }
        }, 1200L, 1200L);

        // 12. Register Commands
        AtlasCommand executor = new AtlasCommand(this, tokenManager, sessionRegistry, tileMemoryCache, tileDiskCache);
        PluginCommand pluginCmd = getCommand("atlas");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(executor);
            pluginCmd.setTabCompleter(executor);
        } else {
            Bukkit.getCommandMap().register("atlas", executor);
        }

        // 13. Terminal Startup Banner
        logStartupBanner();
    }

    private static int[] findTwoFreeLoopbackPorts() throws IOException {
        try (java.net.ServerSocket s1 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             java.net.ServerSocket s2 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return new int[] { s1.getLocalPort(), s2.getLocalPort() };
        }
    }

    @Override
    public void onDisable() {
        if (portMultiplexer != null) {
            portMultiplexer.stop();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (telemetryEngine != null) {
            telemetryEngine.stop();
        }
        if (pendingActionLedger != null) {
            pendingActionLedger.flush();
        }
        if (tileMemoryCache != null) {
            tileMemoryCache.clear();
        }
        if (voiceBridge != null) {
            voiceBridge.shutdown();
        }
        getLogger().info("A.T.L.A.S. surveillance daemon successfully unmounted.");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.atlasConfig = new AtlasConfig(getConfig());
        enforceConfiguredWorldSpawn();
    }

    private void enforceConfiguredWorldSpawn() {
        if (atlasConfig != null && atlasConfig.map().worldSpawn().enabled()) {
            net.atlas.config.MapConfig.WorldSpawnConfig cfg = atlasConfig.map().worldSpawn();
            Bukkit.getScheduler().runTask(this, () -> {
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    String dim = cfg.dimension();
                    if (world.getName().equalsIgnoreCase(dim) || world.getKey().asString().equalsIgnoreCase(dim)
                            || (dim.contains("overworld") && world.getEnvironment() == org.bukkit.World.Environment.NORMAL)) {
                        world.setSpawnLocation(cfg.x(), cfg.y(), cfg.z());
                        getLogger().info("Enforced configured world spawn in " + world.getName() + " at ("
                                + cfg.x() + ", " + cfg.y() + ", " + cfg.z() + ")");
                        if (webSocketServer != null) {
                            com.google.gson.JsonObject packet = new com.google.gson.JsonObject();
                            packet.addProperty("type", "WORLD_SPAWN_UPDATE");
                            packet.addProperty("world", world.getName());
                            packet.addProperty("dimension", world.getKey().asString());
                            packet.addProperty("spawnX", cfg.x());
                            packet.addProperty("spawnY", cfg.y());
                            packet.addProperty("spawnZ", cfg.z());
                            webSocketServer.broadcastJson(packet.toString());
                        }
                        break;
                    }
                }
            });
        }
    }

    private void logStartupBanner() {
        String protocol = atlasConfig.web().isSslEnabled() ? "https://" : "http://";
        int port = atlasConfig.web().httpPort();
        String masterToken = tokenManager.getMasterConsoleToken();
        String localUrl = protocol + "localhost:" + port + "/?token=" + masterToken;
        String voiceStatus = voiceBridge.isHooked() ? "HOOKED (Active Spyglass)" : "UNAVAILABLE (Mod Missing)";

        getLogger().info("======================================================================");
        getLogger().info("    _   _____ _        _   ___ ");
        getLogger().info("   /_\\ |_   _| |      /_\\ / __|");
        getLogger().info("  / _ \\  | | | |__   / _ \\\\__ \\");
        getLogger().info(" /_/ \\_\\ |_| |____| /_/ \\_\\___/ Admin Tool for Live Area Surveillance");
        getLogger().info("======================================================================");
        getLogger().info("  HTTP Port:        " + port + " (Virtual Threads)");
        getLogger().info("  WebSocket Port:   " + atlasConfig.web().websocketPort() + " (NIO Shaded)");
        getLogger().info("  Security Protocol: HMAC-SHA256 Token Auth & Virtual Thread Sandbox");
        getLogger().info("  VoiceChat Bridge: " + voiceStatus);
        getLogger().info("  Master Access URLs (Console Operator):");
        getLogger().info("  > Localhost (Recommended - No Browser Warning):");
        getLogger().info("    " + localUrl);

        String remoteHost = (publicIpResolver != null) ? publicIpResolver.getCachedPublicIp() : null;
        if (remoteHost != null && !remoteHost.isBlank() && !remoteHost.equals("127.0.0.1") && !remoteHost.equalsIgnoreCase("localhost")) {
            getLogger().info("  > Remote / Network Access:");
            getLogger().info("    " + protocol + remoteHost + ":" + port + "/?token=" + masterToken);
        }
        getLogger().info("======================================================================");
    }

    public static AtlasPlugin getInstance() {
        return instance;
    }

    public AtlasConfig getAtlasConfig() {
        return atlasConfig;
    }

    public TokenManager getTokenManager() {
        return tokenManager;
    }

    public SessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public TileRenderer getTileRenderer() {
        return tileRenderer;
    }

    public PublicIpResolver getPublicIpResolver() {
        return publicIpResolver;
    }

    public PendingActionLedger getPendingActionLedger() {
        return pendingActionLedger;
    }

    public OfflinePlayerService getOfflinePlayerService() {
        return offlinePlayerService;
    }

    public LiveInspectorService getLiveInspectorService() {
        return liveInspectorService;
    }

    public TelemetryEngine getTelemetryEngine() {
        return telemetryEngine;
    }

    public AtlasWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public SpyglassManager getSpyglassManager() {
        return spyglassManager;
    }

    public boolean isSinglePortMode() {
        return isSinglePortMode;
    }

    public void broadcastPendingActions() {
        if (webSocketServer == null || pendingActionLedger == null) {
            return;
        }
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (net.atlas.offline.model.PendingAction act : pendingActionLedger.getAllPendingActions()) {
            array.add(act.toJsonObject());
        }
        com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
        resp.addProperty("type", "PENDING_ACTIONS_LIST");
        resp.add("actions", array);
        webSocketServer.broadcastJson(resp.toString());
    }

    public void closeDashboardInstance() {
        if (tokenManager != null) {
            tokenManager.revokeAllLinkTokens();
        }
        if (sessionRegistry != null) {
            sessionRegistry.invalidateAll();
        }
        if (webSocketServer != null) {
            webSocketServer.disconnectAllSessions("Surveillance dashboard instance closed by administrator.");
        }
        if (spyglassManager != null) {
            spyglassManager.clearAll();
        }
        getLogger().info("[A.T.L.A.S.] Active dashboard instance closed and access links revoked.");
    }
}
