// src/main/java/net/atlas/map/anvil/ChunkSectionParser.java
package net.atlas.map.anvil;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ChunkSectionParser {

    public record ParsedChunkSurface(
            int[] heights,
            String[] blockNames,
            String[] biomes
    ) {}

    public static ParsedChunkSurface parseSurface(CompoundBinaryTag chunkNbt) {
        return parseSurface(chunkNbt, false);
    }

    public static ParsedChunkSurface parseSurface(CompoundBinaryTag chunkNbt, boolean isNether) {
        if (chunkNbt == null) {
            return null;
        }

        int[] heights = new int[256];
        String[] blockNames = new String[256];
        String[] biomes = new String[256];

        for (int i = 0; i < 256; i++) {
            heights[i] = -64;
            blockNames[i] = "minecraft:air";
            biomes[i] = "minecraft:plains";
        }

        ListBinaryTag sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.size() == 0) {
            return null; // Empty / ungenerated chunk
        }

        // Collect and sort sections from highest Y to lowest Y
        List<CompoundBinaryTag> sections = new ArrayList<>();
        for (BinaryTag tag : sectionsTag) {
            if (tag instanceof CompoundBinaryTag comp) {
                sections.add(comp);
            }
        }
        sections.sort(Comparator.comparingInt((CompoundBinaryTag s) -> s.getByte("Y", (byte) 0)).reversed());

        boolean[] columnFilled = new boolean[256];
        int filledCount = 0;

        for (CompoundBinaryTag section : sections) {
            int sectionY = section.getByte("Y", (byte) 0);
            int baseBlockY = sectionY * 16;

            // In Nether, skip sections above the ceiling (Y >= 128)
            if (isNether && baseBlockY >= 128) {
                continue;
            }

            CompoundBinaryTag blockStates = section.getCompound("block_states");
            ListBinaryTag paletteTag = blockStates.getList("palette");
            if (paletteTag.size() == 0) {
                continue;
            }

            // If the section palette has only 1 block and it's air, skip
            if (paletteTag.size() == 1) {
                CompoundBinaryTag singleBlock = (CompoundBinaryTag) paletteTag.get(0);
                String name = singleBlock.getString("Name", "minecraft:air");
                if (isAir(name) || (isNether && name.contains("bedrock") && baseBlockY >= 112)) {
                    continue;
                }
            }

            // Extract palette names
            String[] palette = new String[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                CompoundBinaryTag entry = (CompoundBinaryTag) paletteTag.get(i);
                palette[i] = entry.getString("Name", "minecraft:air");
            }

            CompoundBinaryTag biomesTag = section.getCompound("biomes");
            ListBinaryTag biomesPalette = (biomesTag != null) ? biomesTag.getList("palette") : null;
            long[] biomesData = (biomesTag != null) ? biomesTag.getLongArray("data") : null;

            long[] data = blockStates.getLongArray("data");
            if (data.length == 0 && palette.length == 1) {
                // Uniform section filled with single non-air block
                String uniformBlock = palette[0];
                if (isNether && uniformBlock.contains("bedrock") && baseBlockY >= 112) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int col = x + z * 16;
                        if (!columnFilled[col]) {
                            heights[col] = baseBlockY + 15;
                            blockNames[col] = uniformBlock;
                            biomes[col] = getBiomeAt(biomesPalette, biomesData, x, 15, z);
                            columnFilled[col] = true;
                            filledCount++;
                        }
                    }
                }
            } else if (data.length > 0) {
                // Bit-packed block states
                int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
                int entriesPerLong = 64 / bitsPerEntry;
                long mask = (1L << bitsPerEntry) - 1L;

                for (int localY = 15; localY >= 0; localY--) {
                    int worldY = baseBlockY + localY;
                    if (isNether && worldY >= 120) {
                        continue; // Skip the bedrock ceiling in Nether
                    }

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int col = x + z * 16;
                            if (columnFilled[col]) {
                                continue;
                            }

                            int blockIndex = (localY * 16 + z) * 16 + x;
                            int longIndex = blockIndex / entriesPerLong;
                            int bitOffset = (blockIndex % entriesPerLong) * bitsPerEntry;

                            if (longIndex < data.length) {
                                int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & mask);
                                if (paletteIndex >= 0 && paletteIndex < palette.length) {
                                    String blockName = palette[paletteIndex];
                                    if (!isAir(blockName)) {
                                        if (isNether && blockName.contains("bedrock") && worldY >= 115) {
                                            continue; // Skip bedrock roof in Nether
                                        }
                                        heights[col] = worldY;
                                        blockNames[col] = blockName;
                                        biomes[col] = getBiomeAt(biomesPalette, biomesData, x, localY, z);
                                        columnFilled[col] = true;
                                        filledCount++;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (filledCount >= 256) {
                break;
            }
        }

        if (filledCount == 0) {
            return null; // All sections were air or empty
        }
        return new ParsedChunkSurface(heights, blockNames, biomes);
    }

    public static boolean isAir(String name) {
        if (name == null) return true;
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    private static String getBiomeAt(ListBinaryTag biomesPalette, long[] biomesData, int lx, int localY, int lz) {
        if (biomesPalette == null || biomesPalette.size() == 0) {
            return "minecraft:plains";
        }
        if (biomesPalette.size() == 1) {
            BinaryTag bt = biomesPalette.get(0);
            return (bt instanceof StringBinaryTag sbt) ? sbt.value() : bt.toString().replace("\"", "");
        }
        if (biomesData == null || biomesData.length == 0) {
            BinaryTag bt = biomesPalette.get(0);
            return (bt instanceof StringBinaryTag sbt) ? sbt.value() : bt.toString().replace("\"", "");
        }
        int bBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(biomesPalette.size() - 1));
        int biomesPerLong = 64 / bBits;
        long bMask = (1L << bBits) - 1L;
        int qx = lx / 4;
        int qz = lz / 4;
        int qy = localY / 4;
        int bIdx = (qy * 4 + qz) * 4 + qx;
        int bLongIdx = bIdx / biomesPerLong;
        int bBitOffset = (bIdx % biomesPerLong) * bBits;
        if (bLongIdx >= 0 && bLongIdx < biomesData.length) {
            int pIdx = (int) ((biomesData[bLongIdx] >>> bBitOffset) & bMask);
            if (pIdx >= 0 && pIdx < biomesPalette.size()) {
                BinaryTag bt = biomesPalette.get(pIdx);
                return (bt instanceof StringBinaryTag sbt) ? sbt.value() : bt.toString().replace("\"", "");
            }
        }
        BinaryTag bt = biomesPalette.get(0);
        return (bt instanceof StringBinaryTag sbt) ? sbt.value() : bt.toString().replace("\"", "");
    }
}
