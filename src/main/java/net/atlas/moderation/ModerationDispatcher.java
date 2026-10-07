// src/main/java/net/atlas/moderation/ModerationDispatcher.java
package net.atlas.moderation;

import com.google.gson.JsonObject;
import net.atlas.offline.OfflinePlayerService;
import net.atlas.offline.PendingActionLedger;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class ModerationDispatcher {

    private final Plugin plugin;
    private final PendingActionLedger pendingActionLedger;
    private final OfflinePlayerService offlinePlayerService;

    public ModerationDispatcher(Plugin plugin, PendingActionLedger pendingActionLedger, OfflinePlayerService offlinePlayerService) {
        this.plugin = plugin;
        this.pendingActionLedger = pendingActionLedger;
        this.offlinePlayerService = offlinePlayerService;
    }

    public PendingActionLedger getPendingActionLedger() {
        return pendingActionLedger;
    }

    public OfflinePlayerService getOfflinePlayerService() {
        return offlinePlayerService;
    }

    @SuppressWarnings("deprecation")
    public CompletableFuture<JsonObject> executeAction(JsonObject payload, String issuer) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        String action = payload.has("action") ? payload.get("action").getAsString().toUpperCase() : "";
        String targetStr = payload.has("target") ? payload.get("target").getAsString() : "";
        String reason = payload.has("reason") ? payload.get("reason").getAsString() : "Moderation action via A.T.L.A.S.";

        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject ack = new JsonObject();
            ack.addProperty("type", "ACTION_ACK");
            ack.addProperty("action", action);

            try {
                Player player = null;
                UUID targetUuid = null;
                try {
                    targetUuid = UUID.fromString(targetStr);
                    player = Bukkit.getPlayer(targetUuid);
                } catch (IllegalArgumentException e) {
                    player = Bukkit.getPlayerExact(targetStr);
                    if (player != null) {
                        targetUuid = player.getUniqueId();
                    }
                }

                switch (action) {
                    case "KICK" -> {
                        if (player != null && player.isOnline()) {
                            player.kick(Component.text(reason));
                            ack.addProperty("success", true);
                            ack.addProperty("message", "Player " + player.getName() + " was kicked.");
                        } else {
                            ack.addProperty("success", false);
                            ack.addProperty("message", "Target player is not online.");
                        }
                    }
                    case "BAN" -> {
                        int duration = payload.has("durationSeconds") ? payload.get("durationSeconds").getAsInt() : 0;
                        Date expires = duration > 0 ? new Date(System.currentTimeMillis() + (duration * 1000L)) : null;

                        String nameToBan = (player != null) ? player.getName() : targetStr;
                        Bukkit.getBanList(BanList.Type.NAME).addBan(nameToBan, reason, expires, issuer);
                        if (player != null && player.isOnline()) {
                            player.kick(Component.text("Banned: " + reason));
                        }
                        ack.addProperty("success", true);
                        ack.addProperty("message", "Player " + nameToBan + " was banned.");
                    }
                    case "KILL" -> {
                        if (player != null && player.isOnline()) {
                            player.setHealth(0.0);
                            ack.addProperty("success", true);
                            ack.addProperty("message", "Player " + player.getName() + " was eliminated.");
                        } else {
                            ack.addProperty("success", false);
                            ack.addProperty("message", "Target player is not online.");
                        }
                    }
                    case "TELEPORT" -> {
                        if (player != null && player.isOnline() && payload.has("teleportLocation")) {
                            JsonObject locObj = payload.getAsJsonObject("teleportLocation");
                            double x = locObj.get("x").getAsDouble();
                            double y = locObj.get("y").getAsDouble();
                            double z = locObj.get("z").getAsDouble();
                            String worldName = locObj.has("world") ? locObj.get("world").getAsString() : player.getWorld().getName();
                            World targetWorld = Bukkit.getWorld(worldName);
                            if (targetWorld == null) targetWorld = player.getWorld();

                            player.teleport(new Location(targetWorld, x, y, z));
                            ack.addProperty("success", true);
                            ack.addProperty("message", "Teleported " + player.getName() + " to " + x + ", " + y + ", " + z);
                        } else {
                            ack.addProperty("success", false);
                            ack.addProperty("message", "Invalid location parameters or player offline.");
                        }
                    }
                    case "COMMAND" -> {
                        String cmd = payload.has("command") ? payload.get("command").getAsString().trim() : "";
                        if (cmd.startsWith("/")) cmd = cmd.substring(1);
                        boolean autoQueue = !payload.has("queueIfOffline") || payload.get("queueIfOffline").getAsBoolean();

                        if (cmd.isBlank()) {
                            ack.addProperty("success", false);
                            ack.addProperty("message", "Command string cannot be empty.");
                            break;
                        }

                        TargetResolution res = resolveTarget(cmd, targetStr);
                        String targetPlayerName = res.playerName();
                        if ((targetPlayerName == null || targetPlayerName.isBlank()) && payload.has("targetName")) {
                            targetPlayerName = payload.get("targetName").getAsString();
                        }

                        if (res.hasTarget() && res.isOnline()) {
                            // Target player is online: execute immediately with name substitution
                            Player onlineP = res.onlinePlayer();
                            String finalCmd = formatTargetedCommand(cmd, onlineP.getName());
                            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                            ack.addProperty("success", ok);
                            ack.addProperty("executedLive", true);
                            ack.addProperty("target", onlineP.getName());
                            ack.addProperty("message", "Dispatched console command for online player " + onlineP.getName() + ": " + finalCmd);
                        } else if (res.hasTarget() && !res.isOnline() && autoQueue) {
                            // Target player is offline: queue in PendingActionLedger
                            UUID offlineUuid = res.playerUuid();
                            String offlineName = (targetPlayerName != null && !targetPlayerName.isBlank()) ? targetPlayerName : res.playerName();
                            String finalCmd = formatTargetedCommand(cmd, offlineName);

                            String actionId = "act_cmd_" + Long.toHexString(System.currentTimeMillis()) + "_" +
                                    Integer.toHexString(offlineUuid.hashCode() & 0xFFFF);
                            JsonObject params = new JsonObject();
                            params.addProperty("command", finalCmd);
                            params.addProperty("targetName", offlineName);

                            net.atlas.offline.model.PendingAction pending = new net.atlas.offline.model.PendingAction(
                                    actionId,
                                    offlineUuid,
                                    offlineName,
                                    "COMMAND",
                                    params,
                                    issuer,
                                    System.currentTimeMillis(),
                                    net.atlas.offline.model.ActionStatus.PENDING
                            );
                            pendingActionLedger.queueAction(pending);

                            // Broadcast updated ledger to all connected clients immediately
                            net.atlas.AtlasPlugin atlas = net.atlas.AtlasPlugin.getInstance();
                            if (atlas != null) {
                                atlas.broadcastPendingActions();
                            }

                            ack.addProperty("success", true);
                            ack.addProperty("offlineQueued", true);
                            ack.addProperty("actionId", actionId);
                            ack.addProperty("target", offlineName);
                            ack.addProperty("message", "Target " + offlineName + " is offline. Command queued to pending ledger (will auto-execute on login).");
                        } else {
                            // General console command
                            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                            ack.addProperty("success", ok);
                            ack.addProperty("executedLive", true);
                            ack.addProperty("message", "Dispatched console command: " + cmd);
                        }
                    }
                    default -> {
                        ack.addProperty("success", false);
                        ack.addProperty("message", "Unknown moderation action: " + action);
                    }
                }
            } catch (Exception e) {
                ack.addProperty("success", false);
                ack.addProperty("message", "Internal execution error: " + e.getMessage());
            }

            future.complete(ack);
        });

        return future;
    }

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Set<String> NATIVE_CONSOLE_COMMANDS = Set.of(
            "whitelist", "ban", "ban-ip", "pardon", "pardon-ip", "unban", "op", "deop",
            "say", "tellraw", "broadcast", "time", "weather", "difficulty", "defaultgamemode",
            "gamerule", "reload", "stop", "save-all", "save-on", "save-off", "kick", "kickall"
    );

    private record TargetResolution(
            boolean hasTarget,
            boolean isOnline,
            Player onlinePlayer,
            UUID playerUuid,
            String playerName
    ) {}

    private TargetResolution resolveTarget(String cmd, String explicitTarget) {
        // 1. Explicit target provided via payload
        if (explicitTarget != null && !explicitTarget.isBlank()) {
            UUID targetUuid = parseUuidSafe(explicitTarget);
            Player player = (targetUuid != null) ? Bukkit.getPlayer(targetUuid) : Bukkit.getPlayerExact(explicitTarget);
            if (player != null && player.isOnline()) {
                return new TargetResolution(true, true, player, player.getUniqueId(), player.getName());
            }

            // Target is offline
            UUID offUuid = targetUuid;
            String offName = explicitTarget;
            if (offUuid != null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(offUuid);
                if (op != null && op.getName() != null) offName = op.getName();
            } else {
                offUuid = findOfflineUuidByName(explicitTarget);
                if (offUuid == null) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(explicitTarget);
                    offUuid = op.getUniqueId();
                    if (op.getName() != null) offName = op.getName();
                }
            }
            return new TargetResolution(true, false, null, offUuid, offName);
        }

        // 2. Parse from command tokens
        if (cmd == null || cmd.isBlank()) {
            return new TargetResolution(false, false, null, null, null);
        }

        String[] tokens = cmd.trim().split("\\s+");
        if (tokens.length == 0) {
            return new TargetResolution(false, false, null, null, null);
        }

        String verb = tokens[0].toLowerCase();
        if (verb.startsWith("/")) verb = verb.substring(1);

        // Native console commands should not be queued for login; run live on console
        if (NATIVE_CONSOLE_COMMANDS.contains(verb)) {
            return new TargetResolution(false, false, null, null, null);
        }

        // Check if ANY token in the command matches an online player
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            Player onlineP = Bukkit.getPlayerExact(tok);
            if (onlineP != null && onlineP.isOnline()) {
                return new TargetResolution(true, true, onlineP, onlineP.getUniqueId(), onlineP.getName());
            }
        }

        // Check targeted command verbs where candidate argument is at a specific index
        String candidate = null;
        if (tokens.length >= 2 && (
                verb.equals("give") || verb.equals("clear") || verb.equals("kill") ||
                verb.equals("tp") || verb.equals("teleport") || verb.equals("enchant") ||
                verb.equals("spawnpoint") || verb.equals("tag") || verb.equals("attribute") ||
                verb.equals("fly") || verb.equals("heal") || verb.equals("feed") ||
                verb.equals("god") || verb.equals("sudo") || verb.equals("burn") ||
                verb.equals("freeze") || verb.equals("ec") || verb.equals("enderchest") ||
                verb.equals("inv") || verb.equals("invsee") || verb.equals("ci") ||
                verb.equals("msg") || verb.equals("tell") || verb.equals("w") || verb.equals("whisper")
        )) {
            candidate = tokens[1];
        } else if (verb.equals("gamemode")) {
            if (tokens.length >= 3) candidate = tokens[2];
            else if (tokens.length == 2 && !isGamemodeKeyword(tokens[1])) candidate = tokens[1];
        } else if (tokens.length >= 3 && (
                verb.equals("xp") || verb.equals("experience") || verb.equals("effect") ||
                verb.equals("advancement") || verb.equals("eco") || verb.equals("economy") ||
                verb.equals("money") || verb.equals("pay")
        )) {
            candidate = tokens[2];
        } else if (verb.equals("item") && tokens.length >= 4 &&
                "replace".equalsIgnoreCase(tokens[1]) && "entity".equalsIgnoreCase(tokens[2])) {
            candidate = tokens[3];
        } else if (verb.equals("scoreboard") && tokens.length >= 4 &&
                "players".equalsIgnoreCase(tokens[1])) {
            candidate = tokens[3];
        } else if ((verb.equals("lp") || verb.equals("luckperms")) && tokens.length >= 3 &&
                "user".equalsIgnoreCase(tokens[1])) {
            candidate = tokens[2];
        }

        if (candidate != null && isPlayerCandidate(candidate)) {
            UUID offUuid = parseUuidSafe(candidate);
            String offName = candidate;
            if (offUuid == null) {
                offUuid = findOfflineUuidByName(candidate);
                if (offUuid == null) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(candidate);
                    offUuid = op.getUniqueId();
                    if (op.getName() != null) offName = op.getName();
                }
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(offUuid);
                if (op != null && op.getName() != null) offName = op.getName();
            }
            return new TargetResolution(true, false, null, offUuid, offName);
        }

        // Fallback: check if ANY token in the command matches a known offline player in records
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (isPlayerCandidate(tok)) {
                UUID knownUuid = findKnownOfflineUuid(tok);
                if (knownUuid != null) {
                    String knownName = findKnownOfflineName(knownUuid, tok);
                    return new TargetResolution(true, false, null, knownUuid, knownName);
                }
            }
        }

        return new TargetResolution(false, false, null, null, null);
    }

    private boolean isGamemodeKeyword(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.equals("survival") || lower.equals("creative") ||
               lower.equals("adventure") || lower.equals("spectator") ||
               lower.equals("s") || lower.equals("c") || lower.equals("a") || lower.equals("sp") ||
               lower.equals("0") || lower.equals("1") || lower.equals("2") || lower.equals("3");
    }

    private boolean isPlayerCandidate(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.startsWith("@")) return false; // selectors @a, @p, @r, @e, @s
        if (s.startsWith("~") || s.startsWith("^")) return false; // coordinates
        if (parseUuidSafe(s) != null) return true; // valid UUID
        return USERNAME_PATTERN.matcher(s).matches(); // valid player name format
    }

    private UUID findOfflineUuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        if (offlinePlayerService != null) {
            for (JsonObject p : offlinePlayerService.getRecentOfflineRoster()) {
                if (p.has("name") && name.equalsIgnoreCase(p.get("name").getAsString()) && p.has("uuid")) {
                    UUID u = parseUuidSafe(p.get("uuid").getAsString());
                    if (u != null) return u;
                }
            }
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op != null && op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op.getUniqueId();
            }
        }
        return null;
    }

    private UUID findKnownOfflineUuid(String nameOrUuid) {
        UUID u = parseUuidSafe(nameOrUuid);
        if (u != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            if (op != null && (op.hasPlayedBefore() || op.getLastSeen() > 0)) return u;
            return null;
        }
        return findOfflineUuidByName(nameOrUuid);
    }

    private String findKnownOfflineName(UUID uuid, String fallback) {
        if (uuid == null) return fallback;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        if (op != null && op.getName() != null) return op.getName();
        return fallback;
    }

    private UUID parseUuidSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String formatTargetedCommand(String cmd, String playerName) {
        if (cmd == null || cmd.isBlank()) return "";
        if (playerName == null || playerName.isBlank()) return cmd;

        if (cmd.contains("%player%")) {
            return cmd.replace("%player%", playerName);
        }

        String trimmed = cmd.trim();
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) return cmd;

        for (String tok : tokens) {
            if (tok.equalsIgnoreCase(playerName)) {
                return trimmed;
            }
        }

        String verb = tokens[0].toLowerCase();
        if (verb.startsWith("/")) verb = verb.substring(1);

        if (verb.equals("give")) {
            if (tokens.length >= 2) {
                StringBuilder sb = new StringBuilder(tokens[0]).append(" ").append(playerName);
                for (int i = 1; i < tokens.length; i++) {
                    sb.append(" ").append(tokens[i]);
                }
                return sb.toString();
            }
        } else if (verb.equals("gamemode")) {
            if (tokens.length >= 2) {
                return trimmed + " " + playerName;
            }
        } else if (verb.equals("tp") || verb.equals("teleport")) {
            if (tokens.length >= 2) {
                StringBuilder sb = new StringBuilder(tokens[0]).append(" ").append(playerName);
                for (int i = 1; i < tokens.length; i++) {
                    sb.append(" ").append(tokens[i]);
                }
                return sb.toString();
            }
        } else if (verb.equals("effect") && tokens.length >= 3 && tokens[1].equalsIgnoreCase("give")) {
            StringBuilder sb = new StringBuilder(tokens[0]).append(" ").append(tokens[1]).append(" ").append(playerName);
            for (int i = 2; i < tokens.length; i++) {
                sb.append(" ").append(tokens[i]);
            }
            return sb.toString();
        } else if (verb.equals("xp") || verb.equals("experience")) {
            if (tokens.length >= 3 && (tokens[1].equalsIgnoreCase("add") || tokens[1].equalsIgnoreCase("set"))) {
                StringBuilder sb = new StringBuilder(tokens[0]).append(" ").append(tokens[1]).append(" ").append(playerName);
                for (int i = 2; i < tokens.length; i++) {
                    sb.append(" ").append(tokens[i]);
                }
                return sb.toString();
            }
        } else if (verb.equals("kill") || verb.equals("clear") || verb.equals("spawnpoint") ||
                   verb.equals("heal") || verb.equals("feed") || verb.equals("god") ||
                   verb.equals("fly") || verb.equals("burn") || verb.equals("freeze") ||
                   verb.equals("op") || verb.equals("deop") || verb.equals("kick") ||
                   verb.equals("ban") || verb.equals("ci") || verb.equals("inv") ||
                   verb.equals("ec") || verb.equals("enderchest") || verb.equals("sudo")) {
            if (tokens.length == 1) {
                return trimmed + " " + playerName;
            }
        } else if (verb.equals("msg") || verb.equals("tell") || verb.equals("w") || verb.equals("whisper")) {
            StringBuilder sb = new StringBuilder(tokens[0]).append(" ").append(playerName);
            for (int i = 1; i < tokens.length; i++) {
                sb.append(" ").append(tokens[i]);
            }
            return sb.toString();
        }

        return trimmed;
    }
}
