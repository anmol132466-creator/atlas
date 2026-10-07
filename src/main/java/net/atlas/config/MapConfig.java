// src/main/java/net/atlas/config/MapConfig.java
package net.atlas.config;

public record MapConfig(
        String defaultDimension,
        int maxMemoryTiles,
        boolean isDiskCacheEnabled,
        int maxConcurrentChunkReads,
        boolean isBoundingBoxEnabled,
        int boundingBoxMinX,
        int boundingBoxMaxX,
        int boundingBoxMinZ,
        int boundingBoxMaxZ,
        boolean isRenderUnloadedChunks,
        double hillshadingIntensity,
        WorldSpawnConfig worldSpawn
) {
    public record WorldSpawnConfig(
            boolean enabled,
            String dimension,
            int x,
            int y,
            int z
    ) {}
    public boolean isWithinBoundingBox(int blockX, int blockZ) {
        if (!isBoundingBoxEnabled) {
            return true;
        }
        return blockX >= boundingBoxMinX && blockX <= boundingBoxMaxX
                && blockZ >= boundingBoxMinZ && blockZ <= boundingBoxMaxZ;
    }

    public boolean isTileWithinBoundingBox(int tileX, int tileZ) {
        if (!isBoundingBoxEnabled) {
            return true;
        }
        int minTileBlockX = tileX * 256;
        int maxTileBlockX = minTileBlockX + 255;
        int minTileBlockZ = tileZ * 256;
        int maxTileBlockZ = minTileBlockZ + 255;

        return !(maxTileBlockX < boundingBoxMinX || minTileBlockX > boundingBoxMaxX
                || maxTileBlockZ < boundingBoxMinZ || minTileBlockZ > boundingBoxMaxZ);
    }
}
