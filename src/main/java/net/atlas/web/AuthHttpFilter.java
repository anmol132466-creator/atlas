// src/main/java/net/atlas/web/AuthHttpFilter.java
package net.atlas.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import net.atlas.auth.AdminSession;
import net.atlas.auth.RateLimiter;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class AuthHttpFilter extends Filter {

    private final TokenManager tokenManager;
    private final SessionRegistry sessionRegistry;
    private final RateLimiter rateLimiter;

    public AuthHttpFilter(TokenManager tokenManager, SessionRegistry sessionRegistry, RateLimiter rateLimiter) {
        this.tokenManager = tokenManager;
        this.sessionRegistry = sessionRegistry;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String clientIp = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (clientIp == null || clientIp.trim().isEmpty()) {
            clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        } else if (clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }

        // 1. IP Rate Limiting Check
        if (!rateLimiter.tryAcquire(clientIp)) {
            sendResponse(exchange, 429, "text/plain", "429 Too Many Requests - Rate limit exceeded");
            return;
        }

        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        // Assets and tiles may be fetched by authenticated browser session or direct cookie
        String sessionCookie = extractCookie(exchange, "atlas_session");
        String queryToken = extractQueryParam(uri.getQuery(), "token");

        // Authenticate via token query parameter if present
        if (queryToken != null) {
            AdminSession existingSession = sessionRegistry.getSession(queryToken);
            if (existingSession != null) {
                exchange.setAttribute("atlas_session_id", existingSession.getSessionId());
                exchange.getResponseHeaders().add("Set-Cookie", "atlas_session=" + existingSession.getSessionId() + "; Path=/; SameSite=Lax; HttpOnly");
                chain.doFilter(exchange);
                return;
            }

            String principal = tokenManager.consumeToken(queryToken);
            if (principal == null && tokenManager.validateToken(queryToken)) {
                principal = "MASTER";
            }

            if (principal != null) {
                AdminSession session = sessionRegistry.createSession(principal);
                exchange.setAttribute("atlas_session_id", session.getSessionId());
                exchange.getResponseHeaders().add("Set-Cookie", "atlas_session=" + session.getSessionId() + "; Path=/; SameSite=Lax; HttpOnly");
                chain.doFilter(exchange);
                return;
            }
        }

        // Authenticate via active session cookie
        if (sessionCookie != null && sessionRegistry.isValid(sessionCookie)) {
            chain.doFilter(exchange);
            return;
        }

        // Check Authorization Bearer header
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String bearerToken = authHeader.substring(7).trim();
            if (sessionRegistry.isValid(bearerToken) || tokenManager.validateToken(bearerToken)) {
                chain.doFilter(exchange);
                return;
            }
        }

        // Allow public static assets (CSS, JS, Favicon, Privacy & Terms policies, and Map Tiles) without login barrier
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/assets/")
                || path.startsWith("/tiles/") || path.equals("/favicon.ico")
                || path.equals("/privacy.html") || path.equals("/terms.html")) {
            chain.doFilter(exchange);
            return;
        }

        sendResponse(exchange, 401, "text/html", """
                <!DOCTYPE html>
                <html>
                <head><title>A.T.L.A.S. - Unauthorized</title>
                <style>body{background:#0d1117;color:#c9d1d9;font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;}
                .box{background:#161b22;padding:30px;border-radius:8px;border:1px solid #30363d;text-align:center;box-shadow:0 8px 24px rgba(0,0,0,0.5);}
                h1{color:#f85149;margin:0 0 10px;}code{background:#21262d;padding:4px 8px;border-radius:4px;color:#58a6ff;}</style></head>
                <body><div class="box"><h1>401 - Unauthorized</h1><p>Missing or expired access token.</p>
                <p>Run <code>/atlas link</code> in-game or check server console for the master access link.</p></div></body></html>
                """);
    }

    @Override
    public String description() {
        return "ATLAS Authentication and Rate Limiting Filter";
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String extractCookie(HttpExchange exchange, String cookieName) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            String[] parts = cookieHeader.split(";");
            for (String part : parts) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equalsIgnoreCase(cookieName)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private String extractQueryParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase(name)) {
                return pair[1];
            }
        }
        return null;
    }
}
