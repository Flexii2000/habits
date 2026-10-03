#!/usr/bin/env bash
set -euo pipefail

# Gegenstueck zu challenge-to-run-points.sh: stellt eine Challenge auf die Wertung
# „meiste Eintraege“ (MOST_ENTRIES) - jeder Lauf zaehlt eins, Dauer und Distanz
# bleiben an den Eintraegen stehen, zaehlen aber nicht mehr.
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/challenge-to-most-entries.sh <cohabit-id>'
#
# Ueber die App geht das nicht: die Wertung einer laufenden Runde ist fest. Der
# Dienst haelt alles im Speicher - deshalb kurz anhalten, genau diese eine
# Challenge in cohabits.json aendern (Sicherung daneben), wieder starten.

ID="${1:-}"
DATA="/opt/habits/data/cohabit"
PORT=48190

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }
[[ "$ID" =~ ^c-[A-Za-z0-9-]{1,80}$ ]] || { echo "Aufruf: $0 <cohabit-id>  (c-…)" >&2; exit 1; }

echo "[1/4] Pruefen ..."
sudo -u habits python3 - "$DATA" "$ID" <<'EOF'
import json, os, sys
data, cid = sys.argv[1:]
cohabits = json.load(open(f"{data}/cohabits.json", encoding="utf-8"))
c = next((x for x in cohabits["cohabits"] if x["id"] == cid), None)
if c is None or c.get("type") != "CHALLENGE":
    sys.exit(f"    FEHLER: keine Challenge mit der ID {cid}.")
path = f"{data}/checkins/{cid}.json"
entries = json.load(open(path, encoding="utf-8"))["checkins"] if os.path.exists(path) else []
print(f"    „{c['name']}“: {c['challenge']['scoring']} -> MOST_ENTRIES, {len(entries)} Eintraege")
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
c["challenge"]["scoring"] = "MOST_ENTRIES"
c["challenge"]["target"] = None
c["challenge"]["run"] = None
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
        echo "    OK (HTTP $code) - die Challenge zaehlt jetzt die Eintraege."
        exit 0
    fi
    sleep 1
done
echo "    FEHLER: App antwortet nicht. Logs: journalctl -u habits -n 50" >&2
echo "    Zurueck zum alten Stand: sudo -u habits cp -p $BACKUP $DATA/cohabits.json && sudo systemctl restart habits" >&2
exit 1
