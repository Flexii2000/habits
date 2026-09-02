#!/usr/bin/env bash
set -euo pipefail

# Deployt eine neue Version von Habits (fherrmann.com/habits).
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'
#
# Baut auf dem Server aus ~/services/habits und installiert nach /opt/habits.
# Der Build laeuft als flexii, nur die Installation braucht Root - deshalb
# NICHT das ganze Skript mit sudo starten (das -t braucht sudo fuers Passwort).
#
# /opt/habits/data/ wird NICHT angefasst: habits.json ist der Live-Bestand.

BUILD_DIR="$HOME/services/habits"
APP_DIR="/opt/habits"
TARGET="$APP_DIR/app.jar"
JAR="$BUILD_DIR/build/libs/Habits-0.0.1-SNAPSHOT.jar"
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
PORT=48190

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

echo "[1/5] git pull ..."
git -C "$BUILD_DIR" pull --ff-only

echo "[2/5] Jar bauen ..."
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
[[ -f "$JAR" ]] || { echo "    FEHLER: $JAR fehlt." >&2; exit 1; }

echo "[3/5] Laufendes Jar sichern ..."
BACKUP="$TARGET.bak-$(date +%Y%m%d-%H%M%S)"
sudo cp -p "$TARGET" "$BACKUP"
echo "    Backup: $BACKUP"

echo "[4/5] Installieren und neu starten ..."
sudo install -o habits -g habits -m 644 "$JAR" "$TARGET"
sudo systemctl restart habits

echo "[5/5] Health-Check ..."
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/habits/api/habits" || true)"
    # Ohne Cookie ist 403 die richtige Antwort; jeder Status heisst "App bedient".
    if [[ "$code" != "000" ]]; then
        echo "    OK (HTTP $code)"
        echo "habits erfolgreich aktualisiert."
        exit 0
    fi
    sleep 1
done

echo "    FEHLER: App antwortet nicht. Rollback auf $BACKUP ..." >&2
sudo install -o habits -g habits -m 644 "$BACKUP" "$TARGET"
sudo systemctl restart habits
echo "    Zurueckgerollt. Logs: journalctl -u habits -n 50" >&2
exit 1
