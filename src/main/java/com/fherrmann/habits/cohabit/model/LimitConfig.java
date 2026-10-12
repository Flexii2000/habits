package com.fherrmann.habits.cohabit.model;

/**
 * Eine Abstinenz mit Tageslimit (seit 12.10.): ein Tag mit mehr als {@code perDay} bricht die
 * Serie, alle anderen - auch die ohne Eintrag - halten sie. Die Zahl liegt nicht hier, sondern
 * im Logbook (eine Verhaltensweise mit Menge); coHabit liest sie und schreibt Korrekturen
 * dorthin zurueck. So zaehlt eine Tasse, egal ob in coHabit oder im Logbook eingetragen.
 *
 * @param behaviorId   die Verhaltensweise im Logbook der Person - nur beim Anlegen waehlbar
 * @param perDay       hoechstens so viele am Tag
 * @param behaviorName ihr Name, vom Dienst aus dem Logbook uebernommen (Clients schicken ihn nicht)
 * @param unitLabel    ihre Einheit („Tassen“), ebenso
 */
public record LimitConfig(LimitSource source, String behaviorId, Integer perDay, String behaviorName,
                          String unitLabel) {
}
