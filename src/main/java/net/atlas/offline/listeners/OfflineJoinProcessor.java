// src/main/java/net/atlas/offline/listeners/OfflineJoinProcessor.java
package net.atlas.offline.listeners;

import com.google.gson.JsonObject;
import net.atlas.offline.PendingActionLedger;
import net.atlas.offline.model.PendingAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

public final class OfflineJoinProcessor implements Listener {

    private final PendingActionLedger ledger;

    public OfflineJoinProcessor(PendingActionLedger ledger) {
        this.ledger = ledger;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<PendingAction> actions = ledger.getPendingActionsForPlayer(player.getUniqueId());

        if (actions.isEmpty()) {
            return;
        }

        boolean modified = false;

        for (PendingAction action : actions) {
            JsonObject params = action.getParameters();
            switch (action.getActionType().toUpperCase()) {
                case "TELEPORT_SPAWN" -> {
                    player.teleport(player.getWorld().getSpawnLocation());
                    modified = true;
                }
                case "TELEPORT_COORDS" -> {
                    double x = params.has("x") ? params.get("x").getAsDouble() : player.getLocation().getX();
                    double y = params.has("y") ? params.get("y").getAsDouble() : player.getLocation().getY();
                    double z = params.has("z") ? params.get("z").getAsDouble() : player.getLocation().getZ();
                    String worldName = params.has("world") ? params.get("world").getAsString() : player.getWorld().getName();
                    World targetWorld = Bukkit.getWorld(worldName);
                    if (targetWorld == null) targetWorld = player.getWorld();

                    player.teleport(new Location(targetWorld, x, y, z));
                    modified = true;
                }
                case "CLEAR_INVENTORY" -> {
                    player.getInventory().clear();
                    modified = true;
                }
                case "WIPE_ENDER_CHEST" -> {
                    player.getEnderChest().clear();
                    modified = true;
                }
                case "GIVE_ITEM" -> {
                    String matName = params.has("material") ? params.get("material").getAsString() : "DIAMOND";
                    int count = params.has("count") ? params.get("count").getAsInt() : 1;
                    Material mat = Material.matchMaterial(matName);
                    if (mat != null) {
                        player.getInventory().addItem(new ItemStack(mat, count));
                        modified = true;
                    }
                }
                case "COMMAND" -> {
                    String cmd = params.has("command") ? params.get("command").getAsString() : "";
                    if (!cmd.isBlank()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                    }
                }
            }

            ledger.markExecuted(action.getId());
        }

        net.atlas.AtlasPlugin atlas = net.atlas.AtlasPlugin.getInstance();
        if (atlas != null) {
            atlas.broadcastPendingActions();
        }

        if (modified) {
            Component titleText = Component.text("A.T.L.A.S. Notice", NamedTextColor.GOLD, TextDecoration.BOLD);
            Component subText = Component.text("Administrative offline actions applied to your profile", NamedTextColor.YELLOW);
            Title title = Title.title(titleText, subText, Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000)));
            player.showTitle(title);

            player.sendMessage(Component.text("[A.T.L.A.S.] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("Your account was updated while offline by server administration.", NamedTextColor.WHITE)));
        }
    }
}
