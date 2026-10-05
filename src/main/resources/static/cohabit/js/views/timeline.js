// Timeline (S. 3): Ereignisse aller eigenen Co-Habits, neueste zuerst, nach
// Tagen gruppiert. Gefiltert wird ueber einen Knopf mit Abhak-Liste (Felix,
// 01.10.: die seitlich scrollenden Chips gingen unter); gemerkt werden je Geraet
// die AUSGEBLENDETEN Co-Habits - neue erscheinen so von selbst. Foto-Karten mit
// Reaktionen und „Antworten" (oeffnet den Chat), uebrige Ereignisse kompakt.
// Reagieren: Smiley neben „Antworten", langer Druck oder Rechtsklick auf die Karte.
import { get, post, enc } from '../api.js';
import { h, icon, showError, fill, openDialog, sheetHead, prefs } from '../dom.js';
import { avatar, chip, colorClass, emptyState, errorState, loadingState, photoCarousel, photoList, sectionLabel } from '../ui.js';
import { cached, remember } from '../state.js';
import { dayHeading, dayIn } from '../format.js';
import { onLongPress, openReactionBar, reactionPill } from '../reactions.js';
import { navigate } from '../app.js';

const PAGE = 30;
const EXCLUDED_KEY = 'timeline.excluded';

function loadExcluded() {
    try {
        const list = JSON.parse(prefs.get(EXCLUDED_KEY, '[]'));
        return new Set(Array.isArray(list) ? list.filter(id => typeof id === 'string') : []);
    } catch (e) {
        return new Set();
    }
}

function saveExcluded(excluded) {
    prefs.set(EXCLUDED_KEY, JSON.stringify([...excluded]));
}

/** „Alle Habits", der Name des einzigen sichtbaren oder „2 von 6 Habits". */
export function filterLabel(refs, excluded) {
    if (!refs) return excluded.size ? 'Habits' : 'Alle Habits';
    const visible = refs.filter(ref => !excluded.has(ref.id));
    if (visible.length === refs.length) return 'Alle Habits';
    if (visible.length === 1) return visible[0].name;
    return `${visible.length} von ${refs.length} Habits`;
}

/** Fettet den Namen am Anfang des Titels („Lena hat Laufen abgehakt"). */
function titleWithName(item) {
    const title = item.title || '';
    const lead = item.kind === 'HEALTH' ? 'Health' : item.person ? item.person.displayName : null;
    if (lead && title.startsWith(lead)) {
        return [h('b', null, lead), title.slice(lead.length)];
    }
    return title;
}

export function timelineItem(item, { onReply } = {}) {
    const color = colorClass(item.cohabit && item.cohabit.color);
    const who = item.kind === 'HEALTH' || !item.person
        ? h('span', { class: `health-avatar ${color}`, 'aria-hidden': 'true' }, icon(item.kind === 'HEALTH' ? 'pulse' : 'flame'))
        : avatar(item.person, 40);
    const reply = item.canReply
        ? h('button', { type: 'button', class: 'text-btn', onclick: () => onReply(item) }, 'Antworten')
        : null;
    const pillSlot = h('div', { class: 'tl-pill' });
    const renderPill = () => fill(pillSlot, reactionPill(item, { onChange: renderPill }));
    const openBar = () => openReactionBar(item, { onChange: () => {
        renderPill();
        if (foot) foot.hidden = false;
    } });
    const actions = h('div', { class: 'tl-actions' },
        h('button', { type: 'button', class: 'icon-btn tl-react', 'aria-label': 'Reagieren', title: 'Reagieren', onclick: openBar }, icon('smile')),
        reply);
    renderPill();
    let foot = null;
    const photos = photoList(item);
    if (photos.length) {
        const card = h('article', { class: `tl-item ${color}` },
            h('div', { class: 'tl-head' },
                who,
                h('div', { class: 'tl-texts' },
                    h('p', { class: 'tl-title' }, titleWithName(item)),
                    item.subtitle ? h('p', { class: 'tl-sub' }, item.subtitle) : null),
                item.cohabit ? chip(item.cohabit.name, 'tint') : null),
            photoCarousel(photos),
            item.caption ? h('p', { class: 'tl-caption' }, item.caption) : null,
            h('div', { class: 'tl-foot' }, pillSlot, actions));
        onLongPress(card, openBar);
        return card;
    }
    // Kompakt: Reaktionen und „Antworten" erst auf Tipp, ausser es gibt schon
    // welche - so bleibt die Liste ruhig wie im Entwurf.
    const hasReactions = (item.reactions || []).some(r => r.count > 0);
    foot = h('div', { class: 'tl-foot', hidden: !hasReactions }, pillSlot, actions);
    const head = h('div', {
        class: 'tl-head tappable', role: 'button', tabindex: '0', 'aria-expanded': String(hasReactions),
    }, who, h('div', { class: 'tl-texts' },
        h('p', { class: 'tl-title' }, titleWithName(item)),
        item.subtitle ? h('p', { class: 'tl-sub' }, item.subtitle) : null));
    const toggleFoot = () => {
        foot.hidden = !foot.hidden;
        head.setAttribute('aria-expanded', String(!foot.hidden));
    };
    head.addEventListener('click', toggleFoot);
    head.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            toggleFoot();
        }
    });
    const card = h('article', { class: `tl-item compact ${color}` }, head, foot);
    onLongPress(card, openBar);
    return card;
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    let excluded = loadExcluded();
    let cohabits = cached('cohabitsList') || null;
    let items = [];
    let hasMore = false;
    let loading = false;
    let failed = null;
    let loadedOnce = false;
    let observer = null;

    const listEl = h('div', { class: 'tl-sections' });
    const filterLabelEl = h('span');
    const filterBtn = h('button', {
        type: 'button', class: 'filter-btn', 'aria-haspopup': 'dialog', onclick: openFilter,
    }, filterLabelEl, icon('chevronDown'));
    const moreEl = h('div', { class: 'more-loader' });

    page.append(
        h('div', { class: 'page-head' }, h('h1', { class: 'page-title' }, 'Timeline')),
        filterBtn, listEl, moreEl);

    const refs = () => (cohabits ? cohabits.map(s => s.ref) : null);
    /** Ausgeblendet, was es auch gibt - fuer Beschriftung, Abfrage und Zwischenspeicher. */
    const active = () => {
        const list = refs();
        return list ? new Set([...excluded].filter(id => list.some(ref => ref.id === id))) : excluded;
    };
    const filterKey = () => [...active()].sort().join(',') || 'all';
    const nothingSelected = () => {
        const list = refs();
        return !!list && list.length > 0 && list.every(ref => excluded.has(ref.id));
    };

    function renderFilter() {
        filterLabelEl.textContent = filterLabel(refs(), active());
    }

    function setExcluded(next) {
        excluded = next;
        saveExcluded(excluded);
        renderFilter();
        reload();
    }

    function openFilter() {
        const ref = {};
        const listBox = h('div', { class: 'check-list', role: 'group', 'aria-label': 'Habits' });
        const row = (label, checked, onclick, dot) => h('button', {
            type: 'button', class: 'check-item', role: 'checkbox', 'aria-checked': String(checked), onclick,
        }, dot, h('span', { class: 'check-name' }, label), h('span', { class: 'check-tick', 'aria-hidden': 'true' }, icon('check')));
        const render = () => {
            const list = refs() || [];
            const allOn = list.every(r => !excluded.has(r.id));
            fill(listBox,
                row('Alle', allOn, () => {
                    setExcluded(allOn ? new Set(list.map(r => r.id)) : new Set());
                    render();
                }, h('span', { class: 'check-all-dot', 'aria-hidden': 'true' })),
                ...list.map(r => row(r.name, !excluded.has(r.id), () => {
                    const next = new Set(excluded);
                    if (next.has(r.id)) next.delete(r.id);
                    else next.add(r.id);
                    setExcluded(next);
                    render();
                }, h('span', { class: `color-dot ${colorClass(r.color)}`, 'aria-hidden': 'true' }))));
        };
        render();
        ref.current = openDialog([
            ...sheetHead('Habits', null, ref),
            h('div', { class: 'sheet-body' }, listBox),
        ], { kind: 'sheet', className: 'filter-sheet', label: 'Habits' });
    }

    function onReply(item) {
        navigate(`/c/${enc(item.cohabit.id)}/chat`);
    }

    function renderItems() {
        if (!ctx.alive()) return;
        if (!loadedOnce) {
            fill(listEl, failed ? errorState(failed, reload) : loadingState());
            moreEl.replaceChildren();
            return;
        }
        if (nothingSelected()) {
            fill(listEl, emptyState('Keine Habits ausgewählt'));
            moreEl.replaceChildren();
            return;
        }
        if (!items.length) {
            fill(listEl, emptyState(active().size ? 'Hier ist noch nichts passiert.' : 'Noch nichts passiert.'));
            moreEl.replaceChildren();
            return;
        }
        const today = dayIn();
        const sections = [];
        let current = null;
        for (const item of items) {
            if (!current || current.day !== item.day) {
                current = { day: item.day, items: [] };
                sections.push(current);
            }
            current.items.push(item);
        }
        fill(listEl, ...sections.map(section => h('section', null,
            sectionLabel(dayHeading(section.day, today)),
            h('div', { class: 'tl-list' }, section.items.map(item => timelineItem(item, { onReply }))))));
        fill(moreEl, hasMore
            ? h('button', { type: 'button', class: 'btn soft small', onclick: loadMore }, loading ? 'Lädt …' : 'Mehr laden')
            : null);
        watchMore();
    }

    function watchMore() {
        if (observer) observer.disconnect();
        if (!hasMore || !('IntersectionObserver' in window)) return;
        observer = new IntersectionObserver(entries => {
            if (entries.some(e => e.isIntersecting)) loadMore();
        }, { rootMargin: '400px' });
        observer.observe(moreEl);
    }

    function query(before) {
        const q = new URLSearchParams({ limit: String(PAGE) });
        if (active().size) q.set('exclude', [...active()].join(','));
        if (before) q.set('before', before);
        return `/timeline?${q}`;
    }

    async function reload() {
        const wanted = filterKey();
        if (nothingSelected()) {
            items = [];
            hasMore = false;
            loadedOnce = true;
            failed = null;
            renderItems();
            return;
        }
        const previous = cached(`timeline:${wanted}`);
        if (previous) {
            items = previous.items;
            hasMore = previous.hasMore;
            loadedOnce = true;
        } else {
            loadedOnce = false;
        }
        failed = null;
        loading = true;
        renderItems();
        try {
            const res = await get(query(null));
            if (!ctx.alive() || wanted !== filterKey()) return;
            items = res.items || [];
            hasMore = !!res.hasMore;
            loadedOnce = true;
            remember(`timeline:${wanted}`, { items, hasMore });
            markSeen(wanted === 'all' ? items[0] : null);
        } catch (err) {
            if (wanted !== filterKey()) return;
            if (loadedOnce) showError(err);
            else failed = err.message;
        } finally {
            loading = false;
        }
        renderItems();
    }

    /**
     * Gesehen ist das neueste Ereignis ueberhaupt, auch bei Filter - wie in den Apps.
     * Der Dienst kennt den Filter nicht; mit dem neuesten sichtbaren blieben Fotos
     * ausgeblendeter Co-Habits auf Heute fuer immer „neu".
     */
    async function markSeen(newest) {
        try {
            const top = newest || ((await get('/timeline?limit=1')).items || [])[0];
            if (top) await post('/timeline/seen', { lastEventId: top.id });
        } catch (err) { /* nicht wichtig */ }
    }

    async function loadMore() {
        if (loading || !hasMore || !items.length) return;
        loading = true;
        const wanted = filterKey();
        try {
            const res = await get(query(items[items.length - 1].id));
            if (!ctx.alive() || wanted !== filterKey()) return;
            const known = new Set(items.map(i => i.id));
            items = items.concat((res.items || []).filter(i => !known.has(i.id)));
            hasMore = !!res.hasMore;
        } catch (err) {
            showError(err);
        } finally {
            loading = false;
        }
        renderItems();
    }

    async function loadCohabits() {
        try {
            const before = filterKey();
            cohabits = remember('cohabitsList', await get('/cohabits'));
            // Ausblendungen geloeschter Co-Habits nicht ewig mitschleppen.
            const known = new Set(cohabits.map(s => s.ref.id));
            const pruned = new Set([...excluded].filter(id => known.has(id)));
            if (pruned.size !== excluded.size) {
                excluded = pruned;
                saveExcluded(excluded);
            }
            if (!ctx.alive()) return;
            renderFilter();
            if (filterKey() !== before || nothingSelected()) reload();
        } catch (err) { /* ohne Liste bleibt der Knopf bei seiner Beschriftung */ }
    }

    const hadCache = !!cached(`timeline:${filterKey()}`);
    renderFilter();
    reload();
    if (hadCache) ctx.restoreScroll();
    loadCohabits();
    return { unmount: () => observer && observer.disconnect() };
}
