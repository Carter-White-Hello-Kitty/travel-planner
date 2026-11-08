// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索、索引同步或集成验证测试。
package com.travel.journal;

import com.travel.auth.entity.User;
import com.travel.itinerary.entity.Trip;
import com.travel.journal.entity.TravelPost;
import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.search.*;
import com.travel.journal.service.TravelPostIndexService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelPostIndexTest {
  @Test void failedWriteLeavesJobForRetryAndSuccessfulWriteAcknowledgesRevision() {
    var jdbc=mock(JdbcTemplate.class);
    var posts=mock(TravelPostRepository.class);
    var es=mock(ElasticsearchGateway.class);
    var index=new TravelPostIndexService(jdbc,posts,es);
    User user=new User();user.setId(1L);
    Trip trip=new Trip();trip.setId(12L);trip.setUser(user);trip.setStatus("Completed");
    TravelPost post=new TravelPost();post.setId(101L);post.setTrip(trip);post.setContent("海边");
    when(posts.findById(101L)).thenReturn(Optional.of(post));
    doThrow(new JournalSearchException("offline")).doNothing().when(es).upsert(101L,12L,1L,"海边");
    assertThrows(JournalSearchException.class,()->index.process(101L,7));
    verifyNoInteractions(jdbc);
    index.process(101L,7);
    verify(jdbc).update("DELETE FROM journal_search_jobs WHERE post_id=? AND revision=?",101L,7L);
  }

  @Test void deletedPostRemovesSearchDocument() {
    var jdbc=mock(JdbcTemplate.class);
    var posts=mock(TravelPostRepository.class);
    var es=mock(ElasticsearchGateway.class);
    when(posts.findById(101L)).thenReturn(Optional.empty());
    new TravelPostIndexService(jdbc,posts,es).process(101L,1);
    verify(es).delete(101L);
    verify(jdbc).update("DELETE FROM journal_search_jobs WHERE post_id=? AND revision=?",101L,1L);
  }
}
