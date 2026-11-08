package com.travel.wishlist.service.impl;

import com.travel.auth.repository.UserRepository;
import com.travel.common.ApiError;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.vo.PoiVO;
import com.travel.wishlist.entity.Wishlist;
import com.travel.wishlist.repository.WishlistRepository;
import com.travel.wishlist.service.WishlistService;
import com.travel.wishlist.vo.WishlistAddedVO;
import com.travel.wishlist.vo.WishlistItemVO;
import com.travel.wishlist.vo.WishlistPageVO;
import com.travel.wishlist.vo.WishlistRemovedVO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WishlistServiceImpl implements WishlistService {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final WishlistRepository wishlist;
    private final UserRepository users;
    private final GooglePlacesClient places;
    private final Clock clock;

    public WishlistServiceImpl(
            WishlistRepository wishlist,
            UserRepository users,
            GooglePlacesClient places,
            Clock clock) {
        this.wishlist = wishlist;
        this.users = users;
        this.places = places;
        this.clock = clock;
    }

    @Override
    public WishlistPageVO list(Long userId, int page, int pageSize, boolean ascending) {
        Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable =
                PageRequest.of(
                        page - 1,
                        pageSize,
                        Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));
        Page<Wishlist> found = wishlist.findByUserId(userId, pageable);
        // One Places lookup per distinct place per page, mirroring the itinerary display path.
        Map<String, PoiVO> cache = new HashMap<>();
        List<WishlistItemVO> items =
                found.getContent().stream().map(item -> view(item, cache)).toList();
        return new WishlistPageVO(
                items,
                new WishlistPageVO.Pagination(
                        page, pageSize, found.getTotalElements(), found.getTotalPages()));
    }

    @Override
    @Transactional
    public WishlistAddedVO add(Long userId, String googlePlaceId) {
        PoiVO poi = requiredPlace(googlePlaceId);
        if (wishlist.existsByUserIdAndGooglePlaceId(userId, googlePlaceId))
            throw alreadyExists(googlePlaceId);

        Wishlist item = new Wishlist();
        item.setUser(users.getReferenceById(userId));
        item.setGooglePlaceId(poi.googlePlaceId());
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), UTC).withNano(0);
        item.setCreatedAt(createdAt);
        try {
            // Flush inside the boundary so the unique key can reject concurrent duplicates.
            wishlist.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            throw alreadyExists(googlePlaceId);
        }
        return new WishlistAddedVO(poi.googlePlaceId(), createdAt.toInstant(UTC));
    }

    @Override
    @Transactional
    public WishlistRemovedVO remove(Long userId, String googlePlaceId) {
        Wishlist item =
                wishlist
                        .findByUserIdAndGooglePlaceId(userId, googlePlaceId)
                        .orElseThrow(
                                () ->
                                        new ApiError(
                                                HttpStatus.NOT_FOUND,
                                                "WISHLIST_ITEM_NOT_FOUND",
                                                "Wishlist item not found",
                                                List.of(
                                                        Map.of(
                                                                "field",
                                                                "googlePlaceId",
                                                                "reason",
                                                                "The specified POI is not in the current user's wishlist"))));
        wishlist.delete(item);
        return new WishlistRemovedVO(googlePlaceId);
    }

    /** Validates the place through Google, so the client cannot persist invented IDs. */
    private PoiVO requiredPlace(String googlePlaceId) {
        try {
            PoiVO poi = places.details(googlePlaceId);
            if (poi == null || !googlePlaceId.equals(poi.googlePlaceId())) throw upstream();
            return poi;
        } catch (ApiError e) {
            if (e.status == HttpStatus.BAD_REQUEST) throw notFound(googlePlaceId);
            throw e;
        }
    }

    private WishlistItemVO view(Wishlist item, Map<String, PoiVO> cache) {
        PoiVO details;
        try {
            details = cache.computeIfAbsent(item.getGooglePlaceId(), places::details);
            if (details == null || !item.getGooglePlaceId().equals(details.googlePlaceId()))
                throw upstream();
        } catch (ApiError e) {
            // A place removed from Google must not break the whole wishlist page.
            if (e.status == HttpStatus.BAD_REQUEST) details = null;
            else throw e;
        }
        return new WishlistItemVO(
                item.getGooglePlaceId(),
                details == null ? null : details.name(),
                details == null ? null : details.address(),
                details == null ? null : details.latitude(),
                details == null ? null : details.longitude(),
                details == null ? null : details.category(),
                details == null ? null : details.rating(),
                item.getCreatedAt().toInstant(UTC));
    }

    private static ApiError alreadyExists(String googlePlaceId) {
        return new ApiError(
                HttpStatus.CONFLICT,
                "WISHLIST_ITEM_ALREADY_EXISTS",
                "This POI is already in the wishlist",
                List.of(
                        Map.of(
                                "field", "googlePlaceId",
                                "reason", "The current user has already wishlisted this POI")));
    }

    private static ApiError notFound(String googlePlaceId) {
        return new ApiError(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "POI not found",
                List.of(
                        Map.of(
                                "field", "googlePlaceId",
                                "reason", "No place exists with this Google Place ID")));
    }

    private static ApiError upstream() {
        return new ApiError(
                HttpStatus.BAD_GATEWAY,
                "POI_SEARCH_UPSTREAM_ERROR",
                "POI search provider is temporarily unavailable");
    }
}