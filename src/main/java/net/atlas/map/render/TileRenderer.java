// src/main/java/net/atlas/map/render/TileRenderer.java
package net.atlas.map.render;

import net.atlas.config.MapConfig;
import net.atlas.map.TileCoordinate;
import net.atlas.map.anvil.ChunkSectionParser;
import net.atlas.map.anvil.RegionFileReader;
import net.atlas.map.cache.TileDiskCache;
import net.atlas.map.cache.TileMemoryCache;
import net.atlas.map.snapshot.SnapshotProvider;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class TileRenderer {

    private static final byte[] EMPTY_TILE_BYTES;
    static {
        BufferedImage empty = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(512)) {
            ImageIO.write(empty, "PNG", baos);
            bytes = baos.toByteArray();
        } catch (IOException e) {
            bytes = new byte[0];
        }
        EMPTY_TILE_BYTES = bytes;
    }

    private final Plugin plugin;
    private final MapConfig mapConfig;
    private final PaletteRegistry paletteRegistry;
    private final TileMemoryCache memoryCache;
    private final TileDiskCache diskCache;
    private final SnapshotProvider snapshotProvider;
    private final Semaphore ioLimiter;
    private final SeedBiomeGenerator seedBiomeGenerator;

    public TileRenderer(Plugin plugin, MapConfig mapConfig, PaletteRegistry paletteRegistry,
                        TileMemoryCache memoryCache, TileDiskCache diskCache) {
        this.plugin = plugin;
        this.mapConfig = mapConfig;
        this.paletteRegistry = paletteRegistry;
        this.memoryCache = memoryCache;
        this.diskCache = diskCache;
        this.snapshotProvider = new SnapshotProvider(plugin);
        this.ioLimiter = new Semaphore(mapConfig.maxConcurrentChunkReads(), true);
        this.seedBiomeGenerator = new SeedBiomeGenerator(paletteRegistry);
    }

    public byte[] getOrRenderTile(TileCoordinate coord) {
        // 1. Check L1 Memory Cache
        byte[] cached = memoryCache.get(coord);
        if (cached != null) {
            return cached;
        }

        // 2. Check L2 Disk Cache
        cached = diskCache.get(coord);
        if (cached != null) {
            memoryCache.put(coord, cached);
            return cached;
        }

        // 3. Verify Bounding Box
        if (!mapConfig.isTileWithinBoundingBox(coord.tileX(), coord.tileZ())) {
            return getEmptyTile();
        }

        // 4. Render Tile Asynchronously
        byte[] rendered = renderTile(coord);
        if (rendered != null) {
            memoryCache.put(coord, rendered);
            diskCache.put(coord, rendered);
            return rendered;
        }

        return getEmptyTile();
    }

    private byte[] renderTile(TileCoordinate coord) {
        World world = resolveWorld(coord.dimension());
        long worldSeed = (world != null) ? world.getSeed() : 123456789L;
        boolean isNether = (world != null && world.getEnvironment() == World.Environment.NETHER)
                || (coord.dimension() != null && coord.dimension().toLowerCase().contains("nether"));
        boolean isEnd = (world != null && world.getEnvironment() == World.Environment.THE_END)
                || (coord.dimension() != null && coord.dimension().toLowerCase().contains("end"));

        int startChunkX = coord.tileX() * 16;
        int startChunkZ = coord.tileZ() * 16;

        int[][] heights = new int[256][256];
        int[][] colors = new int[256][256];

        // Seed-based procedural terrain and biome synthesis directly derived from Minecraft World BiomeProvider
        seedBiomeGenerator.generateBaseTile(world, worldSeed, coord.dimension(), coord.tileX(), coord.tileZ(), heights, colors);

        File worldFolder = (world != null) ? world.getWorldFolder() : null;
        File regionFolder = (worldFolder != null) ? getRegionFolder(worldFolder, coord.dimension()) : null;

        boolean[][] isLoadedPixel = new boolean[256][256];
        boolean[][] isLoadedChunk = new boolean[16][16];
        boolean hasUnloaded = false;

        // Process 16x16 chunks for the 256x256 tile
        if (world != null) {
            ChunkSnapshot[][] snapshots = null;
            try {
                snapshots = snapshotProvider.getTileSnapshots(world, startChunkX, startChunkZ).get(500, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}

            Map<String, RegionFileReader> openReaders = new HashMap<>();
            Set<String> missingRegions = new HashSet<>();
            try {
                for (int cx = 0; cx < 16; cx++) {
                    for (int cz = 0; cz < 16; cz++) {
                        int worldChunkX = startChunkX + cx;
                        int worldChunkZ = startChunkZ + cz;
                        int basePixelX = cx * 16;
                        int basePixelZ = cz * 16;

                        boolean chunkLoaded = false;
                        ChunkSnapshot snap = (snapshots != null) ? snapshots[cx][cz] : null;
                        if (snap != null) {
                            chunkLoaded = renderFromSnapshot(snap, heights, colors, basePixelX, basePixelZ, world.getMinHeight(), isNether, isEnd);
                        } else if (mapConfig.isRenderUnloadedChunks() && regionFolder != null && regionFolder.exists()) {
                            try {
                                chunkLoaded = renderFromAnvil(regionFolder, worldChunkX, worldChunkZ, heights, colors, basePixelX, basePixelZ, isNether, isEnd, openReaders, missingRegions);
                            } catch (Throwable ignored) {}
                        }

                        if (chunkLoaded) {
                            isLoadedChunk[cx][cz] = true;
                            for (int lx = 0; lx < 16; lx++) {
                                for (int lz = 0; lz < 16; lz++) {
                                    isLoadedPixel[basePixelX + lx][basePixelZ + lz] = true;
                                }
                            }
                        } else {
                            hasUnloaded = true;
                        }
                    }
                }
            } finally {
                for (RegionFileReader reader : openReaders.values()) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {}
                }
            }
        } else {
            hasUnloaded = true;
        }

        // Overlay live placed and broken blocks from memory buffer before hillshading
        LiveBlockBuffer.applyOverlay(coord.dimension(), coord.tileX(), coord.tileZ(), heights, colors);

        // Apply Hillshading and composite into shaded array
        int[][] shadedPixels = new int[256][256];
        double intensity = mapConfig.hillshadingIntensity();

        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                int baseColor = colors[x][z];
                if ((baseColor & 0xFF000000) == 0) {
                    baseColor = seedBiomeGenerator.sampleFallbackColor(coord.dimension());
                    colors[x][z] = baseColor;
                    if (heights[x][z] <= -64) {
                        heights[x][z] = isEnd ? 0 : (isNether ? 32 : 64);
                    }
                }

                // In The End, void space (height 0) remains pristine cosmic abyss without artificial shadow drop
                if (isEnd && heights[x][z] == 0) {
                    shadedPixels[x][z] = baseColor | 0xFF000000;
                    continue;
                }

                int currentY = heights[x][z];
                int northY = (z > 0) ? heights[x][z - 1] : currentY;
                int westY = (x > 0) ? heights[x - 1][z] : currentY;

                // Clamp neighbor slope differences to eliminate harsh cliff shadows at chunk borders
                int clampedNorth = Math.max(currentY - 12, Math.min(currentY + 12, northY));
                int clampedWest = Math.max(currentY - 12, Math.min(currentY + 12, westY));

                int shaded = HillshadingEngine.applyShading2D(baseColor, currentY, clampedNorth, clampedWest, intensity);
                shadedPixels[x][z] = shaded | 0xFF000000; // 100% opaque alpha: zero gaps or transparent black
            }
        }

        // If tile contains unexplored seed chunks, apply intense Bokeh defocus blur to uncharted territory
        if (hasUnloaded) {
            applyCompleteDefocusBlur(shadedPixels, isLoadedPixel, isLoadedChunk, isEnd, heights);
        }

        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                image.setRGB(x, z, shadedPixels[x][z]);
            }
        }

        // Encode to PNG bytes
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(32768)) {
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to encode tile PNG: " + coord.toKey(), e);
            return getEmptyTile();
        }
    }

    private boolean renderFromSnapshot(ChunkSnapshot snap, int[][] heights, int[][] colors, int basePixelX, int basePixelZ, int minHeight, boolean isNether, boolean isEnd) {
        int solidCount = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int px = basePixelX + lx;
                int pz = basePixelZ + lz;

                int highestY = snap.getHighestBlockYAt(lx, lz);
                if (isNether) {
                    // Cull bedrock ceiling (typically Y=123-127) to expose actual Nether caverns and terrain
                    if (highestY >= 120) {
                        highestY = 120;
                    }
                    Material mat = snap.getBlockType(lx, highestY, lz);
                    while ((mat.isAir() || mat == Material.BEDROCK) && highestY > minHeight) {
                        highestY--;
                        mat = snap.getBlockType(lx, highestY, lz);
                    }

                    if (!mat.isAir() && mat != Material.BEDROCK && highestY > minHeight) {
                        Biome biome = snap.getBiome(lx, Math.max(minHeight + 1, highestY), lz);
                        int baseArgb = paletteRegistry.getArgb(mat);
                        int tintedArgb = BiomeColorEngine.tintBlock(baseArgb, mat.name(), biome.name());

                        if ((tintedArgb & 0xFF000000) != 0) {
                            heights[px][pz] = highestY;
                            colors[px][pz] = tintedArgb | 0xFF000000;
                            solidCount++;
                        }
                    }
                } else if (isEnd) {
                    Material mat = snap.getBlockType(lx, highestY, lz);
                    while (mat.isAir() && highestY > minHeight) {
                        highestY--;
                        mat = snap.getBlockType(lx, highestY, lz);
                    }

                    if (!mat.isAir() && highestY > minHeight) {
                        Biome biome = snap.getBiome(lx, Math.max(minHeight + 1, highestY), lz);
                        int baseArgb = paletteRegistry.getArgb(mat);
                        int tintedArgb = BiomeColorEngine.tintBlock(baseArgb, mat.name(), biome.name());

                        if ((tintedArgb & 0xFF000000) != 0) {
                            heights[px][pz] = highestY;
                            colors[px][pz] = tintedArgb | 0xFF000000;
                            solidCount++;
                        }
                    } else {
                        // Unloaded or empty void space in The End
                        heights[px][pz] = 0;
                        colors[px][pz] = SeedBiomeGenerator.COLOR_END_VOID;
                    }
                } else {
                    Material mat = snap.getBlockType(lx, highestY, lz);
                    while (mat.isAir() && highestY > minHeight) {
                        highestY--;
                        mat = snap.getBlockType(lx, highestY, lz);
                    }

                    if (!mat.isAir() && highestY > minHeight) {
                        Biome biome = snap.getBiome(lx, Math.max(minHeight + 1, highestY), lz);
                        int baseArgb = paletteRegistry.getArgb(mat);
                        int tintedArgb = BiomeColorEngine.tintBlock(baseArgb, mat.name(), biome.name());

                        if ((tintedArgb & 0xFF000000) != 0) {
                            heights[px][pz] = highestY;
                            colors[px][pz] = tintedArgb | 0xFF000000;
                            solidCount++;
                        }
                    }
                }
            }
        }
        return isEnd || solidCount >= 16;
    }

    private boolean renderFromAnvil(File regionFolder, int chunkX, int chunkZ, int[][] heights, int[][] colors,
                                    int basePixelX, int basePixelZ, boolean isNether, boolean isEnd,
                                    Map<String, RegionFileReader> openReaders, Set<String> missingRegions) {
        int rx = Math.floorDiv(chunkX, 32);
        int rz = Math.floorDiv(chunkZ, 32);
        String fileName = "r." + rx + "." + rz + ".mca";

        if (missingRegions.contains(fileName)) {
            return false;
        }

        RegionFileReader reader = openReaders.get(fileName);
        if (reader == null) {
            File mcaFile = new File(regionFolder, fileName);
            if (!mcaFile.exists() || mcaFile.length() < 8192) {
                missingRegions.add(fileName);
                return false;
            }
            reader = new RegionFileReader(mcaFile, ioLimiter);
            openReaders.put(fileName, reader);
        }

        try {
            CompoundBinaryTag nbt = reader.readChunkNbt(chunkX, chunkZ);
            if (nbt != null) {
                String status = nbt.getString("Status", "");
                if (status != null && !status.isEmpty()) {
                    String s = status.toLowerCase();
                    if (s.contains("empty") || s.contains("structure_starts")
                            || s.contains("structure_references") || s.contains("biomes")
                            || s.contains("noise")) {
                        return false; // Proto-chunk without generated surface terrain
                    }
                }

                ChunkSectionParser.ParsedChunkSurface surface = ChunkSectionParser.parseSurface(nbt, isNether);
                if (surface != null) {
                    int solidCount = 0;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int col = lx + lz * 16;
                            int px = basePixelX + lx;
                            int pz = basePixelZ + lz;

                            int h = surface.heights()[col];
                            String block = surface.blockNames()[col];
                            String biome = surface.biomes()[col];

                            if (block != null && !ChunkSectionParser.isAir(block) && h > -64) {
                                int baseArgb = paletteRegistry.getArgb(block);
                                int tintedArgb = BiomeColorEngine.tintBlock(baseArgb, block, biome);

                                if ((tintedArgb & 0xFF000000) != 0) {
                                    heights[px][pz] = h;
                                    colors[px][pz] = tintedArgb | 0xFF000000;
                                    solidCount++;
                                }
                            } else if (isEnd) {
                                heights[px][pz] = 0;
                                colors[px][pz] = SeedBiomeGenerator.COLOR_END_VOID;
                            }
                        }
                    }
                    return isEnd || solidCount >= 16;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void invalidateTile(String dimension, int blockX, int blockZ) {
        int tileX = Math.floorDiv(blockX, 256);
        int tileZ = Math.floorDiv(blockZ, 256);
        TileCoordinate coord = new TileCoordinate(dimension, 0, tileX, tileZ);
        memoryCache.invalidate(coord);
        diskCache.invalidate(coord);
    }

    private World resolveWorld(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        String dimClean = dimension.toLowerCase().trim();
        for (World w : Bukkit.getWorlds()) {
            String wName = w.getName().toLowerCase();
            String wKey = w.getKey().asString().toLowerCase();
            if (wName.equals(dimClean) || wKey.equals(dimClean)) {
                return w;
            }
        }
        // Match environment fallback
        if (dimClean.contains("nether")) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == World.Environment.NETHER || w.getName().toLowerCase().contains("nether")) {
                    return w;
                }
            }
        } else if (dimClean.contains("end")) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == World.Environment.THE_END || w.getName().toLowerCase().contains("end")) {
                    return w;
                }
            }
        } else {
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == World.Environment.NORMAL || w.getName().toLowerCase().contains("world")) {
                    return w;
                }
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    private File getRegionFolder(File worldFolder, String dimension) {
        if (worldFolder == null) {
            return new File("region");
        }
        String dimClean = (dimension != null) ? dimension.toLowerCase() : "";
        boolean isNether = dimClean.contains("nether");
        boolean isEnd = dimClean.contains("end");

        if (isNether) {
            // 1. Check DIM-1/region in current world folder
            File dimMinusOne = new File(worldFolder, "DIM-1/region");
            if (dimMinusOne.exists() && dimMinusOne.isDirectory()) {
                return dimMinusOne;
            }
            // 2. Check direct region in worldFolder (e.g. world_nether/region)
            File directRegion = new File(worldFolder, "region");
            if (directRegion.exists() && directRegion.isDirectory()) {
                return directRegion;
            }
            // 3. Check sibling world_nether folder
            if (worldFolder.getParentFile() != null) {
                File sibling = new File(worldFolder.getParentFile(), worldFolder.getName() + "_nether/DIM-1/region");
                if (sibling.exists() && sibling.isDirectory()) return sibling;
                File siblingDirect = new File(worldFolder.getParentFile(), worldFolder.getName() + "_nether/region");
                if (siblingDirect.exists() && siblingDirect.isDirectory()) return siblingDirect;
            }
            return dimMinusOne;
        }

        if (isEnd) {
            // 1. Check DIM1/region in current world folder
            File dimPlusOne = new File(worldFolder, "DIM1/region");
            if (dimPlusOne.exists() && dimPlusOne.isDirectory()) {
                return dimPlusOne;
            }
            // 2. Check direct region in worldFolder (e.g. world_the_end/region)
            File directRegion = new File(worldFolder, "region");
            if (directRegion.exists() && directRegion.isDirectory()) {
                return directRegion;
            }
            // 3. Check sibling world_the_end folder
            if (worldFolder.getParentFile() != null) {
                File sibling = new File(worldFolder.getParentFile(), worldFolder.getName() + "_the_end/DIM1/region");
                if (sibling.exists() && sibling.isDirectory()) return sibling;
                File siblingDirect = new File(worldFolder.getParentFile(), worldFolder.getName() + "_the_end/region");
                if (siblingDirect.exists() && siblingDirect.isDirectory()) return siblingDirect;
            }
            return dimPlusOne;
        }

        // Default Overworld
        File directRegion = new File(worldFolder, "region");
        if (directRegion.exists() && directRegion.isDirectory()) {
            return directRegion;
        }
        return directRegion;
    }

    public PaletteRegistry getPaletteRegistry() {
        return paletteRegistry;
    }

    /**
     * Applies an intense cinematic Bokeh defocus blur to unexplored / unloaded territory
     * so players and administrators can instantly differentiate between loaded (sharp, vivid, illuminated)
     * and unloaded (heavily defocused, muted, shadowed fog-of-war) areas.
     */
    private void applyCompleteDefocusBlur(int[][] shadedPixels, boolean[][] isLoadedPixel, boolean[][] isLoadedChunk, boolean isEnd, int[][] heights) {
        int width = 256;
        int height = 256;

        // Step 1: Precompute perimeter contact shadow for unloaded pixels bordering loaded chunks
        // Creates a crisp, tactical ambient occlusion contour that makes the loaded region pop
        float[][] boundaryShadow = new float[width][height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                boundaryShadow[x][z] = 1.0f;
            }
        }

        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                if (isLoadedChunk[cx][cz]) continue;

                boolean westLoaded = (cx > 0 && isLoadedChunk[cx - 1][cz]);
                boolean eastLoaded = (cx < 15 && isLoadedChunk[cx + 1][cz]);
                boolean northLoaded = (cz > 0 && isLoadedChunk[cx][cz - 1]);
                boolean southLoaded = (cz < 15 && isLoadedChunk[cx][cz + 1]);

                boolean nwLoaded = (cx > 0 && cz > 0 && isLoadedChunk[cx - 1][cz - 1]);
                boolean neLoaded = (cx < 15 && cz > 0 && isLoadedChunk[cx + 1][cz - 1]);
                boolean swLoaded = (cx > 0 && cz < 15 && isLoadedChunk[cx - 1][cz + 1]);
                boolean seLoaded = (cx < 15 && cz < 15 && isLoadedChunk[cx + 1][cz + 1]);

                if (!westLoaded && !eastLoaded && !northLoaded && !southLoaded
                        && !nwLoaded && !neLoaded && !swLoaded && !seLoaded) {
                    continue; // No loaded neighbor in this tile
                }

                int basePixelX = cx * 16;
                int basePixelZ = cz * 16;

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int dist = 99;
                        if (westLoaded) dist = Math.min(dist, lx + 1);
                        if (eastLoaded) dist = Math.min(dist, 16 - lx);
                        if (northLoaded) dist = Math.min(dist, lz + 1);
                        if (southLoaded) dist = Math.min(dist, 16 - lz);

                        if (nwLoaded) dist = Math.min(dist, (int) Math.round(Math.hypot(lx + 1, lz + 1)));
                        if (neLoaded) dist = Math.min(dist, (int) Math.round(Math.hypot(16 - lx, lz + 1)));
                        if (swLoaded) dist = Math.min(dist, (int) Math.round(Math.hypot(lx + 1, 16 - lz)));
                        if (seLoaded) dist = Math.min(dist, (int) Math.round(Math.hypot(16 - lx, 16 - lz)));

                        float s = 1.0f;
                        if (dist == 1) s = 0.70f;
                        else if (dist == 2) s = 0.79f;
                        else if (dist == 3) s = 0.88f;
                        else if (dist == 4) s = 0.95f;

                        boundaryShadow[basePixelX + lx][basePixelZ + lz] = s;
                    }
                }
            }
        }

        // Step 2: 4x Downsampling with Area Averaging (64x64 grid)
        int scale = 4;
        int sW = width / scale; // 64
        int sH = height / scale; // 64

        float[] smallR = new float[sW * sH];
        float[] smallG = new float[sW * sH];
        float[] smallB = new float[sW * sH];

        for (int sz = 0; sz < sH; sz++) {
            int startZ = sz * scale;
            for (int sx = 0; sx < sW; sx++) {
                int startX = sx * scale;
                int sumR = 0, sumG = 0, sumB = 0;
                for (int dz = 0; dz < scale; dz++) {
                    int pz = startZ + dz;
                    for (int dx = 0; dx < scale; dx++) {
                        int px = startX + dx;
                        int col = shadedPixels[px][pz];
                        sumR += (col >> 16) & 0xFF;
                        sumG += (col >> 8) & 0xFF;
                        sumB += col & 0xFF;
                    }
                }
                int sIdx = sz * sW + sx;
                smallR[sIdx] = sumR / 16.0f;
                smallG[sIdx] = sumG / 16.0f;
                smallB[sIdx] = sumB / 16.0f;
            }
        }

        // Step 3: Multi-pass separable Gaussian-like box blur on the 64x64 buffer
        // Radius 8 on 64x64 buffer corresponds to radius 32 on the 256x256 map!
        // 3 passes produces an effective kernel width > 70 blocks, completely dissolving blockiness!
        int blurRadius = 8;
        float[] tempR = new float[sW * sH];
        float[] tempG = new float[sW * sH];
        float[] tempB = new float[sW * sH];

        for (int pass = 0; pass < 3; pass++) {
            // Horizontal Pass
            for (int sz = 0; sz < sH; sz++) {
                int rowOffset = sz * sW;
                for (int sx = 0; sx < sW; sx++) {
                    float sumR = 0, sumG = 0, sumB = 0;
                    int count = 0;
                    int minX = Math.max(0, sx - blurRadius);
                    int maxX = Math.min(sW - 1, sx + blurRadius);
                    for (int kx = minX; kx <= maxX; kx++) {
                        int idx = rowOffset + kx;
                        sumR += smallR[idx];
                        sumG += smallG[idx];
                        sumB += smallB[idx];
                        count++;
                    }
                    int outIdx = rowOffset + sx;
                    tempR[outIdx] = sumR / count;
                    tempG[outIdx] = sumG / count;
                    tempB[outIdx] = sumB / count;
                }
            }

            // Vertical Pass
            for (int sx = 0; sx < sW; sx++) {
                for (int sz = 0; sz < sH; sz++) {
                    float sumR = 0, sumG = 0, sumB = 0;
                    int count = 0;
                    int minZ = Math.max(0, sz - blurRadius);
                    int maxZ = Math.min(sH - 1, sz + blurRadius);
                    for (int kz = minZ; kz <= maxZ; kz++) {
                        int idx = kz * sW + sx;
                        sumR += tempR[idx];
                        sumG += tempG[idx];
                        sumB += tempB[idx];
                        count++;
                    }
                    int outIdx = sz * sW + sx;
                    smallR[outIdx] = sumR / count;
                    smallG[outIdx] = sumG / count;
                    smallB[outIdx] = sumB / count;
                }
            }
        }

        // Step 4: Bilinear Upsampling back to 256x256 and Stylistic Fog-of-War Compositing
        for (int z = 0; z < height; z++) {
            float v = (z + 0.5f) / scale - 0.5f;
            int z0 = Math.max(0, (int) Math.floor(v));
            int z1 = Math.min(sH - 1, z0 + 1);
            float fz = Math.max(0.0f, Math.min(1.0f, v - z0));

            for (int x = 0; x < width; x++) {
                if (isLoadedPixel[x][z]) {
                    // Loaded pixels remain 100% untouched, razor-sharp, and fully saturated!
                    continue;
                }
                if (isEnd && heights[x][z] == 0) {
                    // Cosmic void space in The End remains untouched
                    continue;
                }

                float u = (x + 0.5f) / scale - 0.5f;
                int x0 = Math.max(0, (int) Math.floor(u));
                int x1 = Math.min(sW - 1, x0 + 1);
                float fx = Math.max(0.0f, Math.min(1.0f, u - x0));

                int idx00 = z0 * sW + x0;
                int idx01 = z0 * sW + x1;
                int idx10 = z1 * sW + x0;
                int idx11 = z1 * sW + x1;

                // Bilinear interpolation for Red
                float topR = smallR[idx00] * (1.0f - fx) + smallR[idx01] * fx;
                float botR = smallR[idx10] * (1.0f - fx) + smallR[idx11] * fx;
                float blurredR = topR * (1.0f - fz) + botR * fz;

                // Bilinear interpolation for Green
                float topG = smallG[idx00] * (1.0f - fx) + smallG[idx01] * fx;
                float botG = smallG[idx10] * (1.0f - fx) + smallG[idx11] * fx;
                float blurredG = topG * (1.0f - fz) + botG * fz;

                // Bilinear interpolation for Blue
                float topB = smallB[idx00] * (1.0f - fx) + smallB[idx01] * fx;
                float botB = smallB[idx10] * (1.0f - fx) + smallB[idx11] * fx;
                float blurredB = topB * (1.0f - fz) + botB * fz;

                // Desaturation (40% towards grayscale luminance): Mutes background so loaded chunks POP
                float gray = blurredR * 0.299f + blurredG * 0.587f + blurredB * 0.114f;
                float sat = 0.60f;
                float desatR = blurredR * sat + gray * (1.0f - sat);
                float desatG = blurredG * sat + gray * (1.0f - sat);
                float desatB = blurredB * sat + gray * (1.0f - sat);

                // Fog-of-War Dimming (0.70x brightness) + Perimeter Edge Shadow
                float fogMultiplier = 0.70f * boundaryShadow[x][z];

                int finalR = Math.min(255, Math.max(0, (int) (desatR * fogMultiplier)));
                int finalG = Math.min(255, Math.max(0, (int) (desatG * fogMultiplier)));
                int finalB = Math.min(255, Math.max(0, (int) (desatB * fogMultiplier)));

                shadedPixels[x][z] = 0xFF000000 | (finalR << 16) | (finalG << 8) | finalB;
            }
        }
    }

    public byte[] getEmptyTile() {
        return EMPTY_TILE_BYTES;
    }
}
