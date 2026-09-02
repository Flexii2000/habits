package com.fherrmann.habits.controller;

import com.fherrmann.habits.dto.HabitStatus;
import com.fherrmann.habits.dto.Progress;
import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.Unit;
import com.fherrmann.habits.security.SecurityConfig;
import com.fherrmann.habits.service.HabitsService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HabitsController.class)
@Import({SecurityConfig.class, PlainTextErrors.class})
@TestPropertySource(properties = "habits.security.token=testtoken")
class HabitsControllerTest {

    private static final Cookie COOKIE = new Cookie("fh_private", "testtoken");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private HabitsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        // @WebMvcTest haengt die Security-Filterkette nicht mehr von selbst an
        // MockMvc, das muss explizit passieren.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private static HabitStatus steps() {
        return new HabitStatus("s1", "70.000 Schritte / Woche", HabitKind.STEPS, Unit.WEEKS, 70_000,
                3, false, false, new Progress(55_432, 70_000),
                List.of(true, true, true, true, true, true, false), null);
    }

    @Test
    void ohneCookieVerboten() throws Exception {
        mockMvc.perform(get("/api/habits")).andExpect(status().isForbidden());
    }

    @Test
    void falscherCookieVerboten() throws Exception {
        mockMvc.perform(get("/api/habits").cookie(new Cookie("fh_private", "falsch")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listeMitCookie() throws Exception {
        when(service.list()).thenReturn(List.of(steps()));
        mockMvc.perform(get("/api/habits").cookie(COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("STEPS"))
                .andExpect(jsonPath("$[0].unit").value("WEEKS"))
                .andExpect(jsonPath("$[0].streak").value(3))
                .andExpect(jsonPath("$[0].progress.value").value(55_432))
                .andExpect(jsonPath("$[0].progress.goal").value(70_000))
                .andExpect(jsonPath("$[0].recent.length()").value(7))
                .andExpect(jsonPath("$[0].unavailable").doesNotExist());
    }

    @Test
    void abhakenLiefertDenNeuenStand() throws Exception {
        when(service.mark(eq("b1"), any())).thenReturn(new HabitStatus("b1", "Logbook", HabitKind.BUILD,
                Unit.DAYS, null, 4, true, false, null, List.of(), null));
        mockMvc.perform(post("/api/habits/b1/marks").cookie(COOKIE)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doneToday").value(true))
                .andExpect(jsonPath("$.streak").value(4));
    }

    @Test
    void fehlerKommenAlsKlartext() throws Exception {
        when(service.create(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ein Habit braucht einen Namen."));
        mockMvc.perform(post("/api/habits").cookie(COOKIE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Ein Habit braucht einen Namen."));
    }
}
