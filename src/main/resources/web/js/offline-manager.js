// src/main/resources/web/js/offline-manager.js
class OfflineManager {
    constructor() {
        this.selectedOfflineUuid = null;
        this.offlinePlayers = [];
        this.offlinePlayersData = this.offlinePlayers;
        this.lastPendingActions = [];
        this.filterText = "";
    }

    init() {
        if (!window.AtlasWS) return;

        window.AtlasWS.on("OFFLINE_ROSTER", (msg) => {
            this.offlinePlayers = msg.players || [];
            this.offlinePlayersData = this.offlinePlayers;
            const offCountEl = document.getElementById("tab-offline-count");
            if (offCountEl) offCountEl.textContent = this.offlinePlayers.length;
            this._renderRoster();
            if (window.AtlasMap) {
                window.AtlasMap.setOfflinePlayers(this.offlinePlayers);
            }
            if (this.lastPendingActions && this.lastPendingActions.length > 0) {
                this._renderPendingActions(this.lastPendingActions);
            }
        });

        window.AtlasWS.on("OFFLINE_INSPECT_DATA", (msg) => {
            this._renderOfflineProfile(msg.profile);
        });

        window.AtlasWS.on("PENDING_ACTIONS_LIST", (msg) => {
            this.lastPendingActions = msg.actions || [];
            this._renderPendingActions(this.lastPendingActions);
        });

        window.AtlasWS.on("AUTH_SUCCESS", () => {
            this.fetchRoster();
            this.fetchPendingActions();
        });

        const offlineSearch = document.getElementById("search-offline-players");
        if (offlineSearch) {
            offlineSearch.addEventListener("input", (e) => {
                this.filterText = e.target.value.toLowerCase();
                this._renderRoster();
            });
            offlineSearch.addEventListener("keydown", (e) => {
                if (e.key === "Enter") {
                    const query = e.target.value.trim();
                    if (query) {
                        this.inspectOffline(query);
                    }
                }
            });
        }

        document.getElementById("btn-search-offline")?.addEventListener("click", () => {
            const query = offlineSearch ? offlineSearch.value.trim() : "";
            if (query) {
                this.inspectOffline(query);
            }
        });
    }

    fetchRoster() {
        if (window.AtlasWS) {
            window.AtlasWS.send({ type: "FETCH_OFFLINE_ROSTER" });
        }
    }

    fetchPendingActions() {
        if (window.AtlasWS) {
            window.AtlasWS.send({ type: "FETCH_PENDING_ACTIONS" });
        }
    }

    _renderRoster() {
        const rosterEl = document.getElementById("roster-offline");
        if (!rosterEl) return;

        rosterEl.innerHTML = "";
        const filtered = this.offlinePlayers.filter(p => {
            if (!this.filterText) return true;
            return (p.name && p.name.toLowerCase().includes(this.filterText)) ||
                   (p.uuid && p.uuid.toLowerCase().includes(this.filterText));
        });

        if (filtered.length === 0) {
            rosterEl.innerHTML = `<li class="empty-roster-msg">No matching offline records found.</li>`;
            return;
        }

        for (const p of filtered) {
            const li = document.createElement("li");
            if (p.uuid === this.selectedOfflineUuid) li.className = "active";
            const lastSeenStr = p.lastSeen ? new Date(p.lastSeen).toLocaleDateString() : "Never";
            const locInfo = (p.x !== undefined && p.z !== undefined) ? ` | (${Math.round(p.x)}, ${Math.round(p.z)})` : "";
            li.innerHTML = `
                <img class="player-avatar" src="https://mc-heads.net/avatar/${p.uuid}/28" alt="${p.name || 'Player'}">
                <div class="player-meta">
                    <span class="player-name">${p.name || 'Unknown'}</span>
                    <span class="player-sub">Seen: ${lastSeenStr}${locInfo}</span>
                </div>
            `;
            li.addEventListener("click", () => this.inspectOffline(p.uuid));
            rosterEl.appendChild(li);
        }
    }

    inspectOffline(uuid) {
        this.selectedOfflineUuid = uuid;
        this._renderRoster();
        window.AtlasWS.send({ type: "INSPECT_OFFLINE_PLAYER", uuid: uuid });
    }

    _renderOfflineProfile(profile) {
        if (!profile) return;
        document.getElementById("inspector-empty")?.classList.add("hidden");
        document.getElementById("inspector-body")?.classList.remove("hidden");

        const avatar = document.getElementById("insp-avatar");
        if (avatar) avatar.src = `https://mc-heads.net/avatar/${profile.uuid}/44`;
        const nameEl = document.getElementById("insp-name");
        if (nameEl) nameEl.textContent = (profile.name || "Offline Player") + " (Offline)";
        const uuidEl = document.getElementById("insp-uuid");
        if (uuidEl) uuidEl.textContent = profile.uuid || "";
        const modeEl = document.getElementById("insp-mode");
        if (modeEl) modeEl.textContent = "OFFLINE";

        // Last Known Location
        const loc = profile.location || {
            world: profile.dimension || profile.world || "minecraft:overworld",
            x: profile.x !== undefined ? profile.x : 0,
            y: profile.y !== undefined ? profile.y : 64,
            z: profile.z !== undefined ? profile.z : 0,
            yaw: profile.yaw !== undefined ? profile.yaw : 0
        };

        const dimClean = (loc.world || "minecraft:overworld").replace("minecraft:", "").replace(/_/g, " ").toUpperCase();
        const coordsStr = `X: ${Math.round(loc.x)}, Y: ${Math.round(loc.y)}, Z: ${Math.round(loc.z)}`;

        const locDimEl = document.getElementById("insp-loc-dim");
        const locCoordsEl = document.getElementById("insp-loc-coords");
        if (locDimEl) locDimEl.textContent = `LAST SEEN: ${dimClean}`;
        if (locCoordsEl) locCoordsEl.textContent = coordsStr;

        const locateBtn = document.getElementById("btn-insp-locate");
        if (locateBtn) {
            locateBtn.onclick = () => {
                if (window.AtlasMap) {
                    const targetDim = loc.world || "minecraft:overworld";
                    const normTarget = window.AtlasMap._normalizeDimension(targetDim);
                    const normCurrent = window.AtlasMap._normalizeDimension(window.AtlasMap.currentDimension);
                    if (normTarget !== normCurrent) {
                        window.AtlasMap.setDimension(targetDim);
                        document.querySelectorAll(".dim-btn").forEach(b => {
                            const bDim = b.getAttribute("data-dim");
                            b.classList.toggle("active", window.AtlasMap._normalizeDimension(bDim) === normTarget);
                        });
                    }
                    window.AtlasMap.panTo(loc.x, loc.z);
                    window.AtlasMap.setOfflineWaypoint(profile.name || "Offline Player", profile.uuid, loc.x, loc.z, targetDim, loc.yaw);
                    window.AtlasInspector?.showToast("info", `Focused last known location for ${profile.name || 'player'}`);
                }
            };
        }

        // Place waypoint on map automatically
        if (window.AtlasMap && loc.x !== undefined && loc.z !== undefined) {
            window.AtlasMap.setOfflineWaypoint(profile.name || "Offline Player", profile.uuid, loc.x, loc.z, loc.world, loc.yaw);
        }

        const hpEl = document.getElementById("val-health");
        if (hpEl) hpEl.textContent = "N/A";
        const barHp = document.getElementById("bar-health");
        if (barHp) barHp.style.width = "0%";
        const foodEl = document.getElementById("val-food");
        if (foodEl) foodEl.textContent = "N/A";
        const barFood = document.getElementById("bar-food");
        if (barFood) barFood.style.width = "0%";
        const pingEl = document.getElementById("val-ping");
        if (pingEl) pingEl.textContent = "Offline";

        const effectsEl = document.getElementById("insp-effects");
        const dateStr = profile.lastSeen ? new Date(profile.lastSeen).toLocaleString() : "Unknown";
        if (effectsEl) effectsEl.innerHTML = `<span class="text-muted">Last Seen: ${dateStr}</span>`;

        // Clear and populate equipment rack
        ["slot-helmet", "slot-chestplate", "slot-leggings", "slot-boots", "slot-mainhand", "slot-offhand"].forEach(slotId => {
            const el = document.getElementById(slotId);
            if (el) el.innerHTML = "";
        });
        if (profile.equipment && window.AtlasInspector) {
            window.AtlasInspector._populateEquipment("slot-helmet", profile.equipment.helmet);
            window.AtlasInspector._populateEquipment("slot-chestplate", profile.equipment.chestplate);
            window.AtlasInspector._populateEquipment("slot-leggings", profile.equipment.leggings);
            window.AtlasInspector._populateEquipment("slot-boots", profile.equipment.boots);
            window.AtlasInspector._populateEquipment("slot-mainhand", profile.equipment.mainHand);
            window.AtlasInspector._populateEquipment("slot-offhand", profile.equipment.offHand);
        }

        // Render Offline Inventory
        const grid = document.getElementById("inventory-slots");
        if (grid) {
            grid.innerHTML = "";
            const itemMap = new Map();
            if (profile.inventory) {
                for (const it of profile.inventory) itemMap.set(it.slot, it);
            }

            for (let i = 0; i < 36; i++) {
                const slotBox = document.createElement("div");
                slotBox.className = "slot-box";
                const it = itemMap.get(i);
                if (it && it.material !== "AIR") {
                    window.AtlasInspector._renderItemInside(slotBox, it);
                }
                grid.appendChild(slotBox);
            }
        }

        this._renderOfflineActionControls(profile.uuid);
    }

    _renderOfflineActionControls(uuid) {
        const modGrid = document.querySelector(".mod-btn-grid");
        if (!modGrid) return;

        modGrid.innerHTML = `
            <button class="btn-mod btn-primary" onclick="window.AtlasOffline.queueAction('${uuid}', 'TELEPORT_SPAWN')" data-tooltip="Teleport Player to World Spawn" aria-label="Teleport to Spawn">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
                    <polyline points="9 22 9 12 15 12 15 22"></polyline>
                </svg>
            </button>
            <button class="btn-mod btn-warning" onclick="window.AtlasOffline.queueAction('${uuid}', 'CLEAR_INVENTORY')" data-tooltip="Wipe Offline Player Inventory" aria-label="Wipe Inventory">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    <line x1="10" y1="11" x2="10" y2="17"></line>
                    <line x1="14" y1="11" x2="14" y2="17"></line>
                </svg>
            </button>
            <button class="btn-mod btn-danger" onclick="window.AtlasOffline.queueAction('${uuid}', 'WIPE_ENDER_CHEST')" data-tooltip="Wipe Offline Ender Chest" aria-label="Wipe Ender Chest">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="3" y="3" width="18" height="18" rx="0"></rect>
                    <path d="M3 9h18"></path>
                    <circle cx="12" cy="14" r="2"></circle>
                </svg>
            </button>
            <button class="btn-mod btn-info" onclick="window.AtlasOffline.queueAction('${uuid}', 'GIVE_ITEM')" data-tooltip="Grant 1x Diamond to Inventory" aria-label="Grant Diamond">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="6 3 18 3 22 9 12 22 2 9"></polygon>
                    <polyline points="2 9 12 13 22 9"></polyline>
                    <polyline points="12 13 12 22"></polyline>
                </svg>
            </button>
        `;

        const cmdContainer = document.getElementById("player-cmd-container");
        if (cmdContainer) {
            cmdContainer.innerHTML = `
                <div class="cmd-input-wrap">
                    <span class="cmd-prompt-prefix">&gt;</span>
                    <input type="text" id="input-player-cmd" placeholder="Queue command on offline player (/give %player% netherite_ingot 1)..." autocomplete="off" spellcheck="false">
                    <button id="btn-player-cmd-exec" class="btn-cmd-exec" data-tooltip="Queue Command for Login (Enter)">QUEUE</button>
                </div>
            `;
            const execBtn = document.getElementById("btn-player-cmd-exec");
            const cmdInput = document.getElementById("input-player-cmd");
            const queueCmd = () => {
                const raw = cmdInput ? cmdInput.value.trim() : "";
                if (!raw) return;
                this.queueAction(uuid, "COMMAND", { command: raw });
                if (cmdInput) cmdInput.value = "";
                window.AtlasInspector?.showToast("info", "Queued command for offline player");
            };
            execBtn?.addEventListener("click", queueCmd);
            cmdInput?.addEventListener("keydown", (e) => {
                if (e.key === "Enter") queueCmd();
            });
        }
    }

    queueAction(uuid, actionType, customParams = null) {
        let params = customParams || {};
        if (actionType === "GIVE_ITEM" && !customParams) {
            params = { material: "DIAMOND", count: 1 };
        }

        window.AtlasWS.send({
            type: "QUEUE_OFFLINE_ACTION",
            targetUuid: uuid,
            targetName: this.selectedProfile?.name || null,
            action: actionType,
            parameters: params
        });

        window.AtlasInspector?.showToast("success", `Queued ${actionType} for offline player.`);
        setTimeout(() => this.fetchPendingActions(), 300);
    }

    _renderPendingActions(actions) {
        const listEl = document.getElementById("ledger-action-list");
        if (!listEl) return;
        listEl.innerHTML = "";

        const countEl = document.getElementById("tab-actions-count");
        if (countEl) countEl.textContent = String((actions && actions.length) ? actions.length : "0");

        if (!actions || actions.length === 0) {
            listEl.innerHTML = `
                <div class="empty-queue-card">
                    <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="12" cy="12" r="10"></circle>
                        <polyline points="12 6 12 12 16 14"></polyline>
                    </svg>
                    <span class="empty-queue-title">NO PENDING ACTIONS</span>
                    <span class="empty-queue-sub">Offline player modifications and scheduled commands queued for login appear here.</span>
                </div>
            `;
            return;
        }

        for (const act of actions) {
            const li = document.createElement("li");
            li.className = "pending-card";

            let typeClass = "action-type-default";
            let badgeClass = "badge-action-command";
            let actionLabel = act.actionType || "COMMAND";
            let payloadHtml = "";

            if (act.actionType === "CLEAR_INVENTORY" || act.actionType === "WIPE_ENDER_CHEST") {
                typeClass = "action-type-wipe";
                badgeClass = "badge-action-wipe";
                payloadHtml = act.actionType === "CLEAR_INVENTORY"
                    ? `<div class="pending-payload-box"><span class="pending-prompt-sym">!</span> Wipe all 36 player inventory slots on join</div>`
                    : `<div class="pending-payload-box"><span class="pending-prompt-sym">!</span> Wipe all 27 Ender Chest slots on join</div>`;
            } else if (act.actionType === "TELEPORT_SPAWN" || act.actionType === "TELEPORT") {
                typeClass = "action-type-tp";
                badgeClass = "badge-action-tp";
                const dest = (act.parameters && act.parameters.teleportLocation)
                    ? `[${Math.round(act.parameters.teleportLocation.x)}, ${Math.round(act.parameters.teleportLocation.y || 64)}, ${Math.round(act.parameters.teleportLocation.z)}]`
                    : "World Spawn";
                payloadHtml = `<div class="pending-payload-box"><span class="pending-prompt-sym">&gt;</span> Teleport to ${dest} on join</div>`;
            } else if (act.actionType === "TELEPORT_COORDS") {
                typeClass = "action-type-tp";
                badgeClass = "badge-action-tp";
                const px = act.parameters?.x !== undefined ? Math.round(act.parameters.x) : 0;
                const py = act.parameters?.y !== undefined ? Math.round(act.parameters.y) : 64;
                const pz = act.parameters?.z !== undefined ? Math.round(act.parameters.z) : 0;
                payloadHtml = `<div class="pending-payload-box"><span class="pending-prompt-sym">&gt;</span> Teleport to [${px}, ${py}, ${pz}] on join</div>`;
            } else if (act.actionType === "GIVE_ITEM") {
                typeClass = "action-type-give";
                badgeClass = "badge-action-give";
                const mat = act.parameters?.material || "ITEM";
                const cnt = act.parameters?.count || 1;
                payloadHtml = `<div class="pending-payload-box"><span class="pending-prompt-sym">+</span> Grant ${cnt}x ${mat} to inventory</div>`;
            } else {
                // Command
                const rawCmd = (act.parameters && act.parameters.command) ? act.parameters.command : (act.command || "");
                payloadHtml = `<div class="pending-payload-box"><span class="pending-prompt-sym">&gt;</span> ${this._escapeHtml(rawCmd || act.actionType)}</div>`;
            }
            li.classList.add(typeClass);

            const targetName = act.targetName
                || act.parameters?.targetName
                || (this.offlinePlayers || []).find(p => p.uuid === act.targetUuid)?.name
                || (this.offlinePlayersData || []).find(p => p.uuid === act.targetUuid)?.name
                || window.AtlasMap?.offlinePlayersData?.find(p => p.uuid === act.targetUuid)?.name
                || window.AtlasTelemetry?.players?.get(act.targetUuid)?.data?.name
                || (act.targetUuid ? act.targetUuid.substring(0, 8) + '...' : 'Unknown Player');
            const targetUuidShort = act.targetUuid ? act.targetUuid.substring(0, 8) + '...' + act.targetUuid.substring(act.targetUuid.length - 4) : '';
            
            let dateStr = 'Pending';
            const rawDate = act.createdAt || act.timestamp;
            if (rawDate) {
                try {
                    dateStr = new Date(rawDate).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                } catch (_) {}
            }

            li.innerHTML = `
                <div class="pending-card-top">
                    <span class="pending-badge ${badgeClass}">${actionLabel}</span>
                    <button class="btn-pending-cancel" title="Discard Queued Action" data-tooltip="Cancel Action">
                        <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <line x1="18" y1="6" x2="6" y2="18"></line>
                            <line x1="6" y1="6" x2="18" y2="18"></line>
                        </svg>
                        <span>DISCARD</span>
                    </button>
                </div>
                <div class="pending-player-row">
                    <img class="pending-avatar" src="https://mc-heads.net/avatar/${act.targetUuid}/24" alt="${targetName}" onerror="this.src='/favicon.ico'">
                    <div class="pending-player-info">
                        <span class="pending-player-name">${this._escapeHtml(targetName)}</span>
                        <span class="pending-player-uuid">${targetUuidShort}</span>
                    </div>
                </div>
                ${payloadHtml}
                <div class="pending-footer-row">
                    <span class="queue-status-tag">
                        <span class="queue-status-pulse"></span>
                        <span>QUEUED FOR LOGIN</span>
                    </span>
                    <span class="queue-meta">${dateStr}</span>
                </div>
            `;

            li.querySelector(".btn-pending-cancel")?.addEventListener("click", () => {
                this.cancelPendingAction(act.id);
            });
            listEl.appendChild(li);
        }
    }

    _escapeHtml(str) {
        if (!str) return "";
        return str
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    cancelPendingAction(actionId) {
        if (!actionId) return;
        if (this.lastPendingActions) {
            this.lastPendingActions = this.lastPendingActions.filter(a => a.id !== actionId);
            this._renderPendingActions(this.lastPendingActions);
        }
        if (window.AtlasWS) {
            window.AtlasWS.send({ type: "CANCEL_PENDING_ACTION", actionId: actionId });
            setTimeout(() => this.fetchPendingActions(), 300);
        }
    }
}

window.AtlasOffline = new OfflineManager();
