// src/main/java/net/atlas/voice/VoicePluginHook.java
package net.atlas.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class VoicePluginHook implements VoicechatPlugin {

    private static SpyglassManager spyglassManager;
    private static VoiceTransmitterService transmitterService;
    private static VoicechatApi voicechatApi;

    public static void setSpyglassManager(SpyglassManager manager) {
        spyglassManager = manager;
        if (manager != null && voicechatApi != null) {
            manager.setDecoderService(new SimpleVoiceChatDecoderService(voicechatApi));
        }
    }

    public static void setTransmitterService(VoiceTransmitterService service) {
        transmitterService = service;
        if (service != null && voicechatApi instanceof de.maxhenkel.voicechat.api.VoicechatServerApi serverApi) {
            service.setServerApi(serverApi);
        }
    }

    public static VoicechatApi getVoicechatApi() {
        return voicechatApi;
    }

    public static int getDetectedVoiceChatPort() {
        if (voicechatApi instanceof de.maxhenkel.voicechat.api.VoicechatServerApi serverApi) {
            try {
                return serverApi.getServerConfig().getInt("port", 24454);
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    @Override
    public String getPluginId() {
        return "atlas_surveillance";
    }

    @Override
    public void initialize(VoicechatApi api) {
        voicechatApi = api;
        int vcPort = -1;
        if (api instanceof de.maxhenkel.voicechat.api.VoicechatServerApi serverApi) {
            try {
                vcPort = serverApi.getServerConfig().getInt("port", 24454);
            } catch (Throwable ignored) {}

            try {
                de.maxhenkel.voicechat.api.VolumeCategory cat = serverApi.volumeCategoryBuilder()
                        .setId("atlas_broadcast")
                        .setName("A.T.L.A.S. Voice Broadcast")
                        .setDescription("Transmissions from the A.T.L.A.S. Web Dashboard")
                        .build();
                serverApi.registerVolumeCategory(cat);
                Bukkit.getLogger().info("[A.T.L.A.S.] Registered 'atlas_broadcast' VolumeCategory in Simple Voice Chat.");
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[A.T.L.A.S.] Could not register volume category: " + t.getMessage());
            }
        }

        Bukkit.getLogger().info("[A.T.L.A.S.] Simple Voice Chat API hook initialized successfully (VoiceChat UDP Port: " + (vcPort > 0 ? vcPort : "24454") + ").");

        if (spyglassManager != null && api != null) {
            spyglassManager.setDecoderService(new SimpleVoiceChatDecoderService(api));
        }
        if (transmitterService != null && api instanceof de.maxhenkel.voicechat.api.VoicechatServerApi serverApi) {
            transmitterService.setServerApi(serverApi);
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onPlayerDisconnected(de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent event) {
        if (transmitterService != null && event != null) {
            transmitterService.onPlayerDisconnected(event.getPlayerUuid());
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (spyglassManager == null || event.getSenderConnection() == null) {
            return;
        }

        UUID playerUuid = event.getSenderConnection().getPlayer().getUuid();
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        Location loc = player.getLocation();
        byte[] opusData = event.getPacket().getOpusEncodedData();
        String dimension = net.atlas.telemetry.TelemetryEngine.canonicalizeDimension(player.getWorld());

        spyglassManager.processAudioPacket(playerUuid, dimension, loc.getX(), loc.getY(), loc.getZ(), opusData);
    }
}
