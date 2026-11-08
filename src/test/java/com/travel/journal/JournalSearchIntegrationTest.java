// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索、索引同步或集成验证测试。
package com.travel.journal;

import com.travel.auth.service.AuthService;
import com.travel.journal.service.TravelPostIndexService;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Opt-in: real test MySQL + local ES. Never clears unrelated database rows or indexes. */
@SpringBootTest(properties={"auth.worker-enabled=false", "journal.search.worker-enabled=false"})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="journal.es.integration", matches="true")
class JournalSearchIntegrationTest {
  static final String INDEX="journal-java-test-"+UUID.randomUUID();
  static final String ES=System.getProperty("journal.integration.es-url","http://127.0.0.1:19200");
  @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
    r.add("journal.search.url",()->ES); r.add("journal.search.index",()->INDEX);
  }
  @Autowired AuthService auth;
  @Autowired JdbcTemplate db;
  @Autowired TravelPostIndexService indexing;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MockMvc mvc;
  final List<Long> users=new ArrayList<>();
  final List<Long> trips=new ArrayList<>();
  final List<Long> posts=new ArrayList<>();
  final JsonMapper json=JsonMapper.builder().build();

  long addPost(long owner,String state,String content) {
    String name="search-test-"+UUID.randomUUID();
    db.update("INSERT INTO trips(user_id,name,status,day_count) VALUES(?,?,?,1)",owner,name,state);
    long trip=db.queryForObject("SELECT id FROM trips WHERE name=?",Long.class,name); trips.add(trip);
    db.update("INSERT INTO travel_posts(trip_id,content,photos) VALUES(?,?,'[]')",trip,content);
    long post=db.queryForObject("SELECT id FROM travel_posts WHERE trip_id=?",Long.class,trip); posts.add(post);
    return post;
  }

  @Test void realAuthenticationDatabaseQueueAndElasticsearchWorkTogether() throws Exception {
    assertEquals("travel_planner_auth_test",db.queryForObject("SELECT DATABASE()",String.class));
    String email="journal-"+UUID.randomUUID()+"@example.com";
    long owner=auth.register(email,"TravelPlanner!2026").getId(); users.add(owner);
    long other=auth.register("other-"+UUID.randomUUID()+"@example.com","TravelPlanner!2026").getId(); users.add(other);
    String token=auth.login(email,"TravelPlanner!2026",null).access().value();
    long beach=addPost(owner,"Completed","海边散步");
    addPost(owner,"Cancelled","海边日落");
    addPost(other,"Completed","海边 private-other-user");
    addPost(owner,"Ongoing","海边 unfinished");
    var tx=new TransactionTemplate(transactions);
    tx.executeWithoutResult(status->{indexing.enqueue(beach);status.setRollbackOnly();});
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM journal_search_jobs WHERE post_id=?",Integer.class,beach));
    for(long post:posts) tx.executeWithoutResult(status->indexing.enqueue(post));
    for(long post:posts) {
      long revision=db.queryForObject("SELECT revision FROM journal_search_jobs WHERE post_id=?",Long.class,post);
      indexing.process(post,revision);
    }
    assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ES+"/"+INDEX+"/_refresh"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    var response=mvc.perform(get("/travel-posts").param("keyword","海边")
        .header("Authorization","Bearer "+token)).andReturn().getResponse();
    assertEquals(200,response.getStatus(),response.getContentAsString());
    var body=json.readTree(response.getContentAsString());
    assertEquals(2,body.path("data").path("items").size());
    assertEquals(2,body.path("data").path("pagination").path("totalItems").asInt());
    assertFalse(response.getContentAsString().contains("private-other-user"));
    assertFalse(response.getContentAsString().contains("unfinished"));
    assertEquals(401,mvc.perform(get("/travel-posts")).andReturn().getResponse().getStatus());
    assertEquals(400,mvc.perform(get("/travel-posts").param("page","abc")
        .header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
    var page=mvc.perform(get("/travel-posts").param("keyword","海边").param("pageSize","1")
        .header("Authorization","Bearer "+token)).andReturn().getResponse();
    assertEquals(1,json.readTree(page.getContentAsString()).path("data").path("items").size());
  }

  @AfterEach void cleanup() throws Exception {
    for(long post:posts) {db.update("DELETE FROM journal_search_jobs WHERE post_id=?",post);db.update("DELETE FROM travel_posts WHERE id=?",post);}
    for(long trip:trips) db.update("DELETE FROM trips WHERE id=?",trip);
    for(long user:users) {
      db.update("DELETE FROM auth_refresh_tokens WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",user);
      db.update("DELETE FROM auth_sessions WHERE user_id=?",user);
      db.update("DELETE FROM users WHERE id=?",user);
    }
    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ES+"/"+INDEX)).DELETE().build(),HttpResponse.BodyHandlers.discarding());
  }
}
