package com.fherrmann.habits.cohabit.support;

import com.fherrmann.habits.security.HealthUsers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Ersatz fuer Uhr, Quellen und Push - in allen API-Tests gleich, damit Spring den Kontext teilt. */
@TestConfiguration
public class TestBeans {

    public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Mittwoch, 30. September 2026, 10:00 in Berlin. */
    public static final ZonedDateTime START = ZonedDateTime.of(2026, 9, 30, 10, 0, 0, 0, BERLIN);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(START.toInstant(), BERLIN);
    }

    @Bean
    @Primary
    public FakeSources.Food fakeFood(HealthUsers users) {
        return new FakeSources.Food(users);
    }

    @Bean
    @Primary
    public FakeSources.Steps fakeSteps(HealthUsers users) {
        return new FakeSources.Steps(users);
    }

    @Bean
    public RecordingTransport iosTransport() {
        return new RecordingTransport("ios");
    }

    @Bean
    public RecordingTransport androidTransport() {
        return new RecordingTransport("android");
    }
}
