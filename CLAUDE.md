# Arbeitsregeln für `habits`

Ein Spring-Boot-Dienst, der Felix' Gewohnheiten zählt: anlegen, täglich
abhaken, Sträh­ne mit Flamme. Läuft als `fherrmann.com/habits` auf dem
Heimserver. **Es gibt keine Weboberfläche** — der einzige Client ist der
Habits-Tab der iOS-App (`~/Server-Projects/cockpit-ios`).

## Vor dem ersten Handgriff

1. `README.md` lesen — dort stehen die Regeln, nach denen gezählt wird, und
   warum. Das ist der Einstieg.
2. `../SERVER-CONTEXT.md` lesen, wenn es um Deploy, nginx oder die beiden
   Nachbardienste geht.
3. Die iOS-App liest `HabitStatus` so, wie er hier serialisiert wird. Wer ein
   Feld umbenennt, bricht den Tab still — `CodingKeys` in
   `../cockpit-ios/Cockpit/Habits/HabitsModels.swift` mitziehen.

## Die drei Regeln, die nicht offensichtlich sind

- **Heute darf offen sein.** Ein Build-Habit, das heute noch nicht abgehakt
  ist, hat seine Sträh­ne nicht verloren — erst um Mitternacht. Bis dahin
  zählt sie von gestern weiter und gilt als *gefährdet* (`atRisk`). Bei
  Quit-Habits gilt das **nicht**: ein Rückfall heute ist entschieden, die
  Sträh­ne ist null. `Streaks.daily(…, todayMayBeOpen)` trägt genau diesen
  Unterschied.
- **Die Woche beginnt Montag 0:00 Europe/Berlin.** Ein Sonntag gehört zur
  Vorwoche. Die Zone steht in `application.properties` (`habits.zone`) und
  kommt über eine `Clock` herein — nie `LocalDate.now()` ohne sie.
- **Automatische Habits fragen ihre Quelle, sie kopieren sie nicht.**
  Schritte kommen bei jeder Anfrage frisch aus dem Weight Tracker, „Track
  food" aus dem Kalorienzähler. Ist eine Quelle weg, trägt das Habit einen
  Hinweis (`unavailable`) — keine Null, die wie eine gerissene Sträh­ne
  aussähe.

## Bauen und prüfen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
./gradlew bootRun     # lokal auf 127.0.0.1:48190/habits, Token "changeme-local-token"
```

**Nie behaupten, etwas baue, ohne `./gradlew test` gelaufen zu haben.**

## Konventionen

- **Bezeichner englisch, Kommentare deutsch.** Wie bei `food` und
  `weight-app`. Kommentare erklären das Warum.
- **Keine Tokens im Repo.** Beide Token (`FH_PRIVATE_TOKEN`,
  `WEIGHT_APP_TOKEN`) kommen über `/etc/habits.env`; das Setup-Skript liest
  sie aus den Stellen, an denen sie ohnehin stehen.
- **Fehler als Klartext.** `ResponseStatusException` mit Begründung; die App
  zeigt den Rumpf direkt an (`PlainTextErrors`).
- **Committen und pushen.** Der Server baut aus dem Repo — was nicht gepusht
  ist, wird nicht deployt.

## Doku aktuell halten — im selben Arbeitsgang

`README.md` bei jeder Regeländerung; `../SERVER-CONTEXT.md` bei allem, was
Deploy, Port oder nginx betrifft; `../cockpit-ios/docs/BACKENDS.md` bei jedem
Endpunkt oder Feld.
