// src/main/java/net/atlas/offline/OfflinePlayerService.java
package net.atlas.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.atlas.offline.model.OfflinePlayerData;
import net.atlas.telemetry.TelemetryEngine;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

public final class OfflinePlayerService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Plugin plugin;
    private final Path snapshotsDir;

    public OfflinePlayerService(Plugin plugin, Path snapshotsDir) {
        this.plugin = plugin;
        this.snapshotsDir = snapshotsDir;
    }

    public void saveSnapshotAsync(Player player) {
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        String world = TelemetryEngine.canonicalizeDimension(player.getWorld());
        double x = player.getLocation().getX();
        double y = player.getLocation().getY();
        double z = player.getLocation().getZ();
        float yaw = player.getLocation().getYaw();
        float pitch = player.getLocation().getPitch();
        long playtime = player.getStatistic(Statistic.PLAY_ONE_MINUTE);

        CompletableFuture.runAsync(() -> {
            try {
                Path filePath = snapshotsDir.resolve(uuid.toString() + ".json");
                JsonObject root = new JsonObject();
                root.addProperty("uuid", uuid.toString());
                root.addProperty("name", name);
                root.addProperty("lastSeen", now);
                root.addProperty("world", world);
                root.addProperty("x", x);
                root.addProperty("y", y);
                root.addProperty("z", z);
                root.addProperty("yaw", yaw);
                root.addProperty("pitch", pitch);
                root.addProperty("playtimeTicks", playtime);

                try (FileWriter writer = new FileWriter(filePath.toFile())) {
                    GSON.toJson(root, writer);
                }
            } catch (Exception ignored) {}
        });
    }

    public CompletableFuture<OfflinePlayerData> loadOfflinePlayerDataAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String name = "Unknown";
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer.getName() != null) {
                name = offlinePlayer.getName();
            }

            long lastSeen = offlinePlayer.getLastSeen();
            long playtime = 0;
            try {
                playtime = offlinePlayer.getStatistic(Statistic.PLAY_ONE_MINUTE);
            } catch (Exception ignored) {}

            ResolvedLocation loc = resolveLocation(uuid, offlinePlayer);
            String worldName = loc.world();
            double x = loc.x();
            double y = loc.y();
            double z = loc.z();
            float yaw = loc.yaw();
            float pitch = loc.pitch();

            // Check if name or lastSeen can be extracted from snapshot
            File snapFile = snapshotsDir.resolve(uuid.toString() + ".json").toFile();
            if (snapFile.exists()) {
                try (FileReader reader = new FileReader(snapFile)) {
                    JsonObject snap = JsonParser.parseReader(reader).getAsJsonObject();
                    if (snap.has("name") && (name.equals("Unknown") || name.isBlank())) {
                        name = snap.get("name").getAsString();
                    }
                    if (snap.has("lastSeen") && lastSeen <= 0) {
                        lastSeen = snap.get("lastSeen").getAsLong();
                    }
                } catch (Exception ignored) {}
            }

            // Search candidate playerdata/{uuid}.dat files for inventories
            File worldDir = !Bukkit.getWorlds().isEmpty() ? Bukkit.getWorlds().get(0).getWorldFolder() : Bukkit.getWorldContainer();
            List<File> candidateDataFiles = new ArrayList<>();
            candidateDataFiles.add(new File(worldDir, "playerdata/" + uuid + ".dat"));
            candidateDataFiles.add(new File(Bukkit.getWorldContainer(), "playerdata/" + uuid + ".dat"));
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                candidateDataFiles.add(new File(w.getWorldFolder(), "playerdata/" + uuid + ".dat"));
                candidateDataFiles.add(new File(Bukkit.getWorldContainer(), w.getName() + "/playerdata/" + uuid + ".dat"));
            }

            File playerDataFile = null;
            for (File candidate : candidateDataFiles) {
                if (candidate.exists()) {
                    playerDataFile = candidate;
                    break;
                }
            }

            JsonArray inventoryArray = new JsonArray();
            JsonArray enderArray = new JsonArray();

            if (playerDataFile != null && playerDataFile.exists()) {
                try (InputStream raw = Files.newInputStream(playerDataFile.toPath());
                     GZIPInputStream gz = new GZIPInputStream(raw)) {

                    CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(gz);

                    // Inventory
                    ListBinaryTag invList = root.getList("Inventory");
                    for (BinaryTag tag : invList) {
                        if (tag instanceof CompoundBinaryTag itemComp) {
                            inventoryArray.add(parseNbtItem(itemComp));
                        }
                    }

                    // EnderItems
                    ListBinaryTag enderList = root.getList("EnderItems");
                    for (BinaryTag tag : enderList) {
                        if (tag instanceof CompoundBinaryTag itemComp) {
                            enderArray.add(parseNbtItem(itemComp));
                        }
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to parse playerdata NBT for " + uuid + ": " + e.getMessage());
                }
            }

            // Normalize world name into a valid dimension key
            worldName = TelemetryEngine.canonicalizeDimensionName(worldName);

            return new OfflinePlayerData(
                    uuid,
                    name,
                    lastSeen,
                    worldName,
                    x, y, z,
                    yaw, pitch,
                    playtime,
                    inventoryArray,
                    enderArray
            );
        });
    }

    private JsonObject parseNbtItem(CompoundBinaryTag itemComp) {
        JsonObject obj = new JsonObject();
        int slot = itemComp.getByte("Slot", (byte) 0) & 0xFF;
        String id = itemComp.getString("id", "minecraft:air");
        int count = itemComp.getByte("Count", (byte) 1);

        obj.addProperty("slot", slot);
        obj.addProperty("material", id.replace("minecraft:", "").toUpperCase());
        obj.addProperty("count", count);

        // Check for tag / components
        if (itemComp.keySet().contains("tag")) {
            CompoundBinaryTag tag = itemComp.getCompound("tag");
            if (tag.keySet().contains("display")) {
                CompoundBinaryTag display = tag.getCompound("display");
                if (display.keySet().contains("Name")) {
                    obj.addProperty("customName", display.getString("Name"));
                }
            }
            if (tag.keySet().contains("Damage")) {
                obj.addProperty("damage", tag.getInt("Damage"));
            }
        }

        return obj;
    }

    public record ResolvedLocation(String world, double x, double y, double z, float yaw, float pitch, boolean found) {}

    public ResolvedLocation resolveLocation(UUID uuid, OfflinePlayer offlinePlayer) {
        String defaultWorld = !Bukkit.getWorlds().isEmpty() ? Bukkit.getWorlds().get(0).getName() : "minecraft:overworld";

        // 1. Direct Bukkit OfflinePlayer.getLocation()
        if (offlinePlayer != null) {
            try {
                org.bukkit.Location bukkitLoc = offlinePlayer.getLocation();
                if (bukkitLoc != null && bukkitLoc.getWorld() != null) {
                    return new ResolvedLocation(
                            TelemetryEngine.canonicalizeDimension(bukkitLoc.getWorld()),
                            bukkitLoc.getX(),
                            bukkitLoc.getY(),
                            bukkitLoc.getZ(),
                            bukkitLoc.getYaw(),
                            bukkitLoc.getPitch(),
                            true
                    );
                }
            } catch (Exception ignored) {}
        }

        // 2. Read from offline telemetry snapshot JSON
        File snapFile = snapshotsDir.resolve(uuid.toString() + ".json").toFile();
        if (snapFile.exists()) {
            try (FileReader reader = new FileReader(snapFile)) {
                JsonObject snap = JsonParser.parseReader(reader).getAsJsonObject();
                if (snap.has("world") && snap.has("x") && snap.has("z")) {
                    return new ResolvedLocation(
                            TelemetryEngine.canonicalizeDimensionName(snap.get("world").getAsString()),
                            snap.get("x").getAsDouble(),
                            snap.has("y") ? snap.get("y").getAsDouble() : 64,
                            snap.get("z").getAsDouble(),
                            snap.has("yaw") ? snap.get("yaw").getAsFloat() : 0f,
                            snap.has("pitch") ? snap.get("pitch").getAsFloat() : 0f,
                            true
                    );
                }
            } catch (Exception ignored) {}
        }

        // 3. Search candidate playerdata/{uuid}.dat files
        File worldDir = !Bukkit.getWorlds().isEmpty() ? Bukkit.getWorlds().get(0).getWorldFolder() : Bukkit.getWorldContainer();
        List<File> candidateDataFiles = new ArrayList<>();
        candidateDataFiles.add(new File(worldDir, "playerdata/" + uuid + ".dat"));
        candidateDataFiles.add(new File(Bukkit.getWorldContainer(), "playerdata/" + uuid + ".dat"));
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            candidateDataFiles.add(new File(w.getWorldFolder(), "playerdata/" + uuid + ".dat"));
            candidateDataFiles.add(new File(Bukkit.getWorldContainer(), w.getName() + "/playerdata/" + uuid + ".dat"));
        }

        for (File candidate : candidateDataFiles) {
            if (candidate.exists()) {
                try (InputStream raw = Files.newInputStream(candidate.toPath());
                     GZIPInputStream gz = new GZIPInputStream(raw)) {
                    CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(gz);

                    String worldName = defaultWorld;
                    if (root.keySet().contains("Dimension")) {
                        BinaryTag dimTag = root.get("Dimension");
                        if (dimTag instanceof net.kyori.adventure.nbt.StringBinaryTag sbt) {
                            worldName = sbt.value();
                        } else if (dimTag instanceof net.kyori.adventure.nbt.IntBinaryTag ibt) {
                            worldName = switch (ibt.value()) {
                                case -1 -> "minecraft:the_nether";
                                case 1 -> "minecraft:the_end";
                                default -> "minecraft:overworld";
                            };
                        }
                    }

                    double x = 0, y = 64, z = 0;
                    ListBinaryTag posTag = root.getList("Pos");
                    if (posTag.size() >= 3) {
                        if (posTag.get(0) instanceof DoubleBinaryTag d0) x = d0.value();
                        if (posTag.get(1) instanceof DoubleBinaryTag d1) y = d1.value();
                        if (posTag.get(2) instanceof DoubleBinaryTag d2) z = d2.value();
                    }

                    float yaw = 0, pitch = 0;
                    ListBinaryTag rotTag = root.getList("Rotation");
                    if (rotTag.size() >= 2) {
                        if (rotTag.get(0) instanceof FloatBinaryTag f0) yaw = f0.value();
                        else if (rotTag.get(0) instanceof DoubleBinaryTag d0) yaw = (float) d0.value();
                        if (rotTag.get(1) instanceof FloatBinaryTag f1) pitch = f1.value();
                        else if (rotTag.get(1) instanceof DoubleBinaryTag d1) pitch = (float) d1.value();
                    }

                    return new ResolvedLocation(worldName, x, y, z, yaw, pitch, true);
                } catch (Exception ignored) {}
            }
        }

        return new ResolvedLocation(defaultWorld, 0, 64, 0, 0, 0, false);
    }

    public List<JsonObject> getRecentOfflineRoster() {
        List<JsonObject> roster = new ArrayList<>();
        java.util.Set<String> seenUuids = new java.util.HashSet<>();

        File[] files = snapshotsDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try (FileReader reader = new FileReader(f)) {
                    JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                    if (obj.has("uuid")) {
                        seenUuids.add(obj.get("uuid").getAsString());
                    }
                    roster.add(obj);
                } catch (Exception ignored) {}
            }
        }

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op != null && op.getUniqueId() != null && !seenUuids.contains(op.getUniqueId().toString())) {
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", op.getUniqueId().toString());
                obj.addProperty("name", op.getName() != null ? op.getName() : "Unknown");
                obj.addProperty("lastSeen", op.getLastSeen());
                
                ResolvedLocation loc = resolveLocation(op.getUniqueId(), op);
                if (loc.found()) {
                    obj.addProperty("world", loc.world());
                    obj.addProperty("x", Math.round(loc.x() * 10.0) / 10.0);
                    obj.addProperty("y", Math.round(loc.y() * 10.0) / 10.0);
                    obj.addProperty("z", Math.round(loc.z() * 10.0) / 10.0);
                    obj.addProperty("yaw", Math.round(loc.yaw() * 10.0f) / 10.0f);
                }

                roster.add(obj);
                seenUuids.add(op.getUniqueId().toString());
            }
        }
        return roster;
    }
}
