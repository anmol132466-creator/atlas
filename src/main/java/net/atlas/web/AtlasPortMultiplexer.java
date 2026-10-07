// src/main/java/net/atlas/web/AtlasPortMultiplexer.java
package net.atlas.web;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * High-performance TCP port multiplexer using Project Loom Virtual Threads.
 * Enables serving both HTTP dashboard traffic and WebSocket telemetry/voice connections
 * over a single, unified TCP port (critical for hosting environments with strict port limits).
 */
public final class AtlasPortMultiplexer {

    private final Plugin plugin;
    private final String bindHost;
    private final int publicPort;
    private final int internalHttpPort;
    private final int internalWsPort;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AtlasPortMultiplexer(Plugin plugin, String bindHost, int publicPort, int internalHttpPort, int internalWsPort) {
        this.plugin = plugin;
        this.bindHost = bindHost;
        this.publicPort = publicPort;
        this.internalHttpPort = internalHttpPort;
        this.internalWsPort = internalWsPort;
    }

    public void start() throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(bindHost, publicPort));
        this.running.set(true);

        Thread.ofVirtual().name("AtlasPortMultiplexer-Acceptor").start(this::acceptLoop);

        plugin.getLogger().info("A.T.L.A.S. Single-Port Multiplexer bound to " + bindHost + ":" + publicPort + 
                " [HTTP -> 127.0.0.1:" + internalHttpPort + ", WS -> 127.0.0.1:" + internalWsPort + "]");
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                Thread.ofVirtual().name("AtlasMux-Client-" + clientSocket.getRemoteSocketAddress()).start(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
                plugin.getLogger().log(Level.WARNING, "Error accepting connection in AtlasPortMultiplexer", e);
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            clientSocket.setTcpNoDelay(true);
            clientSocket.setSoTimeout(10000); // 10s initial handshake read timeout

            InputStream in = clientSocket.getInputStream();
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            int read;

            // Read until double CRLF (end of HTTP header block) or buffer capacity reached
            while (totalRead < buffer.length && (read = in.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
                totalRead += read;
                if (hasDoubleCrlf(buffer, totalRead)) {
                    break;
                }
            }

            if (totalRead <= 0) {
                clientSocket.close();
                return;
            }

            // Remove timeout for active streaming connections
            clientSocket.setSoTimeout(0);

            String headerText = new String(buffer, 0, totalRead, StandardCharsets.ISO_8859_1);
            String lowerHeader = headerText.toLowerCase(Locale.ROOT);
            boolean isWebSocket = lowerHeader.contains("upgrade: websocket")
                    || lowerHeader.contains("upgrade:websocket")
                    || lowerHeader.startsWith("get /ws")
                    || lowerHeader.contains("/ws?");

            int targetPort = isWebSocket ? internalWsPort : internalHttpPort;

            Socket targetSocket = new Socket("127.0.0.1", targetPort);
            targetSocket.setTcpNoDelay(true);

            InputStream targetIn = targetSocket.getInputStream();
            OutputStream targetOut = targetSocket.getOutputStream();

            // Forward the original, pristine request bytes to target service
            targetOut.write(buffer, 0, totalRead);
            targetOut.flush();

            OutputStream clientOut = clientSocket.getOutputStream();

            // Pipe streams bidirectionally using Java 21 Virtual Threads
            Thread.ofVirtual().name("AtlasMux-ClientToTarget").start(() -> {
                try {
                    in.transferTo(targetOut);
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(clientSocket, targetSocket);
                }
            });

            Thread.ofVirtual().name("AtlasMux-TargetToClient").start(() -> {
                try {
                    targetIn.transferTo(clientOut);
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(clientSocket, targetSocket);
                }
            });

        } catch (Exception e) {
            closeQuietly(clientSocket);
        }
    }

    private boolean hasDoubleCrlf(byte[] buf, int len) {
        for (int i = 0; i <= len - 4; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return true;
            }
        }
        for (int i = 0; i <= len - 2; i++) {
            if (buf[i] == '\n' && buf[i + 1] == '\n') {
                return true;
            }
        }
        return false;
    }

    private void closeQuietly(Socket... sockets) {
        for (Socket s : sockets) {
            if (s != null && !s.isClosed()) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }

    public void stop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }
}
