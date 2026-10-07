// src/main/resources/web/js/spyglass-radar.js
class SpyglassRadar {
    constructor() {
        this.isActive = false;
        this.audioCtx = null;
        this.gainNode = null;
        this.workletNode = null;
        this.opusDecoder = window.AtlasOpusDecoder ? new window.AtlasOpusDecoder() : null;
        this.nextPlayTime = 0;
    }

    init() {
        if (!window.AtlasWS) return;

        // Auto-resume AudioContext on user interaction
        const unlockAudio = () => {
            if (this.audioCtx && this.audioCtx.state === "suspended") {
                this.audioCtx.resume();
            }
        };
        window.addEventListener("click", unlockAudio, { passive: true });
        window.addEventListener("keydown", unlockAudio, { passive: true });
        window.addEventListener("touchstart", unlockAudio, { passive: true });

        window.AtlasWS.onBinary((buffer) => {
            this._handleBinaryFrame(buffer);
        });

        window.AtlasWS.on("SPYGLASS_STATUS", (status) => {
            this._updateStatusBanner(status);
        });

        document.getElementById("btn-cancel-spyglass")?.addEventListener("click", () => {
            this.stopSurveillance();
        });
    }

    async _ensureAudioContext() {
        if (!this.audioCtx) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            this.audioCtx = new AudioContextClass({ sampleRate: 48000 });
            this.gainNode = this.audioCtx.createGain();
            this.gainNode.gain.setValueAtTime(1.0, this.audioCtx.currentTime);
            this.gainNode.connect(this.audioCtx.destination);

            if (this.opusDecoder) {
                await this.opusDecoder.init();
            }
        }
        if (this.audioCtx.state === "suspended") {
            await this.audioCtx.resume();
        }
    }

    async startSurveillance(x, y, z, radius) {
        await this._ensureAudioContext();
        this.isActive = true;
        this.nextPlayTime = 0;
        this.radarScale = parseFloat(localStorage.getItem("atlas_spyglass_scale") || "1.0");
        this.volumeGain = parseFloat(localStorage.getItem("atlas_spyglass_vol") || "1.0");
        this.currentCoords = { x, y, z, radius };

        window.AtlasWS.send({
            type: "LISTEN_START",
            x: x,
            y: y,
            z: z,
            radius: radius,
            dimension: window.AtlasMap?.currentDimension || "minecraft:overworld"
        });

        window.AtlasMap.setSpyglassSurveillance(x, z, radius * this.radarScale);
    }

    setRadarScale(scale) {
        this.radarScale = Math.max(0.5, Math.min(2.5, scale));
        localStorage.setItem("atlas_spyglass_scale", this.radarScale);
        if (this.isActive && window.AtlasMap && this.currentCoords) {
            window.AtlasMap.setSpyglassSurveillance(this.currentCoords.x, this.currentCoords.z, this.currentCoords.radius * this.radarScale);
        }
    }

    setVolume(volumePct) {
        this.volumeGain = Math.max(0.0, Math.min(2.0, volumePct / 100));
        localStorage.setItem("atlas_spyglass_vol", this.volumeGain);
        if (this.gainNode && this.audioCtx) {
            this.gainNode.gain.setValueAtTime(this.volumeGain, this.audioCtx.currentTime);
        }
    }

    stopSurveillance() {
        this.isActive = false;
        this.nextPlayTime = 0;
        window.AtlasWS.send({ type: "LISTEN_STOP" });
        window.AtlasMap.removeSpyglassSurveillance();
        document.getElementById("spyglass-banner")?.classList.add("hidden");
        document.getElementById("btn-toggle-spyglass")?.classList.remove("active");
    }

    _updateStatusBanner(status) {
        const banner = document.getElementById("spyglass-banner");
        if (!banner) return;

        if (status.active) {
            banner.classList.remove("hidden");
            document.getElementById("spyglass-rad").textContent = Math.round(status.radius);
            document.getElementById("spyglass-coords").textContent = `${Math.round(status.x)}, ${Math.round(status.y)}, ${Math.round(status.z)}`;
            document.getElementById("stat-voice-status").textContent = "LISTENING";
            document.getElementById("stat-voice-status").classList.add("text-green");
        } else {
            banner.classList.add("hidden");
            document.getElementById("stat-voice-status").textContent = "OFF";
            document.getElementById("stat-voice-status").classList.remove("text-green");
        }
    }

    _handleBinaryFrame(arrayBuffer) {
        if (!this.isActive || arrayBuffer.byteLength < 21) return;

        const view = new DataView(arrayBuffer);
        const magic = view.getUint8(0);

        // Packet 0x0B: Hardware-decoded PCM 16-bit Audio Frame (48kHz Mono)
        if (magic === 0x0B) {
            if (arrayBuffer.byteLength < 25) return;

            const attenuation = view.getFloat32(17, false); // Big Endian
            const sampleRate = view.getUint16(21, false) || 48000;
            
            // Determine PCM offset (byte 26 for 16-bit aligned packets, 25 for unpadded)
            const pcmOffset = (arrayBuffer.byteLength % 2 === 0) ? 26 : 25;
            const pcmBytes = arrayBuffer.byteLength - pcmOffset;
            const sampleCount = Math.floor(pcmBytes / 2);

            if (sampleCount <= 0) return;

            if (this.gainNode && this.audioCtx) {
                // Ensure audible gain floor (0.25) so distant players remain intelligible
                const effectiveGain = Math.max(0.25, Math.min(1.0, attenuation));
                this.gainNode.gain.setTargetAtTime(effectiveGain, this.audioCtx.currentTime, 0.04);
            }

            // Read 16-bit Little-Endian shorts safely via DataView (never throws RangeError!)
            const floatData = new Float32Array(sampleCount);
            for (let i = 0; i < sampleCount; i++) {
                floatData[i] = view.getInt16(pcmOffset + i * 2, true) / 32768.0;
            }

            this._playPcmAudio(floatData, sampleRate);
            return;
        }

        // Packet 0x0A: Raw Opus Audio Frame fallback
        if (magic === 0x0A) {
            const attenuation = view.getFloat32(17, false);
            if (this.gainNode && this.audioCtx) {
                const effectiveGain = Math.max(0.25, Math.min(1.0, attenuation));
                this.gainNode.gain.setTargetAtTime(effectiveGain, this.audioCtx.currentTime, 0.04);
            }

            const opusPayload = new Uint8Array(arrayBuffer, 21);
            if (this.opusDecoder) {
                const floatData = this.opusDecoder.decodeFrame(opusPayload);
                if (floatData) {
                    this._playPcmAudio(floatData, 48000);
                }
            }
        }
    }

    _playPcmAudio(floatData, sampleRate) {
        if (!this.audioCtx) return;

        if (this.audioCtx.state === "suspended") {
            this.audioCtx.resume().catch(() => {});
        }

        // Studio-grade scheduled buffer source playback (jitter-buffered, gapless, stereo-upmixed)
        const now = this.audioCtx.currentTime;
        if (!this.nextPlayTime || this.nextPlayTime < now) {
            this.nextPlayTime = now + 0.03; // 30ms jitter buffer
        } else if (this.nextPlayTime > now + 0.20) {
            // Buffer drift clamp! If audio packets accumulated while tab was suspended / network stalled,
            // don't let it lag seconds behind or queue indefinitely.
            this.nextPlayTime = now + 0.05;
        }

        try {
            const buffer = this.audioCtx.createBuffer(1, floatData.length, sampleRate || 48000);
            buffer.copyToChannel(floatData, 0);

            const source = this.audioCtx.createBufferSource();
            source.buffer = buffer;
            source.connect(this.gainNode);
            source.start(this.nextPlayTime);

            this.nextPlayTime += buffer.duration;
        } catch (e) {
            console.warn("[A.T.L.A.S.] Audio play error:", e);
        }
    }
}

window.AtlasSpyglass = new SpyglassRadar();
