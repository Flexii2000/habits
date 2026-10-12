#!/usr/bin/env bash
set -euo pipefail

# Stellt einen eigenen Streak von Felix auf „N-mal pro Woche“ um - etwa von
# taeglich auf 4x pro Woche (12.10.).
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/set-rhythm.sh'
#
# Geht ueber die API des laufenden Dienstes, nicht ueber die Datei: so rechnet der
# Dienst Bestserie und Meilensteine unter der neuen Regel still neu (seit der
# Fassung vom 12.10. - vorher blieb die alte Bestserie in Tagen stehen und wurde
# als Wochen gezeigt), und es braucht keinen Neustart.
#
# Fragt, welches Co-Habit und wie oft pro Woche - Namen stehen nirgends im Repo.
# In der App geht dasselbe ueber „Bearbeiten“.

# Zum Ausprobieren gegen einen lokalen Dienst: HABITS_PORT und HABITS_ENV (eine
# lesbare Datei mit FH_PRIVATE_TOKEN=...) setzen - dann ohne sudo.
PORT="${HABITS_PORT:-48190}"
ENV_FILE="${HABITS_ENV:-/etc/habits.env}"
API="http://127.0.0.1:$PORT/cohabit/api"

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Der Token gehoert nicht auf die Kommandozeile (ps zeigt sie jedem) - curl liest
# den Kopf aus einer Datei, die nur hier lesbar ist.
if [[ -r "$ENV_FILE" ]]; then
    TOKEN="$(sed -n 's/^FH_PRIVATE_TOKEN=//p' "$ENV_FILE")"
else
    TOKEN="$(sudo sed -n 's/^FH_PRIVATE_TOKEN=//p' "$ENV_FILE")"
fi
[[ -n "$TOKEN" ]] || { echo "FEHLER: FH_PRIVATE_TOKEN fehlt in $ENV_FILE." >&2; exit 1; }
(umask 077 && printf 'Authorization: Bearer %s\n' "$TOKEN" > "$WORK/auth")
unset TOKEN

api() {
    curl -sS --fail-with-body --max-time 20 -H @"$WORK/auth" -H 'Accept: application/json' "$@"
}

# Nur Streaks, die man von Hand abhakt: automatische zaehlen ihre Quelle und
# haben keinen Rhythmus zum Einstellen.
api "$API/cohabits" > "$WORK/list.json"
python3 -c '
import json, sys
for s in json.load(open(sys.argv[1], encoding="utf-8")):
    ref = s["ref"]
    if ref["type"] != "STREAK" or ref.get("autoSource") or s.get("archived"):
        continue
    name = ref["name"].replace("\t", " ")
    print("\t".join([ref["id"], name, s.get("typeLine") or "", str(s.get("memberCount", 1))]))
' "$WORK/list.json" > "$WORK/choices.tsv"

[[ -s "$WORK/choices.tsv" ]] || { echo "Keine eigenen Streaks gefunden." >&2; exit 1; }

echo "Streaks:"
i=0
while IFS=$'\t' read -r _ name line members; do
    i=$((i + 1))
    extra=""
    [[ "$members" -gt 1 ]] && extra=" · $members Personen"
    printf '  %2d) %s  (%s%s)\n' "$i" "$name" "$line" "$extra"
done < "$WORK/choices.tsv"

read -rp "Welcher? Nummer: " pick
[[ "$pick" =~ ^[0-9]+$ ]] && (( pick >= 1 && pick <= i )) || { echo "Keine gueltige Nummer." >&2; exit 1; }
IFS=$'\t' read -r ID NAME _ MEMBERS < <(sed -n "${pick}p" "$WORK/choices.tsv")

read -rp "Wie oft pro Woche? [4] " times
times="${times:-4}"
[[ "$times" =~ ^[1-7]$ ]] || { echo "1 bis 7, bitte." >&2; exit 1; }

if [[ "$MEMBERS" -gt 1 ]]; then
    echo "Achtung: „${NAME}“ teilen $MEMBERS Personen - der Rhythmus gilt dann fuer alle."
    read -rp "Trotzdem umstellen? [j/N] " ok
    [[ "$ok" == "j" || "$ok" == "J" ]] || { echo "Nichts geaendert."; exit 0; }
fi

# Die ganze Konfiguration zurueckschicken, nur mit neuem Rhythmus: ein PUT ersetzt
# alles, ein halber Rumpf setzte Erinnerung, Nachtragsfrist usw. zurueck.
api "$API/cohabits/$ID" > "$WORK/before.json"
python3 -c '
import json, sys
detail = json.load(open(sys.argv[1], encoding="utf-8"))
config = dict(detail["config"])
streak = dict(config.get("streak") or {})
streak["rhythm"] = {"kind": "TIMES_PER_WEEK", "times": int(sys.argv[2])}
config["streak"] = streak
print(json.dumps(config, ensure_ascii=False))
' "$WORK/before.json" "$times" > "$WORK/input.json"

api -X PUT -H 'Content-Type: application/json' --data @"$WORK/input.json" "$API/cohabits/$ID" > "$WORK/after.json"

python3 -c '
import json, sys
before, after = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:3])
def record(detail):
    r = (detail.get("streak") or {}).get("record")
    return r["short"] if r else "–"
print("    " + before["summary"]["typeLine"] + "  ->  " + after["summary"]["typeLine"])
print("    Rekord: " + record(before) + "  ->  " + record(after))
' "$WORK/before.json" "$WORK/after.json"
echo "Fertig: „${NAME}“ zaehlt jetzt ${times}x pro Woche."
