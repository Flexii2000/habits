// Abhaken und Eintragen (Vertrag 3.5, 5.3). Ohne Foto und ohne Wert genuegt ein
// Tipp; mit Wert oder zum Nachtragen oeffnet das Eintragsblatt; bei
// Beweisfoto-Pflicht das Beweisfoto-Blatt (S. 15); bei Abstinenz die Rueckfrage
// zur Unterbrechung. Jeder Eintrag traegt eine eigene UUID - ein doppelter
// Tipp oder ein wiederholter Versuch legt nichts doppelt an.
import { get, post, put, del, enc } from './api.js';
import { h, icon, openDialog, sheetHead, toast, showError, uuid, confirmDialog, fill } from './dom.js';
import { pickFile, resizeImage, uploadPhoto } from './photo.js';
import { colorClass } from './ui.js';
import { addDays, dayIn, dayShort, isMe, joinNames, parseNumber, unitLabel } from './format.js';

const cohabitPath = id => `/cohabits/${enc(id)}`;

/** Tage, auf die sich noch eintragen laesst: heute bis `backfillFrom`, neueste zuerst. */
export function backfillDays(detail) {
    if (!detail || !detail.backfillFrom) return [];
    const today = dayIn(new Date(), detail.config && detail.config.timezone);
    const days = [];
    for (let day = today; day >= detail.backfillFrom && days.length < 20; day = addDays(day, -1)) days.push(day);
    return days;
}

function daySelect(days, selected, timeZone) {
    const today = dayIn(new Date(), timeZone);
    const select = h('select', { class: 'day-select', 'aria-label': 'Tag' },
        days.map(day => h('option', { value: day }, dayShort(day, today))));
    select.value = selected && days.includes(selected) ? selected : days[0];
    return select;
}

/**
 * „Anderer Tag": erst ein leiser Knopf, auf Wunsch die Auswahl. Mit
 * vorgegebenem Tag (Tipp auf eine Zelle der Woche) gleich offen.
 */
function dayPicker(detail, preset) {
    const days = backfillDays(detail);
    const wrap = h('div', { class: 'field-group' });
    if (days.length < 2) return { el: wrap, value: () => preset || null };
    const zone = detail.config && detail.config.timezone;
    const select = daySelect(days, preset, zone);
    const label = h('label', { class: 'field-label' }, 'Tag');
    const reveal = () => { fill(wrap, label, select); };
    if (preset && preset !== days[0]) reveal();
    else wrap.append(h('button', { type: 'button', class: 'text-btn', onclick: reveal }, 'Anderer Tag'));
    return {
        el: wrap,
        // null heisst „heute" in der Zone des Co-Habits - das entscheidet der Dienst.
        value: () => (select.isConnected && select.value !== days[0] ? select.value : null),
    };
}

function othersOf(people) {
    return (people || []).filter(p => !isMe(p)).map(p => p.displayName);
}

function valueField(unit, initial) {
    const input = h('input', {
        class: 'field', type: 'text', inputmode: 'decimal', autocomplete: 'off',
        placeholder: '0', 'aria-label': unitLabel(unit) || 'Wert',
        value: initial == null ? '' : String(initial).replace('.', ','),
    });
    return {
        el: h('div', { class: 'field-group' },
            h('label', { class: 'field-label' }, 'Wert'),
            h('div', { class: 'field-wrap' }, input, h('span', { class: 'field-suffix' }, unitLabel(unit)))),
        input,
        read() {
            const n = parseNumber(input.value);
            if (n == null || Number.isNaN(n) || n <= 0) {
                input.classList.add('invalid');
                input.focus();
                return null;
            }
            input.classList.remove('invalid');
            return n;
        },
    };
}

/** Dauer (ganze Minuten) und Distanz (km) eines Laufs - rechnen tut der Dienst. */
function runFields(initial) {
    const minutes = h('input', {
        class: 'field', type: 'text', inputmode: 'numeric', autocomplete: 'off', placeholder: '0', 'aria-label': 'Dauer in Minuten',
        value: initial ? String(initial.durationMinutes) : '',
    });
    const km = h('input', {
        class: 'field', type: 'text', inputmode: 'decimal', autocomplete: 'off', placeholder: '0,0', 'aria-label': 'Distanz in km',
        value: initial ? String(initial.distanceKm).replace('.', ',') : '',
    });
    const group = (label, input, suffix) => h('div', { class: 'field-group' },
        h('label', { class: 'field-label' }, label),
        h('div', { class: 'field-wrap' }, input, h('span', { class: 'field-suffix' }, suffix)));
    const check = (input, ok) => {
        input.classList.toggle('invalid', !ok);
        if (!ok) input.focus();
        return ok;
    };
    return {
        el: h('div', { class: 'run-fields' }, group('Dauer', minutes, 'Min.'), group('Distanz', km, 'km')),
        input: minutes,
        read() {
            const m = parseNumber(minutes.value);
            const d = parseNumber(km.value);
            if (!check(minutes, Number.isInteger(m) && m > 0)) return null;
            if (!check(km, d > 0)) return null;
            return { durationMinutes: m, distanceKm: d };
        },
    };
}

/** Nach dem Eintragen eines Laufs: was er gebracht hat. */
function savedText(res, fallback) {
    const run = res && res.checkin && res.checkin.run;
    return run ? `${run.pointsText} · ${run.breakdownText}` : fallback;
}

function textField(label, placeholder, initial, maxlength = 500) {
    const input = h('input', { class: 'field', type: 'text', placeholder, maxlength, value: initial || '', autocomplete: 'off' });
    return {
        el: h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, label), input),
        read: () => (input.value.trim() ? input.value.trim() : null),
    };
}

async function loadDetail(summary, detail) {
    if (detail) return detail;
    try {
        return await get(cohabitPath(summary.ref.id));
    } catch (err) {
        return null;
    }
}

/**
 * Einstieg von jeder Abhak-Stelle (Karte, Liste, Detailseite, Chat).
 * `onDone(detail)` bekommt das frische CohabitDetail aus der Antwort.
 */
export async function checkIn(summary, { detail, date, onDone } = {}) {
    const type = summary.ref.type;
    if (type === 'ABSTINENCE') return breakDialog(summary, detail, date, onDone);
    if (summary.photoRequired) return photoSheet(summary, detail, date, onDone);
    if (summary.valueUnit || summary.runEntry || date) return entrySheet(summary, detail, date, onDone);
    return quickCheckIn(summary, onDone);
}

function doneText(type) {
    return type === 'STREAK' ? 'Abgehakt.' : 'Eingetragen.';
}

async function quickCheckIn(summary, onDone) {
    const body = { id: uuid(), kind: 'DONE', date: null, value: null, note: null, photoId: null, caption: null };
    try {
        const res = await post(`${cohabitPath(summary.ref.id)}/checkins`, body);
        toast(doneText(summary.ref.type));
        if (onDone) onDone(res.cohabit);
        return res;
    } catch (err) {
        showError(err);
        return null;
    }
}

/** Beweisfoto-Blatt (S. 15): Datei-Eingabe mit Kamera, Galerie, Frontkamera. */
function photoSheet(summary, knownDetail, presetDate, onDone) {
    const ref = {};
    let blob = null;
    let previewUrl = null;
    let uploaded = null;
    let photoKey = uuid();
    let checkinId = uuid();
    const name = summary.ref.name;

    const preview = h('img', { alt: 'Gewähltes Foto', hidden: true });
    const label = h('span', { class: 'camera-label', 'aria-hidden': 'true' }, icon('camera'));
    const choose = async capture => {
        const file = await pickFile({ capture });
        if (!file) return;
        try {
            submit.disabled = true;
            blob = await resizeImage(file);
            uploaded = null;
            photoKey = uuid();
            checkinId = uuid();
            if (previewUrl) URL.revokeObjectURL(previewUrl);
            previewUrl = URL.createObjectURL(blob);
            preview.src = previewUrl;
            preview.hidden = false;
            label.hidden = true;
        } catch (err) {
            showError(err);
        } finally {
            submit.disabled = !blob;
        }
    };
    const camera = h('div', { class: `camera ${colorClass(summary.ref.color)}` },
        label, preview,
        h('div', { class: 'camera-controls' },
            h('button', { type: 'button', class: 'cam-btn', 'aria-label': 'Aus der Galerie', title: 'Galerie', onclick: () => choose(null) }, icon('image')),
            h('button', { type: 'button', class: 'shutter', 'aria-label': 'Foto aufnehmen', title: 'Foto aufnehmen', onclick: () => choose('environment') }),
            h('button', { type: 'button', class: 'cam-btn', 'aria-label': 'Frontkamera', title: 'Kamera wechseln', onclick: () => choose('user') }, icon('switchCamera'))));

    const value = summary.valueUnit ? valueField(summary.valueUnit) : null;
    const run = summary.runEntry ? runFields() : null;
    const caption = textField('Caption (optional)', 'Wie war\'s?', '', 500);
    const dayArea = h('div');
    let day = { value: () => presetDate || null };
    const hint = h('p', { class: 'hint' });
    const setHint = people => {
        const others = othersOf(people);
        hint.textContent = others.length
            ? `Erscheint im Chat von ${name} und in der Timeline von ${joinNames(others)}.`
            : `Erscheint im Chat von ${name}.`;
    };
    setHint(summary.members);

    const submitLabel = run ? summary.checkInLabel || 'Lauf eintragen' : 'Posten & abhaken';
    const submit = h('button', { type: 'button', class: 'btn accent block', disabled: true }, submitLabel);
    submit.addEventListener('click', async () => {
        if (!blob) return;
        const amount = value ? value.read() : null;
        if (value && amount == null) return;
        const runValues = run ? run.read() : null;
        if (run && !runValues) return;
        submit.classList.add('busy');
        submit.textContent = 'Wird gepostet …';
        try {
            if (!uploaded) uploaded = await uploadPhoto(blob, photoKey);
            const res = await post(`${cohabitPath(summary.ref.id)}/checkins`, {
                id: checkinId, kind: 'DONE', date: day.value(), value: amount, note: null,
                photoId: uploaded.id, caption: caption.read(), ...runValues,
            });
            ref.current.close();
            toast(savedText(res, 'Gepostet.'));
            if (onDone) onDone(res.cohabit);
        } catch (err) {
            showError(err);
        } finally {
            submit.classList.remove('busy');
            submit.textContent = submitLabel;
        }
    });

    ref.current = openDialog([
        ...sheetHead(`${name} abhaken`, 'Beweisfoto erforderlich', ref),
        h('div', { class: 'sheet-body' }, camera, value ? value.el : null, run ? run.el : null, caption.el, dayArea, hint),
        h('div', { class: 'sheet-actions' }, submit),
    ], {
        kind: 'sheet', className: 'photo-sheet', label: `${name} abhaken`,
        onClose: () => { if (previewUrl) URL.revokeObjectURL(previewUrl); },
    });

    loadDetail(summary, knownDetail).then(detail => {
        if (!detail || !ref.current.isConnected) return;
        day = dayPicker(detail, presetDate);
        fill(dayArea, day.el);
        setHint(detail.members.filter(m => m.state !== 'INVITED').map(m => m.person));
    });
}

/** Eintragsblatt: Wert und Notiz, Nachtragen ueber „Anderer Tag". */
function entrySheet(summary, knownDetail, presetDate, onDone) {
    const ref = {};
    const checkinId = uuid();
    const value = summary.valueUnit ? valueField(summary.valueUnit) : null;
    const run = summary.runEntry ? runFields() : null;
    const note = textField('Notiz (optional)', '', '', 500);
    const dayArea = h('div');
    let day = { value: () => presetDate || null };
    const label = summary.ref.type === 'STREAK' ? 'Abhaken' : 'Eintragen';
    const submit = h('button', { type: 'button', class: 'btn primary block' }, label);
    submit.addEventListener('click', async () => {
        const amount = value ? value.read() : null;
        if (value && amount == null) return;
        const runValues = run ? run.read() : null;
        if (run && !runValues) return;
        submit.classList.add('busy');
        try {
            const res = await post(`${cohabitPath(summary.ref.id)}/checkins`, {
                id: checkinId, kind: 'DONE', date: day.value(), value: amount,
                note: note.read(), photoId: null, caption: null, ...runValues,
            });
            ref.current.close();
            toast(savedText(res, doneText(summary.ref.type)));
            if (onDone) onDone(res.cohabit);
        } catch (err) {
            showError(err);
        } finally {
            submit.classList.remove('busy');
        }
    });
    const title = summary.checkInLabel && summary.canCheckIn ? summary.checkInLabel : `${summary.ref.name} eintragen`;
    ref.current = openDialog([
        ...sheetHead(title, summary.ref.name !== title ? summary.ref.name : null, ref),
        h('div', { class: 'sheet-body' }, value ? value.el : null, run ? run.el : null, note.el, dayArea),
        h('div', { class: 'sheet-actions' }, submit),
    ], { kind: 'sheet', label: title });
    const first = value || run;
    if (first) setTimeout(() => first.input.focus(), 50);

    loadDetail(summary, knownDetail).then(detail => {
        if (!detail || !ref.current.isConnected) return;
        day = dayPicker(detail, presetDate);
        fill(dayArea, day.el);
    });
}

/** Unterbrechung bei Abstinenz - mit Rueckfrage, sie setzt die Serie zurueck. */
async function breakDialog(summary, knownDetail, presetDate, onDone) {
    const detail = await loadDetail(summary, knownDetail);
    const ref = {};
    const checkinId = uuid();
    const days = backfillDays(detail);
    const zone = detail && detail.config && detail.config.timezone;
    const select = days.length > 1 ? daySelect(days, presetDate, zone) : null;
    const note = h('input', { class: 'field', type: 'text', placeholder: 'Notiz (optional)', maxlength: 500, 'aria-label': 'Notiz' });
    const confirmBtn = h('button', { type: 'button', class: 'btn primary' }, 'Eintragen');
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.classList.add('busy');
        try {
            const res = await post(`${cohabitPath(summary.ref.id)}/checkins`, {
                id: checkinId, kind: 'BREAK', date: select && select.value !== days[0] ? select.value : null,
                value: null, note: note.value.trim() || null, photoId: null, caption: null,
            });
            ref.current.close();
            toast('Unterbrechung eingetragen.');
            if (onDone) onDone(res.cohabit);
        } catch (err) {
            showError(err);
        } finally {
            confirmBtn.classList.remove('busy');
        }
    });
    ref.current = openDialog(h('div', { class: 'confirm' },
        h('h2', { class: 'confirm-title' }, 'Unterbrechung eintragen?'),
        select ? h('div', { class: 'field-group', style: 'margin-top:16px;text-align:left' }, h('label', { class: 'field-label' }, 'Tag'), select) : null,
        h('div', { style: 'margin-top:12px' }, note),
        h('div', { class: 'btn-row' },
            h('button', { type: 'button', class: 'btn outline', autofocus: true, onclick: () => ref.current.close() }, 'Abbrechen'),
            confirmBtn)),
    { kind: 'modal', label: 'Unterbrechung eintragen' });
}

/** Eigenen Eintrag bearbeiten (Wert, Notiz, Caption) oder loeschen - in der Frist. */
export function editCheckin(detail, checkin, onDone) {
    const ref = {};
    const unit = detail.summary.valueUnit;
    const value = unit && checkin.kind === 'DONE' ? valueField(unit, checkin.value) : null;
    const run = detail.summary.runEntry && checkin.kind === 'DONE' ? runFields(checkin.run) : null;
    const note = textField('Notiz', '', checkin.note, 500);
    const caption = checkin.photoId ? textField('Caption', 'Wie war\'s?', checkin.caption, 500) : null;
    const cohabitId = detail.summary.ref.id;
    const save = h('button', { type: 'button', class: 'btn primary' }, 'Speichern');
    save.addEventListener('click', async () => {
        const amount = value ? value.read() : null;
        if (value && amount == null) return;
        const runValues = run ? run.read() : null;
        if (run && !runValues) return;
        save.classList.add('busy');
        try {
            const res = await put(`${cohabitPath(cohabitId)}/checkins/${enc(checkin.id)}`, {
                value: amount, note: note.read(), caption: caption ? caption.read() : null, ...runValues,
            });
            ref.current.close();
            toast(savedText(res, 'Gespeichert.'));
            onDone(res.cohabit);
        } catch (err) {
            showError(err);
        } finally {
            save.classList.remove('busy');
        }
    });
    const remove = h('button', { type: 'button', class: 'btn danger-outline' }, 'Löschen');
    remove.addEventListener('click', async () => {
        const ok = await confirmDialog({ title: 'Eintrag löschen?', confirm: 'Löschen', danger: true });
        if (!ok) return;
        try {
            const fresh = await del(`${cohabitPath(cohabitId)}/checkins/${enc(checkin.id)}`);
            ref.current.close();
            toast('Gelöscht.');
            onDone(fresh);
        } catch (err) {
            showError(err);
        }
    });
    const title = checkin.kind === 'BREAK' ? 'Unterbrechung' : 'Eintrag';
    ref.current = openDialog([
        ...sheetHead(title, dayShort(checkin.date, dayIn(new Date(), detail.config.timezone)), ref),
        h('div', { class: 'sheet-body' }, value ? value.el : null, run ? run.el : null, note.el, caption ? caption.el : null),
        h('div', { class: 'sheet-actions' }, h('div', { class: 'btn-row' }, remove, save)),
    ], { kind: 'sheet', label: title });
}
