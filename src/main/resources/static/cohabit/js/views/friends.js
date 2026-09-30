// Freunde & Einladungen (Vertrag 2.7, 3.3): Suche nach Nutzernamen,
// Co-Habit-Einladungen, Freundschaftsanfragen, Freundesliste, Freundes-Link,
// Blockierte.
import { get, post, del, enc } from '../api.js';
import { h, icon, actionSheet, confirmDialog, shareLink, showError, toast, fill } from '../dom.js';
import { avatar, colorClass, errorState, loadingState, sectionLabel } from '../ui.js';
import { invitationDialog } from '../invitation.js';
import { blockPerson, unblockPerson } from '../social.js';
import { navigate, refreshMe } from '../app.js';
import { subHead } from './profile.js';

function personRow(person, sub, actions) {
    return h('div', { class: 'person-row' },
        avatar(person, 44),
        h('div', { class: 'person-texts' },
            h('div', { class: 'person-name' }, person.displayName),
            h('div', { class: 'person-sub' }, sub != null ? sub : `@${person.username}`)),
        actions);
}

/** Suche nach Nutzernamen mit Aktion je Beziehung - auch fuer Schritt 3 beim Anlegen. */
export function peopleSearch({ onInvite, isInvited, placeholder = 'Nutzername suchen' } = {}) {
    const input = h('input', {
        class: 'pill-field', type: 'search', placeholder, autocomplete: 'off', autocapitalize: 'off',
        spellcheck: 'false', 'aria-label': placeholder,
    });
    const results = h('div', { class: 'list search-results', hidden: true });
    let timer = null;
    let seq = 0;

    async function run() {
        const q = input.value.trim();
        if (q.length < 2) {
            results.hidden = true;
            results.replaceChildren();
            return;
        }
        const mine = ++seq;
        try {
            const hits = await get(`/people/search?q=${enc(q)}`);
            if (mine !== seq) return;
            results.hidden = false;
            if (!hits.length) {
                fill(results, h('p', { class: 'person-row person-sub' }, 'Niemand gefunden.'));
                return;
            }
            fill(results, ...hits.map(hit => personRow(hit.person, null, relationAction(hit))));
        } catch (err) {
            if (mine === seq) showError(err);
        }
    }

    function relationAction(hit) {
        const p = hit.person;
        switch (hit.relation) {
            case 'SELF':
                return h('span', { class: 'person-state' }, 'Du');
            case 'FRIEND':
                if (onInvite) {
                    return isInvited && isInvited(p)
                        ? h('span', { class: 'person-state' }, 'Eingeladen')
                        : h('button', { type: 'button', class: 'btn outline small', onclick: async e => { e.currentTarget.classList.add('busy'); await onInvite(p); run(); } }, 'Einladen');
                }
                return h('span', { class: 'person-state' }, 'Freund');
            case 'REQUEST_SENT':
                return h('span', { class: 'person-state' }, 'Angefragt');
            case 'REQUEST_RECEIVED':
                return h('button', {
                    type: 'button', class: 'btn primary small',
                    onclick: async () => {
                        try {
                            const friends = await get('/friends');
                            const req = (friends.incoming || []).find(r => r.from.id === p.id);
                            if (req) await post(`/friends/requests/${enc(req.id)}/accept`, {});
                            toast(`Mit ${p.displayName} befreundet.`);
                            run();
                        } catch (err) {
                            showError(err);
                        }
                    },
                }, 'Annehmen');
            default:
                return h('button', {
                    type: 'button', class: 'btn outline small',
                    onclick: async () => {
                        try {
                            await post('/friends/requests', { username: p.username });
                            toast(`Anfrage an ${p.displayName} gesendet.`);
                            run();
                        } catch (err) {
                            showError(err);
                        }
                    },
                }, 'Anfragen');
        }
    }

    input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(run, 250);
    });
    return { el: h('div', { class: 'stack' }, input, results), refresh: run };
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    const body = h('div');
    const search = peopleSearch();
    page.append(subHead('Freunde & Einladungen'), search.el, body);

    let data = null;

    function render(failed) {
        if (!ctx.alive()) return;
        if (!data) {
            fill(body, failed ? errorState(failed, load) : loadingState());
            return;
        }
        const { friends, invitations, blocks } = data;
        fill(body,
            invitations.length ? [sectionLabel('Einladungen'), h('div', { class: 'list' }, invitations.map(inv => h('div', { class: 'person-row' },
                h('span', { class: `avatar ${colorClass(inv.cohabit.ref.color)}`, style: '--size:44px' }, inv.cohabit.ref.name.slice(0, 2).toUpperCase()),
                h('div', { class: 'person-texts' },
                    h('div', { class: 'person-name' }, inv.cohabit.ref.name),
                    h('div', { class: 'person-sub' }, `von ${inv.from.displayName} · ${inv.cohabit.typeLine || ''}`)),
                h('button', {
                    type: 'button', class: 'btn primary small',
                    onclick: () => invitationDialog(inv, {
                        onAccepted: detail => navigate(`/c/${enc(detail.summary.ref.id)}`),
                        onDeclined: load,
                    }),
                }, 'Ansehen'))))] : null,
            (friends.incoming || []).length ? [sectionLabel('Anfragen'), h('div', { class: 'list' }, friends.incoming.map(req => personRow(req.from, null,
                h('div', { class: 'person-actions' },
                    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Ablehnen', title: 'Ablehnen', onclick: () => answer(req, 'decline') }, icon('close')),
                    h('button', { type: 'button', class: 'btn primary small', onclick: () => answer(req, 'accept') }, 'Annehmen')))))] : null,
            sectionLabel('Freunde'),
            friendLinkCard(),
            (friends.friends || []).length
                ? h('div', { class: 'list' }, friends.friends.map(p => personRow(p, null,
                    h('button', { type: 'button', class: 'icon-btn', 'aria-label': `${p.displayName}: Aktionen`, onclick: () => friendActions(p) }, icon('more')))))
                : h('p', { class: 'empty' }, 'Noch keine Freunde.'),
            (friends.outgoing || []).length ? [sectionLabel('Gesendet'), h('div', { class: 'list' }, friends.outgoing.map(req => personRow(req.to, null,
                h('span', { class: 'person-state' }, 'Angefragt'))))] : null,
            blocks.length ? [sectionLabel('Blockiert'), h('div', { class: 'list' }, blocks.map(p => personRow(p, null,
                h('button', { type: 'button', class: 'btn outline small', onclick: () => unblockPerson(p, load) }, 'Aufheben'))))] : null);
    }

    function friendLinkCard() {
        const share = h('button', { type: 'button', class: 'btn' }, 'Teilen');
        share.addEventListener('click', async () => {
            share.classList.add('busy');
            try {
                const link = await post('/me/friend-link', {});
                await shareLink(link.url, 'coHabit');
            } catch (err) {
                showError(err);
            } finally {
                share.classList.remove('busy');
            }
        });
        return h('div', { class: 'card invite-link-card deco tr', style: 'margin-bottom:12px' },
            h('span', { class: 'invite-link-title' }, 'Freundes-Link'), share);
    }

    async function answer(req, action) {
        try {
            const res = await post(`/friends/requests/${enc(req.id)}/${action}`, {});
            if (res && res.friends) data.friends = res;
            toast(action === 'accept' ? `Mit ${req.from.displayName} befreundet.` : 'Anfrage abgelehnt.');
            render();
            refreshMe().catch(() => {});
        } catch (err) {
            showError(err);
        }
    }

    function friendActions(person) {
        actionSheet(person.displayName, [
            {
                label: 'Als Freund entfernen', icon: 'leave',
                onSelect: async () => {
                    const ok = await confirmDialog({ title: `${person.displayName} entfernen?`, confirm: 'Entfernen', danger: true });
                    if (!ok) return;
                    try {
                        await del(`/friends/${enc(person.id)}`);
                        toast('Entfernt.');
                        load();
                    } catch (err) {
                        showError(err);
                    }
                },
            },
            { label: 'Blockieren', icon: 'block', danger: true, onSelect: () => blockPerson(person, load) },
        ]);
    }

    async function load() {
        try {
            const [friends, invitations, blocks] = await Promise.all([
                get('/friends'), get('/me/invitations'), get('/blocks'),
            ]);
            data = { friends, invitations, blocks };
            render();
        } catch (err) {
            render(err.message);
        }
    }

    render();
    load();
    return {};
}

export { personRow };
