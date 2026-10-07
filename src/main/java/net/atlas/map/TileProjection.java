// src/main/java/net/atlas/map/TileProjection.java
package net.atlas.map;

public final class TileProjection {

    public static final int TILE_SIZE = 256;
    public static final int BLOCKS_PER_REGION = 512;
    public static final int TILES_PER_REGION = 2;

    private TileProjection() {}

    public static int blockToTile(int blockCoord) {
        return Math.floorDiv(blockCoord, TILE_SIZE);
    }

    public static int blockToPixelInTile(int blockCoord) {
        int rem = blockCoord % TILE_SIZE;
        return rem < 0 ? rem + TILE_SIZE : rem;
    }

    public static int tileToMinBlock(int tileCoord) {
        return tileCoord * TILE_SIZE;
    }

    public static int blockToChunk(int blockCoord) {
        return Math.floorDiv(blockCoord, 16);
    }

    public static int chunkToRegion(int chunkCoord) {
        return Math.floorDiv(chunkCoord, 32);
    }

    public static int blockToRegion(int blockCoord) {
        return Math.floorDiv(blockCoord, BLOCKS_PER_REGION);
    }

    public static int tileToRegion(int tileCoord) {
        return Math.floorDiv(tileCoord, TILES_PER_REGION);
    }
}
