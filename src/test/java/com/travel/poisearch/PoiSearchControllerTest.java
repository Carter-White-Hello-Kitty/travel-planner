package com.travel.poisearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.travel.auth.config.AuthConfig;
import com.travel.auth.controller.AuthController;
import com.travel.auth.support.RateLimiter;
import com.travel.auth.service.AuthService;
import com.travel.auth.service.TokenService;
import com.travel.common.*;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.controller.PoiSearchController;
import com.travel.poisearch.service.impl.PoiSearchServiceImpl;
import com.travel.poisearch.vo.PoiVO;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(PoiSearchControllerTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "auth.cookie-secure=false")
class PoiSearchControllerTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({AuthConfig.class, AuthController.class, ApiSupport.class, GlobalExceptionHandler.class,
      PoiSearchController.class, PoiSearchServiceImpl.class, com.travel.poisearch.controller.PoiDisplayController.class})
  static class Config {
    @Bean AuthService auth() { return mock(AuthService.class); }
    @Bean com.travel.poisearch.client.GoogleMapClient maps() { return mock(com.travel.poisearch.client.GoogleMapClient.class); }
    @Bean RateLimiter limits() { return mock(RateLimiter.class); }
    @Bean GooglePlacesClient google() { return mock(GooglePlacesClient.class); }
  }

  @Autowired WebApplicationContext context;
  @Autowired AuthService auth;
  @Autowired GooglePlacesClient google;
  MockMvc mvc;
  String csrf;
  static final String BODY = """
      {"keyword":"museum","latitude":39.3299,"longitude":-76.6205,"radius":5000}
      """;

  @BeforeEach
  void setup() throws Exception {
    reset(auth, google);
    when(auth.authenticate("test-access")).thenReturn(new TokenService.Identity(1L, UUID.randomUUID()));
    mvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters(context.getBean(FilterChainProxy.class)).build();
    // Obtain a real CSRF token through the existing chain, without security-test bypasses.
    MvcResult response = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
    csrf = Objects.requireNonNull(response.getResponse().getCookie("XSRF-TOKEN")).getValue();
  }

  MockHttpServletRequestBuilder search(String body) {
    return post("/pois/search").contentType("application/json")
        .header("Authorization", "Bearer test-access")
        .cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf).content(body);
  }

  @Test
  void returnsTeamEnvelopeWithGooglePlaceId() throws Exception {
    when(google.search(any())).thenReturn(List.of(new PoiVO("place-id", "Museum", "Baltimore",
        39.33, -76.62, "museum", 4.5, "museum", List.of("museum", "tourist_attraction"),
        1200L, "OPERATIONAL")));
    String body = mvc.perform(search(BODY)).andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("POIs retrieved successfully"))
        .andExpect(jsonPath("$.data.pois[0].googlePlaceId").value("place-id"))
        .andExpect(jsonPath("$.data.pois[0].primaryType").value("museum"))
        .andExpect(jsonPath("$.data.pois[0].types[1]").value("tourist_attraction"))
        .andExpect(jsonPath("$.data.pois[0].userRatingCount").value(1200))
        .andExpect(jsonPath("$.data.pois[0].businessStatus").value("OPERATIONAL"))
        .andReturn().getResponse().getContentAsString();
    var json = JsonMapper.builder().build().readTree(body);
    assertFalse(json.path("data").path("pois").get(0).has("id"));
  }

  @Test
  void keepsAuthenticationAndCsrfRequired() throws Exception {
    mvc.perform(post("/pois/search").contentType("application/json").content(BODY))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    mvc.perform(post("/pois/search").contentType("application/json").content(BODY)
        .header("Authorization", "Bearer test-access"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_REJECTED"));
    verifyNoInteractions(google);
  }

  @Test
  void invalidJsonAndParametersUseTeamErrors() throws Exception {
    for (String body : List.of("{", "{}", BODY.replace("5000", "0"), BODY.replace("39.3299", "100"),
        BODY.replace("5000", "1.5"), BODY.replace("5000", "\"5000\""))) {
      mvc.perform(search(body)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(google);
  }

  @Test
  void distinguishesEmptyResultsAndProviderFailure() throws Exception {
    when(google.search(any())).thenReturn(List.of());
    mvc.perform(search(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pois").isEmpty());
    when(google.search(any())).thenThrow(new ApiError(HttpStatus.GATEWAY_TIMEOUT,
        "POI_SEARCH_TIMEOUT", "POI search timed out"));
    mvc.perform(search(BODY)).andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.error.code").value("POI_SEARCH_TIMEOUT"));
  }
  @Test
  void displayAndMapRoutesKeepAuthCsrfAndViewportValidation() throws Exception {
    mvc.perform(get("/pois/place-id")).andExpect(status().isUnauthorized());
    mvc.perform(post("/pois/map").header("Authorization", "Bearer test-access")
        .contentType("application/json").content("{\"latitude\":0,\"longitude\":0,\"zoom\":12}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/pois/map").header("Authorization", "Bearer test-access")
        .cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)
        .contentType("application/json").content("{\"latitude\":90,\"longitude\":0,\"zoom\":99}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void photoRouteRequiresAuthenticationCsrfAndValidResource() throws Exception {
    mvc.perform(post("/pois/photo").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/pois/photo").header("Authorization", "Bearer test-access")
        .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    when(google.photoUrl("../invalid")).thenThrow(com.travel.common.ApiError.invalid("name", "Invalid resource"));
    mvc.perform(post("/pois/photo").header("Authorization", "Bearer test-access")
        .cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)
        .contentType("application/json").content("{\"name\":\"../invalid\"}"))
        .andExpect(status().isBadRequest());
  }

}
