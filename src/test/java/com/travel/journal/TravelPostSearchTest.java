// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索、索引同步或集成验证测试。
package com.travel.journal;

import com.travel.auth.entity.User;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripStatus;
import com.travel.journal.controller.TravelPostSearchController;
import com.travel.journal.entity.TravelPost;
import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.search.*;
import com.travel.journal.service.impl.TravelPostSearchServiceImpl;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelPostSearchTest {
  private final ElasticsearchGateway es = mock(ElasticsearchGateway.class);
  private final TravelPostRepository posts = mock(TravelPostRepository.class);
  private final TravelPostSearchServiceImpl service = new TravelPostSearchServiceImpl(es, posts);
  private final JsonMapper json = JsonMapper.builder().build();

  private TravelPost post(long id, long owner, String status, String photos) {
    User user = new User(); user.setId(owner);
    Trip trip = new Trip(); trip.setId(12L); trip.setUser(user); trip.setStatus(TripStatus.from(status));
    TravelPost post = new TravelPost(); post.setId(id); post.setTrip(trip);
    post.setContent("海边"); post.setPhotos(photos); return post;
  }

  @Test void sharedSearchReturnsOtherAuthorsAndPreservesRanking() {
    when(es.search(anyMap())).thenReturn(json.readTree("""
      {"hits":{"total":{"value":3},"hits":[
        {"_source":{"id":103}},{"_source":{"id":102}},{"_source":{"id":101}}]}}
      """));
    when(posts.findAllById(any())).thenReturn(List.of(
        post(101,1,"Completed","a.jpg,b.jpg"), post(102,2,"Completed",""),
        post(103,1,"Cancelled","[\"c.jpg\"]")));
    var result = service.search(1L," 海边 ",1,20);
    assertEquals(List.of(103L,102L,101L), result.items().stream().map(p -> p.id()).toList());
    assertEquals(List.of("c.jpg"), result.items().get(0).photos());
    assertEquals(List.of("a.jpg","b.jpg"), result.items().get(2).photos());
    verify(es).search(argThat(query -> {
      String body = json.writeValueAsString(query);
      return !body.contains("user_id") && body.contains("\"content\":\"海边\"");
    }));
    String response = json.writeValueAsString(result);
    assertTrue(response.contains("trip_id")); assertFalse(response.contains("user_id"));
  }

  @Test void blankKeywordAlsoListsOtherAuthors() {
    when(es.search(anyMap())).thenReturn(json.readTree("""
      {"hits":{"total":{"value":1},"hits":[{"_source":{"id":102}}]}}
      """));
    when(posts.findAllById(any())).thenReturn(List.of(post(102,2,"Completed","")));
    assertEquals(102L, service.search(1L," ",1,20).items().get(0).id());
    verify(es).search(argThat(q -> {
      String body = json.writeValueAsString(q);
      return body.contains("match_all") && !body.contains("user_id");
    }));
  }

  @Test void invalidParametersNeverReachEs() {
    for (int[] args : List.of(new int[]{0,20},new int[]{1,101},new int[]{Integer.MAX_VALUE,100}))
      assertEquals(HttpStatus.BAD_REQUEST,
          assertThrows(ApiError.class, () -> service.search(1L,"x",args[0],args[1])).status);
    assertThrows(ApiError.class, () -> service.search(1L,"x".repeat(201),1,20));
    verifyNoInteractions(es);
  }

  @Test void emptySearchReturnsEmptyPage() {
    when(es.search(anyMap())).thenReturn(json.readTree("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}"));
    when(posts.findAllById(any())).thenReturn(List.of());
    var result = service.search(1L,"   ",1,20);
    assertTrue(result.items().isEmpty()); assertEquals(0,result.pagination().totalPages());
    verify(es).search(argThat(q -> json.writeValueAsString(q).contains("match_all")));
  }

  @Test void esOutageReturns503NotEmptySuccess() {
    when(es.search(anyMap())).thenThrow(new JournalSearchException("offline"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
        assertThrows(ApiError.class, () -> service.search(1L,"海边",1,20)).status);
  }

  @Test void controllerRequiresIdentityAndRejectsNonNumericPagination() {
    var controller = new TravelPostSearchController(service);
    assertEquals(HttpStatus.UNAUTHORIZED,
        assertThrows(ApiError.class, () -> controller.search(null,"","1","20")).status);
    assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiError.class,
        () -> controller.search(new TokenService.Identity(1L,UUID.randomUUID()),"","abc","20")).status);
  }

  @Test void unfinishedAndDeletedPostsAreNotReturned() {
    when(es.search(anyMap())).thenReturn(json.readTree("""
      {"hits":{"total":{"value":2},"hits":[{"_source":{"id":101}},{"_source":{"id":999}}]}}
      """));
    when(posts.findAllById(any())).thenReturn(List.of(post(101,1,"Ongoing","")));
    assertTrue(service.search(1L,"x",1,20).items().isEmpty());
  }
}
