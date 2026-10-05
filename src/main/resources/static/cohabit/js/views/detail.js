// Detailseite eines Co-Habits (S. 6-10): Kopf in der Farbe seines Typs, Umschalter
// Übersicht | Chat, je Typ die Uebersicht aus Streak-, Abstinenz-, Ziel- oder
// Challenge-Block, unten der Abhak-Knopf. Menue: Bearbeiten, Mitglieder, Pausen,
// Einladen, Benachrichtigungen, Archivieren, Verlassen/Loeschen.
import { get, post, put, del, enc } from '../api.js';
import { h, icon, actionSheet, confirmDialog, openDialog, sheetHead, poll, shareLink, showError, toast, fill } from '../dom.js';
import { avatar, chip, errorState, loadingState, progressBar, selectRow, toggle, toggleRow } from '../ui.js';
import { cohabitClass } from '../kinds.js';
import { cached, remember, forget, state } from '../state.js';
import { TYPE_NAMES, dateLong, dayIn, dayShort, fmtTime, isMe, personName, plural } from '../format.js';
import { backfillDays, checkIn, editCheckin } from '../checkin.js';
import { finishedDialog } from '../finished.js';
import { nudgeDialog, blockPerson } from '../social.js';
import { mountChat } from '../chat.js';
import { peopleSearch } from './friends.js';
import { navigate, goBack } from '../app.js';

const WEEKDAYS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

export function mount(root, params, ctx) {
    const id = params.id;
    const base = `/cohabits/${enc(id)}`;
    let tab = params.tab === 'chat' ? 'chat' : 'overview';
    let detail = cached(`detail:${id}`) || null;
    let failed = null;
    let chat = null;
    let dialogShown = false;
    let pendingCheckIn = !!params.checkin;
    const el = h('div', { class: 'detail' });
    const chatBox = h('div');
    root.append(el);

    const summary = () => detail.summary;
    const type = () => detail.summary.ref.type;
    const archived = () => !!detail.summary.archived;
    const isAdmin = () => detail.myRole === 'ADMIN';

    // --- Kopf ---------------------------------------------------------------

    function bar(title) {
        return h('div', { class: 'd-bar' },
            h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: () => goBack('/') }, icon('back')),
            h('span', { class: 'd-typeline' }, title),
            h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Menü', onclick: openMenu }, icon('more')));
    }

    function tabs() {
        const unread = detail.unreadMessages || 0;
        const make = (key, label, extra) => h('button', {
            type: 'button', role: 'tab', 'aria-selected': String(tab === key),
            onclick: () => switchTab(key),
        }, label, extra || null);
        return h('div', { class: 'tabs', role: 'tablist' },
            make('overview', 'Übersicht'),
            make('chat', 'Chat', unread && tab !== 'chat' ? h('span', { class: 'badge' }, String(unread)) : null));
    }

    function bigMetric(headline) {
        return h('div', { class: 'metric' },
            h('span', { class: 'metric-value' }, headline.value),
            headline.unit ? h('span', { class: 'metric-unit' }, headline.unit) : null);
    }

    function header() {
        const s = summary();
        const t = type();
        if (t === 'ABSTINENCE') {
            const a = detail.abstinence;
            const recordLine = a ? [a.record != null ? `Rekord ${plural(a.record, 'Tag', 'Tage')}` : null, a.toRecordText].filter(Boolean).join(' · ') : '';
            return h('header', { class: 'd-head deco bl' },
                bar(s.typeLine || TYPE_NAMES[t]),
                h('div', { class: 'd-center' },
                    h('h1', { class: 'd-name' }, s.ref.name),
                    bigMetric(s.headline),
                    recordLine ? h('p', { class: 'd-remaining' }, recordLine) : null),
                tabs());
        }
        if (t === 'GOAL') {
            const g = detail.goal;
            return h('header', { class: 'd-head goal deco br' },
                bar(g && g.typeLine ? g.typeLine : s.typeLine),
                h('div', { class: 'd-title-row' },
                    h('div', { class: 'd-titles' },
                        h('h1', { class: 'd-name' }, s.ref.name),
                        g ? h('p', { class: 'd-remaining' }, g.totalText) : null),
                    h('div', { class: 'd-metric' }, bigMetric(s.headline))),
                g ? progressBar(Math.min(1, (g.percent || 0) / 100), 'd-goalbar') : null,
                g ? h('div', { class: 'd-goalmeta' },
                    g.planDeltaText ? chip(g.planDeltaText, 'white') : h('span'),
                    h('span', null, g.finished ? g.finished.text : g.remainingText)) : null,
                tabs());
        }
        const sub = t === 'CHALLENGE'
            ? (detail.challenge ? detail.challenge.endsInText : '')
            : (detail.streak ? detail.streak.remainingText : s.subline);
        const metric = t === 'CHALLENGE'
            ? h('div', { class: 'd-metric' },
                h('div', { class: 'metric' }, h('span', { class: 'metric-value' }, s.headline.value)),
                s.headline.unit ? h('span', { class: 'd-metric-note' }, s.headline.unit) : null)
            : h('div', { class: 'd-metric' }, bigMetric(s.headline));
        return h('header', { class: 'd-head deco tr' },
            bar(s.typeLine || TYPE_NAMES[t]),
            h('div', { class: 'd-title-row' },
                h('div', { class: 'd-titles' },
                    h('h1', { class: 'd-name' }, s.ref.name),
                    sub ? h('p', { class: 'd-remaining' }, sub) : null),
                metric),
            tabs());
    }

    function compactHeader() {
        const s = summary();
        const names = detail.members.filter(m => m.state !== 'INVITED').map(m => personName(m.person));
        const ordered = [...names.filter(n => n === 'Du'), ...names.filter(n => n !== 'Du')];
        return h('header', { class: 'd-head compact deco tr' },
            h('div', { class: 'd-bar' },
                h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: () => goBack('/') }, icon('back')),
                h('div', { class: 'd-compact-titles' },
                    h('h1', { class: 'd-compact-name' }, s.ref.name),
                    h('p', { class: 'd-compact-members' }, ordered.join(', '))),
                h('div', { class: 'metric' },
                    h('span', { class: 'metric-value' }, s.headline.short && type() !== 'GOAL' && type() !== 'CHALLENGE' ? s.headline.value : s.headline.short || s.headline.value),
                    type() === 'STREAK' || type() === 'ABSTINENCE' ? h('span', { class: 'metric-unit' }, shortUnit(s.headline)) : null)),
            tabs());
    }

    function shortUnit(headline) {
        // „6 Wo." -> „Wo.": die Kurzform liefert der Dienst in headline.short.
        const short = headline.short || '';
        const rest = short.startsWith(headline.value) ? short.slice(headline.value.length).trim() : '';
        return rest || headline.unit;
    }

    // --- Uebersicht je Typ ---------------------------------------------------

    function overview() {
        const t = type();
        const blocks = [];
        if (t === 'STREAK') blocks.push(...streakBlocks());
        else if (t === 'ABSTINENCE') blocks.push(...abstinenceBlocks());
        else if (t === 'GOAL') blocks.push(...goalBlocks());
        else blocks.push(...challengeBlocks());
        if (detail.health) blocks.push(healthCard());
        if ((detail.rules || []).length) {
            blocks.push(h('section', { class: 'panel' },
                h('h2', { class: 'panel-title' }, 'Regeln'),
                h('div', { class: 'chips' }, detail.rules.map(rule => chip(rule)))));
        }
        const entries = myEntries();
        if (entries) blocks.push(entries);
        return h('div', { class: 'd-body' }, blocks);
    }

    function streakBlocks() {
        const s = detail.streak;
        if (!s) return [];
        const out = [];
        if (s.week) out.push(weekPanel(s.week));
        const record = s.record;
        out.push(h('div', { class: 'tiles' },
            tile(s.fulfillmentRate != null ? `${s.fulfillmentRate}%` : '–', 'Erfüllung'),
            tile(record ? record.short : '–', record && record.person ? `Rekord · ${personName(record.person)}` : 'Rekord'),
            h('button', { type: 'button', class: 'tile tile-btn', onclick: membersDialog },
                h('div', { class: 'tile-value' }, `${detail.seats.used} / ${detail.seats.max}`),
                h('div', { class: 'tile-label' }, 'Mitglieder'))));
        if (s.group) {
            out.push(h('div', { class: 'tiles one' }, tile(`${s.group.current} ${s.group.unitLabel}`, 'Gruppen-Streak')));
        }
        return out;
    }

    function tile(value, label) {
        return h('div', { class: 'tile' },
            h('div', { class: 'tile-value' }, value),
            h('div', { class: 'tile-label' }, label));
    }

    function weekPanel(week) {
        const days = backfillDays(detail);
        const rhythmKind = detail.config.streak && detail.config.streak.rhythm ? detail.config.streak.rhythm.kind : 'DAILY';
        const grid = h('div', { class: 'week', role: 'table', 'aria-label': 'Diese Woche' },
            h('span', { 'aria-hidden': 'true' }),
            WEEKDAYS.map((d, i) => h('span', { class: `week-day${i === week.todayIndex ? ' today' : ''}` }, d)));
        const rows = [...week.rows].sort((a, b) => (isMe(b.person) ? 1 : 0) - (isMe(a.person) ? 1 : 0));
        for (const row of rows) {
            const me = isMe(row.person);
            const openToday = row.cells[week.todayIndex] === 'OPEN';
            const canNudge = !me && openToday && !archived();
            grid.append(h('span', { class: 'week-name' },
                canNudge
                    ? h('button', {
                        type: 'button', title: `${row.person.displayName} anstupsen`,
                        onclick: () => nudgeDialog(summary().ref, row.person),
                    }, personName(row.person), icon('poke'))
                    : personName(row.person)));
            row.cells.forEach((state, i) => {
                const day = week.days[i];
                // Nachtragen per Tipp auf die eigene Zelle - bei „x-mal pro Woche/Monat"
                // und Intervall zaehlt jeder Tag, dort auch die nicht faelligen.
                const anyDay = ['TIMES_PER_WEEK', 'TIMES_PER_MONTH', 'INTERVAL'].includes(rhythmKind);
                const open = state === 'MISSED' || state === 'OPEN' || (anyDay && state === 'NOT_DUE');
                const tappable = me && !archived() && !detail.config.auto && open && days.includes(day);
                const label = `${personName(row.person)}, ${WEEKDAYS[i]}: ${cellLabel(state)}`;
                grid.append(tappable
                    ? h('button', { type: 'button', class: `cell ${state} mine`, 'aria-label': `${label} – eintragen`, title: 'Eintragen', onclick: () => startCheckIn(i === week.todayIndex ? null : day) })
                    : h('span', { class: `cell ${state}${me ? ' mine' : ''}`, 'aria-label': label, title: cellLabel(state) }));
            });
        }
        return h('section', { class: 'panel' }, h('h2', { class: 'panel-title' }, 'Diese Woche'), grid);
    }

    function cellLabel(state) {
        return {
            DONE: 'erledigt', MISSED: 'verpasst', OPEN: 'offen', PAUSED: 'Pause', FUTURE: 'kommt noch',
            NOT_DUE: 'nicht fällig', BEFORE_JOIN: 'vor dem Beitritt',
        }[state] || state;
    }

    function abstinenceBlocks() {
        const a = detail.abstinence;
        if (!a) return [];
        const out = [];
        const members = [...(a.members || [])].sort((x, y) => (isMe(y.person) ? 1 : 0) - (isMe(x.person) ? 1 : 0));
        out.push(h('section', { class: 'panel member-days' },
            members.map(m => h('div', { class: 'member-day-row' },
                avatar(m.person, 40),
                h('div', { class: 'person-texts' },
                    h('div', { class: 'person-name' }, personName(m.person)),
                    m.newPersonalRecord ? h('div', { class: 'person-sub' }, 'neuer persönlicher Rekord') : null),
                h('span', { class: 'member-day-value' }, `${m.days} T`))),
            a.group ? h('div', { class: 'member-day-row' },
                h('span', { class: 'health-avatar', 'aria-hidden': 'true' }, icon('users')),
                h('div', { class: 'person-texts' }, h('div', { class: 'person-name' }, 'Gruppe')),
                h('span', { class: 'member-day-value' }, `${a.group.days} T`)) : null));
        const series = a.series || [];
        if (series.length) {
            const max = Math.max(1, ...series.map(x => x.days));
            out.push(h('section', { class: 'panel' },
                h('h2', { class: 'panel-title' }, 'Deine Serien'),
                h('div', { class: 'series' }, series.map((x, i) => [
                    h('span', { class: `series-label${x.current ? ' current' : ''}` }, x.label),
                    h('span', { class: 'series-bar' },
                        h('i', { class: x.current ? 'current' : i < series.length - 2 ? 'old' : '', style: `width:${Math.max(4, (x.days / max) * 100 * 0.78).toFixed(1)}%` }),
                        h('span', null, `${x.days} T`)),
                ]))));
        }
        return out;
    }

    function goalBlocks() {
        const g = detail.goal;
        if (!g) return [];
        const out = [];
        if (g.finished) out.push(finishedBanner(g.finished.text, g.finished.reached));
        const contributions = g.contributions || [];
        if (contributions.length) {
            out.push(h('section', { class: 'panel' },
                h('h2', { class: 'panel-title' }, 'Beiträge'),
                h('div', { class: 'stack', style: 'gap:6px' }, contributions.map(c => h('div', { class: `contrib-row${isMe(c.person) ? ' me' : ''}` },
                    h('span', { class: 'contrib-name' }, personName(c.person)),
                    progressBar(c.fraction),
                    h('span', { class: 'contrib-value' }, c.valueText))))));
        }
        return out;
    }

    function challengeBlocks() {
        const c = detail.challenge;
        if (!c) return [];
        const out = [];
        if (c.finished) out.push(finishedBanner('Challenge beendet', true));
        out.push(h('section', { class: 'panel' }, h('div', { class: 'rank-list' }, (c.leaderboard || []).map(entry => h('div', {
            class: `rank-row${isMe(entry.person) ? ' me' : ''}`,
        },
        h('span', { class: 'rank-no' }, String(entry.rank)),
        h('span', { class: 'rank-name' }, personName(entry.person)),
        progressBar(entry.fraction),
        h('span', { class: 'rank-score' }, entry.scoreText != null ? entry.scoreText : String(entry.score)))))));
        const scoring = c.scoring === 'FIRST_TO_TARGET' && c.target != null ? `${c.scoringText} · ${c.target}` : c.scoringText;
        out.push(h('div', { class: 'tiles two' },
            c.stake ? h('div', { class: 'tile' }, h('div', { class: 'tile-label' }, 'Einsatz'), h('div', { class: 'tile-text' }, c.stake)) : null,
            h('div', { class: 'tile' }, h('div', { class: 'tile-label' }, 'Wertung'), h('div', { class: 'tile-text' }, scoring))));
        const past = c.pastRounds || [];
        if (past.length || c.recurrence !== 'NONE') {
            out.push(h('section', { class: 'panel' },
                h('p', { class: 'tile-label', style: 'margin-bottom:10px' }, ['Frühere Runden', c.recurrenceText].filter(Boolean).join(' · ')),
                past.length
                    ? h('div', { class: 'chips' }, past.map(r => chip(`${r.label} · ${(r.winners || []).map(personName).join(', ') || '–'}`, 'tint')))
                    : null));
        }
        return out;
    }

    function finishedBanner(text, reached) {
        return h('div', { class: 'finished-banner' }, icon(reached ? 'trophy' : 'flag'), text);
    }

    function healthCard() {
        const hl = detail.health;
        const sub = [hl.consent && hl.lastSyncAt ? `zuletzt ${fmtTime(hl.lastSyncAt)}` : null, hl.shareText].filter(Boolean).join(' · ');
        // kcal aus Healthy holt der Dienst selbst - die Einwilligung geht darum auch hier,
        // fuer Apple Health/Health Connect nur in den Apps. Zustimmen kann, wer Healthy hat.
        const healthy = hl.source === 'HEALTHY';
        const canConsent = healthy && ((state.me && state.me.sources) || []).includes('FOOD');
        const control = healthy ? toggle({
            checked: hl.consent,
            disabled: !canConsent && !hl.consent,
            label: 'kcal aus Healthy übernehmen',
            onchange: async on => {
                try {
                    apply(await put(`${base}/settings/me`, { healthConsent: on }));
                } catch (err) {
                    showError(err);
                    render();
                }
            },
        }) : null;
        return h('section', { class: 'panel health-card' },
            h('span', { class: 'health-avatar', 'aria-hidden': 'true' }, icon('pulse')),
            h('div', { class: 'health-texts' },
                h('div', { class: 'health-title' }, healthy ? hl.label : hl.consent ? 'Health-Sync aktiv' : `Health · ${hl.label}`),
                sub ? h('div', { class: 'health-sub' }, sub) : null,
                healthy && !canConsent && !hl.consent ? h('div', { class: 'health-sub' }, 'Kein Healthy-Zugang') : null),
            control);
    }

    function myEntries() {
        const list = detail.myCheckins || [];
        const days = backfillDays(detail);
        const canBackfill = !archived() && !detail.config.auto && days.length > 1 && summary().canCheckIn !== undefined;
        if (!list.length && !canBackfill) return null;
        const zone = detail.config.timezone;
        const today = dayIn(new Date(), zone);
        return h('section', { class: 'panel' },
            h('h2', { class: 'panel-title' }, 'Meine Einträge',
                canBackfill && type() !== 'ABSTINENCE'
                    ? h('button', { type: 'button', class: 'text-btn', onclick: () => startCheckIn(days[1]) }, 'Nachtragen')
                    : null),
            list.length ? h('div', null, list.map(c => h('div', { class: 'entry-row' },
                h('div', { class: 'entry-main' },
                    h('div', { class: 'entry-day' }, `${dayShort(c.date, today)}${c.kind === 'BREAK' ? ' · Unterbrechung' : ''}`),
                    h('div', { class: 'entry-sub' }, [c.valueText, c.caption, c.note, c.source === 'HEALTH' ? 'Health' : null].filter(Boolean).join(' · ') || fmtTime(c.createdAt))),
                c.editable && !archived()
                    ? h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bearbeiten', title: 'Bearbeiten', onclick: () => editCheckin(detail, c, apply) }, icon('edit'))
                    : null))) : h('p', { class: 'entry-sub' }, 'Noch keine Einträge.'));
    }

    // --- Abhak-Knopf ---------------------------------------------------------

    function actionBar() {
        if (archived()) {
            return h('div', { class: 'action-bar' }, h('div', { class: 'archived-note' }, icon('archive'), 'Archiviert'));
        }
        const s = summary();
        if (!s.checkInLabel) return null;
        const outline = type() === 'ABSTINENCE';
        const button = h('button', {
            type: 'button',
            class: `btn block ${outline ? 'outline' : 'primary'}`,
            disabled: !s.canCheckIn,
            onclick: () => startCheckIn(null),
        }, s.photoRequired && s.canCheckIn ? icon('camera') : null, s.checkInLabel);
        return h('div', { class: 'action-bar' }, button);
    }

    function startCheckIn(date) {
        if (!detail || archived()) return;
        checkIn(summary(), { detail, date, onDone: fresh => { apply(fresh); if (chat) chat.refresh(); } });
    }

    // --- Menue und Dialoge ----------------------------------------------------

    function openMenu() {
        const s = summary();
        const admin = isAdmin();
        const actions = [];
        if (admin && !archived()) actions.push({ label: 'Bearbeiten', icon: 'edit', onSelect: () => navigate(`/c/${enc(id)}/bearbeiten`) });
        actions.push({ label: 'Mitglieder', icon: 'users', onSelect: membersDialog });
        if (type() === 'STREAK' && !archived() && !detail.config.auto) actions.push({ label: 'Pausen', icon: 'pause', onSelect: pausesDialog });
        if (detail.canInvite && !archived()) actions.push({ label: 'Einladen', icon: 'userPlus', onSelect: inviteDialog });
        actions.push({ label: 'Benachrichtigungen', icon: 'bell', onSelect: notificationsDialog });
        if (admin) {
            actions.push(archived()
                ? { label: 'Wiederherstellen', icon: 'restore', onSelect: () => setArchived(false) }
                : { label: 'Archivieren', icon: 'archive', onSelect: () => setArchived(true) });
        }
        actions.push({ label: 'Verlassen', icon: 'leave', danger: true, onSelect: leave });
        if (admin) actions.push({ label: 'Löschen', icon: 'trash', danger: true, onSelect: remove });
        actionSheet(s.ref.name, actions);
    }

    function membersDialog() {
        const ref = {};
        const body = h('div', { class: 'sheet-body' });
        const renderMembers = () => {
            const admin = isAdmin();
            const members = [...detail.members].sort((a, b) => (isMe(b.person) ? 1 : 0) - (isMe(a.person) ? 1 : 0));
            fill(body,
                h('div', { class: 'list', style: 'margin-top:0' }, members.map(m => {
                    const me = isMe(m.person);
                    const sub = [m.role === 'ADMIN' ? 'Admin' : null, `@${m.person.username}`, m.state && m.state !== 'ACTIVE' ? memberState(m.state) : null].filter(Boolean).join(' · ');
                    const actions = [];
                    const invited = m.state === 'INVITED';
                    if (!me && !invited && !archived()) actions.push({ label: 'Anstupsen', icon: 'poke', onSelect: () => nudgeDialog(summary().ref, m.person) });
                    if (admin && !me && !invited && !archived()) actions.push({ label: 'Zum Admin machen', icon: 'trophy', onSelect: () => transferAdmin(m.person, renderMembers) });
                    if (admin && !me) actions.push({ label: invited ? 'Einladung zurückziehen' : 'Entfernen', icon: 'leave', danger: true, onSelect: () => removeMember(m.person, renderMembers) });
                    if (!me) actions.push({ label: 'Blockieren', icon: 'block', danger: true, onSelect: () => blockPerson(m.person) });
                    return h('div', { class: 'person-row' },
                        avatar(m.person, 44),
                        h('div', { class: 'person-texts' },
                            h('div', { class: 'person-name' }, personName(m.person)),
                            h('div', { class: 'person-sub' }, sub)),
                        actions.length ? h('button', {
                            type: 'button', class: 'icon-btn', 'aria-label': `${m.person.displayName}: Aktionen`,
                            onclick: () => actionSheet(m.person.displayName, actions),
                        }, icon('more')) : null);
                })),
                detail.canInvite && !archived()
                    ? h('button', { type: 'button', class: 'btn primary block', onclick: () => { ref.current.close(); inviteDialog(); } }, 'Einladen')
                    : null);
        };
        renderMembers();
        ref.current = openDialog([...sheetHead('Mitglieder', `${detail.seats.used} von ${detail.seats.max} Plätzen`, ref), body],
            { kind: 'sheet', label: 'Mitglieder' });
    }

    function memberState(state) {
        return { INVITED: 'eingeladen', PAUSED: 'pausiert', ACTIVE: '' }[state] ?? state.toLowerCase();
    }

    async function transferAdmin(person, after) {
        const ok = await confirmDialog({ title: `${person.displayName} zum Admin machen?`, confirm: 'Übertragen' });
        if (!ok) return;
        try {
            apply(await put(`${base}/admin`, { personId: person.id }));
            toast(`${person.displayName} ist jetzt Admin.`);
            if (after) after();
        } catch (err) {
            showError(err);
        }
    }

    async function removeMember(person, after) {
        const ok = await confirmDialog({ title: `${person.displayName} entfernen?`, confirm: 'Entfernen', danger: true });
        if (!ok) return;
        try {
            await del(`${base}/members/${enc(person.id)}`);
            toast(`${person.displayName} entfernt.`);
            await load();
            if (after) after();
        } catch (err) {
            showError(err);
        }
    }

    function pausesDialog() {
        const ref = {};
        const body = h('div', { class: 'sheet-body' });
        const zone = detail.config.timezone;
        const today = dayIn(new Date(), zone);
        // Rueckwirkend bis zur Nachtragsfrist - wer gestern krank war, traegt es heute nach.
        const earliest = detail.backfillFrom && detail.backfillFrom < today ? detail.backfillFrom : today;
        const from = h('input', { type: 'date', value: today, min: earliest, 'aria-label': 'Von' });
        const to = h('input', { type: 'date', value: today, min: today, 'aria-label': 'Bis' });
        from.addEventListener('change', () => {
            to.min = from.value;
            if (to.value < from.value) to.value = from.value;
        });
        const add = h('button', { type: 'button', class: 'btn primary block' }, 'Pause eintragen');
        add.addEventListener('click', async () => {
            if (!from.value || !to.value) return;
            add.classList.add('busy');
            try {
                apply(await post(`${base}/pauses`, { from: from.value, to: to.value }));
                toast('Pause eingetragen.');
                renderPauses();
            } catch (err) {
                showError(err);
            } finally {
                add.classList.remove('busy');
            }
        });
        const renderPauses = () => {
            const pauses = detail.myPauses || [];
            fill(body,
                pauses.length ? h('div', { class: 'list', style: 'margin-top:0' }, pauses.map(p => h('div', { class: 'person-row' },
                    h('span', { class: 'health-avatar', 'aria-hidden': 'true' }, icon('pause')),
                    h('div', { class: 'person-texts' }, h('div', { class: 'person-name' }, p.from === p.to ? dateLong(p.from) : `${dateLong(p.from)} – ${dateLong(p.to)}`)),
                    h('button', {
                        type: 'button', class: 'icon-btn', 'aria-label': 'Pause löschen', title: 'Löschen',
                        onclick: async () => {
                            try {
                                apply(await del(`${base}/pauses/${enc(p.id)}`));
                                renderPauses();
                            } catch (err) {
                                showError(err);
                            }
                        },
                    }, icon('trash'))))) : null,
                h('div', { class: 'set-group', style: 'margin-top:0' },
                    h('label', { class: 'set-row' }, h('span', { class: 'set-label' }, 'Von'), from),
                    h('label', { class: 'set-row' }, h('span', { class: 'set-label' }, 'Bis'), to)),
                add);
        };
        renderPauses();
        ref.current = openDialog([...sheetHead('Pausen', summary().ref.name, ref), body], { kind: 'sheet', label: 'Pausen' });
    }

    function inviteDialog() {
        const ref = {};
        const body = h('div', { class: 'sheet-body' });
        let candidates = null;
        const seatsLabel = h('span', { class: 'seats' });
        const renderCandidates = () => {
            if (!candidates) {
                fill(body, loadingState());
                return;
            }
            seatsLabel.textContent = `${candidates.seats.used} von ${candidates.seats.max}`;
            const full = candidates.seats.used >= candidates.seats.max;
            fill(body,
                inviteLinkCard(),
                search.el,
                candidates.people.length ? h('div', { class: 'list', style: 'margin-top:0' }, candidates.people.map(c => h('div', { class: 'person-row' },
                    avatar(c.person, 44),
                    h('div', { class: 'person-texts' },
                        h('div', { class: 'person-name' }, c.person.displayName),
                        h('div', { class: 'person-sub' }, `@${c.person.username}`)),
                    c.status === 'INVITE'
                        ? h('button', { type: 'button', class: 'btn outline small', disabled: full || !candidates.canInvite, onclick: e => invite(c.person, e.currentTarget) }, 'Einladen')
                        : h('span', { class: 'person-state' }, c.status === 'INVITED' ? 'Eingeladen' : 'Mitglied')))) : null);
        };
        async function invite(person, button) {
            if (button) button.classList.add('busy');
            try {
                candidates = await post(`${base}/invitations`, { personIds: [person.id] });
                toast(`${person.displayName} eingeladen.`);
                renderCandidates();
                load();
            } catch (err) {
                showError(err);
                if (button) button.classList.remove('busy');
            }
        }
        const search = peopleSearch({
            onInvite: person => invite(person),
            isInvited: person => !!candidates && candidates.people.some(c => c.person.id === person.id && c.status !== 'INVITE'),
        });
        const head = sheetHead('Einladen', null, ref);
        head[1].querySelector('.sheet-titles').append(seatsLabel);
        seatsLabel.style.marginTop = '8px';
        seatsLabel.style.display = 'inline-block';
        renderCandidates();
        ref.current = openDialog([...head, body], { kind: 'sheet', label: 'Einladen' });
        get(`${base}/invite-candidates`).then(res => {
            candidates = res;
            renderCandidates();
        }).catch(err => {
            fill(body, errorState(err.message));
        });
    }

    function inviteLinkCard() {
        const share = h('button', { type: 'button', class: 'btn' }, 'Teilen');
        share.addEventListener('click', async () => {
            share.classList.add('busy');
            try {
                const link = await post(`${base}/invite-link`, {});
                await shareLink(link.url, summary().ref.name);
            } catch (err) {
                showError(err);
            } finally {
                share.classList.remove('busy');
            }
        });
        return h('div', { class: 'card invite-link-card deco tr' }, h('span', { class: 'invite-link-title' }, 'Einladungslink'), share);
    }

    function notificationsDialog() {
        const ref = {};
        let settings = { muted: false, checkins: null, chat: null, shareBreaks: false, healthConsent: false, ...(detail.mySettings || {}) };
        const triState = [[null, 'Wie global'], [true, 'An'], [false, 'Aus']];
        const save = async patch => {
            const before = settings;
            settings = { ...settings, ...patch };
            try {
                apply(await put(`${base}/settings/me`, settings));
            } catch (err) {
                settings = before;
                showError(err);
            }
        };
        const body = h('div', { class: 'sheet-body' },
            h('div', { class: 'set-group', style: 'margin-top:0' },
                toggleRow('Stummschalten', { checked: settings.muted, onchange: v => save({ muted: v }) }),
                selectRow('Check-ins', triState, settings.checkins, v => save({ checkins: v })),
                selectRow('Chat', triState, settings.chat, v => save({ chat: v })),
                type() === 'ABSTINENCE'
                    ? toggleRow('Unterbrechungen teilen', { checked: settings.shareBreaks, onchange: v => save({ shareBreaks: v }) })
                    : null));
        ref.current = openDialog([...sheetHead('Benachrichtigungen', summary().ref.name, ref), body], { kind: 'sheet', label: 'Benachrichtigungen' });
    }

    async function setArchived(on) {
        const name = summary().ref.name;
        if (on) {
            const ok = await confirmDialog({ title: `„${name}“ archivieren?`, confirm: 'Archivieren' });
            if (!ok) return;
        }
        try {
            apply(await post(`${base}/${on ? 'archive' : 'unarchive'}`, {}));
            forget('today');
            forget('cohabitsList');
            toast(on ? 'Archiviert.' : 'Wiederhergestellt.');
        } catch (err) {
            showError(err);
        }
    }

    async function leave() {
        const name = summary().ref.name;
        const ok = await confirmDialog({ title: `„${name}“ verlassen?`, confirm: 'Verlassen', danger: true });
        if (!ok) return;
        try {
            await del(`${base}/members/me`);
            forget(`detail:${id}`);
            forget('today');
            toast(`„${name}“ verlassen.`);
            navigate('/', { replace: true });
        } catch (err) {
            showError(err);
        }
    }

    async function remove() {
        const name = summary().ref.name;
        const ok = await confirmDialog({
            title: `„${name}“ löschen?`,
            text: 'Einträge, Chat und Fotos werden gelöscht.',
            confirm: 'Löschen',
            danger: true,
        });
        if (!ok) return;
        try {
            await del(base, { confirm: true });
            forget(`detail:${id}`);
            forget('today');
            toast(`„${name}“ gelöscht.`);
            navigate('/', { replace: true });
        } catch (err) {
            showError(err);
        }
    }

    // --- Ablauf ---------------------------------------------------------------

    function switchTab(key) {
        if (key === tab) return;
        tab = key;
        const path = `/cohabit/c/${enc(id)}${key === 'chat' ? '/chat' : ''}`;
        history.replaceState(history.state, '', path);
        render();
        if (key === 'chat') window.scrollTo(0, document.documentElement.scrollHeight);
        else window.scrollTo(0, 0);
    }

    function apply(fresh) {
        if (!fresh || !ctx.alive()) return;
        detail = remember(`detail:${id}`, fresh);
        forget('today');
        render();
    }

    function render() {
        if (!ctx.alive()) return;
        if (!detail) {
            el.className = 'detail';
            fill(el, h('div', { class: 'page' },
                h('div', { class: 'd-bar', style: 'padding-top:14px' },
                    h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: () => goBack('/') }, icon('back'))),
                failed ? errorState(failed, failedStatus === 404 ? null : load) : loadingState()));
            return;
        }
        const s = summary();
        document.title = `${s.ref.name} – coHabit`;
        el.className = `detail ${cohabitClass(s.ref)}`;
        if (tab === 'chat') {
            fill(el, compactHeader(), chatBox);
            if (!chat) {
                chat = mountChat(chatBox, {
                    getDetail: () => detail,
                    onCheckIn: () => startCheckIn(null),
                    onRead: () => {
                        if (detail.unreadMessages) {
                            detail.unreadMessages = 0;
                            detail.summary.unreadMessages = 0;
                            forget('today');
                        }
                    },
                    alive: ctx.alive,
                });
            } else {
                chat.update();
            }
        } else {
            if (chat) {
                chat.unmount();
                chat = null;
            }
            fill(el, header(), overview(), actionBar() || '');
        }
        maybeDialog();
    }

    function maybeDialog() {
        if (dialogShown || !detail.dialog || !ctx.alive()) return;
        dialogShown = true;
        finishedDialog(detail, {
            onTimeline: () => navigate('/timeline'),
            onCongratulate: () => {
                switchTab('chat');
                if (chat) chat.refresh();
            },
        });
    }

    let failedStatus = 0;

    async function load() {
        try {
            const fresh = await get(base);
            if (!ctx.alive()) return;
            detail = remember(`detail:${id}`, fresh);
            failed = null;
            render();
            if (pendingCheckIn) {
                pendingCheckIn = false;
                history.replaceState(history.state, '', `/cohabit/c/${enc(id)}`);
                // Erst den Abschlussdialog zeigen - zwei Dialoge uebereinander waeren zu viel.
                if (detail.summary.canCheckIn && !detail.dialog) startCheckIn(null);
            }
        } catch (err) {
            if (err.status === 401) return;
            failedStatus = err.status;
            if (!detail) {
                failed = err.status === 404 ? 'Dieses Co-Habit gibt es nicht (mehr).' : err.message;
                render();
            }
        }
    }

    render();
    load();
    // Einmal pro Minute neu: „endet in …" rechnet der Dienst (Vertrag 3.4).
    const poller = poll(load, 60000);
    return {
        unmount() {
            poller.stop();
            if (chat) chat.unmount();
        },
    };
}
