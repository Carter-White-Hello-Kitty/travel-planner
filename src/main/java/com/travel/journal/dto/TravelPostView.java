// Sida Xue — Travel Journal Search
// 本次搜索模块新增：返回前端的旅行帖子数据结构。
package com.travel.journal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TravelPostView(Long id, @JsonProperty("trip_id") Long tripId,
                             String content, List<String> photos) {}
