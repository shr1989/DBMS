// The list page used by every table: search, filters, sorting, pagination,
// table/card views and the row actions. All data comes from the backend API.

import { api } from "./api.js";
import { onDataChanged, recordName } from "./state.js";
import { openRecordForm, openRecordDetail, deleteRecord } from "./forms.js";
import {
    h, icon, clear, debounce, formatCell, plainCell, isNumeric, emptyState, errorState, toastError, formatNumber,
} from "./ui.js";

const PAGE_SIZES = [5, 10, 25, 50];

export function renderEntity(container, entity, query) {
    const filterNames = entity.filters.map((filter) => filter.name);
    const savedView = localStorage.getItem("view:" + entity.key);
    const state = {
        search: query.get("search") || "",
        sort: query.get("sort") || entity.defaultSort,
        dir: query.get("dir") || entity.defaultDir,
        page: Number(query.get("page")) || 1,
        pageSize: PAGE_SIZES.includes(Number(query.get("pageSize"))) ? Number(query.get("pageSize")) : 10,
        view: savedView || (window.innerWidth < 720 ? "cards" : "table"),
        filters: Object.fromEntries(filterNames.map((name) => [name, query.get(name) || ""])),
    };

    let ticket = 0;
    let loadedOnce = false;
    let disposed = false;

    // ---------- masthead ----------
    const createButton = h("button", { class: "btn", type: "button", onclick: () => openRecordForm(entity) },
        icon("plus"), "New " + entity.singular.toLowerCase());
    const masthead = h("header", { class: "masthead" },
        h("div", null,
            h("p", { class: "masthead__eyebrow" }, entity.group),
            h("h1", null, entity.plural),
            h("p", { class: "masthead__blurb" }, entity.blurb)),
        h("div", { class: "masthead__actions" }, createButton));

    // ---------- toolbar ----------
    const searchInput = h("input", {
        class: "input", type: "search", placeholder: "Search " + entity.plural.toLowerCase() + "…",
        "aria-label": "Search " + entity.plural.toLowerCase(), autocomplete: "off", maxlength: "100",
    });
    searchInput.value = state.search;
    const clearSearch = h("button", { class: "icon-btn search__clear", type: "button", "aria-label": "Clear search" }, icon("close"));
    const applySearch = debounce(() => {
        state.search = searchInput.value.trim();
        state.page = 1;
        load();
    }, 280);
    searchInput.addEventListener("input", () => {
        clearSearch.hidden = searchInput.value === "";
        applySearch();
    });
    clearSearch.hidden = state.search === "";
    clearSearch.addEventListener("click", () => {
        searchInput.value = "";
        clearSearch.hidden = true;
        state.search = "";
        state.page = 1;
        load();
        searchInput.focus();
    });

    const filterControls = new Map();
    for (const filter of entity.filters) {
        const select = h("select", { class: "input", "aria-label": "Filter by " + filter.label.toLowerCase() });
        select.append(h("option", { value: "" }, "Any " + filter.label.toLowerCase()));
        select.addEventListener("change", () => {
            state.filters[filter.name] = select.value;
            state.page = 1;
            load();
        });
        filterControls.set(filter.name, select);
    }

    const fillFilters = async () => {
        for (const filter of entity.filters) {
            const select = filterControls.get(filter.name);
            let options = [];
            try {
                if (filter.kind === "enum") options = filter.options.map((value) => ({ value, label: value }));
                else if (filter.kind === "ref") options = await api.options(filter.ref);
                else options = (await api.distinct(entity.key, filter.name)).map((value) => ({ value, label: value }));
            } catch {
                continue; // the list itself reports connection problems
            }
            if (disposed) return;
            const current = state.filters[filter.name];
            while (select.options.length > 1) select.remove(1);
            options.forEach((option) => select.append(h("option", { value: option.value }, option.label)));
            select.value = current;
            if (select.value !== current) select.value = "";
        }
    };

    const viewButtons = {
        table: h("button", { type: "button", "aria-label": "Table view", title: "Table view" }, icon("table")),
        cards: h("button", { type: "button", "aria-label": "Card view", title: "Card view" }, icon("grid")),
    };
    const syncViewButtons = () => Object.entries(viewButtons).forEach(([name, button]) => {
        button.setAttribute("aria-pressed", String(state.view === name));
    });
    Object.entries(viewButtons).forEach(([name, button]) => button.addEventListener("click", () => {
        state.view = name;
        localStorage.setItem("view:" + entity.key, name);
        syncViewButtons();
        renderRows();
    }));
    syncViewButtons();

    const count = h("span", { class: "toolbar__count", "aria-live": "polite" });
    const toolbar = h("div", { class: "toolbar", role: "search" },
        h("div", { class: "search toolbar__grow" }, icon("search"), searchInput, clearSearch),
        [...filterControls.values()],
        h("div", { class: "segmented", role: "group", "aria-label": "View" }, viewButtons.table, viewButtons.cards),
        count);

    // ---------- results ----------
    const results = h("div", null);
    const pager = h("div", { class: "pager" });
    const card = h("section", { class: "card table-card", "aria-label": entity.plural }, results, pager);

    clear(container).append(masthead, toolbar, card);

    let rows = [];
    let meta = { total: 0, page: 1, pages: 1, pageSize: state.pageSize };

    const hasCriteria = () => state.search !== "" || Object.values(state.filters).some((value) => value !== "");

    const syncUrl = () => {
        const params = new URLSearchParams();
        if (state.search) params.set("search", state.search);
        Object.entries(state.filters).forEach(([name, value]) => { if (value) params.set(name, value); });
        if (state.sort !== entity.defaultSort || state.dir !== entity.defaultDir) {
            params.set("sort", state.sort);
            params.set("dir", state.dir);
        }
        if (state.page > 1) params.set("page", state.page);
        if (state.pageSize !== 10) params.set("pageSize", state.pageSize);
        const text = params.toString();
        history.replaceState(null, "", "#/" + entity.key + (text ? "?" + text : ""));
    };

    async function load() {
        const mine = ++ticket;
        syncUrl();
        if (loadedOnce) results.classList.add("is-refreshing");
        else renderSkeleton();
        try {
            const response = await api.list(entity.key, {
                search: state.search, sort: state.sort, dir: state.dir,
                page: state.page, pageSize: state.pageSize, ...state.filters,
            });
            if (mine !== ticket || disposed) return;
            rows = response.data;
            meta = response.meta;
            state.page = meta.page;
            loadedOnce = true;
            results.classList.remove("is-refreshing");
            renderRows();
        } catch (error) {
            if (mine !== ticket || disposed) return;
            loadedOnce = false;
            results.classList.remove("is-refreshing");
            count.textContent = "";
            clear(pager).hidden = true;
            clear(results).append(errorState(error, load));
        }
    }

    function renderSkeleton() {
        pager.hidden = true;
        const table = h("table", { class: "data", "aria-hidden": "true" },
            h("thead", null, h("tr", null, entity.columns.map((c) => h("th", null, h("span", null, c.label))))),
            h("tbody", null, Array.from({ length: 6 }, () =>
                h("tr", null, entity.columns.map(() => h("td", null, h("span", { class: "skeleton" })))))));
        clear(results).append(h("div", { class: "table-scroll" }, table),
            h("p", { class: "visually-hidden", role: "status" }, "Loading " + entity.plural.toLowerCase() + "…"));
    }

    const open = (row) => openRecordDetail(entity, row[entity.pk]);

    const editRow = async (row, button) => {
        button.disabled = true;
        try {
            // Always edit the latest saved version, not the copy shown in the list.
            const fresh = await api.read(entity.key, row[entity.pk]);
            openRecordForm(entity, { record: fresh });
        } catch (error) {
            toastError(error, "Couldn't open that record");
            if (error.status === 404) load();
        } finally {
            button.disabled = false;
        }
    };

    const rowActions = (row) => {
        const name = recordName(entity, row);
        const edit = h("button", { class: "icon-btn", type: "button", "aria-label": "Edit " + name, title: "Edit" }, icon("edit"));
        edit.addEventListener("click", (event) => { event.stopPropagation(); editRow(row, edit); });
        const remove = h("button", { class: "icon-btn icon-btn--danger", type: "button", "aria-label": "Delete " + name, title: "Delete" }, icon("trash"));
        remove.addEventListener("click", (event) => { event.stopPropagation(); deleteRecord(entity, row); });
        const view = h("button", { class: "icon-btn", type: "button", "aria-label": "View " + name, title: "View" }, icon("eye"));
        view.addEventListener("click", (event) => { event.stopPropagation(); open(row); });
        return [view, edit, remove];
    };

    function renderRows() {
        count.textContent = formatNumber(meta.total) + " " + (meta.total === 1 ? entity.singular : entity.plural).toLowerCase()
            + (hasCriteria() ? " found" : "");

        if (rows.length === 0) {
            pager.hidden = true;
            clear(results).append(hasCriteria()
                ? emptyState({
                    title: "No matches",
                    message: "Nothing in " + entity.plural.toLowerCase() + " matches your search or filters.",
                    actions: h("button", { class: "btn btn--ghost", type: "button", onclick: resetCriteria }, "Clear search and filters"),
                })
                : emptyState({
                    title: "No " + entity.plural.toLowerCase() + " yet",
                    message: "Records you add here are saved to the hotel database straight away.",
                    actions: h("button", { class: "btn", type: "button", onclick: () => openRecordForm(entity) },
                        icon("plus"), "Add the first " + entity.singular.toLowerCase()),
                }));
            return;
        }

        clear(results).append(state.view === "cards" ? renderCards() : renderTable());
        renderPager();
    }

    function renderTable() {
        const head = h("tr", null, entity.columns.map((column) => {
            const active = state.sort === column.name;
            const button = h("button", { class: "sort-btn", type: "button" }, column.label, icon("arrow"));
            button.addEventListener("click", () => {
                state.dir = active && state.dir === "asc" ? "desc" : "asc";
                state.sort = column.name;
                state.page = 1;
                load();
            });
            return h("th", {
                scope: "col", class: isNumeric(column.kind) ? "is-num" : null,
                "aria-sort": active ? (state.dir === "asc" ? "ascending" : "descending") : "none",
            }, button);
        }), h("th", { scope: "col" }, h("span", { class: "visually-hidden" }, "Actions")));

        const body = h("tbody", null, rows.map((row) => {
            const tr = h("tr", null, entity.columns.map((column) => {
                const content = column.name === entity.card.title
                    ? h("button", { class: "row-open", type: "button", onclick: (event) => { event.stopPropagation(); open(row); } },
                        plainCell(column.kind === "strong" ? "text" : column.kind, row[column.name]))
                    : formatCell(column.kind, row[column.name]);
                return h("td", { class: isNumeric(column.kind) ? "is-num" : null }, content);
            }), h("td", { class: "is-actions" }, rowActions(row)));
            tr.addEventListener("click", () => open(row));
            return tr;
        }));

        return h("div", { class: "table-scroll" }, h("table", { class: "data" },
            h("caption", { class: "visually-hidden" }, entity.plural + ", sorted by " + state.sort.replace(/_/g, " ") + " " + state.dir + "ending"),
            h("thead", null, head), body));
    }

    function renderCards() {
        const column = (name) => entity.columns.find((c) => c.name === name);
        const titleKind = column(entity.card.title).kind;
        return h("div", { class: "tiles" }, rows.map((row) => {
            const figure = column(entity.card.figure);
            const tile = h("article", { class: "tile" },
                h("div", { class: "tile__top" },
                    h("button", { class: "row-open tile__title", type: "button", onclick: (event) => { event.stopPropagation(); open(row); } },
                        plainCell(titleKind === "strong" ? "text" : titleKind, row[entity.card.title])),
                    entity.card.badge && row[entity.card.badge] ? formatCell("badge", row[entity.card.badge]) : null),
                h("div", { class: "tile__lines" }, entity.card.lines.map((name) => {
                    const c = column(name);
                    return h("span", null, h("b", null, c.label), plainCell(c.kind, row[name]));
                })),
                h("div", { class: "tile__foot" },
                    figure ? h("div", { class: "tile__figure" }, h("small", null, figure.label), plainCell(figure.kind, row[figure.name])) : h("span"),
                    h("div", null, rowActions(row).slice(1))));
            tile.addEventListener("click", () => open(row));
            return tile;
        }));
    }

    function renderPager() {
        pager.hidden = false;
        const from = (meta.page - 1) * meta.pageSize + 1;
        const to = Math.min(meta.total, meta.page * meta.pageSize);
        const sizeSelect = h("select", { class: "input", "aria-label": "Rows per page" },
            PAGE_SIZES.map((size) => h("option", { value: size }, size + " per page")));
        sizeSelect.value = String(state.pageSize);
        sizeSelect.addEventListener("change", () => {
            state.pageSize = Number(sizeSelect.value);
            state.page = 1;
            load();
        });
        const go = (delta) => () => {
            state.page = meta.page + delta;
            load();
        };
        const prev = h("button", { class: "icon-btn", type: "button", "aria-label": "Previous page", onclick: go(-1) }, icon("prev"));
        const next = h("button", { class: "icon-btn", type: "button", "aria-label": "Next page", onclick: go(1) }, icon("next"));
        prev.disabled = meta.page <= 1;
        next.disabled = meta.page >= meta.pages;
        clear(pager).append(
            h("span", null, "Showing " + from + "–" + to + " of " + formatNumber(meta.total)),
            h("div", { class: "pager__controls" }, sizeSelect, prev,
                h("span", { class: "num" }, "Page " + meta.page + " of " + meta.pages), next));
    }

    function resetCriteria() {
        state.search = "";
        searchInput.value = "";
        clearSearch.hidden = true;
        filterNames.forEach((name) => {
            state.filters[name] = "";
            filterControls.get(name).value = "";
        });
        state.page = 1;
        load();
    }

    const stopListening = onDataChanged(() => {
        load();
        fillFilters();
    });

    load();
    fillFilters();

    return () => {
        disposed = true;
        stopListening();
    };
}
