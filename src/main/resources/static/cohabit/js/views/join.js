// Einladungslink /cohabit/join/{code} (Vertrag 2.7, 3.1): Vorschau ohne
// Anmeldung; wer keinen Zugang hat, registriert sich hier (Anzeigename,
// Nutzername, Zustimmungszeile) und ist danach Mitglied bzw. befreundet.
import { get, post, enc } from '../api.js';
import { h, fill } from '../dom.js';
import { errorState, loadingState, logo } from '../ui.js';
import { state, forget } from '../state.js';
import { invitationCard } from '../invitation.js';
import { navigate, refreshMe } from '../app.js';

export function mount(root, params, ctx) {
    const code = params.code;
    const page = h('div', { class: 'join-page' });
    root.append(page);
    document.title = 'Einladung – coHabit';
    const top = h('a', { href: '/cohabit/', 'data-nav': '', style: 'text-decoration:none' }, logo());
    page.append(top, loadingState());

    async function load() {
        fill(page, top, loadingState());
        let preview;
        try {
            preview = await get(`/invite-links/${enc(code)}`, { quiet401: true });
        } catch (err) {
            if (!ctx.alive()) return;
            const text = err.status === 404 ? 'Dieser Einladungslink ist ungültig oder abgelaufen.' : err.message;
            fill(page, top, errorState(text, err.status === 404 ? null : load),
                h('a', { class: 'btn outline block', href: '/cohabit/', 'data-nav': '' }, state.me ? 'Zu Heute' : 'Zum Start'));
            return;
        }
        if (!ctx.alive()) return;
        const loggedIn = !!state.me;
        const card = invitationCard({
            from: preview.from,
            cohabit: preview.cohabit,
            kind: preview.kind,
            full: preview.full,
            register: !loggedIn,
            onAccept: async fields => {
                const res = await post(`/invite-links/${enc(code)}/accept`, loggedIn ? {} : fields, { quiet401: true });
                // Der Dienst setzt im Web das Cookie; MeView kommt gleich mit.
                if (res && res.me) state.me = res.me;
                else await refreshMe();
                state.cache.clear();
                forget('today');
                if (res && res.cohabitId) navigate(`/c/${enc(res.cohabitId)}`, { replace: true });
                else navigate(preview.kind === 'FRIEND' ? '/freunde' : '/', { replace: true });
            },
            onDecline: async () => {
                navigate('/', { replace: true });
            },
        });
        fill(page, top, card);
    }

    load();
    return {};
}
