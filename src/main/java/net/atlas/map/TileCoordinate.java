// src/main/java/net/atlas/map/TileCoordinate.java
package net.atlas.map;

import java.util.Objects;

public record TileCoordinate(
        String dimension,
        int zoom,
        int tileX,
        int tileZ
) {
    public TileCoordinate {
        Objects.requireNonNull(dimension, "dimension cannot be null");
    }

    public String toKey() {
        return dimension + "/" + zoom + "/" + tileX + "_" + tileZ;
    }

    public String toRelativePath() {
        String safeDim = dimension.replace(":", "_").replace("/", "_");
        return safeDim + "/" + zoom + "/" + tileX + "_" + tileZ + ".png";
    }

    public int getMinBlockX() {
        return tileX * 256;
    }

    public int getMinBlockZ() {
        return tileZ * 256;
    }
}
