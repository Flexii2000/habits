#!/usr/bin/env bash
set -euo pipefail

# Stellt eine bestehende Challenge auf Laufpunkte um (Wertung RUN_POINTS mit den
# Vorgaben: Basis 10 P ab 20 Min., 1 P je km, 1 P je 6 Min., Pace unter 8:00 min/km).
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/challenge-to-run-points.sh <cohabit-id>'
#
# Ueber die App geht das nicht: die Wertung einer laufenden Runde ist fest. Der
# Dienst haelt alles im Speicher - deshalb kurz anhalten, genau diese eine
# Challenge in cohabits.json aendern (Sicherung daneben), wieder starten.
# Bricht ab, wenn die Challenge schon Eintraege ohne Dauer und Distanz hat: die
# brachten danach 0 Punkte. Nach update-habits.sh ausfuehren - der alte Stand
# kennt RUN_POINTS nicht.

ID="${1:-}"
DATA="/opt/habits/data/cohabit"
PORT=48190

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }
[[ "$ID" =~ ^c-[A-Za-z0-9-]{1,80}$ ]] || { echo "Aufruf: $0 <cohabit-id>  (c-…)" >&2; exit 1; }

echo "[1/4] Pruefen ..."
sudo -u habits python3 - "$DATA" "$ID" check <<'EOF'
import json, os, sys
data, cid, _ = sys.argv[1:]
cohabits = json.load(open(f"{data}/cohabits.json", encoding="utf-8"))
c = next((x for x in cohabits["cohabits"] if x["id"] == cid), None)
if c is None or c.get("type") != "CHALLENGE":
    sys.exit(f"    FEHLER: keine Challenge mit der ID {cid}.")
path = f"{data}/checkins/{cid}.json"
entries = json.load(open(path, encoding="utf-8"))["checkins"] if os.path.exists(path) else []
plain = [e for e in entries if e.get("kind") == "DONE" and not (e.get("durationMinutes") and e.get("distanceKm"))]
if plain:
    sys.exit(f"    FEHLER: {len(plain)} Eintraege ohne Dauer und Distanz - die braechten 0 Punkte. Nichts geaendert.")
print(f"    „{c['name']}“: {c['challenge']['scoring']} -> RUN_POINTS, {len(entries)} Eintraege")
EOF

echo "[2/4] Dienst anhalten ..."
sudo systemctl stop habits

echo "[3/4] Umstellen ..."
BACKUP="$DATA/cohabits.json.bak-$(date +%Y%m%d-%H%M%S)"
sudo -u habits cp -p "$DATA/cohabits.json" "$BACKUP"
echo "    Sicherung: $BACKUP"
if ! sudo -u habits python3 - "$DATA" "$ID" <<'EOF'
import json, os, sys
data, cid = sys.argv[1:]
path = f"{data}/cohabits.json"
cohabits = json.load(open(path, encoding="utf-8"))
c = next(x for x in cohabits["cohabits"] if x["id"] == cid)
c["challenge"]["scoring"] = "RUN_POINTS"
c["challenge"]["target"] = None
c["challenge"]["run"] = {"basePoints": 10, "pointsPerKm": 1, "minutesPerPoint": 6,
                         "baseMinMinutes": 20, "paceLimitSeconds": 480}
c["tracking"] = {"mode": "CHECK"}
c["health"] = None
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(cohabits, f, ensure_ascii=False, indent=2)
os.replace(tmp, path)
EOF
then
    echo "    FEHLER beim Umstellen - Sicherung zurueck." >&2
    sudo -u habits cp -p "$BACKUP" "$DATA/cohabits.json"
fi

echo "[4/4] Dienst starten ..."
sudo systemctl start habits
for i in $(seq 1 45); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/cohabit/api/me" || true)"
    if [[ "$code" != "000" ]]; then
        echo "    OK (HTTP $code) - die Challenge wertet jetzt Laufpunkte."
        exit 0
    fi
    sleep 1
done
echo "    FEHLER: App antwortet nicht. Logs: journalctl -u habits -n 50" >&2
echo "    Zurueck zum alten Stand: sudo -u habits cp -p $BACKUP $DATA/cohabits.json && sudo systemctl restart habits" >&2
exit 1
