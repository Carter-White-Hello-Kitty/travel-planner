package com.travel.common;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
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
    api.write(r, new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service temporarily unavailable"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public void json(Exception e, HttpServletResponse r) throws java.io.IOException {
    api.write(r, new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid JSON request"));
  }

  @ExceptionHandler(Exception.class)
  public void unexpected(Exception e, HttpServletResponse r) throws java.io.IOException {
    log.error("Unhandled exception", e);
    api.write(r, new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error"));
  }
}
