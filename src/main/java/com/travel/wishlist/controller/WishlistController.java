package com.travel.wishlist.controller;

import com.travel.auth.service.TokenService;
import com.travel.auth.support.RateLimiter;
import com.travel.common.ApiError;
import com.travel.common.ApiSupport;
import com.travel.common.Result;
import com.travel.wishlist.constant.WishlistConstants;
import com.travel.wishlist.service.WishlistService;
import com.travel.wishlist.vo.WishlistAddedVO;
import com.travel.wishlist.vo.WishlistPageVO;
import com.travel.wishlist.vo.WishlistRemovedVO;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wishlist")
public class WishlistController {

    /** Only the sort field documented today; extend when more ordering is specified. */
    private static final Set<String> SORT_FIELDS = Set.of("createdAt");

    private final WishlistService wishlist;
    private final RateLimiter limits;
    private final ApiSupport api;

    public WishlistController(WishlistService wishlist, RateLimiter limits, ApiSupport api) {
        this.wishlist = wishlist;
        this.limits = limits;
        this.api = api;
    }

    @GetMapping
    public Result<WishlistPageVO> list(
            @AuthenticationPrincipal TokenService.Identity identity,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        Long userId = user(identity);
        limits.hit("wishlist:list:" + userId, WishlistConstants.LIST_LIMIT, WishlistConstants.LIST_WINDOW);

        int number = number(page, "page", 1);
        int size = number(pageSize, "pageSize", WishlistConstants.DEFAULT_PAGE_SIZE);
        if (size > WishlistConstants.MAX_PAGE_SIZE)
            throw ApiError.invalid("pageSize", "Must be at most " + WishlistConstants.MAX_PAGE_SIZE);
        String field = sort == null || sort.isBlank() ? "createdAt" : sort;
        if (!SORT_FIELDS.contains(field)) throw ApiError.invalid("sort", "Unsupported sort field");
        boolean ascending = ascending(order);
        return api.ok(
                "Wishlist retrieved successfully", wishlist.list(userId, number, size, ascending));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<WishlistAddedVO> add(
            @AuthenticationPrincipal TokenService.Identity identity,
            @RequestBody Map<String, Object> body) {
        Long userId = user(identity);
        limits.hit("wishlist:add:" + userId, WishlistConstants.WRITE_LIMIT, WishlistConstants.WRITE_WINDOW);
        keys(body, "poiId");
        return api.ok(
                "POI added to wishlist successfully", wishlist.add(userId, poiId(body.get("poiId"))));
    }

    @DeleteMapping("/{poiId}")
    public Result<WishlistRemovedVO> remove(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable String poiId) {
        Long userId = user(identity);
        limits.hit(
                "wishlist:remove:" + userId, WishlistConstants.WRITE_LIMIT, WishlistConstants.WRITE_WINDOW);
        return api.ok("POI removed from wishlist successfully", wishlist.remove(userId, pathPoiId(poiId)));
    }

    private static Long user(TokenService.Identity identity) {
        if (identity == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        return identity.userId();
    }

    private static void keys(Map<String, Object> body, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String key : body.keySet()) if (!names.contains(key)) throw ApiError.invalid(key, "Unknown field");
    }

    private static Long poiId(Object raw) {
        if (!(raw instanceof Number number)) throw ApiError.invalid("poiId", "Required integer");
        long value = number.longValue();
        if (value <= 0 || number.doubleValue() != (double) value)
            throw ApiError.invalid("poiId", "Must be a positive integer");
        return value;
    }

    private static Long pathPoiId(String raw) {
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiError.invalid("poiId", "Must be a positive integer");
        }
        if (value <= 0) throw ApiError.invalid("poiId", "Must be a positive integer");
        return value;
    }

    private static int number(String raw, String field, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiError.invalid(field, "Must be a positive integer");
        }
        if (value < 1) throw ApiError.invalid(field, "Must be a positive integer");
        return value;
    }

    private static boolean ascending(String order) {
        if (order == null || order.isBlank() || order.equalsIgnoreCase("desc")) return false;
        if (order.equalsIgnoreCase("asc")) return true;
        throw ApiError.invalid("order", "Must be asc or desc");
    }
}