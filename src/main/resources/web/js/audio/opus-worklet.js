// src/main/resources/web/js/audio/opus-worklet.js
class AtlasAudioWorkletProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.bufferQueue = [];
        this.currentChunk = null;
        this.chunkOffset = 0;

        this.port.onmessage = (event) => {
            if (event.data && event.data instanceof Float32Array) {
                // Limit maximum queue size to avoid latency accumulation (max 10 chunks = 200ms)
                if (this.bufferQueue.length > 10) {
                    this.bufferQueue.shift();
                }
                this.bufferQueue.push(event.data);
            }
        };
    }

    process(inputs, outputs, parameters) {
        const output = outputs[0];
        const channel = output[0];
        let written = 0;

        while (written < channel.length) {
            if (!this.currentChunk || this.chunkOffset >= this.currentChunk.length) {
                if (this.bufferQueue.length === 0) {
                    channel.fill(0, written);
                    break;
                }
                this.currentChunk = this.bufferQueue.shift();
                this.chunkOffset = 0;
            }

            const needed = channel.length - written;
            const available = this.currentChunk.length - this.chunkOffset;
            const toCopy = Math.min(needed, available);

            channel.set(this.currentChunk.subarray(this.chunkOffset, this.chunkOffset + toCopy), written);
            this.chunkOffset += toCopy;
            written += toCopy;
        }

        return true;
    }
}

registerProcessor("atlas-audio-processor", AtlasAudioWorkletProcessor);
