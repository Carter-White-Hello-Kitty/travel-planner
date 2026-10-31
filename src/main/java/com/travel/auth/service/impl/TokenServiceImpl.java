package com.travel.auth.service.impl;

import com.travel.auth.config.Secrets;
import com.travel.auth.constant.AuthConstants;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TokenServiceImpl implements TokenService {
  private final Secrets secrets;
  private final Clock clock;

  public TokenServiceImpl(Secrets secrets, Clock clock) {
    this.secrets = secrets;
    this.clock = clock;
  }

  public Access issue(Long user, UUID sid, Instant expiry) {
    Instant now = clock.instant();
    long n = Math.min(AuthConstants.ACCESS_TOKEN_TTL_SECONDS, Duration.between(now, expiry).toSeconds());
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
          || c.getIssueTime().toInstant().isAfter(clock.instant().plusSeconds(AuthConstants.CLOCK_SKEW_SECONDS)))
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
