// Every request to the backend goes through this module.
// The browser never talks to the database — only to /api on the Java server.

const TIMEOUT_MS = 15000;
const listeners = new Set();

export class ApiError extends Error {
    constructor(message, { status = 0, code = "error", fields = {} } = {}) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    // True when the backend or the database could not be reached at all.
    get isOutage() {
        return this.status === 0 || this.status === 503;
    }
}

// Views subscribe to learn when the connection drops or recovers.
export function onConnectionChange(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

function announce(state) {
    listeners.forEach((listener) => listener(state));
}

async function request(method, path, { params, body, idempotencyKey } = {}) {
    const url = new URL("/api/" + path, window.location.origin);
    Object.entries(params || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== "") {
            url.searchParams.set(key, value);
        }
    });

    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    let response;
    try {
        response = await fetch(url, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: controller.signal,
        });
    } catch (cause) {
        announce("down");
        throw new ApiError(
            cause.name === "AbortError"
                ? "The server took too long to respond. Please try again."
                : "Cannot reach the server. Check that the backend is running, then try again.",
            { status: 0, code: "network" });
    } finally {
        clearTimeout(timer);
    }

    let payload = null;
    if (response.status !== 204) {
        try {
            payload = await response.json();
        } catch {
            payload = null;
        }
    }

    if (!response.ok) {
        const error = (payload && payload.error) || {};
        if (response.status === 503) announce("down");
        throw new ApiError(error.message || "The request failed (" + response.status + ").", {
            status: response.status,
            code: error.code,
            fields: error.fields || {},
        });
    }

    // /meta is served from memory, so it says nothing about the database.
    if (path !== "meta") announce("ok");
    return payload;
}

export const api = {
    health: () => request("GET", "health").then((r) => r.data),
    meta: () => request("GET", "meta").then((r) => r.data),
    dashboard: () => request("GET", "dashboard").then((r) => r.data),
    reports: () => request("GET", "reports").then((r) => r.data),
    list: (entity, params) => request("GET", entity, { params }),
    options: (entity) => request("GET", entity + "/options").then((r) => r.data),
    distinct: (entity, column) => request("GET", entity + "/distinct/" + column).then((r) => r.data),
    read: (entity, id) => request("GET", entity + "/" + id).then((r) => r.data),
    bill: (bookingId) => request("GET", "bookings/" + bookingId + "/bill").then((r) => r.data),
    create: (entity, body, idempotencyKey) => request("POST", entity, { body, idempotencyKey }).then((r) => r.data),
    update: (entity, id, body) => request("PUT", entity + "/" + id, { body }).then((r) => r.data),
    remove: (entity, id) => request("DELETE", entity + "/" + id),
};
