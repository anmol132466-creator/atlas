// src/main/java/net/atlas/web/AssetHttpHandler.java
package net.atlas.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;

public final class AssetHttpHandler implements HttpHandler {

    private final Plugin plugin;
    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html; charset=UTF-8",
            "css", "text/css; charset=UTF-8",
            "js", "application/javascript; charset=UTF-8",
            "png", "image/png",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "wasm", "application/wasm",
            "json", "application/json; charset=UTF-8"
    );

    public AssetHttpHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        String resourcePath = "web" + (path.startsWith("/") ? path : "/" + path);
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                sendNotFound(exchange);
                return;
            }

            byte[] data = in.readAllBytes();
            String extension = getFileExtension(path);
            String contentType = MIME_TYPES.getOrDefault(extension, "application/octet-stream");

            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (extension.equals("html")) {
                int httpPort = plugin.getConfig().getInt("web.http-port", 27994);
                boolean singlePort = (plugin instanceof net.atlas.AtlasPlugin ap) ? ap.isSinglePortMode() : (httpPort == plugin.getConfig().getInt("web.websocket-port", 27994));
                int wsPort = singlePort ? httpPort : plugin.getConfig().getInt("web.websocket-port", 27994);
                String sessionId = (String) exchange.getAttribute("atlas_session_id");
                if (sessionId == null) {
                    String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                    if (cookieHeader != null) {
                        for (String part : cookieHeader.split(";")) {
                            String[] kv = part.trim().split("=", 2);
                            if (kv.length == 2 && kv[0].equals("atlas_session")) {
                                sessionId = kv[1];
                                break;
                            }
                        }
                    }
                }
                String sessionScript = sessionId != null ? "window.ATLAS_SESSION_ID='" + sessionId + "';" : "";
                String configScript = "window.ATLAS_HTTP_PORT=" + httpPort + ";window.ATLAS_WS_PORT=" + wsPort + ";window.ATLAS_SINGLE_PORT=" + singlePort + ";";
                String html = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                html = html.replace("<head>", "<head><script>" + configScript + sessionScript + "</script>");
                data = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private void sendNotFound(HttpExchange exchange) throws IOException {
        byte[] msg = "404 Not Found".getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(404, msg.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg);
        }
    }

    private String getFileExtension(String path) {
        int dot = path.lastIndexOf('.');
        return (dot != -1 && dot < path.length() - 1) ? path.substring(dot + 1).toLowerCase() : "";
    }
}
