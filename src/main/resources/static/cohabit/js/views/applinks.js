// App verbinden (Vertrag 1.2, 5.2 Punkt 5): App-Links erzeugen und widerrufen.
// Der Token steht nur in der Antwort auf das Erzeugen - danach kennt ihn nur
// noch der Hash beim Dienst. Das Web zeigt Link und QR-Code; auf dem Handy
// oeffnet „In der App öffnen" die installierte App direkt.
import { get, post, del, enc, API } from '../api.js';
import { h, icon, confirmDialog, copyText, showError, toast, fill } from '../dom.js';
import { emptyState, errorState, loadingState } from '../ui.js';
import { dateTimeShort, fmtNumber } from '../format.js';
import { qrSvg } from '../qr.js';
import { subHead } from './profile.js';

function tokenFrom(url) {
    const m = /[?&]token=([^&#]+)/.exec(url || '');
    return m ? decodeURIComponent(m[1]) : null;
}

export function mount(root, params, ctx) {
    const page = h('div', { class: 'page' });
    root.append(page);
    const created = h('div');
    const listBox = h('div');
    const apkBox = h('div');
    const label = h('input', { class: 'field', type: 'text', maxlength: 40, placeholder: 'z. B. iPhone', 'aria-label': 'Gerät', autocomplete: 'off' });
    const createBtn = h('button', { type: 'submit', class: 'btn primary block' }, 'App-Link erzeugen');
    const form = h('form', { class: 'set-group pad stack' },
        h('label', { class: 'field-label' }, 'Gerät'), label, createBtn);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        createBtn.classList.add('busy');
        try {
            const res = await post('/me/app-links', { label: label.value.trim() || 'Gerät' });
            label.value = '';
            showCreated(res);
            loadList();
        } catch (err) {
            showError(err);
        } finally {
            createBtn.classList.remove('busy');
        }
    });

    page.append(subHead('App verbinden'), apkBox, form, created, listBox);

    function showCreated(res) {
        const token = res.token || tokenFrom(res.setupUrl);
        const qr = h('div', { class: 'qr' });
        try {
            qr.innerHTML = qrSvg(res.setupUrl);
        } catch (err) {
            qr.remove();
        }
        fill(created, h('section', { class: 'set-group pad stack', 'aria-live': 'polite' },
            h('h2', { class: 'panel-title' }, res.label),
            qr,
            h('code', { class: 'link-box' }, res.setupUrl),
            h('div', { class: 'btn-row' },
                h('button', { type: 'button', class: 'btn soft', onclick: () => copyText(res.setupUrl) }, icon('copy'), 'Kopieren'),
                token ? h('a', { class: 'btn primary', href: `cohabit://setup?token=${enc(token)}` }, 'In der App öffnen') : null)));
        created.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    async function loadList() {
        if (!listBox.firstChild) fill(listBox, loadingState());
        try {
            const links = await get('/me/app-links');
            if (!ctx.alive()) return;
            if (!links.length) {
                fill(listBox, emptyState('Noch keine App verbunden.'));
                return;
            }
            fill(listBox,
                h('h2', { class: 'section-label' }, 'Verbundene Geräte'),
                h('div', { class: 'list' }, links.map(link => h('div', { class: 'person-row' },
                    h('span', { class: 'health-avatar c-periwinkle', 'aria-hidden': 'true' }, icon('phone')),
                    h('div', { class: 'person-texts' },
                        h('div', { class: 'person-name' }, link.label),
                        h('div', { class: 'person-sub' },
                            link.lastUsedAt ? `zuletzt ${dateTimeShort(link.lastUsedAt)}` : `erstellt ${dateTimeShort(link.createdAt)}`)),
                    h('button', {
                        type: 'button', class: 'btn danger-outline small',
                        onclick: async () => {
                            const ok = await confirmDialog({ title: `„${link.label}“ abmelden?`, confirm: 'Abmelden', danger: true });
                            if (!ok) return;
                            try {
                                await del(`/me/app-links/${enc(link.id)}`);
                                toast('Abgemeldet.');
                                loadList();
                            } catch (err) {
                                showError(err);
                            }
                        },
                    }, 'Widerrufen')))));
        } catch (err) {
            fill(listBox, errorState(err.message, loadList));
        }
    }

    async function loadApk() {
        try {
            const info = await get('/app/android');
            if (!ctx.alive() || !info) return;
            const size = info.sizeBytes ? ` · ${fmtNumber(Math.round(info.sizeBytes / 1e5) / 10)} MB` : '';
            fill(apkBox, h('a', { class: 'list', href: `${API}/app/android/apk`, download: true },
                h('span', { class: 'list-row' },
                    icon('download'),
                    h('span', { class: 'row-label' }, 'Android-App', h('small', { class: 'row-note' }, ` ${info.versionName}${size}`)),
                    icon('chevronRight', 'chev'))));
        } catch (err) { /* ohne APK kein Eintrag */ }
    }

    loadApk();
    loadList();
    return {};
}
