package com.travel.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BoundaryFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final ApiSupport api;
  private final Set<String> origins;
  static final Set<String> PUBLIC =
      Set.of(
          "/auth/register",
          "/auth/login",
          "/auth/request-reset-pwd",
          "/auth/reset-pwd",
          "/auth/logout",
          "/auth/refresh",
          "/auth/csrf");

  public BoundaryFilter(AuthService a, ApiSupport p, Set<String> o) {
    auth = a;
    api = p;
    origins = o;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    try {
      String path = req.getRequestURI().substring(req.getContextPath().length());
      res.setHeader("Cache-Control", "no-store");
      if (path.startsWith("/auth/") && req.getMethod().equals("POST")) {
        if (!origins.contains(req.getHeader("Origin"))
            || !"XMLHttpRequest".equals(req.getHeader("X-Requested-With")))
          throw new ApiError(403, "CSRF_REJECTED", "请求来源或请求头无效");
        String type = req.getContentType();
        if (type == null || !type.split(";")[0].trim().equalsIgnoreCase("application/json"))
          throw new ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须为JSON");
        byte[] bytes = req.getInputStream().readNBytes(8193);
        if (bytes.length > 8192) throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
        req = new BufferedRequest(req, bytes);
      }
      if (!PUBLIC.contains(path) && !req.getMethod().equals("OPTIONS")) {
        String bearer = req.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer "))
          throw new ApiError(401, "AUTH_REQUIRED", "请先登录");
        if (bearer.length() > 8192) throw new ApiError(401, "ACCESS_TOKEN_INVALID", "登录凭据无效");
        TokenService.Identity id = auth.authenticate(bearer.substring(7));
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(id, null, List.of()));
      }
      chain.doFilter(req, res);
    } catch (ApiError e) {
      api.write(res, e);
    } catch (DataAccessException e) {
      api.write(res, new ApiError(503, "SERVICE_UNAVAILABLE", "服务暂时不可用"));
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
