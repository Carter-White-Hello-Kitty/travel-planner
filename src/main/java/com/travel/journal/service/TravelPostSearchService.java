// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索业务接口定义。
package com.travel.journal.service;

import com.travel.journal.dto.SearchPage;

public interface TravelPostSearchService {
  SearchPage search(Long currentUserId, String keyword, int page, int pageSize);
}
