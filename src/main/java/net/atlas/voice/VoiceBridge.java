// src/main/java/net/atlas/voice/VoiceBridge.java
package net.atlas.voice;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class VoiceBridge {

    private final Plugin plugin;
    private final SpyglassManager spyglassManager;
    private final VoiceTransmitterService transmitterService;
    private boolean hooked = false;

    public VoiceBridge(Plugin plugin, SpyglassManager spyglassManager) {
        this.plugin = plugin;
        this.spyglassManager = spyglassManager;
        this.transmitterService = new VoiceTransmitterService(plugin);
    }

    public void attemptHook() {
        if (!plugin.getConfig().getBoolean("voice.enabled", true)) {
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("voicechat")) {
            plugin.getLogger().info("Simple Voice Chat plugin not detected. Audio Spyglass module disabled.");
            return;
        }

        try {
            VoiceRegistrationHandler.register(spyglassManager, transmitterService);
            this.hooked = true;
            plugin.getLogger().info("Successfully attached A.T.L.A.S. Spyglass & Voice Transmitter to Simple Voice Chat API.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to hook into VoiceChat API: " + t.getMessage());
            this.hooked = false;
        }
    }

    public boolean isHooked() {
        return hooked;
    }

    public VoiceTransmitterService getTransmitterService() {
        return transmitterService;
    }

    public void shutdown() {
        if (hooked) {
            try {
                VoiceRegistrationHandler.unregister();
            } catch (Throwable ignored) {}
            this.hooked = false;
        }
        if (spyglassManager != null) {
            spyglassManager.close();
        }
        if (transmitterService != null) {
            transmitterService.close();
        }
    }

    /**
     * Isolated static helper class.
     * The JVM will ONLY load this class and its dependencies (de.maxhenkel.voicechat.*)
     * if Bukkit.getPluginManager().isPluginEnabled("voicechat") is TRUE.
     */
    private static final class VoiceRegistrationHandler {
        private static VoicePluginHook activeHook;

        static void register(SpyglassManager manager, VoiceTransmitterService transmitter) {
            de.maxhenkel.voicechat.api.BukkitVoicechatService service = 
                    Bukkit.getServicesManager().load(de.maxhenkel.voicechat.api.BukkitVoicechatService.class);
            if (service != null) {
                VoicePluginHook.setSpyglassManager(manager);
                VoicePluginHook.setTransmitterService(transmitter);
                activeHook = new VoicePluginHook();
                service.registerPlugin(activeHook);
            }
        }

        static void unregister() {
            VoicePluginHook.setSpyglassManager(null);
            VoicePluginHook.setTransmitterService(null);
            activeHook = null;
        }
    }
}
