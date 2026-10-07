# 🛰️ A.T.L.A.S. — Admin Tool for Live Area Surveillance

> **A high-performance, real-time cyber-tactical surveillance and moderation dashboard for Minecraft (Paper / Spigot 1.21+).**

![Java 21](https://img.shields.io/badge/Java-21-orange.svg)
![Paper/Spigot](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)
![WebSockets](https://img.shields.io/badge/Realtime-WebSockets-blue.svg)
![Self-Hosted](https://img.shields.io/badge/Architecture-100%25%20Self--Hosted-darkgreen.svg)
![License](https://img.shields.io/badge/License-MIT-purple.svg)

---

## ⚡ Overview

**A.T.L.A.S.** transforms your Minecraft server administration experience by delivering an authenticated, browser-based surveillance operations center. Designed with an obsidian glassmorphic aesthetic, it streams live player coordinates, inventory state, block ripple waves, and proximity voice audio directly into a responsive Leaflet-powered tactical map.

---

## 🌟 Core Features

### 🗺️ Live Slippy Map with Authentic World Seed
- **Zero Chunk Gaps:** Renders 256×256 topographical tiles with 1 block = 1 pixel accuracy.
- **Real Seed Alignment:** Unexplored areas procedurally align with authentic Minecraft vanilla biomes directly from your world seed.
- **Cinematic Defocus Blur:** Unexplored/uncharted territory receives an intense Bokeh defocus blur and 30% fog-of-war dimming, making active player areas stand out with razor-sharp clarity.
- **Live Block Waves:** Live block breaks and placements emit tactical ripple radar waves to instantly spot mining clusters or griefing.

### 🎧 Proximity Voice Audio & Radio Broadcast
- **Simple Voice Chat Hook:** Hooks directly into Simple Voice Chat (UDP port `27995`) with zero packet collision.
- **Spyglass Vicinity Interception:** Listen to player conversations in real time within an operator radius.
- **Broadcasting Suite:** Broadcast announcements globally, by dimension, or target direct player ear channels via Web Audio API.
- **100% Ephemeral Audio:** Audio packets are decoded in volatile memory only—never recorded or stored on disk.

### 🛡️ Remote Player Inspector & Moderation Ledger
- **Live Roster:** Displays online and offline players with custom heads, health, food, gamemode, and coordinates.
- **36-Slot Inventory Viewer:** Inspect live inventories, armor, and off-hand items directly in the browser.
- **Remote Moderation Tools:** Instant actions: Kick, Ban, Mute, Teleport, Gamemode Switch, and Command Dispatch.
- **Atomic Offline Ledger:** Commands queued for offline players persist safely and execute automatically upon reconnection.

### 🔐 Cryptographic Authentication & Access Control
- **Single Persistent Link:** Administrators generate access links via `/atlas link` that stay valid across sessions.
- **Instant Revocation:** Invalidate access and disconnect all active dashboards anytime with `/atlas unlink` (or `/atlas close`).
- **Zero Cloud Dependence:** All WebSockets, HTTP tiles, and telemetry run directly from your server on port `27994`.

---

## 📋 Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/atlas link` | `atlas.admin` | Generates a persistent web surveillance dashboard link. |
| `/atlas unlink` / `/atlas close` | `atlas.admin` | Immediately revokes all active links and terminates sessions. |
| `/atlas reload` | `atlas.admin` | Reloads `config.yml` and clears tile memory/disk caches. |

---

## ⚙️ Configuration (`config.yml`)

```yaml
web:
  host: "0.0.0.0"
  http-port: 27994
  websocket-port: 27994
  ssl:
    enabled: false
    self-signed: true
  rate-limit:
    enabled: true
    burst-capacity: 60
    refill-per-second: 5

map:
  default-dimension: "minecraft:overworld"
  max-memory-tiles: 512
  disk-cache-enabled: true
  max-concurrent-chunk-reads: 4
  render-unloaded-chunks: true
  hillshading-intensity: 0.12
  world-spawn:
    enabled: false
    dimension: "minecraft:overworld"
    x: 0
    y: 64
    z: 0

telemetry:
  broadcast-frequency-hz: 15
  max-batch-size: 100

voice:
  enabled: true
  max-spyglass-radius: 100.0
```

---

## 🛠️ Building & Compiling

### Prerequisites
- **Java 21** or higher
- **Maven 3.8+**

### Compile from Source
```bash
git clone https://github.com/<your-username>/WebPlugin.git
cd WebPlugin
mvn clean package
```

The compiled and shaded plugin JAR will be located at:
```
target/atlas-1.0.0.jar
```

Copy `atlas-1.0.0.jar` into your Minecraft server's `plugins/` directory and restart the server.

---

## 📄 Privacy & Compliance
A.T.L.A.S. includes built-in, unauthenticated public disclosure pages compliant with server administration standards:
- **Privacy Policy:** `http://<your-ip>:27994/privacy.html`
- **Terms & Conditions:** `http://<your-ip>:27994/terms.html`
