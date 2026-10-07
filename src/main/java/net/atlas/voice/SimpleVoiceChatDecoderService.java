// src/main/java/net/atlas/voice/SimpleVoiceChatDecoderService.java
package net.atlas.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleVoiceChatDecoderService implements VoiceDecoderService {

    private final VoicechatApi api;
    private final Map<UUID, DecoderHolder> decoders = new ConcurrentHashMap<>();

    public SimpleVoiceChatDecoderService(VoicechatApi api) {
        this.api = api;
    }

    @Override
    public short[] decode(UUID speakerUuid, byte[] opusData) {
        if (api == null || opusData == null || opusData.length == 0) {
            return null;
        }

        try {
            DecoderHolder holder = decoders.computeIfAbsent(speakerUuid, id -> {
                try {
                    OpusDecoder dec = api.createDecoder();
                    return new DecoderHolder(dec);
                } catch (Throwable t) {
                    org.bukkit.Bukkit.getLogger().warning("[A.T.L.A.S.] Could not create OpusDecoder from VoiceChatApi: " + t.getMessage());
                    return null;
                }
            });

            if (holder == null || holder.decoder.isClosed()) {
                decoders.remove(speakerUuid);
                return null;
            }

            holder.lastAccess = System.currentTimeMillis();
            synchronized (holder.decoder) {
                return holder.decoder.decode(opusData);
            }
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[A.T.L.A.S.] Error decoding Opus frame for speaker " + speakerUuid + ": " + t.getMessage());
            return null;
        }
    }

    public void cleanupIdle(long maxIdleMs) {
        long now = System.currentTimeMillis();
        decoders.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastAccess > maxIdleMs) {
                try {
                    entry.getValue().decoder.close();
                } catch (Throwable ignored) {}
                return true;
            }
            return false;
        });
    }

    @Override
    public void close() {
        for (DecoderHolder holder : decoders.values()) {
            try {
                holder.decoder.close();
            } catch (Throwable ignored) {}
        }
        decoders.clear();
    }

    private static final class DecoderHolder {
        final OpusDecoder decoder;
        volatile long lastAccess;

        DecoderHolder(OpusDecoder decoder) {
            this.decoder = decoder;
            this.lastAccess = System.currentTimeMillis();
        }
    }
}
