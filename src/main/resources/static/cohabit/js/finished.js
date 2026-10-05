// Abschlussdialog (S. 17) fuer Challenge-Runden und Ziele - einmal je Person
// (dialogs/{id}/seen). „Gratulieren" setzt 💪 auf die Systemmeldung zum Ende
// und oeffnet den Chat (Vertrag 5.2, Punkt 17, und 2.7a). Welche Meldung das ist,
// sagt der Dienst in dialog.reactionTarget; fehlt das Feld, sucht die Seite sie.
import { get, post, enc } from './api.js';
import { h, openDialog, showError } from './dom.js';
import { colorClass } from './ui.js';
import { personName } from './format.js';
import { setReaction } from './reactions.js';
import { sameEmoji } from './emoji.js';

const CONGRATS = '💪';

function podium(entries) {
    if (!entries || !entries.length) return null;
    // Anordnung wie auf dem Treppchen: Zweiter links, Erster Mitte, Dritter rechts.
    const order = entries.length >= 3 ? [entries[1], entries[0], entries[2]]
        : entries.length === 2 ? [entries[1], entries[0]] : [entries[0]];
    const cols = order.length;
    return h('div', { class: 'podium', style: `grid-template-columns:repeat(${cols},minmax(0,1fr))` },
        order.map(entry => h('div', { class: `podium-place r${Math.min(3, entry.rank)}` },
            h('span', { class: 'podium-name' }, personName(entry.person)),
            h('div', { class: 'podium-block' },
                podiumScore(entry.scoreText != null ? entry.scoreText : String(entry.score)),
                h('span', null, `Platz ${entry.rank}`)))));
}

/** Lange Werte („41,5 km") kleiner, damit sie in den schmalen Block passen. */
function podiumScore(text) {
    const size = text.length <= 3 ? '' : text.length <= 5 ? ' mid' : ' long';
    return h('b', { class: `podium-score${size}` }, text);
}

/**
 * Sucht die Systemmeldung zum Ende: die juengste, die den Sieger oder das Ende
 * nennt; sonst die juengste Systemmeldung ueberhaupt (direkt nach dem Ende
 * kommt hoechstens noch die Meldung zur neuen Runde dazu).
 */
async function endMessage(cohabitId, dialog) {
    const res = await get(`/cohabits/${enc(cohabitId)}/messages?limit=50`);
    const system = (res.messages || []).filter(m => m.kind === 'SYSTEM' && !m.deleted).reverse();
    if (!system.length) return null;
    const winner = dialog.podium && dialog.podium[0] && dialog.podium[0].person.displayName;
    const endWords = /gewinnt|gewonnen|beendet|zu Ende|erreicht|verfehlt|Sieg/i;
    return system.find(m => endWords.test(m.systemText || '') && (!winner || (m.systemText || '').includes(winner)))
        || system.find(m => endWords.test(m.systemText || ''))
        || system[0];
}

export function finishedDialog(detail, { onTimeline, onCongratulate }) {
    const dialog = detail.dialog;
    const cohabitId = detail.summary.ref.id;
    const ref = {};
    let seen = false;
    const markSeen = () => {
        if (seen) return;
        seen = true;
        post(`/cohabits/${enc(cohabitId)}/dialogs/${enc(dialog.id)}/seen`, {}).catch(() => { /* beim naechsten Oeffnen erneut */ });
    };
    const isGoal = String(dialog.kind).toUpperCase() === 'GOAL';
    const congratulate = h('button', { type: 'button', class: 'btn primary' }, 'Gratulieren');
    congratulate.addEventListener('click', async () => {
        congratulate.classList.add('busy');
        try {
            if (dialog.reactionTarget) {
                await setReaction(dialog.reactionTarget, CONGRATS);
            } else {
                const message = await endMessage(cohabitId, dialog);
                if (message) {
                    const mine = (message.reactions || []).some(r => r.mine && sameEmoji(r.reaction, CONGRATS));
                    if (!mine) await setReaction(message.reactionTarget, CONGRATS);
                }
            }
            markSeen();
            ref.current.close();
            onCongratulate();
        } catch (err) {
            showError(err);
        } finally {
            congratulate.classList.remove('busy');
        }
    });
    const card = h('div', { class: `inv ${colorClass(detail.summary.ref.color)}` },
        h('div', { class: 'fin-top' },
            h('p', { class: 'fin-kicker' }, isGoal ? 'Ziel beendet' : 'Challenge beendet'),
            h('h2', { class: 'fin-title' }, dialog.title),
            podium(dialog.podium)),
        h('div', { class: 'fin-body' },
            dialog.stakeText ? h('div', { class: 'stake-box' }, h('small', null, 'Einsatz'), h('b', null, dialog.stakeText)) : null,
            dialog.nextText ? h('p', { class: 'hint' }, dialog.nextText) : null,
            h('div', { class: 'btn-row' },
                h('button', {
                    type: 'button', class: 'btn outline',
                    onclick: () => { markSeen(); ref.current.close(); onTimeline(); },
                }, 'Zur Timeline'),
                congratulate)));
    ref.current = openDialog(card, { kind: 'modal', className: 'finished', label: dialog.title, onClose: markSeen });
    return ref.current;
}
