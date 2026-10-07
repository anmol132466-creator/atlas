// src/main/resources/web/js/map-engine.js
class AtlasMapEngine {
    constructor() {
        this.currentDimension = "minecraft:overworld";
        this.map = null;
        this.tileLayer = null;
        this.offlineLayer = null;
        this.offlineMarkers = new Map();
        this.offlinePlayersData = [];
        this.showOfflinePlayers = localStorage.getItem("atlas_show_offline") !== "false";
        this.spyglassCircle = null;
        this.spyglassPlacingMode = false;
        this.worldSpawn = { x: 0, z: 0 };
        this.spawnMarker = null;
        this.detectedSeed = null;
        this.detectedVersion = "Java Edition";
        this.measuringMode = false;
        this.measureStart = null;
        this.measureLine = null;
        this.searchMarker = null;
        this._searchPinTimeout = null;
    }

    init() {
        this.map = L.map("map", {
            center: [0, 0],
            zoom: 0,
            minZoom: -3,
            maxZoom: 3
        });

        const tileUrl = this._getTileUrlTemplate(this.currentDimension);
        this.tileLayer = L.tileLayer(tileUrl, { tileSize: 256 });
        this.map.addLayer(this.tileLayer);

        this.offlineLayer = L.layerGroup();
        if (this.showOfflinePlayers) {
            this.map.addLayer(this.offlineLayer);
        }

        this._setupEvents();
        this._setupWsListeners();
        this._updateOfflineToggleUI();
    }

    _formatDimension(dim) {
        return dim.replace(":", "_").replace("/", "_");
    }

    _normalizeDimension(dim) {
        if (!dim) return "overworld";
        const d = dim.toLowerCase().replace("minecraft:", "").replace("the_", "");
        if (d.includes("nether")) return "nether";
        if (d.includes("end")) return "end";
        return "overworld";
    }

    _getTileUrlTemplate(dim) {
        const token = window.AtlasWS?.token || "";
        const formatted = this._formatDimension(dim);
        const query = token ? `?token=${encodeURIComponent(token)}` : "";
        return `/tiles/${formatted}/{zoom}/{x}_{z}.png${query}`;
    }

    setDimension(dimension) {
        this.currentDimension = dimension;
        const newUrl = this._getTileUrlTemplate(dimension);
        if (this.tileLayer) {
            this.tileLayer.setUrl(newUrl);
        }
        if (window.AtlasTelemetry) {
            window.AtlasTelemetry.clearDimensionPlayers();
        }
        this.refreshOfflineMarkers();
        this._updateSpawnMarker();

        if (window.AtlasWS) {
            window.AtlasWS.send({ type: "SUBSCRIBE_TELEMETRY", dimension: dimension });
        }
    }

    setWorldSpawn(x, z) {
        this.worldSpawn = { x, z };
        this._updateSpawnMarker();
    }

    _updateSpawnMarker() {
        if (!this.map) return;
        const curDimNorm = this._normalizeDimension(this.currentDimension);
        if (curDimNorm !== "overworld") {
            if (this.spawnMarker) {
                this.map.removeLayer(this.spawnMarker);
                this.spawnMarker = null;
            }
            return;
        }

        const x = this.worldSpawn?.x || 0;
        const z = this.worldSpawn?.z || 0;

        if (!this.spawnMarker) {
            const icon = L.divIcon({
                className: "atlas-spawn-marker-container",
                html: `
                    <div class="spawn-marker-wrap" data-tooltip="World Spawn [${x}, ${z}]">
                        <div class="spawn-pulse"></div>
                        <div class="spawn-badge">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                                <circle cx="12" cy="12" r="10"></circle>
                                <polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"></polygon>
                            </svg>
                            <span class="spawn-label">SPAWN</span>
                        </div>
                    </div>
                `,
                iconSize: [60, 30],
                iconAnchor: [30, 15]
            });
            this.spawnMarker = L.marker([z, x], { icon: icon });
            this.spawnMarker.on("click", () => {
                this.panTo(x, z);
                window.AtlasApp?.showToast(`World Spawn: X: ${x}, Z: ${z}`);
            });
            this.map.addLayer(this.spawnMarker);
        } else {
            this.spawnMarker.setLatLng([z, x]);
            if (this.spawnMarker.el) {
                const wrap = this.spawnMarker.el.querySelector(".spawn-marker-wrap");
                if (wrap) wrap.setAttribute("data-tooltip", `World Spawn [${x}, ${z}]`);
            }
        }
    }

    setSeedInfo(seed, version) {
        this.detectedSeed = seed;
        if (version) this.detectedVersion = version;

        const seedValEl = document.getElementById("hud-seed-val");
        const verValEl = document.getElementById("hud-version-val");

        if (seedValEl && seed) {
            seedValEl.textContent = seed;
        }
        if (verValEl && version) {
            const cleanVer = version.replace("git-Paper-", "").replace("git-Purpur-", "").split(" ")[0].trim();
            verValEl.textContent = "JAVA " + cleanVer;
        }
    }

    centerSpawn() {
        this.panTo(this.worldSpawn.x, this.worldSpawn.z);
    }

    centerFirstPlayer() {
        if (window.AtlasTelemetry && window.AtlasTelemetry.players.size > 0) {
            const first = window.AtlasTelemetry.players.values().next().value;
            if (first && first.currentPos) {
                this.panTo(first.currentPos.x, first.currentPos.z);
                return true;
            }
        }
        this.centerSpawn();
        return false;
    }

    setOfflinePlayers(playerList) {
        this.offlinePlayersData = playerList || [];
        this.refreshOfflineMarkers();
    }

    toggleOfflineMarkers(visible) {
        this.showOfflinePlayers = (visible !== undefined) ? visible : !this.showOfflinePlayers;
        localStorage.setItem("atlas_show_offline", this.showOfflinePlayers ? "true" : "false");
        if (this.showOfflinePlayers) {
            if (this.map && (!this.map.hasLayer || !this.map.hasLayer(this.offlineLayer))) {
                this.map.addLayer(this.offlineLayer);
            }
            this.refreshOfflineMarkers();
        } else {
            if (this.map && this.offlineLayer) {
                this.map.removeLayer(this.offlineLayer);
            }
        }
        this._updateOfflineToggleUI();
        window.AtlasApp?.showToast(`Offline players ${this.showOfflinePlayers ? "VISIBLE" : "HIDDEN"}`);
    }

    _updateOfflineToggleUI() {
        const btn = document.getElementById("btn-toggle-offline");
        if (btn) {
            btn.classList.toggle("active", this.showOfflinePlayers);
            const statusTooltip = `Toggle Offline Players: ${this.showOfflinePlayers ? "ON" : "OFF"} (Key: O)`;
            btn.setAttribute("data-tooltip", statusTooltip);
            btn.setAttribute("title", statusTooltip);
            btn.setAttribute("aria-label", statusTooltip);
        }
        const chk = document.getElementById("setting-show-offline");
        if (chk) {
            chk.checked = this.showOfflinePlayers;
        }
    }

    refreshMap() {
        if (this.tileLayer) {
            this.tileLayer.refreshAll();
        }
        if (window.AtlasWS) {
            window.AtlasWS.send({ type: "FETCH_OFFLINE_ROSTER" });
            window.AtlasWS.send({ type: "SUBSCRIBE_TELEMETRY", dimension: this.currentDimension });
        }
        window.AtlasApp?.showToast("Map synchronized and refreshed");
    }

    refreshOfflineMarkers() {
        if (!this.offlineLayer) return;
        this.offlineLayer.clearLayers();
        this.offlineMarkers.clear();

        if (!this.showOfflinePlayers) return;

        const currentDimNorm = this._normalizeDimension(this.currentDimension);

        for (const p of this.offlinePlayersData) {
            if (p.x === undefined || p.z === undefined) continue;
            const pDimNorm = this._normalizeDimension(p.dimension || p.world || "minecraft:overworld");
            if (pDimNorm !== currentDimNorm) continue;

            const initial = (p.name && p.name.length > 0) ? p.name.charAt(0).toUpperCase() : 'P';
            const fallbackAvatar = `data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32' viewBox='0 0 32 32'><rect width='32' height='32' rx='4' fill='%23334155'/><text x='16' y='21' font-family='sans-serif' font-size='14' font-weight='bold' fill='%2394a3b8' text-anchor='middle'>${initial}</text></svg>`;
            let screenYaw = ((p.yaw || 0) + 180) % 360;
            if (screenYaw < 0) screenYaw += 360;

            const hideLabelsClass = (window.AtlasTelemetry && !window.AtlasTelemetry.showLabels) ? "hide-labels" : "";
            const hideConesClass = (window.AtlasTelemetry && !window.AtlasTelemetry.showCones) ? "hide-cones" : "";

            const icon = L.divIcon({
                className: `atlas-offline-marker-container ${hideLabelsClass} ${hideConesClass}`.trim(),
                html: `
                    <div class="player-marker-wrap offline-marker" id="marker-offline-${p.uuid}">
                        <div class="player-pointer-needle" style="transform: rotate(${screenYaw}deg)">
                            <div class="player-heading-arrow offline-arrow"></div>
                        </div>
                        <div class="player-pin-ring offline-ring">
                            <img class="marker-head offline-head" src="https://mc-heads.net/avatar/${p.uuid}/32" alt="${p.name || 'Player'}" onerror="this.onerror=null;this.src='${fallbackAvatar}';">
                        </div>
                        <div class="marker-label offline-label">
                            <span class="label-name">${p.name || 'Offline'}</span>
                            <span class="label-offline-badge">OFFLINE</span>
                        </div>
                    </div>
                `,
                iconSize: [36, 36],
                iconAnchor: [18, 18]
            });

            const marker = L.marker([p.z, p.x], { icon: icon });
            marker.on("click", () => {
                if (window.AtlasOffline) {
                    window.AtlasOffline.inspectOffline(p.uuid);
                }
            });
            this.offlineLayer.addLayer(marker);
            this.offlineMarkers.set(p.uuid, marker);
        }
    }

    removeOfflineMarker(uuid) {
        const marker = this.offlineMarkers.get(uuid);
        if (marker && this.offlineLayer) {
            this.offlineLayer.removeLayer(marker);
            this.offlineMarkers.delete(uuid);
        }
    }

    removeOfflineWaypoint() {
        if (this.offlineWaypointMarker && this.map) {
            this.map.removeLayer(this.offlineWaypointMarker);
            this.offlineWaypointMarker = null;
        }
    }

    setOfflineWaypoint(name, uuid, x, z, dimension, yaw) {
        if (dimension) {
            const curDimNorm = this._normalizeDimension(this.currentDimension);
            const targetDimNorm = this._normalizeDimension(dimension);
            if (curDimNorm !== targetDimNorm) {
                this.setDimension(dimension);
                document.querySelectorAll(".dim-btn").forEach(btn => {
                    const bDim = btn.getAttribute("data-dim");
                    btn.classList.toggle("active", this._normalizeDimension(bDim) === targetDimNorm);
                });
            }
        }
        this.panTo(x, z);

        // Highlight offline player marker if present
        const marker = this.offlineMarkers.get(uuid);
        if (marker && marker.el) {
            const wrap = marker.el.querySelector(".player-marker-wrap");
            if (wrap) {
                wrap.classList.add("selected");
                setTimeout(() => wrap.classList.remove("selected"), 3500);
            }
        }
    }

    setSpyglassSurveillance(x, z, radius) {
        if (!this.spyglassCircle) {
            this.spyglassCircle = L.circle([z, x], { radius: radius, color: "#ef4444" }).addTo(this.map);
        } else {
            this.spyglassCircle.setLatLng([z, x]);
            this.spyglassCircle.setRadius(radius);
        }
    }

    removeSpyglassSurveillance() {
        if (this.spyglassCircle) {
            this.map.removeLayer(this.spyglassCircle);
            this.spyglassCircle = null;
        }
    }

    _setupWsListeners() {
        if (!window.AtlasWS) return;

        window.AtlasWS.on("TILE_UPDATE", (data) => {
            const currentNorm = this._normalizeDimension(this.currentDimension);
            const dataNorm = this._normalizeDimension(data.dimension || "");
            if (currentNorm === dataNorm) {
                if (this.tileLayer) {
                    this.tileLayer.refreshTile(data.tileX, data.tileZ);
                }
            }
        });

        window.AtlasWS.on("BLOCK_CHANGE", (data) => {
            const currentNorm = this._normalizeDimension(this.currentDimension);
            const dataNorm = this._normalizeDimension(data.dimension || "");
            if (currentNorm === dataNorm) {
                this.showBlockChange(data.x, data.y, data.z, data.material, data.action, data.player);
            }
        });
    }

    showBlockChange(x, y, z, material, action, player) {
        if (!this.map || !this.map.overlayPane) return;

        const el = document.createElement("div");
        el.className = `block-change-marker ${action.toLowerCase()}`;
        const cleanMat = (material || "BLOCK").replace("MINECRAFT:", "").toLowerCase();
        el.innerHTML = `
            <div class="block-pulse"></div>
            <div class="block-tag">[${action}] ${cleanMat} ${player ? `(${player})` : ""}</div>
        `;

        if (!this.activeBlockChanges) this.activeBlockChanges = [];
        const item = { el, x, z };
        this.activeBlockChanges.push(item);

        const pt = this.map.latLngToContainerPoint({ lat: z, lng: x });
        el.style.left = `${pt.x}px`;
        el.style.top = `${pt.y}px`;

        this.map.overlayPane.appendChild(el);
        setTimeout(() => {
            if (el.parentNode) el.parentNode.removeChild(el);
            if (this.activeBlockChanges) {
                const idx = this.activeBlockChanges.indexOf(item);
                if (idx !== -1) this.activeBlockChanges.splice(idx, 1);
            }
        }, 3000);
    }

    _setupEvents() {
        const hudX = document.getElementById("hud-coord-x");
        const hudZ = document.getElementById("hud-coord-z");
        const hudZoom = document.getElementById("hud-zoom");

        const updateBlockChangePositions = () => {
            if (!this.map || !this.activeBlockChanges) return;
            for (const b of this.activeBlockChanges) {
                const pt = this.map.latLngToContainerPoint({ lat: b.z, lng: b.x });
                b.el.style.left = `${pt.x}px`;
                b.el.style.top = `${pt.y}px`;
            }
        };

        this.map.on("mousemove", (e) => {
            const worldX = Math.round(e.latlng.lng);
            const worldZ = Math.round(e.latlng.lat);
            if (hudX && hudZ) {
                hudX.textContent = worldX;
                hudZ.textContent = worldZ;
            }

            // Live preview rubberband while measuring
            if (this.measuringMode && this.measureStart && this.measureLine) {
                this.measureLine.setLatLngs([
                    [this.measureStart.z, this.measureStart.x],
                    [worldZ, worldX]
                ]);
                const dx = worldX - this.measureStart.x;
                const dz = worldZ - this.measureStart.z;
                const dist = Math.round(Math.sqrt(dx * dx + dz * dz));
                const distHud = document.getElementById("hud-measure-dist");
                if (distHud) {
                    distHud.classList.remove("hidden");
                    distHud.textContent = `Distance: ${dist}m (ΔX: ${dx}, ΔZ: ${dz})`;
                }
            }
        });

        this.map.on("moveend", updateBlockChangePositions);
        this.map.on("zoomend", () => {
            updateBlockChangePositions();
            if (hudZoom) {
                hudZoom.textContent = "Zoom " + this.map.getZoom();
            }
        });

        this.map.on("click", (e) => {
            const worldX = Math.round(e.latlng.lng);
            const worldZ = Math.round(e.latlng.lat);

            if (this.measuringMode) {
                if (!this.measureStart) {
                    this.measureStart = { x: worldX, z: worldZ };
                    if (!this.measureLine) {
                        this.measureLine = L.polyline([[worldZ, worldX], [worldZ, worldX]], {
                            color: "#f59e0b",
                            weight: 3,
                            dashArray: "6, 6"
                        }).addTo(this.map);
                    }
                    window.AtlasApp?.showToast(`Point A set at (${worldX}, ${worldZ}). Click destination point.`);
                } else {
                    const dx = worldX - this.measureStart.x;
                    const dz = worldZ - this.measureStart.z;
                    const dist = Math.round(Math.sqrt(dx * dx + dz * dz));
                    this.measureLine.setLatLngs([
                        [this.measureStart.z, this.measureStart.x],
                        [worldZ, worldX]
                    ]);
                    window.AtlasApp?.showToast(`Measured Distance: ${dist} blocks (ΔX: ${dx}, ΔZ: ${dz})`);
                    this.measureStart = null; // Lock measurement
                }
                return;
            }

            if (this.spyglassPlacingMode) {
                this.spyglassPlacingMode = false;
                document.getElementById("btn-toggle-spyglass")?.classList.remove("active");

                if (window.AtlasSpyglass) {
                    window.AtlasSpyglass.startSurveillance(worldX, 64, worldZ, 50);
                    window.AtlasInspector?.showToast("info", `Audio Monitor radar placed at (${worldX}, 64, ${worldZ})`);
                }
            }
        });

        // Floating HUD Map Controls
        document.getElementById("btn-map-zoom-in")?.addEventListener("click", () => {
            this.map.setZoom(this.map.getZoom() + 1);
        });

        document.getElementById("btn-map-zoom-out")?.addEventListener("click", () => {
            this.map.setZoom(this.map.getZoom() - 1);
        });

        document.getElementById("btn-map-focus")?.addEventListener("click", () => {
            this.centerFirstPlayer();
        });

        document.getElementById("btn-map-refresh")?.addEventListener("click", () => {
            this.refreshMap();
        });

        document.getElementById("btn-toggle-offline")?.addEventListener("click", () => {
            this.toggleOfflineMarkers();
        });

        document.getElementById("btn-toggle-measure")?.addEventListener("click", () => {
            this.toggleMeasureTool();
        });

        // Floating Coordinate Search Bar
        const coordInput = document.getElementById("input-coord-search");
        const coordClearBtn = document.getElementById("btn-coord-clear");
        const coordGoBtn = document.getElementById("btn-coord-go");

        if (coordInput) {
            coordInput.addEventListener("keydown", (e) => {
                if (e.key === "Enter") {
                    e.preventDefault();
                    this.jumpToCoordinates(coordInput.value);
                } else if (e.key === "Escape") {
                    this.clearSearchPin();
                    coordInput.value = "";
                    if (coordClearBtn) coordClearBtn.style.display = "none";
                    coordInput.blur();
                }
            });

            coordInput.addEventListener("input", () => {
                if (coordClearBtn) {
                    coordClearBtn.style.display = coordInput.value.trim().length > 0 ? "flex" : "none";
                }
            });
        }

        coordGoBtn?.addEventListener("click", () => {
            if (coordInput) {
                this.jumpToCoordinates(coordInput.value);
            }
        });

        coordClearBtn?.addEventListener("click", () => {
            if (coordInput) {
                coordInput.value = "";
                coordInput.focus();
            }
            if (coordClearBtn) coordClearBtn.style.display = "none";
            this.clearSearchPin();
        });
    }

    toggleMeasureTool(enabled) {
        this.measuringMode = (enabled !== undefined) ? enabled : !this.measuringMode;
        const btn = document.getElementById("btn-toggle-measure");
        if (btn) {
            btn.classList.toggle("active", this.measuringMode);
            const tip = `Distance Measure Tool: ${this.measuringMode ? "ACTIVE (Click map)" : "OFF"} (Key: M)`;
            btn.setAttribute("data-tooltip", tip);
            btn.setAttribute("title", tip);
            btn.setAttribute("aria-label", tip);
        }

        if (!this.measuringMode) {
            this._clearMeasurement();
            window.AtlasApp?.showToast("Measurement tool cleared");
        } else {
            window.AtlasApp?.showToast("Measure Tool: Click two points on the map");
        }
    }

    _clearMeasurement() {
        if (this.measureLine && this.map) {
            this.map.removeLayer(this.measureLine);
            this.measureLine = null;
        }
        this.measureStart = null;
        const distHud = document.getElementById("hud-measure-dist");
        if (distHud) distHud.classList.add("hidden");
    }

    panTo(x, z) {
        this.map.setView([z, x]);
    }

    getCenterCoords() {
        if (!this.map) return { x: 0, z: 0 };
        const center = this.map.getCenter();
        return { x: Math.round(center.lng), z: Math.round(center.lat) };
    }

    parseCoordinates(str) {
        if (!str || typeof str !== "string") return null;
        const trimmed = str.trim();
        if (!trimmed) return null;

        if (trimmed.toLowerCase() === "spawn") {
            return {
                x: this.worldSpawn?.x || 0,
                z: this.worldSpawn?.z || 0,
                label: `Spawn (${this.worldSpawn?.x || 0}, ${this.worldSpawn?.z || 0})`
            };
        }

        // Check for /tp or tp format e.g. /tp 100 64 -250 or /tp @p 100 64 -250
        const tpRegex = /^\/?tp(?:\s+@?[a-zA-Z0-9_]+)?\s+(-?\d+(?:\.\d+)?)(?:[\s,]+(-?\d+(?:\.\d+)?))?[\s,]+(-?\d+(?:\.\d+)?)$/i;
        const tpMatch = trimmed.match(tpRegex);
        if (tpMatch) {
            const val1 = Math.round(parseFloat(tpMatch[1]));
            const val2 = tpMatch[2] !== undefined ? Math.round(parseFloat(tpMatch[2])) : null;
            const val3 = Math.round(parseFloat(tpMatch[3]));
            if (val2 !== null) {
                return { x: val1, y: val2, z: val3, label: `X: ${val1}, Y: ${val2}, Z: ${val3}` };
            } else {
                return { x: val1, z: val3, label: `X: ${val1}, Z: ${val3}` };
            }
        }

        // Check for labeled format: x: 100, z: -250 or X=100 Y=64 Z=-250
        const xMatch = trimmed.match(/x\s*[:=]\s*(-?\d+(?:\.\d+)?)/i);
        const zMatch = trimmed.match(/z\s*[:=]\s*(-?\d+(?:\.\d+)?)/i);
        if (xMatch && zMatch) {
            const x = Math.round(parseFloat(xMatch[1]));
            const z = Math.round(parseFloat(zMatch[1]));
            const yMatch = trimmed.match(/y\s*[:=]\s*(-?\d+(?:\.\d+)?)/i);
            const y = yMatch ? Math.round(parseFloat(yMatch[1])) : null;
            return {
                x,
                z,
                y,
                label: y !== null ? `X: ${x}, Y: ${y}, Z: ${z}` : `X: ${x}, Z: ${z}`
            };
        }

        // Check for numbers separated by spaces, commas, or semicolons
        const cleaned = trimmed.replace(/[^\d\s,.;-]/g, " ").trim();
        const parts = cleaned.split(/[\s,;]+/).filter(Boolean).map(Number);

        if (parts.length >= 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
            const x = Math.round(parts[0]);
            const y = Math.round(parts[1]);
            const z = Math.round(parts[2]);
            return { x, y, z, label: `X: ${x}, Y: ${y}, Z: ${z}` };
        } else if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
            const x = Math.round(parts[0]);
            const z = Math.round(parts[1]);
            return { x, z, label: `X: ${x}, Z: ${z}` };
        }

        return null;
    }

    jumpToCoordinates(str) {
        const coords = this.parseCoordinates(str);
        if (!coords) {
            window.AtlasApp?.showToast("Invalid coordinates. Use: 100 -250 or X:100 Z:-250", "warning");
            return false;
        }

        if (!this.map) return false;

        const currentZoom = this.map.getZoom();
        const targetZoom = Math.max(currentZoom, 1);
        this.map.setView([coords.z, coords.x], targetZoom);
        this.placeSearchPin(coords.x, coords.z, coords.label);
        window.AtlasApp?.showToast(`Navigated to ${coords.label}`, "info");
        return true;
    }

    placeSearchPin(x, z, labelText) {
        this.clearSearchPin();

        if (!this.map) return;

        const icon = L.divIcon({
            className: "atlas-search-pin-container",
            html: `
                <div class="search-pin-wrap" title="Click to dismiss waypoint">
                    <div class="search-pin-icon">
                        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <circle cx="12" cy="12" r="10"></circle>
                            <line x1="12" y1="2" x2="12" y2="6"></line>
                            <line x1="12" y1="18" x2="12" y2="22"></line>
                            <line x1="2" y1="12" x2="6" y2="12"></line>
                            <line x1="18" y1="12" x2="22" y2="12"></line>
                        </svg>
                    </div>
                    <div class="search-pin-label">${labelText || `X: ${x}, Z: ${z}`}</div>
                    <div class="search-pin-pulse"></div>
                </div>
            `,
            iconSize: [36, 48],
            iconAnchor: [18, 48]
        });

        this.searchMarker = L.marker([z, x], { icon: icon });
        this.searchMarker.on("click", () => {
            this.clearSearchPin();
            window.AtlasApp?.showToast("Waypoint dismissed");
        });

        this.map.addLayer(this.searchMarker);

        // Auto dismiss after 15 seconds
        this._searchPinTimeout = setTimeout(() => {
            this.clearSearchPin();
        }, 15000);
    }

    clearSearchPin() {
        if (this._searchPinTimeout) {
            clearTimeout(this._searchPinTimeout);
            this._searchPinTimeout = null;
        }
        if (this.searchMarker && this.map) {
            this.map.removeLayer(this.searchMarker);
            this.searchMarker = null;
        }
    }
}

window.AtlasMap = new AtlasMapEngine();
