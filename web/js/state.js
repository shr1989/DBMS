// Shared application state: the entity descriptions loaded from /api/meta and a
// tiny change bus so any open view reloads after a record is saved or deleted.

export const entities = new Map();

export function setEntities(list) {
    entities.clear();
    list.forEach((entity) => entities.set(entity.key, entity));
}

const changeListeners = new Set();

export function onDataChanged(listener) {
    changeListeners.add(listener);
    return () => changeListeners.delete(listener);
}

export function dataChanged(entityKey) {
    changeListeners.forEach((listener) => listener(entityKey));
}

// A short human name for a record, e.g. "Rahul Sharma", "Room 101" or "Booking #3".
export function recordName(entity, row) {
    const column = entity.columns.find((c) => c.name === entity.card.title);
    const value = row[entity.card.title];
    if (entity.key === "bookings" || entity.key === "payments" || entity.key === "service-requests") {
        return entity.singular + " #" + row[entity.pk];
    }
    if (value === null || value === undefined) return entity.singular + " #" + row[entity.pk];
    return column && column.kind === "room" ? "Room " + value : String(value);
}
