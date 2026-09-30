// Stupsen, Melden, Blockieren (Vertrag 2.7).
import { post, del, enc } from './api.js';
import { h, openDialog, sheetHead, toast, showError, confirmDialog } from './dom.js';
import { avatar } from './ui.js';

/** Stupser an ein Mitglied; ohne Text schickt der Dienst „Heute noch „{Co-Habit}"?". */
export function nudgeDialog(cohabitRef, person, onDone) {
    const ref = {};
    const input = h('input', {
        class: 'field', type: 'text', maxlength: 60, autocomplete: 'off',
        placeholder: `Heute noch „${cohabitRef.name}“?`, 'aria-label': 'Text (optional)',
    });
    const send = h('button', { type: 'submit', class: 'btn accent block' }, 'Stupsen');
    const form = h('form', { class: 'sheet-body' },
        h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, 'Text (optional)'), input),
        send);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        send.classList.add('busy');
        try {
            await sendNudge(cohabitRef.id, person.id, input.value.trim() || null);
            ref.current.close();
            toast(`${person.displayName} angestupst.`);
            if (onDone) onDone();
        } catch (err) {
            showError(err);
        } finally {
            send.classList.remove('busy');
        }
    });
    ref.current = openDialog([...sheetHead(`${person.displayName} anstupsen`, cohabitRef.name, ref), form],
        { kind: 'sheet', label: `${person.displayName} anstupsen` });
}

export function sendNudge(cohabitId, personId, text) {
    return post(`/cohabits/${enc(cohabitId)}/nudges`, { to: personId, text });
}

export function reportDialog(cohabitId, message, onDone) {
    const ref = {};
    const reason = h('textarea', { class: 'field', maxlength: 500, rows: 3, placeholder: 'Grund (optional)', 'aria-label': 'Grund' });
    const send = h('button', { type: 'submit', class: 'btn primary block' }, 'Melden');
    const form = h('form', { class: 'sheet-body' }, reason, send);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        send.classList.add('busy');
        try {
            await post(`/cohabits/${enc(cohabitId)}/messages/${enc(message.id)}/report`, { reason: reason.value.trim() || null });
            ref.current.close();
            toast('Gemeldet.');
            if (onDone) onDone();
        } catch (err) {
            showError(err);
        } finally {
            send.classList.remove('busy');
        }
    });
    ref.current = openDialog([...sheetHead('Nachricht melden', null, ref), form], { kind: 'sheet', label: 'Nachricht melden' });
}

export async function blockPerson(person, onDone) {
    const ok = await confirmDialog({
        title: `${person.displayName} blockieren?`,
        confirm: 'Blockieren',
        danger: true,
    });
    if (!ok) return false;
    try {
        await post('/blocks', { personId: person.id });
        toast(`${person.displayName} blockiert.`);
        if (onDone) onDone();
        return true;
    } catch (err) {
        showError(err);
        return false;
    }
}

export async function unblockPerson(person, onDone) {
    try {
        await del(`/blocks/${enc(person.id)}`);
        toast(`${person.displayName} nicht mehr blockiert.`);
        if (onDone) onDone();
    } catch (err) {
        showError(err);
    }
}

export function personLine(person, sub) {
    return [avatar(person, 44), h('div', { class: 'person-texts' },
        h('div', { class: 'person-name' }, person.displayName),
        sub ? h('div', { class: 'person-sub' }, sub) : null)];
}
