#!/usr/bin/env bash
set -euo pipefail

# Erstinstallation von Habits (fherrmann.com/habits) auf dem Heimserver.
#
# Vom Laptop aus, das -t ist noetig (sudo fragt nach dem Passwort):
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/setup-habits.sh'
#
# Beim allerersten Mal liegt das Repo noch nicht auf dem Server - dann:
#
#     ssh HeimServerRemote 'git clone git@github.com:Flexii2000/habits.git ~/services/habits'
#
# Idempotent: jeder Schritt prueft erst, ob er schon erledigt ist.
#
# Was es NICHT braucht: DNS und Zertifikat. Der Dienst liegt als Pfad unter
# fherrmann.com, das Zertifikat dafuer gibt es schon - deshalb ein Pfad und
# keine Subdomain.

BUILD_DIR="$HOME/services/habits"
APP_DIR="/opt/habits"
SERVICE_USER="habits"
PORT=48190
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
PRIVATE_MODE_CONF="/etc/nginx/conf.d/private-mode.conf"
WEIGHT_ENV="/etc/health-viz.env"
NGINX_CONF="/etc/nginx/sites-available/fherrmann.com"

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

# Konfiguration pruefen und nginx neu einlesen - mit Warteschleife, weil der
# taegliche certbot-Lauf nginx fuer ein paar Sekunden stoppt (siehe
# setup-food.sh, dort ist das ausfuehrlich begruendet).
nginx_apply() {
    sudo nginx -t
    for attempt in $(seq 1 30); do
        if systemctl is-active --quiet nginx; then
            sudo systemctl reload nginx
            return 0
        fi
        [[ $attempt -eq 1 ]] && echo "    nginx laeuft gerade nicht - vermutlich certbot. Warte ..."
        sleep 2
    done
    fail "nginx ist seit 60 s nicht aktiv. Status: systemctl status nginx"
}

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
[[ -d "$BUILD_DIR/.git" ]] || fail "$BUILD_DIR fehlt - erst klonen (siehe Kopf dieses Skripts)."

step "1/7 Repo aktualisieren"
git -C "$BUILD_DIR" pull --ff-only

step "2/7 Jar bauen"
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
JAR="$BUILD_DIR/build/libs/Habits-0.0.1-SNAPSHOT.jar"
[[ -f "$JAR" ]] || fail "$JAR wurde nicht gebaut."
echo "    $(du -h "$JAR" | cut -f1)"

step "3/7 Service-User und Verzeichnisse"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    sudo useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "    User $SERVICE_USER angelegt."
else
    echo "    User $SERVICE_USER existiert bereits."
fi
sudo mkdir -p "$APP_DIR/data"
sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"

step "4/7 Token uebernehmen -> /etc/habits.env"
# Beide Token stehen anderswo schon: der Privat-Token in der nginx-Map, der
# des Weight Trackers in dessen Umgebung. Lesen statt abtippen.
sudo test -r "$PRIVATE_MODE_CONF" || fail "$PRIVATE_MODE_CONF nicht lesbar - laeuft der private Modus?"
TOKEN="$(sudo grep -oE '"[0-9a-fA-F]{24,}"' "$PRIVATE_MODE_CONF" | head -1 | tr -d '"')"
[[ -n "$TOKEN" ]] || fail "Kein Token in $PRIVATE_MODE_CONF gefunden."
WEIGHT_TOKEN="$(sudo grep -E '^WEIGHT_APP_TOKEN=' "$WEIGHT_ENV" | head -1 | cut -d= -f2- | tr -d '"'"'"'')"
[[ -n "$WEIGHT_TOKEN" ]] || fail "Kein WEIGHT_APP_TOKEN in $WEIGHT_ENV - ohne ihn gibt es keine Schritte."
{
    printf 'FH_PRIVATE_TOKEN=%s\n' "$TOKEN"
    printf 'WEIGHT_APP_TOKEN=%s\n' "$WEIGHT_TOKEN"
} | sudo tee /etc/habits.env >/dev/null
sudo chown root:"$SERVICE_USER" /etc/habits.env
sudo chmod 640 /etc/habits.env
echo "    /etc/habits.env geschrieben (Privat-Token ${TOKEN:0:6}…, Weight-Token ${WEIGHT_TOKEN:0:6}…)."

step "5/7 Jar und systemd-Unit"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "$JAR" "$APP_DIR/app.jar"
sudo cp "$BUILD_DIR/deploy/habits.service" /etc/systemd/system/habits.service
sudo systemctl daemon-reload
sudo systemctl enable habits
# restart statt "enable --now": beim zweiten Lauf bliebe das neue Jar sonst
# ungenutzt, und das Skript meldete trotzdem Erfolg.
sudo systemctl restart habits
sleep 3
sudo systemctl is-active --quiet habits || {
    sudo journalctl -u habits -n 30 --no-pager >&2
    fail "habits.service laeuft nicht - Log siehe oben."
}
echo "    habits.service laeuft."

step "6/7 nginx: /habits/ unter fherrmann.com"
if grep -q "location /habits/" "$NGINX_CONF"; then
    echo "    Schon eingebunden."
else
    sudo cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)"
    # Direkt vor den /grades/-Block: gleiche Ebene, gleiche Bauart.
    grep -q "location /grades/" "$NGINX_CONF" || fail "Kein /grades/-Block in $NGINX_CONF - wo soll /habits/ hin?"
    python3 - "$NGINX_CONF" "$BUILD_DIR/deploy/nginx-habits.conf" <<'PY'
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

step "7/7 Health-Check"
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/habits/api/habits" || true)"
    [[ "$code" != "000" ]] && break
    sleep 1
done
[[ "${code:-000}" != "000" ]] || fail "App antwortet nicht. Log: journalctl -u habits -n 50"
[[ "$code" == "403" ]] || echo "    HINWEIS: ohne Cookie kam HTTP $code statt 403."
# Mit Cookie muss es 200 sein - das prueft Token-Datei und App-Pruefung zugleich.
authed="$(curl -s --max-time 10 --cookie "fh_private=$TOKEN" "http://127.0.0.1:$PORT/habits/api/habits" || true)"
echo "$authed" | grep -q '"streak"' || fail "Mit Cookie kam keine Habit-Liste - Antwort: ${authed:0:200}"
echo "    Habit-Liste mit Cookie: $(echo "$authed" | grep -o '"name":"[^"]*"' | tr '\n' ' ')"
if echo "$authed" | grep -q '"unavailable":"'; then
    echo "    HINWEIS: mindestens eine Quelle antwortet nicht:"
    echo "$authed" | grep -o '"unavailable":"[^"]*"' | sort -u | sed 's/^/      /'
fi

echo
echo "Fertig. https://fherrmann.com/habits/api/habits antwortet, sobald der Client den fh_private-Cookie hat."
echo "Spaetere Updates: ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'"
