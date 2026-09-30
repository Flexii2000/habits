// coHabit im Browser: eine Seite, die Ansicht steht in der Adresse (Vertrag 4:
// /cohabit/, /timeline, /neu, /statistik, /profil, /freunde, /c/{id}, /c/{id}/chat,
// /join/{code}). Jede Ansicht ist ein Modul mit mount(root, params, ctx), das
// ein Objekt mit unmount() zurueckgibt - dort enden ihre Timer.
import { api, setUnauthorizedHandler } from './api.js';
import { state } from './state.js';
import { $, h, closeAllDialogs, showError, fill } from './dom.js';
import { errorState, loadingState } from './ui.js';

export const BASE = '/cohabit';

const ROUTES = [
    { re: /^\/$/, view: 'today', tab: 'today' },
    { re: /^\/timeline$/, view: 'timeline', tab: 'timeline' },
    { re: /^\/neu$/, view: 'create', tab: 'new', bare: true },
    { re: /^\/statistik$/, view: 'stats', tab: 'stats' },
    { re: /^\/profil$/, view: 'profile', tab: 'profile' },
    { re: /^\/profil\/benachrichtigungen$/, view: 'notifications', tab: 'profile' },
    { re: /^\/profil\/archiv$/, view: 'archive', tab: 'profile' },
    { re: /^\/profil\/app$/, view: 'applinks', tab: 'profile' },
    { re: /^\/freunde$/, view: 'friends', tab: 'profile' },
    { re: /^\/c\/([^/]+)$/, view: 'detail', bare: true, params: m => ({ id: m[1], tab: 'overview' }) },
    { re: /^\/c\/([^/]+)\/chat$/, view: 'detail', bare: true, params: m => ({ id: m[1], tab: 'chat' }) },
    { re: /^\/c\/([^/]+)\/checkin$/, view: 'detail', bare: true, params: m => ({ id: m[1], tab: 'overview', checkin: true }) },
    { re: /^\/c\/([^/]+)\/bearbeiten$/, view: 'edit', bare: true, params: m => ({ id: m[1] }) },
    { re: /^\/join\/([^/]+)$/, view: 'join', bare: true, public: true, params: m => ({ code: m[1] }) },
];

const VIEWS = {
    today: () => import('./views/today.js'),
    timeline: () => import('./views/timeline.js'),
    create: () => import('./views/create.js'),
    edit: () => import('./views/create.js').then(m => ({ mount: m.mountEdit })),
    stats: () => import('./views/stats.js'),
    profile: () => import('./views/profile.js'),
    notifications: () => import('./views/profile.js').then(m => ({ mount: m.mountNotifications })),
    archive: () => import('./views/profile.js').then(m => ({ mount: m.mountArchive })),
    applinks: () => import('./views/applinks.js'),
    friends: () => import('./views/friends.js'),
    detail: () => import('./views/detail.js'),
    join: () => import('./views/join.js'),
    start: () => import('./views/start.js'),
    missing: () => Promise.resolve({ mount: mountMissing }),
};

function matchRoute(pathname) {
    let rest = pathname.startsWith(BASE) ? pathname.slice(BASE.length) : pathname;
    if (!rest.startsWith('/')) rest = `/${rest}`;
    if (rest.length > 1 && rest.endsWith('/')) rest = rest.slice(0, -1);
    for (const route of ROUTES) {
        const m = rest.match(route.re);
        if (m) {
            const params = route.params ? route.params(m.map(part => (part ? decodeURIComponent(part) : part))) : {};
            return { ...route, params, path: rest };
        }
    }
    return { view: 'missing', tab: null, params: {}, path: rest };
}

let current = null;
let renderToken = 0;

/** Wechselt die Ansicht. `replace`: ohne neuen Eintrag im Verlauf (Tabs, Weiterleitungen). */
export function navigate(path, { replace = false, state: extra } = {}) {
    const target = path.startsWith(BASE) ? path : BASE + path;
    const url = new URL(target, location.origin);
    if (location.pathname + location.search !== url.pathname + url.search || extra) {
        saveScroll();
        history[replace ? 'replaceState' : 'pushState']({ app: true, ...extra }, '', url.pathname + url.search);
    }
    render();
}

let pendingReplace = null;

/**
 * Springt `stepsBack` Eintraege zurueck und ersetzt den erreichten Eintrag
 * durch `path` - so verschwinden die Schritte des Anlegens aus dem Verlauf,
 * und Zurueck von der neuen Detailseite fuehrt dorthin, woher man kam.
 */
export function replaceFlow(stepsBack, path) {
    if (stepsBack <= 0) {
        navigate(path, { replace: true });
        return;
    }
    pendingReplace = path.startsWith(BASE) ? path : BASE + path;
    history.go(-stepsBack);
}

/** Zurueck innerhalb der Seite; kam man von aussen, zur Startseite statt hinaus. */
export function goBack(fallback = '/') {
    if (history.state && history.state.app && history.length > 1) history.back();
    else navigate(fallback, { replace: true });
}

function saveScroll() {
    try {
        history.replaceState({ ...(history.state || {}), scroll: window.scrollY }, '');
    } catch (e) { /* Verlauf gesperrt - dann ohne */ }
}

function updateNav(route) {
    const nav = $('nav');
    nav.hidden = !state.me;
    document.body.classList.toggle('bare', !!route.bare);
    nav.querySelectorAll('[data-tab]').forEach(link => {
        if (link.dataset.tab === route.tab) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
}

export async function render() {
    const token = ++renderToken;
    closeAllDialogs();
    const route = matchRoute(location.pathname);
    if (route.path === '/rechtliches') {
        location.replace(`${BASE}/rechtliches.html`);
        return;
    }
    if (current && current.instance && current.instance.unmount) {
        try { current.instance.unmount(); } catch (e) { /* Ansicht war schon weg */ }
    }
    current = null;

    let viewName = route.view;
    let params = route.params;
    if (!state.me && !route.public) {
        viewName = 'start';
        params = { invalid: new URLSearchParams(location.search).get('setup') === 'invalid' };
    }
    updateNav(state.me ? route : { bare: true });

    const root = $('view');
    root.className = `view view-${viewName}`;
    fill(root, loadingState());
    let module;
    try {
        module = await VIEWS[viewName]();
    } catch (err) {
        fill(root, errorState('Die Seite konnte nicht geladen werden.', () => location.reload()));
        return;
    }
    if (token !== renderToken) return;
    const container = h('div', { class: 'view-inner' });
    fill(root, container);
    const ctx = {
        alive: () => token === renderToken,
        restoreScroll: () => {
            const y = history.state && history.state.scroll;
            if (typeof y === 'number') window.scrollTo(0, y);
        },
    };
    window.scrollTo(0, 0);
    try {
        const instance = await module.mount(container, params, ctx);
        if (token === renderToken) current = { route, instance };
        else if (instance && instance.unmount) instance.unmount();
    } catch (err) {
        showError(err);
    }
}

function mountMissing(root) {
    root.append(h('div', { class: 'page' },
        h('div', { class: 'empty' },
            h('p', null, 'Diese Seite gibt es nicht.'),
            h('a', { class: 'btn primary', href: `${BASE}/`, 'data-nav': '' }, 'Zu Heute'))));
}

/** Nach Anmelden, Beitritt oder Abmelden: wer bin ich jetzt? */
export async function refreshMe() {
    try {
        state.me = await api('/me', { quiet401: true });
    } catch (err) {
        if (err.status === 401) state.me = null;
        else throw err;
    }
    return state.me;
}

function bind() {
    // Links innerhalb der Seite ohne Neuladen - ausser mit Zusatztaste (neuer Tab).
    document.addEventListener('click', event => {
        const link = event.target.closest('a[data-nav]');
        if (!link || event.defaultPrevented || event.button !== 0
            || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        const url = new URL(link.href, location.href);
        if (url.origin !== location.origin) return;
        event.preventDefault();
        navigate(url.pathname + url.search);
    });
    window.addEventListener('popstate', () => {
        if (pendingReplace) {
            const target = pendingReplace;
            pendingReplace = null;
            history.replaceState({ app: true }, '', target);
        }
        render();
    });
    if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
    setUnauthorizedHandler(() => {
        // Anmeldung weg (widerrufen, Konto geloescht): zurueck zum Start.
        if (!state.me) return;
        state.me = null;
        state.cache.clear();
        render();
    });
}

async function init() {
    bind();
    try {
        await refreshMe();
    } catch (err) {
        $('view').replaceChildren(errorState(err.message, () => location.reload()));
        return;
    }
    render();
}

init();
