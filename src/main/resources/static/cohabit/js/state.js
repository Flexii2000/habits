// Geteilter Zustand der Seite: wer angemeldet ist, und zuletzt geladene Stände,
// damit ein Zurueck sofort etwas zeigt, bevor der frische Stand da ist.
export const state = {
    me: null,
    cache: new Map(),
};

export function cached(key) {
    return state.cache.get(key);
}

export function remember(key, value) {
    state.cache.set(key, value);
    return value;
}

export function forget(prefix) {
    for (const key of [...state.cache.keys()]) {
        if (key.startsWith(prefix)) state.cache.delete(key);
    }
}
