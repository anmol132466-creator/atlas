// src/main/java/net/atlas/web/TileHttpHandler.java
package net.atlas.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.atlas.map.TileCoordinate;
import net.atlas.map.render.TileRenderer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TileHttpHandler implements HttpHandler {

    private static final Pattern TILE_PATTERN = Pattern.compile("^/tiles/([^/]+)/(?:(-?\\d+)/)?(-?\\d+)_(-?\\d+)\\.png$");
    private final TileRenderer tileRenderer;

    public TileHttpHandler(TileRenderer tileRenderer) {
        this.tileRenderer = tileRenderer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        Matcher matcher = TILE_PATTERN.matcher(path);
        if (!matcher.matches()) {
            sendResponse(exchange, 400, "text/plain", "Invalid tile coordinate URL format");
            return;
        }

        String rawDim = matcher.group(1);
        String dimension;
        if (rawDim.startsWith("minecraft_")) {
            dimension = "minecraft:" + rawDim.substring("minecraft_".length());
        } else {
            dimension = rawDim;
        }

        String zoomStr = matcher.group(2);
        int zoom = (zoomStr != null) ? Integer.parseInt(zoomStr) : 0;
        int tileX = Integer.parseInt(matcher.group(3));
        int tileZ = Integer.parseInt(matcher.group(4));

        TileCoordinate coord = new TileCoordinate(dimension, zoom, tileX, tileZ);
        byte[] pngData;
        try {
            pngData = tileRenderer.getOrRenderTile(coord);
        } catch (Throwable t) {
            pngData = tileRenderer.getEmptyTile();
        }

        if (pngData == null || pngData.length == 0) {
            pngData = tileRenderer.getEmptyTile();
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300, stale-while-revalidate=60");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        long length = (pngData != null && pngData.length > 0) ? pngData.length : -1;
        exchange.sendResponseHeaders(200, length);
        if (pngData != null && pngData.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(pngData);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String message) throws IOException {
        byte[] bytes = (message != null) ? message.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().set("Content-Type", contentType);
        long length = (bytes.length > 0) ? bytes.length : -1;
        exchange.sendResponseHeaders(code, length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }
}
