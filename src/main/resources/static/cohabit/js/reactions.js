// Reaktionen (Vertrag 2.7a): Emojis, je Person hoechstens eines je Nachricht
// bzw. Ereignis. Ein neues ersetzt das eigene, dasselbe noch einmal nimmt es
// zurueck. Ein Beweisfoto ist ein Ereignis - Chat-Post und Timeline-Eintrag
// zeigen dieselben Reaktionen, weil beide dasselbe Ziel "event:<id>" tragen.
//
// Oberflaeche: die Pille unter der Nachricht (bis zu drei Emojis, ab 2 die
// Summe) oeffnet das Blatt „Reaktionen"; langer Druck, Rechtsklick oder der
// Smiley-Knopf oeffnen die Leiste mit der Schnellauswahl und „+".
import { api, enc } from './api.js';
import { h, icon, showError, fill, openDialog, sheetHead, actionSheet } from './dom.js';
import { avatar } from './ui.js';
import { isMe, personName } from './format.js';
import { emojiGroups, emojisIn, sameEmoji, searchEmoji } from './emoji.js';

export const QUICK = ['💪', '🔥', '🙌', '❤️', '😂', '👏'];

const LONG_PRESS_MS = 480;

export function myReaction(item) {
    const own = (item.reactions || []).find(r => r.mine && r.count > 0);
    return own ? own.reaction : null;
}

export async function setReaction(target, reaction) {
    const res = await api('/reactions', { method: 'POST', body: { target, reaction } });
    return res.reactions || [];
}

/** Nimmt die eigene zurueck; mit `reaction` nur, wenn sie noch dieselbe ist (Vertrag 2.7a). */
export async function removeReaction(target, reaction) {
    const which = reaction ? `&reaction=${enc(reaction)}` : '';
    const res = await api(`/reactions?target=${enc(target)}${which}`, { method: 'DELETE' });
    return res.reactions || [];
}

/** Waehlt `emoji`: setzt es - oder nimmt es zurueck, wenn es schon das eigene ist. */
export async function react(item, emoji) {
    const mine = myReaction(item);
    item.reactions = mine && sameEmoji(mine, emoji)
        ? await removeReaction(item.reactionTarget, mine)
        : await setReaction(item.reactionTarget, emoji);
    return item.reactions;
}

function shown(item) {
    // Der Dienst sortiert schon (Anzahl absteigend, dann frueheste); stabil nachsortiert,
    // falls eine Antwort einmal anders kommt.
    return (item.reactions || []).filter(r => r.count > 0).sort((a, b) => b.count - a.count);
}

/**
 * Pille unter einer Nachricht bzw. Karte: bis zu drei Emojis, die haeufigsten
 * zuerst, ab zwei Reaktionen die Summe; mit der eigenen in Akzentfarbe umrandet.
 * Ohne Reaktionen null.
 */
export function reactionPill(item, { onChange } = {}) {
    const list = shown(item);
    if (!list.length) return null;
    const total = list.reduce((sum, r) => sum + r.count, 0);
    const mine = list.some(r => r.mine);
    return h('button', {
        type: 'button',
        class: `react-pill${mine ? ' mine' : ''}`,
        'aria-label': `Reaktionen: ${list.map(r => `${r.reaction} ${r.count}`).join(', ')}`,
        onclick: event => {
            event.stopPropagation();
            openReactionList(item, { onChange });
        },
    },
    list.slice(0, 3).map(r => h('span', { class: 'react-emoji', 'aria-hidden': 'true' }, r.reaction)),
    total >= 2 ? h('span', { class: 'react-count', 'aria-hidden': 'true' }, String(total)) : null);
}

/** Blatt „Reaktionen": Avatar, Name, Emoji; die eigene Zeile mit „Entfernen". */
export function openReactionList(item, { onChange } = {}) {
    const ref = {};
    const list = h('div', { class: 'react-list' });
    const render = () => {
        const rows = [];
        for (const r of shown(item)) {
            for (const person of r.people || []) rows.push({ person, emoji: r.reaction, mine: r.mine && isMe(person) });
        }
        if (!rows.length) {
            ref.current.close();
            return;
        }
        fill(list, rows.map(row => h('div', { class: 'react-row' },
            avatar(row.person, 40),
            h('span', { class: 'react-row-name' }, personName(row.person)),
            row.mine ? h('button', {
                type: 'button', class: 'text-btn',
                onclick: async event => {
                    const btn = event.currentTarget;
                    btn.disabled = true;
                    try {
                        item.reactions = await removeReaction(item.reactionTarget, row.emoji);
                        if (onChange) onChange(item.reactions);
                        render();
                    } catch (err) {
                        btn.disabled = false;
                        showError(err);
                    }
                },
            }, 'Entfernen') : null,
            h('span', { class: 'react-row-emoji' }, row.emoji))));
    };
    ref.current = openDialog([...sheetHead('Reaktionen', null, ref), h('div', { class: 'sheet-body' }, list)],
        { kind: 'sheet', className: 'react-sheet', label: 'Reaktionen' });
    render();
    return ref.current;
}

/** Schnellauswahl; ein eigenes Emoji ausserhalb der sechs steht hervorgehoben dabei. */
function quickBar(item, { onPick, onMore }) {
    const mine = myReaction(item);
    const choices = [...QUICK];
    if (mine && !QUICK.some(q => sameEmoji(q, mine))) choices.push(mine);
    return h('div', { class: 'quick-bar', role: 'group', 'aria-label': 'Reagieren', style: `--n:${choices.length + 1}` },
        choices.map(emoji => {
            const on = !!mine && sameEmoji(mine, emoji);
            return h('button', {
                type: 'button', class: `quick-emoji${on ? ' mine' : ''}`, 'aria-pressed': String(on),
                title: on ? 'Entfernen' : null, onclick: () => onPick(emoji),
            }, emoji);
        }),
        h('button', { type: 'button', class: 'quick-emoji more', 'aria-label': 'Weitere Emojis', title: 'Weitere Emojis', onclick: onMore }, icon('plus')));
}

/**
 * Die Leiste zu einer Nachricht bzw. einem Ereignis, zusammen mit den Aktionen
 * (Loeschen, Melden, Blockieren) in einem Blatt. Waehlen schliesst es.
 */
export function openReactionBar(item, { title = 'Reagieren', actions = [], onChange } = {}) {
    let sheet = null;
    const pick = async emoji => {
        if (sheet) sheet.close();
        try {
            await react(item, emoji);
            if (onChange) onChange(item.reactions);
        } catch (err) {
            showError(err);
        }
    };
    const bar = quickBar(item, {
        onPick: pick,
        onMore: () => {
            sheet.close();
            openEmojiPicker({ current: myReaction(item), onPick: pick });
        },
    });
    sheet = actionSheet(title, actions, bar);
    return sheet;
}

/**
 * Jedes Emoji: Kategorien zum Springen, Raster, Suche ueber deutsche Woerter.
 * Ein getipptes oder eingefuegtes Emoji im Suchfeld ist selbst ein Treffer.
 */
export function openEmojiPicker({ current, onPick }) {
    const ref = {};
    const groups = emojiGroups();
    const choose = emoji => {
        ref.current.close();
        onPick(emoji);
    };
    const cell = emoji => h('button', {
        type: 'button', class: `emoji-cell${current && sameEmoji(current, emoji) ? ' mine' : ''}`, onclick: () => choose(emoji),
    }, emoji);

    const search = h('input', {
        class: 'field emoji-search', type: 'search', placeholder: 'Suchen', 'aria-label': 'Emoji suchen',
        autocomplete: 'off', enterkeyhint: 'search',
        autofocus: matchMedia('(hover: hover)').matches ? true : null,
    });
    const sections = groups.map(group => h('section', { class: 'emoji-section' },
        h('h3', { class: 'emoji-group' }, group.name),
        h('div', { class: 'emoji-grid' }, group.items.map(item => cell(item.emoji)))));
    const nav = h('div', { class: 'emoji-nav', style: `--n:${groups.length}` }, groups.map((group, i) => h('button', {
        type: 'button', class: 'emoji-tab', 'aria-label': group.name, title: group.name,
        onclick: () => sections[i].scrollIntoView({ block: 'start' }),
    }, group.icon)));
    const results = h('div', { class: 'emoji-results', hidden: true });
    const all = h('div', { class: 'emoji-all' }, sections);

    const render = () => {
        const query = search.value.trim();
        const searching = !!query;
        nav.hidden = searching;
        all.hidden = searching;
        results.hidden = !searching;
        if (!searching) return;
        const hits = [...emojisIn(query)];
        for (const emoji of searchEmoji(query)) if (!hits.some(e => sameEmoji(e, emoji))) hits.push(emoji);
        fill(results, hits.length
            ? h('div', { class: 'emoji-grid' }, hits.map(cell))
            : h('p', { class: 'emoji-empty' }, 'Nichts gefunden.'));
    };
    search.addEventListener('input', render);
    search.addEventListener('keydown', event => {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        const first = results.querySelector('.emoji-cell');
        if (first) first.click();
    });

    ref.current = openDialog([
        ...sheetHead('Emoji', null, ref),
        h('div', { class: 'emoji-top' }, search, nav),
        h('div', { class: 'emoji-body' }, results, all),
    ], { kind: 'sheet', className: 'emoji-sheet', label: 'Emoji' });
    return ref.current;
}

/**
 * Langer Druck (Touch) bzw. Kontextmenue (Rechtsklick, Android meldet langen
 * Druck ebenfalls so) ruft `fn` - je Beruehrung hoechstens einmal, und der
 * Klick nach dem Loslassen geht nicht mehr an das Element darunter.
 */
export function onLongPress(node, fn) {
    let timer = null;
    let touching = false;
    let fired = false;
    let startX = 0;
    let startY = 0;
    const fire = () => {
        if (touching) {
            if (fired) return;
            fired = true;
        }
        fn();
    };
    const cancel = () => {
        clearTimeout(timer);
        timer = null;
    };
    node.addEventListener('contextmenu', event => {
        if (event.target.closest('a, input, textarea')) return;
        event.preventDefault();
        fire();
    });
    node.addEventListener('touchstart', event => {
        cancel();
        touching = true;
        fired = false;
        if (event.touches.length !== 1) return;
        startX = event.touches[0].clientX;
        startY = event.touches[0].clientY;
        timer = setTimeout(() => {
            timer = null;
            fire();
        }, LONG_PRESS_MS);
    }, { passive: true });
    node.addEventListener('touchmove', event => {
        const t = event.touches[0];
        if (Math.abs(t.clientX - startX) > 8 || Math.abs(t.clientY - startY) > 8) cancel();
    }, { passive: true });
    node.addEventListener('touchend', event => {
        cancel();
        if (fired && event.cancelable) event.preventDefault();
        touching = false;
    });
    node.addEventListener('touchcancel', () => {
        cancel();
        touching = false;
    });
}
