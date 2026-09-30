// Statistik (S. 4): Woche/Monat/Jahr, Erfuellungsquote, laengste Serie,
// Heatmap (Monat als Kalender Mo-So, Woche als Zeile, Jahr als zwoelf
// Monatsbloecke), Fortschritt je Co-Habit. Alle Zahlen kommen vom Dienst.
import { get, enc } from '../api.js';
import { h, icon, prefs, fill } from '../dom.js';
import { colorClass, errorState, loadingState, progressBar } from '../ui.js';
import { cached, remember } from '../state.js';
import { addDays, dayIn, monthName, parseDay } from '../format.js';

const RANGES = [['WEEK', 'Woche'], ['MONTH', 'Monat'], ['YEAR', 'Jahr']];
const DOW = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

function weekdayIndex(day) {
    return (parseDay(day).getUTCDay() + 6) % 7;
}

function cell(day, today, withNumber = true) {
    const future = day.date > today;
    const level = Math.max(0, Math.min(4, day.level || 0));
    const label = `${day.date}: ${day.count}`;
    return h('span', {
        class: `heat-cell ${future ? 'future' : `l${level}`}${day.date === today ? ' today' : ''}`,
        title: label,
        'aria-label': label,
    }, withNumber ? String(Number(day.date.slice(8))) : '');
}

function monthGrid(days, today, withNumbers = true, withHeader = true) {
    const lead = days.length ? weekdayIndex(days[0].date) : 0;
    return h('div', { class: 'heat-grid' },
        withHeader ? DOW.map(d => h('span', { class: 'heat-dow' }, d)) : null,
        Array.from({ length: lead }, () => h('span', { class: 'heat-cell blank', 'aria-hidden': 'true' })),
        days.map(day => cell(day, today, withNumbers)));
}

function yearGrid(days, today) {
    const months = new Map();
    for (const day of days) {
        const key = day.date.slice(0, 7);
        if (!months.has(key)) months.set(key, []);
        months.get(key).push(day);
    }
    return h('div', { class: 'heat-year' }, [...months.entries()].map(([key, list]) => h('div', { class: 'heat-month-block' },
        h('h3', null, monthName(`${key}-01`)),
        monthGrid(list, today, false, false))));
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    let range = prefs.get('statsRange', 'MONTH');
    if (!RANGES.some(([k]) => k === range)) range = 'MONTH';
    let anchor = dayIn();
    let data = cached(`stats:${range}:${anchor}`);
    let failed = null;

    const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Zeitraum' });
    const body = h('div');
    page.append(h('div', { class: 'page-head' }, h('h1', { class: 'page-title' }, 'Statistik'), seg), body);

    function renderSeg() {
        fill(seg, ...RANGES.map(([key, text]) => h('button', {
            type: 'button', 'aria-pressed': String(range === key),
            onclick: () => {
                if (range === key) return;
                range = key;
                prefs.set('statsRange', key);
                anchor = dayIn();
                renderSeg();
                load();
            },
        }, text)));
    }

    function shift(direction) {
        if (!data) return;
        const heat = data.heatmap;
        anchor = direction < 0 ? addDays(heat.from, -1) : addDays(heat.to, 1);
        load();
    }

    function render() {
        if (!ctx.alive()) return;
        if (!data) {
            fill(body, failed ? errorState(failed, load) : loadingState());
            return;
        }
        const today = dayIn();
        const heat = data.heatmap || { from: today, to: today, days: [] };
        const canNext = heat.to < today;
        let grid;
        if (data.range === 'YEAR') grid = yearGrid(heat.days, today);
        else grid = monthGrid(heat.days, today, true, true);

        fill(body,
            h('div', { class: 'stat-cards' },
                h('div', { class: 'card stat-card accent deco tr' },
                    h('span', { class: 'stat-label' }, 'Erfüllungsquote'),
                    h('span', { class: 'stat-value' }, data.fulfillmentRate == null ? '–' : `${data.fulfillmentRate}%`),
                    h('span', { class: 'stat-foot' }, data.label)),
                h('div', { class: 'card stat-card' },
                    h('span', { class: 'stat-label' }, 'Längste Serie'),
                    h('span', { class: 'stat-value' }, data.longestStreak ? data.longestStreak.short : '–'),
                    h('span', { class: 'stat-foot' }, data.longestStreak ? data.longestStreak.cohabit.name : ''))),
            h('div', { class: 'card heat-card' },
                h('div', { class: 'heat-head' },
                    h('div', { class: 'heat-title' },
                        h('button', { type: 'button', class: 'heat-nav', 'aria-label': 'Früher', onclick: () => shift(-1) }, icon('chevronLeft')),
                        h('span', null, data.label),
                        h('button', { type: 'button', class: 'heat-nav', 'aria-label': 'Später', disabled: !canNext, onclick: () => shift(1) }, icon('chevronRight'))),
                    h('span', { class: 'heat-caption' }, 'erledigte Haken pro Tag')),
                grid,
                h('div', { class: 'heat-legend', 'aria-hidden': 'true' },
                    'weniger', h('i', { class: 'l1' }), h('i', { class: 'l2' }), h('i', { class: 'l3' }), h('i', { class: 'l4' }), 'mehr')),
            (data.cohabits || []).length ? h('div', { class: 'stat-rows' }, data.cohabits.map(entry => h('a', {
                class: `stat-row ${colorClass(entry.ref.color)}`, href: `/cohabit/c/${enc(entry.ref.id)}`, 'data-nav': '',
            },
            h('span', { class: 'color-dot', 'aria-hidden': 'true' }),
            h('span', { class: 'stat-row-name' }, entry.ref.name),
            progressBar(entry.fraction),
            h('span', { class: 'stat-row-value' }, entry.progressText)))) : null);
    }

    async function load() {
        const key = `stats:${range}:${anchor}`;
        const wantedKey = key;
        data = cached(key) || (data && data.range === range ? data : null);
        failed = null;
        render();
        try {
            const fresh = await get(`/stats?range=${range}&anchor=${anchor}`);
            if (`stats:${range}:${anchor}` !== wantedKey) return;
            data = remember(key, fresh);
        } catch (err) {
            if (err.status === 401) return;
            failed = err.message;
            if (data && data.range !== range) data = null;
        }
        render();
    }

    renderSeg();
    load();
    return {};
}
