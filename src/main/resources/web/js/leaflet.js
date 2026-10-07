// src/main/resources/web/js/leaflet.js
(function(window) {
    "use strict";

    const L = {
        version: "1.9.4-atlas",
        CRS: {
            Simple: {
                project: function(latlng) { return { x: latlng.lng, y: latlng.lat }; },
                unproject: function(point) { return { lat: point.y, lng: point.x }; }
            }
        },
        Util: {
            extend: Object.assign
        },
        Transformation: function(a, b, c, d) {
            return { a, b, c, d };
        }
    };

    function DomUtil_create(tagName, className, container) {
        const el = document.createElement(tagName);
        if (className) el.className = className;
        if (container) container.appendChild(el);
        return el;
    }

    class LeafletMap {
        constructor(id, options) {
            this.container = typeof id === "string" ? document.getElementById(id) : id;
            if (this.container) this.container.classList.add("leaflet-container");
            this.options = Object.assign({ center: [0, 0], zoom: 0, minZoom: -3, maxZoom: 3 }, options);
            this.center = { x: this.options.center[1], z: this.options.center[0] };
            this.zoom = this.options.zoom;
            this.layers = [];
            this.listeners = {};

            this.pane = DomUtil_create("div", "leaflet-pane", this.container);
            this.tilePane = DomUtil_create("div", "leaflet-tile-pane", this.pane);
            this.markerPane = DomUtil_create("div", "leaflet-marker-pane", this.pane);
            this.overlayPane = DomUtil_create("div", "leaflet-overlay-pane", this.pane);

            this._initEvents();
            this._render();
        }

        _initEvents() {
            let isDragging = false;
            let startX, startY;
            let startCenterX, startCenterZ;
            let rafRenderId = null;

            const scheduleRender = () => {
                if (!rafRenderId) {
                    rafRenderId = requestAnimationFrame(() => {
                        rafRenderId = null;
                        this._render();
                    });
                }
            };

            this.container.addEventListener("mousedown", (e) => {
                if (e.button !== 0) return;
                isDragging = true;
                startX = e.clientX;
                startY = e.clientY;
                startCenterX = this.center.x;
                startCenterZ = this.center.z;
                this.container.style.cursor = "grabbing";
            });

            window.addEventListener("mousemove", (e) => {
                const rect = this.container.getBoundingClientRect();
                const mouseX = e.clientX - rect.left;
                const mouseY = e.clientY - rect.top;

                const worldX = Math.round(this.center.x + (mouseX - rect.width / 2) / this.getScale());
                const worldZ = Math.round(this.center.z + (mouseY - rect.height / 2) / this.getScale());

                this.fire("mousemove", { latlng: { lat: worldZ, lng: worldX } });

                if (!isDragging) return;
                const dx = (e.clientX - startX) / this.getScale();
                const dz = (e.clientY - startY) / this.getScale();
                this.center.x = startCenterX - dx;
                this.center.z = startCenterZ - dz;
                scheduleRender();
            });

            window.addEventListener("mouseup", () => {
                if (isDragging) {
                    isDragging = false;
                    this.container.style.cursor = "grab";
                    this._render();
                    this.fire("moveend");
                }
            });

            this.container.addEventListener("click", (e) => {
                if (Math.abs(e.clientX - startX) > 4 || Math.abs(e.clientY - startY) > 4) {
                    return; // was dragging, ignore
                }
                const rect = this.container.getBoundingClientRect();
                const mouseX = e.clientX - rect.left;
                const mouseY = e.clientY - rect.top;
                const worldX = Math.round(this.center.x + (mouseX - rect.width / 2) / this.getScale());
                const worldZ = Math.round(this.center.z + (mouseY - rect.height / 2) / this.getScale());
                this.fire("click", { latlng: { lat: worldZ, lng: worldX } });
            });

            this.container.addEventListener("wheel", (e) => {
                e.preventDefault();
                if (e.deltaY < 0) {
                    this.setZoom(this.zoom + 1);
                } else if (e.deltaY > 0) {
                    this.setZoom(this.zoom - 1);
                }
            }, { passive: false });

            window.addEventListener("keydown", (e) => {
                if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA")) return;
                if (e.key === "+" || e.key === "=") {
                    this.setZoom(this.zoom + 1);
                } else if (e.key === "-" || e.key === "_") {
                    this.setZoom(this.zoom - 1);
                } else if (e.key === "0") {
                    this.setZoom(0);
                } else if (e.key === "f" || e.key === "F") {
                    window.AtlasMap?.centerFirstPlayer();
                } else if (e.key === "r" || e.key === "R") {
                    window.AtlasMap?.refreshMap();
                } else if (e.key === "o" || e.key === "O") {
                    window.AtlasMap?.toggleOfflineMarkers();
                } else if (e.key === "m" || e.key === "M") {
                    window.AtlasMap?.toggleMeasureTool();
                } else if (e.key === "s" || e.key === "S") {
                    window.AtlasApp?.toggleSettingsModal();
                }
            });

            window.addEventListener("resize", () => this._render());
        }

        getScale() {
            return Math.pow(2, this.zoom);
        }

        setView(centerLatLng, zoom) {
            this.center.x = centerLatLng[1];
            this.center.z = centerLatLng[0];
            if (zoom !== undefined) this.zoom = Math.max(this.options.minZoom, Math.min(this.options.maxZoom, zoom));
            this._render();
            this.fire("moveend");
            return this;
        }

        setZoom(zoom) {
            const nextZoom = Math.max(this.options.minZoom, Math.min(this.options.maxZoom, zoom));
            if (nextZoom !== this.zoom) {
                this.zoom = nextZoom;
                this._render();
                this.fire("zoomend");
            }
            return this;
        }

        getZoom() { return this.zoom; }
        getCenter() { return { lat: this.center.z, lng: this.center.x }; }

        addLayer(layer) {
            if (this.layers.indexOf(layer) === -1) {
                this.layers.push(layer);
                layer.onAdd(this);
            }
            return this;
        }

        removeLayer(layer) {
            const idx = this.layers.indexOf(layer);
            if (idx !== -1) {
                this.layers.splice(idx, 1);
                layer.onRemove(this);
            }
            return this;
        }

        hasLayer(layer) {
            if (!layer) return false;
            if (this.layers.indexOf(layer) !== -1) return true;
            for (const l of this.layers) {
                if (l && typeof l.hasLayer === "function" && l.hasLayer(layer)) return true;
            }
            return false;
        }

        latLngToContainerPoint(latlng) {
            const rect = this.container.getBoundingClientRect();
            const px = (latlng.lng - this.center.x) * this.getScale() + rect.width / 2;
            const py = (latlng.lat - this.center.z) * this.getScale() + rect.height / 2;
            return { x: px, y: py };
        }

        _render() {
            for (const layer of this.layers) {
                if (layer.redraw) layer.redraw();
            }
        }

        on(event, fn) {
            if (!this.listeners[event]) this.listeners[event] = [];
            this.listeners[event].push(fn);
            return this;
        }

        fire(event, data) {
            if (this.listeners[event]) {
                for (const fn of this.listeners[event]) fn(data);
            }
        }
    }

    class TileLayer {
        constructor(urlTemplate, options) {
            this.urlTemplate = urlTemplate || "";
            this.options = Object.assign({ tileSize: 256 }, options);
            this.tiles = new window.Map();
            this.map = null;
        }

        addTo(map) {
            map.addLayer(this);
            return this;
        }

        onAdd(map) {
            this.map = map;
            this.container = DomUtil_create("div", "leaflet-tile-container", map.tilePane);
            this.redraw();
        }

        onRemove() {
            if (this.container && this.container.parentNode) {
                this.container.parentNode.removeChild(this.container);
            }
            this.tiles.clear();
            this.map = null;
        }

        setUrl(newUrl) {
            this.urlTemplate = newUrl;
            if (this.tiles) {
                for (const img of this.tiles.values()) {
                    img.onload = null;
                    img.onerror = null;
                    if (img.parentNode) img.parentNode.removeChild(img);
                }
                this.tiles.clear();
            }
            this.redraw();
        }

        refreshTile(tileX, tileZ) {
            for (const [key, img] of this.tiles.entries()) {
                if (key.startsWith(`${tileX}:${tileZ}:`)) {
                    const clean = img.src.split("?")[0];
                    img.src = `${clean}?t=${Date.now()}`;
                }
            }
        }

        refreshAll() {
            const t = Date.now();
            for (const [key, img] of this.tiles.entries()) {
                const clean = img.src.split("?")[0];
                img.src = `${clean}?t=${t}`;
            }
        }

        redraw() {
            if (!this.map) return;
            const rect = this.map.container.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return;

            const scale = this.map.getScale();
            const tileSize = this.options.tileSize;
            const currentZoom = this.map.zoom;

            const halfW = rect.width / (2 * scale);
            const halfH = rect.height / (2 * scale);

            const minBlockX = this.map.center.x - halfW;
            const maxBlockX = this.map.center.x + halfW;
            const minBlockZ = this.map.center.z - halfH;
            const maxBlockZ = this.map.center.z + halfH;

            const minTileX = Math.floor(minBlockX / tileSize);
            const maxTileX = Math.floor(maxBlockX / tileSize);
            const minTileZ = Math.floor(minBlockZ / tileSize);
            const maxTileZ = Math.floor(maxBlockZ / tileSize);

            const centerTileX = Math.floor(this.map.center.x / tileSize);
            const centerTileZ = Math.floor(this.map.center.z / tileSize);

            // Collect all viewport tile candidates
            const candidateTiles = [];
            for (let tx = minTileX; tx <= maxTileX; tx++) {
                for (let tz = minTileZ; tz <= maxTileZ; tz++) {
                    const distSq = (tx - centerTileX) ** 2 + (tz - centerTileZ) ** 2;
                    candidateTiles.push({ tx, tz, distSq });
                }
            }

            // Prioritize center tiles outwards (spiral sort)
            candidateTiles.sort((a, b) => a.distSq - b.distSq);

            // Enforce a strict tile budget to eliminate network congestion and DOM thrashing
            const maxBudget = (currentZoom >= 0) ? 90 : (currentZoom === -1 ? 110 : (currentZoom === -2 ? 130 : 150));
            const tilesToLoad = candidateTiles.slice(0, maxBudget);

            const neededCurrentKeys = new Set();

            for (const { tx, tz } of tilesToLoad) {
                const key = `${tx}:${tz}:${currentZoom}`;
                neededCurrentKeys.add(key);

                let img = this.tiles.get(key);
                if (!img) {
                    img = document.createElement("img");
                    img.className = "leaflet-tile";
                    img.style.imageRendering = "pixelated";
                    img._tx = tx;
                    img._tz = tz;
                    img._zoom = currentZoom;

                    const markLoaded = () => {
                        img.classList.add("leaflet-tile-loaded");
                        this._pruneOldTiles();
                    };
                    img.onload = markLoaded;
                    img.onerror = () => {
                        img.classList.add("leaflet-tile-loaded");
                        this._pruneOldTiles();
                    };

                    let url;
                    if (typeof this.getTileUrl === "function") {
                        url = this.getTileUrl({ x: tx, y: tz, z: currentZoom });
                    } else {
                        url = this.urlTemplate
                            .replace("{x}", tx)
                            .replace("{z}", tz)
                            .replace("{y}", tz)
                            .replace("{zoom}", currentZoom);
                    }

                    img.src = url;
                    if (img.complete) {
                        markLoaded();
                    }
                    this.container.appendChild(img);
                    this.tiles.set(key, img);
                }
            }

            // Reposition and scale active tiles in DOM
            for (const [key, img] of this.tiles.entries()) {
                const tileZoom = (img._zoom !== undefined) ? img._zoom : currentZoom;
                const renderedTileWidth = Math.ceil(tileSize * scale) + 1;
                const worldX = img._tx * tileSize;
                const worldZ = img._tz * tileSize;
                const point = this.map.latLngToContainerPoint({ lat: worldZ, lng: worldX });

                img.style.left = `${Math.floor(point.x)}px`;
                img.style.top = `${Math.floor(point.y)}px`;
                img.style.width = `${renderedTileWidth}px`;
                img.style.height = `${renderedTileWidth}px`;
                img.style.zIndex = (tileZoom === currentZoom) ? "10" : "5";
            }

            // Prune current zoom tiles that exceed viewport budget
            for (const [key, img] of this.tiles.entries()) {
                const tileZoom = (img._zoom !== undefined) ? img._zoom : currentZoom;
                if (tileZoom === currentZoom) {
                    if (!neededCurrentKeys.has(key)) {
                        img.onload = null;
                        img.onerror = null;
                        if (img.parentNode) img.parentNode.removeChild(img);
                        this.tiles.delete(key);
                    }
                } else if (Math.abs(tileZoom - currentZoom) > 1) {
                    // Instantly drop tiles that are more than 1 zoom level away
                    img.onload = null;
                    img.onerror = null;
                    if (img.parentNode) img.parentNode.removeChild(img);
                    this.tiles.delete(key);
                }
            }
            this._pruneOldTiles();
        }

        _pruneOldTiles() {
            if (!this.map) return;
            const currentZoom = this.map.zoom;
            for (const [key, img] of this.tiles.entries()) {
                const tileZoom = (img._zoom !== undefined) ? img._zoom : currentZoom;
                if (tileZoom !== currentZoom) {
                    const currentKey = `${img._tx}:${img._tz}:${currentZoom}`;
                    const currentImg = this.tiles.get(currentKey);
                    if (currentImg && currentImg.classList.contains("leaflet-tile-loaded")) {
                        img.onload = null;
                        img.onerror = null;
                        if (img.parentNode) img.parentNode.removeChild(img);
                        this.tiles.delete(key);
                    }
                }
            }
            // Strict memory cap: If total tiles exceed 180, prune furthest non-current tiles
            if (this.tiles.size > 180) {
                for (const [key, img] of this.tiles.entries()) {
                    if (img._zoom !== currentZoom) {
                        img.onload = null;
                        img.onerror = null;
                        if (img.parentNode) img.parentNode.removeChild(img);
                        this.tiles.delete(key);
                        if (this.tiles.size <= 150) break;
                    }
                }
            }
        }
    }

    class Marker {
        constructor(latlng, options) {
            this.latlng = { lat: latlng[0], lng: latlng[1] };
            this.options = Object.assign({}, options);
            this.el = null;
            this.map = null;
            this.listeners = {};
        }

        addTo(map) {
            map.addLayer(this);
            return this;
        }

        on(event, fn) {
            if (!this.listeners[event]) this.listeners[event] = [];
            this.listeners[event].push(fn);
            return this;
        }

        fire(event, data) {
            if (this.listeners[event]) {
                for (const fn of this.listeners[event]) fn(data);
            }
        }

        onAdd(map) {
            this.map = map;
            this.el = DomUtil_create("div", "leaflet-marker-icon", map.markerPane);
            if (this.options.icon && this.options.icon.className) {
                this.el.className += " " + this.options.icon.className;
            }
            if (this.options.icon && this.options.icon.iconSize) {
                this.el.style.width = `${this.options.icon.iconSize[0]}px`;
                this.el.style.height = `${this.options.icon.iconSize[1]}px`;
            }
            if (this.options.icon && this.options.icon.html) {
                this.el.innerHTML = this.options.icon.html;
            }
            this.el.addEventListener("click", (e) => {
                e.stopPropagation();
                if (this.options.onClick) this.options.onClick(e);
                this.fire("click", e);
            });
            this.redraw();
        }

        onRemove() {
            if (this.el && this.el.parentNode) {
                this.el.parentNode.removeChild(this.el);
            }
            this.map = null;
        }

        setLatLng(latlng) {
            this.latlng = { lat: latlng[0], lng: latlng[1] };
            this.redraw();
        }

        setIconHtml(html) {
            if (this.el) this.el.innerHTML = html;
        }

        redraw() {
            if (!this.map || !this.el) return;
            const pt = this.map.latLngToContainerPoint(this.latlng);
            this.el.style.left = `${pt.x}px`;
            this.el.style.top = `${pt.y}px`;
            this.el.style.transform = "translate(-50%, -50%)";
        }
    }

    class LayerGroup {
        constructor(layers) {
            this.layers = layers ? layers.slice() : [];
            this.map = null;
        }

        addTo(map) {
            map.addLayer(this);
            return this;
        }

        onAdd(map) {
            this.map = map;
            for (const layer of this.layers) {
                layer.onAdd(map);
            }
        }

        onRemove(map) {
            for (const layer of this.layers) {
                layer.onRemove(map);
            }
            this.map = null;
        }

        addLayer(layer) {
            if (this.layers.indexOf(layer) === -1) {
                this.layers.push(layer);
                if (this.map) {
                    layer.onAdd(this.map);
                }
            }
            return this;
        }

        removeLayer(layer) {
            const idx = this.layers.indexOf(layer);
            if (idx !== -1) {
                this.layers.splice(idx, 1);
                if (this.map) {
                    layer.onRemove(this.map);
                }
            }
            return this;
        }

        hasLayer(layer) {
            if (!layer) return false;
            return this.layers.indexOf(layer) !== -1;
        }

        clearLayers() {
            if (this.map) {
                for (const layer of this.layers) {
                    layer.onRemove(this.map);
                }
            }
            this.layers = [];
            return this;
        }

        redraw() {
            for (const layer of this.layers) {
                if (layer.redraw) layer.redraw();
            }
        }
    }

    class Circle {
        constructor(latlng, options) {
            this.latlng = { lat: latlng[0], lng: latlng[1] };
            this.options = Object.assign({ radius: 25, color: "#ef4444" }, options);
            this.map = null;
            this.el = null;
        }

        addTo(map) {
            map.addLayer(this);
            return this;
        }

        onAdd(map) {
            this.map = map;
            this.el = DomUtil_create("div", "leaflet-circle-overlay", map.overlayPane);
            this.el.style.position = "absolute";
            this.el.style.borderRadius = "50%";
            this.el.style.pointerEvents = "none";
            this.el.style.border = `2px dashed ${this.options.color}`;
            this.el.style.background = "rgba(239, 68, 68, 0.15)";
            this.redraw();
        }

        onRemove() {
            if (this.el && this.el.parentNode) this.el.parentNode.removeChild(this.el);
            this.map = null;
        }

        setLatLng(latlng) {
            this.latlng = { lat: latlng[0], lng: latlng[1] };
            this.redraw();
        }

        setRadius(r) {
            this.options.radius = r;
            this.redraw();
        }

        redraw() {
            if (!this.map || !this.el) return;
            const pt = this.map.latLngToContainerPoint(this.latlng);
            const scale = this.map.getScale();
            const radPx = this.options.radius * scale;

            this.el.style.left = `${pt.x - radPx}px`;
            this.el.style.top = `${pt.y - radPx}px`;
            this.el.style.width = `${radPx * 2}px`;
            this.el.style.height = `${radPx * 2}px`;
        }
    }

    class Polyline {
        constructor(latlngs, options) {
            this.latlngs = (latlngs || []).map(ll => ({ lat: ll[0], lng: ll[1] }));
            this.options = Object.assign({
                color: "#38bdf8",
                weight: 3,
                opacity: 0.8,
                dashArray: null
            }, options);
            this.map = null;
            this.path = null;
        }

        addTo(map) {
            map.addLayer(this);
            return this;
        }

        onAdd(map) {
            this.map = map;
            if (!map._svgOverlay) {
                map._svgOverlay = DomUtil_create("svg", "leaflet-svg-overlay", map.overlayPane);
                map._svgOverlay.style.position = "absolute";
                map._svgOverlay.style.left = "0";
                map._svgOverlay.style.top = "0";
                map._svgOverlay.style.width = "100%";
                map._svgOverlay.style.height = "100%";
                map._svgOverlay.style.pointerEvents = "none";
                map._svgOverlay.style.zIndex = "400";
            }
            this.path = document.createElementNS("http://www.w3.org/2000/svg", "path");
            this.path.setAttribute("fill", "none");
            this.path.setAttribute("stroke", this.options.color);
            this.path.setAttribute("stroke-width", this.options.weight);
            this.path.setAttribute("stroke-opacity", this.options.opacity);
            this.path.setAttribute("stroke-linecap", "round");
            this.path.setAttribute("stroke-linejoin", "round");
            if (this.options.dashArray) {
                this.path.setAttribute("stroke-dasharray", this.options.dashArray);
            }
            map._svgOverlay.appendChild(this.path);
            this.redraw();
        }

        onRemove() {
            if (this.path && this.path.parentNode) {
                this.path.parentNode.removeChild(this.path);
            }
            this.map = null;
        }

        setLatLngs(latlngs) {
            this.latlngs = (latlngs || []).map(ll => ({ lat: ll[0], lng: ll[1] }));
            this.redraw();
        }

        redraw() {
            if (!this.map || !this.path || !this.latlngs || this.latlngs.length < 2) {
                if (this.path) this.path.setAttribute("d", "");
                return;
            }

            let d = "";
            for (let i = 0; i < this.latlngs.length; i++) {
                const pt = this.map.latLngToContainerPoint(this.latlngs[i]);
                d += (i === 0 ? "M " : " L ") + Math.round(pt.x) + " " + Math.round(pt.y);
            }
            this.path.setAttribute("d", d);
        }
    }

    L.map = (id, opts) => new LeafletMap(id, opts);
    L.tileLayer = (url, opts) => new TileLayer(url, opts);
    L.marker = (latlng, opts) => new Marker(latlng, opts);
    L.circle = (latlng, opts) => new Circle(latlng, opts);
    L.polyline = (latlngs, opts) => new Polyline(latlngs, opts);
    L.layerGroup = (layers) => new LayerGroup(layers);
    L.divIcon = (opts) => opts;

    window.L = L;
})(window);
