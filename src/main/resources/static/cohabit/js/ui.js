// Wiederkehrende Bausteine der Oberflaeche.
import { h, icon, openDialog, fill } from './dom.js';
import { photoUrl } from './api.js';
import { isMe } from './format.js';

export const PALETTE = ['peach', 'mint', 'periwinkle', 'butter', 'rose', 'aqua'];

export function colorClass(key) {
    return `c-${PALETTE.includes(key) ? key : 'periwinkle'}`;
}

export function logo(className = '') {
    return h('span', { class: `logo ${className}` }, 'co', h('b', null, 'H'), 'abit');
}

export function avatar(person, size = 32) {
    const me = isMe(person);
    const el = h('span', {
        class: `avatar ${colorClass(person && person.color)}${me ? ' me' : ''}`,
        style: `--size:${size}px`,
        title: person ? person.displayName : '',
    });
    if (person && person.avatarPhotoId) {
        el.classList.add('has-img');
        el.append(h('img', { src: photoUrl(person.avatarPhotoId, 'thumb'), alt: '', loading: 'lazy', decoding: 'async' }));
    } else {
        el.textContent = me ? 'Du' : (person && person.initials) || '?';
    }
    return el;
}

/** Bis zu drei Avatare, dann „+n". */
export function avatarStack(people, size = 32, max = 3) {
    const list = people || [];
    const shown = list.slice(0, max);
    const rest = list.length - shown.length;
    return h('span', { class: 'avatars' },
        shown.map(p => avatar(p, size)),
        rest > 0 ? h('span', { class: 'avatar more', style: `--size:${size}px` }, `+${rest}`) : null);
}

export function chip(text, className = '') {
    return h('span', { class: `chip ${className}` }, text);
}

export function sectionLabel(text) {
    return h('h2', { class: 'section-label' }, text);
}

/** Schalter als echte Checkbox - Tastatur und Screenreader bekommen sie geschenkt. */
export function toggle({ checked, onchange, label, disabled }) {
    const input = h('input', { type: 'checkbox', role: 'switch', checked, disabled, 'aria-label': label });
    input.addEventListener('change', () => onchange && onchange(input.checked));
    return h('span', { class: 'switch' }, input, h('span', { class: 'knob', 'aria-hidden': 'true' }));
}

export function toggleRow(label, options) {
    const control = toggle({ ...options, label });
    return h('label', { class: 'set-row' }, h('span', { class: 'set-label' }, label), control);
}

/** Umschalter aus Pillen: options = [[wert, beschriftung], …]. */
export function segmented(options, value, onchange, { className = '', label } = {}) {
    const wrap = h('div', { class: `seg ${className}`, role: 'group', 'aria-label': label });
    const render = current => {
        fill(wrap, ...options.map(([key, text]) => h('button', {
            type: 'button',
            'aria-pressed': String(key === current),
            onclick: () => {
                if (key === current) return;
                render(key);
                onchange(key);
            },
        }, text)));
    };
    render(value);
    return wrap;
}

/**
 * Einstellzeile mit Wert rechts und nativer Auswahl darueber - auf dem Handy
 * oeffnet sich der System-Picker, wie in den Apps.
 */
export function selectRow(label, options, value, onchange) {
    const valueText = h('span', { class: 'set-value' });
    const select = h('select', { class: 'set-select', 'aria-label': label },
        options.map(([key, text]) => h('option', { value: key == null ? '' : String(key) }, text)));
    const keyOf = v => (v == null ? '' : String(v));
    select.value = keyOf(value);
    const sync = () => {
        const option = select.options[select.selectedIndex];
        valueText.textContent = option ? option.textContent : '';
    };
    select.addEventListener('change', () => {
        sync();
        const match = options.find(([key]) => keyOf(key) === select.value);
        onchange(match ? match[0] : null);
    });
    sync();
    return h('label', { class: 'set-row select' },
        h('span', { class: 'set-label' }, label),
        valueText, icon('chevronRight', 'set-chevron'), select);
}

export function stepper(value, min, max, format, onchange) {
    let current = value;
    const text = h('span', { class: 'stepper-value' });
    const minus = h('button', { type: 'button', class: 'stepper-btn', 'aria-label': 'Weniger' }, '–');
    const plus = h('button', { type: 'button', class: 'stepper-btn', 'aria-label': 'Mehr' }, '+');
    const render = () => {
        text.textContent = format(current);
        minus.disabled = current <= min;
        plus.disabled = current >= max;
    };
    minus.addEventListener('click', () => { if (current > min) { current--; render(); onchange(current); } });
    plus.addEventListener('click', () => { if (current < max) { current++; render(); onchange(current); } });
    render();
    return h('div', { class: 'stepper' }, minus, text, plus);
}

export function emptyState(text, action) {
    return h('div', { class: 'empty' },
        h('p', null, text),
        action ? (action.href
            ? h('a', { class: 'btn primary', href: action.href, 'data-nav': '' }, action.label)
            : h('button', { type: 'button', class: 'btn primary', onclick: action.onclick }, action.label)) : null);
}

export function loadingState() {
    return h('div', { class: 'loading', role: 'status', 'aria-label': 'Lädt' }, h('span', { class: 'spinner' }));
}

export function errorState(message, retry) {
    return h('div', { class: 'empty error' },
        h('p', null, message),
        retry ? h('button', { type: 'button', class: 'btn outline', onclick: retry }, 'Erneut versuchen') : null);
}

/**
 * Foto mit gestreiftem Platzhalter in der Co-Habit-Farbe, bis es geladen ist
 * (wie der „Beweisfoto"-Platzhalter der Entwuerfe). Tippen oeffnet es gross.
 */
export function photo(id, { alt = 'Beweisfoto', onOpen, size = 'thumb' } = {}) {
    // In Listen das Vorschaubild (Vertrag 6), gross erst auf Tipp. Ein eigenes GIF
    // kommt in voller Groesse - nur die bleibt animiert (Vertrag 2.7a).
    const img = h('img', { src: photoUrl(id, size), alt, loading: 'lazy', decoding: 'async' });
    const frame = h('button', { type: 'button', class: 'photo', 'aria-label': 'Foto groß anzeigen' }, img);
    img.addEventListener('load', () => frame.classList.add('loaded'));
    img.addEventListener('error', () => frame.classList.add('failed'));
    frame.addEventListener('click', () => (onOpen ? onOpen(id) : openPhoto(id)));
    return frame;
}

export function openPhoto(id) {
    openPhotos([id], 0);
}

/** Alle Fotos eines Eintrags: `photoIds`, sonst das eine `photoId`. */
export function photoList(item) {
    if (!item) return [];
    if (item.photoIds && item.photoIds.length) return item.photoIds;
    return item.photoId ? [item.photoId] : [];
}

/**
 * Punkte unter einer wischbaren Reihe; folgen dem Wischen und springen auf
 * Klick (am Rechner gibt es kein Wischen).
 */
function swipeDots(track, count, className = '') {
    const dots = Array.from({ length: count }, (_, i) => h('button', {
        type: 'button', class: 'dot', 'aria-label': `Foto ${i + 1} von ${count}`,
        onclick: event => {
            event.stopPropagation();
            track.scrollTo({ left: i * track.clientWidth, behavior: 'smooth' });
        },
    }));
    const mark = () => {
        const index = Math.round(track.scrollLeft / Math.max(1, track.clientWidth));
        dots.forEach((dot, i) => dot.classList.toggle('on', i === index));
    };
    track.addEventListener('scroll', mark, { passive: true });
    mark();
    return h('div', { class: `dots ${className}` }, dots);
}

/** Mehrere Beweisfotos: seitlich wischen, Punkte darunter (Vertrag 2.3a); eines wie bisher. */
export function photoCarousel(ids, opts = {}) {
    if (!ids || !ids.length) return null;
    if (ids.length === 1) return photo(ids[0], opts);
    const track = h('div', { class: 'carousel-track' }, ids.map((id, i) =>
        photo(id, { ...opts, onOpen: () => openPhotos(ids, i) })));
    return h('div', { class: 'carousel' }, track, swipeDots(track, ids.length));
}

/** Vollbild, bei mehreren Fotos ebenfalls wischbar. */
export function openPhotos(ids, start = 0) {
    const ref = {};
    const track = h('div', { class: 'lightbox-track' }, ids.map(id =>
        h('div', { class: 'lightbox-slide' }, h('img', { src: photoUrl(id, 'full'), alt: 'Foto' }))));
    ref.current = openDialog(h('div', { class: 'lightbox-inner' },
        track,
        ids.length > 1 ? swipeDots(track, ids.length, 'on-dark') : null,
        h('button', { type: 'button', class: 'close-btn lightbox-close', 'aria-label': 'Schließen', onclick: () => ref.current.close() }, icon('close'))),
    { kind: 'lightbox', label: 'Foto' });
    requestAnimationFrame(() => { track.scrollLeft = start * track.clientWidth; });
}

export function progressBar(fraction, className = '') {
    const pct = Math.max(0, Math.min(1, fraction || 0)) * 100;
    return h('span', { class: `bar ${className}`, role: 'presentation' }, h('span', { class: 'bar-fill', style: `width:${pct.toFixed(1)}%` }));
}

export function badge(text) {
    return h('span', { class: 'badge' }, text);
}
