#!/usr/bin/env bash
set -euo pipefail

# Richtet coHabit ein (fherrmann.com/cohabit/): Umgebung, Push-Schluessel, das
# Verzeichnis der Android-App und den Pfad in nginx. Der Dienst selbst ist habits -
# erst den neuen Stand deployen, dann dieses Skript:
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'
#     ssh -t HeimServerRemote '~/services/habits/deploy/setup-cohabit.sh'
#
# Beide brauchen das -t (sudo fragt nach dem Passwort). NICHT mit sudo starten.
#
# Optionen:
#     --fcm-key <datei>   ein anderes Firebase-Dienstkonto nach /etc/fcm-cohabit.json legen
#                         (Vorgabe: Kopie von /etc/fcm-healthy.json - dasselbe Projekt
#                         fherrmann-apps, in dem die Android-App com.fherrmann.cohabit steht)
#
# Idempotent: jeder Schritt prueft erst, ob er schon erledigt ist. /etc/habits.env
# wird NIE neu geschrieben - nur einzelne Zeilen gesetzt (set_env), sonst gingen
# Token verloren, die hier niemand kennt.

BUILD_DIR="$HOME/services/habits"
APP_DIR="/opt/habits"
SERVICE_USER="habits"
PORT=48190
HABITS_ENV="/etc/habits.env"
FOOD_ENV="/etc/food.env"
NGINX_CONF="/etc/nginx/sites-available/fherrmann.com"
ANDROID_DIR="/opt/cohabit-android"
APNS_SOURCE="/etc/apns-cockpit.p8"
APNS_TARGET="/etc/apns-cohabit.p8"
FCM_SOURCE="/etc/fcm-healthy.json"
FCM_TARGET="/etc/fcm-cohabit.json"
TEAM_ID="ZWFV263P59"
TOPIC="com.fherrmann.cohabit"
SANDBOX="https://api.sandbox.push.apple.com"
PUBLIC_URL="https://fherrmann.com/cohabit"

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

FCM_KEY=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --fcm-key) FCM_KEY="${2:-}"; [[ -n "$FCM_KEY" ]] || fail "--fcm-key braucht eine Datei"; shift 2 ;;
        *) fail "Unbekannte Option $1" ;;
    esac
done

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
[[ -d "$BUILD_DIR/.git" ]] || fail "$BUILD_DIR fehlt."
sudo -v
sudo test -f "$APP_DIR/app.jar" || fail "$APP_DIR/app.jar fehlt - ist habits eingerichtet (setup-habits.sh)?"
sudo test -f "$HABITS_ENV" || fail "$HABITS_ENV fehlt - ist habits eingerichtet (setup-habits.sh)?"

# Eine Zeile KEY=... setzen oder anhaengen - ueber tee, damit Besitzer und Rechte
# (root:habits 640) bleiben. Wie in setup-health-users.sh.
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

# Der taegliche certbot-Lauf stoppt nginx fuer ein paar Sekunden - warten statt abbrechen.
nginx_apply() {
    sudo nginx -t
    for attempt in $(seq 1 30); do
        if systemctl is-active --quiet nginx; then
            sudo systemctl reload nginx
            return 0
        fi
        [[ $attempt -eq 1 ]] && echo "    nginx laeuft gerade nicht - vermutlich ein certbot-Lauf. Warte ..."
        sleep 2
    done
    fail "nginx ist seit 60 s nicht aktiv. Status: systemctl status nginx"
}

step "1/7 Healthy-Personen und Adresse in $HABITS_ENV"
# Wortgleich aus food.env: ein Token, alle Dienste. Nie abtippen.
if sudo test -f "$FOOD_ENV"; then
    TOKENS="$(get_env "$FOOD_ENV" HEALTH_TOKENS)"
    OWNER="$(get_env "$FOOD_ENV" HEALTH_OWNER)"
else
    TOKENS=""
    OWNER=""
fi
OWNER="${OWNER:-felix}"
set_env "$HABITS_ENV" HEALTH_OWNER "$OWNER"
if [[ -n "$TOKENS" ]]; then
    set_env "$HABITS_ENV" HEALTH_TOKENS "$TOKENS"
    echo "    HEALTH_TOKENS aus $FOOD_ENV: $(tr ',' '\n' <<<"$TOKENS" | cut -d: -f1 | paste -sd' ' -)"
else
    echo "    HINWEIS: keine HEALTH_TOKENS in $FOOD_ENV - nur $OWNER kommt ohne Einladung hinein."
fi
set_env "$HABITS_ENV" COHABIT_PUBLIC_URL "$PUBLIC_URL"
echo "    HEALTH_OWNER=$OWNER, COHABIT_PUBLIC_URL=$PUBLIC_URL"

step "2/7 Push an iOS (APNs, Topic $TOPIC)"
if sudo test -f "$APNS_SOURCE"; then
    sudo test -f "$APNS_TARGET" || sudo install -o root -g "$SERVICE_USER" -m 640 "$APNS_SOURCE" "$APNS_TARGET"
    KEY_ID="$(get_env "$FOOD_ENV" APNS_KEY_ID)"
    FOOD_TEAM="$(get_env "$FOOD_ENV" APNS_TEAM_ID)"
    set_env "$HABITS_ENV" APNS_KEY_FILE "$APNS_TARGET"
    [[ -n "$KEY_ID" ]] && set_env "$HABITS_ENV" APNS_KEY_ID "$KEY_ID"
    set_env "$HABITS_ENV" APNS_TEAM_ID "${FOOD_TEAM:-$TEAM_ID}"
    set_env "$HABITS_ENV" APNS_TOPIC "$TOPIC"
    # Sandbox: Felix installiert aus Xcode. Ein TestFlight-Build braucht https://api.push.apple.com.
    set_env "$HABITS_ENV" APNS_HOST "$SANDBOX"
    [[ -n "$KEY_ID" ]] || echo "    HINWEIS: kein APNS_KEY_ID in $FOOD_ENV - iOS-Push bleibt aus."
    echo "    $APNS_TARGET (root:$SERVICE_USER 640), Host Sandbox."
else
    echo "    HINWEIS: $APNS_SOURCE fehlt - iOS bekommt vorerst keinen Push."
fi

step "3/7 Push an Android (Firebase)"
if [[ -n "$FCM_KEY" ]]; then
    [[ -r "$FCM_KEY" ]] || fail "$FCM_KEY nicht lesbar."
    python3 - "$FCM_KEY" <<'CHECK' || fail "$FCM_KEY ist keine Dienstkonto-Datei von Firebase."
import json, sys
data = json.load(open(sys.argv[1]))
assert data.get("type") == "service_account"
assert data.get("project_id") and data.get("private_key") and data.get("client_email")
CHECK
    sudo install -o root -g "$SERVICE_USER" -m 640 "$FCM_KEY" "$FCM_TARGET"
    echo "    $FCM_TARGET aus $FCM_KEY installiert. Die Kopie $FCM_KEY kann weg."
elif sudo test -f "$FCM_TARGET"; then
    echo "    $FCM_TARGET ist schon da."
elif sudo test -f "$FCM_SOURCE"; then
    sudo install -o root -g "$SERVICE_USER" -m 640 "$FCM_SOURCE" "$FCM_TARGET"
    echo "    $FCM_TARGET als Kopie von $FCM_SOURCE (Projekt fherrmann-apps)."
fi
if sudo test -f "$FCM_TARGET"; then
    set_env "$HABITS_ENV" FCM_SERVICE_ACCOUNT_FILE "$FCM_TARGET"
else
    echo "    HINWEIS: kein Dienstkonto - Android bekommt vorerst keinen Push."
fi

step "4/7 Verzeichnis der Android-App $ANDROID_DIR"
# Gehoert flexii, damit tools/publish.sh ohne sudo hochlaedt; der Dienst liest nur.
sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 755 "$ANDROID_DIR"
set_env "$HABITS_ENV" COHABIT_ANDROID_DIR "$ANDROID_DIR"
if [[ -f "$ANDROID_DIR/cohabit.apk" && -f "$ANDROID_DIR/latest.json" ]]; then
    echo "    veroeffentlicht: $(cat "$ANDROID_DIR/latest.json")"
else
    echo "    noch keine App (tools/publish.sh im Repo cohabit-android)."
fi
sudo chown root:"$SERVICE_USER" "$HABITS_ENV"
sudo chmod 640 "$HABITS_ENV"

step "5/7 systemd-Unit"
if ! sudo cmp -s "$BUILD_DIR/deploy/habits.service" /etc/systemd/system/habits.service; then
    sudo cp "$BUILD_DIR/deploy/habits.service" /etc/systemd/system/habits.service
    sudo systemctl daemon-reload
    echo "    habits.service aufgefrischt."
else
    echo "    habits.service ist aktuell."
fi

step "6/7 nginx: /cohabit/ unter fherrmann.com"
if grep -q "location /cohabit/" "$NGINX_CONF"; then
    echo "    Schon eingebunden."
else
    sudo cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)"
    # Direkt vor den /grades/-Block: gleiche Ebene, gleiche Bauart wie /todo/ - OHNE Gate.
    grep -q "location /grades/" "$NGINX_CONF" || fail "Kein /grades/-Block in $NGINX_CONF - wo soll /cohabit/ hin?"
    python3 - "$NGINX_CONF" "$BUILD_DIR/deploy/nginx-cohabit.conf" <<'PY'
import sys, pathlib, subprocess
conf, snippet = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).read_text()
text = conf.read_text()
marker = "      location /grades/ {"
assert text.count(marker) == 1, "der /grades/-Block steht nicht genau einmal da"
neu = text.replace(marker, snippet + marker)
subprocess.run(["sudo", "tee", str(conf)], input=neu.encode(), check=True, stdout=subprocess.DEVNULL)
PY
    nginx_apply
    echo "    Eingebunden und nginx neu geladen."
fi

step "7/7 Neustart und Pruefung"
sudo systemctl restart habits
for i in $(seq 1 45); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/cohabit/api/me" || true)"
    [[ "$code" != "000" ]] && break
    sleep 1
done
[[ "${code:-000}" != "000" ]] || fail "habits antwortet nicht. Log: journalctl -u habits -n 50"
[[ "$code" == "401" ]] || fail "/cohabit/api/me ohne Anmeldung: HTTP $code statt 401 - laeuft der neue Stand?"
PRIVATE="$(get_env "$HABITS_ENV" FH_PRIVATE_TOKEN)"
me="$(curl -s --max-time 10 --cookie "fh_private=$PRIVATE" "http://127.0.0.1:$PORT/cohabit/api/me" || true)"
grep -q '"isOwner":true' <<<"$me" || fail "Mit fh_private kam kein MeView: ${me:0:200}"
echo "    Felix per fh_private: $(grep -o '"cohabits":[0-9]*' <<<"$me") (migriert)"
if [[ -n "$TOKENS" ]]; then
    first="${TOKENS%%,*}"
    other="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer ${first#*:}" \
        "http://127.0.0.1:$PORT/cohabit/api/me" || true)"
    [[ "$other" == "200" ]] || fail "${first%%:*} per Healthy-Token: HTTP $other statt 200."
    echo "    ${first%%:*} per Healthy-Token: 200."
fi
focus="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cookie "fh_private=$PRIVATE" \
    "http://127.0.0.1:$PORT/habits/api/focus/sessions?from=2026-01-01&to=2030-01-01" || true)"
[[ "$focus" == "200" ]] || fail "Wald-Sessions: HTTP $focus statt 200."
echo "    Wald-Sessions (/habits/api/focus/sessions): 200."
gone="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/habits/api/habits" || true)"
echo "    Alte Habit-API: HTTP $gone (erwartet 410)."
if sudo test -f "$APP_DIR/data/cohabit/migrated.marker"; then
    echo "    Migration: $(sudo cat "$APP_DIR/data/cohabit/migrated.marker")"
else
    echo "    HINWEIS: kein migrated.marker - Log pruefen: journalctl -u habits -n 50"
fi
web="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve fherrmann.com:443:127.0.0.1 "$PUBLIC_URL/" || true)"
[[ "$web" == "000" ]] && web="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$PUBLIC_URL/" || true)"
[[ "$web" == "200" ]] || fail "$PUBLIC_URL/ ueber nginx: HTTP $web statt 200."
api="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve fherrmann.com:443:127.0.0.1 "$PUBLIC_URL/api/me" || true)"
[[ "$api" == "000" ]] && api="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$PUBLIC_URL/api/me" || true)"
[[ "$api" == "401" ]] || fail "$PUBLIC_URL/api/me ueber nginx: HTTP $api statt 401 (Gate davor?)."
echo "    Ueber nginx: Weboberflaeche 200, API ohne Anmeldung 401."

echo
echo "Fertig. $PUBLIC_URL/"
echo "Felix ist im Browser mit fh_private drin, Torben mit seinem health_token."
echo "Android-App fuer Torben (nach tools/publish.sh): $PUBLIC_URL/api/app/android/apk"
