// DOM-Helfer. Nutzertext landet immer als Textknoten im Dokument, nie als HTML -
// Namen, Captions und Chatnachrichten kommen von anderen Personen.
import { ICONS } from './icons.js';

export const $ = id => document.getElementById(id);

export function h(tag, props, ...children) {
    const el = document.createElement(tag);
    if (props) {
        for (const [key, value] of Object.entries(props)) {
            if (value == null || value === false) continue;
            if (key === 'class') el.className = value;
            else if (key === 'dataset') Object.assign(el.dataset, value);
            else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
            else if (key === 'value') el.value = value;
            else if (key === 'checked') el.checked = !!value;
            else if (value === true) el.setAttribute(key, '');
            else el.setAttribute(key, String(value));
        }
    }
    append(el, children);
    return el;
}

/**
 * Ersetzt den Inhalt eines Elements. Anders als replaceChildren() werden
 * null, false und Arrays verarbeitet - replaceChildren(null) schriebe „null".
 */
export function fill(el, ...children) {
    el.replaceChildren();
    return append(el, children);
}

export function append(el, children) {
    for (const child of children.flat(Infinity)) {
        if (child == null || child === false) continue;
        el.append(child instanceof Node ? child : String(child));
    }
    return el;
}

const template = document.createElement('template');

export function icon(name, className) {
    template.innerHTML = ICONS[name] || ICONS.more;
    const el = template.content.firstElementChild;
    el.classList.add('icon');
    if (className) el.classList.add(...className.split(' '));
    return el;
}

/** Knopf nur mit Symbol - die Beschriftung geht an Screenreader. */
export function iconButton(name, label, onclick, className = 'circle-btn') {
    return h('button', { type: 'button', class: className, 'aria-label': label, title: label, onclick }, icon(name));
}

// --- Rueckmeldung --------------------------------------------------------------

let toastTimer = null;

/**
 * Kurze Meldung unten. Als Popover, damit sie auch ueber einem offenen
 * modalen Dialog erscheint: der liegt in der obersten Ebene, und nur ein
 * danach geoeffnetes Popover kommt darueber.
 */
export function toast(text, kind = 'ok') {
    const el = $('toast');
    if (!el) return;
    el.textContent = text;
    el.className = `toast ${kind}`;
    if (el.showPopover) {
        try { el.hidePopover(); } catch (e) { /* war nicht offen */ }
        try { el.showPopover(); } catch (e) { /* ohne Popover-Unterstuetzung bleibt es ein fixes Element */ }
    }
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        el.textContent = '';
        if (el.hidePopover) {
            try { el.hidePopover(); } catch (e) { /* schon zu */ }
        }
    }, kind === 'err' ? 6000 : 3200);
}

export function showError(err) {
    if (err && err.name === 'AbortError') return;
    toast(err && err.message ? err.message : 'Etwas ist schiefgegangen.', 'err');
}

// --- Dialoge -------------------------------------------------------------------

function outside(el, event) {
    const box = el.getBoundingClientRect();
    return event.clientX < box.left || event.clientX > box.right
        || event.clientY < box.top || event.clientY > box.bottom;
}

/**
 * Oeffnet einen modalen Dialog. `kind`: 'sheet' (auf dem Handy ein Blatt von
 * unten) oder 'modal' (mittig). Klick auf den Hintergrund schliesst - geprueft
 * ueber die Koordinaten und nur, wenn der Druck dort auch begann: sonst schloesse
 * ein Markieren von Text, das ausserhalb endet, den Dialog.
 */
export function openDialog(content, { kind = 'sheet', className = '', label, onClose, dismissable = true } = {}) {
    const dialog = h('dialog', { class: `dlg ${kind} ${className}`, 'aria-label': label });
    append(dialog, [content]);
    // Ohne ausdruecklichen Fokuswunsch bekommt der Dialog selbst den Fokus -
    // sonst traegt sein erster Knopf beim Oeffnen einen Fokusring.
    if (!dialog.querySelector('[autofocus]')) dialog.setAttribute('autofocus', '');
    document.body.append(dialog);
    let pressedOutside = false;
    dialog.addEventListener('pointerdown', event => {
        pressedOutside = event.target === dialog && outside(dialog, event);
    });
    dialog.addEventListener('click', event => {
        if (dismissable && pressedOutside && event.target === dialog && outside(dialog, event)) dialog.close();
        pressedOutside = false;
    });
    if (!dismissable) dialog.addEventListener('cancel', event => event.preventDefault());
    dialog.addEventListener('close', () => {
        dialog.remove();
        if (onClose) onClose(dialog.returnValue);
    });
    dialog.showModal();
    return dialog;
}

export function closeAllDialogs() {
    document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
}

/** Kopf eines Blatts: Griff, Titel, optional Unterzeile, Schliessen. */
export function sheetHead(title, subtitle, dialogRef) {
    return [
        h('div', { class: 'sheet-handle', 'aria-hidden': 'true' }),
        h('div', { class: 'sheet-head' },
            h('div', { class: 'sheet-titles' },
                h('h2', { class: 'sheet-title' }, title),
                subtitle ? h('p', { class: 'sheet-sub' }, subtitle) : null),
            h('button', {
                type: 'button', class: 'close-btn', 'aria-label': 'Schließen',
                onclick: () => dialogRef.current && dialogRef.current.close(),
            }, icon('close'))),
    ];
}

/** Rueckfrage mit zwei Knoepfen; loest mit true (bestaetigt) oder false auf. */
export function confirmDialog({ title, text, confirm = 'OK', cancel = 'Abbrechen', danger = false }) {
    return new Promise(resolve => {
        let result = false;
        const ref = {};
        ref.current = openDialog(h('div', { class: 'confirm' },
            h('h2', { class: 'confirm-title' }, title),
            text ? h('p', { class: 'confirm-text' }, text) : null,
            h('div', { class: 'btn-row' },
                h('button', { type: 'button', class: 'btn outline', autofocus: true, onclick: () => ref.current.close() }, cancel),
                h('button', {
                    type: 'button', class: `btn ${danger ? 'danger' : 'primary'}`,
                    onclick: () => { result = true; ref.current.close(); },
                }, confirm))),
        { kind: 'modal', label: title, onClose: () => resolve(result) });
    });
}

/**
 * Auswahlliste als Blatt (Menue "…", Aktionen an Nachrichten). Jede Aktion:
 * { label, icon, danger, onSelect }. Das Blatt schliesst vor der Aktion, damit
 * eine Rueckfrage der Aktion nicht hinter ihm haengt.
 */
export function actionSheet(title, actions, extra) {
    const ref = {};
    const list = h('div', { class: 'action-list' }, actions.filter(Boolean).map(action =>
        h('button', {
            type: 'button', class: `action-item${action.danger ? ' danger' : ''}`,
            onclick: () => { ref.current.close(); action.onSelect(); },
        }, action.icon ? icon(action.icon) : null, h('span', null, action.label))));
    ref.current = openDialog([
        ...sheetHead(title, null, ref),
        h('div', { class: 'sheet-body' }, extra || null, list),
    ], { kind: 'sheet', className: 'actions', label: title });
    return ref.current;
}

export function clear(el) {
    el.replaceChildren();
    return el;
}

export function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const hex = [...b].map(x => x.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Laesst ein Textfeld mit seinem Inhalt wachsen (erst auf auto, sonst schrumpft es nie). */
export function autoGrow(field) {
    field.style.height = 'auto';
    field.style.height = `${field.scrollHeight}px`;
}

/** Einstellung je Geraet; privates Fenster oder gesperrter Speicher darf nichts kaputt machen. */
export const prefs = {
    get(key, fallback) {
        try {
            const value = localStorage.getItem(`cohabit.${key}`);
            return value == null ? fallback : value;
        } catch (e) {
            return fallback;
        }
    },
    set(key, value) {
        try { localStorage.setItem(`cohabit.${key}`, value); } catch (e) { /* dann eben nur fuer diese Sitzung */ }
    },
};

/**
 * Wiederholt `fn` alle `ms`, solange die Seite sichtbar ist; beim Zurueckkommen
 * sofort. Ein laufender Abruf wird nicht doppelt gestartet.
 */
export function poll(fn, ms) {
    let timer = null;
    let stopped = false;
    let running = false;
    const run = async () => {
        if (stopped || running || document.visibilityState !== 'visible') return;
        running = true;
        try { await fn(); } catch (e) { /* naechster Versuch im naechsten Takt */ }
        running = false;
    };
    const schedule = () => {
        clearTimeout(timer);
        timer = setTimeout(async () => { await run(); if (!stopped) schedule(); }, ms);
    };
    const onVisibility = () => {
        if (document.visibilityState === 'visible') { run(); schedule(); }
    };
    document.addEventListener('visibilitychange', onVisibility);
    schedule();
    return {
        stop() {
            stopped = true;
            clearTimeout(timer);
            document.removeEventListener('visibilitychange', onVisibility);
        },
        now: run,
    };
}

/** Teilen ueber das System, sonst in die Zwischenablage. */
export async function shareLink(url, title) {
    if (navigator.share) {
        try {
            await navigator.share({ title, url });
            return;
        } catch (e) {
            if (e.name === 'AbortError') return;
        }
    }
    await copyText(url);
}

export async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
        toast('Link kopiert.');
    } catch (e) {
        // Ohne Zwischenablage-Recht (http, alter Browser): markierbar anzeigen.
        const ref = {};
        const field = h('input', { class: 'field', value: text, readonly: true, onfocus: e2 => e2.target.select() });
        ref.current = openDialog([...sheetHead('Link', null, ref), h('div', { class: 'sheet-body' }, field)], { kind: 'sheet' });
        field.focus();
        field.select();
    }
}
