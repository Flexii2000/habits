#!/usr/bin/env python3
"""Demo-Daten fuer eine lokale coHabit-Instanz (run-backend.sh, mit Demo-Uhr).

    tools/demo-data.py [--base http://127.0.0.1:48790] [--out demo-tokens.json]

Legt fuenf Personen an (felix, torben, lena, max, sara), je ein Co-Habit aller
Typen samt zwei Wochen Geschichte: Eintraege, Beweisfotos, Chat, Reaktionen,
Stupser und eine beendete Challenge-Runde. Die Geschichte entsteht echt - die
Demo-Uhr des Dienstes (/cohabit/api/dev/clock) wird Tag fuer Tag vorgestellt, und
nach jedem Tag laeuft der Scheduler einmal (/cohabit/api/dev/tick).

Nur gegen eine frische Instanz ausfuehren (leeres Datenverzeichnis). Am Ende steht
die Uhr wieder auf jetzt; die Tokens stehen in --out und auf der Konsole.
Nur Python-Standardbibliothek.
"""

import argparse
import datetime as dt
import json
import struct
import sys
import urllib.error
import urllib.request
import uuid
import zlib
from zoneinfo import ZoneInfo

FELIX = "local-private"
TORBEN = "0123456789abcdef0123456789abcdef"
DAYS = 14


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None, raw=None, headers=None):
        url = self.base + path
        data = None
        hdrs = {"Accept": "application/json"}
        if token:
            hdrs["Authorization"] = "Bearer " + token
        if raw is not None:
            data = raw
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        if headers:
            hdrs.update(headers)
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                text = resp.read().decode("utf-8")
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise SystemExit(f"{method} {path} -> HTTP {e.code}: {detail}")

    def get(self, path, token):
        return self.call("GET", path, token)

    def post(self, path, token, body=None):
        return self.call("POST", path, token, body if body is not None else {})

    def put(self, path, token, body):
        return self.call("PUT", path, token, body)


def png(width, height, rgb_top, rgb_bottom, stripes=0):
    """Ein kleines PNG mit Farbverlauf (und Streifen) - ohne PIL."""
    rows = []
    for y in range(height):
        t = y / max(1, height - 1)
        color = [int(a + (b - a) * t) for a, b in zip(rgb_top, rgb_bottom)]
        row = bytearray([0])
        for x in range(width):
            c = color
            if stripes and ((x + y) // stripes) % 2 == 0:
                c = [min(255, v + 28) for v in color]
            row.extend(c)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def upload(api, token, image):
    boundary = "----cohabit" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"demo.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + image + f"\r\n--{boundary}--\r\n".encode()
    return api.call("POST", "/cohabit/api/photos", token, raw=body,
                    headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                             "Idempotency-Key": str(uuid.uuid4())})["id"]


def has(api, path, token):
    """Ob die Instanz einen Endpunkt schon kennt: ein unbekannter Pfad antwortet
    404 mit "Nicht gefunden.", ein bekannter mit seiner eigenen Meldung."""
    req = urllib.request.Request(api.base + path, method="GET",
                                 headers={"Authorization": "Bearer " + token})
    try:
        urllib.request.urlopen(req, timeout=10)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            try:
                return json.loads(e.read().decode("utf-8")).get("message") != "Nicht gefunden."
            except ValueError:
                return False
        return e.code != 405


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://127.0.0.1:48790")
    parser.add_argument("--out", default="demo-tokens.json")
    args = parser.parse_args()
    api = Api(args.base)

    clock = api.get("/cohabit/api/dev/clock", FELIX)
    if clock is None or clock.get("offsetSeconds") is None:
        raise SystemExit("Keine Demo-Uhr - laeuft die Instanz ueber run-backend.sh?")
    if api.get("/cohabit/api/cohabits", FELIX):
        raise SystemExit("Die Instanz hat schon Co-Habits - bitte gegen ein leeres Datenverzeichnis laufen lassen.")

    real_now = dt.datetime.fromisoformat(clock["now"].replace("Z", "+00:00"))
    berlin = ZoneInfo("Europe/Berlin")

    def shift(days_back):
        api.post("/cohabit/api/dev/clock", FELIX, {"offsetSeconds": -days_back * 86400})

    def at(date, hour, minute):
        """Stellt die Demo-Uhr auf eine Ortszeit (Berlin) - heute nie in die Zukunft."""
        target = dt.datetime.combine(date, dt.time(hour, minute), berlin)
        if target > real_now:
            target = real_now - dt.timedelta(minutes=max(1, 24 * 60 - hour * 60 - minute) % 180 + 1)
        offset = int((target - real_now).total_seconds())
        api.post("/cohabit/api/dev/clock", FELIX, {"offsetSeconds": offset})

    def tick():
        api.post("/cohabit/api/dev/tick", FELIX)

    photos_supported = has(api, "/cohabit/api/photos/probe?size=thumb", FELIX)
    chat_supported = has(api, "/cohabit/api/timeline?limit=1", FELIX)

    # --- Tag 0: Personen ----------------------------------------------------------
    shift(DAYS)
    real_today = dt.date.fromisoformat(clock["now"][:10])
    start = real_today - dt.timedelta(days=DAYS)
    api.get("/cohabit/api/me", FELIX)
    api.get("/cohabit/api/me", TORBEN)
    api.put("/cohabit/api/me", FELIX, {"displayName": "Felix", "username": "felix"})

    def register(inviter, name, username):
        code = api.post("/cohabit/api/me/friend-link", inviter)["code"]
        r = api.post(f"/cohabit/api/invite-links/{code}/accept", None,
                     {"displayName": name, "username": username, "acceptTerms": True})
        return r["me"]["person"]["id"], r["token"]

    lena_id, LENA = register(FELIX, "Lena Kraus", "lena.k")
    max_id, MAX = register(FELIX, "Max Berger", "maxb")
    sara_id, SARA = register(FELIX, "Sara Albers", "sara_a")
    req = api.post("/cohabit/api/friends/requests", FELIX, {"username": "torben"})
    api.post(f"/cohabit/api/friends/requests/{req['id']}/accept", TORBEN)
    code = api.post("/cohabit/api/me/friend-link", LENA)["code"]
    api.post(f"/cohabit/api/invite-links/{code}/accept", MAX, {})

    who = {"felix": FELIX, "torben": TORBEN, "lena": LENA, "max": MAX, "sara": SARA}

    # --- Co-Habits ------------------------------------------------------------------
    def create(token, config, members):
        d = api.post("/cohabit/api/cohabits", token, config)
        cid = d["summary"]["ref"]["id"]
        if members:
            link = api.post(f"/cohabit/api/cohabits/{cid}/invite-link", token)["code"]
            for m in members:
                api.post(f"/cohabit/api/invite-links/{link}/accept", who[m], {})
        return cid

    laufen = create(FELIX, {
        "type": "STREAK", "name": "Laufen", "color": "peach", "timezone": "Europe/Berlin",
        "tracking": {"mode": "CHECK"}, "photoRequired": photos_supported, "backfillHours": 48,
        "reminderTime": "07:30", "membersCanInvite": False,
        "streak": {"rhythm": {"kind": "TIMES_PER_WEEK", "times": 3}, "groupStreak": False}},
        ["lena", "max"])
    zucker = create(FELIX, {
        "type": "ABSTINENCE", "name": "Ohne Zucker", "color": "mint", "backfillHours": 48,
        "abstinence": {"groupMode": False}}, ["sara"])
    api.put(f"/cohabit/api/cohabits/{zucker}/settings/me", SARA, {"shareBreaks": True})
    schritte = create(FELIX, {
        "type": "GOAL", "name": "1 Mio. Schritte", "color": "periwinkle", "backfillHours": 48,
        "health": {"metric": "STEPS"},
        "goal": {"target": 1000000, "deadline": (real_today + dt.timedelta(days=31)).isoformat(),
                 "counting": "AMOUNT", "mode": "TEAM"}}, ["lena", "max", "sara"])
    first_to = create(FELIX, {
        "type": "CHALLENGE", "name": "Erster bei 42 km", "color": "aqua", "backfillHours": 48,
        "tracking": {"mode": "VALUE", "unit": "KM"},
        "challenge": {"start": start.isoformat(), "end": (start + dt.timedelta(days=6)).isoformat(),
                      "scoring": "FIRST_TO_TARGET", "target": 42, "stake": "Verlierer gibt ein Eis aus",
                      "recurrence": "WEEKLY"}}, ["lena", "torben"])
    month_start = real_today.replace(day=1)
    month_end = (month_start + dt.timedelta(days=32)).replace(day=1) - dt.timedelta(days=1)
    kochen = create(FELIX, {
        "type": "CHALLENGE", "name": "Wer kocht öfter?", "color": "butter", "backfillHours": 48,
        "tracking": {"mode": "CHECK"},
        "challenge": {"start": month_start.isoformat(), "end": month_end.isoformat(),
                      "scoring": "MOST_ENTRIES", "stake": "Verlierer kocht für alle", "recurrence": "MONTHLY"}},
        ["lena", "max", "sara", "torben"])
    lesen = create(FELIX, {
        "type": "STREAK", "name": "Lesen", "color": "rose", "backfillHours": 48,
        "streak": {"rhythm": {"kind": "DAILY"}, "groupStreak": True}}, ["torben"])
    create(FELIX, {
        "type": "STREAK", "name": "Track food", "color": "peach",
        "streak": {"rhythm": {"kind": "DAILY"}, "groupStreak": False}, "auto": {"source": "FOOD"}}, [])
    for token in (FELIX, LENA, MAX, SARA):
        api.put(f"/cohabit/api/cohabits/{schritte}/settings/me", token, {"healthConsent": True})
    api.post(f"/cohabit/api/cohabits/{lesen}/pauses", TORBEN, {
        "from": (start + dt.timedelta(days=5)).isoformat(), "to": (start + dt.timedelta(days=6)).isoformat()})

    colors = {"felix": ((242, 160, 123), (181, 83, 42)), "lena": ((124, 195, 154), (47, 122, 82)),
              "max": ((143, 156, 242), (63, 79, 194))}
    captions = ["Regenlauf zählt doppelt.", "Früh raus, lohnt sich.", "Mit Gegenwind.", None, "Neue Strecke!"]

    def checkin(cid, token, **body):
        body.setdefault("id", str(uuid.uuid4()))
        return api.post(f"/cohabit/api/cohabits/{cid}/checkins", token, body)

    def message(cid, token, text=None, photo=None):
        if chat_supported:
            api.post(f"/cohabit/api/cohabits/{cid}/messages", token,
                     {"id": str(uuid.uuid4()), "text": text, "photoId": photo})

    # --- Zwei Wochen Geschichte -----------------------------------------------------
    for day in range(DAYS + 1):
        shift(DAYS - day)
        date = start + dt.timedelta(days=day)
        weekday = date.weekday()
        # Laufen: Felix Mo/Mi/Fr, Lena fast jeden zweiten Tag, Max unregelmaessig.
        runners = []
        if weekday in (0, 2, 4) and day != DAYS:
            runners.append("felix")
        if day % 2 == 0 and day != DAYS:
            runners.append("lena")
        if day in (1, 4, 6, 9, 11, 13):
            runners.append("max")
        for i, person in enumerate(runners):
            at(date, 6 + i, 12 + 7 * i + day % 20)
            photo = None
            if photos_supported:
                top, bottom = colors[person]
                photo = upload(api, who[person], png(96, 64, top, bottom, stripes=8 + day % 5))
            checkin(laufen, who[person], photoId=photo, caption=captions[(day + i) % len(captions)])
        # Ohne Zucker: Felix unterbricht einmal (nicht geteilt), Sara einmal (geteilt).
        at(date, 12, 30)
        if day == 3:
            checkin(zucker, FELIX, kind="BREAK")
        if day == 8:
            checkin(zucker, SARA, kind="BREAK")
        # Schritte: Health-Werte aus den Apps, abends synchronisiert.
        at(date, 21, 40)
        for i, token in enumerate((FELIX, LENA, MAX, SARA)):
            if (day + i) % 4 != 3:
                api.put(f"/cohabit/api/cohabits/{schritte}/health/{date.isoformat()}", token,
                        {"value": 3200 + ((day * 7 + i * 13) % 9) * 900})
        # Erster bei 42 km: in jeder Runde; wer die 42 zuerst schafft, beendet sie sofort.
        at(date, 17, 5 + day % 40)
        state = api.get(f"/cohabit/api/cohabits/{first_to}", FELIX)["challenge"]
        if state["start"] <= date.isoformat() <= state["end"]:
            for token, km in ((FELIX, 7 + day % 3), (TORBEN, 5.5), (LENA, 9.5 - day % 4)):
                running = api.get(f"/cohabit/api/cohabits/{first_to}", FELIX)["summary"]["canCheckIn"]
                if running:
                    checkin(first_to, token, value=km)
        # Wer kocht oefter: nur im laufenden Monat.
        at(date, 19, 10 + day % 45)
        if month_start <= date <= month_end:
            cooks = [p for j, p in enumerate(["lena", "felix", "max", "sara", "torben"]) if (day + j) % (j + 2) == 0]
            for p in cooks:
                checkin(kochen, who[p])
        # Lesen: Felix jeden Tag, Torben meist.
        at(date, 22, 15)
        checkin(lesen, FELIX)
        if day % 5 != 2:
            checkin(lesen, TORBEN)
        # Ein bisschen Chat.
        at(date, 20, 45)
        if day == 2:
            message(laufen, MAX, "Morgen 7 Uhr zusammen an der Alster?")
            message(laufen, FELIX, "Bin dabei!")
        if day == 9:
            message(kochen, LENA, "Heute gibt's Linsencurry.")
            message(kochen, SARA, "Lecker, Rezept bitte!")
        at(date, 23, 50)
        tick()

    # --- Heute ----------------------------------------------------------------------
    shift(0)
    if chat_supported:
        tokens_by_id = {"felix": FELIX, "torben": TORBEN, lena_id: LENA, max_id: MAX, sara_id: SARA}
        timeline = api.get("/cohabit/api/timeline?limit=12", FELIX)["items"]
        reacted = 0
        for item in timeline:
            if reacted >= 5 or not item.get("person") or item["person"]["id"] == "felix":
                continue
            api.post("/cohabit/api/reactions", FELIX, {"target": item["reactionTarget"], "reaction": "STARK"})
            members = api.get(f"/cohabit/api/cohabits/{item['cohabit']['id']}", FELIX)["members"]
            others = [m["person"]["id"] for m in members
                      if m["state"] != "INVITED" and m["person"]["id"] not in ("felix", item["person"]["id"])]
            if others:
                api.post("/cohabit/api/reactions", tokens_by_id[others[0]],
                         {"target": item["reactionTarget"], "reaction": "RESPEKT"})
            reacted += 1
        today_felix = api.get(f"/cohabit/api/cohabits/{laufen}", FELIX)["summary"]
        if today_felix["status"] == "OPEN":
            api.post(f"/cohabit/api/cohabits/{laufen}/nudges", MAX, {"to": "felix", "text": "Heute noch laufen?"})
    tick()

    # App-Links fuer alle fuenf: damit laesst sich jede Person im Browser oder in einer App einrichten.
    setup = {}
    for name, token in who.items():
        setup[name] = api.post("/cohabit/api/me/app-links", token, {"label": "Demo"})["setupUrl"]

    tokens = {"base": args.base, "felix": FELIX, "torben": TORBEN, "lena": LENA, "max": MAX, "sara": SARA,
              "setupUrls": setup,
              "ids": {"felix": "felix", "torben": "torben", "lena": lena_id, "max": max_id, "sara": sara_id},
              "cohabits": {"Laufen": laufen, "Ohne Zucker": zucker, "1 Mio. Schritte": schritte,
                           "Erster bei 42 km": first_to, "Wer kocht öfter?": kochen, "Lesen": lesen}}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(tokens, f, indent=2, ensure_ascii=False)
    print(json.dumps(tokens, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
