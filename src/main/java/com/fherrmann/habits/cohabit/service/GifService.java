package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.GifConfig;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Die GIF-Suche laeuft direkt zwischen App und KLIPY (deren Bedingungen verbieten
 * einen Umweg ueber den Dienst). Der Dienst haelt nur den Schluessel - der gehoert in
 * kein Repo - und gibt ihn angemeldeten Personen samt einer Kennung, die KLIPY je
 * Person braucht, ohne zu erfahren, wer sie ist.
 */
@Service
public class GifService {

    private final CohabitStore store;
    private final String apiKey;
    private final String locale;
    private final String contentFilter;

    public GifService(CohabitStore store,
                      @Value("${cohabit.klipy.api-key:}") String apiKey,
                      @Value("${cohabit.klipy.locale:de}") String locale,
                      @Value("${cohabit.klipy.content-filter:medium}") String contentFilter) {
        this.store = store;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.locale = locale;
        this.contentFilter = contentFilter;
    }

    public GifConfig config(Viewer viewer) {
        if (apiKey.isEmpty()) {
            return GifConfig.disabled();
        }
        String me = viewer.personId();
        String known = store.read(data -> PeopleService.requirePerson(data, me).gifCustomerId);
        String customerId = known != null ? known : store.write(tx -> {
            Person p = PeopleService.requirePerson(tx, me);
            if (p.gifCustomerId == null) {
                tx.peopleW();
                p.gifCustomerId = UUID.randomUUID().toString();
            }
            return p.gifCustomerId;
        });
        return new GifConfig(true, apiKey, customerId, locale, contentFilter);
    }
}
