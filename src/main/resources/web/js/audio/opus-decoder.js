// src/main/resources/web/js/audio/opus-decoder.js
(function(window) {
    "use strict";

    class AtlasOpusDecoder {
        constructor() {
            this.sampleRate = 48000;
            this.channels = 1;
            this.frameSize = 960; // 20ms frame at 48kHz
            this.isInitialized = false;
            this.webCodecsDecoder = null;
            this.decodedQueue = [];
            this.timestampMicros = 0;
        }

        async init() {
            if (this.isInitialized) return true;

            // Check if WebCodecs native AudioDecoder is available (Secure Context / HTTPS / localhost)
            if (typeof window.AudioDecoder === "function") {
                try {
                    this.webCodecsDecoder = new window.AudioDecoder({
                        output: (audioData) => {
                            const buffer = new Float32Array(audioData.numberOfFrames);
                            audioData.copyTo(buffer, { planeIndex: 0 });
                            this.decodedQueue.push(buffer);
                            audioData.close();
                        },
                        error: (err) => {
                            console.warn("[OpusDecoder] Native AudioDecoder error:", err);
                        }
                    });

                    // Construct RFC 7845 standard 19-byte OpusHead identification header
                    const opusHead = new Uint8Array(19);
                    // "OpusHead" magic
                    opusHead.set([0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64], 0);
                    opusHead[8] = 1; // Version
                    opusHead[9] = this.channels; // 1 Channel (Mono)
                    opusHead[10] = 0x00; opusHead[11] = 0x00; // Pre-skip (0)
                    // Sample rate 48000 LE (0x0000BB80)
                    opusHead[12] = 0x80; opusHead[13] = 0xBB; opusHead[14] = 0x00; opusHead[15] = 0x00;
                    opusHead[16] = 0x00; opusHead[17] = 0x00; // Output gain (0)
                    opusHead[18] = 0x00; // Channel mapping family 0

                    this.webCodecsDecoder.configure({
                        codec: "opus",
                        sampleRate: this.sampleRate,
                        numberOfChannels: this.channels,
                        description: opusHead
                    });
                } catch (e) {
                    console.warn("[OpusDecoder] WebCodecs AudioDecoder initialization failed:", e);
                    this.webCodecsDecoder = null;
                }
            }

            this.isInitialized = true;
            return true;
        }

        decodeFrame(opusBytes) {
            if (!opusBytes || opusBytes.length === 0) {
                return null;
            }

            if (this.webCodecsDecoder && this.webCodecsDecoder.state === "configured") {
                try {
                    this.timestampMicros += 20000; // 20ms in microseconds
                    const chunk = new window.EncodedAudioChunk({
                        type: "key",
                        timestamp: this.timestampMicros,
                        data: opusBytes
                    });
                    this.webCodecsDecoder.decode(chunk);

                    if (this.decodedQueue.length > 0) {
                        return this.decodedQueue.shift();
                    }
                } catch (err) {
                    console.warn("[OpusDecoder] Native decode failed:", err);
                }
            }

            // If client-side raw Opus decoding is unavailable, return silence (never synthesize fake tones)
            return null;
        }
    }

    window.AtlasOpusDecoder = AtlasOpusDecoder;
})(window);
