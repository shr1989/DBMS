// Small DOM, formatting, toast and dialog helpers shared by every view.

// =========================
// DOM
// =========================

// h("button", { class: "btn", onclick: fn }, "Save") — text children are always
// inserted as text nodes, so database values can never be interpreted as HTML.
export function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
        if (value === null || value === undefined || value === false) continue;
        if (key === "class") el.className = value;
        else if (key === "dataset") Object.assign(el.dataset, value);
        else if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
        else if (value === true) el.setAttribute(key, "");
        else el.setAttribute(key, value);
    }
    append(el, children);
    return el;
}

function append(el, children) {
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false) continue;
        el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
}

export function clear(el) {
    el.replaceChildren();
    return el;
}

const ICONS = {
    dashboard: "M4 13h6V4H4zM14 20h6v-9h-6zM14 4v4h6V4zM4 20h6v-4H4z",
    calendar: "M7 3v3M17 3v3M4 9h16M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM9 14l2 2 4-4",
    guest: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 20c.6-3.6 3.8-5.5 8-5.5s7.400 1.900 8 5.500",
    payment: "M3 7h18v11H3zM3 11h18M7 15h3",
    room: "M3 19V6M3 15h18v4M21 15v-3a3 3 0 0 0-3-3h-7v6M7 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
    layers: "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5M3 17l9 5 9-5",
    bell: "M4 18h16M6 18a6 6 0 0 1 12 0M12 9V7M10 7h4M2 21h20",
    sparkle: "M12 3l1.800 5.200L19 10l-5.200 1.800L12 17l-1.800-5.200L5 10l5.200-1.800zM19 16l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z",
    staff: "M9 11a3.500 3.500 0 1 0 0-7 3.500 3.500 0 0 0 0 7zM2 20c.5-3.200 3.200-5 7-5s6.500 1.800 7 5M16 4.300a3.500 3.500 0 0 1 0 6.400M18.500 15.300c1.900.7 3.100 2.300 3.500 4.700",
    building: "M5 21V4h10v17M15 9h4v12M3 21h18M8 8h4M8 12h4M8 16h4",
    chart: "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-8",
    plus: "M12 5v14M5 12h14",
    search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4",
    close: "M6 6l12 12M18 6L6 18",
    edit: "M4 20h4L19 9l-4-4L4 16zM13.500 6.500l4 4",
    trash: "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6",
    eye: "M2 12s3.600-7 10-7 10 7 10 7-3.600 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    check: "M5 12.500l4.500 4.500L19 7.500",
    alert: "M12 8v5M12 16.500v.5M10.300 3.900L2.700 17a2 2 0 0 0 1.700 3h15.200a2 2 0 0 0 1.700-3L13.700 3.900a2 2 0 0 0-3.400 0z",
    arrow: "M12 19V5M6 11l6-6 6 6",
    prev: "M15 6l-6 6 6 6",
    next: "M9 6l6 6-6 6",
    table: "M4 5h16v14H4zM4 10h16M4 15h16M10 5v14",
    grid: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
    menu: "M4 7h16M4 12h16M4 17h16",
    refresh: "M20 11a8 8 0 1 0-2.300 5.700M20 4v7h-7",
    rose: "M12 12c-1.300-.3-1.600-1.900-.3-2.600 1.600-.8 3.700.5 3.500 2.600-.2 2.400-2.900 3.700-5.100 2.700-2.700-1.200-3.300-4.900-1.200-7 2.200-2.200 6.400-1.800 8.200 1.100M6.500 18c2.400 2.100 7.200 2.200 10.200-.5",
};

export function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", "icon");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", ICONS[name] || ICONS.sparkle);
    svg.append(path);
    return svg;
}

export function sprig(light = false) {
    return h("img", {
        class: "sprig",
        src: light ? "img/botanical-sprig-light.svg" : "img/botanical-sprig.svg",
        alt: "",
        "aria-hidden": "true",
    });
}

export function spinner() {
    return h("span", { class: "spinner", "aria-hidden": "true" });
}

// Puts a button into (and out of) its busy state; a busy button cannot be clicked again.
export function setBusy(button, busy, busyLabel) {
    if (busy) {
        button.dataset.label = button.textContent;
        button.disabled = true;
        button.setAttribute("aria-busy", "true");
        clear(button).append(spinner(), busyLabel || "Working…");
    } else if (button.dataset.label !== undefined) {
        button.disabled = false;
        button.removeAttribute("aria-busy");
        clear(button).append(button.dataset.label);
        delete button.dataset.label;
    }
}

export function debounce(fn, ms) {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
}

// =========================
// FORMATTING
// =========================

const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0 });
const whole = new Intl.NumberFormat("en-IN");
const dateFormat = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

export function formatMoney(value) {
    return value === null || value === undefined ? "—" : money.format(Number(value));
}

export function formatNumber(value) {
    return value === null || value === undefined ? "—" : whole.format(Number(value));
}

export function formatDate(value) {
    if (!value) return "—";
    const date = new Date(value + "T00:00:00Z");
    return Number.isNaN(date.getTime()) ? String(value) : dateFormat.format(date);
}

export function today() {
    const now = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    return now.getFullYear() + "-" + pad(now.getMonth() + 1) + "-" + pad(now.getDate());
}

const BADGE_TONES = {
    Available: "good", Occupied: "active", Maintenance: "deep",
    Confirmed: "good", "Checked-In": "active", "Checked-Out": "deep", Cancelled: "muted",
};

export function badge(value) {
    return h("span", { class: "badge", dataset: { tone: BADGE_TONES[value] || "plain" } }, value);
}

// Renders one value the way its column kind asks for.
export function formatCell(kind, value) {
    if (value === null || value === undefined || value === "") {
        return h("span", { class: "cell-empty", "aria-label": "Not set" }, "—");
    }
    switch (kind) {
        case "money": return formatMoney(value);
        case "int":   return formatNumber(value);
        case "date":  return formatDate(value);
        case "badge": return badge(value);
        case "id":    return h("span", { class: "cell-id" }, "#" + value);
        case "ref":   return h("span", { class: "cell-id" }, "#" + value);
        case "room":  return "Room " + value;
        case "mono":  return h("span", { class: "cell-mono" }, value);
        case "strong": return h("span", { class: "cell-strong" }, value);
        default:      return String(value);
    }
}

export function plainCell(kind, value) {
    const node = formatCell(kind, value);
    return node instanceof Node ? node.textContent : node;
}

export const isNumeric = (kind) => kind === "money" || kind === "int";

// =========================
// TOASTS
// =========================

export function toast(title, message, type = "success") {
    const region = document.getElementById("toasts");
    const close = h("button", { class: "icon-btn", type: "button", "aria-label": "Dismiss notification" }, icon("close"));
    const el = h("div", { class: "toast" + (type === "error" ? " toast--error" : ""), role: type === "error" ? "alert" : "status" },
        h("span", { class: "toast__icon" }, icon(type === "error" ? "alert" : "check")),
        h("div", { class: "toast__text" },
            h("div", { class: "toast__title" }, title),
            message ? h("div", { class: "toast__msg" }, message) : null),
        close);

    const dismiss = () => {
        if (!el.isConnected) return;
        el.classList.add("is-leaving");
        setTimeout(() => el.remove(), 180);
    };
    close.addEventListener("click", dismiss);
    setTimeout(dismiss, type === "error" ? 9000 : 4500);

    // Toasts must stay visible above an open modal dialog, which lives in the top layer.
    const dialog = document.querySelector("dialog[open]:last-of-type");
    (dialog || document.body).append(region);
    region.append(el);
    while (region.children.length > 4) region.firstElementChild.remove();
}

export function toastError(error, title = "That didn't work") {
    toast(title, error && error.message ? error.message : "Please try again.", "error");
}

// =========================
// DIALOGS
// =========================

// Opens a modal built on the native <dialog>, which provides focus trapping,
// Escape handling and an inert background. Returns { dialog, close }.
export function openModal({ title, subtitle, body, footer, size, onClose, canClose }) {
    const titleId = "modal-title-" + Math.random().toString(36).slice(2, 8);
    const closeButton = h("button", { class: "icon-btn icon-btn--on-dark", type: "button", "aria-label": "Close" }, icon("close"));
    const dialog = h("dialog", { class: "modal" + (size ? " modal--" + size : ""), "aria-labelledby": titleId },
        h("header", { class: "modal__head" },
            sprig(true),
            h("div", null, h("h2", { id: titleId }, title), subtitle ? h("p", null, subtitle) : null),
            closeButton),
        h("div", { class: "modal__body" }, body),
        footer ? h("footer", { class: "modal__foot" }, footer) : null);

    let closed = false;
    const close = (force = false) => {
        if (closed) return;
        if (!force && canClose && !canClose()) return;
        closed = true;
        const toasts = document.getElementById("toasts");
        if (dialog.contains(toasts)) document.body.append(toasts);
        dialog.close();
        dialog.remove();
        if (onClose) onClose();
    };

    closeButton.addEventListener("click", () => close());
    dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        close();
    });
    dialog.addEventListener("mousedown", (event) => {
        if (event.target === dialog) close();
    });

    document.body.append(dialog);
    dialog.showModal();
    return { dialog, close };
}

// Asks before a destructive action. `action` runs while the confirm button shows
// a busy state; the dialog stays open if it throws.
export function confirmAction({ title, message, confirmLabel = "Delete", busyLabel = "Deleting…", action }) {
    return new Promise((resolve) => {
        let working = false;
        const cancel = h("button", { class: "btn btn--ghost", type: "button" }, "Cancel");
        const confirm = h("button", { class: "btn", type: "button" }, confirmLabel);
        const modal = openModal({
            title,
            size: "sm",
            body: h("p", null, message),
            footer: [cancel, confirm],
            canClose: () => !working,
            onClose: () => resolve(false),
        });
        cancel.addEventListener("click", () => modal.close());
        confirm.addEventListener("click", async () => {
            if (working) return;
            working = true;
            cancel.disabled = true;
            setBusy(confirm, true, busyLabel);
            try {
                await action();
                working = false;
                resolve(true);
                modal.close(true);
            } catch (error) {
                working = false;
                cancel.disabled = false;
                setBusy(confirm, false);
                toastError(error);
            }
        });
        cancel.focus();
    });
}

// =========================
// STATES
// =========================

export function emptyState({ title, message, actions }) {
    return h("div", { class: "state" }, sprig(), h("h3", null, title), h("p", null, message),
        actions ? h("div", { class: "state__actions" }, actions) : null);
}

export function errorState(error, retry) {
    return h("div", { class: "state state--error", role: "alert" },
        sprig(),
        h("h3", null, error.isOutage ? "We can't reach the register" : "Something went wrong"),
        h("p", null, error.message),
        h("div", { class: "state__actions" },
            h("button", { class: "btn", type: "button", onclick: retry }, icon("refresh"), "Try again")));
}

export function bars(rows, { label, value, format = formatNumber, tone = "" }) {
    const max = Math.max(1, ...rows.map((row) => Number(row[value]) || 0));
    return h("div", { class: "bars" }, rows.map((row) => {
        const fill = h("div", { class: "bar__fill" + (tone ? " bar__fill--" + tone : "") });
        const amount = Number(row[value]) || 0;
        requestAnimationFrame(() => fill.style.setProperty("--w", (amount / max) * 100 + "%"));
        return h("div", null,
            h("div", { class: "bar__row" }, h("b", null, row[label] ?? "Not set"), h("span", { class: "num" }, format(row[value]))),
            h("div", { class: "bar__track", role: "presentation" }, fill));
    }));
}
