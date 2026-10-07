// src/main/java/net/atlas/voice/VoiceDecoderService.java
package net.atlas.voice;

import java.util.UUID;

public interface VoiceDecoderService {

    /**
     * Decodes raw Opus frame payload bytes into 16-bit 48kHz mono PCM audio samples.
     *
     * @param speakerUuid UUID of the speaking player
     * @param opusData    Raw Opus packet bytes
     * @return Array of signed 16-bit PCM samples, or null if decoding failed
     */
    short[] decode(UUID speakerUuid, byte[] opusData);

    /**
     * Closes and releases all underlying native Opus decoder state and memory allocations.
     */
    void close();
}
