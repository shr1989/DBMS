package hotel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;

// Hotel Management System — backend entry point.
// Serves the web frontend from /web and the REST API from /api, on one port.
public class HotelServer {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2");

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    private static Path webRoot;

    // =========================
    // MAIN
    // =========================

    public static void main(String[] args) throws Exception {
        Path appRoot = Path.of(System.getProperty("app.root", ".")).toAbsolutePath().normalize();
        webRoot = appRoot.resolve("web");
        if (!Files.isDirectory(webRoot)) {
            System.err.println("Cannot find the web folder at " + webRoot);
            System.exit(1);
        }

        Db.configure(appRoot);
        try (Connection c = Db.open()) {
            System.out.println("DATABASE CONNECTED");
            System.out.println(Db.describe());
        } catch (SQLException e) {
            // Keep serving: the UI reports the outage and recovers when the database returns.
            System.err.println("DATABASE NOT REACHABLE (" + Db.describe() + ")");
            System.err.println(e.getMessage());
            System.err.println("Run ./db/setup.sh, or start PostgreSQL with: brew services start postgresql@17");
        }

        String host = env("HOST", "127.0.0.1");
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        server.createContext("/api/", new Api());
        server.createContext("/", HotelServer::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("BACKEND SERVER RUNNING");
        System.out.println("http://localhost:" + port);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // =========================
    // FRONTEND FILES
    // =========================

    private static void serveStatic(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            Path file = webRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not found");
                return;
            }

            String name = file.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            byte[] data = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type",
                    CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (extension.equals("html")) {
                exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            }
            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] data = text.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }
}
