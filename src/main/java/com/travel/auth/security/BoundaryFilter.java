package com.travel.auth.security;

import com.travel.auth.constant.AuthConstants;
import com.travel.auth.service.AuthService;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.common.ApiSupport;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;

import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BoundaryFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final ApiSupport api;
  private final Set<String> origins;
  static final Set<String> PUBLIC = buildPublicPaths();

  private static Set<String> buildPublicPaths() {
    Set<String> paths = new HashSet<>(AuthConstants.PUBLIC_POST_PATHS);
    paths.add(AuthConstants.CSRF_PATH);
    return Set.copyOf(paths);
  }

  public BoundaryFilter(AuthService a, ApiSupport p, Set<String> o) {
    auth = a;
    api = p;
    origins = o;
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest req, HttpServletResponse res, @NonNull FilterChain chain)
      throws IOException, ServletException {
    try {
      String path = req.getRequestURI().substring(req.getContextPath().length());
      res.setHeader("Cache-Control", "no-store");
      if (path.startsWith("/auth/") && req.getMethod().equals("POST")) {
        if (!origins.contains(req.getHeader("Origin"))
            || !"XMLHttpRequest".equals(req.getHeader("X-Requested-With")))
          throw new ApiError(HttpStatus.FORBIDDEN, "CSRF_REJECTED", "Invalid request origin or header");
        String type = req.getContentType();
        if (type == null || !type.split(";")[0].trim().equalsIgnoreCase("application/json"))
          throw new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Request must be JSON");
        byte[] bytes = req.getInputStream().readNBytes(AuthConstants.REQUEST_BODY_MAX_BYTES + 1);
        if (bytes.length > AuthConstants.REQUEST_BODY_MAX_BYTES)
          throw new ApiError(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body too large");
        req = new BufferedRequest(req, bytes);
      }
      if (!PUBLIC.contains(path) && !req.getMethod().equals("OPTIONS")) {
        String bearer = req.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer "))
          throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        if (bearer.length() > AuthConstants.REQUEST_BODY_MAX_BYTES)
          throw new ApiError(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID", "Invalid access token");
        TokenService.Identity id = auth.authenticate(bearer.substring(7));
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(id, null, List.of()));
      }
      chain.doFilter(req, res);
    } catch (ApiError e) {
      api.write(res, e);
    } catch (DataAccessException e) {
      api.write(res, new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service temporarily unavailable"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  static class BufferedRequest extends HttpServletRequestWrapper {
    final byte[] bytes;

    BufferedRequest(HttpServletRequest req, byte[] b) {
      super(req);
      bytes = b;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        public int read() {
          return in.read();
        }

        public boolean isFinished() {
          return in.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener l) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
