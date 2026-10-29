package com.travel.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Shared response envelope. The optional error component preserves existing two-field JSON. */
public record Result<T>(
    String message, T data, @JsonInclude(JsonInclude.Include.NON_NULL) ErrorDetail error) {

  public record ErrorDetail(String code, List<Map<String, String>> fields) {}

  /** Source-compatible constructor for existing modules. */
  public Result(String message, T data) {
    this(message, data, null);
  }

  public static <T> Result<T> success(T data) {
    return new Result<>("success", data);
  }

  public static <T> Result<T> success(String message, T data) {
    return new Result<>(message, data);
  }

  public static Result<Void> success() {
    return new Result<>("success", null);
  }

  public static Result<Void> failure(String message) {
    return new Result<>(message, null);
  }

  public static Result<Void> failure(
      String message, String code, List<Map<String, String>> fields) {
    return new Result<>(message, null, new ErrorDetail(code, List.copyOf(fields)));
  }
}
