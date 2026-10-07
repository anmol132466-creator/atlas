// src/main/java/net/atlas/map/render/SeedBiomeGenerator.java
package net.atlas.map.render;

import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;

public final class SeedBiomeGenerator {

    // Authentic fallback block colors (Minecraft Java Edition defaults tinted with standard biomes)
    private static final int DEFAULT_WATER = 0xFF0F36CB;
    private static final int DEFAULT_DEEP_WATER = 0xFF0B2694;
    private static final int DEFAULT_SHALLOW_WATER = 0xFF1A58D8;
    private static final int DEFAULT_GRASS_PLAINS = 0xFF2D7712;
    private static final int DEFAULT_GRASS_MEADOW = 0xFF358C18;
    private static final int DEFAULT_GRASS_FOREST = 0xFF2A6814;
    private static final int DEFAULT_GRASS_DARK_FOREST = 0xFF1E520E;
    private static final int DEFAULT_GRASS_BIRCH = 0xFF3D8520;
    private static final int DEFAULT_GRASS_SWAMP = 0xFF28481A;
    private static final int DEFAULT_GRASS_JUNGLE = 0xFF2A8E1C;
    private static final int DEFAULT_GRASS_SAVANNA = 0xFF656A28;
    private static final int DEFAULT_GRASS_TAIGA = 0xFF386438;
    private static final int DEFAULT_SAND = 0xFFDCD299;
    private static final int DEFAULT_SANDSTONE = 0xFFCBB880;
    private static final int DEFAULT_STONE = 0xFF7D7D7D;
    private static final int DEFAULT_ANDESITE = 0xFF88888A;
    private static final int DEFAULT_SNOW = 0xFFF5F8FA;
    private static final int DEFAULT_BADLANDS_TERRACOTTA = 0xFF9E4B24;
    private static final int DEFAULT_BADLANDS_RED = 0xFF8F3D1F;
    private static final int DEFAULT_BADLANDS_ORANGE = 0xFFA15325;
    private static final int DEFAULT_BADLANDS_YELLOW = 0xFFB87C2E;

    // Foliage colors
    private static final int DEFAULT_LEAVES_OAK = 0xFF246410;
    private static final int DEFAULT_LEAVES_BIRCH = 0xFF3A7B1C;
    private static final int DEFAULT_LEAVES_DARK_OAK = 0xFF194A0B;
    private static final int DEFAULT_LEAVES_SPRUCE = 0xFF2E562E;
    private static final int DEFAULT_LEAVES_JUNGLE = 0xFF288214;

    // Nether fallback colors
    private static final int COLOR_NETHER_LAVA = 0xFFDF4A0B;
    private static final int COLOR_NETHER_WASTES = 0xFF6F2828;
    private static final int COLOR_CRIMSON_FOREST = 0xFF8D1D1D;
    private static final int COLOR_WARPED_FOREST = 0xFF16635B;
    private static final int COLOR_SOUL_SAND_VALLEY = 0xFF44362E;
    private static final int COLOR_BASALT_DELTAS = 0xFF36363B;

    // End fallback colors (opaque cosmic void abyss with zero alpha holes)
    private static final int COLOR_END_STONE = 0xFFDDDFB5;
    private static final int COLOR_END_OBSIDIAN = 0xFF14111E;
    private static final int COLOR_END_PURPUR = 0xFFA97DA9;
    public static final int COLOR_END_VOID = 0xFF0A0714;

    /** Universal Minecraft Sea Level */
    public static final int SEA_LEVEL = 62;

    private final PaletteRegistry paletteRegistry;

    public SeedBiomeGenerator() {
        this(null);
    }

    public SeedBiomeGenerator(PaletteRegistry paletteRegistry) {
        this.paletteRegistry = paletteRegistry;
    }

    public int sampleFallbackColor(String dimension) {
        String d = (dimension != null) ? dimension.toLowerCase() : "";
        if (d.contains("nether")) return COLOR_NETHER_WASTES | 0xFF000000;
        if (d.contains("end")) return COLOR_END_VOID | 0xFF000000;
        return DEFAULT_GRASS_PLAINS | 0xFF000000;
    }

    /**
     * Generates base terrain elevation and authentic biome color palette for a 256x256 tile
     * based on the 64-bit Java Edition world seed.
     * The coordinate grid aligns 1:1 with Minecraft block coordinates (1 pixel = 1 block).
     */
    public void generateBaseTile(long seed, String dimension, int tileX, int tileZ, int[][] heights, int[][] colors) {
        generateBaseTile(null, seed, dimension, tileX, tileZ, heights, colors);
    }

    public void generateBaseTile(World world, long seed, String dimension, int tileX, int tileZ, int[][] heights, int[][] colors) {
        if (world != null) {
            try {
                BiomeProvider provider = null;
                try {
                    provider = world.getBiomeProvider();
                } catch (Throwable ignored) {}
                generateFromWorldBiomeProvider(world, provider, seed, dimension, tileX, tileZ, heights, colors);
                return;
            } catch (Throwable ignored) {}
        }

        String dimClean = (dimension != null) ? dimension.toLowerCase() : "overworld";
        if (dimClean.contains("nether")) {
            generateNetherTile(seed, tileX, tileZ, heights, colors);
        } else if (dimClean.contains("end")) {
            generateEndTile(seed, tileX, tileZ, heights, colors);
        } else {
            generateOverworldTile(seed, tileX, tileZ, heights, colors);
        }
    }

    private void generateFromWorldBiomeProvider(World world, BiomeProvider provider, long seed, String dimension, int tileX, int tileZ, int[][] heights, int[][] colors) {
        int baseWorldX = tileX * 256;
        int baseWorldZ = tileZ * 256;

        // Sample quart-biomes on 4x4 block intervals for high performance (64x64 grid)
        Biome[][] biomes = new Biome[64][64];
        for (int qx = 0; qx < 64; qx++) {
            int wx = baseWorldX + qx * 4 + 2;
            for (int qz = 0; qz < 64; qz++) {
                int wz = baseWorldZ + qz * 4 + 2;
                Biome b = null;
                if (provider != null) {
                    try {
                        b = provider.getBiome(world, wx, 64, wz);
                    } catch (Throwable ignored) {}
                }
                if (b == null && world != null) {
                    try {
                        b = world.getComputedBiome(wx, 64, wz);
                    } catch (Throwable ignored) {}
                    if (b == null) {
                        try {
                            b = world.getBiome(wx, 64, wz);
                        } catch (Throwable ignored) {}
                    }
                }
                biomes[qx][qz] = (b != null) ? b : Biome.PLAINS;
            }
        }

        // Cache base palette colors
        int baseWater = getBlockColor("minecraft:water", DEFAULT_WATER);
        int baseDeepWater = getBlockColor("minecraft:water", DEFAULT_DEEP_WATER);
        int baseShallowWater = getBlockColor("minecraft:water", DEFAULT_SHALLOW_WATER);
        int baseIce = getBlockColor("minecraft:ice", 0xFF90B0FF);
        int baseGrass = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_PLAINS);
        int baseSand = getBlockColor("minecraft:sand", DEFAULT_SAND);
        int baseStone = getBlockColor("minecraft:stone", DEFAULT_STONE);
        int baseSnow = getBlockColor("minecraft:snow_block", DEFAULT_SNOW);
        int baseTerracotta = getBlockColor("minecraft:terracotta", DEFAULT_BADLANDS_TERRACOTTA);
        int baseMud = getBlockColor("minecraft:mud", 0xFF3D332B);
        int baseMycelium = getBlockColor("minecraft:mycelium", 0xFF6F6575);
        int baseNetherrack = getBlockColor("minecraft:netherrack", COLOR_NETHER_WASTES);
        int baseEndStone = getBlockColor("minecraft:end_stone", COLOR_END_STONE);
        int baseOakLeaves = getBlockColor("minecraft:oak_leaves", DEFAULT_LEAVES_OAK);
        int baseSpruceLeaves = getBlockColor("minecraft:spruce_leaves", DEFAULT_LEAVES_SPRUCE);
        int baseBirchLeaves = getBlockColor("minecraft:birch_leaves", DEFAULT_LEAVES_BIRCH);
        int baseDarkOakLeaves = getBlockColor("minecraft:dark_oak_leaves", DEFAULT_LEAVES_DARK_OAK);
        int baseJungleLeaves = getBlockColor("minecraft:jungle_leaves", DEFAULT_LEAVES_JUNGLE);

        for (int x = 0; x < 256; x++) {
            int qx = x >> 2;
            int worldX = baseWorldX + x;

            for (int z = 0; z < 256; z++) {
                int qz = z >> 2;
                int worldZ = baseWorldZ + z;

                Biome biome = biomes[qx][qz];
                String bName = (biome != null) ? biome.name().toLowerCase() : "plains";

                int height;
                int color;

                if (bName.contains("ocean")) {
                    height = SEA_LEVEL;
                    int wColor = bName.contains("deep") ? baseDeepWater : (bName.contains("warm") ? baseShallowWater : baseWater);
                    color = BiomeColorEngine.tintBlock(wColor, "minecraft:water", bName);
                    if (bName.contains("frozen") && (hash(worldX, worldZ, seed) & 7) < 3) {
                        color = baseIce;
                    }
                } else if (bName.contains("river")) {
                    height = SEA_LEVEL;
                    color = BiomeColorEngine.tintBlock(baseWater, "minecraft:water", bName);
                    if (bName.contains("frozen")) color = baseIce;
                } else if (bName.contains("beach") || bName.contains("shore")) {
                    height = bName.contains("stony") ? 66 : 64;
                    color = bName.contains("stony") ? baseStone : (bName.contains("snowy") ? baseSnow : baseSand);
                } else if (bName.contains("desert")) {
                    height = 66 + ((hash(worldX, worldZ, seed) & 3));
                    color = baseSand;
                } else if (bName.contains("badlands")) {
                    height = 74 + ((hash(worldX, worldZ, seed) & 7));
                    int band = Math.abs(height + (worldX >> 3)) % 4;
                    color = switch (band) {
                        case 0 -> DEFAULT_BADLANDS_TERRACOTTA;
                        case 1 -> DEFAULT_BADLANDS_RED;
                        case 2 -> DEFAULT_BADLANDS_ORANGE;
                        default -> DEFAULT_BADLANDS_YELLOW;
                    };
                } else if (bName.contains("peaks") || bName.contains("slopes")) {
                    height = 120 + ((hash(worldX, worldZ, seed) & 15));
                    color = bName.contains("stony") ? baseStone : baseSnow;
                } else if (bName.contains("meadow") || bName.contains("cherry")) {
                    height = 82 + ((hash(worldX, worldZ, seed) & 7));
                    color = BiomeColorEngine.tintBlock(baseGrass, "minecraft:grass_block", bName);
                } else if (bName.contains("hills")) {
                    height = 86 + ((hash(worldX, worldZ, seed) & 7));
                    color = baseStone;
                } else if (bName.contains("swamp")) {
                    height = 63;
                    color = bName.contains("mangrove") ? baseMud : BiomeColorEngine.tintBlock(baseGrass, "minecraft:grass_block", bName);
                } else if (bName.contains("mushroom")) {
                    height = 66;
                    color = baseMycelium;
                } else if (bName.contains("nether") || bName.contains("crimson") || bName.contains("warped") || bName.contains("soul") || bName.contains("basalt")) {
                    height = 40;
                    color = bName.contains("crimson") ? COLOR_CRIMSON_FOREST : (bName.contains("warped") ? COLOR_WARPED_FOREST : (bName.contains("soul") ? COLOR_SOUL_SAND_VALLEY : (bName.contains("basalt") ? COLOR_BASALT_DELTAS : baseNetherrack)));
                } else if (bName.contains("end")) {
                    double distFromOrigin = Math.sqrt((double) worldX * worldX + (double) worldZ * worldZ);
                    if (distFromOrigin < 420 || distFromOrigin > 1000) {
                        height = 60;
                        color = baseEndStone;
                    } else {
                        height = 0;
                        color = COLOR_END_VOID;
                    }
                } else {
                    double tree = fbmNoise(worldX * 0.08, worldZ * 0.08, seed ^ 0x7EFEEL, 2);
                    int baseH = bName.contains("jungle") ? 70 : (bName.contains("taiga") ? 68 : 66);
                    if (tree > 0.22 && (bName.contains("forest") || bName.contains("jungle") || bName.contains("taiga") || bName.contains("grove"))) {
                        height = baseH + 4;
                        int leavesMat = (bName.contains("taiga") || bName.contains("grove")) ? baseSpruceLeaves : (bName.contains("birch") ? baseBirchLeaves : (bName.contains("dark") ? baseDarkOakLeaves : (bName.contains("jungle") ? baseJungleLeaves : baseOakLeaves)));
                        color = BiomeColorEngine.tintBlock(leavesMat, "minecraft:leaves", bName);
                    } else {
                        height = baseH + ((hash(worldX, worldZ, seed) & 3));
                        if (bName.contains("snowy")) {
                            color = baseSnow;
                        } else {
                            color = BiomeColorEngine.tintBlock(baseGrass, "minecraft:grass_block", bName);
                        }
                    }
                }

                int microJitter = ((hash(worldX, worldZ, seed) & 7) - 3) * 2;
                color = adjustRgb(color, microJitter);

                heights[x][z] = Math.max(0, Math.min(255, height));
                colors[x][z] = color | 0xFF000000;
            }
        }
    }

    private void generateOverworldTile(long seed, int tileX, int tileZ, int[][] heights, int[][] colors) {
        int baseWorldX = tileX * 256;
        int baseWorldZ = tileZ * 256;

        long contSeed = seed ^ 0x5555555555555555L;
        long tempSeed = seed ^ 0xAAAAAAAAAAAAAAAAL;
        long humSeed = seed ^ 0x3333333333333333L;
        long eroSeed = seed ^ 0x0F0F0F0F0F0F0F0FL;
        long riverSeed = seed ^ 0x7777777777777777L;

        // Sample on 4x4 blocks and bilinearly interpolate for ultra-fast, smooth performance
        double[][] contGrid = new double[65][65];
        double[][] tempGrid = new double[65][65];
        double[][] humGrid = new double[65][65];
        double[][] eroGrid = new double[65][65];
        double[][] riverGrid = new double[65][65];

        for (int gx = 0; gx <= 64; gx++) {
            for (int gz = 0; gz <= 64; gz++) {
                double wx = baseWorldX + gx * 4;
                double wz = baseWorldZ + gz * 4;

                contGrid[gx][gz] = fbmNoise(wx * 0.0009, wz * 0.0009, contSeed, 4);
                tempGrid[gx][gz] = fbmNoise(wx * 0.0012, wz * 0.0012, tempSeed, 3);
                humGrid[gx][gz] = fbmNoise(wx * 0.0012, wz * 0.0012, humSeed, 3);
                eroGrid[gx][gz] = fbmNoise(wx * 0.0018, wz * 0.0018, eroSeed, 3);
                riverGrid[gx][gz] = fbmNoise(wx * 0.0025, wz * 0.0025, riverSeed, 2);
            }
        }

        // Cache base palette colors for water and distinct biome terrains
        int baseWater = getBlockColor("minecraft:water", DEFAULT_WATER);
        int baseDeepWater = getBlockColor("minecraft:water", DEFAULT_DEEP_WATER);
        int baseShallowWater = getBlockColor("minecraft:water", DEFAULT_SHALLOW_WATER);

        int baseGrassPlains = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_PLAINS);
        int baseGrassMeadow = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_MEADOW);
        int baseGrassForest = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_FOREST);
        int baseGrassDarkForest = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_DARK_FOREST);
        int baseGrassBirch = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_BIRCH);
        int baseGrassSwamp = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_SWAMP);
        int baseGrassJungle = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_JUNGLE);
        int baseGrassSavanna = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_SAVANNA);
        int baseGrassTaiga = getBlockColor("minecraft:grass_block", DEFAULT_GRASS_TAIGA);

        int baseSand = getBlockColor("minecraft:sand", DEFAULT_SAND);
        int baseSandstone = getBlockColor("minecraft:sandstone", DEFAULT_SANDSTONE);
        int baseStone = getBlockColor("minecraft:stone", DEFAULT_STONE);
        int baseAndesite = getBlockColor("minecraft:andesite", DEFAULT_ANDESITE);
        int baseSnow = getBlockColor("minecraft:snow_block", DEFAULT_SNOW);
        int baseOakLeaves = getBlockColor("minecraft:oak_leaves", DEFAULT_LEAVES_OAK);
        int baseBirchLeaves = getBlockColor("minecraft:birch_leaves", DEFAULT_LEAVES_BIRCH);
        int baseDarkOakLeaves = getBlockColor("minecraft:dark_oak_leaves", DEFAULT_LEAVES_DARK_OAK);
        int baseSpruceLeaves = getBlockColor("minecraft:spruce_leaves", DEFAULT_LEAVES_SPRUCE);
        int baseJungleLeaves = getBlockColor("minecraft:jungle_leaves", DEFAULT_LEAVES_JUNGLE);

        for (int x = 0; x < 256; x++) {
            int gx = x >> 2;
            double fx = (x & 3) * 0.25;
            int worldX = baseWorldX + x;

            for (int z = 0; z < 256; z++) {
                int gz = z >> 2;
                double fz = (z & 3) * 0.25;
                int worldZ = baseWorldZ + z;

                // Bilinear interpolation
                double cont = bilerp(contGrid, gx, gz, fx, fz);
                double temp = bilerp(tempGrid, gx, gz, fx, fz);
                double hum = bilerp(humGrid, gx, gz, fx, fz);
                double ero = bilerp(eroGrid, gx, gz, fx, fz);
                double riv = Math.abs(bilerp(riverGrid, gx, gz, fx, fz));

                int height;
                int color;

                // High-frequency tree canopy noise for natural 3D forest aerials
                double treeNoise = fbmNoise(worldX * 0.08, worldZ * 0.08, seed ^ 0x7EFEEL, 2);

                boolean isRiver = (cont > 0.02) && (riv < 0.032);
                boolean isRiverBank = (cont > 0.02) && (riv >= 0.032 && riv < 0.040);

                if (cont < -0.28) {
                    // Deep Ocean - sea level is strictly 62, deep dark water
                    height = SEA_LEVEL;
                    int tinted = BiomeColorEngine.tintBlock(baseDeepWater, "minecraft:water", "minecraft:deep_ocean");
                    color = multiplyRgb(tinted, 0x00B0D0FF);
                } else if (cont < -0.05) {
                    // Regular Ocean - sea level 62
                    height = SEA_LEVEL;
                    int tinted = BiomeColorEngine.tintBlock(baseWater, "minecraft:water", "minecraft:ocean");
                    color = (cont < -0.16) ? tinted : multiplyRgb(tinted, 0x00D0E8FF);
                } else if (cont < 0.015) {
                    // Shallow Ocean / Shoreline
                    height = SEA_LEVEL;
                    int tinted = BiomeColorEngine.tintBlock(baseShallowWater, "minecraft:water", "minecraft:warm_ocean");
                    color = multiplyRgb(tinted, 0x00E0F0FF);
                } else if (isRiver) {
                    // River Channel - sea level 62
                    height = SEA_LEVEL;
                    color = BiomeColorEngine.tintBlock(baseWater, "minecraft:water", "minecraft:river");
                } else if (isRiverBank) {
                    // River Bank
                    height = SEA_LEVEL + 1;
                    color = (temp > 0.3) ? baseSand : baseSand;
                } else if (cont < 0.04) {
                    // Beach / Coastal Transition (elevations 63 to 64)
                    height = (int) (63 + (cont - 0.015) * 40);
                    color = (temp < -0.3) ? baseStone : baseSand;
                } else {
                    // Inland Landmass - starts at 64 at coast, gentle rolling terrain
                    double mountainBoost = Math.max(0.0, ero - 0.22);
                    height = (int) (64 + (cont - 0.04) * 14 + mountainBoost * 72);

                    if (height >= 120) {
                        // High Mountain / Peak
                        if (temp < -0.1 || height > 132) {
                            color = baseSnow;
                        } else {
                            color = ((worldX + worldZ) % 3 == 0) ? baseAndesite : baseStone;
                        }
                    } else if (height >= 96) {
                        // Mountain Slope (Rock, Gravel, sparse snow or grass)
                        if (temp < -0.3) {
                            color = baseSnow;
                        } else {
                            color = ((worldX ^ worldZ) % 4 == 0) ? baseAndesite : baseStone;
                        }
                    } else if (temp < -0.35) {
                        // Snowy Plains / Snowy Taiga
                        if (hum > 0.15) {
                            // Snowy Taiga with spruce trees
                            if (treeNoise > 0.12) {
                                height += 5;
                                color = BiomeColorEngine.tintBlock(baseSpruceLeaves, "minecraft:spruce_leaves", "minecraft:snowy_taiga");
                            } else {
                                color = baseSnow;
                            }
                        } else {
                            color = baseSnow;
                        }
                    } else if (temp < 0.0) {
                        // Cold Biome / Taiga
                        if (hum > 0.1) {
                            // Taiga with spruce canopies
                            if (treeNoise > 0.14) {
                                height += 5;
                                color = BiomeColorEngine.tintBlock(baseSpruceLeaves, "minecraft:spruce_leaves", "minecraft:taiga");
                            } else {
                                color = BiomeColorEngine.tintBlock(baseGrassTaiga, "minecraft:grass_block", "minecraft:taiga");
                            }
                        } else {
                            color = BiomeColorEngine.tintBlock(baseGrassTaiga, "minecraft:grass_block", "minecraft:plains");
                        }
                    } else if (temp > 0.45) {
                        // Warm / Hot Biomes (Desert, Badlands, Savanna, Jungle)
                        if (hum > 0.3) {
                            // Jungle with tall canopy trees
                            if (treeNoise > 0.0) {
                                height += 7;
                                color = BiomeColorEngine.tintBlock(baseJungleLeaves, "minecraft:jungle_leaves", "minecraft:jungle");
                            } else {
                                color = BiomeColorEngine.tintBlock(baseGrassJungle, "minecraft:grass_block", "minecraft:jungle");
                            }
                        } else if (hum < -0.15) {
                            // Desert
                            color = (ero > 0.2) ? baseSandstone : baseSand;
                        } else if (hum < 0.15) {
                            // Badlands Terracotta Strata
                            int band = Math.abs(height + (worldX >> 3)) % 5;
                            color = switch (band) {
                                case 0 -> DEFAULT_BADLANDS_TERRACOTTA;
                                case 1 -> DEFAULT_BADLANDS_RED;
                                case 2 -> DEFAULT_BADLANDS_ORANGE;
                                case 3 -> DEFAULT_BADLANDS_YELLOW;
                                default -> baseSandstone;
                            };
                        } else {
                            // Savanna
                            color = BiomeColorEngine.tintBlock(baseGrassSavanna, "minecraft:grass_block", "minecraft:savanna");
                        }
                    } else {
                        // Temperate Biomes (Swamp, Forest, Birch, Dark Forest, Meadow, Plains)
                        if (hum > 0.38) {
                            // Swamp - lower ground near water level with occasional swamp trees
                            height = Math.min(height, 64);
                            if (treeNoise > 0.25) {
                                height += 3;
                                color = BiomeColorEngine.tintBlock(baseOakLeaves, "minecraft:oak_leaves", "minecraft:swamp");
                            } else {
                                color = BiomeColorEngine.tintBlock(baseGrassSwamp, "minecraft:grass_block", "minecraft:swamp");
                            }
                        } else if (hum > 0.22) {
                            // Dark Forest / Dense Forest
                            if (ero > 0.3) {
                                // Dark Forest with dense dark oak canopy
                                if (treeNoise > -0.2) {
                                    height += 5;
                                    color = BiomeColorEngine.tintBlock(baseDarkOakLeaves, "minecraft:dark_oak_leaves", "minecraft:dark_forest");
                                } else {
                                    color = BiomeColorEngine.tintBlock(baseGrassDarkForest, "minecraft:grass_block", "minecraft:dark_forest");
                                }
                            } else {
                                // Regular Oak Forest
                                if (treeNoise > 0.15) {
                                    height += 4;
                                    color = BiomeColorEngine.tintBlock(baseOakLeaves, "minecraft:oak_leaves", "minecraft:forest");
                                } else {
                                    color = BiomeColorEngine.tintBlock(baseGrassForest, "minecraft:grass_block", "minecraft:forest");
                                }
                            }
                        } else if (hum > 0.0) {
                            // Birch Forest
                            if (treeNoise > 0.12) {
                                height += 4;
                                color = BiomeColorEngine.tintBlock(baseBirchLeaves, "minecraft:birch_leaves", "minecraft:birch_forest");
                            } else {
                                color = BiomeColorEngine.tintBlock(baseGrassBirch, "minecraft:grass_block", "minecraft:birch_forest");
                            }
                        } else if (hum < -0.25) {
                            // Meadow
                            color = BiomeColorEngine.tintBlock(baseGrassMeadow, "minecraft:grass_block", "minecraft:meadow");
                        } else {
                            // Plains with sparse solitary trees
                            if (treeNoise > 0.42) {
                                height += 4;
                                color = BiomeColorEngine.tintBlock(baseOakLeaves, "minecraft:oak_leaves", "minecraft:plains");
                            } else {
                                color = BiomeColorEngine.tintBlock(baseGrassPlains, "minecraft:grass_block", "minecraft:plains");
                            }
                        }
                    }
                }

                // Add subtle organic micro-jitter to prevent artificial banding
                int microJitter = ((hash(worldX, worldZ, seed) & 7) - 3) * 2;
                color = adjustRgb(color, microJitter);

                heights[x][z] = Math.max(1, Math.min(255, height));
                colors[x][z] = color;
            }
        }
    }

    private void generateNetherTile(long seed, int tileX, int tileZ, int[][] heights, int[][] colors) {
        int baseWorldX = tileX * 256;
        int baseWorldZ = tileZ * 256;

        int baseLava = getBlockColor("minecraft:lava", COLOR_NETHER_LAVA);
        int baseNetherrack = getBlockColor("minecraft:netherrack", COLOR_NETHER_WASTES);
        int baseCrimson = getBlockColor("minecraft:crimson_nylium", COLOR_CRIMSON_FOREST);
        int baseWarped = getBlockColor("minecraft:warped_nylium", COLOR_WARPED_FOREST);
        int baseSoulSand = getBlockColor("minecraft:soul_sand", COLOR_SOUL_SAND_VALLEY);
        int baseBasalt = getBlockColor("minecraft:basalt", COLOR_BASALT_DELTAS);

        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 256; z++) {
                int wx = baseWorldX + x;
                int wz = baseWorldZ + z;

                double noise = fbmNoise(wx * 0.003, wz * 0.003, seed, 3);
                double biomeNoise = fbmNoise(wx * 0.0015, wz * 0.0015, seed ^ 0xFEEDL, 2);

                if (noise < -0.35) {
                    // Nether Lava Sea at Y = 31
                    heights[x][z] = 31;
                    colors[x][z] = baseLava;
                } else {
                    int h = (int) (35 + (noise + 0.35) * 60);
                    heights[x][z] = Math.min(115, h);

                    if (biomeNoise < -0.3) {
                        colors[x][z] = baseSoulSand;
                    } else if (biomeNoise < -0.05) {
                        colors[x][z] = baseWarped;
                    } else if (biomeNoise < 0.2) {
                        colors[x][z] = baseCrimson;
                    } else if (biomeNoise < 0.45) {
                        colors[x][z] = baseBasalt;
                    } else {
                        colors[x][z] = baseNetherrack;
                    }
                }
            }
        }
    }

    private void generateEndTile(long seed, int tileX, int tileZ, int[][] heights, int[][] colors) {
        int baseWorldX = tileX * 256;
        int baseWorldZ = tileZ * 256;

        int baseEndStone = getBlockColor("minecraft:end_stone", COLOR_END_STONE);
        int basePurpur = getBlockColor("minecraft:purpur_block", COLOR_END_PURPUR);
        int baseObsidian = getBlockColor("minecraft:obsidian", COLOR_END_OBSIDIAN);

        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 256; z++) {
                int wx = baseWorldX + x;
                int wz = baseWorldZ + z;

                double distFromOrigin = Math.sqrt((double) wx * wx + (double) wz * wz);

                if (distFromOrigin < 420) {
                    // Central End Island
                    double edgeFade = 1.0 - (distFromOrigin / 420.0);
                    double localNoise = fbmNoise(wx * 0.015, wz * 0.015, seed, 2);
                    if (edgeFade + localNoise * 0.25 > 0.2) {
                        heights[x][z] = (int) (58 + edgeFade * 25 + localNoise * 5);
                        // Obsidian pillars at radius ~70 to 86
                        if (distFromOrigin > 70 && distFromOrigin < 86 && Math.abs(Math.sin(Math.atan2(wz, wx) * 5)) > 0.85) {
                            heights[x][z] = 85;
                            colors[x][z] = baseObsidian;
                        } else {
                            colors[x][z] = baseEndStone;
                        }
                    } else {
                        heights[x][z] = 0;
                        colors[x][z] = COLOR_END_VOID;
                    }
                } else if (distFromOrigin < 1000) {
                    // Void Gap between central island and outer islands
                    heights[x][z] = 0;
                    colors[x][z] = COLOR_END_VOID;
                } else {
                    // Outer End Islands
                    double islandNoise = fbmNoise(wx * 0.0035, wz * 0.0035, seed, 3);
                    if (islandNoise > 0.08) {
                        heights[x][z] = (int) (55 + (islandNoise - 0.08) * 45);
                        double plantNoise = fbmNoise(wx * 0.04, wz * 0.04, seed ^ 0xAAAAL, 1);
                        colors[x][z] = (plantNoise > 0.45) ? basePurpur : baseEndStone;
                    } else {
                        heights[x][z] = 0;
                        colors[x][z] = COLOR_END_VOID;
                    }
                }
            }
        }
    }

    private int getBlockColor(String blockId, int fallback) {
        if (paletteRegistry != null) {
            int argb = paletteRegistry.getArgb(blockId);
            if ((argb & 0xFF000000) != 0) {
                return argb;
            }
        }
        return fallback;
    }

    private static int adjustRgb(int argb, int delta) {
        int a = (argb >> 24) & 0xFF;
        if (a == 0) return 0;
        int r = Math.min(255, Math.max(0, ((argb >> 16) & 0xFF) + delta));
        int g = Math.min(255, Math.max(0, ((argb >> 8) & 0xFF) + delta));
        int b = Math.min(255, Math.max(0, (argb & 0xFF) + delta));
        return (a << 24) | (r << 16) | (g << 8) | b;
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

    private static double bilerp(double[][] g, int gx, int gz, double fx, double fz) {
        double v00 = g[gx][gz];
        double v10 = g[gx + 1][gz];
        double v01 = g[gx][gz + 1];
        double v11 = g[gx + 1][gz + 1];

        double v0 = v00 + fx * (v10 - v00);
        double v1 = v01 + fx * (v11 - v01);
        return v0 + fz * (v1 - v0);
    }

    private static double fbmNoise(double x, double z, long seed, int octaves) {
        double total = 0.0;
        double frequency = 1.0;
        double amplitude = 1.0;
        double maxAmplitude = 0.0;

        for (int i = 0; i < octaves; i++) {
            total += simplexNoise2D(x * frequency, z * frequency, seed + (long) i * 1013904223L) * amplitude;
            maxAmplitude += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }

        return total / maxAmplitude;
    }

    // Fast 2D Simplex Noise with deterministic seed hashing
    private static double simplexNoise2D(double xin, double yin, long seed) {
        final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

        double s = (xin + yin) * F2;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);

        double t = (i + j) * G2;
        double X0 = i - t;
        double Y0 = j - t;
        double x0 = xin - X0;
        double y0 = yin - Y0;

        int i1, j1;
        if (x0 > y0) {
            i1 = 1; j1 = 0;
        } else {
            i1 = 0; j1 = 1;
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        double n0, n1, n2;

        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) n0 = 0.0;
        else {
            t0 *= t0;
            n0 = t0 * t0 * grad(hash(i, j, seed), x0, y0);
        }

        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) n1 = 0.0;
        else {
            t1 *= t1;
            n1 = t1 * t1 * grad(hash(i + i1, j + j1, seed), x1, y1);
        }

        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) n2 = 0.0;
        else {
            t2 *= t2;
            n2 = t2 * t2 * grad(hash(i + 1, j + 1, seed), x2, y2);
        }

        return 70.0 * (n0 + n1 + n2);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private static int hash(int x, int y, long seed) {
        long h = seed ^ ((long) x * 374761393L) ^ ((long) y * 668265263L);
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) (h ^ (h >>> 16));
    }

    private static double grad(int hash, double x, double y) {
        int h = hash & 7;
        double u = h < 4 ? x : y;
        double v = h < 4 ? y : x;
        return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -2.0 * v : 2.0 * v);
    }
}
