# Arbeitsregeln für `habits`

Das Backend von **coHabit** (geteilter Habit-Tracker, `fherrmann.com/cohabit`) und der
Wald der Fokus-App (`fherrmann.com/habits/api/focus`). Spring Boot 4, Java 25, JSON-Dateien
statt Datenbank. Clients: die Weboberfläche hier im Repo (`src/main/resources/static/cohabit/`),
die iOS-App `coHabit` (`../cockpit-ios`), die Android-App (`../cohabit-android`) – und die
Fokus-App für den Wald.

## Vor dem ersten Handgriff

1. `README.md` lesen – Regeln, Anmeldung, API, Daten, Migration, Betrieb.
2. `../SERVER-CONTEXT.md` lesen, wenn es um Deploy, nginx, Push-Schlüssel oder die
   Nachbardienste (Kalorienzähler, Weight Tracker) geht.
3. Drei Clients lesen jede Antwort so, wie sie hier serialisiert wird. Wer ein Feld
   umbenennt oder eine Form ändert, bricht sie still – Feldnamen sind Vertrag.

## Die Regeln, die nicht offensichtlich sind

- **Rechnen tut nur der Dienst.** Serien, Quoten, Ränge, Soll/Ist und die Texte der
  Kennzahlen (`remainingText`, `gapText`, `endsInText` …) entstehen in `cohabit/rules`
  und `cohabit/service/Views`; Clients formatieren nur Datum/Uhrzeit.
- **Der laufende Zeitraum darf offen sein** – sonst verlöre, wer morgens die App öffnet,
  seine Serie. Bei Abstinenz dagegen ist eine Unterbrechung heute entschieden.
- **Jedes Co-Habit hat seine Zeitzone.** Nie `LocalDate.now()` ohne Uhr und Zone; die Uhr
  kommt als `Clock` herein (Tests stellen sie mit `MutableClock`).
- **Nie vor dem Start.** Einträge gehen nie vor den Start des Co-Habits oder den Beitritt
  zurück. Wer Tests (oder Demo-Daten) mit Geschichte braucht, legt das Co-Habit früher an
  (`createDaysAgo`, bzw. die Demo-Uhr).
- **`fh_private` geht vor `health_token`.** Die Rangfolge der Zugänge steht in
  `CohabitAuthFilter` und ist absichtlich so: ein fremder Healthy-Cookie darf Felix'
  Browser nie übernehmen.
- **Automatische Co-Habits fragen ihre Quelle, sie kopieren sie nicht** – und zwar bevor
  gerechnet wird, ohne Lock (`ViewService.fetchFacts`). Eine langsame Quelle hält so keinen
  Schreibvorgang auf.
- **Ein Lock für alle Dateien** (`CohabitStore`). Lesen unter `read`, Ändern unter `write`;
  was nach dem Speichern passieren muss (Push, Dateien löschen), per `tx.afterCommit`.
  Objekte aus dem Store nie außerhalb eines `read`/`write` benutzen.
- **Die alte Rechnung bleibt als Referenz im Testbaum** (`legacy/LegacyHabitsService`).
  `MigrationParityTest` vergleicht jede migrierte Serie mit ihr – dort nichts „verbessern“.

## Bauen und prüfen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
JAVA_HOME=… ./gradlew bootRun --args='--server.port=48790 --cohabit.dev.enabled=true'
python3 tools/demo-data.py --base http://127.0.0.1:48790
```

**Nie behaupten, etwas baue, ohne `./gradlew test` gelaufen zu haben.** Die API-Tests
(`cohabit/support/ApiTestBase`) starten den ganzen Dienst mit leerem Datenverzeichnis,
stellbarer Uhr, Quellen ohne Netz und Push-Wegen, die nur mitschreiben.

## Konventionen

- **Bezeichner englisch, Kommentare deutsch** (ohne Umlaute), sie erklären das Warum.
- **Oberflächentexte deutsch mit Umlauten, knapp, keine Erklärtexte.**
- **Fehler**: `ResponseStatusException` mit deutscher Meldung (`Errors`); unter `/cohabit/`
  als `{"message":…}`, beim Wald als Klartext (`ErrorAdvice`).
- **Keine Tokens im Repo.** Alles kommt über `/etc/habits.env`; die Setup-Skripte lesen es
  aus den Stellen, an denen es ohnehin steht, und setzen nur einzelne Zeilen (`set_env`).
- **Keine neuen Abhängigkeiten** ohne Not: APNs, FCM und Bilder laufen mit dem JDK.
- **Committen und pushen.** Der Server baut aus dem Repo – was nicht gepusht ist, wird nicht
  deployt. Deploys stößt Felix selbst an.

## Doku aktuell halten – im selben Arbeitsgang

`README.md` bei jeder Regel- oder API-Änderung; `../SERVER-CONTEXT.md` bei allem, was
Deploy, Port, nginx, Push-Schlüssel oder `/etc/habits.env` betrifft; die Client-Doku
(`../cockpit-ios/docs/BACKENDS.md`, `../cohabit-android`) bei jedem Endpunkt oder Feld.
