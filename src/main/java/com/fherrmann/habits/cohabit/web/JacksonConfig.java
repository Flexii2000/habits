package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.store.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

/** Ganze Kommazahlen gehen auch in den API-Antworten als ganze Zahlen raus (siehe {@link Json}). */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule cohabitWholeNumbers() {
        return Json.wholeNumbers();
    }
}
