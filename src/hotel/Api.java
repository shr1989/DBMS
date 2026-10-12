package hotel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import hotel.Schema.Entity;
import hotel.Schema.Field;
import hotel.Schema.Filter;
import hotel.Schema.Kind;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

// The REST API. Every database operation in the application goes through here.
final class Api implements HttpHandler {

    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal MONEY_LIMIT = new BigDecimal("100000000");
    private static final long IDEMPOTENCY_TTL_MS = 10 * 60 * 1000;

    private record Response(int status, Object body) {
    }

    private record Remembered(long at, CompletableFuture<Response> result) {
    }

    // Create requests carry an Idempotency-Key, so a double click or a retried
    // request returns the first result instead of inserting a second row.
    private final Map<String, Remembered> idempotent = new ConcurrentHashMap<>();

    // =========================
    // ENTRY POINT
    // =========================

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        Response response;
        try {
            response = route(exchange);
        } catch (ApiException e) {
            response = new Response(e.status, e.toBody());
        } catch (SQLException e) {
            ApiException translated = translate(e, null, "read");
            response = new Response(translated.status, translated.toBody());
        } catch (Exception e) {
            e.printStackTrace();
            ApiException unexpected = new ApiException(500, "server_error",
                    "Something went wrong on the server. Please try again.");
            response = new Response(500, unexpected.toBody());
        }
        try {
            send(exchange, response);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + " -> " + response.status() + " (" + ms + " ms)");
            exchange.close();
        }
    }

    private Response route(HttpExchange ex) throws SQLException, IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        List<String> parts = new ArrayList<>();
        for (String p : path.substring("/api".length()).split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            throw ApiException.notFound("That address");
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String head = parts.get(0);

        if (parts.size() == 1 && !Schema.ENTITIES.containsKey(head)) {
            requireMethod(method, "GET");
            switch (head) {
                case "health":    return health();
                case "meta":      return ok(meta());
                case "dashboard": return ok(Reports.dashboard());
                case "reports":   return ok(Reports.insights());
                default:          throw ApiException.notFound("That address");
            }
        }

        Entity entity = Schema.ENTITIES.get(head);
        if (entity == null) {
            throw ApiException.notFound("That address");
        }

        if (parts.size() == 1) {
            if (method.equals("GET")) {
                return list(entity, query);
            }
            requireMethod(method, "POST");
            return createOnce(entity, ex);
        }

        String second = parts.get(1);
        if (parts.size() == 2 && second.equals("options")) {
            requireMethod(method, "GET");
            return options(entity);
        }
        if (parts.size() == 3 && second.equals("distinct")) {
            requireMethod(method, "GET");
            return distinct(entity, parts.get(2));
        }

        int id = parseId(second, entity);
        if (parts.size() == 3 && parts.get(2).equals("bill") && entity.key.equals("bookings")) {
            requireMethod(method, "GET");
            return ok(Reports.bill(id));
        }
        if (parts.size() != 2) {
            throw ApiException.notFound("That address");
        }
        switch (method) {
            case "GET":    return ok(read(entity, id));
            case "PUT":    return ok(update(entity, id, readBody(ex)));
            case "DELETE": return delete(entity, id);
            default:       throw new ApiException(405, "method_not_allowed", "That action is not supported here.");
        }
    }

    // =========================
    // HEALTH + META
    // =========================

    private Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        try (Connection c = Db.open()) {
            Map<String, Object> row = Db.queryOne(c, "SELECT current_database() AS database, version() AS version");
            body.put("status", "ok");
            body.put("database", row.get("database"));
            body.put("server", String.valueOf(row.get("version")).split(" on ")[0]);
            return ok(body);
        } catch (SQLException e) {
            System.err.println("Health check failed: " + e.getMessage());
            return new Response(503, ApiException.unavailable().toBody());
        }
    }

    private Map<String, Object> meta() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entities", Schema.ENTITIES.values().stream().map(Entity::toMeta).toList());
        return body;
    }

    // =========================
    // LIST (search, filter, sort, paginate)
    // =========================

    private Response list(Entity e, Map<String, String> q) throws SQLException {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();

        String search = q.getOrDefault("search", "").strip();
        if (search.length() > 100) {
            throw ApiException.badRequest("Search text is too long.");
        }
        if (!search.isEmpty() && !e.search.isEmpty()) {
            String like = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            List<String> clauses = new ArrayList<>();
            for (String column : e.search) {
                clauses.add("CAST(q." + column + " AS TEXT) ILIKE ? ESCAPE '\\'");
                params.add(like);
            }
            where.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }

        for (Filter filter : e.filters) {
            String raw = q.get(filter.name());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            where.append(" AND q.").append(filter.name()).append(" = ?");
            if (filter.kind().equals("ref")) {
                try {
                    params.add(Integer.parseInt(raw.strip()));
                } catch (NumberFormatException nfe) {
                    throw ApiException.badRequest("Invalid value for the " + filter.label() + " filter.");
                }
            } else {
                params.add(raw.strip());
            }
        }

        String sort = q.getOrDefault("sort", e.defaultSort);
        if (!e.sortable().contains(sort)) {
            throw ApiException.badRequest("Cannot sort by '" + sort + "'.");
        }
        String dir = q.getOrDefault("dir", e.defaultDir).equalsIgnoreCase("desc") ? "DESC" : "ASC";
        int pageSize = clamp(parseInt(q.get("pageSize"), 10, "pageSize"), 1, MAX_PAGE_SIZE);
        int page = Math.max(1, parseInt(q.get("page"), 1, "page"));

        String base = " FROM (" + e.listSql + ") q WHERE TRUE" + where;
        try (Connection c = Db.open()) {
            long total = ((Number) Db.queryOne(c, "SELECT COUNT(*) AS n" + base, params.toArray()).get("n")).longValue();
            int lastPage = (int) Math.max(1, (total + pageSize - 1) / pageSize);
            page = Math.min(page, lastPage);

            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(pageSize);
            pageParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(c,
                    "SELECT q.*" + base + " ORDER BY q." + sort + " " + dir + " NULLS LAST, q." + e.pk + " ASC"
                            + " LIMIT ? OFFSET ?", pageParams.toArray());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("total", total);
            meta.put("page", page);
            meta.put("pageSize", pageSize);
            meta.put("pages", lastPage);
            meta.put("sort", sort);
            meta.put("dir", dir.toLowerCase());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", rows);
            body.put("meta", meta);
            return new Response(200, body);
        }
    }

    private Response options(Entity e) throws SQLException {
        try (Connection c = Db.open()) {
            return ok(Db.query(c, e.optionsSql));
        }
    }

    private Response distinct(Entity e, String column) throws SQLException {
        Filter filter = e.filter(column);
        if (filter == null || !filter.kind().equals("distinct")) {
            throw ApiException.notFound("That filter");
        }
        try (Connection c = Db.open()) {
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> row : Db.query(c, "SELECT DISTINCT " + column + " AS v FROM " + e.table
                    + " WHERE " + column + " IS NOT NULL ORDER BY 1")) {
                values.add(row.get("v"));
            }
            return ok(values);
        }
    }

    // =========================
    // READ ONE
    // =========================

    private Map<String, Object> read(Entity e, int id) throws SQLException {
        try (Connection c = Db.open()) {
            return read(c, e, id);
        }
    }

    private Map<String, Object> read(Connection c, Entity e, int id) throws SQLException {
        Map<String, Object> row = Db.queryOne(c,
                "SELECT q.* FROM (" + e.listSql + ") q WHERE q." + e.pk + " = ?", id);
        if (row == null) {
            throw ApiException.notFound(e.singular + " #" + id);
        }
        return row;
    }

    // =========================
    // CREATE
    // =========================

    private Response createOnce(Entity e, HttpExchange ex) throws SQLException, IOException {
        Map<String, Object> body = readBody(ex);
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 100) {
            return create(e, body);
        }

        long now = System.currentTimeMillis();
        idempotent.values().removeIf(r -> now - r.at() > IDEMPOTENCY_TTL_MS);

        Remembered mine = new Remembered(now, new CompletableFuture<>());
        Remembered earlier = idempotent.putIfAbsent(e.key + ":" + key, mine);
        if (earlier != null) {
            try {
                return earlier.result().get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApiException(500, "server_error", "The request was interrupted. Please try again.");
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof ApiException api) {
                    throw api;
                }
                throw new ApiException(500, "server_error", "Something went wrong on the server. Please try again.");
            }
        }
        try {
            Response response = create(e, body);
            mine.result().complete(response);
            return response;
        } catch (RuntimeException | SQLException failure) {
            // A failed attempt must stay retryable, so it is not remembered.
            idempotent.remove(e.key + ":" + key);
            mine.result().completeExceptionally(failure instanceof ApiException
                    ? failure : translate(failure, e, "create"));
            throw failure;
        }
    }

    private Response create(Entity e, Map<String, Object> body) throws SQLException {
        Map<String, Object> values = validate(e, body);
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                // Primary keys are plain INT columns (no sequence in the schema), so the
                // next number is allocated under a lock that makes concurrent creates queue.
                Db.update(c, "LOCK TABLE " + e.table + " IN SHARE ROW EXCLUSIVE MODE");
                int id = ((Number) Db.queryOne(c,
                        "SELECT COALESCE(MAX(" + e.pk + "), 0) + 1 AS next FROM " + e.table).get("next")).intValue();
                checkRules(c, e, values, id);

                List<String> columns = new ArrayList<>(List.of(e.pk));
                List<Object> params = new ArrayList<>(List.of(id));
                for (Field f : e.fields) {
                    columns.add(f.name);
                    params.add(values.get(f.name));
                }
                Db.update(c, "INSERT INTO " + e.table + " (" + String.join(", ", columns) + ") VALUES ("
                        + String.join(", ", columns.stream().map(x -> "?").toList()) + ")", params.toArray());
                Map<String, Object> row = read(c, e, id);
                c.commit();
                return new Response(201, wrap(row));
            } catch (SQLException sql) {
                rollback(c);
                throw translate(sql, e, "create");
            } catch (RuntimeException rt) {
                rollback(c);
                throw rt;
            }
        }
    }

    // =========================
    // UPDATE
    // =========================

    private Map<String, Object> update(Entity e, int id, Map<String, Object> body) throws SQLException {
        Map<String, Object> values = validate(e, body);
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                if (e.key.equals("bookings")) {
                    Db.update(c, "LOCK TABLE booking IN SHARE ROW EXCLUSIVE MODE");
                }
                checkRules(c, e, values, id);

                List<String> sets = new ArrayList<>();
                List<Object> params = new ArrayList<>();
                for (Field f : e.fields) {
                    sets.add(f.name + " = ?");
                    params.add(values.get(f.name));
                }
                params.add(id);
                int changed = Db.update(c, "UPDATE " + e.table + " SET " + String.join(", ", sets)
                        + " WHERE " + e.pk + " = ?", params.toArray());
                if (changed == 0) {
                    throw ApiException.notFound(e.singular + " #" + id);
                }
                Map<String, Object> row = read(c, e, id);
                c.commit();
                return row;
            } catch (SQLException sql) {
                rollback(c);
                throw translate(sql, e, "update");
            } catch (RuntimeException rt) {
                rollback(c);
                throw rt;
            }
        }
    }

    // =========================
    // DELETE
    // =========================

    private Response delete(Entity e, int id) throws SQLException {
        try (Connection c = Db.open()) {
            int removed = Db.update(c, "DELETE FROM " + e.table + " WHERE " + e.pk + " = ?", id);
            if (removed == 0) {
                throw ApiException.notFound(e.singular + " #" + id);
            }
            return new Response(204, null);
        } catch (SQLException sql) {
            throw translate(sql, e, "delete");
        }
    }

    // =========================
    // VALIDATION
    // =========================

    private Map<String, Object> validate(Entity e, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field f : e.fields) {
            Object raw = body.get(f.name);
            if (raw instanceof String s) {
                raw = s.isBlank() ? null : s.strip();
            }
            if (raw == null && f.fallback != null) {
                raw = f.fallback;
            }
            if (raw == null) {
                if (f.required) {
                    errors.put(f.name, f.label + " is required.");
                }
                values.put(f.name, null);
                continue;
            }
            try {
                values.put(f.name, convert(f, raw));
            } catch (IllegalArgumentException bad) {
                errors.put(f.name, bad.getMessage());
            }
        }

        if (e.key.equals("bookings") && values.get("checkin_date") instanceof LocalDate in
                && values.get("checkout_date") instanceof LocalDate out && !out.isAfter(in)) {
            errors.put("checkout_date", "Check-out must be after check-in.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return values;
    }

    private Object convert(Field f, Object raw) {
        switch (f.kind) {
            case TEXT: {
                if (!(raw instanceof String) && !(raw instanceof BigDecimal)) {
                    throw new IllegalArgumentException(f.label + " must be text.");
                }
                String s = raw instanceof BigDecimal n ? n.toPlainString() : (String) raw;
                if (s.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(f.label + " contains characters that are not allowed.");
                }
                if (f.maxLen > 0 && s.codePointCount(0, s.length()) > f.maxLen) {
                    throw new IllegalArgumentException(f.label + " can be at most " + f.maxLen + " characters.");
                }
                if (f.pattern != null && !f.pattern.matcher(s).matches()) {
                    throw new IllegalArgumentException(f.hint + ".");
                }
                return s;
            }
            case INT:
            case REF: {
                BigDecimal n = number(f, raw);
                if (n.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException(f.label + " must be a whole number.");
                }
                if (n.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    throw new IllegalArgumentException(f.label + " is too large.");
                }
                checkMin(f, n);
                return n.intValueExact();
            }
            case MONEY: {
                BigDecimal n = number(f, raw);
                if (n.stripTrailingZeros().scale() > 2) {
                    throw new IllegalArgumentException(f.label + " can have at most 2 decimal places.");
                }
                checkMin(f, n);
                if (n.compareTo(MONEY_LIMIT) >= 0) {
                    throw new IllegalArgumentException(f.label + " must be below 10,00,00,000.");
                }
                return n.setScale(2);
            }
            case DATE: {
                try {
                    LocalDate d = LocalDate.parse(String.valueOf(raw));
                    if (d.getYear() < 1900 || d.getYear() > 2100) {
                        throw new IllegalArgumentException(f.label + " is outside the supported range.");
                    }
                    return d;
                } catch (DateTimeParseException bad) {
                    throw new IllegalArgumentException(f.label + " must be a valid date.");
                }
            }
            case ENUM: {
                String s = String.valueOf(raw);
                if (!f.options.contains(s)) {
                    throw new IllegalArgumentException(f.label + " must be one of: " + String.join(", ", f.options) + ".");
                }
                return s;
            }
            default:
                throw new IllegalStateException("Unhandled field kind " + f.kind);
        }
    }

    private BigDecimal number(Field f, Object raw) {
        if (raw instanceof BigDecimal n) {
            return n;
        }
        if (raw instanceof String s && s.matches("-?\\d{1,15}(\\.\\d{1,6})?")) {
            return new BigDecimal(s);
        }
        throw new IllegalArgumentException(f.label + " must be a number.");
    }

    private void checkMin(Field f, BigDecimal n) {
        if (f.min == null) {
            return;
        }
        int cmp = n.compareTo(f.min);
        if (f.minExclusive && cmp <= 0) {
            throw new IllegalArgumentException(f.label + " must be greater than " + f.min.toPlainString() + ".");
        }
        if (!f.minExclusive && cmp < 0) {
            throw new IllegalArgumentException(f.label + " cannot be less than " + f.min.toPlainString() + ".");
        }
    }

    // Rules that need the database: referenced rows must exist, a room cannot be
    // double-booked, and an employee cannot report to themselves.
    private void checkRules(Connection c, Entity e, Map<String, Object> values, int id) throws SQLException {
        Map<String, String> errors = new LinkedHashMap<>();
        for (Field f : e.fields) {
            Object value = values.get(f.name);
            if (f.kind != Kind.REF || value == null) {
                continue;
            }
            Entity target = Schema.ENTITIES.get(f.ref);
            if (Db.queryOne(c, "SELECT 1 FROM " + target.table + " WHERE " + target.pk + " = ?", value) == null) {
                errors.put(f.name, "The selected " + target.singular.toLowerCase() + " no longer exists.");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        if (e.key.equals("employees") && Integer.valueOf(id).equals(values.get("manager_id"))) {
            throw ApiException.validation(Map.of("manager_id", "An employee cannot report to themselves."));
        }

        if (e.key.equals("bookings") && !"Cancelled".equals(values.get("booking_status"))) {
            Map<String, Object> clash = Db.queryOne(c, """
                    SELECT b.booking_id, g.guest_name, b.checkin_date, b.checkout_date
                      FROM booking b JOIN guest g ON g.guest_id = b.guest_id
                     WHERE b.room_id = ? AND b.booking_id <> ? AND b.booking_status <> 'Cancelled'
                       AND b.checkin_date < ? AND b.checkout_date > ?
                     ORDER BY b.checkin_date LIMIT 1""",
                    values.get("room_id"), id, values.get("checkout_date"), values.get("checkin_date"));
            if (clash != null) {
                String message = "This room is already booked from " + clash.get("checkin_date") + " to "
                        + clash.get("checkout_date") + " (booking #" + clash.get("booking_id") + ", "
                        + clash.get("guest_name") + ").";
                throw ApiException.conflict(message, Map.of("room_id", message));
            }
        }
    }

    // =========================
    // DATABASE ERRORS -> CLIENT ERRORS
    // =========================

    static ApiException translate(Throwable t, Entity e, String operation) {
        if (t instanceof ApiException api) {
            return api;
        }
        if (!(t instanceof SQLException sql)) {
            t.printStackTrace();
            return new ApiException(500, "server_error", "Something went wrong on the server. Please try again.");
        }
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        ServerErrorMessage detail = sql instanceof PSQLException pg ? pg.getServerErrorMessage() : null;
        String constraint = detail == null || detail.getConstraint() == null ? "" : detail.getConstraint();
        String what = e == null ? "record" : e.singular.toLowerCase();

        switch (state) {
            case "23505": {
                if (e != null && constraint.startsWith(e.table + "_") && constraint.endsWith("_key")) {
                    String column = constraint.substring(e.table.length() + 1, constraint.length() - "_key".length());
                    Field f = e.field(column);
                    if (f != null) {
                        String message = "Another " + what + " already uses this " + f.label.toLowerCase() + ".";
                        return ApiException.conflict(message, Map.of(column, message));
                    }
                }
                return ApiException.conflict("That " + what + " already exists. Please try again.", Map.of());
            }
            case "23503": {
                if (operation.equals("delete")) {
                    Entity child = detail == null ? null : Schema.byTable(detail.getTable());
                    String by = child == null ? "other records" : child.plural.toLowerCase();
                    return ApiException.conflict("This " + what + " cannot be deleted because " + by
                            + " still refer to it. Remove or reassign those first.", Map.of());
                }
                return new ApiException(422, "validation_failed",
                        "A linked record no longer exists. Refresh and choose again.");
            }
            case "23514":
            case "23502":
            case "22001":
            case "22003":
            case "22007":
            case "22008":
                System.err.println("Constraint rejected " + operation + " on " + what + ": " + sql.getMessage());
                return new ApiException(422, "validation_failed",
                        "The database rejected these values" + (constraint.isEmpty() ? "" : " (rule " + constraint + ")")
                                + ". Please review the form.");
            default:
                // 08xxx connection failures, 57Pxx shutdown, 53300 too many connections, 3D000 missing database
                if (state.startsWith("08") || state.startsWith("57P") || state.equals("53300")
                        || state.equals("3D000") || state.equals("28000") || state.equals("28P01")) {
                    System.err.println("Database unavailable: " + sql.getMessage());
                    return ApiException.unavailable();
                }
                sql.printStackTrace();
                return new ApiException(500, "database_error",
                        "The database could not complete this request. Nothing was changed.");
        }
    }

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is being discarded anyway
        }
    }

    // =========================
    // REQUEST / RESPONSE PLUMBING
    // =========================

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "too_large", "The request is too large.");
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException bad) {
            throw ApiException.badRequest("The request body is not valid JSON.");
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw ApiException.badRequest("The request body must be a JSON object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        return body;
    }

    private void send(HttpExchange ex, Response response) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (response.body() == null) {
            ex.sendResponseHeaders(response.status(), -1);
            return;
        }
        byte[] data = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(response.status(), data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static Response ok(Object data) {
        return new Response(200, wrap(data));
    }

    private static Map<String, Object> wrap(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return body;
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ApiException(405, "method_not_allowed", "That action is not supported here.");
        }
    }

    private static int parseId(String text, Entity e) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException bad) {
            throw ApiException.notFound(e.singular + " '" + text + "'");
        }
    }

    private static int parseInt(String text, int fallback, String name) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException bad) {
            throw ApiException.badRequest("'" + name + "' must be a whole number.");
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                try {
                    map.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                } catch (IllegalArgumentException malformed) {
                    throw ApiException.badRequest("The address contains an invalid character sequence.");
                }
            }
        }
        return map;
    }
}
