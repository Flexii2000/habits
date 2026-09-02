package com.fherrmann.habits;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "habits.data-file=build/tmp/context-test/habits.json")
class HabitsApplicationTests {

    @Test
    void contextLoads() {
    }
}
