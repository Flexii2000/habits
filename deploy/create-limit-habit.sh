#!/usr/bin/env bash
set -euo pipefail

# Legt fuer Felix eine Abstinenz mit Tageslimit an, die aus dem Logbook von Healthy
# zaehlt - etwa „hoechstens 4 Tassen am Tag“ (12.10.).
#
#     ssh -t HeimServerRemote '~/services/habits/deploy/create-limit-habit.sh'
#
# Die Zahl je Tag bleibt im Logbook: coHabit liest sie dort und schreibt beim Zaehlen
# dorthin. Deshalb braucht es eine Verhaltensweise MIT Menge (Einheit). Das Skript
# listet sie, fragt nach Limit und Namen und geht ueber die API des laufenden Dienstes.
# Namen stehen nirgends im Repo.
#
# Gibt es fuer die gewaehlte Verhaltensweise schon ein Limit, legt es kein zweites an.
# Spaeter aendern (nur das Limit): in der App ueber „Bearbeiten“.

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

if ! api "$API/logbook/behaviors" > "$WORK/behaviors.json"; then
    echo >&2
    echo "FEHLER: Das Logbook antwortet nicht (oder der Dienst kennt den Endpunkt noch nicht)." >&2
    echo "Erst den Weight Tracker und dann habits auf den Stand vom 12.10. bringen." >&2
    exit 1
fi
python3 -c '
import json, sys
for b in json.load(open(sys.argv[1], encoding="utf-8")):
    print("\t".join([b["id"], b["name"].replace("\t", " "), (b.get("unitLabel") or "").replace("\t", " ")]))
' "$WORK/behaviors.json" > "$WORK/choices.tsv"

guide() {
    echo
    echo "Ein Tageslimit braucht eine Verhaltensweise mit Menge. So geht es:"
    echo "  1. In Healthy im Logbook unter „Verhalten“ ein neues Verhalten mit Einheit anlegen"
    echo "     (z. B. „Tassen“)."
    echo "  2. Gab es dasselbe schon als Ja/Nein-Verhalten: das alte archivieren - seine Tage bleiben."
    echo "  3. Dieses Skript noch einmal starten."
}

echo "Verhaltensweisen mit Menge im Logbook:"
i=0
while IFS=$'\t' read -r _ name unit; do
    i=$((i + 1))
    printf '  %2d) %s  (%s)\n' "$i" "$name" "$unit"
done < "$WORK/choices.tsv"
echo "   0) nicht dabei"

read -rp "Welche? Nummer: " pick
[[ "$pick" =~ ^[0-9]+$ ]] && (( pick <= i )) || { echo "Keine gueltige Nummer." >&2; exit 1; }
if (( pick == 0 )); then
    guide
    exit 0
fi
IFS=$'\t' read -r BEHAVIOR BEHAVIOR_NAME UNIT < <(sed -n "${pick}p" "$WORK/choices.tsv")

# Schon da? Dann nicht doppelt - ein zweiter Lauf des Rollouts legt nichts Neues an.
api "$API/cohabits" > "$WORK/list.json"
python3 -c '
import json, sys
for s in json.load(open(sys.argv[1], encoding="utf-8")):
    if s.get("limit") is not None:
        print(s["ref"]["id"])
' "$WORK/list.json" > "$WORK/limits.txt"
while read -r existing; do
    api "$API/cohabits/$existing" > "$WORK/existing.json"
    found="$(python3 -c '
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
limit = ((d.get("config") or {}).get("abstinence") or {}).get("limit") or {}
if limit.get("behaviorId") == sys.argv[2]:
    print(d["summary"]["ref"]["name"] + " (" + d["summary"]["typeLine"] + ")")
' "$WORK/existing.json" "$BEHAVIOR")"
    if [[ -n "$found" ]]; then
        echo "Gibt es schon: $found - nichts angelegt."
        exit 0
    fi
done < "$WORK/limits.txt"

read -rp "Hoechstens wie viele ${UNIT} am Tag? [4] " per_day
per_day="${per_day:-4}"
[[ "$per_day" =~ ^[0-9]+$ ]] && (( per_day <= 1000 )) || { echo "0 bis 1000, bitte." >&2; exit 1; }

read -rp "Name des Co-Habits? [$BEHAVIOR_NAME] " name
name="${name:-$BEHAVIOR_NAME}"

api "$API/me/type-colors" > "$WORK/colors.json"
python3 -c '
import json, sys
colors = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps({
    "type": "ABSTINENCE",
    "name": sys.argv[2],
    "color": colors.get("ABSTINENCE") or "mint",
    "timezone": "Europe/Berlin",
    "backfillHours": 336,
    "abstinence": {"groupMode": False, "limit": {"behaviorId": sys.argv[3], "perDay": int(sys.argv[4])}},
}, ensure_ascii=False))
' "$WORK/colors.json" "$name" "$BEHAVIOR" "$per_day" > "$WORK/input.json"

if ! api -X POST -H 'Content-Type: application/json' --data @"$WORK/input.json" "$API/cohabits" > "$WORK/created.json"; then
    echo >&2
    echo "FEHLER: Der Dienst hat abgelehnt:" >&2
    python3 -c 'import json, sys; print("  " + (json.load(open(sys.argv[1])).get("message") or "?"))' "$WORK/created.json" >&2 || cat "$WORK/created.json" >&2
    exit 1
fi

python3 -c '
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
s = d["summary"]
print("    " + s["ref"]["name"] + " · " + s["typeLine"])
print("    " + s["headline"]["value"] + " " + s["headline"]["unit"] + " · " + ((s.get("limit") or {}).get("text") or s.get("unavailableText") or ""))
' "$WORK/created.json"
echo "Fertig: „${name}“ zaehlt ab heute aus dem Logbook."
