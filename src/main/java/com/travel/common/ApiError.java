package com.travel.common;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
  public final HttpStatus status;
  public final String code;
  final List<Map<String, String>> fields;
  public int retryAfter;
  boolean clearCookie;

  public ApiError(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiError(HttpStatus status, String code, String message, List<Map<String, String>> fields) {
    super(message);
    this.status = status;
    this.code = code;
    this.fields = fields;
  }

  public static ApiError invalid(String field, String reason) {
    return new ApiError(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request",
        List.of(Map.of("field", field, "reason", reason)));
  }

  public Result<Void> body() {
    return Result.failure(getMessage(), code, fields);
  }

  public ApiError clearing() {
    clearCookie = true;
    return this;
  }
}
