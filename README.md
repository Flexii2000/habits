# Habits

Gewohnheiten anlegen, täglich abhaken, Sträh­ne mit Flamme — wie bei Snapchat,
nur für Vorsätze. Ein Spring-Boot-Dienst unter `fherrmann.com/habits`, bedient
ausschließlich vom **Habits-Tab der iOS-App** (`~/Server-Projects/cockpit-ios`).
Eine Weboberfläche gibt es bewusst nicht.

## Vier Arten von Habits

| Art | Wer hakt ab | Sträh­ne |
|---|---|---|
| **Build** („Logbook") | du, jeden Tag | zusammenhängende abgehakte Tage |
| **Quit** | niemand — zählt von selbst | Tage seit dem Vorsatz bzw. seit dem letzten **Rückfall**, den du einträgst |
| **Track food** | der Kalorienzähler | Tage, an denen der Tag als getrackt gilt (siehe unten) |
| **Schritte / Woche** | der Weight Tracker | Wochen, in denen das Ziel erreicht wurde |

Die ersten drei Habits legt der Dienst beim ersten Start an: *Track food*,
*Logbook* und *70.000 Schritte / Woche*.

## Die Regeln

**Heute darf offen sein.** Ein Build-Habit, das heute noch nicht abgehakt ist,
hat seine Sträh­ne nicht verloren — erst um Mitternacht. Bis dahin zählt sie
von gestern weiter und ist *gefährdet* (`atRisk: true`). Wer morgens die
Liste öffnet, sieht also seine 12 Tage mit einem Hinweis, nicht eine Null.

**Ein Rückfall heute ist entschieden.** Bei Quit-Habits gilt die Regel oben
nicht: ist heute ein Rückfall eingetragen, steht die Sträh­ne auf null — die
Tage davor laufen nicht „noch offen" weiter. Ohne Rückfall zählt jeder Tag seit
dem Anlegen von selbst, heute eingeschlossen.

**Track food gilt als erledigt**, wenn **mindestens 80 % des kcal-Ziels**
erreicht sind **oder Frühstück, Mittag- und Abendessen je einen Eintrag**
haben. Das Zweite fängt den Tag mit kleinem Appetit ab, das Erste den Tag, an
dem alles in einem Eintrag steht. Snacks ersetzen keine Mahlzeit. Gefragt wird
`food.fherrmann.com/api/food/day` über localhost, mit demselben Privat-Token.

**Die Woche beginnt Montag 0:00** (Europe/Berlin). Ein Sonntag gehört noch zur
Vorwoche. Die Schritte kommen aus `weight.fherrmann.com/api/steps` — dorthin
schreibt die iOS-App sie aus Apple Health. Das Wochenziel ist erreicht, sobald
die Summe Mo–So das Ziel schafft; die App zeigt den Stand als „55/70k". Die
laufende Woche zählt zur Sträh­ne, sobald sie erreicht ist, und bis dahin läuft
die Sträh­ne der Vorwoche weiter.

**Was von selbst zählt, lässt sich nicht abhaken.** `POST …/marks` auf ein
automatisches Habit ist ein 400. Und die Art eines Habits lässt sich nicht
ändern: aus einem Build ein Quit zu machen kehrte die Bedeutung jedes
vorhandenen Eintrags um.

**Ist eine Quelle nicht erreichbar**, bekommt das betroffene Habit einen
Hinweis (`unavailable`) statt einer Sträh­ne. Die anderen Habits laufen weiter.

## REST-API

Alles unter `/habits/api/habits`, hinter dem `fh_private`-Cookie (sonst 403).

| Methode | Pfad | Was |
|---|---|---|
| GET | `/api/habits` | alle Habits als `HabitStatus` |
| POST | `/api/habits` | `{name, kind, weeklyStepGoal?}` → 201 |
| PUT | `/api/habits/{id}` | Name und Wochenziel ändern (die Art nicht) |
| DELETE | `/api/habits/{id}` | löscht Habit **und** alle Einträge — kein Archiv |
| POST | `/api/habits/{id}/marks` | `{date?}` — Haken (Build) bzw. Rückfall (Quit); ohne Datum heute |
| DELETE | `/api/habits/{id}/marks/{date}` | Haken bzw. Rückfall zurücknehmen |

```
HabitStatus  id, name, kind (BUILD|QUIT|FOOD|STEPS), unit (DAYS|WEEKS),
             weeklyStepGoal, streak, doneToday, atRisk,
             progress {value, goal} (nur FOOD: kcal gegen 80 % des Ziels,
                                     STEPS: Schritte gegen das Wochenziel),
             recent [7 × bool, älteste zuerst], unavailable (String | null)
```

Jede Antwort ist der fertige Stand — die App rechnet nichts nach. Fehler
kommen als Klartext (`Ein Habit braucht einen Namen.`), nicht als JSON.

## Daten

Alles in `data/habits.json`: die Habits und ihre Einträge (`marks`). Geschrieben
wird erst daneben und dann umbenannt — ein Absturz mitten im Schreiben ließe
sonst jede je gezählte Sträh­ne in einer halben Datei zurück. Die automatischen
Habits haben keine Einträge; ihr Stand wird bei jeder Anfrage aus der Quelle
gerechnet. Vergangene Tage von „Track food" hält der Dienst im Speicher (nicht
in der Datei): heute und gestern werden immer frisch gefragt, alles davor nach
dem ersten Mal nicht mehr — bis zum nächsten Neustart.

## Betrieb

Läuft als `habits.service` (User `habits`, `/opt/habits`) auf `127.0.0.1:48190`
und wird von nginx als `location /habits/` unter `fherrmann.com` durchgereicht —
ein **Pfad, keine Subdomain**: kein DNS-Eintrag, kein eigenes Zertifikat.

```bash
# einmalig
ssh HeimServerRemote 'git clone git@github.com:Flexii2000/habits.git ~/services/habits'
ssh -t HeimServerRemote '~/services/habits/deploy/setup-habits.sh'
# später
ssh -t HeimServerRemote '~/services/habits/deploy/update-habits.sh'
```

Das Setup-Skript ist idempotent. Es liest beide Token aus den Stellen, an
denen sie ohnehin stehen (`private-mode.conf`, `/etc/health-viz.env`), und
schreibt sie nach `/etc/habits.env`. Vorlagen: `deploy/`.

## Bauen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
```

Java 25, Spring Boot 4, keine Datenbank. Wie `food` und `weight-app`.
