// src/main/resources/web/js/telemetry-renderer.js
class TelemetryRenderer {
    constructor() {
        this.players = new Map(); // uuid -> { data, marker, trailLine, trail, targetPos, currentPos }
        this.allOnlinePlayers = new Map(); // uuid -> raw player telemetry state
        this.selectedUuid = null;
        this.filterText = "";
        this.hasCenteredInitial = false;
        this.followSelected = false;
        this.showTrails = localStorage.getItem("atlas_show_trails") !== "false";
        this.showLabels = localStorage.getItem("atlas_show_labels") !== "false";
        this.showCones = localStorage.getItem("atlas_show_cones") !== "false";
        this.showChatOverhead = localStorage.getItem("atlas_show_chat_overhead") !== "false";
        this.chatBubbleDuration = parseInt(localStorage.getItem("atlas_chat_duration") || "7", 10) * 1000;
        this.maxTrailLength = parseInt(localStorage.getItem("atlas_trail_len") || "30", 10);
        this.chatTimers = new Map();

        this._initLoop();
    }

    init() {
        if (!window.AtlasWS) return;

        window.AtlasWS.on("TELEMETRY_BATCH", (batch) => {
            this._handleBatch(batch.players);
        });

        window.AtlasWS.on("PLAYER_QUIT", (quit) => {
            this._removePlayer(quit.uuid);
        });

        window.AtlasWS.on("CHAT_MESSAGE", (chat) => {
            this._handleChatMessage(chat);
        });

        const searchInput = document.getElementById("search-online-players");
        if (searchInput) {
            searchInput.addEventListener("input", (e) => {
                this.filterText = e.target.value.toLowerCase();
                this._renderRoster();
            });
        }
    }

    _handleChatMessage(chat) {
        if (!chat || !chat.uuid || !chat.message) return;

        // 1. Overhead Speech Bubble on Marker
        if (this.showChatOverhead) {
            const markerWrap = document.getElementById(`marker-${chat.uuid}`);
            if (markerWrap) {
                let bubble = markerWrap.querySelector(".marker-chat-bubble");
                if (!bubble) {
                    bubble = document.createElement("div");
                    bubble.className = "marker-chat-bubble hidden";
                    markerWrap.appendChild(bubble);
                }

                // Sanitize and set content
                const safeMsg = this._escapeHtml(chat.message);
                bubble.innerHTML = `
                    <div class="chat-bubble-arrow"></div>
                    <div class="chat-bubble-text">${safeMsg}</div>
                `;
                bubble.classList.remove("hidden");
                bubble.classList.add("active-bubble");

                if (this.chatTimers.has(chat.uuid)) {
                    clearTimeout(this.chatTimers.get(chat.uuid));
                }

                const timer = setTimeout(() => {
                    bubble.classList.remove("active-bubble");
                    bubble.classList.add("hidden");
                    this.chatTimers.delete(chat.uuid);
                }, this.chatBubbleDuration);

                this.chatTimers.set(chat.uuid, timer);
            }
        }

        // 2. Append to Surveillance Chat Log Feed
        this._appendChatFeed(chat);
    }

    _appendChatFeed(chat) {
        const feedList = document.getElementById("surveillance-chat-list");
        if (!feedList) return;

        const timeStr = new Date(chat.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        const li = document.createElement("li");
        li.className = "chat-feed-item";
        li.innerHTML = `
            <span class="chat-time">${timeStr}</span>
            <span class="chat-player" onclick="window.AtlasTelemetry?.selectPlayer('${chat.uuid}')">${this._escapeHtml(chat.player)}</span>
            <span class="chat-coords">(${Math.round(chat.x)}, ${Math.round(chat.z)})</span>:
            <span class="chat-text">${this._escapeHtml(chat.message)}</span>
        `;
        feedList.appendChild(li);

        // Keep last 150 messages in feed
        while (feedList.children.length > 150) {
            feedList.removeChild(feedList.firstChild);
        }

        // Auto-scroll feed to bottom
        const container = document.getElementById("surveillance-chat-scroll");
        if (container) {
            container.scrollTop = container.scrollHeight;
        }

        // Update unread count indicator if drawer is collapsed
        const badge = document.getElementById("badge-chat-unread");
        const drawer = document.getElementById("surveillance-chat-drawer");
        if (badge && drawer && drawer.classList.contains("collapsed")) {
            const count = parseInt(badge.textContent || "0", 10) + 1;
            badge.textContent = count;
            badge.classList.remove("hidden");
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

    toggleChatOverhead(enable) {
        this.showChatOverhead = enable;
        localStorage.setItem("atlas_show_chat_overhead", enable ? "true" : "false");
        if (!enable) {
            document.querySelectorAll(".marker-chat-bubble").forEach(b => {
                b.classList.remove("active-bubble");
                b.classList.add("hidden");
            });
        }
    }

    setChatDuration(seconds) {
        this.chatBubbleDuration = Math.max(2, Math.min(30, seconds)) * 1000;
        localStorage.setItem("atlas_chat_duration", seconds);
    }

    toScreenYaw(mcYaw) {
        if (mcYaw === undefined || isNaN(mcYaw)) return 0;
        // In Minecraft: 0=South(+Z), 90=West(-X), 180=North(-Z), 270=East(+X)
        // On screen: 0deg=North, 90deg=East, 180deg=South, 270deg=West
        // (mcYaw + 180) aligns Minecraft South (0) to Screen South (180deg), etc.
        let deg = (mcYaw + 180) % 360;
        if (deg < 0) deg += 360;
        return deg;
    }

    isPlayerInCurrentDimension(playerWorld) {
        if (!window.AtlasMap) return true;
        const currentDimNorm = window.AtlasMap._normalizeDimension(window.AtlasMap.currentDimension);
        const playerDimNorm = window.AtlasMap._normalizeDimension(playerWorld || "minecraft:overworld");
        return currentDimNorm === playerDimNorm;
    }

    getFallbackAvatar(name) {
        const initial = (name && name.length > 0) ? name.charAt(0).toUpperCase() : 'P';
        return `data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32' viewBox='0 0 32 32'><rect width='32' height='32' rx='0' fill='%230ea5e9'/><text x='16' y='21' font-family='sans-serif' font-size='14' font-weight='bold' fill='%23ffffff' text-anchor='middle'>${initial}</text></svg>`;
    }

    _handleBatch(playerList) {
        if (!playerList) return;

        for (const p of playerList) {
            this.allOnlinePlayers.set(p.uuid, p);

            let record = this.players.get(p.uuid);
            const playerDim = p.dimension || p.world || "minecraft:overworld";

            // Filter out players who are in a different dimension than the active map
            if (!this.isPlayerInCurrentDimension(playerDim)) {
                if (record) {
                    if (record.marker && window.AtlasMap && window.AtlasMap.map) {
                        window.AtlasMap.map.removeLayer(record.marker);
                    }
                    if (record.trailLine && window.AtlasMap && window.AtlasMap.map) {
                        window.AtlasMap.map.removeLayer(record.trailLine);
                    }
                    this.players.delete(p.uuid);
                }
                continue;
            }

            const screenYaw = this.toScreenYaw(p.yaw || 0);
            const fallbackAvatar = this.getFallbackAvatar(p.name);
            const avatarUrl = `https://mc-heads.net/avatar/${p.uuid}/32`;

            if (!record) {
                const icon = L.divIcon({
                    className: `atlas-player-marker-container ${!this.showLabels ? "hide-labels" : ""} ${!this.showCones ? "hide-cones" : ""}`,
                    html: `
                        <div class="player-marker-wrap" id="marker-${p.uuid}">
                            <div class="marker-chat-bubble hidden"></div>
                            <div class="player-pointer-needle" style="transform: rotate(${screenYaw}deg)">
                                <div class="player-heading-arrow"></div>
                            </div>
                            <div class="player-pin-ring">
                                <img class="marker-head" src="${avatarUrl}" alt="${p.name}" onerror="this.onerror=null;this.src='${fallbackAvatar}';">
                            </div>
                            <div class="marker-label">
                                <span class="label-name">${p.name}</span>
                                <span class="label-hp">HP ${Math.round(p.health !== undefined ? p.health : 20)}</span>
                            </div>
                        </div>
                    `,
                    iconSize: [36, 36],
                    iconAnchor: [18, 18]
                });
                const marker = L.marker([p.z, p.x], { icon: icon });
                marker.on("click", () => this.selectPlayer(p.uuid));
                if (window.AtlasMap && window.AtlasMap.map) {
                    window.AtlasMap.map.addLayer(marker);
                }

                let trailLine = null;
                if (this.showTrails && window.AtlasMap && window.AtlasMap.map) {
                    trailLine = L.polyline([[p.z, p.x]], {
                        color: "#f97316",
                        weight: 2.5,
                        opacity: 0.85,
                        dashArray: "4, 4"
                    }).addTo(window.AtlasMap.map);
                }

                record = {
                    data: p,
                    marker: marker,
                    trailLine: trailLine,
                    trail: [[p.z, p.x]],
                    currentPos: { x: p.x, z: p.z, yaw: p.yaw || 0 },
                    targetPos: { x: p.x, z: p.z, yaw: p.yaw || 0 }
                };
                this.players.set(p.uuid, record);

                // Auto center on the first connected player
                if (!this.hasCenteredInitial) {
                    this.hasCenteredInitial = true;
                    window.AtlasMap?.panTo(p.x, p.z);
                    this.selectPlayer(p.uuid);
                }
            } else {
                record.data = p;
                record.targetPos = { x: p.x, z: p.z, yaw: p.yaw || 0 };

                // Ensure marker is present on map
                if (record.marker && window.AtlasMap && window.AtlasMap.map && (!window.AtlasMap.map.hasLayer || !window.AtlasMap.map.hasLayer(record.marker))) {
                    window.AtlasMap.map.addLayer(record.marker);
                }

                // Update live HP and Name badges safely
                const markerEl = document.getElementById("marker-" + p.uuid);
                if (markerEl) {
                    const hpEl = markerEl.querySelector(".label-hp");
                    if (hpEl && p.health !== undefined) {
                        hpEl.textContent = `HP ${Math.round(p.health)}`;
                    }
                    const nameEl = markerEl.querySelector(".label-name");
                    if (nameEl && p.name) {
                        nameEl.textContent = p.name;
                    }
                }
            }
            if (window.AtlasMap) {
                window.AtlasMap.removeOfflineMarker(p.uuid);
            }
        }

        const countEl = document.getElementById("stat-online-count");
        const tabCountEl = document.getElementById("tab-online-count");
        if (countEl) countEl.textContent = this.players.size;
        if (tabCountEl) tabCountEl.textContent = this.allOnlinePlayers.size;

        this._renderRoster();
    }

    _populatePlayersForCurrentDimension() {
        if (!this.allOnlinePlayers || this.allOnlinePlayers.size === 0) return;
        const currentDimPlayers = [];
        for (const p of this.allOnlinePlayers.values()) {
            const playerDim = p.dimension || p.world || "minecraft:overworld";
            if (this.isPlayerInCurrentDimension(playerDim)) {
                currentDimPlayers.push(p);
            }
        }
        if (currentDimPlayers.length > 0) {
            this._handleBatch(currentDimPlayers);
        }
    }

    clearDimensionPlayers() {
        for (const record of this.players.values()) {
            if (record.marker && window.AtlasMap && window.AtlasMap.map) {
                window.AtlasMap.map.removeLayer(record.marker);
            }
            if (record.trailLine && window.AtlasMap && window.AtlasMap.map) {
                window.AtlasMap.map.removeLayer(record.trailLine);
            }
        }
        this.players.clear();
        this.selectedUuid = null;
        this.hasCenteredInitial = false;

        // Immediately populate markers for players in the newly selected dimension
        this._populatePlayersForCurrentDimension();

        const countEl = document.getElementById("stat-online-count");
        const tabCountEl = document.getElementById("tab-online-count");
        if (countEl) countEl.textContent = this.players.size;
        if (tabCountEl) tabCountEl.textContent = this.allOnlinePlayers.size;
        this._renderRoster();
    }

    _removePlayer(uuid) {
        this.allOnlinePlayers.delete(uuid);
        const record = this.players.get(uuid);
        if (record) {
            if (record.marker && window.AtlasMap && window.AtlasMap.map) {
                window.AtlasMap.map.removeLayer(record.marker);
            }
            if (record.trailLine && window.AtlasMap && window.AtlasMap.map) {
                window.AtlasMap.map.removeLayer(record.trailLine);
            }

            if (window.AtlasMap && record.currentPos) {
                const offPlayer = {
                    uuid: uuid,
                    name: record.data?.name || "Player",
                    x: record.currentPos.x,
                    y: record.data?.y || 64,
                    z: record.currentPos.z,
                    yaw: record.currentPos.yaw,
                    world: record.data?.dimension || record.data?.world || window.AtlasMap.currentDimension,
                    lastSeen: Date.now()
                };
                const existingIdx = window.AtlasMap.offlinePlayersData.findIndex(p => p.uuid === uuid);
                if (existingIdx !== -1) {
                    window.AtlasMap.offlinePlayersData[existingIdx] = offPlayer;
                } else {
                    window.AtlasMap.offlinePlayersData.push(offPlayer);
                }
                window.AtlasMap.refreshOfflineMarkers();
            }

            this.players.delete(uuid);
            this._renderRoster();

            if (this.selectedUuid === uuid) {
                this.selectedUuid = null;
                document.getElementById("inspector-empty")?.classList.remove("hidden");
                document.getElementById("inspector-body")?.classList.add("hidden");
            }
        }
    }

    _initLoop() {
        const animate = () => {
            for (const record of this.players.values()) {
                if (!record || !record.currentPos || !record.targetPos) continue;
                if (isNaN(record.targetPos.x) || isNaN(record.targetPos.z)) continue;
                if (isNaN(record.currentPos.x)) record.currentPos.x = record.targetPos.x;
                if (isNaN(record.currentPos.z)) record.currentPos.z = record.targetPos.z;

                // Linear lerp (smoothing factor 0.25)
                record.currentPos.x += (record.targetPos.x - record.currentPos.x) * 0.25;
                record.currentPos.z += (record.targetPos.z - record.currentPos.z) * 0.25;

                // Shortest-arc yaw interpolation
                let targetYaw = isNaN(record.targetPos.yaw) ? 0 : record.targetPos.yaw;
                let curYaw = isNaN(record.currentPos.yaw) ? 0 : record.currentPos.yaw;
                let diffYaw = (targetYaw - curYaw) % 360;
                if (diffYaw < -180) diffYaw += 360;
                else if (diffYaw > 180) diffYaw -= 360;
                record.currentPos.yaw = curYaw + diffYaw * 0.25;

                try {
                    record.marker.setLatLng([record.currentPos.z, record.currentPos.x]);
                } catch (_) {}

                // Update movement trail
                if (record.trail && this.showTrails) {
                    const last = record.trail[record.trail.length - 1];
                    const distSq = (record.currentPos.x - last[1]) ** 2 + (record.currentPos.z - last[0]) ** 2;
                    if (distSq > 2.25) { // moved > 1.5 blocks
                        record.trail.push([record.currentPos.z, record.currentPos.x]);
                        if (record.trail.length > this.maxTrailLength) {
                            record.trail.shift();
                        }
                        if (!record.trailLine && window.AtlasMap?.map) {
                            record.trailLine = L.polyline(record.trail, {
                                color: "#38bdf8",
                                weight: 2.5,
                                opacity: 0.75,
                                dashArray: "4, 4"
                            }).addTo(window.AtlasMap.map);
                        } else if (record.trailLine) {
                            record.trailLine.setLatLngs(record.trail);
                        }
                    }
                }

                const p = record.data;
                const isSelected = p.uuid === this.selectedUuid;
                const el = document.getElementById("marker-" + p.uuid);
                if (el) {
                    if (isSelected) {
                        el.classList.add("selected");
                    } else {
                        el.classList.remove("selected");
                    }
                    const screenAngle = this.toScreenYaw(record.currentPos.yaw);
                    const needle = el.querySelector(".player-pointer-needle");
                    if (needle) {
                        needle.style.transform = `rotate(${screenAngle}deg)`;
                    }
                    const cone = el.querySelector(".player-heading-cone");
                    if (cone) {
                        cone.style.transform = `rotate(${screenAngle}deg)`;
                    }
                }

                if (this.followSelected && isSelected) {
                    window.AtlasMap.panTo(record.currentPos.x, record.currentPos.z);
                }
            }
            requestAnimationFrame(animate);
        };
        requestAnimationFrame(animate);
    }

    toggleTrails(show) {
        this.showTrails = (show !== undefined) ? show : !this.showTrails;
        localStorage.setItem("atlas_show_trails", this.showTrails ? "true" : "false");
        for (const record of this.players.values()) {
            if (!this.showTrails && record.trailLine && window.AtlasMap?.map) {
                window.AtlasMap.map.removeLayer(record.trailLine);
                record.trailLine = null;
            }
        }
    }

    toggleLabels(show) {
        this.showLabels = (show !== undefined) ? show : !this.showLabels;
        localStorage.setItem("atlas_show_labels", this.showLabels ? "true" : "false");
        document.querySelectorAll(".atlas-player-marker-container, .atlas-offline-marker-container").forEach(el => {
            el.classList.toggle("hide-labels", !this.showLabels);
        });
    }

    toggleCones(show) {
        this.showCones = (show !== undefined) ? show : !this.showCones;
        localStorage.setItem("atlas_show_cones", this.showCones ? "true" : "false");
        document.querySelectorAll(".atlas-player-marker-container, .atlas-offline-marker-container").forEach(el => {
            el.classList.toggle("hide-cones", !this.showCones);
        });
    }

    setTrailLength(len) {
        this.maxTrailLength = Math.max(5, Math.min(200, len));
        localStorage.setItem("atlas_trail_len", String(this.maxTrailLength));
    }

    _renderRoster() {
        const rosterEl = document.getElementById("roster-online");
        if (!rosterEl) return;

        rosterEl.innerHTML = "";
        const playerList = Array.from(this.allOnlinePlayers.values());

        if (playerList.length === 0) {
            rosterEl.innerHTML = `<li class="empty-roster-msg">No active players online.</li>`;
            return;
        }

        for (const p of playerList) {
            if (this.filterText && !p.name.toLowerCase().includes(this.filterText)) {
                continue;
            }

            const initial = (p.name && p.name.length > 0) ? p.name.charAt(0).toUpperCase() : 'P';
            const fallbackAvatar = `data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='28' height='28' viewBox='0 0 28 28'><rect width='28' height='28' fill='%230284c7'/><text x='14' y='18' font-family='sans-serif' font-size='12' font-weight='bold' fill='%23ffffff' text-anchor='middle'>${initial}</text></svg>`;

            const pDim = p.dimension || p.world || "minecraft:overworld";
            const normDim = window.AtlasMap?._normalizeDimension(pDim) || "overworld";
            const badgeClass = normDim === "nether" ? "badge-nether" : (normDim === "end" ? "badge-end" : "badge-overworld");
            const badgeLabel = normDim === "nether" ? "NETHER" : (normDim === "end" ? "THE END" : "OVERWORLD");

            const li = document.createElement("li");
            if (p.uuid === this.selectedUuid) li.className = "active";
            li.innerHTML = `
                <img class="player-avatar" src="https://mc-heads.net/avatar/${p.uuid}/28" alt="${p.name}" onerror="this.onerror=null;this.src='${fallbackAvatar}';">
                <div class="player-meta">
                    <div style="display:flex; justify-content:space-between; align-items:center; gap:6px;">
                        <span class="player-name">${p.name}</span>
                        <span class="roster-dim-badge ${badgeClass}">${badgeLabel}</span>
                    </div>
                    <span class="player-sub">X: ${Math.round(p.x)}, Y: ${Math.round(p.y)}, Z: ${Math.round(p.z)}</span>
                </div>
            `;
            li.addEventListener("click", () => this.selectPlayer(p.uuid));
            rosterEl.appendChild(li);
        }
    }

    selectPlayer(uuid) {
        this.selectedUuid = uuid;
        const p = this.allOnlinePlayers.get(uuid) || this.players.get(uuid)?.data;
        if (p) {
            const playerDim = p.dimension || p.world || "minecraft:overworld";
            const currentDimNorm = window.AtlasMap?._normalizeDimension(window.AtlasMap.currentDimension);
            const playerDimNorm = window.AtlasMap?._normalizeDimension(playerDim);

            if (currentDimNorm !== playerDimNorm && window.AtlasMap) {
                window.AtlasMap.setDimension(playerDim);
                document.querySelectorAll(".dim-btn").forEach(b => {
                    const bDim = b.getAttribute("data-dim");
                    b.classList.toggle("active", window.AtlasMap._normalizeDimension(bDim) === playerDimNorm);
                });
            }

            setTimeout(() => {
                const record = this.players.get(uuid);
                if (record && record.currentPos) {
                    window.AtlasMap?.panTo(record.currentPos.x, record.currentPos.z);
                } else if (p.x !== undefined && p.z !== undefined) {
                    window.AtlasMap?.panTo(p.x, p.z);
                }
            }, 50);
        }

        if (window.AtlasInspector) {
            window.AtlasInspector.inspect(uuid);
        }
        this._renderRoster();
    }
}

window.AtlasTelemetry = new TelemetryRenderer();
