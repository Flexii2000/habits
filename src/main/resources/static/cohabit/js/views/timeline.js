// Timeline (S. 3): Ereignisse aller eigenen Co-Habits, neueste zuerst, nach
// Tagen gruppiert; Filter-Chips je Co-Habit in seiner Farbe. Foto-Karten mit
// Reaktionen und „Antworten" (oeffnet den Chat), uebrige Ereignisse kompakt.
import { get, post, enc } from '../api.js';
import { h, icon, showError, fill } from '../dom.js';
import { avatar, chip, colorClass, emptyState, errorState, loadingState, photo, sectionLabel } from '../ui.js';
import { cached, remember } from '../state.js';
import { dayHeading, dayIn } from '../format.js';
import { reactionBar } from '../reactions.js';
import { navigate } from '../app.js';

const PAGE = 30;

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
    if (item.photoId) {
        return h('article', { class: `tl-item ${color}` },
            h('div', { class: 'tl-head' },
                who,
                h('div', { class: 'tl-texts' },
                    h('p', { class: 'tl-title' }, titleWithName(item)),
                    item.subtitle ? h('p', { class: 'tl-sub' }, item.subtitle) : null),
                item.cohabit ? chip(item.cohabit.name, 'tint') : null),
            photo(item.photoId),
            item.caption ? h('p', { class: 'tl-caption' }, item.caption) : null,
            h('div', { class: 'tl-foot' }, reactionBar(item), reply));
    }
    // Kompakt: Reaktionen und „Antworten" erst auf Tipp, ausser es gibt schon
    // welche - so bleibt die Liste ruhig wie im Entwurf.
    const hasReactions = (item.reactions || []).some(r => r.count > 0);
    const foot = h('div', { class: 'tl-foot', hidden: !hasReactions }, reactionBar(item), reply);
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
    return h('article', { class: `tl-item compact ${color}` }, head, foot);
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    let filter = cached('timelineFilter') || null;
    let cohabits = cached('cohabitsList') || null;
    let items = [];
    let hasMore = false;
    let loading = false;
    let failed = null;
    let loadedOnce = false;
    let observer = null;

    const listEl = h('div', { class: 'tl-sections' });
    const chipsEl = h('div', { class: 'chips-scroll', role: 'group', 'aria-label': 'Filter' });
    const moreEl = h('div', { class: 'more-loader' });

    page.append(
        h('div', { class: 'page-head' }, h('h1', { class: 'page-title' }, 'Timeline')),
        chipsEl, listEl, moreEl);

    function renderChips() {
        const all = [{ id: null, name: 'Alle', color: null }, ...(cohabits || []).map(s => s.ref)];
        fill(chipsEl, ...all.map(ref => h('button', {
            type: 'button',
            class: `filter-chip ${ref.id ? colorClass(ref.color) : ''}`,
            'aria-pressed': String(filter === ref.id),
            onclick: () => {
                if (filter === ref.id) return;
                filter = ref.id;
                remember('timelineFilter', filter);
                renderChips();
                reload();
            },
        }, ref.name)));
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
        if (!items.length) {
            fill(listEl, emptyState(filter ? 'Hier ist noch nichts passiert.' : 'Noch nichts passiert.'));
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
        if (filter) q.set('cohabitId', filter);
        if (before) q.set('before', before);
        return `/timeline?${q}`;
    }

    async function reload() {
        const wanted = filter;
        const previous = cached(`timeline:${filter || 'all'}`);
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
            if (!ctx.alive() || wanted !== filter) return;
            items = res.items || [];
            hasMore = !!res.hasMore;
            loadedOnce = true;
            remember(`timeline:${filter || 'all'}`, { items, hasMore });
            if (!filter && items.length) {
                post('/timeline/seen', { lastEventId: items[0].id }).catch(() => { /* nicht wichtig */ });
            }
        } catch (err) {
            if (wanted !== filter) return;
            if (loadedOnce) showError(err);
            else failed = err.message;
        } finally {
            loading = false;
        }
        renderItems();
    }

    async function loadMore() {
        if (loading || !hasMore || !items.length) return;
        loading = true;
        const wanted = filter;
        try {
            const res = await get(query(items[items.length - 1].id));
            if (!ctx.alive() || wanted !== filter) return;
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
            cohabits = remember('cohabitsList', await get('/cohabits'));
            if (filter && !cohabits.some(s => s.ref.id === filter)) {
                filter = null;
                remember('timelineFilter', null);
                reload();
            }
            if (ctx.alive()) renderChips();
        } catch (err) { /* Filter ohne Chips geht auch */ }
    }

    const hadCache = !!cached(`timeline:${filter || 'all'}`);
    renderChips();
    reload();
    if (hadCache) ctx.restoreScroll();
    loadCohabits();
    return { unmount: () => observer && observer.disconnect() };
}
