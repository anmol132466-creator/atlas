// src/main/java/net/atlas/moderation/command/AtlasCommand.java
package net.atlas.moderation.command;

import net.atlas.AtlasPlugin;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;
import net.atlas.map.cache.TileDiskCache;
import net.atlas.map.cache.TileMemoryCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AtlasCommand extends Command implements TabExecutor {

    private final AtlasPlugin plugin;
    private final TokenManager tokenManager;
    private final SessionRegistry sessionRegistry;
    private final TileMemoryCache memoryCache;
    private final TileDiskCache diskCache;

    public AtlasCommand(AtlasPlugin plugin, TokenManager tokenManager, SessionRegistry sessionRegistry,
                        TileMemoryCache memoryCache, TileDiskCache diskCache) {
        super("atlas", "Master command for ATLAS surveillance administration", "/atlas <link|unlink|reload|status>", List.of("at"));
        this.plugin = plugin;
        this.tokenManager = tokenManager;
        this.sessionRegistry = sessionRegistry;
        this.memoryCache = memoryCache;
        this.diskCache = diskCache;
        setPermission("atlas.admin");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        return handleCommand(sender, args);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return handleCommand(sender, args);
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return handleTabComplete(args);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return handleTabComplete(args);
    }

    private boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("atlas.admin")) {
            sender.sendMessage(Component.text("You lack permission to use A.T.L.A.S. commands.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(Component.text("=== A.T.L.A.S. Administration Commands ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            sender.sendMessage(Component.text("/atlas link ", NamedTextColor.YELLOW).append(Component.text("- Displays persistent web dashboard access link", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/atlas unlink ", NamedTextColor.YELLOW).append(Component.text("- Closes active instance and revokes access links", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/atlas reload ", NamedTextColor.YELLOW).append(Component.text("- Reloads configurations and flushes tile cache", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/atlas status ", NamedTextColor.YELLOW).append(Component.text("- Displays live surveillance daemon status", NamedTextColor.WHITE)));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "link" -> {
                boolean forceNew = args.length > 1 && (args[1].equalsIgnoreCase("new") || args[1].equalsIgnoreCase("reset"));
                String issuer = sender.getName();
                String token = forceNew
                        ? tokenManager.generateNewPersistentLinkToken(issuer)
                        : tokenManager.getOrCreatePersistentLinkToken(issuer);

                String scheme = plugin.getAtlasConfig().web().getHttpScheme();
                String publicUrl = plugin.getAtlasConfig().web().publicUrl().trim();
                int port = plugin.getAtlasConfig().web().httpPort();
                String host = plugin.getPublicIpResolver() != null ? plugin.getPublicIpResolver().resolveHost(sender) : "localhost";

                String url;
                if (!publicUrl.isBlank()) {
                    url = (publicUrl.startsWith("http://") || publicUrl.startsWith("https://"))
                            ? publicUrl
                            : scheme + publicUrl;
                    if (url.endsWith("/")) {
                        url = url.substring(0, url.length() - 1);
                    }
                    url += "/?token=" + token;
                } else {
                    url = scheme + host + ":" + port + "/?token=" + token;
                }

                sender.sendMessage(Component.text("--------------------------------------------------", NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("[A.T.L.A.S.] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("Surveillance Dashboard Access Link", NamedTextColor.YELLOW, TextDecoration.BOLD)));
                sender.sendMessage(Component.text("Authorized For: ", NamedTextColor.GRAY)
                        .append(Component.text(issuer, NamedTextColor.WHITE))
                        .append(Component.text(" (Active until server restart or /atlas unlink)", NamedTextColor.GREEN)));
                sender.sendMessage(Component.text("URL: ", NamedTextColor.GRAY)
                        .append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                .hoverEvent(HoverEvent.showText(Component.text("Click or copy to open A.T.L.A.S. web panel", NamedTextColor.GRAY)))
                                .clickEvent(ClickEvent.openUrl(url))));
                sender.sendMessage(Component.text("Close Instance: ", NamedTextColor.GRAY)
                        .append(Component.text("Run '/atlas unlink' to revoke access and close sessions.", NamedTextColor.DARK_AQUA)));
                if (port == 8080) {
                    sender.sendMessage(Component.text("Note: If using shared game hosting (Pterodactyl), port 8080 is reserved by Wings (HTTPS). Set an allocated port in config.yml.", NamedTextColor.DARK_GRAY));
                }
                sender.sendMessage(Component.text("--------------------------------------------------", NamedTextColor.DARK_GRAY));
                return true;
            }
            case "unlink", "close" -> {
                plugin.closeDashboardInstance();
                sender.sendMessage(Component.text("--------------------------------------------------", NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("[A.T.L.A.S.] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("Surveillance Dashboard Instance Closed", NamedTextColor.RED, TextDecoration.BOLD)));
                sender.sendMessage(Component.text("Active dashboard access links have been revoked and all web sessions disconnected.", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Run '/atlas link' whenever you wish to reopen dashboard access.", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("--------------------------------------------------", NamedTextColor.DARK_GRAY));
                return true;
            }
            case "reload" -> {
                plugin.reloadConfig();
                memoryCache.clear();
                diskCache.clear();
                sender.sendMessage(Component.text("[A.T.L.A.S.] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("Configuration reloaded; tile memory & disk caches purged.", NamedTextColor.GREEN)));
                return true;
            }
            case "status" -> {
                int sessions = sessionRegistry.getActiveSessionCount();
                int wsClients = plugin.getWebSocketServer() != null ? plugin.getWebSocketServer().getConnectedClientsCount() : 0;
                int tilesInMem = memoryCache.size();

                int vcPort = net.atlas.voice.VoicePluginHook.getDetectedVoiceChatPort();
                boolean vcHooked = net.atlas.voice.VoicePluginHook.getVoicechatApi() != null;
                boolean singlePort = plugin.isSinglePortMode();
                int httpPort = plugin.getAtlasConfig().web().httpPort();
                int wsPort = singlePort ? httpPort : plugin.getAtlasConfig().web().websocketPort();

                sender.sendMessage(Component.text("=== A.T.L.A.S. System Metrics ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                sender.sendMessage(Component.text("Active Admin Sessions: ", NamedTextColor.GRAY).append(Component.text(sessions, NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Connected WebSockets:  ", NamedTextColor.GRAY).append(Component.text(wsClients, NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Web Port Architecture: ", NamedTextColor.GRAY).append(Component.text(singlePort ? "Unified Single-Port (HTTP+WS on " + httpPort + ")" : "Split (HTTP: " + httpPort + ", WS: " + wsPort + ")", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("Tiles in Memory Cache: ", NamedTextColor.GRAY).append(Component.text(tilesInMem + " / " + plugin.getAtlasConfig().map().maxMemoryTiles(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Simple Voice Chat:     ", NamedTextColor.GRAY)
                        .append(Component.text(vcHooked ? "HOOKED (UDP Port: " + (vcPort > 0 ? vcPort : "27995") + ")" : "NOT HOOKED", vcHooked ? NamedTextColor.GREEN : NamedTextColor.RED)));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Type /atlas help for commands.", NamedTextColor.RED));
                return true;
            }
        }
    }

    private List<String> handleTabComplete(String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String q = args[0].toLowerCase();
            for (String sub : List.of("link", "unlink", "close", "reload", "status", "help")) {
                if (sub.startsWith(q)) completions.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
            String q = args[1].toLowerCase();
            for (String sub : List.of("new", "reset")) {
                if (sub.startsWith(q)) completions.add(sub);
            }
        }
        return completions;
    }
}
