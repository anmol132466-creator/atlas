// src/main/java/net/atlas/web/EmbeddedHttpServer.java
package net.atlas.web;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import net.atlas.auth.RateLimiter;
import net.atlas.auth.SessionRegistry;
import net.atlas.auth.TokenManager;
import net.atlas.config.WebServerConfig;
import net.atlas.map.render.TileRenderer;
import org.bukkit.plugin.Plugin;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class EmbeddedHttpServer {

    private final Plugin plugin;
    private final WebServerConfig config;
    private final TokenManager tokenManager;
    private final SessionRegistry sessionRegistry;
    private final RateLimiter rateLimiter;
    private final TileRenderer tileRenderer;

    private HttpServer server;

    public EmbeddedHttpServer(Plugin plugin, WebServerConfig config, TokenManager tokenManager,
                              SessionRegistry sessionRegistry, RateLimiter rateLimiter, TileRenderer tileRenderer) {
        this.plugin = plugin;
        this.config = config;
        this.tokenManager = tokenManager;
        this.sessionRegistry = sessionRegistry;
        this.rateLimiter = rateLimiter;
        this.tileRenderer = tileRenderer;
    }

    public void start() throws IOException {
        start(config.host(), config.httpPort());
    }

    public void start(String host, int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);

        if (config.isSslEnabled()) {
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            SSLContext sslContext = createSslContext();
            if (sslContext != null) {
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    @Override
                    public void configure(HttpsParameters params) {
                        try {
                            SSLContext ctx = getSSLContext();
                            SSLParameters sslParams = ctx.getDefaultSSLParameters();
                            params.setSSLParameters(sslParams);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Failed to configure HTTPS parameters: " + ex.getMessage());
                        }
                    }
                });
                this.server = httpsServer;
                plugin.getLogger().info("A.T.L.A.S. HTTP Server configured with TLS/SSL encryption.");
            } else {
                plugin.getLogger().warning("Failed to initialize SSLContext. Falling back to unencrypted HTTP.");
                this.server = HttpServer.create(address, 0);
            }
        } else {
            this.server = HttpServer.create(address, 0);
        }

        // Project Loom Virtual Threads Executor
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        AuthHttpFilter authFilter = new AuthHttpFilter(tokenManager, sessionRegistry, rateLimiter);

        // Register static assets handler (web root)
        HttpContext rootContext = server.createContext("/", new AssetHttpHandler(plugin));
        rootContext.getFilters().add(authFilter);

        // Register dynamic map tile renderer handler (public read-only stream for browser image elements)
        server.createContext("/tiles", new TileHttpHandler(tileRenderer));

        server.start();
        plugin.getLogger().info("A.T.L.A.S. Embedded Web Server bound to " + host + ":" + getBoundPort() + " (Virtual Threads)");
    }

    public int getBoundPort() {
        return (server != null && server.getAddress() != null) ? server.getAddress().getPort() : -1;
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            plugin.getLogger().info("A.T.L.A.S. Embedded Web Server stopped.");
        }
    }

    private SSLContext createSslContext() {
        File keystoreFile = new File(config.keystorePath());
        char[] password = config.keystorePassword().toCharArray();

        try {
            if (!keystoreFile.exists() && config.isSelfSignedSsl()) {
                generateSelfSignedKeystore(keystoreFile, password);
            }

            if (!keystoreFile.exists()) {
                plugin.getLogger().severe("Keystore file not found at: " + keystoreFile.getAbsolutePath());
                return null;
            }

            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                ks.load(fis, password);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            return sslContext;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize SSL Context for ATLAS", e);
            return null;
        }
    }

    private void generateSelfSignedKeystore(File keystoreFile, char[] password) {
        plugin.getLogger().info("Generating self-signed SSL certificate for Secure Context Web Audio API...");
        File parent = keystoreFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try {
            // Invokes standard JDK keytool to generate self-signed JKS
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "atlas",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "365",
                    "-keystore", keystoreFile.getAbsolutePath(),
                    "-storepass", new String(password),
                    "-keypass", new String(password),
                    "-dname", "CN=AtlasServer, OU=Admin, O=Minecraft, L=Server, ST=Global, C=US"
            );
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                plugin.getLogger().info("Self-signed keystore generated successfully at: " + keystoreFile.getAbsolutePath());
            } else {
                plugin.getLogger().warning("keytool process exited with code " + exitCode + ". Automatic SSL generation failed.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Unable to execute keytool for self-signed certificate generation", e);
        }
    }
}
