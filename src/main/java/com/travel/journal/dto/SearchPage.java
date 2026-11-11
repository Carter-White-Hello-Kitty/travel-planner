// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索结果与分页信息的数据结构。
package com.travel.journal.dto;

import java.util.List;

public record SearchPage(List<TravelPostView> items, Pagination pagination) {
  public record Pagination(int page, int pageSize, long totalItems, long totalPages) {}
}
