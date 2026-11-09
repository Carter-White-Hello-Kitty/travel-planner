// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索业务实现：参数校验、权限过滤、ES 查询和结果转换。
package com.travel.journal.service.impl;

import com.travel.common.ApiError;
import com.travel.journal.dto.*;
import com.travel.journal.entity.TravelPost;
import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.search.*;
import com.travel.journal.service.TravelPostSearchService;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TravelPostSearchServiceImpl implements TravelPostSearchService {
  private final ElasticsearchGateway es;
  private final TravelPostRepository posts;
  private final JsonMapper json = JsonMapper.builder().build();

  public TravelPostSearchServiceImpl(ElasticsearchGateway es, TravelPostRepository posts) {
    this.es = es; this.posts = posts;
  }

  @Override
  @Transactional(readOnly = true)
  public SearchPage search(Long userId, String keyword, int page, int pageSize) {
    if (userId == null) throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
    String text = keyword == null ? "" : keyword.strip();
    if (text.codePointCount(0, text.length()) > 200 || page < 1 || pageSize < 1
        || pageSize > 100 || (long) page * pageSize > 10000)
      throw ApiError.invalid("search", "Invalid keyword or pagination parameters");
    Map<String, Object> fullText = text.isEmpty() ? Map.of("match_all", Map.of())
        : Map.of("match", Map.of("content", text));
    Map<String, Object> query = Map.of("query", fullText,
        "from", (page - 1) * pageSize, "size", pageSize, "track_total_hits", true,
        "sort", List.of(Map.of("_score", "desc"), Map.of("id", "desc")));
    JsonNode result;
    try { result = es.search(query); }
    catch (JournalSearchException e) {
      throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "Search service unavailable.");
    }
    List<Long> ids = new ArrayList<>();
    for (JsonNode hit : result.path("hits").path("hits"))
      ids.add(hit.path("_source").path("id").asLong());
    Map<Long, TravelPost> stored = posts.findAllById(ids).stream()
        .collect(Collectors.toMap(TravelPost::getId, Function.identity()));
    List<TravelPostView> items = new ArrayList<>();
    for (Long id : ids) {
      TravelPost post = stored.get(id);
      // Published journals are shared across users; recheck current DB state, not authorship.
      if (post == null || post.getTrip().getStatus() == null
          || !post.getTrip().getStatus().isPublishable()) continue;
      items.add(new TravelPostView(post.getId(), post.getTrip().getId(), post.getContent(), photos(post.getPhotos())));
    }
    long total = result.path("hits").path("total").path("value").asLong();
    return new SearchPage(List.copyOf(items), new SearchPage.Pagination(page, pageSize, total,
        (total + pageSize - 1) / pageSize));
  }

  private List<String> photos(String value) {
    if (value == null || value.isBlank()) return List.of();
    // Team schema currently specifies comma-separated TEXT; also accept a JSON array for migration.
    if (value.strip().startsWith("[")) {
      JsonNode array = json.readTree(value);
      if (!array.isArray()) throw new IllegalStateException("Invalid stored photos");
      List<String> values = new ArrayList<>();
      for (JsonNode item : array) {
        if (!item.isTextual()) throw new IllegalStateException("Invalid stored photo URL");
        values.add(item.asText());
      }
      return List.copyOf(values);
    }
    return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }
}
