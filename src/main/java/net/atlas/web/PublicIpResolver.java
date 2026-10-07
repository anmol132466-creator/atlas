// src/main/java/net/atlas/web/PublicIpResolver.java
package net.atlas.web;

import net.atlas.AtlasPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class PublicIpResolver {

    private static final List<String> IP_CHECK_SERVICES = List.of(
            "https://checkip.amazonaws.com",
            "https://api.ipify.org",
            "https://icanhazip.com"
    );

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$"
    );

    private final AtlasPlugin plugin;
    private final AtomicReference<String> cachedPublicIp = new AtomicReference<>(null);

    public PublicIpResolver(AtlasPlugin plugin) {
        this.plugin = plugin;
    }

    public void initAsync() {
        Thread.ofVirtual().name("atlas-ip-detector").start(this::fetchPublicIp);
    }

    public void fetchPublicIp() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {

            for (String serviceUrl : IP_CHECK_SERVICES) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(serviceUrl))
                            .timeout(Duration.ofSeconds(3))
                            .header("User-Agent", "ATLAS-Plugin/1.0")
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        String body = response.body().trim();
                        if (IPV4_PATTERN.matcher(body).matches()) {
                            cachedPublicIp.set(body);
                            plugin.getLogger().info("Auto-detected server public IP: " + body);
                            int port = plugin.getAtlasConfig().web().httpPort();
                            String scheme = plugin.getAtlasConfig().web().getHttpScheme();
                            String masterToken = plugin.getTokenManager().getMasterConsoleToken();
                            plugin.getLogger().info("Remote Web Dashboard Access URL (Paste in browser on your PC):");
                            plugin.getLogger().info("  " + scheme + body + ":" + port + "/?token=" + masterToken);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // Try next fallback service
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not resolve public IP: " + e.getMessage());
        }
    }

    public String resolveHost(CommandSender sender) {
        // 1. Configured public-url override has highest priority
        String publicUrl = plugin.getAtlasConfig().web().publicUrl().trim();
        if (!publicUrl.isBlank()) {
            return publicUrl;
        }

        // 2. If executed by an in-game player, detect how the player connected from their computer
        if (sender instanceof Player player) {
            try {
                // If player is on the server host PC (loopback), use localhost
                InetSocketAddress remoteAddr = player.getAddress();
                if (remoteAddr != null && remoteAddr.getAddress() != null && remoteAddr.getAddress().isLoopbackAddress()) {
                    return "localhost";
                }

                InetSocketAddress vHost = player.getVirtualHost();
                if (vHost != null) {
                    String hostName = vHost.getHostString();
                    if (hostName != null && !hostName.isBlank()) {
                        if (hostName.equals("127.0.0.1") || hostName.equalsIgnoreCase("localhost")) {
                            return "localhost";
                        }
                        return hostName;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Auto-detected public IP from AWS / ipify / icanhazip (for remote server / VPS access)
        String detected = cachedPublicIp.get();
        if (detected != null && !detected.isBlank()) {
            return detected;
        }

        // 4. Bukkit.getIp() if bound to a non-wildcard IP
        String bukkitIp = Bukkit.getIp();
        if (bukkitIp != null && !bukkitIp.isBlank() && !bukkitIp.equals("0.0.0.0") && !bukkitIp.equals("127.0.0.1")) {
            return bukkitIp;
        }

        // 6. Safe RFC 6761 loopback fallback
        return "localhost";
    }

    public String getCachedPublicIp() {
        return cachedPublicIp.get();
    }
}
