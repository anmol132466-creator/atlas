// src/main/java/net/atlas/voice/AudioBinaryFrame.java
package net.atlas.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class AudioBinaryFrame {

    public static final byte PACKET_ID_OPUS = 0x0A;
    public static final byte PACKET_ID_PCM = 0x0B;

    private AudioBinaryFrame() {}

    public static ByteBuffer packOpus(UUID speakerUuid, float attenuation, byte[] opusData) {
        int length = 21 + (opusData != null ? opusData.length : 0);
        ByteBuffer buffer = ByteBuffer.allocate(length);

        buffer.put(PACKET_ID_OPUS);
        buffer.putLong(speakerUuid.getMostSignificantBits());
        buffer.putLong(speakerUuid.getLeastSignificantBits());
        buffer.putFloat(attenuation);

        if (opusData != null && opusData.length > 0) {
            buffer.put(opusData);
        }

        buffer.flip();
        return buffer;
    }

    public static ByteBuffer packPcm(UUID speakerUuid, float attenuation, short[] pcm) {
        int sampleCount = (pcm != null) ? pcm.length : 0;
        int length = 26 + (sampleCount * 2);
        ByteBuffer buffer = ByteBuffer.allocate(length);

        // Header: Big Endian metadata
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(PACKET_ID_PCM);
        buffer.putLong(speakerUuid.getMostSignificantBits());
        buffer.putLong(speakerUuid.getLeastSignificantBits());
        buffer.putFloat(attenuation);
        buffer.putShort((short) 48000);
        buffer.putShort((short) 1);
        buffer.put((byte) 0); // 1-byte alignment padding so PCM samples start at byte 26 (16-bit aligned)

        // Samples: Little Endian for direct typed view in browser JS
        if (pcm != null && sampleCount > 0) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcm) {
                buffer.putShort(sample);
            }
        }

        buffer.flip();
        return buffer;
    }

    public static ByteBuffer pack(UUID speakerUuid, float attenuation, byte[] opusData) {
        return packOpus(speakerUuid, attenuation, opusData);
    }
}
