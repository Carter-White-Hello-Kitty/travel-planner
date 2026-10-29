package com.travel.auth;

import java.util.*;

public class ApiError extends RuntimeException {
  final int status;
  final String code;
  final List<Map<String, String>> fields;
  int retryAfter;
  boolean clearCookie;

  public ApiError(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiError(int status, String code, String message, List<Map<String, String>> fields) {
    super(message);
    this.status = status;
    this.code = code;
    this.fields = fields;
  }

  public static ApiError invalid(String field, String reason) {
    return new ApiError(
        400, "INVALID_REQUEST", "请求参数无效", List.of(Map.of("field", field, "reason", reason)));
  }

  public Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", getMessage());
    body.put("data", null);
    body.put("error", Map.of("code", code, "fields", fields));
    return body;
  }

  public ApiError clearing() {
    clearCookie = true;
    return this;
  }
}
