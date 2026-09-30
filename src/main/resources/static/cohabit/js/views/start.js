// Start ohne Zugang (S. 11, angepasst): Logo, Kreise, Feld „Link einfügen".
// Nimmt coHabit-App-Links, den Healthy-Setup-Link und Einladungslinks, auch
// mitten in einem kopierten Text (Vertrag 5.4). Keine Apple-/Google-/E-Mail-Knoepfe.
import { API } from '../api.js';
import { h } from '../dom.js';
import { logo } from '../ui.js';
import { navigate } from '../app.js';

/** Erkennt die Art des Links; null, wenn nichts Brauchbares darin steht. */
export function parseLink(text) {
    const s = String(text || '');
    let m = /cohabit\/setup\?(?:[^\s]*&)?token=([A-Za-z0-9_-]+)/i.exec(s) || /cohabit:\/\/setup\?(?:[^\s]*&)?token=([A-Za-z0-9_-]+)/i.exec(s);
    if (m) return { kind: 'setup', token: m[1] };
    m = /food\.fherrmann\.com\/setup\?(?:[^\s]*&)?token=([A-Za-z0-9._~-]+)/i.exec(s);
    if (m) return { kind: 'healthy', token: m[1] };
    m = /cohabit\/join\/([A-Za-z0-9]+)/.exec(s) || /cohabit:\/\/join\/([A-Za-z0-9]+)/.exec(s);
    if (m) return { kind: 'join', code: m[1] };
    return null;
}

/**
 * Healthy-Link im Browser: Der Healthy-Token taugt als Bearer fuer die API,
 * das Cookie dafuer setzt aber nur food.fherrmann.com. Deshalb tauscht die
 * Seite ihn gegen einen eigenen App-Link „Browser" und meldet sich damit an -
 * im Profil unter „App verbinden" sichtbar und einzeln widerrufbar.
 */
async function loginWithHealthyToken(token) {
    const headers = { Authorization: `Bearer ${token}`, Accept: 'application/json' };
    const me = await fetch(`${API}/me`, { headers, credentials: 'omit' });
    if (!me.ok) return false;
    const res = await fetch(`${API}/me/app-links`, {
        method: 'POST',
        headers: { ...headers, 'Content-Type': 'application/json' },
        credentials: 'omit',
        body: JSON.stringify({ label: 'Browser' }),
    });
    if (!res.ok) return false;
    const link = await res.json();
    const appToken = link.token || (/token=([^&]+)/.exec(link.setupUrl || '') || [])[1];
    if (!appToken) return false;
    location.assign(`/cohabit/setup?token=${encodeURIComponent(appToken)}`);
    return true;
}

export function mount(root, params) {
    document.title = 'coHabit';
    const input = h('input', {
        class: 'field', type: 'text', placeholder: 'Link einfügen', autocomplete: 'off',
        autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'Link einfügen', inputmode: 'url',
    });
    const msg = h('p', { class: 'form-msg err', role: 'alert' }, params.invalid ? 'Link ungültig' : '');
    const submit = h('button', { type: 'submit', class: 'btn primary block' }, 'Weiter');
    const form = h('form', { class: 'start-form' }, input, msg, submit);
    if (params.invalid) input.classList.add('invalid');
    input.addEventListener('input', () => {
        input.classList.remove('invalid');
        msg.textContent = '';
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const link = parseLink(input.value);
        if (!link) {
            msg.textContent = 'Link ungültig';
            input.classList.add('invalid');
            return;
        }
        if (link.kind === 'join') {
            navigate(`/join/${encodeURIComponent(link.code)}`);
            return;
        }
        if (link.kind === 'setup') {
            // Der Dienst setzt das Cookie und leitet zurueck (bei ungueltigem Token mit ?setup=invalid).
            location.assign(`/cohabit/setup?token=${encodeURIComponent(link.token)}`);
            return;
        }
        submit.classList.add('busy');
        try {
            const ok = await loginWithHealthyToken(link.token);
            if (!ok) {
                msg.textContent = 'Link ungültig';
                input.classList.add('invalid');
            }
        } catch (err) {
            msg.textContent = 'Keine Verbindung zum Server.';
        } finally {
            submit.classList.remove('busy');
        }
    });

    root.append(h('div', { class: 'start' },
        h('div', { class: 'start-circles', 'aria-hidden': 'true' },
            h('span', { class: 's1' }), h('span', { class: 's2' }), h('span', { class: 's3' }), h('span', { class: 's4' })),
        h('div', { class: 'start-bottom' },
            h('h1', null, logo()),
            form,
            h('p', { class: 'start-legal' }, h('a', { href: '/cohabit/rechtliches' }, 'Rechtliches')))));
    if (params.invalid) {
        // Den Hinweis nur einmal zeigen: ein Neuladen soll nicht wieder „ungueltig" sagen.
        history.replaceState(history.state, '', '/cohabit/');
    }
    return {};
}
