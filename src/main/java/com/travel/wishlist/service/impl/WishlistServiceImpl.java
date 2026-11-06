package com.travel.wishlist.service.impl;

import com.travel.auth.repository.UserRepository;
import com.travel.common.ApiError;
import com.travel.poisearch.entity.Poi;
import com.travel.poisearch.repository.PoiRepository;
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
    private final PoiRepository pois;
    private final UserRepository users;
    private final Clock clock;

    public WishlistServiceImpl(
            WishlistRepository wishlist, PoiRepository pois, UserRepository users, Clock clock) {
        this.wishlist = wishlist;
        this.pois = pois;
        this.users = users;
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
        List<WishlistItemVO> items =
                found.getContent().stream().map(WishlistServiceImpl::view).toList();
        return new WishlistPageVO(
                items,
                new WishlistPageVO.Pagination(
                        page, pageSize, found.getTotalElements(), found.getTotalPages()));
    }

    @Override
    @Transactional
    public WishlistAddedVO add(Long userId, Long poiId) {
        Poi poi =
                pois.findById(poiId)
                        .orElseThrow(
                                () ->
                                        new ApiError(
                                                HttpStatus.NOT_FOUND,
                                                "RESOURCE_NOT_FOUND",
                                                "POI not found",
                                                List.of(Map.of("field", "poiId", "reason", "No POI exists with this id"))));
        if (wishlist.existsByUserIdAndPoiId(userId, poiId)) throw alreadyExists(poiId);

        Wishlist item = new Wishlist();
        item.setUser(users.getReferenceById(userId));
        item.setPoi(poi);
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), UTC).withNano(0);
        item.setCreatedAt(createdAt);
        try {
            // Flush inside the boundary so the unique key can reject concurrent duplicates.
            wishlist.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            throw alreadyExists(poiId);
        }
        return new WishlistAddedVO(poi.getId(), createdAt.toInstant(UTC));
    }

    @Override
    @Transactional
    public WishlistRemovedVO remove(Long userId, Long poiId) {
        Wishlist item =
                wishlist
                        .findByUserIdAndPoiId(userId, poiId)
                        .orElseThrow(
                                () ->
                                        new ApiError(
                                                HttpStatus.NOT_FOUND,
                                                "WISHLIST_ITEM_NOT_FOUND",
                                                "Wishlist item not found",
                                                List.of(
                                                        Map.of(
                                                                "field",
                                                                "poiId",
                                                                "reason",
                                                                "The specified POI is not in the current user's wishlist"))));
        wishlist.delete(item);
        return new WishlistRemovedVO(poiId);
    }

    private static WishlistItemVO view(Wishlist item) {
        Poi poi = item.getPoi();
        return new WishlistItemVO(
                poi.getId(),
                poi.getGooglePlaceId(),
                item.getCreatedAt().toInstant(UTC));
    }

    private static ApiError alreadyExists(Long poiId) {
        return new ApiError(
                HttpStatus.CONFLICT,
                "WISHLIST_ITEM_ALREADY_EXISTS",
                "This POI is already in the wishlist",
                List.of(
                        Map.of(
                                "field", "poiId",
                                "reason", "The current user has already wishlisted this POI")));
    }
}