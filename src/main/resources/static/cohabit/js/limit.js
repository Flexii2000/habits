// Zaehlen beim Tageslimit aus dem Logbook (Vertrag 2.5a, 5.2c). Die Zahl eines Tages
// steht im Logbook; die Seite schickt sie absolut - je Co-Habit und Tag gewinnt die
// neueste, hoechstens ein Aufruf ist unterwegs, schnelles Tippen wartet 0,5 s Ruhe ab.
// Scheitert der Aufruf, gilt wieder der Stand des Dienstes, und seine Meldung erscheint.
// Nur im Speicher der Seite: das Web wird nicht offline benutzt.
import { put, enc } from './api.js';
import { h, icon, showError } from './dom.js';
import { fmtNumber } from './format.js';

const QUIET_MS = 500;
const MAX_COUNT = 10000;

/** Je „id|Tag“: die Zahl, die die Person sehen will, solange der Dienst sie nicht bestaetigt hat. */
const lanes = new Map();

const laneKey = (id, date) => `${id}|${date}`;

/** Was gerade gilt: die getippte Zahl, sonst die des Dienstes (fehlend = 0). */
export function shownCount(id, date, serverCount) {
    const lane = lanes.get(laneKey(id, date));
    return lane ? lane.wanted : (serverCount ?? 0);
}

export function isWaiting(id, date) {
    return lanes.has(laneKey(id, date));
}

/**
 * Setzt die Zahl eines Tages. `onSettled(detail)` kommt nach dem letzten Aufruf: mit dem
 * frischen Stand des Dienstes, oder mit null, wenn er gescheitert ist. Es gilt der zuletzt
 * genannte - die Ansicht, in der zuletzt getippt wurde.
 */
export function setCount(id, date, count, onSettled) {
    const key = laneKey(id, date);
    let lane = lanes.get(key);
    if (!lane) {
        lane = { wanted: count, sending: false, timer: null, onSettled: null };
        lanes.set(key, lane);
    }
    lane.wanted = Math.max(0, Math.min(MAX_COUNT, count));
    if (onSettled) lane.onSettled = onSettled;
    clearTimeout(lane.timer);
    lane.timer = setTimeout(() => {
        lane.timer = null;
        flush(id, date, lane);
    }, QUIET_MS);
}

async function flush(id, date, lane) {
    if (lane.sending || lane.timer) return;
    const key = laneKey(id, date);
    const value = lane.wanted;
    lane.sending = true;
    let detail = null;
    let failure = null;
    try {
        detail = await put(`/cohabits/${enc(id)}/count/${date}`, { count: value });
    } catch (err) {
        failure = err;
    }
    lane.sending = false;
    if (lanes.get(key) !== lane) return;
    if (failure) {
        clearTimeout(lane.timer);
        lanes.delete(key);
        showError(failure);
        if (lane.onSettled) lane.onSettled(null);
        return;
    }
    if (lane.wanted !== value) {
        // Waehrend des Aufrufs weitergetippt: das Neue geht hinterher (nach der Ruhe, falls sie laeuft).
        flush(id, date, lane);
        return;
    }
    lanes.delete(key);
    if (lane.onSettled) lane.onSettled(detail);
}

// --- Anzeige ---------------------------------------------------------------

/** Heute bei einem Tageslimit: Zahl, Limit, Einheit - mit dem, was gerade getippt wurde. */
export function today(summary) {
    const limit = summary.limit;
    const count = shownCount(summary.ref.id, limit.date, limit.count);
    const waiting = isWaiting(summary.ref.id, limit.date);
    return {
        count,
        waiting,
        over: count > limit.perDay,
        // Der Satz kommt vom Dienst; nur waehrend die Zahl wartet, steht er hier in derselben Form.
        text: waiting || !limit.text ? textFor(count, limit) : limit.text,
    };
}

function textFor(count, limit) {
    return `heute ${fmtNumber(count)} von ${limit.perDay}${limit.unit ? ` ${limit.unit}` : ''}`;
}

/** Ein Schritt weiter: Kommawerte aus dem Logbook gehen auf die naechste ganze Zahl (2,5 → 3 bzw. 2). */
function stepped(n, delta) {
    return Math.max(0, delta > 0 ? Math.floor(n) + 1 : Math.ceil(n) - 1);
}

/** Ob sich heute zaehlen laesst: Logbook erreichbar, nicht archiviert. */
export function canCount(summary) {
    return !!(summary.limit && summary.limit.canCount && summary.status !== 'UNAVAILABLE');
}

/** Das runde „+“ an der Stelle des Hakens: eine mehr fuer heute. */
export function plusButton(summary, { size = '', onChange, onSettled } = {}) {
    if (!canCount(summary)) return null;
    const label = 'Eine mehr';
    return h('button', {
        type: 'button',
        class: `round-btn ${size}${size === 'sm' ? ' outline' : ''}`,
        'aria-label': label,
        title: label,
        onclick: event => {
            event.preventDefault();
            event.stopPropagation();
            const limit = summary.limit;
            setCount(summary.ref.id, limit.date, stepped(shownCount(summary.ref.id, limit.date, limit.count), 1), onSettled);
            if (onChange) onChange();
        },
    }, icon('plus'));
}

/**
 * „− 3 +“ fuer einen Tag: zeigt die geltende Zahl (ueber `perDay` in der Farbe fuer
 * „nicht geschafft“); jeder Tipp setzt sie sofort und schickt sie nach der Ruhe.
 */
export function counter(id, date, serverCount, { perDay, unitText, onChange, onSettled, large = false } = {}) {
    const value = h('span', { class: 'limit-value', 'aria-live': 'polite' });
    const minus = h('button', { type: 'button', class: 'stepper-btn', 'aria-label': 'Eine weniger' }, icon('minus'));
    const plus = h('button', { type: 'button', class: 'stepper-btn', 'aria-label': 'Eine mehr' }, icon('plus'));
    const render = () => {
        const n = shownCount(id, date, serverCount);
        value.textContent = fmtNumber(n);
        value.classList.toggle('over', perDay != null && n > perDay);
        minus.disabled = n <= 0;
        plus.disabled = n >= MAX_COUNT;
    };
    const step = delta => {
        setCount(id, date, stepped(shownCount(id, date, serverCount), delta), onSettled);
        render();
        if (onChange) onChange();
    };
    minus.addEventListener('click', () => step(-1));
    plus.addEventListener('click', () => step(1));
    render();
    return h('div', { class: `limit-counter${large ? ' large' : ''}` },
        minus,
        h('span', { class: 'limit-figure' }, value, unitText ? h('span', { class: 'limit-unit' }, unitText) : null),
        plus);
}
