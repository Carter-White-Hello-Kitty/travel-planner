package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travel.auth.config.AuthConfig;
import com.travel.auth.controller.AuthController;
import com.travel.auth.service.AuthService;
import com.travel.auth.service.TokenService;
import com.travel.auth.support.RateLimiter;
import com.travel.common.ApiSupport;
import com.travel.common.GlobalExceptionHandler;
import com.travel.itinerary.controller.TripController;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** HTTP contract at the authenticated and CSRF-protected trip generation boundary. */
@SpringJUnitConfig(TripGenerationControllerTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "auth.cookie-secure=false")
class TripGenerationControllerTest {
    private static final String ORIGIN = "http://localhost:3000";
    private static final String BODY = """
            {"name":"New York Trip","startDate":"2026-12-01","dayCount":1,
             "searchArea":{"latitude":40.758,"longitude":-73.9855,"radiusMeters":15000},
             "constraints":{"maxPoisPerDay":2,"preferredTypes":["museum"]},"editTimestamp":101}
            """;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({AuthConfig.class, AuthController.class, ApiSupport.class,
            GlobalExceptionHandler.class, TripController.class})
    static class Config {
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean RateLimiter limits() { return mock(RateLimiter.class); }
        @Bean TripService trips() { return mock(TripService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired TripService trips;
    private MockMvc mvc;
    private String csrf;

    @BeforeEach
    void setUp() throws Exception {
        reset(auth, trips);
        when(auth.authenticate("test-access"))
                .thenReturn(new TokenService.Identity(17L, UUID.randomUUID()));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
        MvcResult handshake = mvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        csrf = Objects.requireNonNull(handshake.getResponse().getCookie("XSRF-TOKEN")).getValue();
    }

    private MockHttpServletRequestBuilder generate(String requestId) {
        return post("/trips/88/generate")
                .header("Authorization", "Bearer test-access")
                .header("Idempotency-Key", requestId)
                .cookie(new Cookie("XSRF-TOKEN", csrf))
                .header("X-XSRF-TOKEN", csrf)
                .contentType("application/json")
                .content(BODY);
    }

    private static GeneratedTrip savedTrip() {
        return new GeneratedTrip(88L, "New York Trip", LocalDate.of(2026, 12, 1),
                "Planning", 1, 101L, List.of(new GeneratedTrip.Day(1,
                List.of(new GeneratedTrip.Poi(101L, "place_A", "Museum A", "New York, NY",
                        40.7794, -73.9632, "Planned")))));
    }

    @Test
    void firstGenerationReturnsCreatedTripAndPassesRequestIdToService() throws Exception {
        String requestId = UUID.randomUUID().toString();
        when(trips.generate(any(), eq(88L), eq(requestId), any()))
                .thenReturn(new TripService.GenerationOutcome(savedTrip(), false));

        mvc.perform(generate(requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.tripId").value(88))
                .andExpect(jsonPath("$.data.status").value("Planning"))
                .andExpect(jsonPath("$.data.tripDays[0].tripPois[0].googlePlaceId").value("place_A"));
        verify(trips).generate(argThat(id -> id.userId().equals(17L)), eq(88L), eq(requestId),
                any(GenerateTripRequest.class));
    }

    @Test
    void processingReturnsAcceptedAndRetryAfterWithoutTrip() throws Exception {
        String requestId = UUID.randomUUID().toString();
        when(trips.generate(any(), eq(88L), eq(requestId), any()))
                .thenReturn(new TripService.GenerationOutcome(null, true));

        mvc.perform(generate(requestId))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.message").value("Trip generation in progress"))
                .andExpect(jsonPath("$.data").value((Object) null));
    }

    @Test
    void completedReplayKeepsTheSameSuccessBody() throws Exception {
        String requestId = UUID.randomUUID().toString();
        when(trips.generate(any(), eq(88L), eq(requestId), any()))
                .thenReturn(new TripService.GenerationOutcome(savedTrip(), false));

        String first = mvc.perform(generate(requestId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(generate(requestId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
    }

    @Test
    void corsPreflightAllowsIdempotencyHeader() throws Exception {
        MvcResult response = mvc.perform(options("/trips/88/generate")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key, Content-Type, Authorization, X-XSRF-TOKEN"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(ORIGIN, response.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertTrue(Objects.requireNonNull(response.getResponse().getHeader("Access-Control-Allow-Headers"))
                .contains("Idempotency-Key"));
        verifyNoInteractions(trips);
    }

    @Test
    void authenticationAndCsrfAreRequiredBeforeService() throws Exception {
        mvc.perform(post("/trips/88/generate").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/trips/88/generate").header("Authorization", "Bearer test-access")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(trips);
    }
}
