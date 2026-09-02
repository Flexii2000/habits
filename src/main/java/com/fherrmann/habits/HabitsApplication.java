package com.fherrmann.habits;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
public class HabitsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HabitsApplication.class, args);
    }

    /**
     * Die Uhr, nach der ein Tag endet und eine Woche beginnt.
     *
     * <p>Mit fester Zone statt {@code systemDefaultZone()}: "Montag 0:00" ist
     * eine Aussage ueber Felix' Zeit, nicht ueber die des Servers. Injizierbar,
     * damit Tests "heute" festnageln koennen.
     */
    @Bean
    public Clock clock(@Value("${habits.zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
