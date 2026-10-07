// src/main/java/net/atlas/voice/SpyglassManager.java
package net.atlas.voice;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SpyglassManager {

    private final double maxAllowedRadius;
    private final Map<String, SpyglassTarget> activeSubscriptions = new ConcurrentHashMap<>();
    private volatile VoiceDecoderService decoderService;

    public SpyglassManager(double maxAllowedRadius) {
        this.maxAllowedRadius = maxAllowedRadius;
    }

    public void setDecoderService(VoiceDecoderService service) {
        this.decoderService = service;
    }

    public void registerSubscription(String subscriberId, double x, double y, double z, double radius, Consumer<ByteBuffer> audioSink) {
        registerSubscription(subscriberId, "minecraft:overworld", x, y, z, radius, audioSink);
    }

    public void registerSubscription(String subscriberId, String dimension, double x, double y, double z, double radius, Consumer<ByteBuffer> audioSink) {
        double boundedRadius = Math.max(1.0, Math.min(radius, maxAllowedRadius));
        String dim = (dimension != null && !dimension.isBlank()) ? dimension : "minecraft:overworld";
        activeSubscriptions.put(subscriberId, new SpyglassTarget(dim, x, y, z, boundedRadius, audioSink));
    }

    public void removeSubscription(String subscriberId) {
        activeSubscriptions.remove(subscriberId);
    }

    public void clearAll() {
        activeSubscriptions.clear();
    }

    public void processAudioPacket(UUID speakerUuid, double speakerX, double speakerY, double speakerZ, byte[] opusData) {
        processAudioPacket(speakerUuid, "minecraft:overworld", speakerX, speakerY, speakerZ, opusData);
    }

    public void processAudioPacket(UUID speakerUuid, String speakerDimension, double speakerX, double speakerY, double speakerZ, byte[] opusData) {
        if (activeSubscriptions.isEmpty() || opusData == null || opusData.length == 0) {
            return;
        }

        // 1. Quick distance cull: verify at least one subscriber is in listening range and same dimension
        boolean anySubscriberInRange = false;
        for (SpyglassTarget target : activeSubscriptions.values()) {
            if (!net.atlas.telemetry.TelemetryEngine.isSameDimension(speakerDimension, target.dimension())) {
                continue;
            }
            double dx = speakerX - target.x();
            double dy = speakerY - target.y();
            double dz = speakerZ - target.z();
            double horizontalDistSq = dx * dx + dz * dz;
            double radiusSq = target.radius() * target.radius();
            if (horizontalDistSq <= radiusSq && Math.abs(dy) <= Math.max(target.radius(), 128.0)) {
                anySubscriberInRange = true;
                break;
            }
        }

        if (!anySubscriberInRange) {
            return;
        }

        // 2. Decode Opus packet once for all in-range listeners
        short[] pcm = null;
        VoiceDecoderService decoder = this.decoderService;
        if (decoder != null) {
            pcm = decoder.decode(speakerUuid, opusData);
        }

        // 3. Dispatch to all active listening targets with individualized attenuation
        for (SpyglassTarget target : activeSubscriptions.values()) {
            if (!net.atlas.telemetry.TelemetryEngine.isSameDimension(speakerDimension, target.dimension())) {
                continue;
            }
            double dx = speakerX - target.x();
            double dy = speakerY - target.y();
            double dz = speakerZ - target.z();
            double horizontalDistSq = dx * dx + dz * dz;
            double radiusSq = target.radius() * target.radius();

            if (horizontalDistSq <= radiusSq && Math.abs(dy) <= Math.max(target.radius(), 128.0)) {
                double horizontalDist = Math.sqrt(horizontalDistSq);
                float attenuation = (float) Math.max(0.25, 1.0 - (horizontalDist / target.radius()));

                ByteBuffer frame = (pcm != null && pcm.length > 0)
                        ? AudioBinaryFrame.packPcm(speakerUuid, attenuation, pcm)
                        : AudioBinaryFrame.packOpus(speakerUuid, attenuation, opusData);

                target.audioSink().accept(frame);
            }
        }
    }

    public void cleanupIdleDecoders(long maxIdleMs) {
        VoiceDecoderService decoder = this.decoderService;
        if (decoder instanceof SimpleVoiceChatDecoderService svc) {
            svc.cleanupIdle(maxIdleMs);
        }
    }

    public void close() {
        activeSubscriptions.clear();
        VoiceDecoderService decoder = this.decoderService;
        if (decoder != null) {
            decoder.close();
            this.decoderService = null;
        }
    }

    public record SpyglassTarget(
            String dimension,
            double x,
            double y,
            double z,
            double radius,
            Consumer<ByteBuffer> audioSink
    ) {}
}
