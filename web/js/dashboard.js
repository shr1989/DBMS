// Dashboard and Insights pages. Every figure is computed by the database
// through /api/dashboard and /api/reports — nothing here is sample data.

import { api } from "./api.js";
import { entities, onDataChanged } from "./state.js";
import { openRecordForm, openRecordDetail } from "./forms.js";
import {
    h, icon, clear, sprig, spinner, errorState, emptyState, bars, badge,
    formatMoney, formatNumber, formatDate,
} from "./ui.js";

function loading(text) {
    return h("div", { class: "boot", role: "status" }, spinner(), h("p", null, text));
}

function panel(title, note, body, link) {
    return h("section", { class: "card" },
        h("div", { class: "card__head" },
            h("div", null, h("h2", null, title), note ? h("p", null, note) : null),
            link ? h("a", { class: "card__link", href: link.href }, link.label) : null),
        h("div", { class: "card__body" }, body));
}

// Loads once, then again whenever a record changes anywhere in the app.
function liveView(container, masthead, fetcher, render, loadingText) {
    const content = h("div", { class: "stack" });
    clear(container).append(masthead, content);
    let ticket = 0;
    let disposed = false;
    let loaded = false;

    async function load() {
        const mine = ++ticket;
        if (!loaded) clear(content).append(loading(loadingText));
        try {
            const data = await fetcher();
            if (mine !== ticket || disposed) return;
            loaded = true;
            clear(content).append(...[render(data)].flat());
        } catch (error) {
            if (mine !== ticket || disposed) return;
            loaded = false;
            clear(content).append(h("div", { class: "card" }, errorState(error, load)));
        }
    }

    const stop = onDataChanged(load);
    load();
    return () => {
        disposed = true;
        stop();
    };
}

// =========================
// DASHBOARD
// =========================

export function renderDashboard(container) {
    const hour = new Date().getHours();
    const greeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
    const masthead = h("header", { class: "masthead" },
        h("div", null,
            h("p", { class: "masthead__eyebrow" }, "Front Office"),
            h("h1", null, "Dashboard"),
            h("p", { class: "masthead__blurb" }, "A live view of the house, read straight from the hotel database.")));

    return liveView(container, masthead, api.dashboard, (data) => {
        const s = data.stats;
        const occupancy = s.rooms > 0 ? Math.round((s.rooms_occupied / s.rooms) * 100) : 0;

        const hero = h("section", { class: "hero" },
            sprig(true),
            h("div", null,
                h("h2", null, greeting),
                h("p", null, new Intl.DateTimeFormat("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(new Date())
                    + " · " + s.active_bookings + (s.active_bookings === 1 ? " active booking" : " active bookings")
                    + " · " + s.rooms_available + " of " + s.rooms + " rooms available")),
            h("div", { class: "hero__actions" },
                h("button", { class: "btn", type: "button", onclick: () => openRecordForm(entities.get("bookings")) }, icon("plus"), "New booking"),
                h("button", { class: "btn btn--on-dark", type: "button", onclick: () => openRecordForm(entities.get("guests")) }, icon("plus"), "Register guest")));
        hero.querySelector(".sprig").classList.add("hero__sprig");

        const stat = (label, value, note, href, accent) => h("a", { class: "stat" + (accent ? " stat--accent" : ""), href },
            h("div", { class: "stat__label" }, label),
            h("div", { class: "stat__value num" }, value),
            h("div", { class: "stat__note" }, note));
        const stats = h("section", { class: "stats", "aria-label": "Key figures" },
            stat("Rooms available", formatNumber(s.rooms_available), "of " + s.rooms + " · " + occupancy + "% occupied", "#/rooms?room_status=Available"),
            stat("Active bookings", formatNumber(s.active_bookings), s.bookings + " in total", "#/bookings"),
            stat("Guests", formatNumber(s.guests), "on the register", "#/guests"),
            stat("Collected", formatMoney(s.collected), "payments received", "#/payments"),
            stat("Outstanding", formatMoney(s.outstanding), "still to be paid", "#/bookings", true));

        const recent = data.recentBookings.length === 0
            ? emptyState({ title: "No bookings yet", message: "New reservations will appear here." })
            : h("ul", { class: "list" }, data.recentBookings.map((b) => h("li", null,
                h("div", { class: "list__main" },
                    h("button", { class: "row-open list__title", type: "button", onclick: () => openRecordDetail(entities.get("bookings"), b.booking_id) }, b.guest_name),
                    h("div", { class: "list__sub" }, "Room " + b.room_no + " · " + b.type_name + " · "
                        + formatDate(b.checkin_date) + " → " + formatDate(b.checkout_date))),
                h("div", { class: "list__side" }, badge(b.booking_status), h("span", { class: "num" }, formatMoney(b.total_amount))))));

        const board = h("div", null,
            h("div", { class: "room-board", id: "room-board" }, spinner()),
            h("div", { class: "legend" },
                ["Available", "Occupied", "Maintenance"].map((status) => h("span", null, h("i", { dataset: { status } }), status))));
        api.list("rooms", { pageSize: 200, sort: "room_no" }).then((response) => {
            const target = board.querySelector("#room-board");
            clear(target);
            if (response.data.length === 0) {
                target.append(h("p", { class: "field__hint" }, "No rooms have been added yet."));
            }
            response.data.forEach((room) => target.append(h("a", {
                class: "room-chip", href: "#/rooms?search=" + room.room_no, dataset: { status: room.room_status },
                "aria-label": "Room " + room.room_no + ", " + room.type_name + ", " + room.room_status,
            }, h("b", null, room.room_no), h("span", null, room.type_name))));
        }).catch(() => {
            clear(board.querySelector("#room-board")).append(h("p", { class: "field__hint" }, "The room board could not be loaded."));
        });

        return [
            hero,
            stats,
            h("div", { class: "grid grid--split" },
                panel("Recent bookings", "Latest arrivals first", recent, { href: "#/bookings", label: "All bookings" }),
                h("div", { class: "stack" },
                    panel("Room board", "Tap a room to open it", board, { href: "#/rooms", label: "All rooms" }),
                    panel("Revenue by category", "Room charges on active and past stays",
                        bars(data.revenueByRoomType, { label: "type_name", value: "revenue", format: formatMoney })))),
        ];
    }, "Reading the register…");
}

// =========================
// INSIGHTS
// =========================

function miniTable(columns, rows, emptyText) {
    if (rows.length === 0) return h("p", { class: "field__hint" }, emptyText);
    return h("table", { class: "mini" },
        h("thead", null, h("tr", null, columns.map((c) => h("th", { scope: "col", class: c.num ? "is-num" : null }, c.label)))),
        h("tbody", null, rows.map((row) => h("tr", null, columns.map((c) =>
            h("td", { class: c.num ? "is-num" : null }, c.render(row)))))));
}

export function renderInsights(container) {
    const masthead = h("header", { class: "masthead" },
        h("div", null,
            h("p", { class: "masthead__eyebrow" }, "Reports"),
            h("h1", null, "Insights"),
            h("p", { class: "masthead__blurb" }, "Management reports built from GROUP BY, HAVING, sub-query and JOIN queries on the live data.")));

    return liveView(container, masthead, api.reports, (d) => [
        h("div", { class: "grid grid--2" },
            panel("Revenue by room category", "Total room charges per category",
                h("div", null,
                    bars(d.revenueByRoomType, { label: "type_name", value: "revenue", format: formatMoney }),
                    h("p", { class: "query-note" }, "JOIN room_type → room → booking · GROUP BY category"))),
            panel("Payments by mode", "How guests have paid",
                h("div", null,
                    d.paymentsByMode.length === 0 ? h("p", { class: "field__hint" }, "No payments recorded yet.")
                        : bars(d.paymentsByMode, { label: "label", value: "total", format: formatMoney, tone: "boho" }),
                    h("p", { class: "query-note" }, "GROUP BY pay_mode · SUM(amount)")))),
        h("div", { class: "grid grid--2" },
            panel("Bookings by status", "Count of reservations in each state",
                h("div", null,
                    d.bookingsByStatus.length === 0 ? h("p", { class: "field__hint" }, "No bookings yet.")
                        : bars(d.bookingsByStatus, { label: "label", value: "total", tone: "deep" }),
                    h("p", { class: "query-note" }, "GROUP BY booking_status · COUNT(*)"))),
            panel("Most requested services", "Units and revenue per service",
                h("div", null,
                    miniTable([
                        { label: "Service", render: (r) => r.service_name },
                        { label: "Units", num: true, render: (r) => formatNumber(r.units) },
                        { label: "Revenue", num: true, render: (r) => formatMoney(r.revenue) },
                    ], d.services, "No services defined yet."),
                    h("p", { class: "query-note" }, "LEFT JOIN service_request · SUM(quantity × price)")))),
        panel("Departments", "Head-count and salary spend per department",
            h("div", null,
                miniTable([
                    { label: "Department", render: (r) => r.dept_name },
                    { label: "Location", render: (r) => r.location || "—" },
                    { label: "Employees", num: true, render: (r) => formatNumber(r.employees) },
                    { label: "Average salary", num: true, render: (r) => formatMoney(r.avg_salary) },
                    { label: "Total salary", num: true, render: (r) => formatMoney(r.total_salary) },
                ], d.departments, "No departments yet."),
                h("p", { class: "query-note" }, "GROUP BY department · departments averaging above ₹50,000 (HAVING): "
                    + (d.highAverageDepartments.map((r) => r.dept_name).join(", ") || "none")))),
        h("div", { class: "grid grid--2" },
            panel("Earning above average", "Average salary is " + formatMoney(d.averageSalary),
                h("div", null,
                    miniTable([
                        { label: "Employee", render: (r) => r.emp_name },
                        { label: "Department", render: (r) => r.dept_name },
                        { label: "Salary", num: true, render: (r) => formatMoney(r.salary) },
                    ], d.aboveAverageSalary, "No employees are above the average."),
                    h("p", { class: "query-note" }, "Sub-query: salary > (SELECT AVG(salary) FROM employee)"))),
            panel("Highest-spending guests", "By room charges on their stays",
                h("div", null,
                    miniTable([
                        { label: "Guest", render: (r) => r.guest_name },
                        { label: "City", render: (r) => r.city || "—" },
                        { label: "Stays", num: true, render: (r) => formatNumber(r.stays) },
                        { label: "Room spend", num: true, render: (r) => formatMoney(r.room_spend) },
                    ], d.topGuests, "No stays recorded yet."),
                    h("p", { class: "query-note" }, "JOIN guest → booking · ORDER BY spend · top 5")))),
    ], "Running the reports…");
}
