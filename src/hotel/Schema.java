package hotel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// One description of every table the application manages. The backend validates
// and builds SQL from it, and the frontend builds its forms and tables from the
// same description (served at /api/meta), so the two can never drift apart.
final class Schema {

    enum Kind { TEXT, INT, MONEY, DATE, ENUM, REF }

    // =========================
    // FIELD (an editable column)
    // =========================

    static final class Field {
        final String name;
        final String label;
        final Kind kind;
        boolean required;
        int maxLen;
        BigDecimal min;
        boolean minExclusive;
        List<String> options = List.of();
        String ref;
        Pattern pattern;
        String hint;
        String placeholder;
        String fallback;
        boolean today;
        boolean wide;

        Field(String name, String label, Kind kind) {
            this.name = name;
            this.label = label;
            this.kind = kind;
        }

        Field required() {
            this.required = true;
            return this;
        }

        Field max(int maxLen) {
            this.maxLen = maxLen;
            return this;
        }

        Field atLeast(String min) {
            this.min = new BigDecimal(min);
            return this;
        }

        Field moreThan(String min) {
            this.min = new BigDecimal(min);
            this.minExclusive = true;
            return this;
        }

        Field pattern(String regex, String hint) {
            this.pattern = Pattern.compile(regex);
            this.hint = hint;
            return this;
        }

        Field placeholder(String placeholder) {
            this.placeholder = placeholder;
            return this;
        }

        // Value used when the field is left empty (mirrors the column DEFAULT).
        Field fallback(String fallback) {
            this.fallback = fallback;
            return this;
        }

        Field today() {
            this.today = true;
            return this;
        }

        Field wide() {
            this.wide = true;
            return this;
        }

        Map<String, Object> toMeta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            m.put("kind", kind.name().toLowerCase());
            m.put("required", required);
            if (maxLen > 0) m.put("maxLength", maxLen);
            if (min != null) {
                m.put("min", min);
                m.put("minExclusive", minExclusive);
            }
            if (!options.isEmpty()) m.put("options", options);
            if (ref != null) m.put("ref", ref);
            if (pattern != null) m.put("pattern", pattern.pattern());
            if (hint != null) m.put("hint", hint);
            if (placeholder != null) m.put("placeholder", placeholder);
            if (fallback != null) m.put("default", fallback);
            if (today) m.put("today", true);
            if (wide) m.put("wide", true);
            return m;
        }
    }

    static Field text(String name, String label, int maxLen) {
        return new Field(name, label, Kind.TEXT).max(maxLen);
    }

    static Field integer(String name, String label) {
        return new Field(name, label, Kind.INT);
    }

    static Field money(String name, String label) {
        return new Field(name, label, Kind.MONEY);
    }

    static Field date(String name, String label) {
        return new Field(name, label, Kind.DATE);
    }

    static Field choice(String name, String label, String... options) {
        Field f = new Field(name, label, Kind.ENUM);
        f.options = List.of(options);
        return f;
    }

    static Field ref(String name, String label, String entityKey) {
        Field f = new Field(name, label, Kind.REF);
        f.ref = entityKey;
        return f;
    }

    // =========================
    // COLUMN (shown in lists) and FILTER
    // =========================

    record Column(String name, String label, String kind) {
        Map<String, Object> toMeta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            m.put("kind", kind);
            return m;
        }
    }

    static Column col(String name, String label, String kind) {
        return new Column(name, label, kind);
    }

    // kind: "enum" (fixed options), "ref" (rows of another entity), "distinct" (values present in the data)
    record Filter(String name, String label, String kind) {
    }

    // =========================
    // ENTITY
    // =========================

    static final class Entity {
        String key;
        String table;
        String pk;
        String singular;
        String plural;
        String group;
        String icon;
        String blurb;
        List<Field> fields = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        List<String> search = List.of();
        List<Filter> filters = new ArrayList<>();
        String listSql;
        String optionsSql;
        String defaultSort;
        String defaultDir = "asc";
        String cardTitle;
        List<String> cardLines = List.of();
        String cardBadge;
        String cardFigure;

        Field field(String name) {
            for (Field f : fields) {
                if (f.name.equals(name)) {
                    return f;
                }
            }
            return null;
        }

        Filter filter(String name) {
            for (Filter f : filters) {
                if (f.name.equals(name)) {
                    return f;
                }
            }
            return null;
        }

        Set<String> sortable() {
            Set<String> names = new LinkedHashSet<>();
            for (Column c : columns) {
                names.add(c.name);
            }
            return names;
        }

        Map<String, Object> toMeta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("pk", pk);
            m.put("singular", singular);
            m.put("plural", plural);
            m.put("group", group);
            m.put("icon", icon);
            m.put("blurb", blurb);
            m.put("fields", fields.stream().map(Field::toMeta).toList());
            m.put("columns", columns.stream().map(Column::toMeta).toList());
            List<Map<String, Object>> fs = new ArrayList<>();
            for (Filter f : filters) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("label", f.label());
                fm.put("kind", f.kind());
                Field field = field(f.name());
                if (field != null && !field.options.isEmpty()) fm.put("options", field.options);
                if (field != null && field.ref != null) fm.put("ref", field.ref);
                fs.add(fm);
            }
            m.put("filters", fs);
            m.put("defaultSort", defaultSort);
            m.put("defaultDir", defaultDir);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("title", cardTitle);
            card.put("lines", cardLines);
            card.put("badge", cardBadge);
            card.put("figure", cardFigure);
            m.put("card", card);
            return m;
        }
    }

    static final Map<String, Entity> ENTITIES = new LinkedHashMap<>();

    static Entity byTable(String table) {
        for (Entity e : ENTITIES.values()) {
            if (e.table.equalsIgnoreCase(table)) {
                return e;
            }
        }
        return null;
    }

    private static Entity add(String key, String table, String pk, String singular, String plural,
                              String group, String icon, String blurb) {
        Entity e = new Entity();
        e.key = key;
        e.table = table;
        e.pk = pk;
        e.singular = singular;
        e.plural = plural;
        e.group = group;
        e.icon = icon;
        e.blurb = blurb;
        e.defaultSort = pk;
        ENTITIES.put(key, e);
        return e;
    }

    static {
        // ---- BOOKING ----
        Entity booking = add("bookings", "booking", "booking_id", "Booking", "Bookings",
                "Front Office", "calendar", "Reservations, stays and their current status.");
        booking.fields.add(ref("guest_id", "Guest", "guests").required());
        booking.fields.add(ref("room_id", "Room", "rooms").required());
        booking.fields.add(date("booking_date", "Booked on").required().today());
        booking.fields.add(choice("booking_status", "Status", "Confirmed", "Checked-In", "Checked-Out", "Cancelled")
                .required().fallback("Confirmed"));
        booking.fields.add(date("checkin_date", "Check-in").required());
        booking.fields.add(date("checkout_date", "Check-out").required());
        booking.fields.add(money("total_amount", "Room charges (₹)").atLeast("0").wide());
        booking.listSql = """
                SELECT b.*, g.guest_name, r.room_no, t.type_name, t.base_price,
                       (b.checkout_date - b.checkin_date) AS nights
                  FROM booking b
                  JOIN guest g ON g.guest_id = b.guest_id
                  JOIN room r ON r.room_id = b.room_id
                  JOIN room_type t ON t.type_id = r.type_id""";
        booking.optionsSql = """
                SELECT b.booking_id AS value,
                       '#' || b.booking_id || ' · ' || g.guest_name || ' · Room ' || r.room_no AS label,
                       b.booking_status AS note
                  FROM booking b
                  JOIN guest g ON g.guest_id = b.guest_id
                  JOIN room r ON r.room_id = b.room_id
                 ORDER BY b.booking_id DESC""";
        booking.columns = List.of(col("booking_id", "No.", "id"), col("guest_name", "Guest", "strong"),
                col("room_no", "Room", "room"), col("type_name", "Category", "text"),
                col("checkin_date", "Check-in", "date"), col("checkout_date", "Check-out", "date"),
                col("nights", "Nights", "int"), col("total_amount", "Room charges", "money"),
                col("booking_status", "Status", "badge"));
        booking.search = List.of("booking_id", "guest_name", "room_no", "type_name", "booking_status");
        booking.filters = List.of(new Filter("booking_status", "Status", "enum"), new Filter("guest_id", "Guest", "ref"));
        booking.defaultSort = "checkin_date";
        booking.defaultDir = "desc";
        booking.cardTitle = "guest_name";
        booking.cardLines = List.of("room_no", "type_name", "checkin_date", "checkout_date");
        booking.cardBadge = "booking_status";
        booking.cardFigure = "total_amount";

        // ---- GUEST ----
        Entity guest = add("guests", "guest", "guest_id", "Guest", "Guests",
                "Front Office", "guest", "Everyone who has stayed or reserved with the hotel.");
        guest.fields.add(text("guest_name", "Full name", 50).required().placeholder("e.g. Ananya Iyer"));
        guest.fields.add(text("phone", "Phone", 15).required()
                .pattern("^\\+?[0-9]{7,14}$", "7 to 14 digits, with an optional leading +").placeholder("9876543210"));
        guest.fields.add(text("email", "Email", 100)
                .pattern("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", "A valid email such as name@example.com")
                .placeholder("name@example.com"));
        guest.fields.add(text("id_proof_no", "ID proof number", 30).required()
                .pattern("^[A-Za-z0-9][A-Za-z0-9 /-]{3,29}$", "4 to 30 letters, digits, spaces, / or -")
                .placeholder("Aadhaar / passport no."));
        guest.fields.add(text("address", "Address", 100).wide());
        guest.fields.add(text("city", "City", 50));
        guest.listSql = """
                SELECT g.*, (SELECT COUNT(*) FROM booking b WHERE b.guest_id = g.guest_id) AS booking_count
                  FROM guest g""";
        guest.optionsSql = """
                SELECT guest_id AS value, guest_name || ' · ' || phone AS label
                  FROM guest ORDER BY guest_name""";
        guest.columns = List.of(col("guest_id", "No.", "id"), col("guest_name", "Guest", "strong"),
                col("phone", "Phone", "text"), col("email", "Email", "text"), col("city", "City", "text"),
                col("id_proof_no", "ID proof", "mono"), col("booking_count", "Stays", "int"));
        guest.search = List.of("guest_name", "phone", "email", "city", "address", "id_proof_no");
        guest.filters = List.of(new Filter("city", "City", "distinct"));
        guest.defaultSort = "guest_name";
        guest.cardTitle = "guest_name";
        guest.cardLines = List.of("phone", "email", "city");
        guest.cardFigure = "booking_count";

        // ---- PAYMENT ----
        Entity payment = add("payments", "payment", "payment_id", "Payment", "Payments",
                "Front Office", "payment", "Money received against bookings.");
        payment.fields.add(ref("booking_id", "Booking", "bookings").required().wide());
        payment.fields.add(money("amount", "Amount (₹)").required().moreThan("0"));
        payment.fields.add(choice("pay_mode", "Mode", "Cash", "Card", "UPI", "Net Banking").required());
        payment.fields.add(date("pay_date", "Paid on").required().today());
        payment.listSql = """
                SELECT p.*, g.guest_name, r.room_no
                  FROM payment p
                  JOIN booking b ON b.booking_id = p.booking_id
                  JOIN guest g ON g.guest_id = b.guest_id
                  JOIN room r ON r.room_id = b.room_id""";
        payment.optionsSql = """
                SELECT payment_id AS value, '#' || payment_id AS label FROM payment ORDER BY payment_id DESC""";
        payment.columns = List.of(col("payment_id", "No.", "id"), col("booking_id", "Booking", "ref"),
                col("guest_name", "Guest", "strong"), col("room_no", "Room", "room"),
                col("amount", "Amount", "money"), col("pay_mode", "Mode", "badge"), col("pay_date", "Paid on", "date"));
        payment.search = List.of("payment_id", "booking_id", "guest_name", "pay_mode", "room_no");
        payment.filters = List.of(new Filter("pay_mode", "Mode", "enum"), new Filter("booking_id", "Booking", "ref"));
        payment.defaultSort = "pay_date";
        payment.defaultDir = "desc";
        payment.cardTitle = "guest_name";
        payment.cardLines = List.of("booking_id", "room_no", "pay_date");
        payment.cardBadge = "pay_mode";
        payment.cardFigure = "amount";

        // ---- ROOM ----
        Entity room = add("rooms", "room", "room_id", "Room", "Rooms",
                "Rooms", "room", "The physical room inventory and its live status.");
        room.fields.add(integer("room_no", "Room number").required().moreThan("0").placeholder("e.g. 204"));
        room.fields.add(integer("floor_no", "Floor").required().moreThan("0"));
        room.fields.add(ref("type_id", "Category", "room-types").required());
        room.fields.add(choice("room_status", "Status", "Available", "Occupied", "Maintenance")
                .required().fallback("Available"));
        room.listSql = """
                SELECT r.*, t.type_name, t.base_price, t.capacity
                  FROM room r
                  JOIN room_type t ON t.type_id = r.type_id""";
        room.optionsSql = """
                SELECT r.room_id AS value,
                       'Room ' || r.room_no || ' · ' || t.type_name AS label,
                       r.room_status AS note, t.base_price
                  FROM room r
                  JOIN room_type t ON t.type_id = r.type_id
                 ORDER BY r.room_no""";
        room.columns = List.of(col("room_id", "No.", "id"), col("room_no", "Room", "room"),
                col("floor_no", "Floor", "int"), col("type_name", "Category", "text"),
                col("capacity", "Sleeps", "int"), col("base_price", "Tariff / night", "money"),
                col("room_status", "Status", "badge"));
        room.search = List.of("room_no", "type_name", "room_status", "floor_no");
        room.filters = List.of(new Filter("room_status", "Status", "enum"), new Filter("type_id", "Category", "ref"));
        room.defaultSort = "room_no";
        room.cardTitle = "room_no";
        room.cardLines = List.of("type_name", "floor_no", "capacity");
        room.cardBadge = "room_status";
        room.cardFigure = "base_price";

        // ---- ROOM_TYPE ----
        Entity roomType = add("room-types", "room_type", "type_id", "Room category", "Room categories",
                "Rooms", "layers", "Categories with their nightly tariff and capacity.");
        roomType.fields.add(text("type_name", "Category name", 30).required().wide().placeholder("e.g. Garden Suite"));
        roomType.fields.add(money("base_price", "Tariff per night (₹)").required().moreThan("0"));
        roomType.fields.add(integer("capacity", "Sleeps").required().moreThan("0"));
        roomType.listSql = """
                SELECT t.*, (SELECT COUNT(*) FROM room r WHERE r.type_id = t.type_id) AS room_count
                  FROM room_type t""";
        roomType.optionsSql = """
                SELECT type_id AS value, type_name AS label, base_price FROM room_type ORDER BY base_price""";
        roomType.columns = List.of(col("type_id", "No.", "id"), col("type_name", "Category", "strong"),
                col("base_price", "Tariff / night", "money"), col("capacity", "Sleeps", "int"),
                col("room_count", "Rooms", "int"));
        roomType.search = List.of("type_name");
        roomType.defaultSort = "base_price";
        roomType.cardTitle = "type_name";
        roomType.cardLines = List.of("capacity", "room_count");
        roomType.cardFigure = "base_price";

        // ---- SERVICE_REQUEST ----
        Entity request = add("service-requests", "service_request", "request_id", "Service request", "Service requests",
                "Services", "bell", "Services consumed during a stay and who attended them.");
        request.fields.add(ref("booking_id", "Booking", "bookings").required().wide());
        request.fields.add(ref("service_id", "Service", "services").required());
        request.fields.add(integer("quantity", "Quantity").required().moreThan("0").fallback("1"));
        request.fields.add(ref("emp_id", "Attended by", "employees").required());
        request.fields.add(date("request_date", "Requested on").required().today());
        request.listSql = """
                SELECT sr.*, s.service_name, s.service_price,
                       (sr.quantity * s.service_price) AS line_total,
                       e.emp_name, g.guest_name, r.room_no
                  FROM service_request sr
                  JOIN service s ON s.service_id = sr.service_id
                  JOIN employee e ON e.emp_id = sr.emp_id
                  JOIN booking b ON b.booking_id = sr.booking_id
                  JOIN guest g ON g.guest_id = b.guest_id
                  JOIN room r ON r.room_id = b.room_id""";
        request.optionsSql = """
                SELECT request_id AS value, '#' || request_id AS label FROM service_request ORDER BY request_id DESC""";
        request.columns = List.of(col("request_id", "No.", "id"), col("guest_name", "Guest", "strong"),
                col("room_no", "Room", "room"), col("service_name", "Service", "text"),
                col("quantity", "Qty", "int"), col("line_total", "Charge", "money"),
                col("emp_name", "Attended by", "text"), col("request_date", "Requested on", "date"));
        request.search = List.of("request_id", "guest_name", "service_name", "emp_name", "room_no");
        request.filters = List.of(new Filter("service_id", "Service", "ref"), new Filter("emp_id", "Attended by", "ref"));
        request.defaultSort = "request_date";
        request.defaultDir = "desc";
        request.cardTitle = "service_name";
        request.cardLines = List.of("guest_name", "room_no", "emp_name", "request_date");
        request.cardFigure = "line_total";

        // ---- SERVICE ----
        Entity service = add("services", "service", "service_id", "Service", "Services",
                "Services", "sparkle", "Chargeable services offered to guests.");
        service.fields.add(text("service_name", "Service name", 50).required().wide().placeholder("e.g. Afternoon Tea"));
        service.fields.add(money("service_price", "Price (₹)").required().moreThan("0").wide());
        service.listSql = """
                SELECT s.*,
                       (SELECT COALESCE(SUM(q.quantity), 0) FROM service_request q WHERE q.service_id = s.service_id)
                           AS times_requested
                  FROM service s""";
        service.optionsSql = """
                SELECT service_id AS value, service_name AS label, service_price FROM service ORDER BY service_name""";
        service.columns = List.of(col("service_id", "No.", "id"), col("service_name", "Service", "strong"),
                col("service_price", "Price", "money"), col("times_requested", "Units requested", "int"));
        service.search = List.of("service_name");
        service.defaultSort = "service_name";
        service.cardTitle = "service_name";
        service.cardLines = List.of("times_requested");
        service.cardFigure = "service_price";

        // ---- EMPLOYEE ----
        Entity employee = add("employees", "employee", "emp_id", "Employee", "Employees",
                "Staff", "staff", "Staff records, departments and reporting lines.");
        employee.fields.add(text("emp_name", "Full name", 50).required());
        employee.fields.add(text("designation", "Designation", 50).placeholder("e.g. Concierge"));
        employee.fields.add(ref("dept_id", "Department", "departments").required());
        employee.fields.add(ref("manager_id", "Reports to", "employees"));
        employee.fields.add(money("salary", "Monthly salary (₹)").moreThan("0"));
        employee.fields.add(date("hire_date", "Hired on").required().today());
        employee.listSql = """
                SELECT e.*, d.dept_name, m.emp_name AS manager_name
                  FROM employee e
                  JOIN department d ON d.dept_id = e.dept_id
                  LEFT JOIN employee m ON m.emp_id = e.manager_id""";
        employee.optionsSql = """
                SELECT emp_id AS value, emp_name || COALESCE(' · ' || designation, '') AS label
                  FROM employee ORDER BY emp_name""";
        employee.columns = List.of(col("emp_id", "No.", "id"), col("emp_name", "Employee", "strong"),
                col("designation", "Designation", "text"), col("dept_name", "Department", "text"),
                col("manager_name", "Reports to", "text"), col("salary", "Salary", "money"),
                col("hire_date", "Hired on", "date"));
        employee.search = List.of("emp_name", "designation", "dept_name", "manager_name");
        employee.filters = List.of(new Filter("dept_id", "Department", "ref"));
        employee.defaultSort = "emp_name";
        employee.cardTitle = "emp_name";
        employee.cardLines = List.of("designation", "dept_name", "manager_name");
        employee.cardFigure = "salary";

        // ---- DEPARTMENT ----
        Entity department = add("departments", "department", "dept_id", "Department", "Departments",
                "Staff", "building", "Departments and where they are located.");
        department.fields.add(text("dept_name", "Department name", 50).required().wide());
        department.fields.add(text("location", "Location", 50).wide().placeholder("e.g. Ground Floor"));
        department.listSql = """
                SELECT d.*, (SELECT COUNT(*) FROM employee e WHERE e.dept_id = d.dept_id) AS employee_count
                  FROM department d""";
        department.optionsSql = """
                SELECT dept_id AS value, dept_name AS label FROM department ORDER BY dept_name""";
        department.columns = List.of(col("dept_id", "No.", "id"), col("dept_name", "Department", "strong"),
                col("location", "Location", "text"), col("employee_count", "Employees", "int"));
        department.search = List.of("dept_name", "location");
        department.filters = List.of(new Filter("location", "Location", "distinct"));
        department.defaultSort = "dept_name";
        department.cardTitle = "dept_name";
        department.cardLines = List.of("location");
        department.cardFigure = "employee_count";
    }

    private Schema() {
    }
}
