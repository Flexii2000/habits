// Chat eines Co-Habits (S. 7, Vertrag 3.6): Check-in-Posts als Karten,
// Textblasen (eigene rechts violett), Fotos und GIFs, Systemmeldungen zentriert.
// Langer Druck bzw. Rechtsklick oeffnet die Reaktionsleiste mit Loeschen,
// Melden, Blockieren (Vertrag 2.7a). Neue Nachrichten alle 20 s, solange die
// Seite sichtbar ist.
import { get, post, del, enc } from './api.js';
import { h, icon, autoGrow, poll, showError, uuid, fill } from './dom.js';
import { avatar, photo, photoCarousel, photoList } from './ui.js';
import { dayHeading, dayIn, fmtTime, isMe, personName } from './format.js';
import { onLongPress, openReactionBar, reactionPill } from './reactions.js';
import { blockPerson, reportDialog } from './social.js';
import { chatImage, pickFile, uploadPhoto } from './photo.js';
import { cssUrl, gifConfig, gifInput, openGifSheet, shareGif } from './gifs.js';

/**
 * Ein GIF aus der Suche: Seitenverhaeltnis aus width/height, animiert als webp
 * (sonst gif), bis dahin das Standbild bzw. der gestreifte Platzhalter.
 */
function gifImage(gif) {
    const w = Math.max(1, Number(gif.width) || 1);
    const hgt = Math.max(1, Number(gif.height) || 1);
    const frame = h('div', { class: 'gif-frame', style: `aspect-ratio:${w} / ${hgt}` });
    if (gif.stillUrl) frame.style.backgroundImage = cssUrl(gif.stillUrl);
    const img = h('img', { src: gif.webpUrl || gif.gifUrl, alt: gif.title || 'GIF', width: w, height: hgt, loading: 'lazy', decoding: 'async' });
    img.addEventListener('load', () => frame.classList.add('loaded'));
    img.addEventListener('error', () => frame.classList.add('failed'));
    frame.append(img);
    return frame;
}

export function mountChat(container, { getDetail, onCheckIn, onRead, alive }) {
    const cohabitId = getDetail().summary.ref.id;
    const base = `/cohabits/${enc(cohabitId)}`;
    let messages = [];
    let hasMore = false;
    let loaded = false;
    let failed = null;
    let sending = false;
    let attachment = null; // { blob, name, url, key }
    let gifs = null; // Konfiguration fuer KLIPY, solange der Chat offen ist
    const nodes = new Map();

    const list = h('div', { class: 'chat', 'aria-live': 'polite' });
    const olderBtn = h('button', { type: 'button', class: 'btn soft small chat-older', hidden: true }, 'Frühere Nachrichten');
    olderBtn.addEventListener('click', loadOlder);

    const text = h('textarea', { rows: 1, placeholder: 'Nachricht', maxlength: 2000, 'aria-label': 'Nachricht' });
    const attachBtn = h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Foto anhängen', title: 'Foto anhängen' }, icon('image'));
    const gifBtn = h('button', { type: 'button', class: 'icon-btn gif-btn', 'aria-label': 'GIF', title: 'GIF', hidden: true },
        h('span', { 'aria-hidden': 'true' }, 'GIF'));
    const send = h('button', { type: 'button', class: 'composer-send', 'aria-label': 'Senden', title: 'Senden', disabled: true }, icon('send'));
    const checkBtn = h('button', { type: 'button', class: 'composer-check' });
    const preview = h('div', { class: 'attach-preview', hidden: true });
    const composer = h('div', { class: 'composer' },
        preview,
        h('div', { class: 'composer-row' }, checkBtn, h('div', { class: 'composer-field' }, text, gifBtn, attachBtn), send));

    fill(container, list, composer);

    function updateComposer() {
        const detail = getDetail();
        const summary = detail.summary;
        composer.hidden = !!summary.archived;
        const canCheck = summary.canCheckIn && summary.ref.type !== 'ABSTINENCE';
        checkBtn.hidden = !canCheck;
        fill(checkBtn,
            icon(summary.photoRequired ? 'camera' : summary.valueUnit ? 'plus' : 'check'),
            h('span', { class: 'composer-check-label' }, summary.ref.type === 'STREAK' ? 'Abhaken' : 'Eintragen'));
        checkBtn.setAttribute('aria-label', summary.checkInLabel || 'Abhaken');
        send.disabled = sending || (!text.value.trim() && !attachment);
    }

    checkBtn.addEventListener('click', () => onCheckIn());
    text.addEventListener('input', () => {
        autoGrow(text);
        updateComposer();
    });
    // Beim Schreiben wird „Abhaken" zum runden Knopf - das Feld braucht dann die Breite.
    // Auf schmalen Bildschirmen erscheint „Senden" erst dann (styles.css).
    const compact = () => composer.classList.toggle('typing', document.activeElement === text || !!text.value || !!attachment);
    text.addEventListener('focus', compact);
    text.addEventListener('blur', () => setTimeout(compact, 150));
    text.addEventListener('keydown', event => {
        // Enter sendet am Rechner; auf dem Handy (ohne Hover) macht Enter eine neue Zeile.
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && matchMedia('(hover: hover)').matches) {
            event.preventDefault();
            sendMessage();
        }
    });
    send.addEventListener('click', sendMessage);
    attachBtn.addEventListener('click', async () => {
        const file = await pickFile();
        if (file) attachFile(file);
    });
    // Ein eingefuegtes Bild (Screenshot, GIF-Datei) wird zum Anhang wie aus der Auswahl.
    text.addEventListener('paste', event => {
        const data = event.clipboardData;
        if (!data) return;
        let files = Array.from(data.files || []);
        if (!files.length) files = Array.from(data.items || []).filter(item => item.kind === 'file').map(item => item.getAsFile()).filter(Boolean);
        const image = files.find(file => file.type.startsWith('image/'));
        if (!image) return;
        event.preventDefault();
        attachFile(image);
    });
    gifBtn.addEventListener('click', () => {
        if (gifs) openGifSheet(gifs, { onPick: sendGif });
    });

    async function attachFile(file) {
        try {
            const { blob, name, gif } = await chatImage(file);
            clearAttachment();
            attachment = { blob, name, url: URL.createObjectURL(blob), key: uuid() };
            fill(preview,
                h('img', { src: attachment.url, alt: gif ? 'GIF zum Senden' : 'Foto zum Senden' }),
                h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Foto entfernen', onclick: () => { clearAttachment(); compact(); updateComposer(); } }, icon('close')));
            preview.hidden = false;
            compact();
            updateComposer();
            text.focus();
        } catch (err) {
            showError(err);
        }
    }

    function clearAttachment() {
        if (attachment) URL.revokeObjectURL(attachment.url);
        attachment = null;
        preview.hidden = true;
        preview.replaceChildren();
    }

    // --- Darstellung --------------------------------------------------------------

    function nearBottom() {
        return window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 160;
    }

    function scrollToBottom() {
        window.scrollTo(0, document.documentElement.scrollHeight);
    }

    function renderAll({ keepBottom = false, keepAnchor = null } = {}) {
        if (!alive()) return;
        if (!loaded) {
            fill(list, failed
                ? h('div', { class: 'chat-empty' }, failed, h('div', null, h('button', { type: 'button', class: 'btn outline small', onclick: loadLatest }, 'Erneut versuchen')))
                : h('div', { class: 'chat-empty' }, h('span', { class: 'spinner' })));
            return;
        }
        const anchorTop = keepAnchor ? keepAnchor.getBoundingClientRect().top : 0;
        const today = dayIn();
        const children = [olderBtn];
        olderBtn.hidden = !hasMore;
        let lastDay = null;
        let prev = null;
        for (const message of messages) {
            const day = dayIn(new Date(message.createdAt));
            if (day !== lastDay) {
                children.push(h('div', { class: 'msg-day' }, dayHeading(day, today)));
                lastDay = day;
                prev = null;
            }
            const key = `${message.id}:${stamp(message)}:${prev && sameAuthor(prev, message) ? 'c' : 'n'}`;
            let node = nodes.get(message.id);
            if (!node || node.dataset.key !== key) {
                node = renderMessage(message, prev);
                node.dataset.key = key;
                nodes.set(message.id, node);
            }
            children.push(node);
            prev = message;
        }
        if (!messages.length) children.push(h('div', { class: 'chat-empty' }, 'Noch keine Nachrichten.'));
        fill(list, ...children);
        if (keepAnchor) {
            window.scrollBy(0, keepAnchor.getBoundingClientRect().top - anchorTop);
        } else if (keepBottom) {
            scrollToBottom();
        }
    }

    function stamp(message) {
        return JSON.stringify([message.deleted, message.reactions, message.text, message.checkin && message.checkin.caption]);
    }

    function sameAuthor(a, b) {
        return a.author && b.author && a.author.id === b.author.id && a.kind === 'TEXT' && b.kind === 'TEXT'
            && new Date(b.createdAt) - new Date(a.createdAt) < 5 * 60000;
    }

    function renderMessage(message, prev) {
        const mine = message.mine || isMe(message.author);
        if (message.kind === 'SYSTEM') {
            const node = h('div', { class: 'msg system' },
                h('p', { class: 'system-text' }, message.systemText || ''),
                pill(message));
            bindActions(node, message);
            return node;
        }
        if (message.kind === 'CHECKIN' && message.checkin) {
            const c = message.checkin;
            const who = message.author || c.person;
            const node = h('div', { class: `msg post${mine ? ' mine' : ''}` },
                h('article', { class: 'post-card' },
                    h('div', { class: 'post-head' },
                        avatar(who, 32),
                        h('span', { class: 'post-title' }, `${personName(who)} · hat abgehakt`),
                        h('time', { datetime: message.createdAt }, fmtTime(message.createdAt))),
                    photoCarousel(message.photoIds && message.photoIds.length ? message.photoIds : photoList(c)),
                    c.valueText ? h('p', { class: 'post-value' }, c.valueText) : null,
                    c.caption ? h('p', { class: 'post-caption' }, c.caption) : null),
                pill(message));
            bindActions(node, message);
            return node;
        }
        const showAuthor = !mine && message.author && !(prev && sameAuthor(prev, message));
        const gif = message.kind === 'GIF' && message.gif && !message.deleted ? message.gif : null;
        const media = !!(gif || message.photoId);
        let bubble;
        if (message.deleted) {
            bubble = h('div', { class: 'bubble deleted' }, 'Nachricht gelöscht');
        } else {
            const onlyMedia = media && !message.text;
            const kind = onlyMedia ? (gif ? ' only-gif' : ' only-photo') : '';
            bubble = h('div', { class: `bubble${kind}`, title: fmtTime(message.createdAt) },
                showAuthor && !onlyMedia ? h('span', { class: 'bubble-author' }, message.author.displayName) : null,
                gif ? gifImage(gif) : null,
                message.photoId ? photo(message.photoId, message.photoAnimated
                    ? { alt: 'GIF', size: 'full' }
                    : { alt: 'Foto' }) : null,
                message.text || null);
        }
        const node = h('div', { class: `msg${mine ? ' mine' : ''}${gif ? ' gif' : ''}` },
            showAuthor && media && !message.text ? h('span', { class: 'bubble-author' }, message.author.displayName) : null,
            bubble,
            message.deleted ? null : pill(message));
        // Breite wie ein Foto, hoechstens so breit, dass das GIF nicht hoeher als 460 px wird.
        if (gif) node.style.setProperty('--ar', String(Math.max(1, Number(gif.width) || 1) / Math.max(1, Number(gif.height) || 1)));
        if (!message.deleted) bindActions(node, message);
        return node;
    }

    /** Antwort des Dienstes auch in den aktuellen Stand uebernehmen und neu zeichnen. */
    function reacted(message) {
        const current = messages.find(m => m.id === message.id);
        if (current && current !== message) current.reactions = message.reactions;
        nodes.delete(message.id);
        renderAll();
    }

    function pill(message) {
        return reactionPill(message, { onChange: () => reacted(message) });
    }

    // --- Aktionen an Nachrichten --------------------------------------------------

    function bindActions(node, message) {
        onLongPress(node, () => openActions(message));
        // Am Rechner ohne Rechtsklick-Gewohnheit: ein leiser Knopf beim Zeigen.
        const more = h('button', { type: 'button', class: 'msg-more', 'aria-label': 'Reagieren', title: 'Reagieren', onclick: () => openActions(message) }, icon('smile'));
        node.append(more);
    }

    function openActions(message) {
        const mine = message.mine || isMe(message.author);
        const author = message.author || (message.checkin && message.checkin.person);
        const actions = [];
        if (mine && ['TEXT', 'PHOTO', 'GIF'].includes(message.kind) && !message.deleted) {
            actions.push({ label: 'Löschen', icon: 'trash', danger: true, onSelect: () => removeMessage(message) });
        }
        if (!mine && author && message.kind !== 'SYSTEM') {
            actions.push({ label: 'Melden', icon: 'flag', onSelect: () => reportDialog(cohabitId, message) });
            actions.push({ label: `${author.displayName} blockieren`, icon: 'block', danger: true, onSelect: () => blockPerson(author, () => loadLatest({ reset: true })) });
        }
        openReactionBar(message, { title: 'Nachricht', actions, onChange: () => reacted(message) });
    }

    async function removeMessage(message) {
        try {
            const updated = await del(`${base}/messages/${enc(message.id)}`);
            merge([updated]);
            renderAll();
        } catch (err) {
            showError(err);
        }
    }

    // --- Laden und Senden ------------------------------------------------------

    function merge(incoming) {
        const byId = new Map(messages.map(m => [m.id, m]));
        for (const m of incoming) byId.set(m.id, m);
        messages = [...byId.values()].sort((a, b) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0));
    }

    async function markRead() {
        const last = messages[messages.length - 1];
        if (!last || document.visibilityState !== 'visible') return;
        const detail = getDetail();
        if (!detail.unreadMessages && last.id === markRead.lastId) return;
        markRead.lastId = last.id;
        try {
            await post(`${base}/read`, { lastMessageId: last.id });
            onRead();
        } catch (err) { /* naechster Versuch beim naechsten Abruf */ }
    }

    async function loadLatest({ reset = false } = {}) {
        const wasBottom = !loaded || nearBottom();
        try {
            const res = await get(`${base}/messages?limit=50`);
            if (!alive()) return;
            const incoming = res.messages || [];
            const known = new Set(messages.map(m => m.id));
            const gap = loaded && messages.length && incoming.length && !incoming.some(m => known.has(m.id));
            if (reset || !loaded || gap) {
                // Luecke (mehr als 50 neue) oder Neustart: frisch aufsetzen.
                messages = incoming;
                hasMore = !!res.hasMore;
                nodes.clear();
            } else {
                merge(incoming);
            }
            loaded = true;
            failed = null;
            renderAll({ keepBottom: wasBottom });
            markRead();
        } catch (err) {
            if (!loaded) {
                failed = err.message;
                renderAll();
            }
        }
    }

    async function loadOlder() {
        if (!messages.length) return;
        olderBtn.classList.add('busy');
        const anchor = nodes.get(messages[0].id);
        try {
            const res = await get(`${base}/messages?before=${enc(messages[0].id)}&limit=50`);
            merge(res.messages || []);
            hasMore = !!res.hasMore;
            renderAll({ keepAnchor: anchor });
        } catch (err) {
            showError(err);
        } finally {
            olderBtn.classList.remove('busy');
        }
    }

    async function sendMessage() {
        const body = text.value.trim();
        if (sending || (!body && !attachment)) return;
        sending = true;
        updateComposer();
        const id = uuid();
        try {
            let photoId = null;
            if (attachment) {
                const uploaded = await uploadPhoto(attachment.blob, attachment.key, attachment.name);
                photoId = uploaded.id;
            }
            const message = await post(`${base}/messages`, { id, text: body || null, photoId });
            text.value = '';
            autoGrow(text);
            clearAttachment();
            compact();
            merge([message]);
            renderAll({ keepBottom: true });
            markRead();
        } catch (err) {
            showError(err);
        } finally {
            sending = false;
            updateComposer();
        }
    }

    /** Ein GIF aus dem Blatt geht sofort raus (ohne Text), danach die Weitergabe an KLIPY. */
    async function sendGif(item, query) {
        const gif = gifInput(item);
        if (!gif) return;
        const cfg = gifs;
        try {
            const message = await post(`${base}/messages`, { id: uuid(), text: null, photoId: null, gif });
            if (!alive()) return;
            merge([message]);
            renderAll({ keepBottom: true });
            markRead();
            shareGif(cfg, gif.slug, query);
        } catch (err) {
            showError(err);
        }
    }

    updateComposer();
    renderAll();
    loadLatest();
    gifConfig().then(cfg => {
        gifs = cfg;
        gifBtn.hidden = !cfg;
    });
    const poller = poll(() => loadLatest(), 20000);

    return {
        refresh: () => loadLatest(),
        update: () => updateComposer(),
        unmount() {
            poller.stop();
            clearAttachment();
        },
    };
}
