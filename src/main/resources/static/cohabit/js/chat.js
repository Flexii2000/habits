// Chat eines Co-Habits (S. 7, Vertrag 3.6): Check-in-Posts als Karten,
// Textblasen (eigene rechts violett), Systemmeldungen zentriert. Langer Druck
// bzw. Rechtsklick oeffnet Reagieren, Loeschen, Melden, Blockieren. Neue
// Nachrichten alle 20 s, solange die Seite sichtbar ist.
import { get, post, del, enc } from './api.js';
import { h, icon, actionSheet, autoGrow, poll, showError, uuid, fill } from './dom.js';
import { avatar, photo } from './ui.js';
import { dayHeading, dayIn, fmtTime, isMe, personName } from './format.js';
import { reactionBar, reactionPicker } from './reactions.js';
import { blockPerson, reportDialog } from './social.js';
import { pickFile, resizeImage, uploadPhoto } from './photo.js';

const LONG_PRESS_MS = 480;

export function mountChat(container, { getDetail, onCheckIn, onRead, alive }) {
    const cohabitId = getDetail().summary.ref.id;
    const base = `/cohabits/${enc(cohabitId)}`;
    let messages = [];
    let hasMore = false;
    let loaded = false;
    let failed = null;
    let sending = false;
    let attachment = null; // { blob, url, key }
    const nodes = new Map();

    const list = h('div', { class: 'chat', 'aria-live': 'polite' });
    const olderBtn = h('button', { type: 'button', class: 'btn soft small chat-older', hidden: true }, 'Frühere Nachrichten');
    olderBtn.addEventListener('click', loadOlder);

    const text = h('textarea', { rows: 1, placeholder: 'Nachricht', maxlength: 2000, 'aria-label': 'Nachricht' });
    const attachBtn = h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Foto anhängen', title: 'Foto anhängen' }, icon('image'));
    const send = h('button', { type: 'button', class: 'composer-send', 'aria-label': 'Senden', title: 'Senden', disabled: true }, icon('send'));
    const checkBtn = h('button', { type: 'button', class: 'composer-check' });
    const preview = h('div', { class: 'attach-preview', hidden: true });
    const composer = h('div', { class: 'composer' },
        preview,
        h('div', { class: 'composer-row' }, checkBtn, h('div', { class: 'composer-field' }, text, attachBtn), send));

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
    const compact = () => composer.classList.toggle('typing', document.activeElement === text || !!text.value);
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
        if (!file) return;
        try {
            const blob = await resizeImage(file);
            clearAttachment();
            attachment = { blob, url: URL.createObjectURL(blob), key: uuid() };
            fill(preview,
                h('img', { src: attachment.url, alt: 'Foto zum Senden' }),
                h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Foto entfernen', onclick: () => { clearAttachment(); updateComposer(); } }, icon('close')));
            preview.hidden = false;
            updateComposer();
            text.focus();
        } catch (err) {
            showError(err);
        }
    });

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
                (message.reactions || []).some(r => r.count > 0) ? reactionBar(message) : null);
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
                    c.photoId ? photo(c.photoId) : null,
                    c.valueText ? h('p', { class: 'post-value' }, c.valueText) : null,
                    c.caption ? h('p', { class: 'post-caption' }, c.caption) : null,
                    reactionBar(message)));
            bindActions(node, message);
            return node;
        }
        const showAuthor = !mine && message.author && !(prev && sameAuthor(prev, message));
        let bubble;
        if (message.deleted) {
            bubble = h('div', { class: 'bubble deleted' }, 'Nachricht gelöscht');
        } else {
            const onlyPhoto = message.photoId && !message.text;
            bubble = h('div', { class: `bubble${onlyPhoto ? ' only-photo' : ''}`, title: fmtTime(message.createdAt) },
                showAuthor && !onlyPhoto ? h('span', { class: 'bubble-author' }, message.author.displayName) : null,
                message.photoId ? photo(message.photoId, { alt: 'Foto' }) : null,
                message.text || null);
        }
        const node = h('div', { class: `msg${mine ? ' mine' : ''}` },
            showAuthor && message.photoId && !message.text ? h('span', { class: 'bubble-author' }, message.author.displayName) : null,
            bubble,
            !message.deleted && (message.reactions || []).some(r => r.count > 0) ? reactionBar(message) : null);
        if (!message.deleted) bindActions(node, message);
        return node;
    }

    // --- Aktionen an Nachrichten --------------------------------------------------

    function bindActions(node, message) {
        let timer = null;
        let startX = 0;
        let startY = 0;
        node.addEventListener('contextmenu', event => {
            if (event.target.closest('a')) return;
            event.preventDefault();
            openActions(message);
        });
        node.addEventListener('touchstart', event => {
            const t = event.touches[0];
            startX = t.clientX;
            startY = t.clientY;
            clearTimeout(timer);
            timer = setTimeout(() => {
                timer = null;
                openActions(message);
            }, LONG_PRESS_MS);
        }, { passive: true });
        const cancel = () => { clearTimeout(timer); timer = null; };
        node.addEventListener('touchmove', event => {
            const t = event.touches[0];
            if (Math.abs(t.clientX - startX) > 8 || Math.abs(t.clientY - startY) > 8) cancel();
        }, { passive: true });
        node.addEventListener('touchend', cancel);
        node.addEventListener('touchcancel', cancel);
        // Am Rechner ohne Rechtsklick-Gewohnheit: ein leiser Knopf beim Zeigen.
        const more = h('button', { type: 'button', class: 'msg-more', 'aria-label': 'Aktionen', onclick: () => openActions(message) }, '···');
        node.append(more);
    }

    function openActions(message) {
        const mine = message.mine || isMe(message.author);
        const author = message.author || (message.checkin && message.checkin.person);
        const actions = [];
        if (mine && (message.kind === 'TEXT' || message.kind === 'PHOTO') && !message.deleted) {
            actions.push({ label: 'Löschen', icon: 'trash', danger: true, onSelect: () => removeMessage(message) });
        }
        if (!mine && author && message.kind !== 'SYSTEM') {
            actions.push({ label: 'Melden', icon: 'flag', onSelect: () => reportDialog(cohabitId, message) });
            actions.push({ label: `${author.displayName} blockieren`, icon: 'block', danger: true, onSelect: () => blockPerson(author, () => loadLatest({ reset: true })) });
        }
        const picker = message.deleted ? null : reactionPicker(message, () => {
            nodes.delete(message.id);
            renderAll();
        });
        actionSheet('Nachricht', actions, picker);
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
                const uploaded = await uploadPhoto(attachment.blob, attachment.key);
                photoId = uploaded.id;
            }
            const message = await post(`${base}/messages`, { id, text: body || null, photoId });
            text.value = '';
            autoGrow(text);
            clearAttachment();
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

    updateComposer();
    renderAll();
    loadLatest();
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
