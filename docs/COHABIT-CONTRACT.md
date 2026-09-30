# coHabit — Vertrag zwischen Backend, Web, iOS und Android

Stand 2026-09-30. Quelle: Felix' Feature-Katalog F0–F15 (`coHabit-Feature-Katalog (1).md`) und die
Entwürfe (`coHabit – Designvarianten.pdf`, 18 Seiten), beide bei Felix, nicht im Repo. **Alles aus dem Katalog wird
umgesetzt**, mit den Abweichungen, die hier als **[Entscheidung]** markiert sind. Wo dieser Vertrag
etwas festlegt, gilt er vor dem Katalog. Findet ein Agent eine Lücke, entscheidet er im Geist dieses
Vertrags, nennt es im Bericht — und ändert keine hier festgelegten Namen, Pfade oder JSON-Formen.

## 0. Felix' Entscheidungen

- **Kein Apple-/Google-/E-Mail-Login.** Zugang über persönliche Token wie bei Healthy. Wer schon einen
  Healthy-Zugang hat (Felix, Torben), ist sofort drin; weitere Freunde kommen über einen
  **Einladungslink** dazu, der Dienst stellt ihnen dann selbst einen Token aus.
- **Eigene App coHabit** für iOS und Android, dazu eine **Weboberfläche** mit demselben Funktionsumfang
  (außer Widgets und dem Lesen von Health-Daten). Die Fokus-App behält To-Do und Wald; ihr Habits-Tab und
  ihr Habits-Widget fallen weg.
- **Verlustfrei:** Felix verliert nichts, was die Habit-App heute kann (siehe §7 Migration).
- **Alles auf einmal** (F0–F15).
- Das Design orientiert sich an den Entwürfen.

---

## 1. Architektur und Betrieb

### 1.1 Backend
- **Der Dienst `habits` wird das coHabit-Backend.** Repo `/Users/flexii/Server-Projects/habits`
  (GitHub `Flexii2000/habits`), systemd-Unit `habits.service`, User `habits`, Laufzeit `/opt/habits`,
  `127.0.0.1:48190`. Spring Boot 4, Java 25, **JSON-Dateien statt Datenbank** (wie bisher; atomar:
  erst daneben schreiben, dann umbenennen; ein Schreib-Lock je Datei oder global).
- **Pfade** [Entscheidung]: Die Context-Path-Einstellung `/habits` entfällt; Controller tragen den
  vollen Pfad.
  - `https://fherrmann.com/cohabit/` — Weboberfläche (statische Dateien aus dem Jar, SPA: jede
    unbekannte Unterseite unter `/cohabit/` liefert `index.html`).
  - `https://fherrmann.com/cohabit/api/…` — die API dieses Vertrags.
  - `https://fherrmann.com/habits/api/focus/sessions` — bleibt **unverändert** (Wald der Fokus-App,
    §7.3). `/habits/api/habits/**` antwortet künftig `410 Gone` mit
    `{"message":"Die Habits sind nach coHabit umgezogen: https://fherrmann.com/cohabit/"}`.
- **nginx**: neue `location /cohabit/` unter `fherrmann.com` → `proxy_pass http://127.0.0.1:48190;`
  (Pfad bleibt erhalten), **ohne Privat-Gate** (der Dienst prüft selbst), `client_max_body_size 12m;`,
  `proxy_read_timeout 60s`. Die bestehende `location /habits/` (mit Gate) bleibt für die Wald-Sessions.
- **Daten** unter `/opt/habits/data/`:
  - `habits.json`, `focus.json` — bisherige Dateien. `habits.json` wird nach der Migration **nicht**
    mehr geschrieben und bleibt als Sicherung liegen (§7). `focus.json` wird weiter geschrieben.
  - `cohabit/` — alles Neue. Vorschlag (das Backend darf feiner schneiden, dokumentiert es dann):
    `people.json` (Personen, Profile, Token-Hashes, Einstellungen, Freundschaften, Anfragen, Blocks,
    Geräte), `cohabits.json` (Co-Habits, Mitgliedschaften, Einladungen, Einladungslinks, Pausen,
    Challenge-Runden), `checkins/<cohabitId>.json`, `messages/<cohabitId>.json` (Chat inkl.
    Systemmeldungen, Reaktionen, Lesestand), `events.json` (Timeline-Ereignisse, Meilensteine,
    Stupser), `reports.json`, `photos/<id>.jpg` und `photos/<id>_thumb.jpg`, `migrated.marker`.
  - ⚠️ `/opt/habits/data/` wird nie überschrieben (gilt schon heute) — auch nicht `cohabit/`.
- **Fotos**: Upload `multipart/form-data`, Feld `photo`, `image/jpeg` oder `image/png`, höchstens
  **10 MB**. Der Dienst dekodiert, dreht **nicht** nach EXIF (Clients laden aufrecht hoch, ohne sich auf
  EXIF zu verlassen), skaliert auf höchstens **2048 px** lange Kante (`full`, JPEG q=0.85) und **512 px**
  (`thumb`, JPEG q=0.8), entfernt alle Metadaten (neu kodieren genügt). ImageIO, keine neue Abhängigkeit.
- **Push**
  - iOS: APNs token-basiert, **Topic `com.fherrmann.cohabit`**, Host Sandbox
    (`https://api.sandbox.push.apple.com`, weil Felix aus Xcode installiert). Schlüssel: Kopie des
    Team-Schlüssels als `/etc/apns-cohabit.p8` (`root:habits`, 640). Env `APNS_KEY_FILE`,
    `APNS_KEY_ID`, `APNS_TEAM_ID` (`ZWFV263P59`), `APNS_TOPIC`, `APNS_HOST`. Implementierung ohne
    Bibliothek wie `todo`/`food` (`push/ApnsClient`).
  - Android: FCM HTTP v1 wie im Kalorienzähler (`push/FcmClient`), Dienstkonto als Kopie
    `/etc/fcm-cohabit.json` (`root:habits`, 640) — dasselbe Firebase-Projekt `fherrmann-apps`, dort
    muss Felix die Android-App **`com.fherrmann.cohabit`** anlegen (§1.5). Env
    `FCM_SERVICE_ACCOUNT_FILE`. Ohne Datei bzw. Schlüssel: kein Push, sonst läuft alles.
  - Reine Datennachrichten auf Android, Alert + Daten auf iOS (§4).
- **Android-APK-Verteilung** wie beim Kalorienzähler: Dateien in `/opt/cohabit-android/`
  (`cohabit.apk`, `latest.json` `{versionCode, versionName}`, gehört `flexii`, Dienst liest nur),
  Env `COHABIT_ANDROID_DIR`. `GET /cohabit/api/app/android` → `{versionCode, versionName, sizeBytes,
  sha256}` (404 ohne), `GET /cohabit/api/app/android/apk` → die APK. Ein `ReleaseAnnouncer` meldet
  eine neue Version per Push an alle Android-Geräte (Merker in `data/cohabit/android-release.json`).
- **Scheduler** (jede Minute): Erinnerungen, gefährdete Streaks, Challenge-Enden und neue Runden,
  Ziel-Enden, Meilensteine, Ablauf von Einladungslinks. Alles in der Zeitzone des jeweiligen Co-Habits.
- **Env** `/etc/habits.env` (neu dazu): `HEALTH_TOKENS` (wortgleich wie in `/etc/food.env`),
  `HEALTH_OWNER` (Vorgabe `felix`), `COHABIT_PUBLIC_URL=https://fherrmann.com/cohabit`, APNs-, FCM-
  und APK-Variablen wie oben. Bestehend: `FH_PRIVATE_TOKEN`, `WEIGHT_APP_TOKEN`, `FOOD_URL`,
  `WEIGHT_URL`.
- **Deploy-Skripte** im Repo `habits`: `deploy/setup-cohabit.sh` (idempotent, TTY, ohne vorangestelltes
  sudo; übernimmt `HEALTH_TOKENS` aus `/etc/food.env`, kopiert APNs-Schlüssel und FCM-Dienstkonto,
  setzt die Env-Zeilen einzeln mit `set_env` — **nie die Datei neu schreiben** —, legt
  `/opt/cohabit-android` an, fügt die nginx-`location /cohabit/` vor dem `/grades/`-Block in
  `/etc/nginx/sites-available/fherrmann.com` ein wie `setup-todo.sh`, `nginx -t`, Reload mit Warten auf
  certbot, Neustart, Prüfung). `deploy/update-habits.sh` bleibt der Weg für Code-Updates.
  Felix startet beides selbst.

### 1.2 Personen und Anmeldung
- **Personen-ID** (`personId`, String): für Healthy-Personen deren Name aus `HEALTH_TOKENS` bzw.
  `HEALTH_OWNER` (`felix`, `torben`); für neue Personen `u-` + 12 Hexzeichen. Stabil, nie geändert.
- **Credentials und Rangfolge** (erster gültiger Treffer gewinnt) [Entscheidung]:
  1. `Authorization: Bearer <token>` — App-Token des Dienstes, Healthy-Token oder `FH_PRIVATE_TOKEN`.
  2. Cookie `fh_private` = `FH_PRIVATE_TOKEN` → die Eigentümerin (`felix`). **Geht vor** allen
     anderen Cookies: ein fremder `health_token` darf Felix' Browser nie übernehmen (ist am 2026-09-26
     beim Kalorienzähler passiert).
  3. Cookie `cohabit_token` (vom Dienst gesetzt, `Path=/cohabit`, `HttpOnly`, `Secure`,
     `SameSite=Lax`, 5 Jahre) → Person zum Token.
  4. Cookie `health_token` (Healthy, `Domain=fherrmann.com`) → Person zum Healthy-Token.
  Ungültige Credentials fallen zur nächsten Stufe durch; ohne gültige: 401 für `/cohabit/api/**`
  (außer den öffentlichen Endpunkten, §3.1).
- **App-Token** [Entscheidung]: Apps bekommen **nie** den Master-Token `FH_PRIVATE_TOKEN`. Jede Person
  (auch Felix und Torben) kann sich im Profil einen **App-Link** erzeugen
  (`https://fherrmann.com/cohabit/setup?token=<48 hex>`), gespeichert nur als SHA-256-Hash mit Label,
  Erstell- und letztem Nutzungszeitpunkt; einzeln widerrufbar. Die Apps nehmen außerdem den
  **Healthy-Setup-Link** (`https://food.fherrmann.com/setup?token=…`) und **Einladungslinks** an
  (§5.4). Für Torben heißt das: sein bekannter Healthy-Link genügt.
- **Einladung = Registrierung**: Wer einen Einladungslink ohne Zugang öffnet, gibt Anzeigename und
  Nutzername ein und bestätigt die Zeile „Mit dem Beitritt akzeptierst du die Nutzungsbedingungen und die
  Datenschutzerklärung" (Links auf `/cohabit/rechtliches`). Der Dienst legt die Person an, stellt einen
  Token aus (im Web als `cohabit_token`-Cookie, in der App in der Antwort) und führt die Einladung aus.
- **Rechtliches**: schlichte Unterseite `/cohabit/rechtliches` mit „Nutzungsbedingungen" und
  „Datenschutz" (privates Projekt von Felix Herrmann; welche Daten liegen wo — Profil, Check-ins, Fotos,
  Chat auf Felix' Server in Deutschland; kein Tracking, keine Weitergabe; Löschen und Export in der App;
  Kontakt: Felix). Knapp, sachlich, keine Floskeln.

### 1.3 Clients
| Client | Ort | Kennung |
|---|---|---|
| Web | Repo `habits`, `src/main/resources/static/cohabit/` | `/cohabit/` |
| iOS | Repo `cockpit-ios`, neues Ziel **`coHabit`** + Erweiterung **`coHabitWidget`** (XcodeGen, `project.yml`) | Bundle `com.fherrmann.cohabit`, Widget `com.fherrmann.cohabit.widget`, App-Gruppe `group.com.fherrmann.cohabit`, Team `ZWFV263P59`, URL-Schema `cohabit` |
| Android | **neues Repo** `/Users/flexii/Server-Projects/cohabit-android` (Kotlin, Compose M3, wie `healthy-android`) | Paket `com.fherrmann.cohabit`, URL-Schema `cohabit` |

### 1.4 Kompatibilität
Alle drei Clients sprechen ausschließlich diese API; gemischte Gruppen (iOS, Android, Web) sind der
Normalfall. **Rechnen tut nur der Dienst** (Streaks, Quoten, Ränge, Soll/Ist, Texte der Kennzahlen);
Clients formatieren nur Datum/Uhrzeit und rendern, was kommt.

### 1.5 Einmalige Schritte für Felix (in den Bericht an ihn)
1. Firebase-Konsole, Projekt `fherrmann-apps`: Android-App `com.fherrmann.cohabit` hinzufügen,
   `google-services.json` nach `cohabit-android/app/`.
2. `ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'`, danach
   `ssh -t HeimServerRemote '~/services/habits/deploy/setup-cohabit.sh'`.
3. iPhone: `tools/install-device.sh coHabit` und `tools/install-device.sh Fokus` (ohne Habits-Tab).
4. Android: `tools/publish.sh` in `cohabit-android`.
5. Torben: APK unter `https://fherrmann.com/cohabit/api/app/android/apk` (sein `health_token`-Cookie
   gilt dort), in der App seinen Healthy-Setup-Link einfügen.

---

## 2. Datenmodell (fachlich)

### 2.1 Farben
Feste Palette (Schlüssel in der API, Hexwerte für alle Clients gleich):

| Schlüssel | Fläche hell | Akzent (Balken, Zellen) | Kräftig (Text auf Fläche) | Fläche dunkel |
|---|---|---|---|---|
| `peach` | `#FFD9C7` | `#F2A07B` | `#B5532A` | `#4D3A31` |
| `mint` | `#CFE8D5` | `#7CC39A` | `#2F7A52` | `#2E4336` |
| `periwinkle` | `#D6DCFB` | `#8F9CF2` | `#3F4FC2` | `#323A5C` |
| `butter` | `#FFF0B3` | `#F2D46B` | `#8A6D00` | `#4A4326` |
| `rose` | `#FBD3E0` | `#EE8FB0` | `#A83A62` | `#4A2F3A` |
| `aqua` | `#CDEFF1` | `#6CCFD6` | `#1E7F87` | `#27454A` |

App-Farben: Hintergrund hell `#F3F0FB` / dunkel `#14131C`; Fläche `#FFFFFF` / `#1F1D2B`; Tinte
`#1C1B2E` / `#F2F0FA`; gedämpft `#6E6A80` / `#A19DB5`; Akzent (Violett, „+"-Knopf, Einladungslink,
„H" im Logo) `#5B3FD9` / `#8A74F0`; Akzent zart `#E7E1FB` / `#2C2645`; Gefahr `#B3261E` / `#F2B8B5`.
Dunkler Modus folgt dem System. Personen bekommen beim Anlegen eine Palettenfarbe für ihren
Avatar-Kreis (aus der ID abgeleitet, stabil).

### 2.2 Typen und Einstellungen eines Co-Habits
- `type`: `STREAK` | `ABSTINENCE` | `GOAL` | `CHALLENGE` — **nach dem Anlegen unveränderlich**.
- `name` (1–40), `color` (Palettenschlüssel), `timezone` (IANA, Vorgabe `Europe/Berlin`; alle
  Tagesgrenzen, Fristen, Enddaten in dieser Zone).
- `tracking`: `{"mode":"CHECK"}` oder `{"mode":"VALUE","unit":"COUNT|MINUTES|KM|STEPS"}`.
- `photoRequired` (bool), `backfillHours` (Nachtragsfrist: 0, 24, 48, 72, 168 oder 336),
  `reminderTime` (`"HH:mm"` oder `null`), `membersCanInvite` (bool).
- `streak` (nur STREAK): `{"rhythm": Rhythm, "groupStreak": bool}`.
  Rhythm: `{"kind":"DAILY"}` · `{"kind":"WEEKDAYS","weekdays":[1,3,5]}` (ISO, 1 = Montag) ·
  `{"kind":"TIMES_PER_WEEK","times":3}` (1–7) · `{"kind":"TIMES_PER_MONTH","times":2}` (1–31,
  **[Entscheidung]** zusätzlich zum Katalog, weil Felix' „Politisch aktiv sein, 2× im Monat" sonst
  verloren ginge) · `{"kind":"INTERVAL","days":2}` (2–30).
- `abstinence` (nur ABSTINENCE): `{"groupMode": bool}`.
- `goal` (nur GOAL): `{"target": number > 0, "start": date, "deadline": date, "counting":"ENTRIES|AMOUNT",
  "mode":"INDIVIDUAL|TEAM"}` (`start` = Anlagetag, nicht änderbar).
- `challenge` (nur CHALLENGE): `{"start": date, "end": date, "scoring":"MOST_ENTRIES|HIGHEST_SUM|FIRST_TO_TARGET",
  "target": number|null (nur FIRST_TO_TARGET), "stake": string|null (≤ 80), "recurrence":"NONE|WEEKLY|MONTHLY"}`.
- `health`: `{"metric":"STEPS|RUNNING_DISTANCE|WORKOUTS|WORKOUT_MINUTES"}` oder `null` — Werte kommen
  aus Apple Health / Health Connect über die Apps (§3.9). Sinnvoll für GOAL/CHALLENGE/STREAK mit Wert.
- `auto` **[Entscheidung, für Felix' bisherige automatische Habits]**: `{"source":"FOOD|STEPS_WEEKLY|FOCUS",
  "weeklyStepGoal": int|null, "focusMinutesGoal": int|null}` oder `null`. Nur bei STREAK. Ein
  automatisches Co-Habit hakt man nicht selbst ab; der Dienst rechnet je Mitglied aus der Quelle:
  - `FOOD`: Tag erfüllt, wenn ≥ 80 % des kcal-Ziels **oder** Frühstück, Mittag- und Abendessen je ein
    Eintrag (wie heute). Quelle `food` über localhost, für Felix mit `fh_private`, für andere
    Healthy-Personen per Bearer mit ihrem Healthy-Token.
  - `STEPS_WEEKLY`: Woche (Mo–So) erfüllt, wenn Schritte ≥ `weeklyStepGoal` (Weight Tracker
    `/api/steps`, Felix mit `WEIGHT_APP_TOKEN`, andere per Bearer). Streak in Wochen.
  - `FOCUS`: Tag erfüllt, wenn die Minuten der Wald-Sessions ≥ `focusMinutesGoal` (Vorgabe 240).
    Nur Felix hat diese Quelle (die Sessions gehören ihm, §7.3).
  - Mitglied kann nur werden, wer die Quelle hat (`me.sources`). Quelle nicht erreichbar →
    `status: "UNAVAILABLE"` mit Text, wie heute `unavailable`.
- Mitglieder: höchstens **8 inklusive Ersteller**; offene Einladungen zählen mit. Rollen `ADMIN`
  (genau eine Person, anfangs der Ersteller, übertragbar) und `MEMBER`.
- Archivieren (lesbar, keine neuen Einträge, im Profil unter „Archivierte Co-Habits"),
  Wiederherstellen, Löschen (nur Admin, mit Bestätigung; löscht Check-ins, Chat, Fotos des Co-Habits).

### 2.3 Einträge (Check-ins)
`kind`: `DONE` (erledigt/Eintrag) oder `BREAK` (Unterbrechung bei ABSTINENCE). Je Typ:
- STREAK: höchstens **ein** `DONE` je Person und Tag (zweiter am selben Tag → 409). `value` optional
  bei `tracking.mode = VALUE`. Pflichtfoto bei `photoRequired`.
- ABSTINENCE: nur `BREAK`; setzt die Serie ab diesem Tag zurück (Tag mit Unterbrechung = 0).
- GOAL: beliebig viele `DONE`; Beitrag = `value` (AMOUNT) bzw. 1 (ENTRIES).
- CHALLENGE: beliebig viele `DONE` im Zeitraum; Wertung siehe §2.6.
- Quelle `source`: `MANUAL` | `HEALTH` (aus Health, je Person/Tag ein Eintrag, wird aktualisiert) |
  `AUTO` (nur intern, nicht gespeichert: automatische Co-Habits) | `MIGRATED` (§7).
- **Nachtragen** bis `backfillHours` vor jetzt (Tag in der Zeitzone des Co-Habits), nie vor dem
  Beitritt der Person bzw. dem Start des Co-Habits, nie in der Zukunft. Eigene Einträge lassen sich in
  derselben Frist bearbeiten (Wert, Notiz, Caption) und löschen.
- **Pausen** (STREAK, je Person): Zeitraum `from`–`to`; Pausentage sind weder fällig noch brechen sie
  die Serie. Eine Woche/ein Monat mit Pausentagen verlangt anteilig weniger:
  `ceil(times × unpausierteTage / TageImZeitraum)`; vollständig pausierte Zeiträume werden übersprungen.
- **Selbstauskunft**, keine Verifikation. Offline-Einträge tragen eine Client-UUID (idempotent).

### 2.4 Rechenregeln STREAK (übernimmt die heutigen Regeln exakt)
- „Heute darf offen sein": Ein noch nicht erfüllter laufender Zeitraum bricht die Serie nicht;
  sie zählt vom letzten abgeschlossenen Zeitraum weiter und ist dann `atRisk: true`.
- Woche beginnt Montag, Monat am Ersten, jeweils in der Zeitzone des Co-Habits.
- DAILY: Tag erfüllt bei `DONE`. WEEKDAYS: nur die gewählten Wochentage sind fällig; andere Tage werden
  übersprungen. TIMES_PER_WEEK/TIMES_PER_MONTH: Zeitraum erfüllt ab `times` Tagen mit `DONE`; Serie in
  Wochen/Monaten. INTERVAL n: Fenster zu je n Tagen ab dem Start; Fenster erfüllt bei ≥ 1 `DONE`; Serie
  in Fenstern **[Entscheidung]**.
- Einheit der Serie: `DAYS` (DAILY, WEEKDAYS, FOOD, FOCUS), `WEEKS` (TIMES_PER_WEEK, STEPS_WEEKLY),
  `MONTHS`, `WINDOWS` (INTERVAL). Beschriftung: Tag/Tage, Woche/Wochen, Monat/Monate, Mal/Mal;
  kurz: „T", „Wo.", „Mon.", „×".
- Erfüllungsquote eines Mitglieds: erfüllte / fällige abgeschlossene Zeiträume seit Beitritt, plus der
  laufende, wenn schon erfüllt; ganze Prozent.
- Rekord: längste Serie irgendeines Mitglieds in diesem Co-Habit (mit Person).
- Gruppen-Streak (`groupStreak`): Zeiträume, in denen **alle** aktiven Mitglieder erfüllt haben.
- „Was fehlt": `remainingText` wie „noch 1 Lauf diese Woche" — Substantiv aus dem Namen ist nicht
  ableitbar, daher **[Entscheidung]**: „noch 1 Eintrag diese Woche" / „noch 2 Einträge diesen Monat" /
  „heute noch offen" / „heute erledigt" / „diese Woche geschafft".
- Neue Bestserie eines Mitglieds (Serie ≥ 2 und größer als sein bisheriger Rekord in diesem Co-Habit)
  → Systemmeldung + Timeline-Ereignis `NEW_BEST`.
- Meilensteine STREAK: 7, 30, 100, 365 Tage · 4, 12, 26, 52 Wochen · 3, 6, 12 Monate.

### 2.5 ABSTINENCE
- Serie = Tage seit Start (Beitritt) bzw. seit dem letzten `BREAK`; heute zählt mit; Unterbrechung heute
  → 0 (wie heute bei Quit). Rekord = längste abgeschlossene oder laufende Serie der Person.
  `toRecordText`: „noch 18 bis zum Rekord" / „neuer Rekord" (wenn aktuell ≥ Rekord und > 0).
- Verlauf früherer Serien je Person (Label = Monat, in dem die Serie endete, „aktuell" für die laufende).
- Gruppenmodus: Tage seit dem letzten `BREAK` irgendeines Mitglieds.
- Meilensteine: 7, 14, 30, 50, 100, 200, 365, dann jährlich → Timeline `MILESTONE`, Systemmeldung.
- Unterbrechungen erscheinen im Chat/der Timeline nur, wenn das Mitglied das für dieses Co-Habit
  freigibt (`shareBreaks`, Vorgabe aus).

### 2.6 GOAL und CHALLENGE
- GOAL TEAM: Summe aller Beiträge; INDIVIDUAL: jede Person gegen dasselbe Ziel.
  `percent` = floor(100 × Summe / Ziel, max. 999). `planDelta` = Summe − Ziel × (verstrichene Tage inkl.
  heute / Gesamttage). `remainingDays` bis einschließlich `deadline`. Nach der Deadline:
  `finished: {reached}` + Systemmeldung + Timeline `GOAL_FINISHED` + Abschlussdialog (§3.5).
- CHALLENGE: Zeitraum `start`–`end` (Tage einschließlich, Ende 23:59:59 in der Zone).
  `MOST_ENTRIES` = Anzahl Einträge; `HIGHEST_SUM` = Summe der Werte; `FIRST_TO_TARGET` = Summe, wer
  zuerst `target` erreicht, gewinnt (Zeitpunkt des Eintrags), die Challenge endet dann sofort.
  Gleichstand → gleicher Rang. Einsatz: löst ein, wer auf dem letzten Platz steht (bei Gleichstand alle
  Letzten). Wiederkehrend: nach dem Ende startet automatisch die nächste Runde (WEEKLY: nächster Montag,
  MONTHLY: nächster Monatserster) mit derselben Länge; `pastRounds` behält Gewinner und Stände.
  Abstand zum Nächstplatzierten: `gapText` „noch 2 bis Lena" (zum direkt Besseren; auf Platz 1:
  „vorn" bzw. „gleichauf mit Lena").
- „Siege" im Profil = gewonnene Challenge-Runden (geteilter erster Platz zählt).

### 2.7 Soziales
- **Freundschaften** symmetrisch. Anfrage per Nutzername oder Freundes-Link; Annahme macht beide zu
  Freunden. Wer eine Co-Habit-Einladung annimmt, wird automatisch mit der einladenden Person befreundet.
- **Einladungen** zu Co-Habits: an Freunde (in der App) oder per **Einladungslink**
  (`https://fherrmann.com/cohabit/join/<code>`, 22 Zeichen Base62, 14 Tage gültig, mehrfach nutzbar bis
  die Plätze voll sind; Admin — oder Mitglieder bei `membersCanInvite` — erzeugt ihn). Freundes-Link
  `https://fherrmann.com/cohabit/join/<code>` mit Art `FRIEND`.
- **Chat** je Co-Habit: Text (≤ 2000), eigene Fotos (mit optionalem Text), Check-in-Posts
  (Beweisfotos), Systemmeldungen. Reaktionen: feste Auswahl **[Entscheidung]** `STARK` „Stark",
  `RESPEKT` „Respekt", `WEITER_SO` „Weiter so", `HAHA` „Haha" — je Person höchstens eine je Art.
  Eigene Nachrichten löschen (bleibt als „Nachricht gelöscht"), melden (landet in `reports.json`, Push an
  Felix), Personen blockieren (deren Nachrichten und Timeline-Einträge sind für mich ausgeblendet; sie
  können mich weder einladen noch anfragen).
- **Timeline**: Ereignisse aller meiner Co-Habits (keine Chatnachrichten): Check-ins mit/ohne Foto,
  Health-Werte, Meilensteine, neue Bestserien, Challenge-Ende, Ziel-Ende, Unterbrechungen (nur wenn
  freigegeben). Reaktionen auf Ereignisse; ein Beweisfoto ist **ein** Ereignis, auf das Chat-Post und
  Timeline-Eintrag dieselben Reaktionen zeigen.
- **Stupser**: an ein Mitglied, das heute noch offen ist; höchstens einer je Absender, Empfänger,
  Co-Habit und Tag (sonst 429); optionaler Text (≤ 60), sonst „Heute noch „{Co-Habit}"?". Push +
  Banner auf „Heute" mit „Zurückstupsen" (stupst den Absender im selben Co-Habit).

### 2.8 Benachrichtigungen
Global (Profil): `checkins`, `photos`, `chat`, `nudges`, `invites`, `reminders`, `streakAtRisk`,
`challengeEnd` (alle Vorgabe an). Je Co-Habit: `muted` (alles aus außer Einladungen), `checkins`,
`chat` (`null` = wie global). Erinnerung zur `reminderTime` an Mitglieder, die heute noch offen sind.
Gefährdete Streaks: um **20:00** (Zone des Co-Habits) an Mitglieder mit Serie ≥ 2 und offenem heutigen
Pflichttag bzw. -zeitraum, der heute endet. Challenge: eine Stunde vor Ende an alle Mitglieder; am Ende
Ergebnis. Keine Pushes an die Person, die das Ereignis selbst ausgelöst hat.

---

## 3. REST-API

Alles unter `https://fherrmann.com/cohabit/api`. JSON UTF-8. Zeitpunkte ISO-8601 UTC
(`2026-09-30T05:12:00Z`), Tage `yyyy-MM-dd` in der Zone des Co-Habits. **Fehler**: Status + JSON
`{"message":"Klartext auf Deutsch"}` (400 Eingabe, 401 ohne Anmeldung, 403 keine Berechtigung,
404 unbekannt oder nicht sichtbar, 409 Konflikt, 410 umgezogen, 413 zu groß, 429 zu oft). Clients zeigen
`message`. IDs vergibt der Dienst (UUID) — außer Check-ins, Nachrichten und Uploads, deren UUID der Client
mitschickt (Idempotenz: dieselbe ID noch einmal → 200 mit dem bestehenden Objekt, nichts doppelt).

### 3.0 Gemeinsame Objekte
```json
PersonView   {"id":"torben","displayName":"Torben","username":"torben","initials":"TO",
              "color":"mint","avatarPhotoId":null}
CohabitRef   {"id":"c-3f2a…","name":"Laufen","color":"peach","type":"STREAK"}
ReactionView {"reaction":"STARK","label":"Stark","count":2,"mine":true}
Headline     {"value":"6","unit":"Wochen","short":"6 Wo."}      // GOAL: {"value":"68%","unit":"","short":"68%"}
                                                                // CHALLENGE: {"value":"#2","unit":"dein Platz","short":"#2"}
```
`initials`: zwei Buchstaben aus dem Anzeigenamen (ein Wort → erste zwei Buchstaben), groß.

### 3.1 Öffentlich (ohne Anmeldung)
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| GET | `/invite-links/{code}` | → `InviteLinkPreview` (404 unbekannt/abgelaufen) |
| POST | `/invite-links/{code}/accept` | `{"displayName","username","acceptTerms":true}` wenn nicht angemeldet, `{}` wenn angemeldet → `{"me": MeView, "token": "…"\|null, "setupUrl": "…"\|null, "cohabitId": "…"\|null}`; setzt im Web das `cohabit_token`-Cookie. 409 Plätze voll / Nutzername vergeben |
| GET | `/cohabit/setup?token=` (kein `/api`) | gültiger App-Token → setzt `cohabit_token`, 302 auf `/cohabit/`; sonst 302 auf `/cohabit/?setup=invalid` |
| GET | `/cohabit/rechtliches` | HTML |

```json
InviteLinkPreview {"kind":"COHABIT","from":PersonView,
  "cohabit":{"ref":CohabitRef,"typeLine":"Streak · 3× pro Woche","rules":["Beweisfoto-Pflicht"],
             "members":[PersonView],"seats":{"used":2,"max":8}} | null,
  "full":false}
```

### 3.2 Ich, Profil, Konto
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| GET | `/me` | → `MeView` |
| PUT | `/me` | `{"displayName","username"}` → `MeView` (409 Nutzername vergeben; Regeln: 3–20 Zeichen `[a-z0-9._]`, beginnt mit Buchstabe; Anzeigename 1–30) |
| PUT | `/me/avatar` | multipart `photo` → `MeView` (quadratisch zuschneiden tut der Client) |
| DELETE | `/me/avatar` | → `MeView` |
| GET/PUT | `/me/notifications` | `NotificationSettings` |
| GET | `/me/app-links` | → `[{"id","label","createdAt","lastUsedAt"}]` |
| POST | `/me/app-links` | `{"label":"iPhone"}` → `{"id","label","setupUrl","token"}` (Token nur in dieser Antwort) |
| DELETE | `/me/app-links/{id}` | → 204 (Abmelden eines Geräts; `DELETE /me/app-links/current` widerruft den eigenen Token) |
| GET | `/me/export` | → `application/zip` (profile.json, cohabits.json, checkins.json, messages.json, photos/…) |
| DELETE | `/me` | `{"confirm":"LÖSCHEN"}` → 204. Löscht Profil, Einträge, Nachrichten (bleiben als „Nachricht gelöscht"), Fotos, Freundschaften, Geräte, App-Token; verlässt alle Co-Habits (Admin geht an das am längsten beteiligte Mitglied; allein → Co-Habit gelöscht). Healthy-Token bleiben (gehören nicht coHabit) |
| GET | `/me/archived` | → `[CohabitSummary]` |

```json
MeView {"person":PersonView,"isOwner":true,"sources":["FOOD","STEPS_WEEKLY","FOCUS"],
        "counts":{"cohabits":4,"friends":5,"wins":3},
        "pendingInvitations":1,"incomingFriendRequests":0,"canLogout":false,
        "createdAt":"2026-09-30T12:00:00Z"}
NotificationSettings {"checkins":true,"photos":true,"chat":true,"nudges":true,"invites":true,
                      "reminders":true,"streakAtRisk":true,"challengeEnd":true}
```
`canLogout`: im Web nur für Personen, die über `cohabit_token` angemeldet sind (Healthy-Personen melden
sich im Web nicht ab); Apps melden sich immer über `DELETE /me/app-links/current` ab.

### 3.3 Freunde, Suche, Blocks
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| GET | `/friends` | → `{"friends":[PersonView],"incoming":[FriendRequest],"outgoing":[FriendRequest]}` |
| GET | `/people/search?q=` | Präfix auf Nutzername/Anzeigename, ≥ 2 Zeichen → `[{"person":PersonView,"relation":"FRIEND\|REQUEST_SENT\|REQUEST_RECEIVED\|NONE\|SELF"}]` (Blockierte fehlen) |
| POST | `/friends/requests` | `{"username"}` → `FriendRequest` (409 schon befreundet/angefragt) |
| POST | `/friends/requests/{id}/accept` · `/decline` | → `{"friends":…}` wie GET |
| DELETE | `/friends/{personId}` | → 204 |
| POST | `/me/friend-link` | → `{"url","code","expiresAt"}` |
| GET | `/blocks` · POST `/blocks` `{"personId"}` · DELETE `/blocks/{personId}` | `[PersonView]` / 204 |

`FriendRequest {"id","from":PersonView,"to":PersonView,"createdAt"}`

### 3.4 Co-Habits
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| GET | `/cohabits` | → `[CohabitSummary]` (aktive, meine) |
| POST | `/cohabits` | `CohabitConfig` + `"invitePersonIds":[…]` → 201 `CohabitDetail` |
| GET | `/cohabits/{id}` | → `CohabitDetail` |
| PUT | `/cohabits/{id}` | `CohabitConfig` ohne `type` (Admin) → `CohabitDetail`; `type` im Rumpf, der abweicht → 400 „Der Typ lässt sich nicht ändern." Challenge-Zeitraum einer laufenden Runde nur verlängerbar |
| POST | `/cohabits/{id}/archive` · `/unarchive` | Admin → `CohabitDetail` |
| DELETE | `/cohabits/{id}` | Admin, `{"confirm":true}` → 204 |
| GET | `/cohabits/{id}/invite-candidates` | → `{"seats":{"used","max"},"canInvite":true,"people":[{"person":PersonView,"status":"INVITE\|INVITED\|MEMBER"}]}` (meine Freunde) |
| POST | `/cohabits/{id}/invitations` | `{"personIds":["torben"]}` → gleiche Antwort wie invite-candidates (409 voll) |
| POST | `/cohabits/{id}/invite-link` | → `{"url","code","expiresAt"}` |
| DELETE | `/cohabits/{id}/members/{personId}` | Admin entfernt / Person verlässt selbst (`me`) → 204 |
| PUT | `/cohabits/{id}/admin` | `{"personId"}` → `CohabitDetail` |
| PUT | `/cohabits/{id}/settings/me` | `{"muted","checkins","chat","shareBreaks","healthConsent"}` → `CohabitDetail` |
| POST | `/cohabits/{id}/pauses` | `{"from","to"}` → `CohabitDetail` · DELETE `/cohabits/{id}/pauses/{pauseId}` |
| POST | `/cohabits/{id}/dialogs/{dialogId}/seen` | → 204 (Abschlussdialog gesehen) |
| GET | `/me/invitations` | → `[InvitationView]` |
| POST | `/invitations/{id}/accept` · `/decline` | → `CohabitDetail` / 204 |

```json
CohabitConfig {"type":"STREAK","name":"Laufen","color":"peach","timezone":"Europe/Berlin",
  "tracking":{"mode":"CHECK"},"photoRequired":true,"backfillHours":48,"reminderTime":"07:30",
  "membersCanInvite":false,
  "streak":{"rhythm":{"kind":"TIMES_PER_WEEK","times":3},"groupStreak":false},
  "abstinence":null,"goal":null,"challenge":null,"health":null,"auto":null}

CohabitSummary {"ref":CohabitRef,"archived":false,
  "headline":Headline,
  "typeLine":"Streak · 3× pro Woche",
  "subline":"Lena & Max heute schon",           // Dashboard-Karte, s. §5.2
  "listLine":"2 von 3 · Foto",                  // Listenzeile
  "status":"OPEN",                              // OPEN | DONE | RUNNING | UNAVAILABLE
  "section":"OPEN_TODAY",                       // OPEN_TODAY | RUNNING
  "unavailableText":null,
  "canCheckIn":true,"photoRequired":true,"valueUnit":null,
  "checkInLabel":"Beweisfoto & abhaken",        // s. §5.3
  "members":[PersonView],"memberCount":3,
  "doneTodayBy":["lena","max"],
  "progress":{"done":2,"goal":3,"fraction":0.67} ,   // Wochen-/Monatsfortschritt, Ziel-%; sonst null
  "rank":{"mine":2,"of":4,"gapText":"noch 2 bis Lena"},  // nur CHALLENGE, sonst null
  "unreadMessages":2}

CohabitDetail {"summary":CohabitSummary,"config":CohabitConfig,
  "createdBy":"felix","createdAt":"…","myRole":"ADMIN","canInvite":true,
  "seats":{"used":3,"max":8},
  "members":[{"person":PersonView,"role":"ADMIN","state":"ACTIVE","joinedAt":"…"}],
  "rules":["Beweisfoto-Pflicht","Nachtragen bis 48 h","Europe/Berlin","Erinnerung 07:30"],
  "streak":StreakBlock|null,"abstinence":AbstinenceBlock|null,
  "goal":GoalBlock|null,"challenge":ChallengeBlock|null,
  "health":{"metric":"STEPS","label":"Schritte","consent":true,"lastSyncAt":"…"|null,
            "shareText":"nur die Schrittzahl wird geteilt"}|null,
  "myCheckins":[Checkin],          // eigene, innerhalb der Nachtragsfrist + heute, neueste zuerst
  "backfillFrom":"2026-09-28",
  "myPauses":[{"id","from","to"}],
  "mySettings":{"muted":false,"checkins":null,"chat":null,"shareBreaks":false,"healthConsent":false},
  "unreadMessages":2,
  "dialog":FinishedDialog|null}

StreakBlock {"current":6,"unit":"WEEKS","unitLabel":"Wochen","atRisk":true,
  "remainingText":"noch 1 Eintrag diese Woche",
  "week":{"days":["2026-09-28",…7 Tage Mo–So],"todayIndex":2,
          "rows":[{"person":PersonView,"cells":["DONE","DONE","OPEN","FUTURE","FUTURE","FUTURE","FUTURE"]}]},
  "fulfillmentRate":86,
  "record":{"value":9,"short":"9 Wo.","person":PersonView}|null,
  "group":{"current":2,"unitLabel":"Wochen"}|null}
// cells: DONE | MISSED | OPEN (heute, noch nicht erledigt) | PAUSED | FUTURE | NOT_DUE | BEFORE_JOIN

AbstinenceBlock {"currentDays":23,"record":41,"toRecordText":"noch 18 bis zum Rekord",
  "members":[{"person":PersonView,"days":23,"newPersonalRecord":false}],
  "series":[{"label":"Mai","days":12,"current":false},{"label":"aktuell","days":23,"current":true}],
  "group":{"days":12}|null}

GoalBlock {"target":100000,"targetText":"100.000","unitLabel":"Schritte","deadline":"2026-10-31",
  "counting":"AMOUNT","mode":"TEAM","typeLine":"Teamziel · bis 31.10.",
  "total":68400,"totalText":"68.400 von 100.000","percent":68,
  "planDelta":2400,"planDeltaText":"+2.400 vor Plan","remainingDays":31,"remainingText":"noch 31 Tage",
  "contributions":[{"person":PersonView,"value":24800,"valueText":"24.800","fraction":1.0}],
  "finished":null}                  // {"reached":true,"text":"Ziel erreicht"} nach der Deadline

ChallengeBlock {"round":3,"start":"2026-09-01","end":"2026-09-30","endsAt":"2026-09-30T21:59:59Z",
  "endsInText":"endet in 9 Std. 41 Min.","periodLabel":"September",
  "scoring":"MOST_ENTRIES","scoringText":"Meiste Einträge","target":null,
  "stake":"Verlierer kocht für alle","recurrence":"MONTHLY","recurrenceText":"startet jeden Monat neu",
  "myRank":2,"leaderboard":[{"rank":1,"person":PersonView,"score":9,"scoreText":"9","fraction":1.0}],
  "pastRounds":[{"label":"August","winners":[PersonView]}],"finished":false}

FinishedDialog {"id":"challenge-2","kind":"CHALLENGE",          // oder "goal"
  "title":"Lena gewinnt „Wer kocht öfter?“",
  "podium":[{"rank":1,"person":PersonView,"score":9,"scoreText":"9"}],   // bis zu 3
  "stakeText":"Verlierer kocht für alle: Sara ist dran.",
  "nextText":"Die nächste Runde startet am 1. Oktober automatisch."}

InvitationView {"id","from":PersonView,"createdAt",
  "cohabit":{"ref":CohabitRef,"typeLine","rules":[…],"members":[PersonView],"seats":{"used","max"}}}
```
`endsInText` rechnet der Dienst zum Abrufzeitpunkt; Clients zählen **nicht** selbst herunter
(einmal pro Minute neu laden genügt).

### 3.5 Einträge
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| POST | `/cohabits/{id}/checkins` | `{"id":"<uuid>","kind":"DONE","date":"2026-09-30"\|null,"value":null,"note":null,"photoId":null,"caption":null}` → 201 `{"checkin":Checkin,"cohabit":CohabitDetail}`. 400 Foto fehlt / außerhalb der Frist / Wert fehlt; 403 automatisches Co-Habit; 409 schon erledigt (STREAK) |
| PUT | `/cohabits/{id}/checkins/{checkinId}` | `{"value","note","caption"}` → wie POST |
| DELETE | `/cohabits/{id}/checkins/{checkinId}` | → `CohabitDetail` |
| PUT | `/cohabits/{id}/health/{date}` | `{"value":8200}` → `CohabitDetail`; nur mit `health` und `healthConsent`; legt den HEALTH-Eintrag des Tages an oder aktualisiert ihn (Timeline-Ereignis wird aktualisiert, nicht verdoppelt) |

```json
Checkin {"id","cohabitId","person":PersonView,"kind":"DONE","date":"2026-09-30","createdAt",
  "value":null,"valueText":null,"note":null,"photoId":null,"caption":null,
  "source":"MANUAL","editable":true}
```
Ein Check-in mit Foto erzeugt einen Chat-Post (`CHECKIN`) und ein Timeline-Ereignis `PHOTO_CHECKIN`;
ohne Foto nur ein Timeline-Ereignis `CHECKIN`.

### 3.6 Chat und Reaktionen
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| GET | `/cohabits/{id}/messages?before=<messageId>&limit=50` | → `{"messages":[Message],"hasMore":true}` (aufsteigend nach Zeit; ohne `before` die neuesten) |
| GET | `/cohabits/{id}/messages?after=<messageId>` | neuere als … (für Aktualisierung) |
| POST | `/cohabits/{id}/messages` | `{"id":"<uuid>","text":"…"\|null,"photoId":null}` → 201 `Message` |
| DELETE | `/cohabits/{id}/messages/{messageId}` | eigene → `Message` (mit `deleted:true`) |
| POST | `/cohabits/{id}/messages/{messageId}/report` | `{"reason":"…"}` → 204 |
| POST | `/cohabits/{id}/read` | `{"lastMessageId"}` → `{"unread":0}` |
| POST | `/reactions` | `{"target":"event:<id>"\|"message:<id>","reaction":"STARK"}` → `{"reactions":[ReactionView]}` |
| DELETE | `/reactions?target=…&reaction=…` | → `{"reactions":[ReactionView]}` |

```json
Message {"id","cohabitId","kind":"TEXT","author":PersonView|null,"mine":true,"createdAt",
  "text":"Bin dabei!","photoId":null,
  "checkin":Checkin|null,                 // bei kind=CHECKIN
  "systemText":null,                      // bei kind=SYSTEM, z. B. "Lena hat eine neue Bestserie: 9 Wochen"
  "reactionTarget":"message:<id>",        // bei CHECKIN: "event:<eventId>"
  "reactions":[ReactionView],"deleted":false}
// kind: TEXT | PHOTO | CHECKIN | SYSTEM
```
Systemmeldungen: Beitritt, Austritt, Entfernen, Adminwechsel, neue Bestserie, Meilenstein,
Challenge-Ende (mit Gewinner), neue Runde, Ziel erreicht/verfehlt, Unterbrechung (nur mit
`shareBreaks`), Einstellungen geändert.

### 3.7 Heute, Timeline, Statistik, Widget
| Methode | Pfad | Antwort |
|---|---|---|
| GET | `/today` | `Today` |
| GET | `/timeline?cohabitId=&before=<eventId>&limit=30` | `{"items":[TimelineItem],"hasMore":bool}` (neueste zuerst) |
| GET | `/stats?range=WEEK\|MONTH\|YEAR&anchor=2026-09-30` | `Stats` |
| GET | `/widget` | `WidgetData` |
| POST | `/nudges/{id}/seen` | 204 |
| POST | `/cohabits/{id}/nudges` | `{"to":"torben","text":null}` → 201 `Nudge` (429 heute schon) |
| POST | `/timeline/seen` | `{"lastEventId"}` → 204 (für „neue Beweisfotos") |

```json
Today {"date":"2026-09-30","openCount":2,"headline":"Noch 2 Haken offen",   // 1: "Noch 1 Haken offen", 0: "Alles erledigt"
  "nudges":[Nudge],"newPhotos":{"count":2,"photoIds":["p1","p2"]},
  "invitations":[InvitationView],"cohabits":[CohabitSummary]}   // Reihenfolge: OPEN_TODAY zuerst, dann nach Name

Nudge {"id","from":PersonView,"cohabit":CohabitRef,"text":"Heute noch kochen?","createdAt"}

TimelineItem {"id":"<eventId>","day":"2026-09-30","at":"…","cohabit":CohabitRef,
  "kind":"PHOTO_CHECKIN",   // CHECKIN | PHOTO_CHECKIN | HEALTH | MILESTONE | NEW_BEST | CHALLENGE_ENDED | GOAL_FINISHED | BREAK
  "person":PersonView|null,
  "title":"Lena hat Laufen abgehakt","subtitle":"07:12 · Serie 9 Wochen",
  "photoId":"…"|null,"caption":"Regenlauf zählt doppelt."|null,
  "reactionTarget":"event:<id>","reactions":[ReactionView],"canReply":true}

Stats {"range":"MONTH","label":"September","fulfillmentRate":86,
  "longestStreak":{"short":"6 Wo.","cohabit":CohabitRef}|null,
  "heatmap":{"from":"2026-09-01","to":"2026-09-30","days":[{"date":"2026-09-01","count":1,"level":1}]},
  "cohabits":[{"ref":CohabitRef,"progressText":"11/13","fraction":0.85}]}

WidgetData {"generatedAt","openCount":2,
  "cohabits":[{"ref":CohabitRef,"value":"6","unit":"Wochen","sub":"Wochen · 2/3","status":"OPEN",
               "statusText":"offen","photoRequired":true,"quickCheckIn":false}],
  "challenge":{"ref":CohabitRef,"endsText":"endet heute","myRank":2,
               "leaderboard":[{"rank":1,"name":"Lena","score":9,"me":false}]}|null,
  "teamGoal":{"ref":CohabitRef,"percent":68}|null,
  "openStreak":{"ref":CohabitRef,"text":"Laufen · 6 Wochen"}|null}
```
Regeln: `openCount` = Zahl der Karten mit `status = OPEN`. OPEN heißt: STREAK mit heute fälligem,
noch nicht erfülltem Eintrag bzw. offenem Zeitraum; CHALLENGE, laufend, ohne eigenen Eintrag heute.
ABSTINENCE und GOAL sind `RUNNING` (Abschnitt „Läuft"). Automatische Co-Habits: Status aus der Quelle,
nie abhakbar. Erfüllungsquote der Statistik über STREAK und ABSTINENCE im Zeitraum **[Entscheidung]**;
Heatmap zählt eigene `DONE`-Einträge je Tag (alle Typen, ohne BREAK, ohne HEALTH), `level` 0–4 nach
Quantilen des Zeitraums. `quickCheckIn` = CHECK-Modus, ohne Foto, ohne Wert, heute offen.

### 3.8 Fotos
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| POST | `/photos` | multipart `photo`, Header `Idempotency-Key: <uuid>` → 201 `{"id","width","height"}` (413 > 10 MB) |
| GET | `/photos/{id}?size=thumb\|full` | JPEG; `Cache-Control: private, max-age=31536000, immutable`. Sichtbar für Mitglieder des Co-Habits, in dem es verwendet wird, für die hochladende Person, und Avatare für alle angemeldeten Personen |

Ein hochgeladenes, nach 24 h nirgends verwendetes Foto wird gelöscht.

### 3.9 Geräte, App, Health
| Methode | Pfad | Rumpf → Antwort |
|---|---|---|
| POST | `/devices` | `{"token","platform":"ios\|android"}` → 204 |
| DELETE | `/devices/{token}` | → 204 |
| GET | `/app/android` · `/app/android/apk` | siehe §1.1 |

Health: Die Apps lesen je Co-Habit mit `health` und `healthConsent` die Tageswerte der letzten
`backfillHours` (mindestens heute und gestern) und schreiben sie mit `PUT /cohabits/{id}/health/{date}`:
`STEPS` Schritte, `RUNNING_DISTANCE` km Lauf/Gehen (iOS `distanceWalkingRunning`, Android `Distance`
aus Lauf-Sessions), `WORKOUTS` Anzahl Trainings, `WORKOUT_MINUTES` Minuten. Einwilligung je Datentyp
über die System-Dialoge, Widerruf jederzeit (`healthConsent:false` im Co-Habit, und in den
System-Einstellungen). Der Dienst sieht nur den einen Tageswert.

---

## 4. Push

Datenfelder für beide Plattformen (iOS: zusätzlich `aps.alert {title, body}`, `aps.sound default`,
`aps.thread-id` = cohabitId; Android: reine Datennachricht, die App baut die Benachrichtigung):
```json
{"kind":"checkin","title":"Lena hat Laufen abgehakt","body":"Regenlauf zählt doppelt.",
 "cohabitId":"c-…","link":"cohabit://cohabit/c-…"}
```
| kind | wann | link |
|---|---|---|
| `checkin` | Check-in ohne Foto eines anderen (Einstellung `checkins`) | `cohabit://cohabit/{id}` |
| `photo` | Beweisfoto eines anderen (`photos`) | `cohabit://cohabit/{id}/chat` |
| `chat` | neue Nachricht (`chat`), gebündelt: höchstens eine je Co-Habit alle 2 Minuten | `cohabit://cohabit/{id}/chat` |
| `nudge` | Stupser | `cohabit://today` |
| `invite` | Co-Habit-Einladung | `cohabit://invitation/{invitationId}` |
| `friend-request` | Freundschaftsanfrage | `cohabit://friends` |
| `reminder` | Erinnerungszeit | `cohabit://cohabit/{id}/checkin` |
| `streak-at-risk` | 20:00, s. §2.8 | `cohabit://cohabit/{id}/checkin` |
| `challenge-ending` | 1 h vor Ende | `cohabit://cohabit/{id}` |
| `challenge-ended` | Ende, Ergebnis | `cohabit://cohabit/{id}` (öffnet den Abschlussdialog) |
| `goal-finished` | Deadline vorbei | `cohabit://cohabit/{id}` |
| `milestone` | Meilenstein/Bestserie eines anderen | `cohabit://timeline` |
| `report` | nur an Felix: neue Meldung | `cohabit://cohabit/{id}/chat` |
| `app-update` | nur Android, neue APK (`versionCode`, `versionName`) | — |

Deep Links (Apps, URL-Schema `cohabit`): `cohabit://today`, `cohabit://timeline`, `cohabit://new`,
`cohabit://stats`, `cohabit://profile`, `cohabit://friends`, `cohabit://cohabit/{id}`,
`cohabit://cohabit/{id}/chat`, `cohabit://cohabit/{id}/checkin` (öffnet Abhaken bzw. das
Beweisfoto-Blatt), `cohabit://invitation/{id}`, `cohabit://join/{code}`, `cohabit://setup?token=…`.
Web-Gegenstücke: `/cohabit/`, `/cohabit/timeline`, `/cohabit/neu`, `/cohabit/statistik`,
`/cohabit/profil`, `/cohabit/freunde`, `/cohabit/c/{id}`, `/cohabit/c/{id}/chat`, `/cohabit/join/{code}`.

---

## 5. Oberfläche (Web, iOS, Android gleich)

### 5.1 Grundsätze
- Deutsch, knapp, echte Umlaute. **Keine Erklärtexte** (Felix' Regel), **außer** dem, was der Katalog
  ausdrücklich verlangt **[Entscheidung]**: „Der Typ kann später nicht mehr geändert werden." (Anlegen,
  Schritt 1), der Hinweis im Beweisfoto-Blatt „Erscheint im Chat von {Co-Habit} und in der Timeline von
  {Namen}.", im Einladungsdialog „Deine Check-ins und Beweisfotos sind für alle Mitglieder sichtbar.",
  auf der Detailseite bei Health „nur {Wert} wird geteilt" und die Zustimmungszeile zu Nutzungsbedingungen
  und Datenschutz. Die Tagline des Startbildschirms und der Absatz „Die Serie läuft von allein weiter …"
  der Abstinenz-Seite entfallen. Die kurzen Beschreibungen der vier Typen im Anlegen-Schritt 1 bleiben
  (sie sind die Auswahl), ebenso ihre Beispielzeile.
- Logo „coHabit" als Wortmarke: kleines „c", großes „H" in Akzent-Violett, sonst Tinte, sehr fette
  serifenlose Schrift (System: iOS SF Pro `.heavy`/`.black`, Android Roboto `FontWeight.Black`, Web
  `system-ui` 900). Große Kennzahlen ebenfalls schwarz/fett, tabellarische Ziffern.
- Karten: große Radien (≈ 24 pt), Pastellfläche in der Co-Habit-Farbe, dekorativer Kreis in der
  Akzentfarbe angeschnitten in einer Ecke (wie in den Entwürfen). Primärknöpfe: Tinte gefüllt,
  volle Breite unten; Akzent-Violett für „+" und Einladungslink.
- Zustände: leer (ein Satz + Knopf, z. B. „Noch keine Co-Habits" + „Co-Habit anlegen"), lädt,
  offline (letzter Stand mit „Stand: …" wie in Healthy; Einträge, Nachrichten, Reaktionen landen im
  Postausgang und gehen beim nächsten Netz raus), Fehler (Meldung des Dienstes).
- Hell und dunkel; Handy zuerst; Web ab 360 px ohne seitliches Scrollen, ab 1100 px zentrierte Spalte
  (max. 560 px) mit der Navigation unten wie in der App oder als Seitenleiste — der Web-Agent entscheidet.

### 5.2 Navigation und Bildschirme (Seitenzahlen = PDF)
Untere Leiste mit fünf Einträgen: **Heute** (Haus), **Timeline**, **+** (violett, großer Kreis),
**Statistik**, **Profil**. Oben auf „Heute" das Logo und rechts der eigene Avatar (→ Profil).
1. **Heute – Dashboard** (S. 1): Kopf „Noch 2 Haken offen" + Umschalter „Dashboard | Liste" (Wahl je
   Gerät merken). Stupser-Banner (violett, „{Name} hat dich angestupst: {Text}" + „Zurückstupsen").
   Offene Einladungen als Karte. Große Karten je Co-Habit: `typeLine` klein oben (`"Laufen · Streak"`
   = Name · Typname), `headline` groß, Avatare (bis 3 + „+n"), `subline`, Abhak-Knopf rund rechts
   (Kamera-Symbol bei Foto-Pflicht, Haken sonst, fehlt bei `canCheckIn:false`). Zwei kleine Karten
   nebeneinander für GOAL/CHALLENGE wie im Entwurf.
2. **Heute – Liste** (S. 2): Abschnitte „Offen heute" und „Läuft"; Zeile = `headline.short` links,
   Name, `listLine`, Wochenpunkte (`progress`) bzw. Balken (GOAL), rechts Abhak-Knopf oder Pfeil.
   Darunter „{n} neue Beweisfotos in der Timeline" mit Vorschaubildern.
3. **Timeline** (S. 3): Filter-Chips „Alle" + je Co-Habit in seiner Farbe; Abschnitte je Tag („Heute",
   „Gestern", Datum). Foto-Karten mit Bild, Caption, Reaktionen, „Antworten" (öffnet den Chat des
   Co-Habits). Übrige Ereignisse kompakt.
4. **Statistik** (S. 4): Umschalter Woche/Monat/Jahr, Karte „Erfüllungsquote" (violett), Karte
   „Längste Serie", Heatmap (Monat als Kalender Mo–So wie im Entwurf; Woche als Zeile; Jahr als
   12 Monatsblöcke), Liste je Co-Habit mit Balken und `progressText`.
5. **Profil** (S. 5): Avatar, Name, @nutzername, „Profil bearbeiten"; drei Zahlenkacheln
   (Co-Habits, Freunde, Siege); Liste: Benachrichtigungen, Health-Verbindung (nur Apps; Status
   „verbunden"), Freunde & Einladungen (Badge „{n} neu"), Archivierte Co-Habits, Daten exportieren,
   **App verbinden** (App-Links erzeugen/widerrufen; Web zeigt Link + QR-Code), Rechtliches; unten
   „Abmelden" (wenn erlaubt) und „Account löschen" (rot, zweistufige Bestätigung mit Eingabe
   „LÖSCHEN").
6. **Detail STREAK** (S. 6): Kopf in Co-Habit-Farbe mit Zurück, `typeLine`, Menü (…): Bearbeiten,
   Mitglieder, Pausen, Einladen, Benachrichtigungen, Archivieren, Verlassen/Löschen. Name groß,
   `remainingText`, rechts `current` + Einheit groß. Umschalter „Übersicht | Chat {ungelesen}".
   Übersicht: „Diese Woche" (Raster Personen × Mo–So, heute fett, eigener offener Tag gestrichelt),
   drei Kacheln (Erfüllung, Rekord · Name, Mitglieder n/8), „Regeln" als Chips. Unten fester Knopf
   `checkInLabel`.
7. **Chat** (S. 7): Check-in-Posts als hervorgehobene Karten (Avatar, „{Name} · hat abgehakt",
   Uhrzeit, Foto, Caption, Reaktionen), Textblasen (eigene rechts violett), Systemmeldungen zentriert
   klein. Eingabe: links Knopf „Abhaken" (bzw. Kamera), Textfeld „Nachricht", Senden; Foto anhängen.
   Langer Druck/Kontext: Reagieren, Löschen (eigene), Melden, Person blockieren.
8. **Detail ABSTINENCE** (S. 8): große Tageszahl mittig, „Rekord 41 Tage · noch 18 bis zum Rekord",
   Mitgliederliste mit Tagen und „neuer persönlicher Rekord", „Deine Serien" als Balken, unten
   „Unterbrechung eintragen" (umrandet, mit Rückfrage).
9. **Detail GOAL** (S. 9): Titel, `totalText`, Prozent groß, Fortschrittsbalken, Chip `planDeltaText`,
   `remainingText`; „Beiträge" als Rangliste mit Balken (eigener dunkel); Health-Karte „Health-Sync
   aktiv · zuletzt 14:02 · nur die Schrittzahl wird geteilt"; unten „{Einheit} manuell eintragen".
10. **Detail CHALLENGE** (S. 10): Titel, `endsInText`, „#2 dein Platz"; Rangliste (eigene Zeile dunkel
    hervorgehoben); Kacheln „Einsatz" und „Wertung"; „Frühere Runden · startet jeden Monat neu" als
    Chips; unten „+1 {Name} eintragen" (bei Wert: Wert-Eingabe).
11. **Start ohne Zugang** (S. 11, angepasst): Logo, Kreise, Feld „Link einfügen" (Setup-,
    Healthy- oder Einladungslink) + „Weiter"; im Web zusätzlich der Hinweis-freie Zustand für
    `?setup=invalid` („Link ungültig"). Keine Apple-/Google-/E-Mail-Knöpfe.
12. **Anlegen Schritt 1** (S. 12): „Was wollt ihr gemeinsam verfolgen?", vier Typkarten in ihren
    Farben (Streak peach, Abstinenz mint, Ziel periwinkle, Challenge butter) mit Beschreibung und
    Beispiel; „Der Typ kann später nicht mehr geändert werden."
13. **Anlegen Schritt 2** (S. 13): Name, Farbe (6 Kreise), typabhängig: Rhythmus (Täglich,
    Wochentage, pro Woche, pro Monat, Intervall + Stepper) · Ziel (Zielwert, Einheit/Zählart, Datum,
    Einzel/Team) · Challenge (Start/Ende, Wertung, Zielwert, Einsatz, Wiederholung) ·
    Abstinenz (Gruppenmodus); dann Schalter Beweisfoto-Pflicht, Mit Wert erfassen (+ Einheit),
    Health-Metrik (Apps; Web zeigt die Auswahl ebenfalls, Werte kommen dann aus den Apps), Zeitzone,
    Nachtragsfrist, Erinnerung, Gruppen-Streak (STREAK), „Mitglieder dürfen einladen". Automatische
    Quellen (Track food, Schritte/Woche, Fokus-Zeit) als eigene Auswahl unter STREAK „Automatisch"
    nur für Personen mit `me.sources`.
14. **Anlegen Schritt 3** (S. 14): „Wer macht mit?", Plätze „3 von 8", violette Karte
    „Einladungslink · Teilen" (System-Teilen-Dialog), Suche „Nutzername suchen", Freundesliste mit
    „Einladen"/„Eingeladen"; unten „Co-Habit starten". Allein starten geht.
15. **Beweisfoto-Blatt** (S. 15): „{Name} abhaken" / „Beweisfoto erforderlich", Kamera-Vorschau mit
    Auslöser, Galerie, Kamerawechsel; Caption optional „Wie war's?"; Hinweis s. §5.1; „Posten &
    abhaken". Web: Datei-Eingabe mit `capture="environment"` plus Galerie.
16. **Einladungsdialog** (S. 16): Avatare, „{Name} lädt dich zu „{Co-Habit}" ein", Chips (Typzeile,
    Regeln, „{frei} von 8 Plätzen"), Hinweis s. §5.1, „Ablehnen" | „Mitmachen". Ohne Zugang zusätzlich
    Anzeigename, Nutzername und die Zustimmungszeile.
17. **Challenge beendet** (S. 17): `FinishedDialog` mit Podest, Einsatz, `nextText`, „Zur Timeline" |
    „Gratulieren" (öffnet den Chat mit einer vorbereiteten Reaktion „Stark" auf die Systemmeldung —
    **[Entscheidung]**: „Gratulieren" setzt `STARK` auf die Challenge-Ende-Systemmeldung und öffnet den
    Chat). Einmal je Person (`dialogs/{id}/seen`).
18. **Widgets** (S. 18), siehe §5.5.

### 5.3 Abhak-Beschriftungen (`checkInLabel`, vom Dienst)
STREAK ohne Foto „Abhaken", mit Foto „Beweisfoto & abhaken", erledigt „Heute erledigt" (Knopf
deaktiviert); ABSTINENCE „Unterbrechung eintragen"; GOAL „{Einheit} manuell eintragen" bzw. „Eintrag
hinzufügen"; CHALLENGE „+1 {Name} eintragen" bzw. „Wert eintragen". Bei `tracking.mode=VALUE` fragt
der Client nach dem Wert (und optional Notiz), sonst genügt ein Tipp; Nachtragen über „Anderer Tag"
im Eintragsblatt (Tage ab `backfillFrom`).

### 5.4 Links und Zugang in den Apps
Das Feld „Link einfügen" nimmt: `https://fherrmann.com/cohabit/setup?token=…`,
`https://food.fherrmann.com/setup?token=…` (Healthy), `https://fherrmann.com/cohabit/join/<code>`,
`cohabit://…`, auch mitten in einem kopierten Text. Token werden erst gespeichert, wenn
`GET /me` damit 200 liefert. iOS speichert den Token im Schlüsselbund mit Zugriffsgruppe für das Widget,
Android verschlüsselt mit dem Keystore (wie Healthy). Abmelden: `DELETE /me/app-links/current`, lokal
alles löschen, Gerät abmelden (`DELETE /devices/{token}`).

### 5.5 Widgets
- **Klein** (ein Co-Habit, konfigurierbar): Fläche in seiner Farbe, Name, Kennzahl groß, Unterzeile
  (`sub`), rechts unten runder Knopf: Haken (Abhaken direkt, `quickCheckIn`), Kamera bei Foto-Pflicht
  (öffnet `cohabit://cohabit/{id}/checkin`), sonst keiner.
- **Mittel**: „Heute · {n} offen", Zeilen mit Farbpunkt, Name, `statusText` (Pill „offen" bzw.
  Kennzahl).
- **Groß**: Challenge-Rangliste (Top 3), Teamziel-Balken, offener Streak.
- **iOS Sperrbildschirm**: rund (Streak eines gewählten Co-Habits: `value` + Einheitskürzel + Name)
  und rechteckig (Challenge: Name, „Platz 2", `endsText`).
- Aktualisierung nicht zeitkritisch (iOS Timeline alle 30 min, Android alle 30 min + nach eigenen
  Aktionen sofort). Eigene Aktionen sofort sichtbar (lokal übernehmen, dann neu laden).
- Web: keine Widgets.

---

## 6. Plattform-Details
- **iOS**: Ziel `coHabit` (Deployment-Target iOS 18.0 wie die anderen Apps), `coHabitWidget` (WidgetKit mit
  App-Intents: konfigurierbares Co-Habit, interaktiver Abhak-Knopf ab iOS 17-API), HealthKit
  (Schritte, Lauf-/Gehdistanz, Trainings), Kamera/Fotos (Nutzungsbeschreibungen deutsch), Push
  (`aps-environment development`), URL-Schema `cohabit`. Teilt `Shared/` bzw. `Core/` nur, wo es dort
  hingehört (siehe `cockpit-ios/CLAUDE.md`). Fokus: Habits-Tab, `HabitEditorSheet`,
  `HabitHistorySheet`, `HabitsStore`, `HabitsWidget`/`HabitsProvider` und `HabitsAPI`/`HabitsModels`
  entfernen, soweit nur Fokus sie nutzt; Wald und To-Do bleiben; der Wald meldet Sessions weiter an
  `/habits/api/focus/sessions`. `tools/install-device.sh`, `tools/verify.sh`, `tools/bootstrap.sh`
  kennen `coHabit`. Doku (`docs/STAND.md`, `ARCHITEKTUR.md`, `BACKENDS.md`, `ENTSCHEIDUNGEN.md`)
  nachziehen.
- **Android**: neues Repo nach dem Muster `healthy-android` (Gradle/AGP/Kotlin-Versionen und
  Bibliotheken von dort übernehmen: Compose BOM, OkHttp 5, kotlinx.serialization, WorkManager, Glance,
  Health Connect, Firebase Messaging, CameraX **oder** Photo-Picker + `ACTION_IMAGE_CAPTURE` — der Agent
  entscheidet, keine exotischen neuen Bibliotheken), minSdk 28, target/compile 37, Keystore-Token,
  Offline-Cache + Postausgang (WorkManager), Selbst-Aktualisierung über `/cohabit/api/app/android`
  (PackageInstaller), `tools/publish.sh` (aus `healthy-android` übernehmen samt der Fixes für den
  ersten Lauf, SIGPIPE und JDK 17; Ziel `/opt/cohabit-android`, Datei `cohabit.apk`), Signatur mit dem
  **vorhandenen** Schlüssel `~/.android/healthy-release.jks` (**[Entscheidung]**: ein Schlüssel für
  beide Apps, eine Sicherung), Glance-Widgets (klein/mittel/groß, Konfigurations-Activity für das
  Co-Habit, Abhak-Aktion), Health Connect.
- **Web**: Vanilla-JS wie die übrigen Weboberflächen (keine Build-Kette, kein Framework), eigene
  Tokens nach §2.1, SPA-Routen nach §4, `fetch` mit `credentials: 'same-origin'`, Fotos als
  `<img src="/cohabit/api/photos/{id}?size=thumb">` (Cookie reicht), Aktualisierung von Chat/Heute alle
  20 s, wenn die Seite sichtbar ist. Datei-Upload für Fotos (Canvas: auf 2048 px verkleinern, JPEG).

---

## 7. Migration der bestehenden Habits

### 7.1 Einmalig beim ersten Start (Marker `data/cohabit/migrated.marker`)
- Person `felix` anlegen: Anzeigename „Felix", Nutzername `felix`, Palettenfarbe aus der ID.
  (`torben` entsteht beim ersten Zugriff mit Anzeigename „Torben", Nutzername `torben`.)
- Je Habit aus `habits.json` ein Co-Habit, Felix allein, Admin, gleiche ID-Basis (`c-` + alte ID),
  Zeitzone `Europe/Berlin`, `backfillHours: 336` (die App erlaubte bisher 14 Tage rückwirkend),
  `photoRequired:false`, `reminderTime:null`, Farben reihum aus der Palette, Start = `createdAt`:
  | alt | neu |
  |---|---|
  | BUILD, period DAY/null | STREAK, `DAILY` |
  | BUILD, WEEK, n | STREAK, `TIMES_PER_WEEK` n |
  | BUILD, MONTH, n | STREAK, `TIMES_PER_MONTH` n |
  | QUIT | ABSTINENCE |
  | FOOD | STREAK, `DAILY`, `auto.source FOOD` |
  | STEPS (weeklyStepGoal) | STREAK, `TIMES_PER_WEEK` 1 (Anzeige), `auto.source STEPS_WEEKLY` mit Ziel |
  | FOCUS (focusMinutesGoal) | STREAK, `DAILY`, `auto.source FOCUS` mit Ziel |
- Marks: BUILD → `DONE`-Check-ins, QUIT → `BREAK`-Check-ins; `source: MIGRATED`,
  `createdAt` = Tag 12:00 Europe/Berlin, UUID neu.
- Keine Systemmeldungen, keine Timeline-Ereignisse, keine Pushes für migrierte Einträge.
- `habits.json` bleibt unverändert liegen (Sicherung), wird nie wieder geschrieben.
- **Prüfung** (Test mit einer Kopie von Felix' echter Datei, Struktur siehe unten): Die Serien jedes
  migrierten Co-Habits am Stichtag müssen mit dem übereinstimmen, was die alte API am selben Stichtag
  geliefert hätte (alte Rechenlogik im Test gegenüberstellen).

Felix' Daten zum Zeitpunkt der Umstellung (2026-09-30): neun Habits — je eines der drei
automatischen Arten (FOOD, STEPS mit Wochenziel, FOCUS mit Tagesziel), vier BUILD (täglich, 1× pro
Woche, 2× pro Monat), zwei QUIT —, 24 Marks, 16 Fokus-Sessions. Die Testdaten in
`src/test/resources/migration/habits.json` bilden diese Struktur mit neutralen Namen nach.

### 7.2 Fokus-App (iOS)
Habits-Tab und Habits-Widget entfallen (Ersatz: coHabit-App und ihre Widgets). Wald und To-Do bleiben
unverändert.

### 7.3 Wald-Sessions
`/habits/api/focus/sessions` bleibt, wie in der README beschrieben (Privat-Cookie, Idempotenz über die
Session-ID). Die Sessions gehören der Eigentümerin (`felix`); daraus rechnet `auto.source FOCUS`.

---

## 8. Aufteilung und Reihenfolge

| Agent | Repo / Worktree | Umfang |
|---|---|---|
| **Backend** | `habits`, Branch `cohabit` | §1.1, §1.2, §2, §3, §4, §7 (Server), Scheduler, Push, Fotos, Export, APK-Verteilung, Deploy-Skripte, README/CLAUDE.md, **Demo-Daten-Skript** (lokal: Personen felix/torben/lena/max/sara, je ein Co-Habit aller Typen mit Einträgen, Fotos, Chat, einer beendeten Challenge-Runde) für die Clients. Legt den SPA-Controller für `/cohabit/**` an. |
| **Web** | `habits`, Branch `cohabit-web`, nur `src/main/resources/static/cohabit/**` und `/cohabit/rechtliches` | §5 im Browser. Testet gegen einen lokal gebauten Stand des Backend-Branches, sobald dieser läuft. |
| **iOS** | `cockpit-ios`, Branch `cohabit` | §5, §6 iOS, Fokus-Umbau §7.2. Testet mit Stub (URLProtocol) und, sobald vorhanden, gegen das lokale Backend im Simulator. |
| **Android** | neues Repo `cohabit-android` (ohne Remote) | §5, §6 Android. Testet mit MockWebServer und, sobald vorhanden, gegen das lokale Backend auf dem Emulator (`adb reverse`). |

Ports für lokale Instanzen: Backend 48790 (Backend-Agent), 48791 (Web-Agent), 48792 (iOS-Agent),
48793 (Android-Agent) — jeder startet sich seinen eigenen Stand mit eigener Datenkopie.
Ende-zu-Ende-Prüfung durch den Hauptagenten nach dem Merge: Demo-Daten → Web, iOS-Simulator und
Android-Emulator gleichzeitig: gemeinsames Co-Habit, Check-in mit Foto auf Android erscheint im
iOS-Chat und in der Web-Timeline, Reaktion im Web erscheint in der App, Challenge-Ende-Dialog auf allen
drei, Migration mit einer Kopie von Felix' `habits.json`.

## 9. Offene Fragen an Felix
Keine blockierenden. Später zu klären: Verteilung der iOS-App an weitere iPhone-Freunde (TestFlight
über `tools/testflight.sh`; bis dahin nutzen sie die Weboberfläche).

---

## Anhang: Abweichungen des Backends vom Vertrag (verbindlich für die Clients)

Das Backend ist fertig (Branch `cohabit`, 137 Tests). Wo es vom Vertrag abweicht, gilt das Backend:

1. **`typeLine`** hat die Form aus §3.4: „Streak · 3× pro Woche". Die kleine Zeile oben auf den
   Dashboard-Karten („Laufen · Streak", §5.2) setzen die Clients selbst zusammen: `name` + „ · " +
   Typname (Streak, Abstinenz, Ziel, Challenge).
2. **`FinishedDialog`** hat zusätzlich `reactionTarget` (`message:<id>`): auf dieses Ziel setzt
   „Gratulieren" die Reaktion `STARK`. `kind` ist `"CHALLENGE"` oder `"GOAL"` (Großbuchstaben).
3. **Mitglieder im Detail**: `state` kann `ACTIVE`, `INVITED` (dann `joinedAt: null`) oder `PAUSED`
   sein. `DELETE …/members/{id}` auf eine eingeladene Person zieht die Einladung zurück.
4. **Zurückstupsen** geht immer, auch wenn der Absender heute schon erledigt hat.
5. **Health**: Ein Wert ≤ 0 in `PUT …/health/{date}` löscht den Health-Eintrag des Tages.
   Health-Einträge lassen sich nicht per PUT bearbeiten. Ein manueller STREAK-Eintrag an einem Tag mit
   Health-Eintrag ergibt 409.
6. **Fotos**: Die ID vergibt der Dienst (Antwort von `POST /photos`), `Idempotency-Key` gilt je
   Person. `GET /photos/{id}` ohne `size` liefert `full`.
7. **`POST /friends/requests`** antwortet 200 (nicht 201).
8. **Texte**, die der Dienst liefert (nur zur Info, Clients zeigen sie unverändert):
   `remainingText` z. B. „heute frei", „pausiert", „fällig bis 02.10.",
   „noch 12.000 Schritte diese Woche"; Challenge vor dem Start: Headline „in 3 T" mit Einheit
   „Tage bis Start"; `periodLabel` „September", „KW 40" oder „01.09.–15.09.".
9. **Statistik**: `fulfillmentRate` ist 0 statt null, wenn nichts fällig war.
10. **Demo**: siehe `DEMO.md`; Tokens und Setup-Links in `demo-tokens.json` im Datenverzeichnis.
