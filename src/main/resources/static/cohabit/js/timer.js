// Timer bei Minuten pro Woche (Vertrag 2.4b, 5.2d). Die Startzeit liegt beim Dienst
// (`summary.timer.startedAt`) - so laeuft er auf jedem Geraet weiter; hochgezaehlt wird
// hier, jede Sekunde ab dem Start. Start, Stopp und Verwerfen gelten sofort: solange ein
// Aufruf unterwegs ist, zeigt jede Ansicht den getippten Stand. Scheitert er, gilt wieder
// der des Dienstes, und seine Meldung erscheint (ueber 24 h laeuft der Timer weiter).
// Nur online - das Web hat keinen Postausgang.
import { post, del, enc } from './api.js';
import { h, icon, actionSheet, showError, toast, uuid } from './dom.js';
import { onLongPress } from './reactions.js';

/** Je Co-Habit der getippte Stand (Start als ISO-Zeit, null = steht), bis der Dienst antwortet. */
const pending = new Map();

const timerPath = id => `/cohabits/${enc(id)}/timer`;

/** Seit wann er laeuft (ISO) oder null - mit dem, was gerade getippt wurde. */
export function startedAt(summary) {
    const id = summary.ref.id;
    if (pending.has(id)) return pending.get(id);
    return summary.timer ? summary.timer.startedAt : null;
}

export function isRunning(summary) {
    return !!(summary.timer && startedAt(summary));
}

/** Ein Aufruf ist unterwegs - der naechste Tipp wartet auf ihn. */
export function isBusy(summary) {
    return pending.has(summary.ref.id);
}

/** „12:34“, ab einer Stunde „1:02:05“. Eine vorgehende Uhr des Geraets zaehlt nicht ins Minus. */
export function elapsedText(since, now = Date.now()) {
    const total = Math.max(0, Math.floor((now - Date.parse(since)) / 1000));
    const two = n => String(n).padStart(2, '0');
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor(total / 60) % 60;
    const seconds = total % 60;
    return hours ? `${hours}:${two(minutes)}:${two(seconds)}` : `${minutes}:${two(seconds)}`;
}

// Eine Uhr fuer alle laufenden Anzeigen: sie sucht ihre Elemente bei jedem Takt neu -
// die Ansichten zeichnen sich staendig neu, ohne sich ab- und anzumelden. Ohne Anzeige
// haelt sie an. Ein halber Takt, damit keine Sekunde uebersprungen wird.
let ticker = null;

function tick() {
    const shown = document.querySelectorAll('.timer-elapsed[data-since]');
    if (!shown.length) {
        clearInterval(ticker);
        ticker = null;
        return;
    }
    const now = Date.now();
    shown.forEach(el => { el.textContent = elapsedText(el.dataset.since, now); });
}

/** Die laufende Zeit ab `since`, sie zaehlt von selbst hoch. */
export function elapsed(since, className = '') {
    const el = h('span', { class: `timer-elapsed${className ? ` ${className}` : ''}`, 'data-since': since, role: 'timer' }, elapsedText(since));
    if (!ticker) ticker = setInterval(tick, 500);
    return el;
}

/**
 * Schickt einen Wechsel. `onChange()` kommt sofort (neu zeichnen mit dem getippten Stand),
 * `onSettled(detail)` mit dem frischen CohabitDetail - oder mit null, wenn der Dienst
 * abgelehnt hat: dann gilt wieder sein Stand.
 */
async function send(summary, wanted, call, { onChange, onSettled } = {}) {
    const id = summary.ref.id;
    if (pending.has(id)) return;
    pending.set(id, wanted);
    if (onChange) onChange();
    let fresh = null;
    try {
        fresh = await call(id);
    } catch (err) {
        showError(err);
    }
    pending.delete(id);
    if (onSettled) onSettled(fresh);
}

/** Startet mit der Tippzeit - laeuft er auf einem anderen Geraet schon, bleibt dessen Start. */
export function start(summary, handlers) {
    const at = new Date().toISOString();
    return send(summary, at, id => post(timerPath(id), { startedAt: at }), handlers);
}

/** Stoppt mit der Tippzeit; der Dienst traegt die Minuten am Tag des Starts ein. */
export function stop(summary, handlers) {
    const at = new Date().toISOString();
    return send(summary, null, async id => {
        const res = await post(`${timerPath(id)}/stop`, { id: uuid(), stoppedAt: at });
        // Unter 30 s traegt der Dienst nichts ein - ohne Meldung saehe das wie ein Fehler aus.
        toast(res.checkin ? `${res.checkin.valueText || 'Minuten'} eingetragen.` : 'Unter 30 Sekunden – nichts eingetragen.');
        return res.cohabit;
    }, handlers);
}

/** Verwirft ohne Eintrag und ohne Nachfrage (Vertrag 5.2d). */
export function discard(summary, handlers) {
    return send(summary, null, async id => {
        const fresh = await del(timerPath(id));
        toast('Timer verworfen.');
        return fresh;
    }, handlers);
}

/** Der runde Knopf an der Stelle des Hakens: ▶ startet, ■ in der Typfarbe stoppt. */
export function roundButton(summary, { size = '', onChange, onSettled } = {}) {
    const running = isRunning(summary);
    if (!running && !summary.canCheckIn) return null;
    const label = running ? 'Timer stoppen' : 'Timer starten';
    return h('button', {
        type: 'button',
        class: `round-btn ${size}${running ? ' stop' : size === 'sm' ? ' outline' : ''}${isBusy(summary) ? ' busy' : ''}`,
        'aria-label': label,
        title: label,
        onclick: event => {
            event.preventDefault();
            event.stopPropagation();
            (running ? stop : start)(summary, { onChange, onSettled });
        },
    }, icon(running ? 'stop' : 'play'));
}

/**
 * Langes Druecken bzw. Rechtsklick auf die Karte: Minuten von Hand und, solange er
 * laeuft, verwerfen. Ein Tipp oeffnet weiter das Detail.
 */
export function bindMenu(card, summary, { onEntry, onChange, onSettled } = {}) {
    card.classList.add('timer-card');
    onLongPress(card, () => {
        const actions = [
            summary.canCheckIn && summary.checkInLabel ? { label: summary.checkInLabel, icon: 'plus', onSelect: onEntry } : null,
            isRunning(summary) ? { label: 'Timer verwerfen', icon: 'trash', danger: true, onSelect: () => discard(summary, { onChange, onSettled }) } : null,
        ].filter(Boolean);
        if (actions.length) actionSheet(summary.ref.name, actions);
    }, { overLinks: true });
    return card;
}
