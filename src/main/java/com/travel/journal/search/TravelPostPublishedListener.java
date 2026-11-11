// Sida Xue — Travel Journal Search
// 与搭档的发布事件衔接：在发布事务提交前登记持久化搜索任务。
package com.travel.journal.search;

import com.travel.journal.service.TravelPostIndexService;
import com.travel.journal.service.TravelPostPublishService.TravelPostPublished;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TravelPostPublishedListener {
  private final TravelPostIndexService index;

  public TravelPostPublishedListener(TravelPostIndexService index) { this.index = index; }

  // Intentionally synchronous: enqueue joins the publisher transaction, so both commit or roll back.
  @EventListener
  public void onPublished(TravelPostPublished event) { index.enqueue(event.id()); }
}
