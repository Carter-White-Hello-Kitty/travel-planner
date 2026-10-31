package com.travel.common;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ApiSupport api;

  public GlobalExceptionHandler(ApiSupport api) {
    this.api = api;
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
    log.error("Unhandled exception", e);
    api.write(r, new ApiError(500, "INTERNAL_ERROR", "服务器处理失败"));
  }
}
