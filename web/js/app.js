// Application shell: start-up, navigation, routing and the connection indicator.

import { api, onConnectionChange } from "./api.js";
import { entities, setEntities, dataChanged } from "./state.js";
import { renderEntity } from "./entity.js";
import { renderDashboard, renderInsights } from "./dashboard.js";
import { h, icon, clear, errorState, emptyState } from "./ui.js";

const main = document.getElementById("main");
const nav = document.getElementById("nav");
const sidebar = document.getElementById("sidebar");
const menuButton = document.getElementById("menu-btn");
const statusPill = document.getElementById("status");
const statusText = document.getElementById("status-text");

let disposeView = null;
let connection = "checking";

// =========================
// CONNECTION INDICATOR
// =========================

function setConnection(state, label) {
    const recovered = connection === "down" && state === "ok";
    connection = state;
    statusPill.dataset.state = state;
    statusText.textContent = label || (state === "ok" ? "Database connected" : "Database unreachable");
    if (recovered) dataChanged(null); // reload whatever is on screen
}

onConnectionChange((state) => {
    if (state !== connection) setConnection(state);
});

async function checkHealth() {
    try {
        const health = await api.health();
        setConnection("ok", "Connected · " + health.database);
    } catch {
        setConnection("down");
    }
}

// While the database is unreachable, keep checking so the app recovers by itself.
setInterval(() => {
    if (connection === "down" && !document.hidden) checkHealth();
}, 5000);

// =========================
// NAVIGATION
// =========================

function buildNav() {
    const groups = new Map([["Front Office", [{ href: "#/dashboard", label: "Dashboard", icon: "dashboard" }]]]);
    for (const entity of entities.values()) {
        if (!groups.has(entity.group)) groups.set(entity.group, []);
        groups.get(entity.group).push({ href: "#/" + entity.key, label: entity.plural, icon: entity.icon });
    }
    groups.set("Reports", [{ href: "#/insights", label: "Insights", icon: "chart" }]);

    clear(nav);
    for (const [label, links] of groups) {
        nav.append(h("div", { class: "nav__group" },
            h("div", { class: "nav__label" }, label),
            links.map((link) => h("a", { class: "nav__link", href: link.href }, icon(link.icon), link.label))));
    }
}

function markCurrent(route) {
    nav.querySelectorAll(".nav__link").forEach((link) => {
        if (link.getAttribute("href") === "#/" + route) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
}

let scrim = null;

function setMenu(open) {
    sidebar.classList.toggle("is-open", open);
    menuButton.setAttribute("aria-expanded", String(open));
    if (open && !scrim) {
        scrim = h("button", { class: "scrim", type: "button", "aria-label": "Close menu", onclick: () => setMenu(false) });
        document.body.append(scrim);
    } else if (!open && scrim) {
        scrim.remove();
        scrim = null;
    }
}

menuButton.addEventListener("click", () => setMenu(!sidebar.classList.contains("is-open")));
nav.addEventListener("click", (event) => {
    if (event.target.closest(".nav__link")) setMenu(false);
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && sidebar.classList.contains("is-open")) {
        setMenu(false);
        menuButton.focus();
    }
});

// =========================
// ROUTING
// =========================

function route() {
    const hash = window.location.hash.replace(/^#\/?/, "");
    const [path, queryText] = hash.split("?");
    const name = path || "dashboard";
    const query = new URLSearchParams(queryText || "");

    if (disposeView) disposeView();
    disposeView = null;
    main.classList.remove("view-enter");
    void main.offsetWidth; // restart the entrance animation
    main.classList.add("view-enter");
    markCurrent(name);

    if (name === "dashboard") {
        document.title = "Dashboard · Hotel Management";
        disposeView = renderDashboard(main);
    } else if (name === "insights") {
        document.title = "Insights · Hotel Management";
        disposeView = renderInsights(main);
    } else if (entities.has(name)) {
        const entity = entities.get(name);
        document.title = entity.plural + " · Hotel Management";
        disposeView = renderEntity(main, entity, query);
    } else {
        document.title = "Not found · Hotel Management";
        clear(main).append(h("div", { class: "masthead" }), h("div", { class: "card" }, emptyState({
            title: "This page doesn't exist",
            message: "The address may be mistyped, or the page may have moved.",
            actions: h("a", { class: "btn", href: "#/dashboard" }, "Back to the dashboard"),
        })));
    }
    window.scrollTo(0, 0);
}

// =========================
// START
// =========================

async function start() {
    document.getElementById("brand-mark").append(icon("rose"));
    document.getElementById("menu-icon").append(icon("menu"));
    try {
        const meta = await api.meta();
        setEntities(meta.entities);
    } catch (error) {
        setConnection("down");
        clear(main).append(h("div", { class: "masthead" }), h("div", { class: "card" }, errorState(error, start)));
        return;
    }
    buildNav();
    window.addEventListener("hashchange", route);
    route();
    checkHealth();
}

start();
