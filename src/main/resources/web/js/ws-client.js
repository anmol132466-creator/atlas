// src/main/resources/web/js/ws-client.js
class AtlasWebSocketClient {
    constructor() {
        this.socket = null;
        this.listeners = new Map();
        this.binaryListeners = [];
        this.token = this._extractToken();
        this.reconnectAttempts = 0;
        this.isConnected = false;
    }

    _extractToken() {
        if (window.ATLAS_SESSION_ID) return window.ATLAS_SESSION_ID;

        const match = document.cookie.match(/(?:^|;\s*)atlas_session=([^;]+)/);
        if (match) return match[1];

        const urlParams = new URLSearchParams(window.location.search);
        const queryToken = urlParams.get("token");
        if (queryToken) return queryToken;

        return "";
    }

    isOpen() {
        return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
    }

    connect() {
        if (this._reconnectTimer) {
            clearTimeout(this._reconnectTimer);
            this._reconnectTimer = null;
        }

        if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
            return; // Socket is already open or connecting
        }

        if (this.socket) {
            this.socket.onopen = null;
            this.socket.onclose = null;
            this.socket.onerror = null;
            this.socket.onmessage = null;
            try { this.socket.close(); } catch (_) {}
            this.socket = null;
        }

        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const host = window.location.hostname || "localhost";
        let portPart = "";
        if (window.location.port) {
            let port = (window.ATLAS_SINGLE_PORT || window.ATLAS_WS_PORT === window.ATLAS_HTTP_PORT)
                ? parseInt(window.location.port, 10)
                : (window.ATLAS_WS_PORT || parseInt(window.location.port, 10));
            portPart = `:${port}`;
        } else if (!window.ATLAS_SINGLE_PORT && window.ATLAS_WS_PORT && window.ATLAS_WS_PORT !== 80 && window.ATLAS_WS_PORT !== 443) {
            portPart = `:${window.ATLAS_WS_PORT}`;
        }
        const wsUrl = `${protocol}//${host}${portPart}/ws?token=${encodeURIComponent(this.token)}`;

        this._updateStatus("CONNECTING...", false);

        try {
            this.socket = new WebSocket(wsUrl);
            this.socket.binaryType = "arraybuffer";

            this.socket.onopen = () => {
                this.isConnected = true;
                this.reconnectAttempts = 0;
                this._updateStatus("CONNECTED", true);

                // Send explicit AUTH handshake frame
                this.send({ type: "AUTH", token: this.token });
                const currentDim = window.AtlasMap?.currentDimension || "minecraft:overworld";
                this.send({ type: "SUBSCRIBE_TELEMETRY", dimension: currentDim });
            };

            this.socket.onclose = () => {
                this.isConnected = false;
                this._updateStatus("DISCONNECTED", false);
                this._scheduleReconnect();
            };

            this.socket.onerror = (err) => {
                console.warn("[ATLAS WS] Error:", err);
            };

            this.socket.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    for (const fn of this.binaryListeners) {
                        try { fn(event.data); } catch (e) { console.error("[ATLAS WS] Binary listener error:", e); }
                    }
                    return;
                }

                try {
                    const msg = JSON.parse(event.data);
                    const list = this.listeners.get(msg.type);
                    if (list) {
                        for (const fn of list) {
                            try { fn(msg); } catch (e) { console.error(`[ATLAS WS] Handler error for ${msg.type}:`, e); }
                        }
                    }
                    const wildcards = this.listeners.get("*");
                    if (wildcards) {
                        for (const fn of wildcards) {
                            try { fn(msg); } catch (e) { console.error("[ATLAS WS] Wildcard handler error:", e); }
                        }
                    }
                } catch (e) {
                    console.error("[ATLAS WS] Malformed frame:", e);
                }
            };
        } catch (e) {
            console.error("[ATLAS WS] Connect exception:", e);
            this._scheduleReconnect();
        }
    }

    _scheduleReconnect() {
        if (this._reconnectTimer) {
            clearTimeout(this._reconnectTimer);
            this._reconnectTimer = null;
        }
        const timeout = Math.min(10000, 1000 * Math.pow(1.5, this.reconnectAttempts));
        this.reconnectAttempts++;
        this._reconnectTimer = setTimeout(() => this.connect(), timeout);
    }

    _updateStatus(text, ok) {
        const el = document.getElementById("connection-status");
        if (el) {
            el.textContent = text;
            el.className = ok ? "badge-status connected" : "badge-status";
        }
    }

    on(type, fn) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(fn);
    }

    onBinary(fn) {
        this.binaryListeners.push(fn);
    }

    send(data) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        }
    }

    sendBinary(buffer) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(buffer);
        }
    }
}

window.AtlasWS = new AtlasWebSocketClient();
