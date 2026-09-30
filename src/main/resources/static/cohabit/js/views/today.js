// „Heute" (S. 1 und 2): Kopf mit offenen Haken, Umschalter Dashboard | Liste
// (je Geraet gemerkt), Stupser-Banner, offene Einladungen, Karten bzw. Zeilen je
// Co-Habit, Hinweis auf neue Beweisfotos. Aktualisiert sich alle 20 s, solange
// die Seite sichtbar ist.
import { get, post, enc, photoUrl } from '../api.js';
import { h, icon, poll, prefs, showError, toast, fill } from '../dom.js';
import { avatar, avatarStack, colorClass, emptyState, errorState, loadingState, logo, progressBar, sectionLabel } from '../ui.js';
import { state, cached, remember } from '../state.js';
import { TYPE_NAMES, dateTimeShort, isMe } from '../format.js';
import { checkIn } from '../checkin.js';
import { invitationDialog } from '../invitation.js';
import { sendNudge } from '../social.js';
import { navigate } from '../app.js';

const detailPath = id => `/cohabit/c/${enc(id)}`;

/** Die anderen zuerst, „Du" am Ende - wie in den Entwuerfen. */
export function membersMeLast(members) {
    const list = members || [];
    return [...list.filter(p => !isMe(p)), ...list.filter(p => isMe(p))];
}

export function mount(root, params, ctx) {
    let mode = prefs.get('todayMode', 'dashboard') === 'list' ? 'list' : 'dashboard';
    let data = cached('today');
    let loadedAt = cached('todayAt');
    let failed = null;
    // Ein Stupser ist eine Nachricht, keine Aufgabe: gesehen ist er, sobald man
    // „Heute" wieder verlaesst (oder zurueckstupst) - ein eigenes Wegklicken braucht
    // es dann nicht, wie im Entwurf.
    const shownNudges = new Set();
    const shownSince = Date.now();
    const page = h('div', { class: 'page' });
    root.append(page);

    function render() {
        if (!ctx.alive()) return;
        const me = state.me.person;
        const head = h('div', { class: 'top-bar' },
            h('a', { href: '/cohabit/', 'data-nav': '', 'aria-label': 'coHabit' }, logo()),
            h('a', { href: '/cohabit/profil', 'data-nav': '', 'aria-label': 'Profil', class: 'me-link' }, avatar(me, 50)));
        if (!data) {
            fill(page, head, failed ? errorState(failed, load) : loadingState());
            return;
        }
        const title = h('div', { class: 'today-title' },
            h('h1', null, data.headline),
            (data.cohabits || []).length ? segmentedMode() : null);
        const blocks = [
            staleNote(),
            ...(data.nudges || []).map(nudgeBanner),
            ...(data.invitations || []).map(invitationCard),
        ];
        const cohabits = data.cohabits || [];
        let content;
        if (!cohabits.length) {
            content = emptyState('Noch keine Co-Habits', { label: 'Co-Habit anlegen', href: '/cohabit/neu' });
        } else if (mode === 'dashboard') {
            content = dashboard(cohabits);
        } else {
            content = list(cohabits);
        }
        fill(page, head, title, h('div', { class: 'today-body' }, blocks, content, newPhotos()));
    }

    function staleNote() {
        if (!failed || !loadedAt) return null;
        return h('p', { class: 'stale' }, `Stand: ${dateTimeShort(loadedAt)} · ${failed}`);
    }

    function segmentedMode() {
        const wrap = h('div', { class: 'seg', role: 'group', 'aria-label': 'Ansicht' });
        [['dashboard', 'Dashboard'], ['list', 'Liste']].forEach(([key, text]) => {
            wrap.append(h('button', {
                type: 'button', 'aria-pressed': String(mode === key),
                onclick: () => {
                    if (mode === key) return;
                    mode = key;
                    prefs.set('todayMode', key);
                    render();
                },
            }, text));
        });
        return wrap;
    }

    // --- Stupser und Einladungen -------------------------------------------------

    function nudgeBanner(nudge) {
        shownNudges.add(nudge.id);
        const back = h('button', { type: 'button', class: 'btn' }, 'Zurückstupsen');
        back.addEventListener('click', async () => {
            back.classList.add('busy');
            try {
                await sendNudge(nudge.cohabit.id, nudge.from.id, null);
                toast(`${nudge.from.displayName} zurückgestupst.`);
                await markNudgeSeen(nudge);
            } catch (err) {
                showError(err);
                if (err.status === 429) await markNudgeSeen(nudge);
            } finally {
                back.classList.remove('busy');
            }
        });
        return h('div', { class: 'nudge', role: 'status' },
            avatar(nudge.from, 40),
            h('a', { class: 'nudge-text', href: detailPath(nudge.cohabit.id), 'data-nav': '' },
                h('b', null, nudge.from.displayName), ` hat dich angestupst: ${nudge.text}`),
            back);
    }

    async function markNudgeSeen(nudge, quiet) {
        shownNudges.delete(nudge.id);
        try {
            await post(`/nudges/${enc(nudge.id)}/seen`, {});
        } catch (err) { /* dann kommt er beim naechsten Laden wieder */ }
        if (quiet || !data) return;
        data.nudges = (data.nudges || []).filter(n => n.id !== nudge.id);
        render();
    }

    function invitationCard(invitation) {
        return h('div', { class: `card invite-card tinted deco tr ${colorClass(invitation.cohabit.ref.color)}` },
            avatar(invitation.from, 40),
            h('p', { class: 'invite-text' }, `${invitation.from.displayName} lädt dich zu „${invitation.cohabit.ref.name}“ ein`),
            h('button', {
                type: 'button', class: 'btn primary small',
                onclick: () => invitationDialog(invitation, {
                    onAccepted: detail => navigate(detailPath(detail.summary.ref.id)),
                    onDeclined: load,
                }),
            }, 'Ansehen'));
    }

    // --- Abhaken ----------------------------------------------------------------

    function checkButton(summary, size = '') {
        // Eine Unterbrechung traegt man bewusst auf der Detailseite ein, nicht
        // mit einem Tipp im Vorbeigehen (im Entwurf hat die Karte keinen Knopf).
        if (summary.ref.type === 'ABSTINENCE') return null;
        if (!summary.canCheckIn) {
            if (summary.status === 'DONE') {
                return h('span', { class: 'done-mark', title: 'Heute erledigt', 'aria-label': 'Heute erledigt' }, icon('check'));
            }
            return null;
        }
        const outline = size === 'sm' && !summary.photoRequired;
        const button = h('button', {
            type: 'button',
            class: `round-btn ${size}${outline ? ' outline' : ''}`,
            'aria-label': summary.checkInLabel || 'Abhaken',
            title: summary.checkInLabel || 'Abhaken',
        }, icon(summary.photoRequired ? 'camera' : summary.ref.type === 'ABSTINENCE' ? 'close' : summary.valueUnit ? 'plus' : 'check'));
        button.addEventListener('click', async event => {
            event.preventDefault();
            event.stopPropagation();
            button.classList.add('busy');
            try {
                await checkIn(summary, { onDone: () => load() });
            } finally {
                button.classList.remove('busy');
            }
        });
        return button;
    }

    // --- Dashboard (S. 1) ---------------------------------------------------------

    function dashboard(cohabits) {
        const big = cohabits.filter(c => c.ref.type === 'STREAK' || c.ref.type === 'ABSTINENCE');
        const small = cohabits.filter(c => c.ref.type === 'GOAL' || c.ref.type === 'CHALLENGE');
        const smallCards = small.map((c, i) => smallCard(c, small.length % 2 === 1 && i === small.length - 1));
        return h('div', { class: 'dash' },
            big.map(c => (c.ref.type === 'ABSTINENCE' ? abstinenceCard(c) : streakCard(c))),
            small.length ? h('div', { class: 'small-cards' }, smallCards) : null);
    }

    function cardLink(summary) {
        return h('a', { class: 'card-link', href: detailPath(summary.ref.id), 'data-nav': '', 'aria-label': summary.ref.name });
    }

    function metric(headline, unitOverride) {
        const unit = unitOverride !== undefined ? unitOverride : headline.unit;
        return h('div', { class: 'metric' },
            h('span', { class: 'metric-value' }, headline.value),
            unit ? h('span', { class: 'metric-unit' }, unit) : null);
    }

    function cardClasses(summary, extra) {
        return `card tinted on-tint ${colorClass(summary.ref.color)} ${extra}${summary.status === 'UNAVAILABLE' ? ' unavailable' : ''}`;
    }

    function subline(summary) {
        return summary.status === 'UNAVAILABLE' && summary.unavailableText ? summary.unavailableText : summary.subline;
    }

    function streakCard(summary) {
        return h('article', { class: cardClasses(summary, 'big-card deco tr') },
            cardLink(summary),
            h('div', { class: 'card-top' },
                h('div', null,
                    h('p', { class: 'card-type' }, `${summary.ref.name} · ${TYPE_NAMES[summary.ref.type]}`),
                    metric(summary.headline)),
                checkButton(summary)),
            h('div', { class: 'card-foot' },
                avatarStack(membersMeLast(summary.members), 34),
                subline(summary) ? h('p', { class: 'card-sub' }, subline(summary)) : null));
    }

    function abstinenceCard(summary) {
        return h('article', { class: cardClasses(summary, 'big-card side deco bl') },
            cardLink(summary),
            h('div', { class: 'side-left' },
                h('p', { class: 'card-type' }, `${summary.ref.name} · ${TYPE_NAMES[summary.ref.type]}`),
                avatarStack(membersMeLast(summary.members), 34),
                subline(summary) ? h('p', { class: 'card-sub' }, subline(summary)) : null),
            h('div', { class: 'side-right' }, metric(summary.headline)));
    }

    function smallCard(summary, span) {
        return h('article', { class: cardClasses(summary, `small-card deco ${summary.ref.type === 'GOAL' ? 'br' : 'tr'}${span ? ' span' : ''}`) },
            cardLink(summary),
            h('div', { class: 'card-top' },
                h('p', { class: 'card-type' }, summary.ref.name),
                summary.status === 'OPEN' || summary.status === 'DONE' ? checkButton(summary, 'xs') : null),
            metric(summary.headline, summary.ref.type === 'GOAL' ? '' : null),
            subline(summary) ? h('p', { class: 'card-sub' }, subline(summary)) : null);
    }

    // --- Liste (S. 2) ---------------------------------------------------------

    function list(cohabits) {
        const open = cohabits.filter(c => c.section === 'OPEN_TODAY');
        const running = cohabits.filter(c => c.section !== 'OPEN_TODAY');
        return h('div', null,
            open.length ? [sectionLabel('Offen heute'), h('div', { class: 'rows' }, open.map(row))] : null,
            running.length ? [sectionLabel('Läuft'), h('div', { class: 'rows' }, running.map(row))] : null);
    }

    function row(summary) {
        const progress = summary.progress;
        let sub;
        if (summary.ref.type === 'GOAL' && progress) {
            sub = progressBar(progress.fraction, '');
        } else {
            const dots = summary.ref.type === 'STREAK' && progress && progress.goal > 1 && progress.goal <= 14
                ? h('span', { class: 'dots', 'aria-label': `${progress.done} von ${progress.goal}` },
                    Array.from({ length: progress.goal }, (_, i) => h('i', { class: i < progress.done ? 'on' : '' })))
                : null;
            const text = summary.status === 'UNAVAILABLE' && summary.unavailableText ? summary.unavailableText : summary.listLine;
            sub = h('span', { class: 'row-sub' }, dots, text ? h('span', null, text) : null);
        }
        const action = checkButton(summary, 'sm') || h('span', { class: 'row-chevron', 'aria-hidden': 'true' }, icon('chevronRight'));
        return h('article', { class: `card row-card ${cardClasses(summary, '')}` },
            cardLink(summary),
            h('span', { class: 'row-metric' }, summary.headline.short),
            h('div', { class: 'row-main' }, h('span', { class: 'row-name' }, summary.ref.name), sub),
            action);
    }

    function newPhotos() {
        const info = data && data.newPhotos;
        if (!info || !info.count) return null;
        const ids = (info.photoIds || []).slice(0, 2);
        return h('a', { class: 'photos-row', href: '/cohabit/timeline', 'data-nav': '' },
            h('span', { class: 'thumbs' }, ids.length
                ? ids.map(id => h('img', { src: photoUrl(id, 'thumb'), alt: '', loading: 'lazy' }))
                : h('span', { class: 'thumb-ph' })),
            h('span', { class: 'photos-text' },
                h('b', null, info.count === 1 ? '1 neues Beweisfoto' : `${info.count} neue Beweisfotos`), ' in der Timeline'),
            icon('chevronRight'));
    }

    async function load() {
        try {
            const fresh = await get('/today');
            data = remember('today', fresh);
            loadedAt = remember('todayAt', new Date().toISOString());
            failed = null;
        } catch (err) {
            if (err.status === 401) return;
            failed = err.message;
        }
        render();
    }

    render();
    if (data) ctx.restoreScroll();
    load();
    const poller = poll(load, 20000);
    return {
        unmount() {
            poller.stop();
            if (Date.now() - shownSince < 2500 || !data) return;
            (data.nudges || []).filter(n => shownNudges.has(n.id)).forEach(n => markNudgeSeen(n, true));
        },
    };
}
