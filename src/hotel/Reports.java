package hotel;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Read-only summaries: the dashboard, the management reports from the project
// report (GROUP BY, HAVING, sub-query and JOIN queries) and a booking's bill.
final class Reports {

    private Reports() {
    }

    // What a booking owes: room charges plus services, less payments received.
    private static final String BALANCE_PER_BOOKING = """
            SELECT b.booking_id,
                   COALESCE(b.total_amount, 0)
                   + COALESCE((SELECT SUM(sr.quantity * s.service_price)
                                 FROM service_request sr JOIN service s ON s.service_id = sr.service_id
                                WHERE sr.booking_id = b.booking_id), 0)
                   - COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.booking_id = b.booking_id), 0) AS due
              FROM booking b
             WHERE b.booking_status <> 'Cancelled'""";

    private static final String REVENUE_BY_ROOM_TYPE = """
            SELECT t.type_name, t.base_price,
                   COUNT(b.booking_id) AS bookings,
                   COALESCE(SUM(b.total_amount), 0) AS revenue
              FROM room_type t
              LEFT JOIN room r ON r.type_id = t.type_id
              LEFT JOIN booking b ON b.room_id = r.room_id AND b.booking_status <> 'Cancelled'
             GROUP BY t.type_id, t.type_name, t.base_price
             ORDER BY revenue DESC, t.type_name""";

    private static final String BOOKINGS_BY_STATUS = """
            SELECT booking_status AS label, COUNT(*) AS total
              FROM booking GROUP BY booking_status ORDER BY total DESC, label""";

    static Map<String, Object> dashboard() throws SQLException {
        try (Connection c = Db.open()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stats", Db.queryOne(c, """
                    SELECT (SELECT COUNT(*) FROM guest) AS guests,
                           (SELECT COUNT(*) FROM room) AS rooms,
                           (SELECT COUNT(*) FROM room WHERE room_status = 'Available') AS rooms_available,
                           (SELECT COUNT(*) FROM room WHERE room_status = 'Occupied') AS rooms_occupied,
                           (SELECT COUNT(*) FROM room WHERE room_status = 'Maintenance') AS rooms_maintenance,
                           (SELECT COUNT(*) FROM booking) AS bookings,
                           (SELECT COUNT(*) FROM booking
                             WHERE booking_status IN ('Confirmed', 'Checked-In')) AS active_bookings,
                           (SELECT COUNT(*) FROM employee) AS employees,
                           (SELECT COALESCE(SUM(amount), 0) FROM payment) AS collected,
                           (SELECT COALESCE(SUM(GREATEST(x.due, 0)), 0)
                              FROM (""" + BALANCE_PER_BOOKING + ") x) AS outstanding"));
            body.put("recentBookings", Db.query(c, "SELECT q.* FROM (" + Schema.ENTITIES.get("bookings").listSql
                    + ") q ORDER BY q.checkin_date DESC, q.booking_id DESC LIMIT 6"));
            body.put("revenueByRoomType", Db.query(c, REVENUE_BY_ROOM_TYPE));
            body.put("bookingsByStatus", Db.query(c, BOOKINGS_BY_STATUS));
            return body;
        }
    }

    static Map<String, Object> insights() throws SQLException {
        try (Connection c = Db.open()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("revenueByRoomType", Db.query(c, REVENUE_BY_ROOM_TYPE));
            body.put("bookingsByStatus", Db.query(c, BOOKINGS_BY_STATUS));
            body.put("paymentsByMode", Db.query(c, """
                    SELECT pay_mode AS label, COUNT(*) AS payments, SUM(amount) AS total
                      FROM payment GROUP BY pay_mode ORDER BY total DESC"""));
            body.put("departments", Db.query(c, """
                    SELECT d.dept_name, d.location, COUNT(e.emp_id) AS employees,
                           ROUND(AVG(e.salary), 2) AS avg_salary,
                           COALESCE(SUM(e.salary), 0) AS total_salary
                      FROM department d
                      LEFT JOIN employee e ON e.dept_id = d.dept_id
                     GROUP BY d.dept_id, d.dept_name, d.location
                     ORDER BY total_salary DESC, d.dept_name"""));
            body.put("highAverageDepartments", Db.query(c, """
                    SELECT d.dept_name, ROUND(AVG(e.salary), 2) AS avg_salary
                      FROM department d JOIN employee e ON e.dept_id = d.dept_id
                     GROUP BY d.dept_name
                    HAVING AVG(e.salary) > 50000
                     ORDER BY avg_salary DESC"""));
            body.put("averageSalary", Db.queryOne(c,
                    "SELECT ROUND(AVG(salary), 2) AS value FROM employee").get("value"));
            body.put("aboveAverageSalary", Db.query(c, """
                    SELECT e.emp_name, e.designation, d.dept_name, e.salary
                      FROM employee e JOIN department d ON d.dept_id = e.dept_id
                     WHERE e.salary > (SELECT AVG(salary) FROM employee)
                     ORDER BY e.salary DESC"""));
            body.put("services", Db.query(c, """
                    SELECT s.service_name, s.service_price,
                           COALESCE(SUM(sr.quantity), 0) AS units,
                           COALESCE(SUM(sr.quantity * s.service_price), 0) AS revenue
                      FROM service s
                      LEFT JOIN service_request sr ON sr.service_id = s.service_id
                     GROUP BY s.service_id, s.service_name, s.service_price
                     ORDER BY revenue DESC, s.service_name"""));
            body.put("topGuests", Db.query(c, """
                    SELECT g.guest_name, g.city, COUNT(b.booking_id) AS stays,
                           COALESCE(SUM(b.total_amount), 0) AS room_spend
                      FROM guest g
                      JOIN booking b ON b.guest_id = g.guest_id AND b.booking_status <> 'Cancelled'
                     GROUP BY g.guest_id, g.guest_name, g.city
                     ORDER BY room_spend DESC, g.guest_name
                     LIMIT 5"""));
            return body;
        }
    }

    static Map<String, Object> bill(int bookingId) throws SQLException {
        try (Connection c = Db.open()) {
            Map<String, Object> booking = Db.queryOne(c, "SELECT q.* FROM ("
                    + Schema.ENTITIES.get("bookings").listSql + ") q WHERE q.booking_id = ?", bookingId);
            if (booking == null) {
                throw ApiException.notFound("Booking #" + bookingId);
            }
            List<Map<String, Object>> services = Db.query(c, """
                    SELECT sr.request_id, sr.request_date, s.service_name, sr.quantity, s.service_price,
                           (sr.quantity * s.service_price) AS line_total, e.emp_name
                      FROM service_request sr
                      JOIN service s ON s.service_id = sr.service_id
                      JOIN employee e ON e.emp_id = sr.emp_id
                     WHERE sr.booking_id = ?
                     ORDER BY sr.request_date, sr.request_id""", bookingId);
            List<Map<String, Object>> payments = Db.query(c, """
                    SELECT payment_id, pay_date, pay_mode, amount
                      FROM payment WHERE booking_id = ? ORDER BY pay_date, payment_id""", bookingId);

            BigDecimal room = booking.get("total_amount") instanceof BigDecimal n ? n : BigDecimal.ZERO;
            BigDecimal serviceTotal = sum(services, "line_total");
            BigDecimal paid = sum(payments, "amount");

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("room", room);
            totals.put("services", serviceTotal);
            totals.put("grand", room.add(serviceTotal));
            totals.put("paid", paid);
            totals.put("balance", room.add(serviceTotal).subtract(paid));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("booking", booking);
            body.put("services", services);
            body.put("payments", payments);
            body.put("totals", totals);
            return body;
        }
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            if (row.get(column) instanceof BigDecimal n) {
                total = total.add(n);
            }
        }
        return total;
    }
}
