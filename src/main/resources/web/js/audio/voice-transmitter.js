// src/main/resources/web/js/audio/voice-transmitter.js
class VoiceTransmitter {
    constructor() {
        this.audioCtx = null;
        this.mediaStream = null;
        this.sourceNode = null;
        this.processorNode = null;
        this.isTransmitting = false;
        this.transmissionMode = "BROADCAST"; // "BROADCAST", "PLAYER", or "AREA"
        this.targetUuid = null;
        this.targetName = "Server";
        this.areaParams = null; // { x, y, z, radius, dimension }
        this.gain = 1.0;
        this.vuMeterCallback = null;
        this.pttActive = false;
        this.sampleQueue = [];
    }

    init() {
        // Bind UI triggers
        document.getElementById("btn-voice-broadcast")?.addEventListener("click", () => {
            this.toggleBroadcast();
        });

        document.getElementById("btn-voice-area")?.addEventListener("click", () => {
            if (this.isTransmitting && this.transmissionMode === "AREA") {
                this.stopTransmission();
            } else {
                const mapCenter = window.AtlasMap?.getCenterCoords ? window.AtlasMap.getCenterCoords() : { x: 0, z: 0 };
                const dim = window.AtlasMap?.currentDimension || "minecraft:overworld";
                this.talkToArea(mapCenter.x, 64, mapCenter.z, 50, dim, `Map Area (${Math.round(mapCenter.x)}, ${Math.round(mapCenter.z)})`);
            }
        });

        document.getElementById("btn-end-broadcast")?.addEventListener("click", () => {
            this.stopTransmission();
        });

        // Push-to-Talk hotkey 'V'
        window.addEventListener("keydown", (e) => {
            if (e.code === "KeyV" && !this.pttActive && !this._isInputElement(e.target)) {
                this.pttActive = true;
                const mode = localStorage.getItem("atlas_ptt_mode") || "hold";
                if (mode === "hold") {
                    this.startTransmission(this.transmissionMode, this.targetUuid, this.targetName);
                }
            }
        });

        window.addEventListener("keyup", (e) => {
            if (e.code === "KeyV" && this.pttActive) {
                this.pttActive = false;
                const mode = localStorage.getItem("atlas_ptt_mode") || "hold";
                if (mode === "hold" && this.isTransmitting) {
                    this.stopTransmission();
                }
            }
        });
    }

    _isInputElement(el) {
        if (!el) return false;
        const tag = el.tagName ? el.tagName.toLowerCase() : "";
        return tag === "input" || tag === "textarea" || el.isContentEditable;
    }

    setGain(value) {
        this.gain = Math.max(0.1, Math.min(3.0, value));
    }

    onVuMeter(callback) {
        this.vuMeterCallback = callback;
    }

    async toggleBroadcast() {
        if (this.isTransmitting && this.transmissionMode === "BROADCAST") {
            this.stopTransmission();
        } else {
            await this.startTransmission("BROADCAST", null, "Everyone (Server Broadcast)");
        }
    }

    async talkToPlayer(uuid, name) {
        if (this.isTransmitting && this.transmissionMode === "PLAYER" && this.targetUuid === uuid) {
            this.stopTransmission();
        } else {
            await this.startTransmission("PLAYER", uuid, name);
        }
    }

    async talkToArea(x = 0, y = 64, z = 0, radius = 50, dimension = "minecraft:overworld", label = "Local Area") {
        if (this.isTransmitting && this.transmissionMode === "AREA") {
            this.stopTransmission();
        } else {
            this.areaParams = { x, y, z, radius, dimension };
            await this.startTransmission("AREA", null, label);
        }
    }

    async startTransmission(mode, targetUuid = null, targetName = "Target") {
        if (!window.AtlasWS || !window.AtlasWS.isOpen()) {
            if (window.AtlasInspector?.showToast) {
                window.AtlasInspector.showToast("danger", "WebSocket disconnected. Reconnecting to server...");
            }
            window.AtlasWS?.connect();
            return;
        }

        if (this.isTransmitting) {
            this.stopTransmission();
        }

        this.transmissionMode = mode;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.sampleQueue = [];

        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                    channelCount: 1,
                    sampleRate: 48000
                }
            });

            this.mediaStream = stream;
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            this.audioCtx = new AudioContextClass({ sampleRate: 48000 });
            if (this.audioCtx.state === "suspended") {
                await this.audioCtx.resume();
            }

            this.sourceNode = this.audioCtx.createMediaStreamSource(stream);

            // ScriptProcessorNode with bufferSize=2048
            const bufferSize = 2048;
            this.processorNode = this.audioCtx.createScriptProcessor(bufferSize, 1, 1);

            this.processorNode.onaudioprocess = (e) => {
                if (!this.isTransmitting) return;

                const inputChannel = e.inputBuffer.getChannelData(0);
                let sumSquares = 0.0;

                for (let i = 0; i < inputChannel.length; i++) {
                    let s = inputChannel[i] * this.gain;
                    if (s > 1.0) s = 1.0;
                    else if (s < -1.0) s = -1.0;
                    sumSquares += s * s;
                    this.sampleQueue.push(s);
                }

                // Volume level / RMS calculation
                const rms = Math.sqrt(sumSquares / inputChannel.length);
                if (this.vuMeterCallback) {
                    this.vuMeterCallback(Math.min(1.0, rms * 4.0));
                }

                // Chunk into exact 960-sample (20ms at 48kHz) frames for standard Opus compliance
                while (this.sampleQueue.length >= 960) {
                    const frameSamples = this.sampleQueue.splice(0, 960);
                    const pcmBuffer = new ArrayBuffer(960 * 2);
                    const pcmView = new DataView(pcmBuffer);

                    for (let j = 0; j < 960; j++) {
                        const sample = frameSamples[j];
                        pcmView.setInt16(j * 2, sample < 0 ? sample * 32768 : sample * 32767, true);
                    }

                    if (window.AtlasWS?.isOpen()) {
                        this._sendBinaryFrame(pcmBuffer);
                    }
                }
            };

            this.sourceNode.connect(this.processorNode);
            const muteGain = this.audioCtx.createGain();
            muteGain.gain.setValueAtTime(0, this.audioCtx.currentTime);
            this.processorNode.connect(muteGain);
            muteGain.connect(this.audioCtx.destination);

            this.isTransmitting = true;
            this._updateUi(true);

            if (window.AtlasInspector?.showToast) {
                let label = "Live Server Broadcast";
                if (mode === "PLAYER") label = `Direct Comms with ${targetName}`;
                else if (mode === "AREA") label = `Area Transmission (${targetName})`;
                window.AtlasInspector.showToast("danger", `TRANSMITTING: ${label}`);
            }
        } catch (err) {
            console.error("[Voice Transmitter] Failed to acquire microphone:", err);
            if (window.AtlasInspector?.showToast) {
                window.AtlasInspector.showToast("danger", "Microphone access denied or audio input unavailable.");
            }
            this.stopTransmission();
        }
    }

    _sendBinaryFrame(pcmArrayBuffer) {
        let modeByte = 0;
        let extraHeaderLen = 0;
        if (this.transmissionMode === "PLAYER") {
            modeByte = 1;
            extraHeaderLen = 16; // 16-byte UUID
        } else if (this.transmissionMode === "AREA") {
            modeByte = 2;
            extraHeaderLen = 28; // double x (8), double y (8), double z (8), float radius (4)
        }

        const headerLen = 1 + 1 + extraHeaderLen + 1; // 0x0C + modeByte + extra + isPcmByte
        const totalLen = headerLen + pcmArrayBuffer.byteLength;
        const outBuffer = new ArrayBuffer(totalLen);
        const outView = new DataView(outBuffer);

        outView.setUint8(0, 0x0C); // Magic byte for voice transmit
        outView.setUint8(1, modeByte);

        let offset = 2;
        if (modeByte === 1 && this.targetUuid) {
            try {
                const clean = this.targetUuid.replace(/-/g, "");
                const mostHigh = parseInt(clean.substring(0, 8), 16);
                const mostLow = parseInt(clean.substring(8, 16), 16);
                const leastHigh = parseInt(clean.substring(16, 24), 16);
                const leastLow = parseInt(clean.substring(24, 32), 16);
                outView.setUint32(offset, mostHigh, false);
                outView.setUint32(offset + 4, mostLow, false);
                outView.setUint32(offset + 8, leastHigh, false);
                outView.setUint32(offset + 12, leastLow, false);
            } catch (_) {}
            offset += 16;
        } else if (modeByte === 2) {
            const ap = this.areaParams || { x: 0, y: 64, z: 0, radius: 50 };
            outView.setFloat64(offset, ap.x || 0, false);
            outView.setFloat64(offset + 8, ap.y || 64, false);
            outView.setFloat64(offset + 16, ap.z || 0, false);
            outView.setFloat32(offset + 24, ap.radius || 50, false);
            offset += 28;
        }

        outView.setUint8(offset, 1); // 1 = 16-bit PCM format
        offset += 1;

        new Uint8Array(outBuffer, offset).set(new Uint8Array(pcmArrayBuffer));
        window.AtlasWS?.sendBinary(outBuffer);
    }

    stopTransmission() {
        if (!this.isTransmitting && !this.mediaStream) return;

        this.isTransmitting = false;
        this.sampleQueue = [];

        if (this.processorNode) {
            try { this.processorNode.disconnect(); } catch (_) {}
            this.processorNode = null;
        }
        if (this.sourceNode) {
            try { this.sourceNode.disconnect(); } catch (_) {}
            this.sourceNode = null;
        }
        if (this.mediaStream) {
            this.mediaStream.getTracks().forEach(t => t.stop());
            this.mediaStream = null;
        }
        if (this.audioCtx && this.audioCtx.state !== "closed") {
            try { this.audioCtx.close(); } catch (_) {}
            this.audioCtx = null;
        }

        if (this.vuMeterCallback) {
            this.vuMeterCallback(0);
        }

        this._updateUi(false);

        if (window.AtlasInspector?.showToast) {
            window.AtlasInspector.showToast("info", "Voice transmission ended.");
        }
    }

    _updateUi(active) {
        const hud = document.getElementById("voice-transmit-hud");
        const btnBroadcast = document.getElementById("btn-voice-broadcast");
        const btnArea = document.getElementById("btn-voice-area");
        const statusLabel = document.getElementById("transmit-target-label");

        if (hud) {
            hud.classList.toggle("hidden", !active);
        }
        if (btnBroadcast) {
            btnBroadcast.classList.toggle("broadcasting", active && this.transmissionMode === "BROADCAST");
        }
        if (btnArea) {
            btnArea.classList.toggle("broadcasting", active && this.transmissionMode === "AREA");
        }
        if (statusLabel) {
            if (this.transmissionMode === "BROADCAST") {
                statusLabel.textContent = "BROADCAST: ALL ONLINE PLAYERS";
            } else if (this.transmissionMode === "PLAYER") {
                statusLabel.textContent = `DIRECT COMMS: ${this.targetName}`;
            } else if (this.transmissionMode === "AREA") {
                const rad = this.areaParams?.radius || 50;
                statusLabel.textContent = `AREA PROXIMITY (${rad}m): ${this.targetName}`;
            }
        }

        const inspTalkBtn = document.getElementById("btn-mod-talk");
        if (inspTalkBtn) {
            inspTalkBtn.classList.toggle("active-talking", active && this.transmissionMode === "PLAYER");
        }
        const inspAreaBtn = document.getElementById("btn-mod-talk-area");
        if (inspAreaBtn) {
            inspAreaBtn.classList.toggle("active-talking", active && this.transmissionMode === "AREA");
        }
    }
}

window.AtlasVoice = new VoiceTransmitter();
