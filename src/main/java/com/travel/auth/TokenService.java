package com.travel.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final Secrets secrets;
  private final Clock clock;

  public TokenService(Secrets s, Clock c) {
    secrets = s;
    clock = c;
  }

  public record Identity(Long userId, UUID sessionId) {}

  public record Access(String value, long seconds) {}

  public Access issue(Long user, UUID sid, Instant expiry) {
    Instant now = clock.instant();
    long n = Math.min(900, Duration.between(now, expiry).toSeconds());
    if (n < 1) throw new ApiError(401, "REFRESH_TOKEN_INVALID_OR_EXPIRED", "会话已过期").clearing();
    try {
      SignedJWT j =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).build(),
              new JWTClaimsSet.Builder()
                  .subject(user.toString())
                  .claim("sid", sid.toString())
                  .issuer("travel-planner")
                  .audience("travel-planner-api")
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plusSeconds(n)))
                  .jwtID(UUID.randomUUID().toString())
                  .build());
      j.sign(new RSASSASigner(secrets.privateKey));
      return new Access(j.serialize(), n);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public Identity verify(String token) {
    try {
      SignedJWT j = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(j.getHeader().getAlgorithm())
          || !new JOSEObjectType("at+jwt").equals(j.getHeader().getType())
          || !j.verify(new RSASSAVerifier(secrets.publicKey))) throw new IllegalArgumentException();
      JWTClaimsSet c = j.getJWTClaimsSet();
      if (!"travel-planner".equals(c.getIssuer())
          || !c.getAudience().contains("travel-planner-api")
          || c.getIssueTime() == null
          || c.getExpirationTime() == null
          || c.getJWTID() == null
          || c.getIssueTime().toInstant().isAfter(clock.instant().plusSeconds(30)))
        throw new IllegalArgumentException();
      Identity id =
          new Identity(Long.valueOf(c.getSubject()), UUID.fromString(c.getStringClaim("sid")));
      if (id.userId() <= 0) throw new IllegalArgumentException();
      if (!c.getExpirationTime().toInstant().isAfter(clock.instant()))
        throw new ApiError(401, "ACCESS_TOKEN_EXPIRED", "登录凭据已过期");
      return id;
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(401, "ACCESS_TOKEN_INVALID", "登录凭据无效");
    }
  }
}
