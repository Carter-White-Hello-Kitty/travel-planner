package com.travel.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.travel.auth")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ErrorHandler {
  final ApiSupport api;

  public ErrorHandler(ApiSupport a) {
    api = a;
  }

  @ExceptionHandler(ApiError.class)
  public void api(ApiError e, HttpServletResponse r) throws java.io.IOException {
    api.write(r, e);
  }

  @ExceptionHandler(DataAccessException.class)
  public void database(Exception e, HttpServletResponse r) throws java.io.IOException {
    api.write(r, new ApiError(503, "SERVICE_UNAVAILABLE", "服务暂时不可用"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public void json(Exception e, HttpServletResponse r) throws java.io.IOException {
    api.write(r, new ApiError(400, "INVALID_REQUEST", "JSON请求无效"));
  }

  @ExceptionHandler(Exception.class)
  public void unexpected(Exception e, HttpServletResponse r) throws java.io.IOException {
    api.write(r, new ApiError(500, "INTERNAL_ERROR", "服务器处理失败"));
  }
}
