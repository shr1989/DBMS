package hotel;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// Database access. Connection settings come from environment variables first,
// then config/db.properties, then local defaults — never from frontend code.
final class Db {

    private static String url;
    private static final Properties connectionProps = new Properties();
    private static String description;

    private Db() {
    }

    static void configure(Path appRoot) throws IOException {
        Properties file = new Properties();
        Path configFile = appRoot.resolve("config").resolve("db.properties");
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                file.load(in);
            }
        }

        String host = setting(file, "DB_HOST", "localhost");
        String port = setting(file, "DB_PORT", "5432");
        String name = setting(file, "DB_NAME", "hotel_management");
        String user = setting(file, "DB_USER", System.getProperty("user.name"));
        String password = setting(file, "DB_PASSWORD", "");

        url = setting(file, "DB_URL", "jdbc:postgresql://" + host + ":" + port + "/" + name);
        connectionProps.setProperty("user", user);
        if (!password.isEmpty()) {
            connectionProps.setProperty("password", password);
        }
        connectionProps.setProperty("connectTimeout", "5");
        connectionProps.setProperty("socketTimeout", "20");
        connectionProps.setProperty("ApplicationName", "hotel-management");
        description = url + " as " + user;
    }

    private static String setting(Properties file, String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = file.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // Safe to log: contains no password.
    static String describe() {
        return description;
    }

    static Connection open() throws SQLException {
        return DriverManager.getConnection(url, connectionProps);
    }

    // =========================
    // QUERY HELPERS
    // =========================

    static List<Map<String, Object>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(md.getColumnLabel(i).toLowerCase(), toJsonValue(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    static Map<String, Object> queryOne(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (p instanceof BigDecimal n) {
                ps.setBigDecimal(i + 1, n);
            } else if (p instanceof LocalDate d) {
                ps.setObject(i + 1, d);
            } else if (p instanceof String s) {
                ps.setString(i + 1, s);
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    private static Object toJsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        // DATE columns arrive as java.sql.Date, whose toString() is already YYYY-MM-DD
        return value.toString();
    }
}
