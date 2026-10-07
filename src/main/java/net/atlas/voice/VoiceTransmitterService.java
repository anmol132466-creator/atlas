// src/main/java/net/atlas/voice/VoiceTransmitterService.java
package net.atlas.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class VoiceTransmitterService {

    private static final int OPUS_FRAME_SIZE = 960; // Standard 20ms at 48kHz mono
    private static final String VOLUME_CATEGORY = "atlas_broadcast";

    private final Plugin plugin;
    private volatile VoicechatServerApi serverApi;
    private volatile OpusEncoder broadcastEncoder;

    private final Map<UUID, PlayerAudioSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicLong lastBroadcastAlert = new AtomicLong(0L);
    private final Map<UUID, Long> lastPlayerAlert = new ConcurrentHashMap<>();
    private final AtomicLong lastAreaAlert = new AtomicLong(0L);

    public VoiceTransmitterService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setServerApi(VoicechatServerApi api) {
        this.serverApi = api;
        if (api != null) {
            try {
                this.broadcastEncoder = api.createEncoder();
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not initialize OpusEncoder: " + t.getMessage());
            }
        }
    }

    /**
     * Checks if a player's connection is valid and actively capable of receiving audio frames.
     */
    public boolean isVoiceEligible(VoicechatConnection conn) {
        if (conn == null) return false;
        if (!conn.isInstalled()) return false;
        if (conn.isDisabled()) return false;
        return true;
    }

    /**
     * Broadcasts live audio from the web console to all voice-connected players on the server.
     */
    public void broadcastAudio(byte[] audioData, boolean isPcm) {
        if (audioData == null || audioData.length == 0) return;

        short[] pcm = resolvePcmSamples(audioData, isPcm);
        if (pcm == null || pcm.length == 0) return;

        // Visual and auditory alert in-game (throttled to once every 10 seconds)
        long now = System.currentTimeMillis();
        if (now - lastBroadcastAlert.get() > 10000L) {
            lastBroadcastAlert.set(now);
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendActionBar(Component.text("🎙 [ATLAS] Admin Live Voice Broadcast", NamedTextColor.GOLD, TextDecoration.BOLD));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.VOICE, 0.8f, 1.2f);
                }
            });
        }

        VoicechatServerApi api = this.serverApi;
        if (api == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                VoicechatConnection conn = api.getConnectionOf(player.getUniqueId());
                if (isVoiceEligible(conn)) {
                    PlayerAudioSession session = getOrCreateSession(api, player.getUniqueId(), conn);
                    if (session != null) {
                        session.enqueue(pcm);
                    }
                }
            } catch (Throwable t) {
                onPlayerDisconnected(player.getUniqueId());
            }
        }
    }

    /**
     * Transmits live audio from the web console directly to a specific target player.
     */
    public void transmitToPlayer(UUID targetUuid, byte[] audioData, boolean isPcm) {
        if (targetUuid == null || audioData == null || audioData.length == 0) return;

        short[] pcm = resolvePcmSamples(audioData, isPcm);
        if (pcm == null || pcm.length == 0) return;

        long now = System.currentTimeMillis();
        Long lastAlert = lastPlayerAlert.get(targetUuid);
        if (lastAlert == null || now - lastAlert > 8000L) {
            lastPlayerAlert.put(targetUuid, now);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(Component.text("🎙 [ATLAS] Incoming Direct Voice Transmission", NamedTextColor.RED, TextDecoration.BOLD));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.VOICE, 0.9f, 1.4f);
                }
            });
        }

        VoicechatServerApi api = this.serverApi;
        if (api == null) return;

        try {
            VoicechatConnection conn = api.getConnectionOf(targetUuid);
            if (isVoiceEligible(conn)) {
                PlayerAudioSession session = getOrCreateSession(api, targetUuid, conn);
                if (session != null) {
                    session.enqueue(pcm);
                }
            }
        } catch (Throwable t) {
            onPlayerDisconnected(targetUuid);
        }
    }

    /**
     * Transmits live audio from the web console to players within a specific area and radius.
     */
    public void transmitToArea(String dimension, double x, double y, double z, double radius, byte[] audioData, boolean isPcm) {
        if (audioData == null || audioData.length == 0) return;

        short[] pcm = resolvePcmSamples(audioData, isPcm);
        if (pcm == null || pcm.length == 0) return;

        VoicechatServerApi api = this.serverApi;
        if (api == null) return;

        World world = resolveWorld(dimension);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) return;

        Location center = new Location(world, x, y, z);
        double radSq = radius * radius;

        long now = System.currentTimeMillis();
        if (now - lastAreaAlert.get() > 8000L) {
            lastAreaAlert.set(now);
            final World alertWorld = world;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player player : alertWorld.getPlayers()) {
                    if (player.getLocation().distanceSquared(center) <= radSq) {
                        player.sendActionBar(Component.text("🎙 [ATLAS] Admin Area Voice Transmission (Proximity)", NamedTextColor.AQUA, TextDecoration.BOLD));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.VOICE, 0.8f, 1.4f);
                    }
                }
            });
        }

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= radSq) {
                try {
                    VoicechatConnection conn = api.getConnectionOf(player.getUniqueId());
                    if (isVoiceEligible(conn)) {
                        PlayerAudioSession session = getOrCreateSession(api, player.getUniqueId(), conn);
                        if (session != null) {
                            session.enqueue(pcm);
                        }
                    }
                } catch (Throwable t) {
                    onPlayerDisconnected(player.getUniqueId());
                }
            }
        }
    }

    private short[] resolvePcmSamples(byte[] audioData, boolean isPcm) {
        if (isPcm) {
            return bytesToShorts(audioData);
        }

        VoicechatServerApi api = this.serverApi;
        if (api != null) {
            try {
                de.maxhenkel.voicechat.api.opus.OpusDecoder dec = api.createDecoder();
                short[] decoded = dec.decode(audioData);
                dec.close();
                return decoded;
            } catch (Throwable ignored) {}
        }
        return new short[0];
    }

    private World resolveWorld(String dimension) {
        if (dimension == null || dimension.isBlank()) return null;
        String norm = dimension.toLowerCase().replace("minecraft:", "");
        for (World w : Bukkit.getWorlds()) {
            String wNorm = w.getName().toLowerCase();
            if (wNorm.equals(norm) || w.getKey().asString().equalsIgnoreCase(dimension) ||
                (norm.contains("nether") && wNorm.contains("nether")) ||
                (norm.contains("end") && wNorm.contains("the_end"))) {
                return w;
            }
        }
        return null;
    }

    private PlayerAudioSession getOrCreateSession(VoicechatServerApi api, UUID playerUuid, VoicechatConnection conn) {
        PlayerAudioSession existing = activeSessions.get(playerUuid);
        if (existing != null && !existing.closed && existing.channel != null && !existing.channel.isClosed()) {
            return existing;
        }

        try {
            UUID channelId = UUID.randomUUID();
            StaticAudioChannel channel = api.createStaticAudioChannel(channelId, conn.getPlayer().getServerLevel(), conn);
            if (channel != null) {
                channel.setCategory(VOLUME_CATEGORY);
                PlayerAudioSession session = new PlayerAudioSession(playerUuid, channel);
                activeSessions.put(playerUuid, session);
                return session;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[A.T.L.A.S.] Failed to create StaticAudioChannel for player " + playerUuid + ": " + t.getMessage());
        }
        return null;
    }

    public void onPlayerDisconnected(UUID playerUuid) {
        PlayerAudioSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    private short[] bytesToShorts(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return new short[0];
        int shortCount = bytes.length / 2;
        short[] shorts = new short[shortCount];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }

    public void close() {
        for (PlayerAudioSession session : activeSessions.values()) {
            session.close();
        }
        activeSessions.clear();
        if (broadcastEncoder != null) {
            try {
                broadcastEncoder.close();
            } catch (Throwable ignored) {}
            broadcastEncoder = null;
        }
    }

    /**
     * Managed audio streaming session per player connection with paced 20ms AudioPlayer loop.
     */
    private final class PlayerAudioSession {
        final UUID playerUuid;
        final StaticAudioChannel channel;
        final ConcurrentLinkedQueue<short[]> pcmQueue = new ConcurrentLinkedQueue<>();
        volatile AudioPlayer player;
        volatile long lastEnqueuedTime = System.currentTimeMillis();
        volatile boolean closed = false;

        PlayerAudioSession(UUID playerUuid, StaticAudioChannel channel) {
            this.playerUuid = playerUuid;
            this.channel = channel;
        }

        void enqueue(short[] samples) {
            if (closed || samples == null || samples.length == 0) return;

            for (int i = 0; i + OPUS_FRAME_SIZE <= samples.length; i += OPUS_FRAME_SIZE) {
                short[] slice = Arrays.copyOfRange(samples, i, i + OPUS_FRAME_SIZE);
                pcmQueue.offer(slice);
            }
            if (samples.length < OPUS_FRAME_SIZE && samples.length > 0) {
                short[] padded = new short[OPUS_FRAME_SIZE];
                System.arraycopy(samples, 0, padded, 0, samples.length);
                pcmQueue.offer(padded);
            }
            lastEnqueuedTime = System.currentTimeMillis();
            ensurePlaying();
        }

        synchronized void ensurePlaying() {
            VoicechatServerApi api = serverApi;
            if (api == null || closed) return;

            if (player == null || player.isStopped()) {
                try {
                    player = api.createAudioPlayer(channel, api.createEncoder(), () -> {
                        short[] frame = pcmQueue.poll();
                        if (frame != null) {
                            lastEnqueuedTime = System.currentTimeMillis();
                            return frame;
                        }
                        // If queue has been empty for over 400ms, stop playback cleanly
                        if (System.currentTimeMillis() - lastEnqueuedTime > 400L) {
                            return null;
                        }
                        return new short[OPUS_FRAME_SIZE]; // Comfort silence frame during network packet jitter
                    });
                    player.startPlaying();
                } catch (Throwable t) {
                    sendDirectFallback();
                }
            }
        }

        private void sendDirectFallback() {
            OpusEncoder enc = broadcastEncoder;
            if (enc == null || enc.isClosed()) return;
            short[] frame;
            while ((frame = pcmQueue.poll()) != null) {
                try {
                    byte[] encoded = enc.encode(frame);
                    if (encoded != null && encoded.length > 0) {
                        channel.send(encoded);
                    }
                } catch (Throwable ignored) {}
            }
        }

        void close() {
            this.closed = true;
            if (player != null) {
                try {
                    player.stopPlaying();
                } catch (Throwable ignored) {}
                player = null;
            }
            if (channel != null && !channel.isClosed()) {
                try {
                    channel.flush();
                } catch (Throwable ignored) {}
            }
            pcmQueue.clear();
        }
    }
}
