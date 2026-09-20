package com.fherrmann.habits.controller;

import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.security.SecurityConfig;
import com.fherrmann.habits.service.FocusService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FocusController.class)
@Import({SecurityConfig.class, PlainTextErrors.class})
@TestPropertySource(properties = "habits.security.token=testtoken")
class FocusControllerTest {

    private static final Cookie COOKIE = new Cookie("fh_private", "testtoken");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FocusService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private static FocusSessionView session(String id) {
        return new FocusSessionView(id, Instant.parse("2026-09-02T12:00:00Z"),
                Instant.parse("2026-09-02T12:45:00Z"), 45, LocalDate.of(2026, 9, 2));
    }

    @Test
    void ohneCookieVerboten() throws Exception {
        mockMvc.perform(get("/api/focus/sessions").param("from", "2026-09-01").param("to", "2026-09-02"))
                .andExpect(status().isForbidden());
    }

    @Test
    void neuerBaum201BekannterBaum200() throws Exception {
        String body = "{\"id\":\"s1\",\"start\":\"2026-09-02T12:00:00Z\",\"end\":\"2026-09-02T12:45:00Z\"}";
        when(service.record(any())).thenReturn(new FocusService.Recorded(session("s1"), true));
        mockMvc.perform(post("/api/focus/sessions").cookie(COOKIE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minutes").value(45))
                .andExpect(jsonPath("$.day").value("2026-09-02"));
        when(service.record(any())).thenReturn(new FocusService.Recorded(session("s1"), false));
        mockMvc.perform(post("/api/focus/sessions").cookie(COOKIE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void listeLiefertDieSessionsDesZeitraums() throws Exception {
        when(service.list(any(), any())).thenReturn(List.of(session("s2"), session("s1")));
        mockMvc.perform(get("/api/focus/sessions").cookie(COOKIE)
                        .param("from", "2026-08-01").param("to", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("s2"));
    }
}
