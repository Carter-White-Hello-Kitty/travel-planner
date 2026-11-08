// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索 API 入口：接收参数和当前登录用户，调用搜索服务。
package com.travel.journal.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.common.Result;
import com.travel.journal.dto.SearchPage;
import com.travel.journal.service.TravelPostSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/travel-posts")
public class TravelPostSearchController {
  private final TravelPostSearchService service;

  public TravelPostSearchController(TravelPostSearchService service) { this.service = service; }

  @GetMapping
  public Result<SearchPage> search(@AuthenticationPrincipal TokenService.Identity identity,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "1") String page,
      @RequestParam(defaultValue = "20") String pageSize) {
    if (identity == null)
      throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
    try {
      return Result.success("Search successful.",
          service.search(identity.userId(), keyword, Integer.parseInt(page), Integer.parseInt(pageSize)));
    } catch (NumberFormatException e) {
      throw ApiError.invalid("page", "page and pageSize must be integers");
    }
  }
}
