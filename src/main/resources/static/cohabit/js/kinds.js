// Farbe nach Typ (Vertrag 5.2a, Felix
// 05.10.): die Seite soll so leicht zu verfolgen sein wie die klassische Liste.
// Jedes Co-Habit traegt die Farbe seines Typs - die gespeicherte `ref.color`
// zeigt keine Ansicht mehr, deshalb geht jede Farbe eines Co-Habits ueber
// cohabitClass(). Dieselbe Zuordnung wie in der iOS-App (CohabitKinds.swift).

/** Wie die Typkarten im Anlegen-Schritt 1; automatische (jeder Typ) sind Aqua. */
export const TYPE_COLORS = { STREAK: 'peach', ABSTINENCE: 'mint', GOAL: 'periwinkle', CHALLENGE: 'butter' };
const AUTOMATIC_COLOR = 'aqua';

/** Zaehlt von selbst - aus Healthy, Health, dem Wald oder der Evaluation. */
export function isAutomatic(ref) {
    return !!(ref && ref.autoSource);
}

export function typeColor(type, automatic = false) {
    return automatic ? AUTOMATIC_COLOR : TYPE_COLORS[type] || 'periwinkle';
}

/** Palettenschluessel eines Co-Habits (CohabitRef) nach seinem Typ. */
export function refColor(ref) {
    return typeColor(ref && ref.type, isAutomatic(ref));
}

/** CSS-Klasse mit den Farben eines Co-Habits (`c-peach` …). */
export function cohabitClass(ref) {
    return `c-${refColor(ref)}`;
}
