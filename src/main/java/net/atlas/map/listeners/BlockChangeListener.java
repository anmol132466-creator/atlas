// src/main/java/net/atlas/map/listeners/BlockChangeListener.java
package net.atlas.map.listeners;

import com.google.gson.JsonObject;
import net.atlas.map.render.LiveBlockBuffer;
import net.atlas.map.render.TileRenderer;
import net.atlas.websocket.AtlasWebSocketServer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BlockChangeListener implements Listener {

    private final TileRenderer tileRenderer;
    private final AtlasWebSocketServer webSocketServer;
    private final Map<String, Long> lastUpdatePerTile = new ConcurrentHashMap<>();

    /** Throttle map cleanup runs at most once every 60 seconds. */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    /** Throttle entries older than 5 seconds are purged. */
    private static final long THROTTLE_ENTRY_TTL_MS = 5_000L;
    private final AtomicLong lastCleanup = new AtomicLong(0L);

    public BlockChangeListener(TileRenderer tileRenderer, AtlasWebSocketServer webSocketServer) {
        this.tileRenderer = tileRenderer;
        this.webSocketServer = webSocketServer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        processChange(event.getBlock(), "PLACE", event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        processChange(event.getBlock(), "BREAK", event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        processChange(event.getBlock(), "FORM", null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        processChange(event.getBlock(), "FADE", null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            processChange(b, "EXPLODE", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            processChange(b, "EXPLODE", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        int bx = chunkX * 16;
        int bz = chunkZ * 16;
        int tx = Math.floorDiv(bx, 256);
        int tz = Math.floorDiv(bz, 256);
        String dimension = event.getWorld().getKey().asString();

        String tileKey = "chunkload:" + dimension + ":" + tx + ":" + tz;
        long now = System.currentTimeMillis();
        Long last = lastUpdatePerTile.get(tileKey);
        // Throttle chunk-load tile re-renders to once per 1.5 seconds per 256x256 tile
        if (last == null || (now - last) > 1500) {
            lastUpdatePerTile.put(tileKey, now);
            tileRenderer.invalidateTile(dimension, bx, bz);

            if (webSocketServer != null) {
                JsonObject tilePacket = new JsonObject();
                tilePacket.addProperty("type", "TILE_UPDATE");
                tilePacket.addProperty("dimension", dimension);
                tilePacket.addProperty("tileX", tx);
                tilePacket.addProperty("tileZ", tz);
                webSocketServer.broadcastJson(tilePacket.toString());
            }
        }
    }

    private void processChange(Block block, String action, Player player) {
        String dimension = block.getWorld().getKey().asString();
        int bx = block.getX();
        int bz = block.getZ();
        int tx = Math.floorDiv(bx, 256);
        int tz = Math.floorDiv(bz, 256);

        // Record immediate overlay in LiveBlockBuffer for 0ms rendered visibility
        try {
            if ("BREAK".equals(action) || "EXPLODE".equals(action)) {
                Block below = block.getRelative(0, -1, 0);
                int color = (tileRenderer.getPaletteRegistry() != null)
                        ? tileRenderer.getPaletteRegistry().getArgb(below.getType().name())
                        : 0;
                LiveBlockBuffer.recordBlock(dimension, bx, block.getY() - 1, bz, color);
            } else {
                int color = (tileRenderer.getPaletteRegistry() != null)
                        ? tileRenderer.getPaletteRegistry().getArgb(block.getType().name())
                        : 0xFF7F7F7F;
                LiveBlockBuffer.recordBlock(dimension, bx, block.getY(), bz, color);
            }
        } catch (Throwable ignored) {}

        // Invalidate in-memory & disk cache
        tileRenderer.invalidateTile(dimension, bx, bz);

        // Throttle WS tile update per tile to at most once per 200ms
        String tileKey = dimension + ":" + tx + ":" + tz;
        long now = System.currentTimeMillis();
        Long last = lastUpdatePerTile.get(tileKey);
        if (last == null || (now - last) > 200) {
            lastUpdatePerTile.put(tileKey, now);

            if (webSocketServer != null) {
                JsonObject tilePacket = new JsonObject();
                tilePacket.addProperty("type", "TILE_UPDATE");
                tilePacket.addProperty("dimension", dimension);
                tilePacket.addProperty("tileX", tx);
                tilePacket.addProperty("tileZ", tz);
                webSocketServer.broadcastJson(tilePacket.toString());
            }
        }

        // Broadcast block change for live map visual feedback
        if (webSocketServer != null && player != null) {
            JsonObject changePacket = new JsonObject();
            changePacket.addProperty("type", "BLOCK_CHANGE");
            changePacket.addProperty("dimension", dimension);
            changePacket.addProperty("x", bx);
            changePacket.addProperty("y", block.getY());
            changePacket.addProperty("z", bz);
            changePacket.addProperty("material", block.getType().name());
            changePacket.addProperty("action", action);
            changePacket.addProperty("player", player.getName());
            webSocketServer.broadcastJson(changePacket.toString());
        }

        // Periodically clean the throttle map to prevent unbounded growth
        tryCleanThrottleMap(now);
    }

    /**
     * Removes stale entries from the throttle map to prevent unbounded memory growth
     * on servers with many unique tile interactions.
     */
    private void tryCleanThrottleMap(long now) {
        long lastRun = lastCleanup.get();
        if ((now - lastRun) < CLEANUP_INTERVAL_MS) {
            return;
        }
        if (!lastCleanup.compareAndSet(lastRun, now)) {
            return;
        }
        long cutoff = now - THROTTLE_ENTRY_TTL_MS;
        lastUpdatePerTile.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
