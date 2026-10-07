// src/main/java/net/atlas/map/render/BiomeColorEngine.java
package net.atlas.map.render;

public final class BiomeColorEngine {

    private BiomeColorEngine() {}

    public static int tintBlock(int baseArgb, String materialName, String biomeName) {
        if (materialName == null) {
            return baseArgb;
        }

        String mat = materialName.toLowerCase();
        if (mat.contains("grass") || mat.contains("leaves") || mat.contains("vine")) {
            int tint = getFoliageTint(biomeName);
            return multiplyRgb(baseArgb, tint);
        } else if (mat.contains("water")) {
            int tint = getWaterTint(biomeName);
            return multiplyRgb(baseArgb, tint);
        }

        return baseArgb;
    }

    private static int getFoliageTint(String biomeName) {
        if (biomeName == null) {
            return 0x79C05A; // Default Plains Green
        }
        String b = biomeName.toLowerCase();
        if (b.contains("desert") || b.contains("badlands") || b.contains("savanna")) {
            return 0xBFB755;
        }
        if (b.contains("swamp")) {
            return 0x4C782F;
        }
        if (b.contains("taiga") || b.contains("snowy")) {
            return 0x86B783;
        }
        if (b.contains("dark_forest")) {
            return 0x507A32;
        }
        if (b.contains("jungle")) {
            return 0x59C93C;
        }
        return 0x79C05A;
    }

    private static int getWaterTint(String biomeName) {
        if (biomeName == null) {
            return 0x3F76E4; // Default Water Blue
        }
        String b = biomeName.toLowerCase();
        if (b.contains("swamp")) {
            return 0x617B5D;
        }
        if (b.contains("frozen") || b.contains("ice")) {
            return 0x3938C9;
        }
        if (b.contains("warm_ocean")) {
            return 0x43D5EE;
        }
        return 0x3F76E4;
    }

    private static int multiplyRgb(int argb, int tintRgb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        int tr = (tintRgb >> 16) & 0xFF;
        int tg = (tintRgb >> 8) & 0xFF;
        int tb = tintRgb & 0xFF;

        int nr = (r * tr) / 255;
        int ng = (g * tg) / 255;
        int nb = (b * tb) / 255;

        return (a << 24) | (nr << 16) | (ng << 8) | nb;
    }
}
