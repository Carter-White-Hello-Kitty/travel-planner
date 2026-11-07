package com.travel.journal.service;

import com.travel.journal.vo.CreateTravelPostRequest;
import java.util.List;


public interface TravelPostPublishService {
    Long publish(Long currentUserId, CreateTravelPostRequest request);


    record TravelPostPublished(Long id, Long trip_id, String content, List<String> photos) {
        public TravelPostPublished {
            photos = photos == null ? null : List.copyOf(photos);
        }
    }
}
