// Sida Xue — Travel Journal Search
// 本次搜索模块新增：搜索、索引同步或集成验证测试。
package com.travel.journal;

import com.sun.net.httpserver.HttpServer;
import com.travel.journal.search.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchGatewayTest {
  @Test void actualHttpRequestsHandleIndexCreationSearchAndErrors() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    List<String> requests = new ArrayList<>();
    AtomicInteger searchCount = new AtomicInteger();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      requests.add(exchange.getRequestMethod()+" "+path);
      assertEquals("ApiKey test-key", exchange.getRequestHeaders().getFirst("Authorization"));
      String body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
      int status = 200; String response = "{}";
      if (path.endsWith("_search")) {
        assertTrue(body.contains("match_all"));
        if (searchCount.getAndIncrement()==0) { status=404; response="{}"; }
        else response="{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}";
      } else if (exchange.getRequestMethod().equals("PUT") && path.equals("/test-journal")) {
        assertTrue(body.contains("cjk"));
        status=400; response="{\"error\":{\"type\":\"resource_already_exists_exception\"}}";
      } else if (exchange.getRequestMethod().equals("DELETE")) status=404;
      byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status,bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      var gateway = new ElasticsearchGateway("http://127.0.0.1:"+server.getAddress().getPort(),"test-journal","test-key");
      var response=gateway.search(Map.of("query",Map.of("match_all",Map.of())));
      assertEquals(0,response.path("hits").path("total").path("value").asInt());
      gateway.upsert(101L,12L,1L,"海边"); gateway.delete(101L);
      assertTrue(requests.contains("PUT /test-journal/_doc/101"));
    } finally { server.stop(0); }
  }

  @Test void unavailableEndpointThrowsSearchException() {
    var gateway = new ElasticsearchGateway("http://127.0.0.1:1","test-journal","");
    assertThrows(JournalSearchException.class, () -> gateway.search(Map.of()));
  }
}
