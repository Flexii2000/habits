// Einladungsdialog (S. 16) - fuer Einladungen in der App (InvitationView) und
// fuer Einladungslinks (InviteLinkPreview), dort ohne Zugang zugleich die
// Registrierung (Vertrag 1.2): Anzeigename, Nutzername, Zustimmungszeile.
import { post, enc } from './api.js';
import { h, openDialog, showError, toast } from './dom.js';
import { avatarStack, chip, colorClass } from './ui.js';

export const USERNAME_RE = /^[a-z][a-z0-9._]{2,19}$/;

/** Vorschlag fuer den Nutzernamen aus dem Anzeigenamen. */
export function suggestUsername(displayName) {
    const base = displayName.toLowerCase()
        .replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
        .normalize('NFKD').replace(/[̀-ͯ]/g, '')
        .replace(/\s+/g, '.').replace(/[^a-z0-9._]/g, '').replace(/^[^a-z]+/, '').slice(0, 20);
    return base;
}

function seatsChip(seats) {
    if (!seats) return null;
    const free = Math.max(0, seats.max - seats.used);
    return chip(free === 0 ? 'Alle Plätze belegt' : `${free} von ${seats.max} Plätzen frei`);
}

/**
 * Baut die Karte. `mode`: 'invitation' | 'link'. `onAccept(fields)` und
 * `onDecline()` liefern Promises; die Karte zeigt waehrenddessen den Knopf als
 * beschaeftigt und Fehler des Dienstes darunter.
 */
export function invitationCard({ from, cohabit, kind = 'COHABIT', full = false, register = false, onAccept, onDecline }) {
    const isFriend = kind === 'FRIEND' || !cohabit;
    const color = isFriend ? (from && from.color) : cohabit.ref.color;
    const title = isFriend
        ? `${from.displayName} möchte mit dir befreundet sein`
        : `${from.displayName} lädt dich zu „${cohabit.ref.name}“ ein`;
    const people = isFriend ? [from] : (cohabit.members && cohabit.members.length ? cohabit.members : [from]);
    const stack = avatarStack(people, 54, 3);
    stack.append(h('span', { class: 'avatar join', style: '--size:54px', 'aria-hidden': 'true' }, '+'));

    const msg = h('p', { class: 'form-msg err', role: 'alert' });
    let displayName = null;
    let username = null;
    let form = null;
    if (register) {
        displayName = h('input', { class: 'field', type: 'text', maxlength: 30, autocomplete: 'nickname', 'aria-label': 'Anzeigename', placeholder: 'Anzeigename' });
        username = h('input', {
            class: 'field', type: 'text', maxlength: 20, autocomplete: 'username', autocapitalize: 'off', spellcheck: 'false',
            'aria-label': 'Nutzername', placeholder: 'Nutzername',
        });
        let touched = false;
        displayName.addEventListener('input', () => {
            if (!touched) username.value = suggestUsername(displayName.value);
        });
        username.addEventListener('input', () => {
            touched = true;
            const lower = username.value.toLowerCase();
            if (lower !== username.value) username.value = lower;
        });
        form = h('form', { onsubmit: e => { e.preventDefault(); accept.click(); } },
            h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, 'Anzeigename'), displayName),
            h('div', { class: 'field-group' }, h('label', { class: 'field-label' }, 'Nutzername'), username),
            h('button', { type: 'submit', hidden: true, tabindex: '-1' }));
    }

    const decline = h('button', { type: 'button', class: 'btn outline' }, 'Ablehnen');
    const accept = h('button', { type: 'button', class: 'btn primary', disabled: full && !isFriend }, isFriend ? 'Annehmen' : 'Mitmachen');

    accept.addEventListener('click', async () => {
        msg.textContent = '';
        let fields = {};
        if (register) {
            const name = displayName.value.trim();
            const user = username.value.trim();
            if (!name) {
                msg.textContent = 'Bitte einen Anzeigenamen angeben.';
                displayName.focus();
                return;
            }
            if (!USERNAME_RE.test(user)) {
                msg.textContent = 'Nutzername: 3–20 Zeichen aus a–z, 0–9, Punkt und Unterstrich, am Anfang ein Buchstabe.';
                username.focus();
                return;
            }
            fields = { displayName: name, username: user, acceptTerms: true };
        }
        accept.classList.add('busy');
        try {
            await onAccept(fields);
        } catch (err) {
            if (err.name !== 'AbortError') msg.textContent = err.message;
        } finally {
            accept.classList.remove('busy');
        }
    });
    decline.addEventListener('click', async () => {
        decline.classList.add('busy');
        try {
            await onDecline();
        } catch (err) {
            showError(err);
        } finally {
            decline.classList.remove('busy');
        }
    });

    const chips = isFriend ? null : h('div', { class: 'chips' },
        cohabit.typeLine ? chip(cohabit.typeLine) : null,
        (cohabit.rules || []).map(rule => chip(rule)),
        seatsChip(cohabit.seats));

    return h('div', { class: `inv ${colorClass(color)}` },
        h('div', { class: 'inv-top deco tr' },
            h('div', { class: 'inv-avatars on-tint' }, stack),
            h('h2', { class: 'inv-title' }, title)),
        h('div', { class: 'inv-body' },
            chips,
            isFriend ? null : h('p', { class: 'hint' }, 'Deine Check-ins und Beweisfotos sind für alle Mitglieder sichtbar.'),
            form,
            register ? h('p', { class: 'fine' },
                'Mit dem Beitritt akzeptierst du die ',
                h('a', { href: '/cohabit/rechtliches#nutzungsbedingungen', target: '_blank', rel: 'noopener' }, 'Nutzungsbedingungen'),
                ' und die ',
                h('a', { href: '/cohabit/rechtliches#datenschutz', target: '_blank', rel: 'noopener' }, 'Datenschutzerklärung'),
                '.') : null,
            msg,
            h('div', { class: 'btn-row' }, decline, accept)));
}

/** Einladung in der App annehmen oder ablehnen (Heute, Freunde & Einladungen). */
export function invitationDialog(invitation, { onAccepted, onDeclined } = {}) {
    const ref = {};
    const card = invitationCard({
        from: invitation.from,
        cohabit: invitation.cohabit,
        onAccept: async () => {
            const detail = await post(`/invitations/${enc(invitation.id)}/accept`, {});
            ref.current.close();
            toast(`Willkommen bei „${invitation.cohabit.ref.name}“.`);
            if (onAccepted) onAccepted(detail);
        },
        onDecline: async () => {
            await post(`/invitations/${enc(invitation.id)}/decline`, {});
            ref.current.close();
            toast('Einladung abgelehnt.');
            if (onDeclined) onDeclined();
        },
    });
    ref.current = openDialog(card, { kind: 'modal', className: 'invite', label: 'Einladung' });
    return ref.current;
}
