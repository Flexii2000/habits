# habits – das Backend von coHabit

**coHabit** ist ein geteilter Habit-Tracker für kleine Freundesgruppen: Gewohnheiten
werden gemeinsam angelegt, verfolgt, verglichen und im Chat begleitet. Ein gemeinsam
verfolgtes Habit heißt **Co-Habit**. Dieser Spring-Boot-Dienst ist das ganze Backend –
API, Weboberfläche (statisch aus dem Jar), Push, Scheduler. Die Clients (Web, iOS-App
`coHabit` in `cockpit-ios`, Android-App in `cohabit-android`) rechnen nichts selbst:
Serien, Quoten, Ränge, Soll/Ist und jeder Text einer Kennzahl kommen von hier.

Dazu bleibt der **Wald der Fokus-App** (`/habits/api/focus/sessions`) hier zu Hause.
Die alte Habit-API (`/habits/api/habits/**`) antwortet `410 Gone` – ihre Habits sind
beim ersten Start nach coHabit umgezogen (siehe „Migration“).

| Pfad | Was |
|---|---|
| `https://fherrmann.com/cohabit/` | Weboberfläche (Single-Page-App aus `src/main/resources/static/cohabit/`) |
| `https://fherrmann.com/cohabit/api/…` | die API |
| `https://fherrmann.com/cohabit/setup?token=…` | richtet einen Browser mit einem App-Token ein |
| `https://fherrmann.com/habits/api/focus/sessions` | der Wald der Fokus-App, unverändert |

## Vier Typen

| Typ | Worum es geht | Kennzahl |
|---|---|---|
| **STREAK** | regelmäßig dranbleiben: täglich, an Wochentagen, n× pro Woche, n× pro Monat oder alle n Tage | Serie in Tagen, Wochen, Monaten bzw. Fenstern |
| **ABSTINENCE** | auf etwas verzichten – eingetragen wird nur eine Unterbrechung | Tage seit Beitritt bzw. letzter Unterbrechung |
| **GOAL** | eine Menge (oder Zahl von Einträgen) bis zu einem Datum, allein oder als Team | Prozent, Soll/Ist |
| **CHALLENGE** | wer schafft im Zeitraum am meisten (Einträge, Summe, zuerst beim Ziel) | Rang |

Der Typ ist nach dem Anlegen fest. Höchstens **8 Mitglieder** einschließlich offener
Einladungen, genau ein Admin (übertragbar).

## Die Regeln, nach denen gerechnet wird

**Der laufende Zeitraum darf offen sein.** Wer heute (diese Woche) noch nicht erfüllt
hat, hat die Serie nicht verloren – sie zählt vom letzten abgeschlossenen Zeitraum
weiter und ist `atRisk`. So hat es die alte Habit-App gerechnet, und so bleibt es.

**Woche ab Montag, Monat ab dem Ersten** – in der Zeitzone des Co-Habits. Jedes
Co-Habit hat seine eigene (Vorgabe `Europe/Berlin`); alle Tagesgrenzen, Fristen und
Enddaten richten sich danach, nie nach dem Gerät.

**n× pro Woche/Monat**: erfüllt ab n Tagen mit Eintrag; höchstens ein Eintrag je Person
und Tag. **Wochentage**: nur die gewählten Tage sind fällig, die anderen werden
übersprungen. **Intervall n**: Fenster zu n Tagen ab dem Start, erfüllt bei einem Eintrag.

**Pausen** (je Person): Pausentage sind weder fällig noch brechen sie die Serie. Ein
Zeitraum mit Pausentagen verlangt anteilig weniger, `ceil(n × unpausierte Tage / Tage)`;
ein ganz pausierter wird übersprungen.

**Nie vor dem Start.** Einträge gehen bis zur Nachtragsfrist zurück (0–336 h), aber nie
vor den Start des Co-Habits oder den Beitritt der Person, nie in die Zukunft.

**Abstinenz**: heute zählt mit, und eine Unterbrechung heute ist entschieden – dann
steht die Serie auf null. Unterbrechungen erscheinen nur bei Freigabe (`shareBreaks`)
in Chat und Timeline.

**Ziel**: `percent = floor(100 × Summe / Ziel)`, `planDelta = Summe − Ziel × verstrichene
Tage / Gesamttage`. Nach der Deadline: Ergebnis, Systemmeldung, Abschlussdialog.

**Challenge**: Gleichstand = gleicher Rang. Bei „zuerst beim Ziel“ gewinnt der Zeitpunkt
des Eintrags, und die Runde endet sofort. Den Einsatz löst ein, wer auf dem letzten
Platz steht. Wiederkehrende Challenges beginnen am nächsten Montag bzw. Monatsersten neu.

**Bestserie** (Serie ≥ 2, über dem bisherigen Rekord) wird je Serie einmal gemeldet – in
dem Moment, in dem sie den alten Rekord übertrifft. **Meilensteine**: STREAK 7/30/100/365
Tage, 4/12/26/52 Wochen, 3/6/12 Monate; Abstinenz 7/14/30/50/100/200/365 Tage, dann jährlich.

**Automatische Co-Habits fragen ihre Quelle, sie kopieren sie nicht** (nur STREAK, nur für
Personen mit dieser Quelle):

| Quelle | erfüllt, wenn … | gefragt bei |
|---|---|---|
| `FOOD` | ≥ 80 % des kcal-Ziels **oder** Frühstück, Mittag- und Abendessen je ein Eintrag | Kalorienzähler `/api/food/day` – Felix mit `fh_private`, andere Healthy-Personen per Bearer |
| `STEPS_WEEKLY` | Schritte Mo–So ≥ Wochenziel (Serie in Wochen) | Weight Tracker `/api/steps` – Felix mit `WEIGHT_APP_TOKEN`, andere per Bearer |
| `FOCUS` | Minuten der Wald-Sessions ≥ Tagesziel (Vorgabe 240) | `data/focus.json` – nur Felix |

Beide Nachbardienste laufen auf derselben Kiste, gefragt wird über localhost. Vergangene
Tage des Kalorienzählers merkt sich der Dienst im Speicher (heute und gestern immer
frisch). Die automatischen Serien schauen wie früher bis zu 730 Tage zurück, auch vor den
Beitritt. Ist eine Quelle weg, steht das Co-Habit auf `UNAVAILABLE` mit einem Satz statt
einer falschen Null.

## Anmeldung

Kein Apple-, Google- oder E-Mail-Login. Der erste gültige Zugang gewinnt, ungültige fallen
durch:

1. `Authorization: Bearer` – App-Token, Healthy-Token oder `FH_PRIVATE_TOKEN`
2. Cookie `fh_private` – Felix. **Geht allen anderen Cookies vor**: ein fremder
   `health_token` darf Felix' Browser nie übernehmen (beim Kalorienzähler 2026-09-26 passiert).
3. Cookie `cohabit_token` – vom Dienst gesetzt (`Path=/cohabit`, 5 Jahre)
4. Cookie `health_token` – Healthy-Personen aus `HEALTH_TOKENS`

Healthy-Personen (Felix, Torben) sind sofort drin; sie entstehen beim ersten Zugriff.
Alle anderen kommen über einen **Einladungslink** (`/cohabit/join/<code>`, 14 Tage) –
Einladung = Registrierung, der Dienst stellt dabei einen App-Token aus. Apps bekommen nie
den Master-Token: jede Person erzeugt sich **App-Links** (48 Hex, gespeichert nur als
SHA-256, einzeln widerrufbar).

## API

Alles unter `/cohabit/api`, JSON UTF-8, Zeitpunkte ISO-8601 UTC, Tage `yyyy-MM-dd` in
der Zone des Co-Habits. Fehler: Status + `{"message":"Klartext"}`. Check-ins, Nachrichten
und Uploads tragen die UUID des Clients – dieselbe noch einmal liefert 200 mit dem
vorhandenen Objekt (Postausgang ohne Doppelte). Die verbindlichen JSON-Formen stehen im
coHabit-Vertrag; hier der Überblick:

| Bereich | Endpunkte |
|---|---|
| öffentlich | `GET /invite-links/{code}`, `POST /invite-links/{code}/accept` (legt ohne Zugang die Person an), `GET /cohabit/setup?token=` |
| ich | `GET/PUT /me`, `PUT/DELETE /me/avatar`, `GET/PUT /me/notifications`, `GET/POST/DELETE /me/app-links[/{id\|current}]`, `GET /me/export` (ZIP), `DELETE /me` (`{"confirm":"LÖSCHEN"}`), `GET /me/archived`, `POST /me/friend-link`, `GET /me/invitations` |
| Freunde | `GET /friends`, `GET /people/search?q=`, `POST /friends/requests`, `POST /friends/requests/{id}/accept\|decline`, `DELETE /friends/{id}`, `GET/POST/DELETE /blocks` |
| Co-Habits | `GET/POST /cohabits`, `GET/PUT/DELETE /cohabits/{id}`, `POST …/archive\|unarchive`, `GET …/invite-candidates`, `POST …/invitations`, `POST …/invite-link`, `DELETE …/members/{id\|me}`, `PUT …/admin`, `PUT …/settings/me`, `POST/DELETE …/pauses`, `POST …/dialogs/{id}/seen`, `POST /invitations/{id}/accept\|decline` |
| Einträge | `POST/PUT/DELETE /cohabits/{id}/checkins[/{checkinId}]`, `PUT /cohabits/{id}/health/{date}` |
| Chat | `GET/POST /cohabits/{id}/messages`, `DELETE …/messages/{id}`, `POST …/messages/{id}/report`, `POST …/read`, `POST/DELETE /reactions` |
| Übersicht | `GET /today`, `GET /timeline?exclude=`, `POST /timeline/seen`, `GET /stats?range=WEEK\|MONTH\|YEAR`, `GET /widget`, `POST /cohabits/{id}/nudges`, `POST /nudges/{id}/seen` |
| Fotos | `POST /photos` (multipart `photo`, ≤ 10 MB, `Idempotency-Key`), `GET /photos/{id}?size=thumb\|full` |
| Geräte, App | `POST /devices`, `DELETE /devices/{token}`, `GET /app/android`, `GET /app/android/apk` |
| Klassische Liste | `GET/POST /classic/habits`, `PUT/DELETE /classic/habits/{id}`, `POST /classic/habits/{id}/marks`, `DELETE /classic/habits/{id}/marks/{date}` |

**kcal aus Healthy** (seit 2026-10-01): Ein Co-Habit mit Wert kann als Health-Metrik `KCAL` nutzen. Die kcal
holt der Dienst selbst aus dem Kalorienzähler (`KcalSync`) – je Mitglied mit Einwilligung und Healthy-Zugang,
ein Wert je Tag in der Nachtragsfrist, sofort nach der Zustimmung und danach alle 15 Minuten. Details im
Vertrag, §3.9.

**Klassische Liste** (seit 2026-09-30): Felix' alte Habit-Ansicht aus der Fokus-App lässt sich in
der iOS-App von coHabit per Schalter zurückholen. Dafür liefert `/classic/habits` Streaks und
Abstinenz der Person – auch geteilte, seit 01.10. auch Ziele und Challenges (mit der Zusammenfassung
der neuen Liste) – genau in der Form der alten Habits-API (`HabitStatus`:
Flamme, sieben Punkte, markierte Tage, Stand des Zeitraums), dazu `photoRequired`, `shared`,
`admin` und `backfillFrom`. Gerechnet wird mit den Regeln von coHabit; `MigrationParityTest`
prüft Feld für Feld gegen die alte Rechnung. Abhaken, Anlegen, Ändern und Löschen laufen über
die normalen Dienste (Nachtragsfrist, Foto-Pflicht, Timeline, Push gelten also genauso); Löschen
heißt bei einem geteilten Co-Habit Verlassen. Details im Vertrag, §3.10.

**Fotos** werden neu kodiert (2048 px, JPEG 0,85; Vorschau 512 px, 0,8) – damit sind alle
Metadaten weg. Sichtbar für die hochladende Person, für Mitglieder des Co-Habits, in dem
das Foto hängt, und als Avatar für alle Angemeldeten. Nach 24 h unbenutzt: gelöscht.

**Push**: iOS über APNs (Topic `com.fherrmann.cohabit`, Sandbox), Android über Firebase als
reine Datennachricht – beide ohne Bibliothek. Arten: `checkin`, `photo`, `chat` (gebündelt:
höchstens eine je Co-Habit alle 2 Minuten), `nudge`, `invite`, `friend-request`,
`reminder`, `streak-at-risk`, `challenge-ending`, `challenge-ended`, `goal-finished`,
`milestone`, `report` (an Felix), `app-update` (Android). Nie an die auslösende Person,
nie von Blockierten; globale Schalter und je Co-Habit stumm/Check-ins/Chat.

**Scheduler** (jede Minute, in der Zone des Co-Habits): Erinnerung zur Erinnerungszeit an
alle, die heute noch offen sind; um 20:00 gefährdete Serien; eine Stunde vor
Challenge-Ende und das Ende selbst (Podest, Einsatz, neue Runde); Ziel-Enden; Meilensteine
ohne Eintrag (Abstinenz, automatische Quellen); abgelaufene Links; stündlich Fotos.
Was schon verschickt ist, steht in `scheduler.json` – ein Neustart schickt nichts doppelt.

### Der Wald – `/habits/api/focus/sessions`

Die Fokus-Sessions der Fokus-App, nur für Felix (`fh_private`), Fehler als Klartext.

| Methode | Pfad | Was |
|---|---|---|
| POST | `/habits/api/focus/sessions` | `{id, start, end}` → 201; **dieselbe Id noch einmal → 200**, nichts ändert sich |
| GET | `/habits/api/focus/sessions?from=&to=` | Sessions, deren Tag im Zeitraum liegt, neueste zuerst (mit `categoryId`, `categoryName`) |
| DELETE | `/habits/api/focus/sessions/{id}` | Baum fällen → 204; unbekannt → 404 |
| GET | `/habits/api/focus/categories` | die Kategorien zur Auswahl `[{id, name}]`, in der Reihenfolge des Anlegens |
| POST | `/habits/api/focus/categories` | `{id?, name}` → 201; dieselbe Id noch einmal → 200 (Postausgang); Name schon da → 409 |
| PUT | `/habits/api/focus/categories/{id}` | `{name}` umbenennen; coHabit zieht den Namen in seinen Fokus-Habits nach |
| DELETE | `/habits/api/focus/categories/{id}` | aus der Auswahl → 204; Bäume und Co-Habits behalten den Namen |

**Kategorien** (seit 2026-10-01): vor dem Pflanzen gewählt (`categoryId` beim POST, optional – alte Bäume
haben keine); eine unbekannte Kategorie → 400. Ein Fokus-Habit in coHabit kann nach einer Kategorie
zählen, täglich oder pro Woche (Vertrag §2, `auto.focusCategoryId`/`focusPeriod`).

Eine Session gehört zu dem Tag, an dem sie **begann** (Europe/Berlin). Abgelehnt (400):
unter einer Minute, über 1440 Minuten, ein Ende mehr als fünf Minuten in der Zukunft.

## Daten

Alles unter `data/` (auf dem Server `/opt/habits/data/`, **nie überschreiben**):

| Datei | Inhalt |
|---|---|
| `habits.json` | die Habits der alten App – nach der Migration nur noch gelesen (Sicherung) |
| `focus.json` | die Wald-Sessions, wird weiter geschrieben |
| `cohabit/people.json` | Personen, Profile, App-Token-Hashes, Einstellungen, Geräte, Blocks, Freundschaften, Anfragen |
| `cohabit/cohabits.json` | Co-Habits samt Mitgliedschaften, Pausen, Challenge-Runden; Einladungen; Einladungs- und Freundes-Links |
| `cohabit/checkins/<id>.json` | Einträge je Co-Habit |
| `cohabit/messages/<id>.json` | Chat je Co-Habit mit Systemmeldungen, Reaktionen und Lesestand |
| `cohabit/events.json` | Timeline-Ereignisse (mit Reaktionen), Stupser, wer die Timeline bis wohin gesehen hat |
| `cohabit/photos.json`, `cohabit/photos/<id>.jpg`, `<id>_thumb.jpg` | Fotos und wo sie hängen |
| `cohabit/reports.json` | Meldungen (mit Kopie der Nachricht) |
| `cohabit/scheduler.json` | was der Scheduler schon verschickt hat |
| `cohabit/android-release.json` | zuletzt angekündigte Android-Version |
| `cohabit/migrated.marker` | die Migration ist gelaufen |

Alles liegt im Speicher und wird bei jeder Änderung atomar geschrieben (erst daneben,
dann umbenennen) – unter einem Lock für alle Dateien, damit eine Änderung, die Eintrag,
Chat und Timeline zugleich berührt, eine Einheit bleibt. Schlägt sie fehl, springt der
Speicher auf den Stand der Platte zurück.

## Migration

Beim ersten Start (ohne `cohabit/migrated.marker`) wird aus jedem Habit in `habits.json`
ein Co-Habit – Felix allein und Admin, ID `c-<alte ID>`, Zone Europe/Berlin, Nachtragsfrist
336 h, Farben reihum:

| alt | neu |
|---|---|
| BUILD täglich / je Woche n / je Monat n | STREAK `DAILY` / `TIMES_PER_WEEK` n / `TIMES_PER_MONTH` n |
| QUIT | ABSTINENCE |
| FOOD / STEPS / FOCUS | STREAK automatisch (`FOOD`, `STEPS_WEEKLY` mit Wochenziel, `FOCUS` mit Tagesziel) |

Haken werden `DONE`-, Rückfälle `BREAK`-Einträge (`source: MIGRATED`, 12:00 Uhr des Tages).
Keine Systemmeldungen, keine Timeline, kein Push; Bestserie und Meilensteine der
übernommenen Serien werden still als erreicht vermerkt. `MigrationParityTest` prüft gegen
eine Nachbildung von Felix' Datei, dass jede Serie an jedem Tag rund um den Umzug genau das
ist, was die alte API geliefert hätte – die alte Rechnung liegt dafür im Testbaum.

## Betrieb

`habits.service` (User `habits`, `/opt/habits`, `127.0.0.1:48190`). nginx reicht unter
`fherrmann.com` zwei Pfade durch: `location /habits/` (mit Privat-Gate, für den Wald) und
`location /cohabit/` (**ohne** Gate – der Dienst prüft jeden Zugang selbst; 12 MB für Fotos).

```bash
# Code-Update
ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'
# einmalig nach dem ersten coHabit-Deploy: Healthy-Token, APNs/FCM, Android-Verzeichnis, nginx
ssh -t HeimServerRemote '~/services/habits/deploy/setup-cohabit.sh'
```

`setup-cohabit.sh` ist idempotent und setzt in `/etc/habits.env` nur einzelne Zeilen
(`HEALTH_TOKENS`/`HEALTH_OWNER` wortgleich aus `/etc/food.env`, `COHABIT_PUBLIC_URL`,
APNs mit Kopie `/etc/apns-cohabit.p8`, Firebase mit Kopie `/etc/fcm-cohabit.json`,
`COHABIT_ANDROID_DIR=/opt/cohabit-android`), fügt `location /cohabit/` vor dem
`/grades/`-Block ein und prüft am Ende Dienst, Wald, 410 und den Weg über nginx.
Vorlagen: `deploy/`.

## Lokal

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
JAVA_HOME=… ./gradlew bootRun --args='--server.port=48790 --cohabit.dev.enabled=true'
python3 tools/demo-data.py --base http://127.0.0.1:48790      # zwei Wochen Demo-Geschichte
```

Ohne Umgebung ist `changeme-local-token` der Privat-Token (= Felix). Mit
`cohabit.dev.enabled=true` gibt es für Felix `GET/POST /cohabit/api/dev/clock`
(`{"offsetSeconds": …}` verschiebt die Uhr des Dienstes) und `POST /cohabit/api/dev/tick`
(Scheduler sofort) – so baut `tools/demo-data.py` echte Geschichte über die öffentliche
API. Auf dem Server gibt es diese Endpunkte nicht.

Java 25, Spring Boot 4, keine Datenbank, keine Bibliothek für Push oder Bilder.
