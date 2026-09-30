package com.fherrmann.habits;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"habits.data-dir=build/tmp/context-test", "cohabit.scheduler.enabled=false"})
class HabitsApplicationTests {

    @Test
    void contextLoads() {
    }
}
