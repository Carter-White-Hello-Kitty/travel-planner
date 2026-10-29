package com.travel.auth;

import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
public class AuthConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  }

  @Bean
  org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Use the explicit auth API");
    };
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, AuthService auth, ApiSupport api, Environment env)
      throws Exception {
    Set<String> origins =
        new HashSet<>(
            Arrays.asList(env.getProperty("auth.origins", "http://localhost:3000").split(",")));
    if (origins.contains("*") || origins.contains("null"))
      throw new IllegalArgumentException("Explicit trusted origins are required");
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(new ArrayList<>(origins));
    cors.setAllowCredentials(true);
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of("Content-Type", "Accept", "Authorization", "X-Requested-With", "X-XSRF-TOKEN"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieCustomizer(c -> c.secure(api.secure).sameSite("Lax").path("/"));
    return http.cors(c -> c.configurationSource(source))
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers("/auth/csrf")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/auth/register",
                        "/auth/login",
                        "/auth/request-reset-pwd",
                        "/auth/reset-pwd",
                        "/auth/logout",
                        "/auth/refresh")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(new BoundaryFilter(auth, api, origins), CsrfFilter.class)
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (q, r, e) -> api.write(r, new ApiError(401, "AUTH_REQUIRED", "请先登录")))
                    .accessDeniedHandler(
                        (q, r, e) ->
                            api.write(
                                r,
                                new ApiError(
                                    403,
                                    e instanceof CsrfException ? "CSRF_REJECTED" : "FORBIDDEN",
                                    "请求未获授权"))))
        .build();
  }
}
