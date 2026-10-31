package com.travel.auth.security;

import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;

/** Shared authorization guard; business repositories must still load the actual owner. */
public final class Ownership {
  private Ownership() {}

  public static void requireOwner(TokenService.Identity identity, Long ownerId) {
    if (identity == null) throw new ApiError(401, "AUTH_REQUIRED", "请先登录");
    if (!identity.userId().equals(ownerId))
      throw new ApiError(403, "FORBIDDEN", "无权操作其他用户的资源");
  }
}
