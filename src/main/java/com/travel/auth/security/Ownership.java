package com.travel.auth.security;

import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import org.springframework.http.HttpStatus;

/** Shared authorization guard; business repositories must still load the actual owner. */
public final class Ownership {
  private Ownership() {}

  public static void requireOwner(TokenService.Identity identity, Long ownerId) {
    if (identity == null)
      throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
    if (!identity.userId().equals(ownerId))
      throw new ApiError(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed to access another user's resource");
  }
}
