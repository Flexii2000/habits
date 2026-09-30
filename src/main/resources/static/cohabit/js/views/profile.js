// Profil (S. 5) mit seinen Unterseiten: Benachrichtigungen, archivierte
// Co-Habits; dazu Profil bearbeiten, Daten exportieren, Abmelden und
// Account loeschen (zweistufig, mit Eingabe „LÖSCHEN").
import { API, get, put, del } from '../api.js';
import { h, icon, openDialog, sheetHead, confirmDialog, showError, toast, fill } from '../dom.js';
import { avatar, badge, colorClass, emptyState, errorState, loadingState, toggleRow } from '../ui.js';
import { state, cached, remember } from '../state.js';
import { squareImage, uploadAvatar, pickFile } from '../photo.js';
import { USERNAME_RE } from '../invitation.js';
import { navigate, goBack, refreshMe } from '../app.js';

function listRow(label, { href, onclick, trailing, className = '', download, external } = {}) {
    const inner = [h('span', { class: 'row-label' }, label), trailing || null, icon('chevronRight', 'chev')];
    if (href) {
        return h('a', {
            class: `list-row ${className}`, href,
            'data-nav': download || external ? null : '',
            download: download || null,
        }, inner);
    }
    return h('button', { type: 'button', class: `list-row ${className}`, onclick }, inner);
}

export function subHead(title, fallback = '/profil') {
    return h('div', { class: 'sub-head' },
        h('button', { type: 'button', class: 'circle-btn', 'aria-label': 'Zurück', onclick: () => goBack(fallback) }, icon('back')),
        h('h1', { class: 'page-title' }, title));
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);

    function render() {
        if (!ctx.alive()) return;
        const me = state.me;
        const person = me.person;
        const news = (me.pendingInvitations || 0) + (me.incomingFriendRequests || 0);
        fill(page,
            h('section', { class: 'card profile-card' },
                avatar(person, 96),
                h('h1', { class: 'profile-name' }, person.displayName),
                h('p', { class: 'profile-user' }, `@${person.username}`),
                h('button', { type: 'button', class: 'btn outline', onclick: () => editProfile(render) }, 'Profil bearbeiten')),
            h('div', { class: 'count-tiles' },
                countTile(me.counts.cohabits, 'Co-Habits', 'peach'),
                countTile(me.counts.friends, 'Freunde', 'mint'),
                countTile(me.counts.wins, 'Siege', 'butter')),
            h('nav', { class: 'list', 'aria-label': 'Einstellungen' },
                listRow('Benachrichtigungen', { href: '/cohabit/profil/benachrichtigungen' }),
                listRow('Freunde & Einladungen', { href: '/cohabit/freunde', trailing: news ? badge(`${news} neu`) : null }),
                listRow('Archivierte Co-Habits', { href: '/cohabit/profil/archiv' }),
                listRow('Daten exportieren', { href: `${API}/me/export`, download: true }),
                listRow('App verbinden', { href: '/cohabit/profil/app' }),
                listRow('Rechtliches', { href: '/cohabit/rechtliches', external: true })),
            h('div', { class: 'list' },
                me.canLogout ? h('button', { type: 'button', class: 'list-row', onclick: logout }, h('span', { class: 'row-label' }, 'Abmelden')) : null,
                h('button', { type: 'button', class: 'list-row danger', onclick: deleteAccount }, h('span', { class: 'row-label' }, 'Account löschen'))));
    }

    function countTile(value, label, color) {
        return h('div', { class: `count-tile c-${color}` },
            h('div', { class: 'count-value' }, String(value ?? 0)),
            h('div', { class: 'count-label' }, label));
    }

    render();
    // Zaehler und Abzeichen frisch holen (Einladungen koennen inzwischen da sein).
    refreshMe().then(() => { if (state.me) render(); }).catch(() => { /* alter Stand bleibt */ });
    return {};
}

async function logout() {
    const ok = await confirmDialog({ title: 'Abmelden?', confirm: 'Abmelden' });
    if (!ok) return;
    try {
        await del('/me/app-links/current');
    } catch (err) {
        if (err.status !== 401) {
            showError(err);
            return;
        }
    }
    state.cache.clear();
    await refreshMe().catch(() => { state.me = null; });
    navigate('/', { replace: true });
}

async function deleteAccount() {
    const first = await confirmDialog({
        title: 'Account löschen?',
        text: 'Profil, Einträge, Fotos, Nachrichten und Freundschaften werden gelöscht.',
        confirm: 'Weiter',
        danger: true,
    });
    if (!first) return;
    const ref = {};
    const input = h('input', {
        class: 'field center', type: 'text', autocomplete: 'off', autocapitalize: 'characters', spellcheck: 'false',
        placeholder: 'LÖSCHEN', 'aria-label': 'Zur Bestätigung LÖSCHEN eingeben',
    });
    const confirmBtn = h('button', { type: 'button', class: 'btn danger', disabled: true }, 'Löschen');
    input.addEventListener('input', () => { confirmBtn.disabled = input.value.trim() !== 'LÖSCHEN'; });
    confirmBtn.addEventListener('click', async () => {
        confirmBtn.classList.add('busy');
        try {
            await del('/me', { confirm: 'LÖSCHEN' });
            ref.current.close();
            state.cache.clear();
            state.me = null;
            toast('Account gelöscht.');
            await refreshMe().catch(() => { state.me = null; });
            navigate('/', { replace: true });
        } catch (err) {
            showError(err);
        } finally {
            confirmBtn.classList.remove('busy');
        }
    });
    ref.current = openDialog(h('div', { class: 'confirm' },
        h('h2', { class: 'confirm-title' }, 'Zum Bestätigen „LÖSCHEN“ eingeben'),
        input,
        h('div', { class: 'btn-row' },
            h('button', { type: 'button', class: 'btn outline', autofocus: true, onclick: () => ref.current.close() }, 'Abbrechen'),
            confirmBtn)),
    { kind: 'modal', label: 'Account löschen' });
}

function editProfile(onSaved) {
    const ref = {};
    const person = state.me.person;
    const avatarBox = h('div', { class: 'edit-avatar' });
    const renderAvatar = () => {
        const p = state.me.person;
        fill(avatarBox,
            avatar(p, 88),
            h('div', { class: 'edit-avatar-actions' },
                h('button', { type: 'button', class: 'btn soft small', onclick: changePhoto }, p.avatarPhotoId ? 'Foto ändern' : 'Foto wählen'),
                p.avatarPhotoId ? h('button', { type: 'button', class: 'btn link', onclick: removePhoto }, 'Foto entfernen') : null));
    };
    async function changePhoto() {
        const file = await pickFile();
        if (!file) return;
        try {
            const blob = await squareImage(file, 1024);
            state.me = await uploadAvatar(blob);
            renderAvatar();
            onSaved();
            toast('Foto gespeichert.');
        } catch (err) {
            showError(err);
        }
    }
    async function removePhoto() {
        try {
            state.me = await del('/me/avatar');
            renderAvatar();
            onSaved();
        } catch (err) {
            showError(err);
        }
    }
    const name = h('input', { class: 'field', type: 'text', maxlength: 30, value: person.displayName, autocomplete: 'nickname', 'aria-label': 'Anzeigename' });
    const username = h('input', { class: 'field', type: 'text', maxlength: 20, value: person.username, autocapitalize: 'off', spellcheck: 'false', autocomplete: 'username', 'aria-label': 'Nutzername' });
    username.addEventListener('input', () => {
        const lower = username.value.toLowerCase();
        if (lower !== username.value) username.value = lower;
    });
    const msg = h('p', { class: 'form-msg err', role: 'alert' });
    const save = h('button', { type: 'submit', class: 'btn primary block' }, 'Speichern');
    const form = h('form', { class: 'sheet-body' },
        avatarBox,
        h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, 'Anzeigename'), name),
        h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, 'Nutzername'), username),
        msg, save);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        msg.textContent = '';
        const displayName = name.value.trim();
        const user = username.value.trim();
        if (!displayName) {
            msg.textContent = 'Bitte einen Anzeigenamen angeben.';
            return;
        }
        if (!USERNAME_RE.test(user)) {
            msg.textContent = 'Nutzername: 3–20 Zeichen aus a–z, 0–9, Punkt und Unterstrich, am Anfang ein Buchstabe.';
            return;
        }
        save.classList.add('busy');
        try {
            state.me = await put('/me', { displayName, username: user });
            ref.current.close();
            toast('Gespeichert.');
            onSaved();
        } catch (err) {
            msg.textContent = err.message;
        } finally {
            save.classList.remove('busy');
        }
    });
    renderAvatar();
    ref.current = openDialog([...sheetHead('Profil bearbeiten', null, ref), form], { kind: 'sheet', label: 'Profil bearbeiten' });
}

// --- Benachrichtigungen --------------------------------------------------------

const NOTIFICATION_KEYS = [
    ['checkins', 'Check-ins'],
    ['photos', 'Beweisfotos'],
    ['chat', 'Chat'],
    ['nudges', 'Stupser'],
    ['invites', 'Einladungen'],
    ['reminders', 'Erinnerungen'],
    ['streakAtRisk', 'Gefährdete Streaks'],
    ['challengeEnd', 'Challenge-Ende'],
];

export function mountNotifications(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    let settings = cached('notifications');
    const body = h('div');
    page.append(subHead('Benachrichtigungen'), body);

    function render(failed) {
        if (!ctx.alive()) return;
        if (!settings) {
            fill(body, failed ? errorState(failed, load) : loadingState());
            return;
        }
        fill(body, h('div', { class: 'set-group' }, NOTIFICATION_KEYS.map(([key, label]) => toggleRow(label, {
            checked: settings[key] !== false,
            onchange: async checked => {
                const before = settings;
                settings = { ...settings, [key]: checked };
                try {
                    settings = remember('notifications', await put('/me/notifications', settings));
                } catch (err) {
                    settings = before;
                    showError(err);
                    render();
                }
            },
        }))));
    }

    async function load() {
        try {
            settings = remember('notifications', await get('/me/notifications'));
            render();
        } catch (err) {
            render(err.message);
        }
    }

    render();
    load();
    return {};
}

// --- Archiv ------------------------------------------------------------------

export function mountArchive(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    const body = h('div');
    page.append(subHead('Archivierte Co-Habits'), body);

    async function load() {
        fill(body, loadingState());
        try {
            const list = await get('/me/archived');
            if (!ctx.alive()) return;
            if (!list.length) {
                fill(body, emptyState('Keine archivierten Co-Habits'));
                return;
            }
            fill(body, h('div', { class: 'rows' }, list.map(summary => h('a', {
                class: `card row-card tinted on-tint ${colorClass(summary.ref.color)}`,
                href: `/cohabit/c/${encodeURIComponent(summary.ref.id)}`, 'data-nav': '',
            },
            h('span', { class: 'row-metric' }, summary.headline ? summary.headline.short : ''),
            h('div', { class: 'row-main' },
                h('span', { class: 'row-name' }, summary.ref.name),
                h('span', { class: 'row-sub' }, h('span', null, summary.typeLine || ''))),
            h('span', { class: 'row-chevron', 'aria-hidden': 'true' }, icon('chevronRight'))))));
        } catch (err) {
            fill(body, errorState(err.message, load));
        }
    }

    load();
    return {};
}

