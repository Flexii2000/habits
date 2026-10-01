// Co-Habit anlegen in drei Schritten (S. 12-14) und Bearbeiten (Schritt 2
// allein). Der Typ steht nach dem Anlegen fest. Die Schritte sind Eintraege im
// Verlauf: Zurueck im Browser fuehrt zum vorigen Schritt, der Entwurf bleibt.
import { get, post, put, enc } from '../api.js';
import { h, icon, shareLink, showError, toast, fill } from '../dom.js';
import { avatar, errorState, loadingState, PALETTE, segmented, selectRow, stepper, toggle, toggleRow } from '../ui.js';
import { state, remember, forget } from '../state.js';
import { addDays, dayIn, parseNumber, TYPE_NAMES, UNIT_LABELS } from '../format.js';
import { peopleSearch } from './friends.js';
import { goBack, replaceFlow } from '../app.js';

const TYPES = [
    { key: 'STREAK', color: 'peach', desc: 'Regelmäßig dranbleiben, täglich oder im eigenen Rhythmus.', example: 'z. B. 3× pro Woche laufen' },
    { key: 'ABSTINENCE', color: 'mint', desc: 'Tage zählen, an denen ihr auf etwas verzichtet.', example: 'z. B. ohne Zucker' },
    { key: 'GOAL', color: 'periwinkle', desc: 'Eine Menge bis zu einem Datum, allein oder als Team.', example: 'z. B. 100.000 Schritte bis 31.10.' },
    { key: 'CHALLENGE', color: 'butter', desc: 'Wer schafft im Zeitraum am meisten?', example: 'z. B. wer kocht im Oktober öfter' },
];

const BACKFILL = [[0, 'Keine'], [24, '24 Stunden'], [48, '48 Stunden'], [72, '72 Stunden'], [168, '7 Tage'], [336, '14 Tage']];
const UNITS = Object.entries(UNIT_LABELS);
// KCAL holt der Dienst selbst aus dem Kalorienzaehler (Healthy) - je Mitglied mit Einwilligung.
const HEALTH = [[null, 'Keine'], ['STEPS', 'Schritte'], ['RUNNING_DISTANCE', 'Laufdistanz'], ['WORKOUTS', 'Trainings'], ['WORKOUT_MINUTES', 'Trainingsminuten'], ['KCAL', 'kcal aus Healthy']];
const HEALTH_UNIT = { STEPS: 'STEPS', RUNNING_DISTANCE: 'KM', WORKOUTS: 'COUNT', WORKOUT_MINUTES: 'MINUTES', KCAL: 'KCAL' };
const SOURCES = { FOOD: 'Track food', STEPS_WEEKLY: 'Schritte pro Woche', FOCUS: 'Fokus-Zeit' };
const FOCUS_PERIODS = [['DAY', 'Täglich'], ['WEEK', 'Pro Woche']];
/** Kategorien aus dem Wald der Fokus-App (nur Felix hat die Quelle) - einmal je Seitenaufruf geholt. */
let focusCategories = null;
const MAX_SEATS = 8;

function timezones(current) {
    let zones = [];
    try {
        zones = Intl.supportedValuesOf('timeZone');
    } catch (e) {
        zones = ['Europe/Berlin', 'Europe/London', 'Europe/Lisbon', 'Europe/Paris', 'Europe/Vienna', 'Europe/Zurich', 'Europe/Athens', 'America/New_York', 'America/Los_Angeles', 'Asia/Tokyo', 'Australia/Sydney', 'UTC'];
    }
    const list = ['Europe/Berlin', ...zones.filter(z => z !== 'Europe/Berlin')];
    if (current && !list.includes(current)) list.unshift(current);
    return list.map(z => [z, z.replace(/_/g, ' ')]);
}

let draft = null;

function newDraft() {
    const today = dayIn();
    return {
        flow: Math.random().toString(36).slice(2),
        type: null,
        name: '',
        color: 'peach',
        timezone: 'Europe/Berlin',
        photoRequired: false,
        valueTracking: false,
        unit: 'COUNT',
        backfillHours: 48,
        reminderTime: null,
        membersCanInvite: false,
        rhythm: { kind: 'DAILY', weekdays: [1, 3, 5], weekTimes: 3, monthTimes: 2, days: 2 },
        groupStreak: false,
        groupMode: false,
        goal: { target: '', start: today, deadline: addDays(today, 30), counting: 'ENTRIES', mode: 'TEAM' },
        challenge: { start: today, end: addDays(today, 6), scoring: 'MOST_ENTRIES', target: '', stake: '', recurrence: 'NONE' },
        health: null,
        auto: null,
        weeklyStepGoal: 70000,
        focusMinutesGoal: 240,
        focusPeriod: 'DAY',
        focusCategoryId: null,
        focusCategoryName: null,
        invites: new Set(),
        createdId: null,
    };
}

/** Entwurf aus einer bestehenden Konfiguration (Bearbeiten). */
function draftFrom(config) {
    const d = newDraft();
    d.type = config.type;
    d.name = config.name;
    d.color = config.color;
    d.timezone = config.timezone || 'Europe/Berlin';
    d.photoRequired = !!config.photoRequired;
    d.valueTracking = config.tracking && config.tracking.mode === 'VALUE';
    d.unit = (config.tracking && config.tracking.unit) || 'COUNT';
    d.backfillHours = config.backfillHours ?? 48;
    d.reminderTime = config.reminderTime || null;
    d.membersCanInvite = !!config.membersCanInvite;
    if (config.streak) {
        const r = config.streak.rhythm || { kind: 'DAILY' };
        d.rhythm.kind = r.kind;
        if (r.kind === 'WEEKDAYS') d.rhythm.weekdays = r.weekdays || [];
        if (r.kind === 'TIMES_PER_WEEK') d.rhythm.weekTimes = r.times;
        if (r.kind === 'TIMES_PER_MONTH') d.rhythm.monthTimes = r.times;
        if (r.kind === 'INTERVAL') d.rhythm.days = r.days;
        d.groupStreak = !!config.streak.groupStreak;
    }
    if (config.abstinence) d.groupMode = !!config.abstinence.groupMode;
    if (config.goal) d.goal = { ...config.goal, target: String(config.goal.target).replace('.', ',') };
    if (config.challenge) {
        d.challenge = {
            ...config.challenge,
            target: config.challenge.target == null ? '' : String(config.challenge.target).replace('.', ','),
            stake: config.challenge.stake || '',
        };
    }
    d.health = config.health ? config.health.metric : null;
    if (config.auto) {
        d.auto = config.auto.source;
        d.weeklyStepGoal = config.auto.weeklyStepGoal || 70000;
        d.focusMinutesGoal = config.auto.focusMinutesGoal || 240;
        d.focusPeriod = config.auto.focusPeriod || 'DAY';
        d.focusCategoryId = config.auto.focusCategoryId || null;
        d.focusCategoryName = config.auto.focusCategoryName || null;
    }
    return d;
}

function needsValue(d) {
    if (d.auto) return false;
    if (d.type === 'STREAK') return d.valueTracking;
    if (d.type === 'GOAL') return d.goal.counting === 'AMOUNT';
    if (d.type === 'CHALLENGE') return d.challenge.scoring !== 'MOST_ENTRIES';
    return false;
}

/** Baut CohabitConfig (Vertrag 3.4); wirft mit Klartext, wenn etwas fehlt. */
function toConfig(d, { forEdit = false } = {}) {
    const fail = (message, field) => {
        const err = new Error(message);
        err.field = field;
        throw err;
    };
    const name = d.name.trim();
    if (!name) fail('Bitte einen Namen angeben.', 'name');
    const config = {
        type: d.type,
        name,
        color: d.color,
        timezone: d.timezone,
        tracking: needsValue(d) ? { mode: 'VALUE', unit: d.unit } : { mode: 'CHECK' },
        photoRequired: d.type === 'ABSTINENCE' || d.auto ? false : d.photoRequired,
        backfillHours: d.backfillHours,
        reminderTime: d.reminderTime || null,
        membersCanInvite: d.membersCanInvite,
        streak: null,
        abstinence: null,
        goal: null,
        challenge: null,
        health: d.health && !d.auto ? { metric: d.health } : null,
        auto: null,
    };
    if (d.type === 'STREAK') {
        let rhythm;
        if (d.auto === 'STEPS_WEEKLY' || (d.auto === 'FOCUS' && d.focusPeriod === 'WEEK')) rhythm = { kind: 'TIMES_PER_WEEK', times: 1 };
        else if (d.auto) rhythm = { kind: 'DAILY' };
        else if (d.rhythm.kind === 'WEEKDAYS') {
            if (!d.rhythm.weekdays.length) fail('Bitte mindestens einen Wochentag wählen.', 'weekdays');
            rhythm = { kind: 'WEEKDAYS', weekdays: [...d.rhythm.weekdays].sort((a, b) => a - b) };
        } else if (d.rhythm.kind === 'TIMES_PER_WEEK') rhythm = { kind: 'TIMES_PER_WEEK', times: d.rhythm.weekTimes };
        else if (d.rhythm.kind === 'TIMES_PER_MONTH') rhythm = { kind: 'TIMES_PER_MONTH', times: d.rhythm.monthTimes };
        else if (d.rhythm.kind === 'INTERVAL') rhythm = { kind: 'INTERVAL', days: d.rhythm.days };
        else rhythm = { kind: 'DAILY' };
        config.streak = { rhythm, groupStreak: d.groupStreak };
        if (d.auto) {
            const steps = parseNumber(d.weeklyStepGoal);
            const minutes = parseNumber(d.focusMinutesGoal);
            if (d.auto === 'STEPS_WEEKLY' && !(steps > 0)) fail('Bitte ein Schrittziel angeben.', 'autoGoal');
            if (d.auto === 'FOCUS' && !(minutes > 0)) fail('Bitte ein Minutenziel angeben.', 'autoGoal');
            config.auto = {
                source: d.auto,
                weeklyStepGoal: d.auto === 'STEPS_WEEKLY' ? Math.round(steps) : null,
                focusMinutesGoal: d.auto === 'FOCUS' ? Math.round(minutes) : null,
                // Den Namen traegt der Dienst aus dem Wald ein; null heisst alle Baeume.
                focusCategoryId: d.auto === 'FOCUS' ? d.focusCategoryId : null,
                focusPeriod: d.auto === 'FOCUS' ? d.focusPeriod : null,
            };
        }
    } else if (d.type === 'ABSTINENCE') {
        config.abstinence = { groupMode: d.groupMode };
    } else if (d.type === 'GOAL') {
        const target = parseNumber(d.goal.target);
        if (!(target > 0)) fail('Bitte einen Zielwert angeben.', 'target');
        if (!d.goal.deadline) fail('Bitte ein Datum angeben.', 'deadline');
        if (!forEdit && d.goal.deadline < dayIn()) fail('Das Datum liegt in der Vergangenheit.', 'deadline');
        config.goal = { target, start: d.goal.start, deadline: d.goal.deadline, counting: d.goal.counting, mode: d.goal.mode };
    } else if (d.type === 'CHALLENGE') {
        const c = d.challenge;
        if (!c.start || !c.end) fail('Bitte Start und Ende angeben.', 'start');
        if (c.end < c.start) fail('Das Ende liegt vor dem Start.', 'end');
        let target = null;
        if (c.scoring === 'FIRST_TO_TARGET') {
            target = parseNumber(c.target);
            if (!(target > 0)) fail('Bitte einen Zielwert angeben.', 'ctarget');
        }
        config.challenge = {
            start: c.start, end: c.end, scoring: c.scoring, target,
            stake: c.stake.trim() ? c.stake.trim().slice(0, 80) : null, recurrence: c.recurrence,
        };
    }
    if (forEdit) delete config.type;
    return config;
}

// --- Bausteine ---------------------------------------------------------------

function field(label, input) {
    return h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, label), input);
}

function textInput(value, onInput, attrs = {}) {
    const input = h('input', { class: 'field', type: 'text', value: value ?? '', autocomplete: 'off', ...attrs });
    input.addEventListener('input', () => onInput(input.value));
    return input;
}

function dateInput(value, onChange, attrs = {}) {
    const input = h('input', { class: 'field', type: 'date', value: value || '', ...attrs });
    input.addEventListener('change', () => onChange(input.value));
    return input;
}

/** Einstellzeile „Erinnerung": Uhrzeit oder aus. */
function reminderRow(d) {
    const time = h('input', { type: 'time', value: d.reminderTime || '', 'aria-label': 'Erinnerung' });
    const sw = toggle({
        checked: !!d.reminderTime, label: 'Erinnerung',
        onchange: on => {
            d.reminderTime = on ? (time.value || '08:00') : null;
            time.value = d.reminderTime || '';
            time.hidden = !on;
        },
    });
    time.hidden = !d.reminderTime;
    time.addEventListener('change', () => { d.reminderTime = time.value || null; });
    return h('div', { class: 'set-row' }, h('span', { class: 'set-label' }, 'Erinnerung'), time, sw);
}

/**
 * Schritt 2: Name, Farbe, typabhaengige Einstellungen, Schalter. `edit`:
 * bestehendes Co-Habit (Typ fest, Zielstart fest).
 */
function settingsForm(d, { edit = false, errorEl, onChange } = {}) {
    const wrap = h('div', { class: 'stack' });
    const me = state.me;
    const sources = (me && me.sources) || [];
    const rerender = () => {
        const focusedId = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.fid : null;
        fill(wrap, ...build());
        if (focusedId) {
            const again = wrap.querySelector(`[data-fid="${focusedId}"]`);
            if (again) again.focus();
        }
        if (onChange) onChange();
    };

    function build() {
        const out = [];
        // Name und Farbe
        const colors = h('div', { class: 'color-picks', role: 'group', 'aria-label': 'Farbe' }, PALETTE.map(key => h('button', {
            type: 'button', class: `color-pick c-${key}`, 'aria-pressed': String(d.color === key), 'aria-label': key,
            onclick: () => { d.color = key; rerender(); },
        })));
        out.push(h('section', { class: 'set-group pad stack' },
            field('Name', textInput(d.name, v => { d.name = v; }, { maxlength: 40, placeholder: namePlaceholder(d.type), 'data-fid': 'name' })),
            h('div', { class: 'field-group' }, h('span', { class: 'field-label' }, 'Farbe'), colors)));

        if (d.type === 'STREAK') out.push(streakSection());
        if (d.type === 'ABSTINENCE') {
            out.push(h('section', { class: 'set-group' }, toggleRow('Gruppenmodus', { checked: d.groupMode, onchange: v => { d.groupMode = v; } })));
        }
        if (d.type === 'GOAL') out.push(goalSection());
        if (d.type === 'CHALLENGE') out.push(challengeSection());

        // Schalter und Einstellungen
        const rows = [];
        if (d.type !== 'ABSTINENCE' && !d.auto) {
            rows.push(toggleRow('Beweisfoto-Pflicht', { checked: d.photoRequired, onchange: v => { d.photoRequired = v; } }));
        }
        if (d.type === 'STREAK' && !d.auto) {
            rows.push(toggleRow('Mit Wert erfassen', { checked: d.valueTracking, onchange: v => { d.valueTracking = v; rerender(); } }));
        }
        if (needsValue(d)) {
            rows.push(selectRow('Einheit', UNITS, d.unit, v => { d.unit = v; }));
        }
        if (!d.auto && d.type !== 'ABSTINENCE' && (d.type !== 'STREAK' || d.valueTracking)) {
            rows.push(selectRow('Health-Metrik', HEALTH, d.health, v => {
                d.health = v;
                if (v) {
                    d.unit = HEALTH_UNIT[v];
                    if (d.type === 'STREAK') d.valueTracking = true;
                    if (d.type === 'GOAL') d.goal.counting = 'AMOUNT';
                }
                rerender();
            }));
        }
        rows.push(selectRow('Zeitzone', timezones(d.timezone), d.timezone, v => { d.timezone = v; }));
        rows.push(selectRow('Nachtragsfrist', BACKFILL, d.backfillHours, v => { d.backfillHours = v; }));
        rows.push(reminderRow(d));
        if (d.type === 'STREAK') {
            rows.push(toggleRow('Gruppen-Streak', { checked: d.groupStreak, onchange: v => { d.groupStreak = v; } }));
        }
        rows.push(toggleRow('Mitglieder dürfen einladen', { checked: d.membersCanInvite, onchange: v => { d.membersCanInvite = v; } }));
        out.push(h('section', { class: 'set-group' }, rows));
        if (errorEl) out.push(errorEl);
        return out;
    }

    function streakSection() {
        const sections = [];
        if (!d.auto) {
            const parts = [h('span', { class: 'field-label' }, 'Rhythmus')];
            parts.push(segmented([
                ['DAILY', 'Täglich'], ['WEEKDAYS', 'Wochentage'], ['TIMES_PER_WEEK', 'pro Woche'],
                ['TIMES_PER_MONTH', 'pro Monat'], ['INTERVAL', 'Intervall'],
            ], d.rhythm.kind, v => { d.rhythm.kind = v; rerender(); }, { className: 'rhythm-seg', label: 'Rhythmus' }));
            if (d.rhythm.kind === 'WEEKDAYS') {
                parts.push(h('div', { class: 'weekday-picks', role: 'group', 'aria-label': 'Wochentage', 'data-fid': 'weekdays', tabindex: '-1' },
                    ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'].map((label, i) => {
                        const iso = i + 1;
                        return h('button', {
                            type: 'button', class: 'weekday-pick', 'aria-pressed': String(d.rhythm.weekdays.includes(iso)),
                            onclick: () => {
                                const set = new Set(d.rhythm.weekdays);
                                if (set.has(iso)) set.delete(iso); else set.add(iso);
                                d.rhythm.weekdays = [...set];
                                rerender();
                            },
                        }, label);
                    })));
            } else if (d.rhythm.kind === 'TIMES_PER_WEEK') {
                parts.push(stepper(d.rhythm.weekTimes, 1, 7, n => `${n}× pro Woche`, n => { d.rhythm.weekTimes = n; }));
            } else if (d.rhythm.kind === 'TIMES_PER_MONTH') {
                parts.push(stepper(d.rhythm.monthTimes, 1, 31, n => `${n}× pro Monat`, n => { d.rhythm.monthTimes = n; }));
            } else if (d.rhythm.kind === 'INTERVAL') {
                parts.push(stepper(d.rhythm.days, 2, 30, n => `alle ${n} Tage`, n => { d.rhythm.days = n; }));
            }
            sections.push(h('section', { class: 'set-group pad stack' }, parts));
        }
        // Automatische Quellen (Vertrag 5.2, Punkt 13) nur fuer Personen, die sie haben.
        if (sources.length || d.auto) {
            const options = [[null, 'Aus'], ...sources.map(s => [s, SOURCES[s] || s])];
            if (d.auto && !sources.includes(d.auto)) options.push([d.auto, SOURCES[d.auto] || d.auto]);
            const rows = [selectRow('Automatisch', options, d.auto, v => { d.auto = v; rerender(); })];
            if (d.auto === 'STEPS_WEEKLY') {
                rows.push(h('label', { class: 'set-row' }, h('span', { class: 'set-label' }, 'Schritte pro Woche'),
                    inlineNumber(d.weeklyStepGoal, v => { d.weeklyStepGoal = v; }, 'autoGoal')));
            } else if (d.auto === 'FOCUS') {
                rows.push(selectRow('Zeitraum', FOCUS_PERIODS, d.focusPeriod, v => { d.focusPeriod = v; rerender(); }));
                rows.push(h('label', { class: 'set-row' },
                    h('span', { class: 'set-label' }, d.focusPeriod === 'WEEK' ? 'Minuten pro Woche' : 'Minuten pro Tag'),
                    inlineNumber(d.focusMinutesGoal, v => { d.focusMinutesGoal = v; }, 'autoGoal')));
                const categories = (focusCategories || []).map(c => [c.id, c.name]);
                // Eine inzwischen geloeschte Kategorie darf ein bestehendes Co-Habit behalten.
                if (d.focusCategoryId && !categories.some(([id]) => id === d.focusCategoryId)) {
                    categories.push([d.focusCategoryId, d.focusCategoryName || 'Kategorie']);
                }
                rows.push(selectRow('Kategorie', [[null, 'Alle Bäume'], ...categories], d.focusCategoryId,
                    v => { d.focusCategoryId = v; }));
                if (focusCategories === null) {
                    focusCategories = [];
                    get('/focus/categories').then(list => { focusCategories = list || []; rerender(); })
                        .catch(() => { focusCategories = null; });
                }
            }
            sections.push(h('section', { class: 'set-group' }, rows));
        }
        return sections;
    }

    function inlineNumber(value, onInput, fid) {
        const input = h('input', { class: 'inline-number', type: 'text', inputmode: 'numeric', value: String(value ?? ''), 'data-fid': fid, autocomplete: 'off' });
        input.addEventListener('input', () => onInput(input.value));
        return input;
    }

    function goalSection() {
        const g = d.goal;
        return h('section', { class: 'set-group pad stack' },
            field('Zielwert', textInput(g.target, v => { g.target = v; }, { inputmode: 'decimal', placeholder: 'z. B. 100.000', 'data-fid': 'target' })),
            h('div', { class: 'field-group' }, h('span', { class: 'field-label' }, 'Zählart'),
                segmented([['ENTRIES', 'Einträge'], ['AMOUNT', 'Menge']], g.counting, v => { g.counting = v; rerender(); }, { className: 'wide on-field', label: 'Zählart' })),
            field('Bis', dateInput(g.deadline, v => { g.deadline = v; }, { min: edit ? null : dayIn(), 'data-fid': 'deadline' })),
            h('div', { class: 'field-group' }, h('span', { class: 'field-label' }, 'Modus'),
                segmented([['INDIVIDUAL', 'Einzelziel'], ['TEAM', 'Teamziel']], g.mode, v => { g.mode = v; }, { className: 'wide on-field', label: 'Modus' })));
    }

    function challengeSection() {
        const c = d.challenge;
        return h('section', { class: 'set-group pad stack' },
            h('div', { class: 'tiles two', style: 'gap:10px' },
                field('Start', dateInput(c.start, v => { c.start = v; }, { 'data-fid': 'start' })),
                field('Ende', dateInput(c.end, v => { c.end = v; }, { 'data-fid': 'end' }))),
            h('div', { class: 'set-group', style: 'margin:0;padding:0 2px' },
                selectRow('Wertung', [['MOST_ENTRIES', 'Meiste Einträge'], ['HIGHEST_SUM', 'Höchste Summe'], ['FIRST_TO_TARGET', 'Zuerst zum Zielwert']], c.scoring, v => { c.scoring = v; rerender(); }),
                selectRow('Wiederholung', [['NONE', 'Keine'], ['WEEKLY', 'Wöchentlich'], ['MONTHLY', 'Monatlich']], c.recurrence, v => { c.recurrence = v; })),
            c.scoring === 'FIRST_TO_TARGET' ? field('Zielwert', textInput(c.target, v => { c.target = v; }, { inputmode: 'decimal', 'data-fid': 'ctarget' })) : null,
            field('Einsatz', textInput(c.stake, v => { c.stake = v; }, { maxlength: 80, placeholder: 'z. B. Verlierer kocht für alle' })));
    }

    fill(wrap, ...build());
    return wrap;
}

function namePlaceholder(type) {
    return { STREAK: 'z. B. Laufen', ABSTINENCE: 'z. B. Ohne Zucker', GOAL: 'z. B. 100k Schritte', CHALLENGE: 'z. B. Wer kocht öfter?' }[type] || '';
}

function focusField(root, fid) {
    const el = fid && root.querySelector(`[data-fid="${fid}"]`);
    if (el) el.focus();
}

// --- Anlegen ---------------------------------------------------------------

export function mount(root, params, ctx) {
    const st = history.state || {};
    if (!draft || st.flow !== draft.flow) {
        draft = newDraft();
        history.replaceState({ ...st, app: true, flow: draft.flow, step: 1 }, '');
    }
    const d = draft;
    let step = (history.state && history.state.step) || 1;
    if (step > 1 && !d.type) step = 1;
    const page = h('div', { class: 'page create' });
    root.append(page);
    document.title = 'Neues Co-Habit – coHabit';

    function go(next) {
        history.pushState({ app: true, flow: d.flow, step: next }, '', '/cohabit/neu');
        step = next;
        window.scrollTo(0, 0);
        render();
    }

    function head(title) {
        return [
            h('div', { class: 'create-head' },
                step === 1
                    ? h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Schließen', onclick: () => goBack('/') }, icon('close'))
                    : h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: () => history.back() }, icon('back')),
                h('span', { class: 'create-step' }, `${title} · ${step} von 3`)),
            h('div', { class: 'steps', 'aria-hidden': 'true' }, [1, 2, 3].map(n => h('i', { class: n <= step ? 'on' : '' }))),
        ];
    }

    function render() {
        if (!ctx.alive()) return;
        if (step === 1) renderStep1();
        else if (step === 2) renderStep2();
        else renderStep3();
    }

    function renderStep1() {
        fill(page,
            ...head('Neues Co-Habit'),
            h('h1', { class: 'create-title' }, 'Was wollt ihr gemeinsam verfolgen?'),
            h('div', { class: 'type-cards' }, TYPES.map(t => h('button', {
                type: 'button', class: `card type-card tinted deco c-${t.color}`,
                'aria-pressed': String(d.type === t.key),
                onclick: () => {
                    if (d.type !== t.key) {
                        d.type = t.key;
                        d.color = t.color;
                    }
                    go(2);
                },
            },
            h('span', { class: 'type-name' }, TYPE_NAMES[t.key]),
            h('span', { class: 'type-desc', style: 'display:block' }, t.desc),
            h('span', { class: 'type-example', style: 'display:block' }, t.example)))),
            h('p', { class: 'type-note' }, 'Der Typ kann später nicht mehr geändert werden.'));
    }

    function renderStep2() {
        const errorEl = h('p', { class: 'form-msg err', role: 'alert' });
        const form = settingsForm(d, { errorEl });
        const next = h('button', { type: 'button', class: 'btn primary block' }, 'Weiter');
        next.addEventListener('click', async () => {
            errorEl.textContent = '';
            try {
                toConfig(d);
            } catch (err) {
                errorEl.textContent = err.message;
                focusField(page, err.field);
                return;
            }
            if (d.createdId) {
                // Schon angelegt (Einladungslink geteilt): Aenderungen speichern.
                next.classList.add('busy');
                try {
                    await put(`/cohabits/${enc(d.createdId)}`, toConfig(d, { forEdit: true }));
                } catch (err) {
                    errorEl.textContent = err.message;
                    return;
                } finally {
                    next.classList.remove('busy');
                }
            }
            go(3);
        });
        fill(page, ...head(TYPE_NAMES[d.type]), form, h('div', { class: 'action-bar' }, next));
    }

    function renderStep3() {
        let friends = null;
        let candidates = null;
        const seatsEl = h('span', { class: 'seats' });
        const listEl = h('div');
        const msg = h('p', { class: 'form-msg err', role: 'alert' });
        const start = h('button', { type: 'button', class: 'btn primary block' }, 'Co-Habit starten');

        const seatsUsed = () => (candidates ? candidates.seats.used : 1 + d.invites.size);
        const isInvited = person => (candidates
            ? candidates.people.some(c => c.person.id === person.id && c.status !== 'INVITE')
            : d.invites.has(person.id));

        async function invite(person) {
            if (d.createdId) {
                try {
                    candidates = await post(`/cohabits/${enc(d.createdId)}/invitations`, { personIds: [person.id] });
                    toast(`${person.displayName} eingeladen.`);
                } catch (err) {
                    showError(err);
                }
            } else {
                if (seatsUsed() >= MAX_SEATS) {
                    toast('Alle Plätze sind vergeben.', 'err');
                    return;
                }
                d.invites.add(person.id);
            }
            renderList();
        }

        function renderList() {
            seatsEl.textContent = `${seatsUsed()} von ${candidates ? candidates.seats.max : MAX_SEATS}`;
            const people = candidates ? candidates.people.map(c => ({ person: c.person, status: c.status })) : (friends || []).map(p => ({ person: p, status: d.invites.has(p.id) ? 'INVITED' : 'INVITE' }));
            if (!friends && !candidates) {
                fill(listEl, loadingState());
                return;
            }
            if (!people.length) {
                listEl.replaceChildren();
                return;
            }
            const full = seatsUsed() >= (candidates ? candidates.seats.max : MAX_SEATS);
            fill(listEl, h('div', { class: 'list' }, people.map(({ person, status }) => h('div', { class: 'person-row' },
                avatar(person, 44),
                h('div', { class: 'person-texts' },
                    h('div', { class: 'person-name' }, person.displayName),
                    h('div', { class: 'person-sub' }, `@${person.username}`)),
                status === 'INVITE'
                    ? h('button', { type: 'button', class: 'btn outline small', disabled: full, onclick: () => invite(person) }, 'Einladen')
                    : !d.createdId && status === 'INVITED'
                        ? h('button', { type: 'button', class: 'person-state', style: 'border:0;background:none', title: 'Zurücknehmen', onclick: () => { d.invites.delete(person.id); renderList(); } }, 'Eingeladen')
                        : h('span', { class: 'person-state' }, status === 'MEMBER' ? 'Mitglied' : 'Eingeladen')))));
        }

        async function ensureCreated() {
            if (d.createdId) return d.createdId;
            const detail = await post('/cohabits', { ...toConfig(d), invitePersonIds: [...d.invites] });
            d.createdId = detail.summary.ref.id;
            remember(`detail:${d.createdId}`, detail);
            forget('today');
            forget('cohabitsList');
            try {
                candidates = await get(`/cohabits/${enc(d.createdId)}/invite-candidates`);
            } catch (err) { /* Liste bleibt beim alten Stand */ }
            return d.createdId;
        }

        const share = h('button', { type: 'button', class: 'btn' }, 'Teilen');
        share.addEventListener('click', async () => {
            share.classList.add('busy');
            msg.textContent = '';
            try {
                const cid = await ensureCreated();
                const link = await post(`/cohabits/${enc(cid)}/invite-link`, {});
                renderList();
                await shareLink(link.url, d.name.trim());
            } catch (err) {
                msg.textContent = err.message;
            } finally {
                share.classList.remove('busy');
            }
        });

        start.addEventListener('click', async () => {
            start.classList.add('busy');
            msg.textContent = '';
            try {
                const cid = await ensureCreated();
                draft = null;
                // Die Schritte des Anlegens aus dem Verlauf nehmen: Zurueck von der
                // Detailseite soll nicht wieder im Formular landen.
                replaceFlow(step - 1, `/c/${enc(cid)}`);
                toast(`„${d.name.trim()}“ gestartet.`);
            } catch (err) {
                msg.textContent = err.message;
            } finally {
                start.classList.remove('busy');
            }
        });

        const search = peopleSearch({ onInvite: invite, isInvited });
        fill(page,
            ...head(d.name.trim() || TYPE_NAMES[d.type]),
            h('div', { class: 'page-head', style: 'padding-top:0' }, h('h1', { class: 'create-title', style: 'margin:0' }, 'Wer macht mit?'), seatsEl),
            h('div', { class: 'stack' },
                h('div', { class: 'card invite-link-card deco tr' }, h('span', { class: 'invite-link-title' }, 'Einladungslink'), share),
                search.el,
                listEl,
                msg),
            h('div', { class: 'action-bar' }, start));
        renderList();
        const loadList = d.createdId
            ? get(`/cohabits/${enc(d.createdId)}/invite-candidates`).then(res => { candidates = res; })
            : get('/friends').then(res => { friends = res.friends || []; });
        loadList.then(renderList).catch(err => { fill(listEl, errorState(err.message)); });
    }

    render();
    return {};
}

// --- Bearbeiten ------------------------------------------------------------

export function mountEdit(root, params, ctx) {
    const id = params.id;
    const page = h('div', { class: 'page create' });
    root.append(page);
    const back = () => goBack(`/c/${enc(id)}`);
    page.append(h('div', { class: 'create-head' },
        h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: back }, icon('back')),
        h('span', { class: 'create-step' }, 'Bearbeiten')), loadingState());

    get(`/cohabits/${enc(id)}`).then(detail => {
        if (!ctx.alive()) return;
        const d = draftFrom(detail.config);
        const errorEl = h('p', { class: 'form-msg err', role: 'alert' });
        const save = h('button', { type: 'button', class: 'btn primary block' }, 'Speichern');
        save.addEventListener('click', async () => {
            errorEl.textContent = '';
            let config;
            try {
                config = toConfig(d, { forEdit: true });
            } catch (err) {
                errorEl.textContent = err.message;
                focusField(page, err.field);
                return;
            }
            save.classList.add('busy');
            try {
                const fresh = await put(`/cohabits/${enc(id)}`, config);
                remember(`detail:${id}`, fresh);
                forget('today');
                toast('Gespeichert.');
                back();
            } catch (err) {
                errorEl.textContent = err.message;
            } finally {
                save.classList.remove('busy');
            }
        });
        document.title = `${detail.summary.ref.name} bearbeiten – coHabit`;
        fill(page,
            h('div', { class: 'create-head' },
                h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: back }, icon('back')),
                h('span', { class: 'create-step' }, `${TYPE_NAMES[d.type]} · Bearbeiten`)),
            h('div', { style: 'height:18px' }),
            settingsForm(d, { edit: true, errorEl }),
            h('div', { class: 'action-bar' }, save));
    }).catch(err => {
        if (!ctx.alive()) return;
        fill(page, page.firstChild, errorState(err.message));
    });
    return {};
}
