// Reaktionen (Vertrag 2.7, 3.6): feste Auswahl, je Person hoechstens eine je
// Art. Ein Beweisfoto ist ein Ereignis - Chat-Post und Timeline-Eintrag zeigen
// dieselben Reaktionen, weil beide dasselbe Ziel "event:<id>" tragen.
import { api, enc } from './api.js';
import { h, icon, showError, fill } from './dom.js';

export const REACTIONS = [
    ['STARK', 'Stark'],
    ['RESPEKT', 'Respekt'],
    ['WEITER_SO', 'Weiter so'],
    ['HAHA', 'Haha'],
];

export async function setReaction(target, reaction, on) {
    const res = on
        ? await api('/reactions', { method: 'POST', body: { target, reaction } })
        : await api(`/reactions?target=${enc(target)}&reaction=${enc(reaction)}`, { method: 'DELETE' });
    return res.reactions || [];
}

function merged(reactions) {
    const byKey = new Map((reactions || []).map(r => [r.reaction, r]));
    return REACTIONS.map(([key, label]) => byKey.get(key) || { reaction: key, label, count: 0, mine: false });
}

/**
 * Leiste mit den vorhandenen Reaktionen und einem Knopf, der alle vier zur
 * Wahl aufklappt. `item` wird mit der Antwort des Dienstes aktualisiert.
 */
export function reactionBar(item, { onChange, trailing } = {}) {
    const wrap = h('div', { class: 'reactions' });
    let picking = false;

    const render = () => {
        const all = merged(item.reactions);
        const shown = picking ? all : all.filter(r => r.count > 0);
        wrap.classList.toggle('picking', picking);
        fill(wrap,
            ...shown.map(r => h('button', {
                type: 'button',
                class: `reaction${r.mine ? ' mine' : ''}`,
                'aria-pressed': String(!!r.mine),
                onclick: () => toggle(r),
            }, r.count > 0 ? `${r.label} · ${r.count}` : r.label)),
            h('button', {
                type: 'button', class: 'reaction add',
                'aria-label': picking ? 'Auswahl schließen' : 'Reagieren',
                title: picking ? 'Schließen' : 'Reagieren',
                'aria-expanded': String(picking),
                onclick: () => { picking = !picking; render(); },
            }, icon(picking ? 'close' : 'smile')),
            trailing || null);
    };

    async function toggle(reaction) {
        const turnOn = !reaction.mine;
        try {
            item.reactions = await setReaction(item.reactionTarget, reaction.reaction, turnOn);
            picking = false;
            render();
            if (onChange) onChange(item.reactions);
        } catch (err) {
            showError(err);
        }
    }

    render();
    return wrap;
}

/** Vier Knoepfe zum Umschalten - fuer das Aktionsblatt einer Nachricht. */
export function reactionPicker(item, onChange) {
    const wrap = h('div', { class: 'reactions picking' });
    const render = () => {
        fill(wrap, ...merged(item.reactions).map(r => h('button', {
            type: 'button', class: `reaction${r.mine ? ' mine' : ''}`, 'aria-pressed': String(!!r.mine),
            onclick: async () => {
                try {
                    item.reactions = await setReaction(item.reactionTarget, r.reaction, !r.mine);
                    render();
                    if (onChange) onChange(item.reactions);
                } catch (err) {
                    showError(err);
                }
            },
        }, r.count > 0 ? `${r.label} · ${r.count}` : r.label)));
    };
    render();
    return wrap;
}
