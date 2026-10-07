// src/main/java/net/atlas/telemetry/listeners/TelemetryMoveListener.java
package net.atlas.telemetry.listeners;

import net.atlas.telemetry.TelemetryEngine;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class TelemetryMoveListener implements Listener {

    private final TelemetryEngine engine;

    public TelemetryMoveListener(TelemetryEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        // Fast euclidean distance check to prevent spamming on pure head pitch twitching
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        float dyaw = Math.abs(to.getYaw() - from.getYaw());

        if ((dx * dx + dy * dy + dz * dz) > 0.0004 || dyaw > 1.0f) {
            engine.updatePlayer(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        engine.updatePlayer(event.getPlayer());
    }
}
