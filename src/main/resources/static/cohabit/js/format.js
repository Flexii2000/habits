// Formatieren, nicht rechnen: Kennzahlen und ihre Texte liefert der Dienst
// (Vertrag 1.4). Hier nur Datum, Uhrzeit, Zahlen und Namen fuer die Anzeige.
import { state } from './state.js';

const numberFormat = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 2 });

export const fmtNumber = n => numberFormat.format(n);

export function fmtTime(iso) {
    return new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
}

/** Kalendertag 'yyyy-MM-dd' in einer Zeitzone (ohne Zone: die des Geraets). */
export function dayIn(date = new Date(), timeZone) {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(date);
    const get = type => parts.find(p => p.type === type).value;
    return `${get('year')}-${get('month')}-${get('day')}`;
}

export function parseDay(day) {
    const [y, m, d] = day.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d));
}

export function addDays(day, n) {
    const date = parseDay(day);
    date.setUTCDate(date.getUTCDate() + n);
    return date.toISOString().slice(0, 10);
}

const utc = options => ({ ...options, timeZone: 'UTC' });

/** „Heute", „Gestern" oder „Montag, 28. September". */
export function dayHeading(day, today = dayIn()) {
    if (day === today) return 'Heute';
    if (day === addDays(today, -1)) return 'Gestern';
    const date = parseDay(day);
    const sameYear = day.slice(0, 4) === today.slice(0, 4);
    return date.toLocaleDateString('de-DE', utc({ weekday: 'long', day: 'numeric', month: 'long', year: sameYear ? undefined : 'numeric' }));
}

/** Kurz fuer Auswahllisten: „Heute", „Gestern", „Mo., 28.09.". */
export function dayShort(day, today = dayIn()) {
    if (day === today) return 'Heute';
    if (day === addDays(today, -1)) return 'Gestern';
    return parseDay(day).toLocaleDateString('de-DE', utc({ weekday: 'short', day: '2-digit', month: '2-digit' }));
}

/** „31.10.2026" */
export function dateLong(day) {
    return parseDay(day).toLocaleDateString('de-DE', utc({ day: '2-digit', month: '2-digit', year: 'numeric' }));
}

export function monthName(day) {
    return parseDay(day).toLocaleDateString('de-DE', utc({ month: 'long' }));
}

export function dateTimeShort(iso) {
    const date = new Date(iso);
    return date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
        + ', ' + fmtTime(iso);
}

export function isMe(person) {
    return !!(person && state.me && person.id === state.me.person.id);
}

/** Die eigene Person heisst in Listen „Du" - wie in den Entwuerfen. */
export function personName(person) {
    if (!person) return '';
    return isMe(person) ? 'Du' : person.displayName;
}

export function joinNames(names) {
    if (names.length <= 1) return names.join('');
    return `${names.slice(0, -1).join(', ')} und ${names[names.length - 1]}`;
}

export const TYPE_NAMES = { STREAK: 'Streak', ABSTINENCE: 'Abstinenz', GOAL: 'Ziel', CHALLENGE: 'Challenge' };

export const UNIT_LABELS = { COUNT: 'Anzahl', MINUTES: 'Minuten', KM: 'km', STEPS: 'Schritte' };

export function unitLabel(unit) {
    return UNIT_LABELS[unit] || unit || '';
}

/** Zahl aus einem Eingabefeld, deutsches Komma erlaubt. */
export function parseNumber(text) {
    const cleaned = String(text).trim().replace(/\s/g, '').replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.');
    if (!cleaned) return null;
    const n = Number(cleaned);
    return Number.isFinite(n) ? n : NaN;
}

export function plural(n, one, many) {
    return `${fmtNumber(n)} ${n === 1 ? one : many}`;
}
