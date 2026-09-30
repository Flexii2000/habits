// Zugriff auf die coHabit-API. Das Cookie (cohabit_token, health_token oder
// fh_private) traegt die Anmeldung; der Dienst antwortet bei Fehlern mit
// {"message": "..."} - genau diese Meldung zeigt die Oberflaeche.
export const API = '/cohabit/api';

export class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

let onUnauthorized = () => {};

export function setUnauthorizedHandler(fn) {
    onUnauthorized = fn;
}

/**
 * `body` geht als JSON raus, `form` als multipart. `quiet401`: ein 401 ist hier
 * eine erwartete Antwort (Start ohne Zugang) und kein Abmelden mitten in der
 * Sitzung.
 */
export async function api(path, { method = 'GET', body, form, headers = {}, signal, quiet401 = false } = {}) {
    const options = { method, credentials: 'same-origin', headers: { Accept: 'application/json', ...headers }, signal };
    if (form) {
        options.body = form;
    } else if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
    }
    let res;
    try {
        res = await fetch(API + path, options);
    } catch (err) {
        if (err.name === 'AbortError') throw err;
        throw new ApiError('Keine Verbindung zum Server.', 0);
    }
    if (!res.ok) {
        let message = res.status === 413 ? 'Die Datei ist zu groß.' : `Fehler ${res.status}`;
        try {
            const data = await res.json();
            if (data && data.message) message = data.message;
        } catch (e) { /* Fehlerkoerper ist nicht immer JSON - dann bleibt der Status. */ }
        if (res.status === 401 && !quiet401) onUnauthorized();
        throw new ApiError(message, res.status);
    }
    if (res.status === 204) return null;
    const type = res.headers.get('Content-Type') || '';
    if (type.includes('json')) return res.json();
    return res;
}

export const get = (path, opts) => api(path, opts);
export const post = (path, body, opts = {}) => api(path, { ...opts, method: 'POST', body: body === undefined ? {} : body });
export const put = (path, body, opts = {}) => api(path, { ...opts, method: 'PUT', body });
export const del = (path, body, opts = {}) => api(path, { ...opts, method: 'DELETE', body });

export function photoUrl(id, size = 'thumb') {
    return `${API}/photos/${encodeURIComponent(id)}?size=${size}`;
}

export const enc = encodeURIComponent;
