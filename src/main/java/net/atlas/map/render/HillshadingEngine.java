// src/main/java/net/atlas/map/render/HillshadingEngine.java
package net.atlas.map.render;

public final class HillshadingEngine {

    private HillshadingEngine() {}

    public static int applyShading(int argb, int currentY, int northY, double intensity) {
        return applyShading2D(argb, currentY, northY, northY, intensity);
    }

    public static int applyShading2D(int argb, int currentY, int northY, int westY, double intensity) {
        int a = (argb >> 24) & 0xFF;
        if (a == 0) {
            return 0; // Transparent
        }

        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        // North-West Virtual Sunlight (315 deg azimuth, 45 deg zenith)
        double slopeX = (currentY - westY);
        double slopeZ = (currentY - northY);
        double combinedSlope = (slopeX + slopeZ) * 0.7071;

        // Dynamic contrast enhancement
        double factor = 1.0 + Math.max(-0.35, Math.min(0.35, combinedSlope * intensity));

        // Subtle edge-highlighting for building walls and sudden drops (e.g. structures)
        if (combinedSlope < -2) {
            factor *= 0.88; // Deep building/cliff shadow
        } else if (combinedSlope > 2) {
            factor *= 1.12; // Crisp highlighted ridge
        }

        int newR = Math.min(255, Math.max(0, (int) Math.round(r * factor)));
        int newG = Math.min(255, Math.max(0, (int) Math.round(g * factor)));
        int newB = Math.min(255, Math.max(0, (int) Math.round(b * factor)));

        return (a << 24) | (newR << 16) | (newG << 8) | newB;
    }
}
