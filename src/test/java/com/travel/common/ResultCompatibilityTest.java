package com.travel.common;

import static org.junit.jupiter.api.Assertions.*;

import com.travel.auth.ApiError;
import com.travel.auth.AuthController;
import com.travel.auth.AuthService;
import com.travel.auth.AuthServiceImpl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import tools.jackson.databind.json.JsonMapper;

class ResultCompatibilityTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void existingSuccessAndConstructorKeepTwoFieldJson() throws Exception {
    assertEquals(
        "{\"message\":\"success\",\"data\":null}", json.writeValueAsString(Result.success()));
    assertEquals(
        "{\"message\":\"success\",\"data\":7}", json.writeValueAsString(Result.success(7)));
    assertEquals(
        "{\"message\":\"custom\",\"data\":null}",
        json.writeValueAsString(new Result<>("custom", null)));
  }

  @Test
  void existingFailureFactoryKeepsTwoFieldJson() throws Exception {
    assertEquals(
        "{\"message\":\"failed\",\"data\":null}",
        json.writeValueAsString(Result.failure("failed")));
  }

  @Test
  void authErrorUsesSharedEnvelopeWithoutChangingWireContract() throws Exception {
    var tree = json.readTree(json.writeValueAsString(ApiError.invalid("email", "邮箱格式无效").body()));
    assertTrue(tree.get("data").isNull());
    assertEquals("INVALID_REQUEST", tree.get("error").get("code").asText());
    assertEquals("email", tree.get("error").get("fields").get(0).get("field").asText());
    var success =
        json.readTree(json.writeValueAsString(Result.success("registered", Map.of("id", 1))));
    assertFalse(success.has("error"));
  }

  @Test
  void everyAuthEndpointDeclaresSharedResult() {
    int endpoints = 0;
    for (var method : AuthController.class.getDeclaredMethods()) {
      if (method.isAnnotationPresent(GetMapping.class)
          || method.isAnnotationPresent(PostMapping.class)) {
        assertEquals(Result.class, method.getReturnType(), method.getName());
        endpoints++;
      }
    }
    assertEquals(8, endpoints);
  }

  @Test
  void authenticationUsesInterfaceAndSeparateImplementation() {
    assertTrue(AuthService.class.isInterface());
    assertTrue(AuthService.class.isAssignableFrom(AuthServiceImpl.class));
    assertNotEquals(AuthService.class, AuthServiceImpl.class);
  }
}
