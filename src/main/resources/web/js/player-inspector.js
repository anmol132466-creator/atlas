// src/main/resources/web/js/player-inspector.js
class PlayerInspector {
    constructor() {
        this.currentUuid = null;
        this.currentPlayerData = null;
    }

    init() {
        if (!window.AtlasWS) return;

        window.AtlasWS.on("PLAYER_INSPECT_DATA", (data) => {
            this._renderInspector(data);
        });

        window.AtlasWS.on("ACTION_ACK", (ack) => {
            if (ack.message) {
                this.showToast(ack.success ? "success" : "danger", ack.message);
            }
        });

        this._setupModButtons();
    }

    showToast(type, text) {
        let container = document.getElementById("toast-container");
        if (!container) {
            container = document.createElement("div");
            container.id = "toast-container";
            container.className = "toast-container";
            document.body.appendChild(container);
        }
        const toast = document.createElement("div");
        toast.className = `atlas-toast toast-${type}`;
        toast.textContent = text;
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 4000);
    }

    inspect(uuid) {
        this.currentUuid = uuid;
        window.AtlasWS.send({ type: "INSPECT_PLAYER", uuid: uuid });
    }

    _renderInspector(data) {
        this.currentPlayerData = data;
        document.getElementById("inspector-empty")?.classList.add("hidden");
        document.getElementById("inspector-body")?.classList.remove("hidden");

        // Identity
        const avatar = document.getElementById("insp-avatar");
        if (avatar) avatar.src = `https://mc-heads.net/avatar/${data.uuid}/44`;
        document.getElementById("insp-name").textContent = data.name;
        document.getElementById("insp-uuid").textContent = data.uuid;
        document.getElementById("insp-mode").textContent = data.gamemode;

        // Clear any offline waypoint when inspecting live player
        window.AtlasMap?.removeOfflineWaypoint();

        // Live Location Info
        const pRecord = window.AtlasTelemetry?.players?.get(data.uuid);
        const pos = {
            x: (data.x !== undefined) ? data.x : (pRecord?.currentPos?.x || 0),
            y: (data.y !== undefined) ? data.y : (pRecord?.currentPos?.y || 64),
            z: (data.z !== undefined) ? data.z : (pRecord?.currentPos?.z || 0)
        };
        const rawDim = data.dimension || data.world || pRecord?.data?.dimension || pRecord?.data?.world || window.AtlasMap?.currentDimension || "minecraft:overworld";
        const curDim = rawDim.replace("minecraft:", "").replace(/_/g, " ").toUpperCase();

        const locDimEl = document.getElementById("insp-loc-dim");
        const locCoordsEl = document.getElementById("insp-loc-coords");
        if (locDimEl) locDimEl.textContent = `ONLINE: ${curDim}`;
        if (locCoordsEl) locCoordsEl.textContent = `X: ${Math.round(pos.x)}, Y: ${Math.round(pos.y)}, Z: ${Math.round(pos.z)}`;

        const locateBtn = document.getElementById("btn-insp-locate");
        if (locateBtn) {
            locateBtn.onclick = () => {
                if (window.AtlasMap) {
                    const normTarget = window.AtlasMap._normalizeDimension(rawDim);
                    const normCurrent = window.AtlasMap._normalizeDimension(window.AtlasMap.currentDimension);
                    if (normTarget !== normCurrent) {
                        window.AtlasMap.setDimension(rawDim);
                        document.querySelectorAll(".dim-btn").forEach(b => {
                            const bDim = b.getAttribute("data-dim");
                            b.classList.toggle("active", window.AtlasMap._normalizeDimension(bDim) === normTarget);
                        });
                    }
                    window.AtlasMap.panTo(pos.x, pos.z);
                    window.AtlasInspector?.showToast("info", `Centered map on ${data.name} in ${curDim}`);
                }
            };
        }

        // Health & Food
        document.getElementById("val-health").textContent = `${data.health}/20`;
        const healthPct = Math.min(100, Math.max(0, (data.health / 20) * 100));
        document.getElementById("bar-health").style.width = `${healthPct}%`;

        document.getElementById("val-food").textContent = `${data.foodLevel}/20`;
        const foodPct = Math.min(100, Math.max(0, (data.foodLevel / 20) * 100));
        document.getElementById("bar-food").style.width = `${foodPct}%`;

        document.getElementById("val-ping").textContent = `${data.ping} ms`;

        // Potion Effects
        const effectsEl = document.getElementById("insp-effects");
        effectsEl.innerHTML = "";
        if (data.activeEffects && data.activeEffects.length > 0) {
            for (const eff of data.activeEffects) {
                const chip = document.createElement("span");
                chip.className = "effect-chip";
                chip.textContent = `${eff.name} ${eff.amplifier + 1} (${eff.durationSeconds}s)`;
                effectsEl.appendChild(chip);
            }
        } else {
            effectsEl.innerHTML = '<span class="text-muted">None</span>';
        }

        // Equipment Rack
        this._populateEquipment("slot-helmet", data.equipment.helmet);
        this._populateEquipment("slot-chestplate", data.equipment.chestplate);
        this._populateEquipment("slot-leggings", data.equipment.leggings);
        this._populateEquipment("slot-boots", data.equipment.boots);
        this._populateEquipment("slot-mainhand", data.equipment.mainHand);
        this._populateEquipment("slot-offhand", data.equipment.offHand);

        // 36-slot Inventory Grid
        const grid = document.getElementById("inventory-slots");
        grid.innerHTML = "";
        const itemMap = new Map();
        if (data.inventory) {
            for (const it of data.inventory) itemMap.set(it.slot, it);
        }

        for (let i = 0; i < 36; i++) {
            const slotBox = document.createElement("div");
            slotBox.className = "slot-box";
            const it = itemMap.get(i);
            if (it && it.material !== "AIR") {
                this._renderItemInside(slotBox, it);
            }
            grid.appendChild(slotBox);
        }

        this._renderOnlineActionControls(data);
    }

    _renderOnlineActionControls(data) {
        const pData = data || this.currentPlayerData || {};
        const modGrid = document.querySelector(".mod-btn-grid");
        if (modGrid) {
            modGrid.innerHTML = `
            <button id="btn-mod-kick" class="btn-mod btn-warning" data-tooltip="Kick Player from Server" aria-label="Kick Player">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
                    <polyline points="16 17 21 12 16 7"></polyline>
                    <line x1="21" y1="12" x2="9" y2="12"></line>
                </svg>
            </button>
            <button id="btn-mod-ban" class="btn-mod btn-danger" data-tooltip="Ban Player from Server" aria-label="Ban Player">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"></line>
                </svg>
            </button>
            <button id="btn-mod-kill" class="btn-mod btn-danger" data-tooltip="Execute / Kill Player" aria-label="Kill Player">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
                </svg>
            </button>
            <button id="btn-mod-tp" class="btn-mod btn-primary" data-tooltip="Teleport To Player" aria-label="Teleport To Player">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="3"></circle>
                    <path d="M12 2v3m0 14v3M2 12h3m14 0h3"></path>
                    <path d="M5.6 5.6l2.1 2.1m8.6 8.6l2.1 2.1M5.6 18.4l2.1-2.1m8.6-8.6l2.1-2.1"></path>
                </svg>
            </button>
            <button id="btn-mod-clear" class="btn-mod btn-warning" data-tooltip="Clear Player Inventory" aria-label="Clear Inventory">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    <line x1="10" y1="11" x2="10" y2="17"></line>
                    <line x1="14" y1="11" x2="14" y2="17"></line>
                </svg>
            </button>
            <button id="btn-mod-talk" class="btn-mod btn-voice" data-tooltip="Talk Directly to Player (Live Mic)" aria-label="Talk to Player">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
                    <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
                    <line x1="12" y1="19" x2="12" y2="23"></line>
                    <line x1="8" y1="23" x2="16" y2="23"></line>
                </svg>
            </button>
            <button id="btn-mod-talk-area" class="btn-mod btn-voice" data-tooltip="Talk to Players in this Area (Radius 50m)" aria-label="Talk to Area">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
                    <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
                    <circle cx="12" cy="12" r="10" stroke-dasharray="2 2"></circle>
                </svg>
            </button>
            <button id="btn-mod-spyglass" class="btn-mod btn-info" data-tooltip="Activate Audio Monitor on Player" aria-label="Audio Monitor">
                <svg class="spyglass-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M21 3l-6 6"/>
                    <path d="M21 7l-2-2"/>
                    <path d="M18 6l-6 6 2 2 6-6"/>
                    <path d="M13 11l-4 4 1.5 1.5 4-4"/>
                    <path d="M9.5 14.5l-3 3 1 1 3-3"/>
                    <path d="M7 17l-3.5 3.5a1 1 0 0 1-1.4-1.4L5.6 15.6"/>
                </svg>
            </button>
            `;
        }

        const cmdContainer = document.getElementById("player-cmd-container");
        if (cmdContainer) {
            cmdContainer.innerHTML = `
                <div class="cmd-input-wrap">
                    <span class="cmd-prompt-prefix">&gt;</span>
                    <input type="text" id="input-player-cmd" placeholder="Run command on ${pData.name || 'player'} (e.g. /gamemode survival %player%)..." autocomplete="off" spellcheck="false">
                    <button id="btn-player-cmd-exec" class="btn-cmd-exec" data-tooltip="Run or Queue Command (Enter)">EXEC</button>
                </div>
            `;
        }

        this._setupModButtons();
    }

    _populateEquipment(slotId, item) {
        const box = document.getElementById(slotId);
        if (!box) return;
        box.innerHTML = "";
        if (item && item.material !== "AIR") {
            this._renderItemInside(box, item);
        }
    }

    _renderItemInside(container, item) {
        if (!item || item.material === "AIR") return;
        const mat = item.material.toLowerCase();

        const icon = document.createElement("img");
        icon.className = "slot-item-icon";
        icon.alt = item.material;
        icon.src = `https://assets.mcasset.cloud/1.21.1/assets/minecraft/textures/item/${mat}.png`;
        let triedBlock = false;
        icon.onerror = () => {
            if (!triedBlock) {
                triedBlock = true;
                icon.src = `https://assets.mcasset.cloud/1.21.1/assets/minecraft/textures/block/${mat}.png`;
            } else {
                icon.style.display = "none";
                const badge = document.createElement("div");
                badge.className = "slot-item-badge";
                const initials = mat.split('_').map(w => w[0]).join('').toUpperCase().substring(0, 3);
                badge.textContent = initials || mat.substring(0, 2).toUpperCase();
                container.appendChild(badge);
            }
        };
        container.appendChild(icon);

        if (item.count > 1) {
            const count = document.createElement("span");
            count.className = "slot-count";
            count.textContent = item.count;
            container.appendChild(count);
        }

        if (item.maxDurability > 0 && item.durability < item.maxDurability) {
            const durTrack = document.createElement("div");
            durTrack.className = "slot-durability";
            const durFill = document.createElement("div");
            durFill.className = "slot-durability-fill";
            const pct = Math.max(0, Math.min(100, (item.durability / item.maxDurability) * 100));
            durFill.style.width = `${pct}%`;
            durTrack.appendChild(durFill);
            container.appendChild(durTrack);
        }

        container.onmouseenter = (e) => this._showTooltip(e, item);
        container.onmouseleave = () => this._hideTooltip();
    }

    _showTooltip(e, item) {
        const tip = document.getElementById("item-tooltip");
        if (!tip) return;

        document.getElementById("tooltip-name").textContent = item.customName || item.material;

        const loreEl = document.getElementById("tooltip-lore");
        loreEl.innerHTML = "";
        if (item.lore && item.lore.length > 0) {
            loreEl.textContent = item.lore.join("\n");
        }

        const enchEl = document.getElementById("tooltip-enchantments");
        enchEl.innerHTML = "";
        if (item.enchantments && Object.keys(item.enchantments).length > 0) {
            const list = Object.entries(item.enchantments).map(([k, v]) => `${k} ${v}`).join(", ");
            enchEl.textContent = list;
        }

        const durEl = document.getElementById("tooltip-durability");
        if (durEl) {
            if (item.maxDurability > 0) {
                durEl.textContent = `Durability: ${item.durability} / ${item.maxDurability}`;
            } else {
                durEl.textContent = "";
            }
        }

        let left = e.clientX + 14;
        let top = e.clientY + 14;
        if (left + 260 > window.innerWidth) left = Math.max(10, e.clientX - 260);
        if (top + 180 > window.innerHeight) top = Math.max(10, e.clientY - 180);

        tip.classList.remove("hidden");
        tip.style.left = `${left}px`;
        tip.style.top = `${top}px`;
    }

    _hideTooltip() {
        document.getElementById("item-tooltip")?.classList.add("hidden");
    }

    _requireSelectedPlayer() {
        if (!this.currentUuid) {
            this.showToast("warning", "No player selected! Click an active player marker on the map or roster first.");
            return false;
        }
        return true;
    }

    _setupModButtons() {
        document.getElementById("btn-mod-kick")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            const reason = prompt("Enter kick reason:", "Kicked by A.T.L.A.S. Admin");
            if (reason) {
                window.AtlasWS.send({ type: "EXECUTE_ACTION", action: "KICK", target: this.currentUuid, reason: reason });
            }
        });

        document.getElementById("btn-mod-ban")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            const reason = prompt("Enter ban reason:", "Banned by A.T.L.A.S. Admin");
            if (reason) {
                window.AtlasWS.send({ type: "EXECUTE_ACTION", action: "BAN", target: this.currentUuid, reason: reason, durationSeconds: 86400 });
            }
        });

        document.getElementById("btn-mod-kill")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            if (!confirm(`Are you sure you want to eliminate ${this.currentPlayerData?.name || 'this player'}?`)) return;
            window.AtlasWS.send({ type: "EXECUTE_ACTION", action: "KILL", target: this.currentUuid });
        });

        document.getElementById("btn-mod-tp")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            const choice = prompt("Enter target coordinates (x y z) or type 'spawn' to send to world spawn:", "spawn");
            if (!choice) return;
            if (choice.trim().toLowerCase() === "spawn") {
                const spawn = window.AtlasMap?.worldSpawn || { x: 0, z: 0 };
                window.AtlasWS.send({
                    type: "EXECUTE_ACTION",
                    action: "TELEPORT",
                    target: this.currentUuid,
                    teleportLocation: { x: spawn.x, y: 64, z: spawn.z }
                });
            } else {
                const parts = choice.trim().split(/\s+/);
                if (parts.length >= 3) {
                    window.AtlasWS.send({
                        type: "EXECUTE_ACTION",
                        action: "TELEPORT",
                        target: this.currentUuid,
                        teleportLocation: { x: parseFloat(parts[0]), y: parseFloat(parts[1]), z: parseFloat(parts[2]) }
                    });
                } else {
                    this.showToast("warning", "Invalid coordinate format! Use: <x> <y> <z> or 'spawn'");
                }
            }
        });

        document.getElementById("btn-mod-clear")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            if (!confirm(`Wipe ${this.currentPlayerData?.name || 'this player'}'s active inventory?`)) return;
            window.AtlasWS.send({ type: "EXECUTE_ACTION", action: "COMMAND", command: `clear ${this.currentPlayerData.name}` });
        });

        document.getElementById("btn-mod-spyglass")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer() || !this.currentPlayerData) return;
            let p = window.AtlasTelemetry?.players?.get(this.currentUuid);
            let rawData = p ? p.data : window.AtlasTelemetry?.allOnlinePlayers?.get(this.currentUuid);
            if (!rawData) rawData = this.currentPlayerData;

            if (rawData && window.AtlasSpyglass) {
                const px = p?.currentPos?.x ?? rawData.x ?? 0;
                const py = (p?.currentPos?.y !== undefined) ? p.currentPos.y : (rawData.y ?? 64);
                const pz = p?.currentPos?.z ?? rawData.z ?? 0;
                const pDim = rawData.dimension || rawData.world || window.AtlasMap?.currentDimension || "minecraft:overworld";

                if (window.AtlasMap && window.AtlasMap.currentDimension !== pDim) {
                    window.AtlasMap.setDimension(pDim);
                    document.querySelectorAll(".dim-btn").forEach(b => {
                        b.classList.toggle("active", b.getAttribute("data-dim") === pDim);
                    });
                }

                window.AtlasSpyglass.startSurveillance(px, py, pz, 50);
                this.showToast("info", `Audio Monitor radar focused on ${rawData.name || 'player'}`);
            }
        });

        document.getElementById("btn-mod-talk")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            const name = this.currentPlayerData?.name || "Player";
            if (window.AtlasVoice) {
                window.AtlasVoice.talkToPlayer(this.currentUuid, name);
            }
        });

        document.getElementById("btn-mod-talk-area")?.addEventListener("click", () => {
            if (!this._requireSelectedPlayer()) return;
            const p = window.AtlasTelemetry?.players?.get(this.currentUuid);
            const rawData = p ? p.data : window.AtlasTelemetry?.allOnlinePlayers?.get(this.currentUuid) || this.currentPlayerData;
            const px = p?.currentPos?.x ?? rawData?.x ?? 0;
            const py = (p?.currentPos?.y !== undefined) ? p.currentPos.y : (rawData?.y ?? 64);
            const pz = p?.currentPos?.z ?? rawData?.z ?? 0;
            const dim = rawData?.dimension || rawData?.world || window.AtlasMap?.currentDimension || "minecraft:overworld";
            const name = this.currentPlayerData?.name || "Player";
            if (window.AtlasVoice) {
                window.AtlasVoice.talkToArea(px, py, pz, 50, dim, `Area near ${name}`);
            }
        });

        const execBtn = document.getElementById("btn-player-cmd-exec");
        const cmdInput = document.getElementById("input-player-cmd");
        const runCmd = () => {
            const raw = cmdInput ? cmdInput.value.trim() : "";
            if (!raw) return;
            const target = this.currentUuid;
            const targetName = this.currentPlayerData?.name || target;
            window.AtlasWS?.send({
                type: "EXECUTE_COMMAND",
                command: raw,
                target: target,
                targetName: targetName,
                queueIfOffline: true
            });
            if (cmdInput) cmdInput.value = "";
            this.showToast("info", `Command dispatched for ${targetName}...`);
        };

        execBtn?.addEventListener("click", runCmd);
        cmdInput?.addEventListener("keydown", (e) => {
            if (e.key === "Enter") runCmd();
        });
    }
}

window.AtlasInspector = new PlayerInspector();
