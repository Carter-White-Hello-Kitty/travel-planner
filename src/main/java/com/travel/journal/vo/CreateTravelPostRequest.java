package com.travel.journal.vo;

import java.util.List;


public record CreateTravelPostRequest(Long trip_id, String content, List<String> photos) {
}
