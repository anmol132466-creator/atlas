// src/main/java/net/atlas/map/render/PaletteRegistry.java
package net.atlas.map.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PaletteRegistry {

    private final Plugin plugin;
    private final Map<String, Integer> colorMap = new ConcurrentHashMap<>();
    private static final int FALLBACK_COLOR = 0xFF7F7F7F; // Neutral Stone Gray

    public PaletteRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void loadDefaultPalette() {
        try (InputStream in = plugin.getResource("palette/colors.json")) {
            if (in == null) {
                plugin.getLogger().warning("Could not find default palette/colors.json in JAR resources.");
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    String blockKey = entry.getKey().toLowerCase();
                    JsonArray rgba = entry.getValue().getAsJsonArray();
                    if (rgba.size() >= 4) {
                        int r = rgba.get(0).getAsInt();
                        int g = rgba.get(1).getAsInt();
                        int b = rgba.get(2).getAsInt();
                        int a = rgba.get(3).getAsInt();
                        int argb = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                        colorMap.put(blockKey, argb);
                    }
                }
            }
            plugin.getLogger().info("Loaded " + colorMap.size() + " block color mappings into PaletteRegistry.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load colors.json palette", e);
        }
    }

    public int getArgb(String blockId) {
        if (blockId == null) {
            return 0; // transparent air
        }
        String key = blockId.toLowerCase();
        if (!key.startsWith("minecraft:")) {
            key = "minecraft:" + key;
        }

        Integer exact = colorMap.get(key);
        if (exact != null) {
            return exact;
        }

        // Intelligent Substring / Pattern Fallback for Placed Blocks & Variants
        int fuzzy = resolveFuzzyColor(key);
        colorMap.put(key, fuzzy); // Memoize resolved color
        return fuzzy;
    }

    private int resolveFuzzyColor(String key) {
        // Concrete, Wool, Terracotta, Glass colors
        if (key.contains("white_")) return colorMap.getOrDefault("minecraft:white_concrete", 0xFFD0D5D6);
        if (key.contains("orange_")) return colorMap.getOrDefault("minecraft:orange_concrete", 0xFFE06100);
        if (key.contains("magenta_")) return colorMap.getOrDefault("minecraft:magenta_concrete", 0xFFA9309F);
        if (key.contains("light_blue_")) return colorMap.getOrDefault("minecraft:light_blue_concrete", 0xFF2489C7);
        if (key.contains("yellow_")) return colorMap.getOrDefault("minecraft:yellow_concrete", 0xFFF1AF15);
        if (key.contains("lime_")) return colorMap.getOrDefault("minecraft:lime_concrete", 0xFF5EA918);
        if (key.contains("pink_")) return colorMap.getOrDefault("minecraft:pink_concrete", 0xFFD6658F);
        if (key.contains("gray_")) return colorMap.getOrDefault("minecraft:gray_concrete", 0xFF36393D);
        if (key.contains("light_gray_")) return colorMap.getOrDefault("minecraft:light_gray_concrete", 0xFF7D7D73);
        if (key.contains("cyan_")) return colorMap.getOrDefault("minecraft:cyan_concrete", 0xFF157788);
        if (key.contains("purple_")) return colorMap.getOrDefault("minecraft:purple_concrete", 0xFF641F9C);
        if (key.contains("blue_")) return colorMap.getOrDefault("minecraft:blue_concrete", 0xFF2C2E8F);
        if (key.contains("brown_")) return colorMap.getOrDefault("minecraft:brown_concrete", 0xFF603B1F);
        if (key.contains("green_")) return colorMap.getOrDefault("minecraft:green_concrete", 0xFF495B24);
        if (key.contains("red_")) return colorMap.getOrDefault("minecraft:red_concrete", 0xFF8E2020);
        if (key.contains("black_")) return colorMap.getOrDefault("minecraft:black_concrete", 0xFF080A0F);

        // Wood variants
        if (key.contains("spruce")) return colorMap.getOrDefault("minecraft:spruce_planks", 0xFF725430);
        if (key.contains("birch")) return colorMap.getOrDefault("minecraft:birch_planks", 0xFFC4B37B);
        if (key.contains("jungle")) return colorMap.getOrDefault("minecraft:jungle_planks", 0xFFA07350);
        if (key.contains("acacia")) return colorMap.getOrDefault("minecraft:acacia_planks", 0xFFA85A32);
        if (key.contains("dark_oak")) return colorMap.getOrDefault("minecraft:dark_oak_planks", 0xFF422B14);
        if (key.contains("mangrove")) return colorMap.getOrDefault("minecraft:mangrove_planks", 0xFF753630);
        if (key.contains("cherry")) return colorMap.getOrDefault("minecraft:cherry_planks", 0xFFE2B2AE);
        if (key.contains("bamboo")) return colorMap.getOrDefault("minecraft:bamboo_planks", 0xFFC0AE52);
        if (key.contains("crimson")) return colorMap.getOrDefault("minecraft:crimson_planks", 0xFF653046);
        if (key.contains("warped")) return colorMap.getOrDefault("minecraft:warped_planks", 0xFF2B6863);
        if (key.contains("oak") || key.contains("wood") || key.contains("plank") || key.contains("log")) {
            return colorMap.getOrDefault("minecraft:oak_planks", 0xFFA2824E);
        }

        // Stone & Brick variants
        if (key.contains("stone_brick")) return colorMap.getOrDefault("minecraft:stone_bricks", 0xFF7A7A7A);
        if (key.contains("brick")) return colorMap.getOrDefault("minecraft:bricks", 0xFF966153);
        if (key.contains("cobblestone")) return colorMap.getOrDefault("minecraft:cobblestone", 0xFF828282);
        if (key.contains("deepslate")) return colorMap.getOrDefault("minecraft:deepslate_bricks", 0xFF414146);
        if (key.contains("blackstone")) return colorMap.getOrDefault("minecraft:blackstone", 0xFF2A262E);
        if (key.contains("basalt")) return colorMap.getOrDefault("minecraft:basalt", 0xFF515156);
        if (key.contains("tuff")) return colorMap.getOrDefault("minecraft:tuff", 0xFF6C6D66);
        if (key.contains("calcite")) return colorMap.getOrDefault("minecraft:calcite", 0xFFDFE0DC);
        if (key.contains("quartz")) return colorMap.getOrDefault("minecraft:quartz_block", 0xFFEBE5DE);
        if (key.contains("sandstone")) return colorMap.getOrDefault("minecraft:sandstone", 0xFFDAD29E);
        if (key.contains("granite")) return colorMap.getOrDefault("minecraft:granite", 0xFF956756);
        if (key.contains("diorite")) return colorMap.getOrDefault("minecraft:diorite", 0xFFB5B5B7);
        if (key.contains("andesite")) return colorMap.getOrDefault("minecraft:andesite", 0xFF88888A);
        if (key.contains("prismarine")) return colorMap.getOrDefault("minecraft:prismarine", 0xFF639C96);
        if (key.contains("purpur")) return colorMap.getOrDefault("minecraft:purpur_block", 0xFFA97DA9);
        if (key.contains("end_stone")) return colorMap.getOrDefault("minecraft:end_stone", 0xFFDDDFB5);
        if (key.contains("netherrack")) return colorMap.getOrDefault("minecraft:netherrack", 0xFF6F2828);

        // Metals & Minerals
        if (key.contains("iron")) return colorMap.getOrDefault("minecraft:iron_block", 0xFFDCDCDC);
        if (key.contains("gold")) return colorMap.getOrDefault("minecraft:gold_block", 0xFFF5CD2F);
        if (key.contains("diamond")) return colorMap.getOrDefault("minecraft:diamond_block", 0xFF62EDE4);
        if (key.contains("netherite")) return colorMap.getOrDefault("minecraft:netherite_block", 0xFF423D3F);
        if (key.contains("copper")) return colorMap.getOrDefault("minecraft:copper_block", 0xFFC06B4F);
        if (key.contains("emerald")) return colorMap.getOrDefault("minecraft:emerald_block", 0xFF2ACB57);
        if (key.contains("lapis")) return colorMap.getOrDefault("minecraft:lapis_block", 0xFF1E438C);
        if (key.contains("redstone")) return colorMap.getOrDefault("minecraft:redstone_block", 0xFFB21C07);
        if (key.contains("amethyst")) return colorMap.getOrDefault("minecraft:amethyst_block", 0xFF8560C4);

        // Transparent & Special
        if (key.contains("glass")) return colorMap.getOrDefault("minecraft:glass", 0x3CC8DCE6);
        if (key.contains("water")) return colorMap.getOrDefault("minecraft:water", 0xDC4064C8);
        if (key.contains("lava") || key.contains("fire")) return colorMap.getOrDefault("minecraft:lava", 0xFFD76717);
        if (key.contains("leaves") || key.contains("grass") || key.contains("foliage") || key.contains("moss")) {
            return colorMap.getOrDefault("minecraft:grass_block", 0xFF5FA838);
        }

        return FALLBACK_COLOR;
    }

    public int getArgb(Material material) {
        if (material == null || material.isAir()) {
            return 0;
        }
        return getArgb(material.name().toLowerCase());
    }
}
