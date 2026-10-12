package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.LogbookClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.rules.LogbookFacts;
import com.fherrmann.habits.security.HealthUsers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Das Logbook als Quelle einer Abstinenz mit Tageslimit - vor der Rechnung geholt, ausserhalb
 * jedes Locks, wie die automatischen Quellen ({@link AutoSources}).
 *
 * <p>Fehlt die Verhaltensweise (in Healthy geloescht), sieht das Logbook nach "nie etwas
 * eingetragen" aus - das waere eine perfekte Serie. Deshalb wird erst nach ihr gefragt, und
 * ohne sie ist die Quelle "nicht erreichbar", nicht leer.
 */
@Component
public class LogbookSource {

    static final String UNAVAILABLE = "Logbook nicht erreichbar";
    static final String GONE = "Das Verhalten gibt es im Logbook nicht mehr.";

    private final LogbookClient client;
    private final HealthUsers users;

    public LogbookSource(LogbookClient client, HealthUsers users) {
        this.client = client;
        this.users = users;
    }

    /** Ein Logbook hat nur, wer Healthy hat. */
    public boolean hasLogbook(String personId) {
        return users.isHealthPerson(personId);
    }

    public LogbookFacts facts(String personId, String behaviorId, LocalDate from, LocalDate to) {
        try {
            boolean exists = client.behaviors(personId).stream().anyMatch(b -> b.id().equals(behaviorId));
            if (!exists) {
                return LogbookFacts.unavailable(GONE);
            }
            if (from.isAfter(to)) {
                return new LogbookFacts(Map.of(), null);
            }
            return new LogbookFacts(client.values(personId, behaviorId, from, to), null);
        } catch (SourceUnavailableException | LogbookClient.Rejected e) {
            return LogbookFacts.unavailable(UNAVAILABLE);
        }
    }

    /** Die aktiven Verhaltensweisen mit Menge - nur die taugen fuer ein Tageslimit. */
    public List<LogbookClient.Behavior> amountBehaviors(String personId) {
        try {
            return client.behaviors(personId).stream().filter(b -> !b.archived() && b.unit() != null).toList();
        } catch (SourceUnavailableException | LogbookClient.Rejected e) {
            throw Errors.unavailable(UNAVAILABLE);
        }
    }

    /** Eine Verhaltensweise, auch archiviert - oder {@code null}, wenn es sie nicht gibt. */
    public LogbookClient.Behavior behavior(String personId, String behaviorId) {
        try {
            return client.behaviors(personId).stream().filter(b -> b.id().equals(behaviorId)).findFirst().orElse(null);
        } catch (SourceUnavailableException | LogbookClient.Rejected e) {
            throw Errors.unavailable(UNAVAILABLE);
        }
    }

    /** Schreibt die Zahl eines Tages ins Logbook - mit dessen Grund, wenn es ablehnt. */
    public void setValue(String personId, String behaviorId, LocalDate date, double value) {
        try {
            client.setValue(personId, behaviorId, date, value);
        } catch (LogbookClient.Rejected e) {
            throw Errors.badRequest(e.status == 404 ? GONE : e.getMessage() == null ? "Das Logbook hat abgelehnt." : e.getMessage());
        } catch (SourceUnavailableException e) {
            throw Errors.unavailable(UNAVAILABLE);
        }
    }
}
