// Sida Xue — Travel Journal Search
// 本次搜索模块新增：与发布模块衔接：登记持久化索引任务，同步 MySQL 帖子到 ES。
package com.travel.journal.service;

import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.search.ElasticsearchGateway;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Publisher must enqueue in the SAME transaction as saving the post. */
@Service
public class TravelPostIndexService {
  private final JdbcTemplate jdbc;
  private final TravelPostRepository posts;
  private final ElasticsearchGateway es;

  public TravelPostIndexService(JdbcTemplate jdbc, TravelPostRepository posts, ElasticsearchGateway es) {
    this.jdbc = jdbc; this.posts = posts; this.es = es;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(Long postId) {
    jdbc.update("INSERT INTO journal_search_jobs(post_id,revision) VALUES (?,1) "
        + "ON DUPLICATE KEY UPDATE revision=revision+1", postId);
  }

  /** One transaction per page, never load the entire posts table into memory. */
  @Transactional
  public boolean enqueuePage(int page) {
    var rows = posts.findAll(PageRequest.of(page, 100, Sort.by("id")));
    for (var post : rows) enqueue(post.getId());
    return rows.hasNext();
  }

  @Transactional
  public void process(Long postId, long revision) {
    var post = posts.findById(postId).orElse(null);
    if (post == null || (post.getTrip().getStatus() == null || !post.getTrip().getStatus().isPublishable())) {
      es.delete(postId);
    } else {
      es.upsert(post.getId(), post.getTrip().getId(), post.getTrip().getUser().getId(), post.getContent());
    }
    // A concurrent publish/update changes revision: preserve its new job instead of losing it.
    jdbc.update("DELETE FROM journal_search_jobs WHERE post_id=? AND revision=?", postId, revision);
  }
}
