// src/main/java/net/atlas/telemetry/LiveInspectorService.java
package net.atlas.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LiveInspectorService {

    private final Plugin plugin;

    public LiveInspectorService(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<JsonObject> inspectPlayerAsync(UUID uuid) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                future.complete(null);
                return;
            }

            try {
                JsonObject data = new JsonObject();
                data.addProperty("type", "PLAYER_INSPECT_DATA");
                data.addProperty("uuid", player.getUniqueId().toString());
                data.addProperty("name", player.getName());
                data.addProperty("ping", player.getPing());
                data.addProperty("gamemode", player.getGameMode().name());
                data.addProperty("health", Math.round(player.getHealth() * 10.0) / 10.0);
                data.addProperty("foodLevel", player.getFoodLevel());
                data.addProperty("saturation", Math.round(player.getSaturation() * 10.0f) / 10.0f);
                data.addProperty("airSupply", player.getRemainingAir());
                data.addProperty("dimension", TelemetryEngine.canonicalizeDimension(player.getWorld()));
                data.addProperty("world", TelemetryEngine.canonicalizeDimension(player.getWorld()));
                data.addProperty("x", Math.round(player.getLocation().getX() * 100.0) / 100.0);
                data.addProperty("y", Math.round(player.getLocation().getY() * 100.0) / 100.0);
                data.addProperty("z", Math.round(player.getLocation().getZ() * 100.0) / 100.0);
                data.addProperty("yaw", Math.round(player.getLocation().getYaw() * 10.0f) / 10.0f);

                // Potion effects
                JsonArray effectsArray = new JsonArray();
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    JsonObject eff = new JsonObject();
                    eff.addProperty("name", effect.getType().getKey().getKey());
                    eff.addProperty("amplifier", effect.getAmplifier());
                    eff.addProperty("durationSeconds", effect.getDuration() / 20);
                    effectsArray.add(eff);
                }
                data.add("activeEffects", effectsArray);

                // Inventory & Equipment
                PlayerInventory inv = player.getInventory();
                JsonObject equipment = new JsonObject();
                equipment.add("helmet", serializeItem(inv.getHelmet()));
                equipment.add("chestplate", serializeItem(inv.getChestplate()));
                equipment.add("leggings", serializeItem(inv.getLeggings()));
                equipment.add("boots", serializeItem(inv.getBoots()));
                equipment.add("mainHand", serializeItem(inv.getItemInMainHand()));
                equipment.add("offHand", serializeItem(inv.getItemInOffHand()));
                data.add("equipment", equipment);

                JsonArray inventoryArray = new JsonArray();
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack item = inv.getItem(slot);
                    JsonObject itemObj = serializeItem(item);
                    itemObj.addProperty("slot", slot);
                    inventoryArray.add(itemObj);
                }
                data.add("inventory", inventoryArray);

                future.complete(data);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public static JsonObject serializeItem(ItemStack item) {
        JsonObject obj = new JsonObject();
        if (item == null || item.getType().isAir()) {
            obj.addProperty("material", "AIR");
            obj.addProperty("count", 0);
            return obj;
        }

        obj.addProperty("material", item.getType().name());
        obj.addProperty("count", item.getAmount());

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                obj.addProperty("customName", PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
            }

            if (meta instanceof Damageable dmg) {
                obj.addProperty("durability", item.getType().getMaxDurability() - dmg.getDamage());
                obj.addProperty("maxDurability", (int) item.getType().getMaxDurability());
            } else {
                obj.addProperty("durability", 0);
                obj.addProperty("maxDurability", 0);
            }

            // Enchantments
            JsonObject enchObj = new JsonObject();
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                enchObj.addProperty(entry.getKey().getKey().getKey().toUpperCase(), entry.getValue());
            }
            obj.add("enchantments", enchObj);

            // Lore
            if (meta.hasLore() && meta.lore() != null) {
                JsonArray loreArray = new JsonArray();
                for (net.kyori.adventure.text.Component line : meta.lore()) {
                    loreArray.add(PlainTextComponentSerializer.plainText().serialize(line));
                }
                obj.add("lore", loreArray);
            }
        }

        return obj;
    }
}
