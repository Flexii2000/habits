// Farbe, Reihenfolge und Fortschrittsanzeige nach Typ (Vertrag 5.2a, Felix
// 05.10.): die Seite soll so leicht zu verfolgen sein wie die klassische Liste.
// Jedes Co-Habit traegt die Farbe seines Typs - die gespeicherte `ref.color`
// zeigt keine Ansicht mehr, deshalb geht jede Farbe eines Co-Habits ueber
// cohabitClass(). Welche Farbe ein Typ hat, waehlt jede Person selbst (5.2b,
// `me.typeColors`); jede Ansicht fragt beim Zeichnen hier nach und folgt so
// einer Aenderung, sobald sie neu zeichnet. Dieselbe Zuordnung wie in der
// iOS-App (CohabitKinds.swift).
import { state } from './state.js';

/**
 * Die Palette aus Vertrag 2.1, in der Reihenfolge der Auswahl. Die letzten vier
 * kamen am 05.10. fuer die Typfarben dazu; Avatare leitet der Dienst weiter nur
 * aus den ersten sechs ab.
 */
export const PALETTE = ['peach', 'mint', 'periwinkle', 'butter', 'rose', 'aqua', 'lavender', 'sky', 'sage', 'coral'];

export const COLOR_NAMES = {
    peach: 'Pfirsich', mint: 'Mint', periwinkle: 'Periwinkle', butter: 'Butter', rose: 'Rosé',
    aqua: 'Aqua', lavender: 'Lavendel', sky: 'Himmelblau', sage: 'Salbei', coral: 'Koralle',
};

/** Die Plaetze der Typfarben; AUTOMATIC gilt fuer jedes Co-Habit mit `autoSource`. */
export const TYPE_SLOTS = ['STREAK', 'ABSTINENCE', 'GOAL', 'CHALLENGE', 'AUTOMATIC'];

/**
 * Vorgaben wie die Typkarten im Anlegen-Schritt 1 - sie gelten, solange die
 * Person nichts anderes gewaehlt hat oder ein aelterer Dienst das Feld nicht kennt.
 */
export const DEFAULT_TYPE_COLORS = { STREAK: 'peach', ABSTINENCE: 'mint', GOAL: 'periwinkle', CHALLENGE: 'butter', AUTOMATIC: 'aqua' };

/** Farbe eines Platzes: die eigene Wahl, wenn sie ein Palettenschluessel ist, sonst die Vorgabe. */
function slotColor(colors, slot) {
    const own = colors && colors[slot];
    return PALETTE.includes(own) ? own : DEFAULT_TYPE_COLORS[slot];
}

/** Alle fuenf Plaetze der angemeldeten Person, Vorgaben eingesetzt. */
export function typeColors(colors = state.me && state.me.typeColors) {
    return Object.fromEntries(TYPE_SLOTS.map(slot => [slot, slotColor(colors, slot)]));
}

/** Uebernimmt eine neue Zuordnung; die Ansichten zeigen sie beim naechsten Zeichnen. */
export function setTypeColors(colors) {
    if (state.me) state.me = { ...state.me, typeColors: typeColors(colors) };
}

/** Zaehlt von selbst - aus Healthy, Health, dem Wald oder der Evaluation. */
export function isAutomatic(ref) {
    return !!(ref && ref.autoSource);
}

export function typeColor(type, automatic = false) {
    const slot = automatic ? 'AUTOMATIC' : type;
    if (!TYPE_SLOTS.includes(slot)) return 'periwinkle';
    return slotColor(state.me && state.me.typeColors, slot);
}

/** Palettenschluessel eines Co-Habits (CohabitRef) nach seinem Typ. */
export function refColor(ref) {
    return typeColor(ref && ref.type, isAutomatic(ref));
}

/** CSS-Klasse mit den Farben eines Co-Habits (`c-peach` …). */
export function cohabitClass(ref) {
    return `c-${refColor(ref)}`;
}

// --- Reihenfolge auf „Heute" -----------------------------------------------

export const GROUP = { STREAK: 0, GOALS_AND_CHALLENGES: 1, ABSTINENCE: 2, AUTOMATIC: 3 };

/**
 * Erst, was man abhakt (manuelle Streaks, dann Ziele und Challenges), dann
 * Abstinenz, zuletzt die automatischen - wie in der klassischen Liste.
 */
export function todayGroup(ref) {
    if (isAutomatic(ref)) return GROUP.AUTOMATIC;
    if (ref.type === 'STREAK') return GROUP.STREAK;
    if (ref.type === 'ABSTINENCE') return GROUP.ABSTINENCE;
    return GROUP.GOALS_AND_CHALLENGES;
}

const byName = new Intl.Collator('de', { numeric: true, sensitivity: 'base' });

/**
 * Nach Gruppe, darin nach Anlegedatum (aeltestes zuerst), ohne Datum nach
 * Name. Unabhaengig vom Status: was man abhakt, bleibt, wo es war.
 */
export function typeOrder(summaries) {
    return (summaries || [])
        .map((summary, index) => ({ summary, index }))
        .sort((a, b) => {
            const left = a.summary.ref;
            const right = b.summary.ref;
            const group = todayGroup(left) - todayGroup(right);
            if (group) return group;
            const l = left.createdAt ? Date.parse(left.createdAt) : NaN;
            const r = right.createdAt ? Date.parse(right.createdAt) : NaN;
            if (!Number.isNaN(l) && !Number.isNaN(r) && l !== r) return l - r;
            if (!Number.isNaN(l) && Number.isNaN(r)) return -1;
            if (Number.isNaN(l) && !Number.isNaN(r)) return 1;
            return byName.compare(left.name || '', right.name || '') || a.index - b.index;
        })
        .map(entry => entry.summary);
}

// --- Fortschritt ----------------------------------------------------------

/**
 * Automatische Quellen mit einem Ziel zum Auffuellen. Track food, das
 * kcal-Ziel im Wochenmittel und die Evaluation haben keins - und eine Quelle,
 * die die Seite nicht kennt, bekommt erst recht keinen Balken.
 */
const BAR_SOURCES = new Set(['STEPS_WEEKLY', 'FOCUS']);

/**
 * Was den Stand zeigt: ein Balken wie beim Ziel (`{ bar: 0…1 }`), bei manuellen
 * Streaks die Punkte des Zeitraums (`{ dots: { done, goal } }`), sonst null.
 */
export function gauge(summary) {
    const progress = summary.progress;
    if (!progress) return null;
    const ref = summary.ref;
    const fraction = Math.max(0, Math.min(1, Number(progress.fraction) || 0));
    if (isAutomatic(ref)) return BAR_SOURCES.has(ref.autoSource) ? { bar: fraction } : null;
    if (ref.type === 'GOAL' || ref.type === 'CHALLENGE') return { bar: fraction };
    if (ref.type === 'STREAK' && progress.goal >= 1 && progress.goal <= 7) {
        return { dots: { done: Math.max(0, Math.round(progress.done)), goal: Math.round(progress.goal) } };
    }
    return null;
}
