// src/main/java/net/atlas/map/render/LiveBlockBuffer.java
package net.atlas.map.render;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance atomic in-memory buffer that stores live placed and broken blocks
 * directly overlaid on top of map tiles with 0ms latency.
 * 
 * Entries are automatically evicted after {@link #EVICTION_AGE_MS} (10 minutes)
 * to prevent unbounded memory growth on busy servers.
 */
public final class LiveBlockBuffer {

    public record BlockEntry(int x, int y, int z, int argb, long timestamp) {}

    /** Entries older than 10 minutes are evicted. */
    private static final long EVICTION_AGE_MS = 10L * 60L * 1000L;
    /** Eviction scan runs at most once every 30 seconds. */
    private static final long EVICTION_INTERVAL_MS = 30_000L;
    private static final AtomicLong LAST_EVICTION = new AtomicLong(0L);

    // Key: "dimension:tileX:tileZ" -> Map of blockKey -> BlockEntry
    private static final Map<String, Map<Long, BlockEntry>> TILE_OVERLAYS = new ConcurrentHashMap<>();

    private LiveBlockBuffer() {}

    public static void recordBlock(String dimension, int x, int y, int z, int argb) {
        if (dimension == null) return;
        int tx = Math.floorDiv(x, 256);
        int tz = Math.floorDiv(z, 256);
        String tileKey = dimension.toLowerCase() + ":" + tx + ":" + tz;

        long blockKey = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        TILE_OVERLAYS.computeIfAbsent(tileKey, k -> new ConcurrentHashMap<>())
                .put(blockKey, new BlockEntry(x, y, z, argb, System.currentTimeMillis()));

        tryEvict();
    }

    public static void applyOverlay(String dimension, int tileX, int tileZ, int[][] heights, int[][] colors) {
        if (dimension == null) return;
        String tileKey = dimension.toLowerCase() + ":" + tileX + ":" + tileZ;
        Map<Long, BlockEntry> entries = TILE_OVERLAYS.get(tileKey);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        int baseWorldX = tileX * 256;
        int baseWorldZ = tileZ * 256;

        for (BlockEntry entry : entries.values()) {
            int px = entry.x() - baseWorldX;
            int pz = entry.z() - baseWorldZ;

            if (px >= 0 && px < 256 && pz >= 0 && pz < 256) {
                if (entry.argb() != 0) {
                    heights[px][pz] = Math.max(heights[px][pz], entry.y());
                    colors[px][pz] = entry.argb();
                }
            }
        }
    }

    /**
     * Periodically evicts entries older than the eviction threshold.
     * Uses a time-gated check to avoid scanning on every write.
     */
    private static void tryEvict() {
        long now = System.currentTimeMillis();
        long lastRun = LAST_EVICTION.get();
        if ((now - lastRun) < EVICTION_INTERVAL_MS) {
            return;
        }
        // CAS to prevent concurrent eviction scans
        if (!LAST_EVICTION.compareAndSet(lastRun, now)) {
            return;
        }

        long cutoff = now - EVICTION_AGE_MS;
        Iterator<Map.Entry<String, Map<Long, BlockEntry>>> tileIt = TILE_OVERLAYS.entrySet().iterator();
        while (tileIt.hasNext()) {
            Map<Long, BlockEntry> blocks = tileIt.next().getValue();
            blocks.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
            if (blocks.isEmpty()) {
                tileIt.remove();
            }
        }
    }

    public static void clear() {
        TILE_OVERLAYS.clear();
    }

    /** Returns the total number of buffered block entries across all tiles. */
    public static int size() {
        int total = 0;
        for (Map<Long, BlockEntry> entries : TILE_OVERLAYS.values()) {
            total += entries.size();
        }
        return total;
    }
}
