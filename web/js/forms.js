// Create / edit forms, the record detail dialog and delete confirmation.
// Forms are generated from the entity descriptions served by the backend.

import { api } from "./api.js";
import { entities, dataChanged, recordName } from "./state.js";
import {
    h, icon, clear, openModal, confirmAction, setBusy, toast, toastError,
    formatCell, formatMoney, formatDate, today, badge,
} from "./ui.js";

// =========================
// VALIDATION (mirrors the backend rules)
// =========================

function minMessage(field, number) {
    if (field.min === undefined) return "";
    if (field.minExclusive && number <= field.min) return field.label + " must be greater than " + field.min + ".";
    if (!field.minExclusive && number < field.min) return field.label + " cannot be less than " + field.min + ".";
    return "";
}

function validateValue(field, control) {
    if (control.validity && control.validity.badInput) {
        return field.label + (field.kind === "date" ? " must be a valid date." : " must be a number.");
    }
    const value = control.value.trim();
    if (value === "") {
        return field.required && field.default === undefined ? field.label + " is required." : "";
    }
    switch (field.kind) {
        case "text":
            if (field.maxLength && [...value].length > field.maxLength) {
                return field.label + " can be at most " + field.maxLength + " characters.";
            }
            if (field.pattern && !new RegExp(field.pattern).test(value)) return field.hint + ".";
            return "";
        case "int":
        case "ref":
            if (!/^-?\d+$/.test(value)) return field.label + " must be a whole number.";
            if (Math.abs(Number(value)) > 2147483647) return field.label + " is too large.";
            return minMessage(field, Number(value));
        case "money":
            if (!/^-?\d+(\.\d+)?$/.test(value)) return field.label + " must be a number.";
            if (/\.\d{3,}$/.test(value.replace(/0+$/, ""))) return field.label + " can have at most 2 decimal places.";
            if (Number(value) >= 1e8) return field.label + " must be below 10,00,00,000.";
            return minMessage(field, Number(value));
        case "date":
            if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(value))) {
                return field.label + " must be a valid date.";
            }
            return "";
        case "enum":
            return field.options.includes(value) ? "" : "Choose one of the listed options.";
        default:
            return "";
    }
}

function toPayloadValue(field, control) {
    const value = control.value.trim();
    if (value === "") return null;
    return field.kind === "int" || field.kind === "ref" ? Number(value) : value;
}

// =========================
// FORM
// =========================

function buildControl(field, value, options, record, entity) {
    const id = "field-" + field.name;
    let control;
    if (field.kind === "enum" || field.kind === "ref") {
        control = h("select", { class: "input", id, name: field.name });
        if (field.kind === "ref") {
            control.append(h("option", { value: "" }, field.required ? "Select…" : "None"));
            for (const option of options[field.name] || []) {
                // An employee cannot be their own manager.
                if (record && field.ref === entity.key && option.value === record[entity.pk]) continue;
                control.append(h("option", { value: option.value },
                    option.note ? option.label + " — " + option.note : option.label));
            }
        } else {
            if (!field.required) control.append(h("option", { value: "" }, "Not set"));
            field.options.forEach((option) => control.append(h("option", { value: option }, option)));
        }
    } else {
        const type = field.kind === "date" ? "date"
            : field.kind === "text" ? (field.name === "email" ? "email" : field.name === "phone" ? "tel" : "text")
            : "number";
        control = h("input", {
            class: "input", id, name: field.name, type,
            maxlength: field.maxLength,
            placeholder: field.placeholder,
            step: field.kind === "int" ? "1" : field.kind === "money" ? "0.01" : null,
            min: field.min !== undefined && type === "number" ? field.min : null,
            inputmode: field.kind === "int" ? "numeric" : field.kind === "money" ? "decimal" : null,
            autocomplete: "off",
        });
    }
    control.value = value === null || value === undefined ? "" : String(value);
    if (field.required) control.setAttribute("aria-required", "true");
    return control;
}

export async function openRecordForm(entity, { record = null, preset = {}, onSaved } = {}) {
    const editing = record !== null;
    const refFields = entity.fields.filter((field) => field.kind === "ref");

    let options = {};
    try {
        const lists = await Promise.all(refFields.map((field) => api.options(field.ref)));
        refFields.forEach((field, index) => { options[field.name] = lists[index]; });
    } catch (error) {
        toastError(error, "Couldn't open the form");
        return;
    }

    const missing = refFields.find((field) => field.required && options[field.name].length === 0);
    if (missing) {
        const needed = entities.get(missing.ref);
        toast("Add a " + needed.singular.toLowerCase() + " first",
            "A " + entity.singular.toLowerCase() + " needs a " + needed.singular.toLowerCase() + " to link to.", "error");
        return;
    }

    const controls = new Map();
    const errorSlots = new Map();
    const alertSlot = h("div", { role: "alert" });
    const grid = h("div", { class: "form-grid" });

    for (const field of entity.fields) {
        const initial = editing ? record[field.name]
            : preset[field.name] !== undefined ? preset[field.name]
            : field.default !== undefined ? field.default
            : field.today ? today() : "";
        const control = buildControl(field, initial, options, record, entity);
        const errorSlot = h("div", { id: control.id + "-error", "aria-live": "polite" });
        const hint = field.hint ? h("div", { class: "field__hint", id: control.id + "-hint" }, field.hint) : null;
        control.setAttribute("aria-describedby", (hint ? hint.id + " " : "") + errorSlot.id);
        controls.set(field.name, control);
        errorSlots.set(field.name, errorSlot);
        grid.append(h("div", { class: "field" + (field.wide ? " field--wide" : ""), dataset: { field: field.name } },
            h("label", { class: "field__label", for: control.id }, field.label,
                field.required ? h("span", { class: "field__req", "aria-hidden": "true" }, "*") : null),
            control, hint, errorSlot));
    }

    const showError = (name, message) => {
        const control = controls.get(name);
        const slot = errorSlots.get(name);
        if (!control || !slot) return;
        clear(slot);
        if (message) {
            control.setAttribute("aria-invalid", "true");
            slot.append(h("div", { class: "field__error" }, icon("alert"), message));
        } else {
            control.removeAttribute("aria-invalid");
        }
    };

    const crossFieldErrors = () => {
        const errors = {};
        if (entity.key === "bookings") {
            const checkin = controls.get("checkin_date").value;
            const checkout = controls.get("checkout_date").value;
            if (checkin && checkout && checkout <= checkin) errors.checkout_date = "Check-out must be after check-in.";
        }
        return errors;
    };

    const validateField = (field) => {
        const message = validateValue(field, controls.get(field.name)) || crossFieldErrors()[field.name] || "";
        showError(field.name, message);
        return message;
    };

    for (const field of entity.fields) {
        const control = controls.get(field.name);
        control.addEventListener("blur", () => validateField(field));
        control.addEventListener("input", () => {
            if (control.getAttribute("aria-invalid") === "true") validateField(field);
        });
    }

    attachSuggestions(entity, controls, options, grid, record);

    const snapshot = () => JSON.stringify(entity.fields.map((field) => controls.get(field.name).value));
    const initialSnapshot = snapshot();

    const cancel = h("button", { class: "btn btn--ghost", type: "button" }, "Cancel");
    const submit = h("button", { class: "btn", type: "submit" }, editing ? "Save changes" : "Create " + entity.singular.toLowerCase());
    const form = h("form", { novalidate: true, id: "record-form" }, alertSlot, grid);
    submit.setAttribute("form", "record-form");

    let saving = false;
    let discardConfirmed = false;
    // One key per opened form: retries and double clicks can never create two rows.
    const idempotencyKey = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();

    const modal = openModal({
        title: editing ? "Edit " + entity.singular.toLowerCase() : "New " + entity.singular.toLowerCase(),
        subtitle: editing ? recordName(entity, record) : entity.blurb,
        body: form,
        footer: [cancel, submit],
        canClose: () => {
            if (saving) return false;
            if (discardConfirmed || snapshot() === initialSnapshot) return true;
            confirmAction({
                title: "Discard changes?",
                message: "You have unsaved changes in this form. They will be lost if you close it.",
                confirmLabel: "Discard", busyLabel: "Discarding…",
                action: async () => {},
            }).then((yes) => {
                if (yes) {
                    discardConfirmed = true;
                    modal.close();
                }
            });
            return false;
        },
    });
    cancel.addEventListener("click", () => modal.close());

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (saving) return;

        clear(alertSlot);
        const invalid = entity.fields.filter((field) => validateField(field) !== "");
        if (invalid.length > 0) {
            controls.get(invalid[0].name).focus();
            return;
        }

        const payload = {};
        entity.fields.forEach((field) => { payload[field.name] = toPayloadValue(field, controls.get(field.name)); });

        saving = true;
        cancel.disabled = true;
        setBusy(submit, true, "Saving…");
        try {
            const saved = editing
                ? await api.update(entity.key, record[entity.pk], payload)
                : await api.create(entity.key, payload, idempotencyKey);
            saving = false;
            modal.close(true);
            toast(editing ? "Changes saved" : entity.singular + " created", recordName(entity, saved) + " is saved in the database.");
            dataChanged(entity.key);
            if (onSaved) onSaved(saved);
        } catch (error) {
            saving = false;
            cancel.disabled = false;
            setBusy(submit, false);
            const fieldNames = Object.keys(error.fields || {}).filter((name) => controls.has(name));
            fieldNames.forEach((name) => showError(name, error.fields[name]));
            alertSlot.append(h("div", { class: "form-alert" }, icon("alert"), h("span", null, error.message)));
            if (fieldNames.length > 0) controls.get(fieldNames[0]).focus();
            else alertSlot.scrollIntoView({ block: "nearest" });
        }
    });

    const first = entity.fields.map((field) => controls.get(field.name)).find((control) => !control.disabled);
    if (first) first.focus();
}

// Helpful, optional suggestions computed from real data: the room charge for a
// stay, and the balance still due when recording a payment.
function attachSuggestions(entity, controls, options, grid, record) {
    const slotFor = (name) => {
        const slot = h("div", { "aria-live": "polite" });
        grid.querySelector('[data-field="' + name + '"]').append(slot);
        return slot;
    };
    const offer = (slot, text, amount, target) => {
        clear(slot).append(h("div", { class: "field__suggest" }, h("span", null, text),
            h("button", { class: "btn btn--soft btn--sm", type: "button", onclick: () => {
                target.value = amount;
                target.dispatchEvent(new Event("input"));
                target.focus();
            } }, "Use " + formatMoney(amount))));
    };

    if (entity.key === "bookings") {
        const slot = slotFor("total_amount");
        const refresh = () => {
            clear(slot);
            const room = (options.room_id || []).find((o) => String(o.value) === controls.get("room_id").value);
            const checkin = Date.parse(controls.get("checkin_date").value);
            const checkout = Date.parse(controls.get("checkout_date").value);
            const nights = Math.round((checkout - checkin) / 86400000);
            if (!room || !(nights > 0)) return;
            const amount = nights * Number(room.base_price);
            if (Number(controls.get("total_amount").value) === amount) return;
            offer(slot, nights + (nights === 1 ? " night" : " nights") + " × " + formatMoney(room.base_price) + " tariff",
                amount, controls.get("total_amount"));
        };
        ["room_id", "checkin_date", "checkout_date", "total_amount"].forEach((name) => {
            controls.get(name).addEventListener("change", refresh);
        });
        refresh();
    }

    if (entity.key === "payments" && !record) {
        const slot = slotFor("amount");
        let latest = 0;
        const refresh = async () => {
            clear(slot);
            const bookingId = controls.get("booking_id").value;
            const ticket = ++latest;
            if (!bookingId) return;
            try {
                const bill = await api.bill(bookingId);
                if (ticket !== latest) return;
                const balance = Number(bill.totals.balance);
                if (balance > 0) offer(slot, "Balance due on this booking", balance, controls.get("amount"));
                else clear(slot).append(h("div", { class: "field__suggest" }, "This booking is fully paid."));
            } catch {
                // The suggestion is optional; the form still works without it.
            }
        };
        controls.get("booking_id").addEventListener("change", refresh);
        refresh();
    }
}

// =========================
// DELETE
// =========================

export function deleteRecord(entity, row, onDeleted) {
    const name = recordName(entity, row);
    return confirmAction({
        title: "Delete " + entity.singular.toLowerCase() + "?",
        message: name + " will be permanently removed from the database. This cannot be undone.",
        confirmLabel: "Delete", busyLabel: "Deleting…",
        action: () => api.remove(entity.key, row[entity.pk]),
    }).then((deleted) => {
        if (deleted) {
            toast(entity.singular + " deleted", name + " was removed.");
            dataChanged(entity.key);
            if (onDeleted) onDeleted();
        }
        return deleted;
    });
}

// =========================
// DETAIL
// =========================

export async function openRecordDetail(entity, id) {
    let record;
    let bill = null;
    try {
        [record, bill] = await Promise.all([
            api.read(entity.key, id),
            entity.key === "bookings" ? api.bill(id) : Promise.resolve(null),
        ]);
    } catch (error) {
        toastError(error, "Couldn't open that record");
        if (error.status === 404) dataChanged(entity.key);
        return;
    }

    const shown = new Set();
    const details = h("dl", { class: "details" });
    const addDetail = (label, node) => details.append(h("div", null, h("dt", null, label), h("dd", null, node)));
    for (const column of entity.columns) {
        shown.add(column.name);
        addDetail(column.label === "No." ? "Record no." : column.label, formatCell(column.kind, record[column.name]));
    }
    for (const field of entity.fields) {
        if (shown.has(field.name) || field.kind === "ref") continue;
        const kind = field.kind === "money" ? "money" : field.kind === "date" ? "date" : "text";
        addDetail(field.label.replace(" (₹)", ""), formatCell(kind, record[field.name]));
    }

    const body = h("div", null, details);
    const remove = h("button", { class: "btn btn--ghost spacer", type: "button" }, icon("trash"), "Delete");
    const edit = h("button", { class: "btn", type: "button" }, icon("edit"), "Edit");

    const modal = openModal({
        title: recordName(entity, record),
        subtitle: entity.singular + " · record no. " + record[entity.pk],
        size: bill ? "lg" : null,
        body,
        footer: [remove, edit],
    });
    const reopen = () => {
        modal.close(true);
        openRecordDetail(entity, id);
    };

    remove.addEventListener("click", () => deleteRecord(entity, record, () => modal.close(true)));
    edit.addEventListener("click", () => {
        modal.close(true);
        openRecordForm(entity, { record, onSaved: () => openRecordDetail(entity, id) });
    });

    if (bill) body.append(renderBill(record, bill, reopen, modal));
}

function renderBill(booking, bill, reopen, modal) {
    const section = (title, rows, columns, emptyText) => h("div", { class: "bill__section" },
        h("h3", null, title),
        rows.length === 0 ? h("p", { class: "field__hint" }, emptyText)
            : h("table", { class: "mini" },
                h("thead", null, h("tr", null, columns.map((c) => h("th", { class: c.num ? "is-num" : null, scope: "col" }, c.label)))),
                h("tbody", null, rows.map((row) => h("tr", null, columns.map((c) =>
                    h("td", { class: c.num ? "is-num" : null }, c.render(row))))))));

    const setStatus = async (button, status, busyLabel) => {
        setBusy(button, true, busyLabel);
        try {
            const payload = {};
            entities.get("bookings").fields.forEach((field) => { payload[field.name] = booking[field.name]; });
            payload.booking_status = status;
            await api.update("bookings", booking.booking_id, payload);
            toast("Booking updated", "Booking #" + booking.booking_id + " is now " + status + ".");
            dataChanged("bookings");
            reopen();
        } catch (error) {
            setBusy(button, false);
            toastError(error);
        }
    };

    const actions = h("div", { class: "bill__actions" });
    const statusButton = (label, status, busyLabel) => {
        const button = h("button", { class: "btn btn--soft btn--sm", type: "button" }, label);
        button.addEventListener("click", () => setStatus(button, status, busyLabel));
        actions.append(button);
    };
    if (booking.booking_status === "Confirmed") statusButton("Check in", "Checked-In", "Checking in…");
    if (booking.booking_status === "Checked-In") statusButton("Check out", "Checked-Out", "Checking out…");
    if (booking.booking_status === "Confirmed") statusButton("Cancel booking", "Cancelled", "Cancelling…");

    const addLinked = (label, entityKey) => actions.append(h("button", {
        class: "btn btn--ghost btn--sm", type: "button",
        onclick: () => {
            modal.close(true);
            openRecordForm(entities.get(entityKey), {
                preset: { booking_id: booking.booking_id },
                onSaved: () => openRecordDetail(entities.get("bookings"), booking.booking_id),
            });
        },
    }, icon("plus"), label));
    if (booking.booking_status !== "Cancelled") {
        addLinked("Record payment", "payments");
        addLinked("Add service", "service-requests");
    }

    const totals = bill.totals;
    return h("section", { class: "bill", "aria-label": "Bill" },
        section("Services used", bill.services, [
            { label: "Date", render: (r) => formatDate(r.request_date) },
            { label: "Service", render: (r) => r.service_name + " × " + r.quantity },
            { label: "Attended by", render: (r) => r.emp_name },
            { label: "Charge", num: true, render: (r) => formatMoney(r.line_total) },
        ], "No services have been requested on this booking."),
        section("Payments received", bill.payments, [
            { label: "Date", render: (r) => formatDate(r.pay_date) },
            { label: "Mode", render: (r) => r.pay_mode ? badge(r.pay_mode) : "—" },
            { label: "Amount", num: true, render: (r) => formatMoney(r.amount) },
        ], "No payments have been recorded yet."),
        h("div", { class: "bill__totals" },
            h("div", null, h("span", null, "Room charges"), h("span", { class: "num" }, formatMoney(totals.room))),
            h("div", null, h("span", null, "Services"), h("span", { class: "num" }, formatMoney(totals.services))),
            h("div", null, h("span", null, "Paid"), h("span", { class: "num" }, "− " + formatMoney(totals.paid))),
            h("div", { class: "is-total" },
                h("span", null, Number(totals.balance) < 0 ? "Credit" : "Balance due"),
                h("span", { class: "num" }, formatMoney(Math.abs(Number(totals.balance)))))),
        actions);
}
