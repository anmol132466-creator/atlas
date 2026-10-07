// src/main/resources/web/js/app.js
class AtlasApplication {
    constructor() {
        this.settingsModal = null;
        this.pingTimer = null;
        this.terminalHistory = [];
        this.terminalHistoryIndex = -1;
    }

    showToast(msg, type = "info") {
        if (window.AtlasInspector?.showToast) {
            window.AtlasInspector.showToast(type, msg);
        } else {
            console.log(`[A.T.L.A.S. Toast] ${msg}`);
        }
    }

    toggleSettingsModal(show) {
        if (!this.settingsModal) {
            this.settingsModal = document.getElementById("settings-modal");
        }
        if (!this.settingsModal) return;

        const isCurrentlyHidden = this.settingsModal.classList.contains("hidden");
        const nextState = (show !== undefined) ? !show : !isCurrentlyHidden;

        if (nextState) {
            this.settingsModal.classList.add("hidden");
        } else {
            this.loadSettingsIntoUI();
            this.settingsModal.classList.remove("hidden");
        }
    }

    initSecurityStatus() {
        const badge = document.getElementById("security-status");
        const badgeText = document.getElementById("security-status-text");
        if (!badge) return;

        const isSecure = window.isSecureContext === true;
        const isLocal = window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";

        if (isSecure || isLocal) {
            badge.classList.add("secure-context");
            if (badgeText) badgeText.textContent = "SECURE CONTEXT";
            badge.setAttribute("title", "RFC 6761 Loopback Secure Context active. Click for security details.");
        } else {
            badge.classList.add("token-secured");
            if (badgeText) badgeText.textContent = "TOKEN SECURED";
            badge.setAttribute("title", "Direct IP mode: HMAC authenticated. Click for details & tips.");
        }

        badge.addEventListener("click", () => {
            this.toggleSecurityModal(true);
        });

        const bannerTitle = document.getElementById("sec-banner-title");
        const bannerDesc = document.getElementById("sec-banner-desc");
        const bannerIcon = document.getElementById("sec-banner-icon");

        if (bannerTitle && bannerDesc && bannerIcon) {
            if (isSecure || isLocal) {
                bannerTitle.textContent = "NATIVE SECURE CONTEXT ACTIVE";
                bannerTitle.style.color = "#34d399";
                bannerIcon.textContent = "🛡️";
                bannerDesc.textContent = `Connected to ${window.location.host}. Browser privileges (Web Audio, Clipboard) fully active with zero security warnings.`;
            } else {
                bannerTitle.textContent = "AUTHENTICATED IP STREAM (HMAC-SHA256)";
                bannerTitle.style.color = "#fbbf24";
                bannerIcon.textContent = "🔒";
                bannerDesc.textContent = `Connected via raw IP (${window.location.host}). Browser flags raw HTTP as 'Not secure', but all operations are isolated and protected by one-time token authorization.`;
            }
        }
    }

    toggleSecurityModal(show) {
        const modal = document.getElementById("security-modal");
        if (!modal) return;
        const isHidden = modal.classList.contains("hidden");
        const nextState = (show !== undefined) ? !show : !isHidden;
        if (nextState) {
            modal.classList.add("hidden");
        } else {
            modal.classList.remove("hidden");
        }
    }

    loadSettingsIntoUI() {
        // Client settings
        const chkOffline = document.getElementById("setting-show-offline");
        const chkLabels = document.getElementById("setting-show-labels");
        const chkCones = document.getElementById("setting-show-cones");
        const chkPulses = document.getElementById("setting-show-block-pulses");
        const chkChatOverhead = document.getElementById("setting-show-chat-overhead");
        const rngChatDuration = document.getElementById("setting-chat-duration");
        const chkChatFeed = document.getElementById("setting-show-chat-feed");

        if (chkOffline) chkOffline.checked = localStorage.getItem("atlas_show_offline") !== "false";
        if (chkLabels) chkLabels.checked = localStorage.getItem("atlas_show_labels") !== "false";
        if (chkCones) chkCones.checked = localStorage.getItem("atlas_show_cones") !== "false";
        if (chkPulses) chkPulses.checked = localStorage.getItem("atlas_show_block_pulses") !== "false";
        if (chkChatOverhead) chkChatOverhead.checked = localStorage.getItem("atlas_show_chat_overhead") !== "false";
        if (chkChatFeed) chkChatFeed.checked = localStorage.getItem("atlas_show_chat_feed") !== "false";

        const chatDur = localStorage.getItem("atlas_chat_duration") || "6";
        if (rngChatDuration) {
            rngChatDuration.value = chatDur;
            const valEl = document.getElementById("val-chat-duration");
            if (valEl) valEl.textContent = `${chatDur}s`;
        }

        // Surveillance & Audio settings
        const rngRadarScale = document.getElementById("setting-radar-scale");
        const rngSpyglass = document.getElementById("setting-spyglass-radius");
        const rngSpyglassVol = document.getElementById("setting-spyglass-volume");
        const rngVoiceGain = document.getElementById("setting-voice-mic-gain");
        const selVoiceMode = document.getElementById("setting-voice-tx-mode");
        const chkTrails = document.getElementById("setting-show-trails");
        const rngTrail = document.getElementById("setting-trail-length");

        const radarScale = localStorage.getItem("atlas_spyglass_scale") || "1.0";
        if (rngRadarScale) {
            rngRadarScale.value = radarScale;
            const valEl = document.getElementById("val-radar-scale");
            if (valEl) valEl.textContent = `${Number(radarScale).toFixed(1)}x`;
        }

        const spyglassRad = localStorage.getItem("atlas_spyglass_rad") || "25";
        if (rngSpyglass) {
            rngSpyglass.value = spyglassRad;
            const valEl = document.getElementById("val-spyglass-radius");
            if (valEl) valEl.textContent = `${spyglassRad}m`;
        }

        const spyglassVol = localStorage.getItem("atlas_spyglass_vol_pct") || "80";
        if (rngSpyglassVol) {
            rngSpyglassVol.value = spyglassVol;
            const valEl = document.getElementById("val-spyglass-volume");
            if (valEl) valEl.textContent = `${spyglassVol}%`;
        }

        const voiceGain = localStorage.getItem("atlas_voice_gain") || "1.0";
        if (rngVoiceGain) {
            rngVoiceGain.value = voiceGain;
            const valEl = document.getElementById("val-voice-mic-gain");
            if (valEl) valEl.textContent = `${Number(voiceGain).toFixed(1)}x`;
        }

        const txMode = localStorage.getItem("atlas_ptt_mode") || "hold";
        if (selVoiceMode) {
            selVoiceMode.value = txMode;
        }

        if (chkTrails) chkTrails.checked = localStorage.getItem("atlas_show_trails") !== "false";
        const trailLen = localStorage.getItem("atlas_trail_len") || "30";
        if (rngTrail) {
            rngTrail.value = trailLen;
            const valEl = document.getElementById("val-trail-length");
            if (valEl) valEl.textContent = `${trailLen} pts`;
        }

        // Request server config if WS is connected
        if (window.AtlasWS?.isOpen()) {
            window.AtlasWS.send({ type: "FETCH_CONFIG" });
        }
    }

    saveSettingsFromUI() {
        const chkOffline = document.getElementById("setting-show-offline");
        const chkLabels = document.getElementById("setting-show-labels");
        const chkCones = document.getElementById("setting-show-cones");
        const chkPulses = document.getElementById("setting-show-block-pulses");
        const chkChatOverhead = document.getElementById("setting-show-chat-overhead");
        const rngChatDuration = document.getElementById("setting-chat-duration");
        const chkChatFeed = document.getElementById("setting-show-chat-feed");

        const rngRadarScale = document.getElementById("setting-radar-scale");
        const rngSpyglass = document.getElementById("setting-spyglass-radius");
        const rngSpyglassVol = document.getElementById("setting-spyglass-volume");
        const rngVoiceGain = document.getElementById("setting-voice-mic-gain");
        const selVoiceMode = document.getElementById("setting-voice-tx-mode");
        const chkTrails = document.getElementById("setting-show-trails");
        const rngTrail = document.getElementById("setting-trail-length");

        const rngHz = document.getElementById("setting-telemetry-hz");
        const chkUnloaded = document.getElementById("setting-render-unloaded");
        const rngHill = document.getElementById("setting-hillshading-intensity");

        // Apply Client Display settings
        if (chkOffline && window.AtlasMap) {
            window.AtlasMap.toggleOfflineMarkers(chkOffline.checked);
        }
        if (chkLabels && window.AtlasTelemetry) {
            window.AtlasTelemetry.toggleLabels(chkLabels.checked);
        }
        if (chkCones && window.AtlasTelemetry) {
            window.AtlasTelemetry.toggleCones(chkCones.checked);
        }
        if (chkPulses) {
            localStorage.setItem("atlas_show_block_pulses", chkPulses.checked ? "true" : "false");
        }
        if (chkChatOverhead && window.AtlasTelemetry) {
            window.AtlasTelemetry.toggleChatOverhead(chkChatOverhead.checked);
        }
        if (rngChatDuration && window.AtlasTelemetry) {
            window.AtlasTelemetry.setChatDuration(parseInt(rngChatDuration.value, 10));
        }
        if (chkChatFeed) {
            localStorage.setItem("atlas_show_chat_feed", chkChatFeed.checked ? "true" : "false");
            const drawer = document.getElementById("surveillance-chat-drawer");
            if (drawer) drawer.style.display = chkChatFeed.checked ? "" : "none";
        }

        // Apply Surveillance & Audio settings
        if (rngRadarScale && window.AtlasSpyglass) {
            window.AtlasSpyglass.setRadarScale(parseFloat(rngRadarScale.value));
        }
        if (rngSpyglass) {
            localStorage.setItem("atlas_spyglass_rad", rngSpyglass.value);
        }
        if (rngSpyglassVol && window.AtlasSpyglass) {
            const vol = parseInt(rngSpyglassVol.value, 10);
            localStorage.setItem("atlas_spyglass_vol_pct", vol);
            window.AtlasSpyglass.setVolume(vol);
        }
        if (rngVoiceGain && window.AtlasVoice) {
            const gain = parseFloat(rngVoiceGain.value);
            localStorage.setItem("atlas_voice_gain", gain);
            window.AtlasVoice.setGain(gain);
        }
        if (selVoiceMode) {
            localStorage.setItem("atlas_ptt_mode", selVoiceMode.value);
        }
        if (chkTrails && window.AtlasTelemetry) {
            window.AtlasTelemetry.toggleTrails(chkTrails.checked);
        }
        if (rngTrail && window.AtlasTelemetry) {
            window.AtlasTelemetry.setTrailLength(parseInt(rngTrail.value, 10));
        }

        // Send Server Config to Backend
        if (window.AtlasWS?.isOpen() && rngHz && chkUnloaded && rngHill) {
            window.AtlasWS.send({
                type: "UPDATE_CONFIG",
                telemetryHz: parseInt(rngHz.value, 10),
                renderUnloaded: chkUnloaded.checked,
                hillshadingIntensity: parseFloat(rngHill.value)
            });
        }

        this.showToast("Settings and configurations applied", "success");
        this.toggleSettingsModal(false);
    }

    resetDefaults() {
        localStorage.removeItem("atlas_show_offline");
        localStorage.removeItem("atlas_show_labels");
        localStorage.removeItem("atlas_show_cones");
        localStorage.removeItem("atlas_show_block_pulses");
        localStorage.removeItem("atlas_show_chat_overhead");
        localStorage.removeItem("atlas_chat_duration");
        localStorage.removeItem("atlas_show_chat_feed");
        localStorage.removeItem("atlas_spyglass_scale");
        localStorage.removeItem("atlas_spyglass_rad");
        localStorage.removeItem("atlas_spyglass_vol_pct");
        localStorage.removeItem("atlas_voice_gain");
        localStorage.removeItem("atlas_ptt_mode");
        localStorage.removeItem("atlas_show_trails");
        localStorage.removeItem("atlas_trail_len");

        this.loadSettingsIntoUI();
        this.showToast("Settings reset to defaults", "info");
    }

    _startPingLoop() {
        if (this.pingTimer) clearInterval(this.pingTimer);
        this.pingTimer = setInterval(() => {
            if (window.AtlasWS?.isOpen()) {
                window.AtlasWS.send({ type: "PING", time: Date.now() });
            }
        }, 4000);
    }

    logToTerminal(text, type = "normal") {
        const out = document.getElementById("terminal-output-container");
        if (!out) return;

        const line = document.createElement("div");
        line.className = "terminal-line";
        if (type === "success") line.classList.add("term-line-success");
        else if (type === "queued") line.classList.add("term-line-queued");
        else if (type === "error") line.classList.add("term-line-error");

        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        line.textContent = `[${timeStr}] ${text}`;
        out.appendChild(line);

        while (out.children.length > 200) {
            out.removeChild(out.firstChild);
        }
        out.scrollTop = out.scrollHeight;
    }
}

window.AtlasApp = new AtlasApplication();

document.addEventListener("DOMContentLoaded", () => {
    console.log("[A.T.L.A.S.] Initializing surveillance dashboard...");

    // 1. Initialize Core Engines with isolation
    try { window.AtlasMap?.init(); } catch (e) { console.error("[A.T.L.A.S.] Map init error:", e); }
    try { window.AtlasWS?.connect(); } catch (e) { console.error("[A.T.L.A.S.] WS connect error:", e); }
    try { window.AtlasTelemetry?.init(); } catch (e) { console.error("[A.T.L.A.S.] Telemetry init error:", e); }
    try { window.AtlasInspector?.init(); } catch (e) { console.error("[A.T.L.A.S.] Inspector init error:", e); }
    try { window.AtlasOffline?.init(); } catch (e) { console.error("[A.T.L.A.S.] Offline init error:", e); }
    try { window.AtlasSpyglass?.init(); } catch (e) { console.error("[A.T.L.A.S.] Spyglass init error:", e); }
    try { window.AtlasVoice?.init(); } catch (e) { console.error("[A.T.L.A.S.] Voice transmitter init error:", e); }
    try { window.AtlasApp?.initSecurityStatus(); } catch (e) { console.error("[A.T.L.A.S.] Security status init error:", e); }

    // Connect VU meter to HUD fill
    window.AtlasVoice?.onVuMeter((level) => {
        const bar = document.getElementById("transmit-vu-level");
        if (bar) {
            bar.style.width = `${Math.min(100, Math.round(level * 100))}%`;
        }
    });

    // 2. Dimension Switching
    document.querySelectorAll(".dim-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".dim-btn").forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
            const dim = btn.getAttribute("data-dim");
            window.AtlasMap?.setDimension(dim);
            const dimName = btn.getAttribute("data-dim-name") || btn.textContent.trim() || "Dimension";
            window.AtlasInspector?.showToast("info", `Switched surveillance viewport to ${dimName}`);
        });
    });

    // 3. Sidebar Tab Switching
    document.querySelectorAll(".tab-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
            document.querySelectorAll(".tab-content").forEach(c => c.classList.remove("active"));

            btn.classList.add("active");
            const tabId = "pane-" + btn.getAttribute("data-tab");
            document.getElementById(tabId)?.classList.add("active");

            if (btn.getAttribute("data-tab") === "offline") {
                window.AtlasOffline?.fetchRoster();
            } else if (btn.getAttribute("data-tab") === "actions") {
                window.AtlasOffline?.fetchPendingActions();
            }
        });
    });

    // 4. Audio Monitor Surveillance Button Toggle
    const spyBtn = document.getElementById("btn-toggle-spyglass");
    if (spyBtn) {
        spyBtn.addEventListener("click", () => {
            if (window.AtlasSpyglass?.isActive) {
                window.AtlasSpyglass.stopSurveillance();
            } else if (window.AtlasMap) {
                window.AtlasMap.spyglassPlacingMode = !window.AtlasMap.spyglassPlacingMode;
                if (window.AtlasMap.spyglassPlacingMode) {
                    spyBtn.classList.add("active");
                    window.AtlasInspector?.showToast("info", "Click anywhere on the world map to place the Audio Monitor radar.");
                } else {
                    spyBtn.classList.remove("active");
                    window.AtlasInspector?.showToast("info", "Audio Monitor placement canceled.");
                }
            }
        });
    }

    // 5. Settings Modal Event Handlers
    document.getElementById("btn-open-settings")?.addEventListener("click", () => {
        window.AtlasApp.toggleSettingsModal(true);
    });

    document.getElementById("btn-close-settings")?.addEventListener("click", () => {
        window.AtlasApp.toggleSettingsModal(false);
    });

    document.getElementById("settings-modal")?.addEventListener("click", (e) => {
        if (e.target.id === "settings-modal") {
            window.AtlasApp.toggleSettingsModal(false);
        }
    });

    // Security Modal Event Handlers
    document.getElementById("btn-close-security")?.addEventListener("click", () => {
        window.AtlasApp.toggleSecurityModal(false);
    });
    document.getElementById("btn-dismiss-security")?.addEventListener("click", () => {
        window.AtlasApp.toggleSecurityModal(false);
    });
    document.getElementById("security-modal")?.addEventListener("click", (e) => {
        if (e.target.id === "security-modal") {
            window.AtlasApp.toggleSecurityModal(false);
        }
    });

    document.getElementById("btn-save-settings")?.addEventListener("click", () => {
        window.AtlasApp.saveSettingsFromUI();
    });

    document.getElementById("btn-reset-settings")?.addEventListener("click", () => {
        window.AtlasApp.resetDefaults();
    });

    // Modal Tabs Switching
    document.querySelectorAll(".modal-tab-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".modal-tab-btn").forEach(b => b.classList.remove("active"));
            document.querySelectorAll(".modal-pane").forEach(p => p.classList.remove("active"));

            btn.classList.add("active");
            const targetId = "pane-setting-" + btn.getAttribute("data-modaltab");
            document.getElementById(targetId)?.classList.add("active");
        });
    });

    // Settings Sliders Value Badges
    const bindSliderVal = (sliderId, badgeId, suffix, decimals = 0) => {
        const slider = document.getElementById(sliderId);
        const badge = document.getElementById(badgeId);
        if (slider && badge) {
            slider.addEventListener("input", () => {
                const val = decimals > 0 ? Number(slider.value).toFixed(decimals) : slider.value;
                badge.textContent = `${val}${suffix}`;
            });
        }
    };
    bindSliderVal("setting-trail-length", "val-trail-length", " pts");
    bindSliderVal("setting-spyglass-radius", "val-spyglass-radius", "m");
    bindSliderVal("setting-spyglass-volume", "val-spyglass-volume", "%");
    bindSliderVal("setting-radar-scale", "val-radar-scale", "x", 1);
    bindSliderVal("setting-voice-mic-gain", "val-voice-mic-gain", "x", 1);
    bindSliderVal("setting-chat-duration", "val-chat-duration", "s");
    bindSliderVal("setting-telemetry-hz", "val-telemetry-hz", " Hz");
    bindSliderVal("setting-hillshading-intensity", "val-hillshading-intensity", "");

    // 6. Live Chat Drawer Toggle & Actions
    const chatDrawer = document.getElementById("surveillance-chat-drawer");
    const chatToggleBtn = document.getElementById("btn-toggle-chat-feed");
    const chatHeader = document.getElementById("chat-drawer-header");
    const chatUnreadBadge = document.getElementById("badge-chat-unread");
    const clearChatBtn = document.getElementById("btn-clear-chat");

    const toggleChatDrawer = () => {
        if (!chatDrawer) return;
        chatDrawer.classList.toggle("collapsed");
        if (!chatDrawer.classList.contains("collapsed")) {
            if (chatUnreadBadge) {
                chatUnreadBadge.textContent = "0";
                chatUnreadBadge.classList.add("hidden");
            }
        }
    };

    chatToggleBtn?.addEventListener("click", toggleChatDrawer);
    chatHeader?.addEventListener("click", toggleChatDrawer);

    clearChatBtn?.addEventListener("click", (e) => {
        e.stopPropagation();
        const list = document.getElementById("surveillance-chat-list");
        if (list) {
            list.innerHTML = `<li class="chat-feed-item text-muted">Surveillance feed cleared.</li>`;
        }
    });

    // 7. Tactical Server Command Terminal
    const termDrawer = document.getElementById("surveillance-terminal-drawer");
    const termToggleBtn = document.getElementById("btn-toggle-terminal");
    const termCloseBtn = document.getElementById("btn-close-terminal");
    const termForm = document.getElementById("terminal-form");
    const termInput = document.getElementById("terminal-input");

    termToggleBtn?.addEventListener("click", () => {
        if (!termDrawer) return;
        termDrawer.classList.toggle("hidden");
        if (!termDrawer.classList.contains("hidden") && termInput) {
            termInput.focus();
        }
    });

    termCloseBtn?.addEventListener("click", () => {
        termDrawer?.classList.add("hidden");
    });

    termForm?.addEventListener("submit", (e) => {
        e.preventDefault();
        const cmd = termInput?.value?.trim();
        if (!cmd) return;

        window.AtlasApp.terminalHistory.push(cmd);
        window.AtlasApp.terminalHistoryIndex = window.AtlasApp.terminalHistory.length;

        window.AtlasApp.logToTerminal(`> ${cmd}`);

        if (window.AtlasWS?.isOpen()) {
            const activeTarget = window.AtlasInspector?.currentUuid || window.AtlasOffline?.selectedOfflineUuid || undefined;
            const targetName = window.AtlasInspector?.currentPlayerData?.name || window.AtlasOffline?.selectedProfile?.name || undefined;
            window.AtlasWS.send({
                type: "EXECUTE_COMMAND",
                command: cmd,
                target: activeTarget,
                targetName: targetName,
                queueIfOffline: true
            });
            setTimeout(() => window.AtlasOffline?.fetchPendingActions(), 250);
        } else {
            window.AtlasApp.logToTerminal("[ERROR] Offline or disconnected from server WebSocket.", "error");
        }

        if (termInput) termInput.value = "";
    });

    termInput?.addEventListener("keydown", (e) => {
        const hist = window.AtlasApp.terminalHistory;
        if (e.key === "ArrowUp") {
            if (hist.length > 0 && window.AtlasApp.terminalHistoryIndex > 0) {
                window.AtlasApp.terminalHistoryIndex--;
                termInput.value = hist[window.AtlasApp.terminalHistoryIndex];
            }
            e.preventDefault();
        } else if (e.key === "ArrowDown") {
            if (window.AtlasApp.terminalHistoryIndex < hist.length - 1) {
                window.AtlasApp.terminalHistoryIndex++;
                termInput.value = hist[window.AtlasApp.terminalHistoryIndex];
            } else {
                window.AtlasApp.terminalHistoryIndex = hist.length;
                termInput.value = "";
            }
            e.preventDefault();
        }
    });

    // Listen for COMMAND_RESULT & ACTION_ACK responses to log to terminal
    window.AtlasWS?.on("COMMAND_RESULT", (msg) => {
        const res = msg.result || {};
        if (res.message) {
            const lineType = res.success ? (res.offlineQueued ? "queued" : "success") : "error";
            window.AtlasApp.logToTerminal(`[SERVER] ${res.message}`, lineType);
        }
        if (res.offlineQueued) {
            window.AtlasOffline?.fetchPendingActions();
        }
    });

    window.AtlasWS?.on("ACTION_ACK", (ack) => {
        if (ack.action === "COMMAND") {
            // Already handled via COMMAND_RESULT
            return;
        }
        if (ack.message) {
            const lineType = ack.success ? (ack.offlineQueued ? "queued" : "success") : "error";
            window.AtlasApp.logToTerminal(`[SERVER] ${ack.message}`, lineType);
        }
        if (ack.action === "QUEUE_OFFLINE_ACTION" || ack.action === "CANCEL_PENDING_ACTION") {
            window.AtlasOffline?.fetchPendingActions();
        }
    });

    // 8. Update TPS, Server Version, World Spawn, Seed & Version Detection
    window.AtlasWS?.on("AUTH_SUCCESS", (data) => {
        const tpsEl = document.getElementById("stat-tps");
        if (tpsEl) tpsEl.textContent = Number(data.serverTps).toFixed(1);

        if (data.seed) {
            window.AtlasMap?.setSeedInfo(data.seed, data.minecraftVersion || data.serverVersion);
        }

        if (data.spawnX !== undefined && data.spawnZ !== undefined) {
            window.AtlasMap?.setWorldSpawn(data.spawnX, data.spawnZ);
            if (window.AtlasTelemetry && window.AtlasTelemetry.players.size === 0) {
                window.AtlasMap?.centerSpawn();
            }
        }

        // Start ping loop upon authenticated connection
        window.AtlasApp._startPingLoop();
    });

    // Real-time World Spawn updates from in-game events or config enforcement
    window.AtlasWS?.on("WORLD_SPAWN_UPDATE", (data) => {
        if (data.spawnX !== undefined && data.spawnZ !== undefined) {
            window.AtlasMap?.setWorldSpawn(data.spawnX, data.spawnZ);
            window.AtlasApp?.showToast(`World spawn updated: [${data.spawnX}, ${data.spawnY ?? 64}, ${data.spawnZ}]`);
        }
    });

    // PONG Latency response handler
    window.AtlasWS?.on("PONG", (data) => {
        if (data.clientTime) {
            const rtt = Date.now() - data.clientTime;
            const pingEl = document.getElementById("stat-ping");
            if (pingEl) pingEl.textContent = `${rtt} ms`;
        }
    });

    // CONFIG_DATA response handler
    window.AtlasWS?.on("CONFIG_DATA", (data) => {
        const rngHz = document.getElementById("setting-telemetry-hz");
        const chkUnloaded = document.getElementById("setting-render-unloaded");
        const rngHill = document.getElementById("setting-hillshading-intensity");

        if (rngHz && data.telemetryHz !== undefined) {
            rngHz.value = data.telemetryHz;
            const valEl = document.getElementById("val-telemetry-hz");
            if (valEl) valEl.textContent = `${data.telemetryHz} Hz`;
        }
        if (chkUnloaded && data.renderUnloaded !== undefined) {
            chkUnloaded.checked = data.renderUnloaded;
        }
        if (rngHill && data.hillshadingIntensity !== undefined) {
            rngHill.value = data.hillshadingIntensity;
            const valEl = document.getElementById("val-hillshading-intensity");
            if (valEl) valEl.textContent = `${data.hillshadingIntensity}`;
        }
    });

    // 9. Resilient Clipboard Helper & World Seed Copy Handler
    function copyToClipboard(text, successMsg) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => {
                window.AtlasInspector?.showToast("success", successMsg || "Copied to clipboard");
            }).catch(() => {
                fallbackCopy(text, successMsg);
            });
        } else {
            fallbackCopy(text, successMsg);
        }
    }

    function fallbackCopy(text, successMsg) {
        try {
            const textarea = document.createElement("textarea");
            textarea.value = text;
            textarea.style.position = "fixed";
            textarea.style.left = "-9999px";
            textarea.style.top = "-9999px";
            document.body.appendChild(textarea);
            textarea.focus();
            textarea.select();
            const successful = document.execCommand("copy");
            document.body.removeChild(textarea);
            if (successful) {
                window.AtlasInspector?.showToast("success", successMsg || "Copied to clipboard");
            } else {
                window.AtlasInspector?.showToast("info", `Text: ${text}`);
            }
        } catch (e) {
            window.AtlasInspector?.showToast("info", `Text: ${text}`);
        }
    }

    document.getElementById("seed-badge-container")?.addEventListener("click", () => {
        const seedVal = window.AtlasMap?.detectedSeed;
        if (seedVal) {
            copyToClipboard(seedVal, `World seed ${seedVal} copied to clipboard`);
        }
    });
});
