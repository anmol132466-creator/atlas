// src/main/java/net/atlas/telemetry/listeners/TelemetryLifecycleListener.java
package net.atlas.telemetry.listeners;

import net.atlas.offline.OfflinePlayerService;
import net.atlas.telemetry.LiveInspectorService;
import net.atlas.telemetry.TelemetryEngine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class TelemetryLifecycleListener implements Listener {

    private final TelemetryEngine telemetryEngine;
    private final OfflinePlayerService offlinePlayerService;
    private final LiveInspectorService inspectorService;

    public TelemetryLifecycleListener(TelemetryEngine telemetryEngine, OfflinePlayerService offlinePlayerService, LiveInspectorService inspectorService) {
        this.telemetryEngine = telemetryEngine;
        this.offlinePlayerService = offlinePlayerService;
        this.inspectorService = inspectorService;
    }

    public LiveInspectorService getInspectorService() {
        return inspectorService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        telemetryEngine.updatePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        offlinePlayerService.saveSnapshotAsync(player);
        telemetryEngine.removePlayer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            telemetryEngine.updatePlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        telemetryEngine.updatePlayer(event.getPlayer());
    }
}
