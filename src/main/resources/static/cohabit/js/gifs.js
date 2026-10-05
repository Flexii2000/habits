// GIFs aus der Suche von KLIPY (Vertrag 2.7a). KLIPYs Bedingungen sind
// verbindlich: Suche und Medien gehen direkt vom Browser an KLIPY (kein Umweg
// ueber den Dienst), URLs unveraendert, Ergebnisse in der gelieferten
// Reihenfolge und ungefiltert, ein eigenes Raster, Platzhalter des Suchfelds
// woertlich „Search KLIPY". Der Schluessel steht in keinem Repo - er kommt zur
// Laufzeit von /gifs/config und bleibt nur im Speicher.
import { get } from './api.js';
import { h, openDialog, sheetHead, fill } from './dom.js';

const KLIPY = 'https://api.klipy.com/api/v1';
const PER_PAGE = 24;
const DEBOUNCE_MS = 300;

let config = null;

/** Die Konfiguration, einmal je Chat frisch geholt; ohne Antwort die letzte bekannte. */
export async function gifConfig() {
    try {
        config = await get('/gifs/config');
    } catch (err) { /* ohne Konfiguration eben ohne GIF-Knopf */ }
    return config && config.enabled && config.apiKey ? config : null;
}

function klipyUrl(cfg, path, params = {}) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries({
        ...params,
        customer_id: cfg.customerId,
        locale: cfg.locale,
        content_filter: cfg.contentFilter,
    })) {
        if (value != null && value !== '') query.set(key, String(value));
    }
    return `${KLIPY}/${encodeURIComponent(cfg.apiKey)}/gifs/${path}?${query}`;
}

async function fetchPage(cfg, q, page, signal) {
    const url = q
        ? klipyUrl(cfg, 'search', { q, page, per_page: PER_PAGE })
        : klipyUrl(cfg, 'trending', { page, per_page: PER_PAGE });
    const res = await fetch(url, { signal, credentials: 'omit', headers: { Accept: 'application/json' } });
    const body = await res.json().catch(() => null);
    if (!res.ok || !body || body.result === false || !body.data) throw new Error('KLIPY antwortet nicht.');
    return {
        // Eintraege ohne Datei (etwa Werbung) ueberspringen, sonst nichts aussortieren.
        items: (body.data.data || []).filter(item => item && item.file),
        hasNext: !!body.data.has_next,
    };
}

/** Feuern und vergessen, nach dem Senden (KLIPY zaehlt damit Weitergaben). */
export function shareGif(cfg, slug, q) {
    if (!cfg || !slug) return;
    fetch(`${KLIPY}/${encodeURIComponent(cfg.apiKey)}/gifs/share/${encodeURIComponent(slug)}`, {
        method: 'POST',
        credentials: 'omit',
        keepalive: true,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ customer_id: cfg.customerId, q: q || '' }),
    }).catch(() => { /* egal */ });
}

/** Erste vorhandene Groesse mit animiertem Bild, fuer die Kacheln `sm` (webp, sonst gif). */
function tileMedia(item) {
    for (const size of ['sm', 'xs', 'md', 'hd']) {
        const f = item.file[size];
        const media = f && (f.webp || f.gif);
        if (media && media.url) return media;
    }
    return null;
}

/**
 * Was in die Nachricht geht (`GifInput`): aus `md`, fehlt das `hd`, dann `sm`.
 * Ohne GIF-Datei in diesen Groessen null.
 */
export function gifInput(item) {
    for (const size of ['md', 'hd', 'sm']) {
        const f = item.file && item.file[size];
        if (!f || !f.gif || !f.gif.url) continue;
        const dims = f.gif.width && f.gif.height ? f.gif : (f.webp || f.gif);
        return {
            slug: item.slug,
            title: item.title || null,
            width: Math.max(1, Math.round(Number(dims.width) || 1)),
            height: Math.max(1, Math.round(Number(dims.height) || 1)),
            gifUrl: f.gif.url,
            webpUrl: (f.webp && f.webp.url) || null,
            mp4Url: (f.mp4 && f.mp4.url) || null,
            stillUrl: (f.jpg && f.jpg.url) || null,
        };
    }
    return null;
}

/** Hintergrundbild aus einer Adresse, ohne dass sie das CSS verlaesst. */
export function cssUrl(url) {
    return `url(${JSON.stringify(String(url))})`;
}

/**
 * Das GIF-Blatt: Suchfeld oben, darunter zwei Spalten (jede Kachel im
 * Seitenverhaeltnis, die naechste in die kuerzere Spalte - so bleibt die
 * Reihenfolge von KLIPY erhalten). Antippen ruft `onPick(item, suchbegriff)`
 * und schliesst das Blatt.
 */
export function openGifSheet(cfg, { onPick }) {
    const ref = {};
    const search = h('input', {
        class: 'field gif-search-input', type: 'search', placeholder: 'Search KLIPY', 'aria-label': 'Search KLIPY',
        autocomplete: 'off', enterkeyhint: 'search', maxlength: 100,
        autofocus: matchMedia('(hover: hover)').matches ? true : null,
    });
    const cols = [h('div', { class: 'gif-col' }), h('div', { class: 'gif-col' })];
    const grid = h('div', { class: 'gif-grid' }, cols);
    const status = h('div', { class: 'gif-status' });
    const sentinel = h('div', { class: 'gif-sentinel', 'aria-hidden': 'true' });

    let query = '';
    let page = 0;
    let hasNext = false;
    let loading = false;
    let generation = 0;
    let controller = null;
    let heights = [0, 0];
    let timer = null;
    let observer = null;

    const showStatus = (content) => fill(status, content);

    function tile(item) {
        const media = tileMedia(item);
        if (!media) return null;
        const w = Number(media.width) || 1;
        const hgt = Number(media.height) || 1;
        const btn = h('button', {
            type: 'button', class: 'gif-tile', 'aria-label': item.title || 'GIF', title: item.title || null,
            style: `aspect-ratio:${w} / ${hgt}`,
        });
        if (item.blur_preview) btn.style.backgroundImage = cssUrl(item.blur_preview);
        const img = h('img', { src: media.url, alt: '', width: w, height: hgt, loading: 'lazy', decoding: 'async' });
        img.addEventListener('load', () => btn.classList.add('loaded'));
        btn.append(img);
        btn.addEventListener('click', () => {
            ref.current.close();
            onPick(item, query);
        });
        return { btn, ratio: hgt / w };
    }

    function add(items) {
        for (const item of items) {
            const t = tile(item);
            if (!t) continue;
            const col = heights[0] <= heights[1] ? 0 : 1;
            cols[col].append(t.btn);
            heights[col] += t.ratio;
        }
    }

    async function load(reset) {
        if (reset) {
            generation++;
            if (controller) controller.abort();
            page = 0;
            hasNext = false;
            heights = [0, 0];
            cols.forEach(col => col.replaceChildren());
        } else if (loading || !hasNext) {
            return;
        }
        const mine = generation;
        controller = new AbortController();
        loading = true;
        showStatus(h('span', { class: 'spinner' }));
        try {
            const result = await fetchPage(cfg, query, page + 1, controller.signal);
            if (mine !== generation) return;
            page += 1;
            hasNext = result.hasNext;
            add(result.items);
            showStatus(page === 1 && !result.items.length ? h('p', null, 'Keine GIFs gefunden.') : null);
        } catch (err) {
            if (err.name === 'AbortError' || mine !== generation) return;
            showStatus(h('div', { class: 'gif-error' },
                h('p', null, 'KLIPY ist gerade nicht erreichbar.'),
                h('button', { type: 'button', class: 'btn soft small', onclick: () => load(page === 0) }, 'Erneut versuchen')));
        } finally {
            if (mine === generation) loading = false;
        }
        // Fuellt eine Seite den Platz nicht, sofort weiter (der Waechter sieht keine Aenderung).
        if (mine === generation && hasNext && sentinelVisible()) load(false);
    }

    function sentinelVisible() {
        const dialog = ref.current;
        if (!dialog || !dialog.open) return false;
        return sentinel.getBoundingClientRect().top < dialog.getBoundingClientRect().bottom + 600;
    }

    search.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
            const next = search.value.trim();
            if (next === query) return;
            query = next;
            ref.current.scrollTop = 0;
            load(true);
        }, DEBOUNCE_MS);
    });
    search.addEventListener('keydown', event => {
        if (event.key === 'Enter') event.preventDefault();
    });

    ref.current = openDialog([
        ...sheetHead('GIF', null, ref),
        h('div', { class: 'gif-search' }, search),
        grid, status, sentinel,
    ], {
        kind: 'sheet', className: 'gif-sheet', label: 'GIF',
        onClose: () => {
            clearTimeout(timer);
            generation++;
            if (controller) controller.abort();
            if (observer) observer.disconnect();
        },
    });
    if ('IntersectionObserver' in window) {
        observer = new IntersectionObserver(entries => {
            if (entries.some(e => e.isIntersecting)) load(false);
        }, { root: ref.current, rootMargin: '0px 0px 600px 0px' });
        observer.observe(sentinel);
    } else {
        ref.current.addEventListener('scroll', () => { if (sentinelVisible()) load(false); }, { passive: true });
    }
    load(true);
    return ref.current;
}
