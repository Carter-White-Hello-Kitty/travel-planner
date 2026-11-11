// Sida Xue — Travel Journal Search
// 本次搜索模块新增：后台索引任务：历史数据补建、定时同步与失败重试。
package com.travel.journal.search;

import com.travel.journal.service.TravelPostIndexService;
import org.slf4j.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "journal.search.worker-enabled", havingValue = "true")
public class JournalIndexWorker implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(JournalIndexWorker.class);
  private final TravelPostIndexService index;
  private final JdbcTemplate jdbc;
  private int scanPage;

  public JournalIndexWorker(TravelPostIndexService index, JdbcTemplate jdbc) {
    this.index = index; this.jdbc = jdbc;
  }

  @Override public void run(ApplicationArguments args) { tick(); }

  @Scheduled(fixedDelayString = "${journal.search.sync-delay-ms:30000}")
  public synchronized void tick() {
    try {
      // Reconciliation also covers historical rows and publishers not yet calling enqueue.
      // Stable data is repeatedly reindexed so a deleted/recreated ES index can recover.
      if (!index.enqueuePage(scanPage++)) scanPage = 0;
      var jobs = jdbc.query("SELECT post_id,revision FROM journal_search_jobs ORDER BY post_id LIMIT 100",
          (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)});
      for (long[] job : jobs) {
        try { index.process(job[0], job[1]); }
        catch (Exception e) { log.warn("Journal indexing deferred for post {}: {}", job[0], e.getClass().getSimpleName()); }
      }
    } catch (Exception e) {
      scanPage = 0;
      log.warn("Journal reconciliation deferred: {}", e.getClass().getSimpleName());
    }
  }
}
