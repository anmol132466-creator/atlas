// src/test/java/net/atlas/map/TileProjectionTest.java
package net.atlas.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TileProjectionTest {

    @Test
    public void testBlockToTilePositive() {
        assertEquals(0, TileProjection.blockToTile(0));
        assertEquals(0, TileProjection.blockToTile(255));
        assertEquals(1, TileProjection.blockToTile(256));
        assertEquals(4, TileProjection.blockToTile(1024));
    }

    @Test
    public void testBlockToTileNegative() {
        assertEquals(-1, TileProjection.blockToTile(-1));
        assertEquals(-1, TileProjection.blockToTile(-256));
        assertEquals(-2, TileProjection.blockToTile(-257));
        assertEquals(-5, TileProjection.blockToTile(-1025));
    }

    @Test
    public void testBlockToPixelInTile() {
        assertEquals(0, TileProjection.blockToPixelInTile(0));
        assertEquals(255, TileProjection.blockToPixelInTile(255));
        assertEquals(0, TileProjection.blockToPixelInTile(256));
        assertEquals(255, TileProjection.blockToPixelInTile(-1));
        assertEquals(254, TileProjection.blockToPixelInTile(-2));
        assertEquals(0, TileProjection.blockToPixelInTile(-256));
    }

    @Test
    public void testRegionMapping() {
        assertEquals(0, TileProjection.tileToRegion(0));
        assertEquals(0, TileProjection.tileToRegion(1));
        assertEquals(1, TileProjection.tileToRegion(2));
        assertEquals(-1, TileProjection.tileToRegion(-1));
        assertEquals(-1, TileProjection.tileToRegion(-2));
        assertEquals(-2, TileProjection.tileToRegion(-3));
    }
}
