#!/usr/bin/env bash
set -euo pipefail

# Setzt den Schluessel fuer die GIF-Suche (KLIPY) in /etc/habits.env und startet
# habits neu. Den Schluessel gibt es im Partner-Panel von KLIPY
# (https://partner.klipy.com/api-keys); er gehoert in kein Repo.
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/set-klipy-key.sh'
#
# Fragt nach dem Schluessel (Eingabe unsichtbar). Leer lassen = nichts aendern.
# NICHT mit sudo starten. /etc/habits.env wird nie neu geschrieben, nur die eine Zeile.

HABITS_ENV="/etc/habits.env"
PORT=48190

fail() { echo "FEHLER: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
sudo -v
sudo test -f "$HABITS_ENV" || fail "$HABITS_ENV fehlt - ist habits eingerichtet (setup-habits.sh)?"

# Eine Zeile KEY=... setzen oder anhaengen - ueber tee, damit Besitzer und Rechte
# (root:habits 640) bleiben. Wie in setup-cohabit.sh.
set_env() {
    local file="$1" key="$2" value="$3" current updated
    current="$(sudo cat "$file")"
    if grep -qE "^${key}=" <<<"$current"; then
        updated="$(awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' <<<"$current")"
    else
        updated="$(printf '%s\n%s=%s' "$current" "$key" "$value")"
    fi
    printf '%s\n' "$updated" | sudo tee "$file" >/dev/null
}

get_env() {
    sudo grep -E "^$2=" "$1" 2>/dev/null | tail -1 | cut -d= -f2- || true
}

if [[ -n "$(get_env "$HABITS_ENV" KLIPY_API_KEY)" ]]; then
    echo "KLIPY_API_KEY ist schon gesetzt. Neuer Schluessel ersetzt ihn."
fi
read -rsp "KLIPY-Schluessel: " key
echo
key="$(tr -d '[:space:]' <<<"$key")"
[[ -z "$key" ]] && { echo "Nichts geaendert."; exit 0; }
[[ "$key" =~ ^[A-Za-z0-9_-]+$ ]] || fail "Der Schluessel enthaelt unerwartete Zeichen."

set_env "$HABITS_ENV" KLIPY_API_KEY "$key"
echo "KLIPY_API_KEY gesetzt. Starte habits neu ..."
sudo systemctl restart habits

PRIVATE="$(get_env "$HABITS_ENV" FH_PRIVATE_TOKEN)"
for _ in $(seq 1 60); do
    config="$(curl -s --max-time 5 -H "Authorization: Bearer $PRIVATE" \
        "http://127.0.0.1:$PORT/cohabit/api/gifs/config" || true)"
    [[ -n "$config" ]] && break
    sleep 1
done
[[ "$config" == *'"enabled":true'* ]] || fail "/cohabit/api/gifs/config meldet nicht enabled: ${config:-keine Antwort}"
echo "GIF-Suche ist an."
