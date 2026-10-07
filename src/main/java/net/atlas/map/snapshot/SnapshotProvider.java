// src/main/java/net/atlas/map/snapshot/SnapshotProvider.java
package net.atlas.map.snapshot;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

public final class SnapshotProvider {

    private final Plugin plugin;

    public SnapshotProvider(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<ChunkSnapshot[][]> getTileSnapshots(World world, int startChunkX, int startChunkZ) {
        CompletableFuture<ChunkSnapshot[][]> future = new CompletableFuture<>();

        if (world == null) {
            future.complete(new ChunkSnapshot[16][16]);
            return future;
        }

        Runnable task = () -> {
            ChunkSnapshot[][] grid = new ChunkSnapshot[16][16];
            for (int cx = 0; cx < 16; cx++) {
                for (int cz = 0; cz < 16; cz++) {
                    int wx = startChunkX + cx;
                    int wz = startChunkZ + cz;
                    if (world.isChunkLoaded(wx, wz)) {
                        try {
                            Chunk chunk = world.getChunkAt(wx, wz);
                            grid[cx][cz] = chunk.getChunkSnapshot(true, true, false);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            future.complete(grid);
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }

        return future;
    }

    public CompletableFuture<ChunkSnapshot> getSnapshotIfLoaded(World world, int chunkX, int chunkZ) {
        CompletableFuture<ChunkSnapshot> future = new CompletableFuture<>();

        if (world == null) {
            future.complete(null);
            return future;
        }

        if (Bukkit.isPrimaryThread()) {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                future.complete(chunk.getChunkSnapshot(true, true, false));
            } else {
                future.complete(null);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    future.complete(chunk.getChunkSnapshot(true, true, false));
                } else {
                    future.complete(null);
                }
            });
        }

        return future;
    }

    public boolean isChunkLoaded(World world, int chunkX, int chunkZ) {
        if (world == null) return false;
        try {
            return world.isChunkLoaded(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }
}
