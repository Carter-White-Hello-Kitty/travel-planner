// Sida Xue — Travel Journal Search
// 本次搜索模块新增：Elasticsearch HTTP 调用：创建索引、搜索、写入与删除文档。
package com.travel.journal.search;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uses ES REST over the JDK HTTP client; no extra version-coupled ES dependency. */
@Component
public class ElasticsearchGateway {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final String base;
  private final String index;
  private final String apiKey;

  public ElasticsearchGateway(@Value("${journal.search.url:http://127.0.0.1:19200}") String base,
      @Value("${journal.search.index:travel-posts-java}") String index,
      @Value("${journal.search.api-key:}") String apiKey) {
    if (!index.matches("[a-z][a-z0-9_-]{0,100}")) throw new IllegalArgumentException("Invalid ES index name");
    this.base = base.replaceAll("/+$", ""); this.index = index; this.apiKey = apiKey;
  }

  public void ensureIndex() {
    JsonNode mapping = json.valueToTree(Map.of("mappings", Map.of("properties", Map.of(
        "id", Map.of("type", "long"), "trip_id", Map.of("type", "long"),
        "user_id", Map.of("type", "long"),
        "content", Map.of("type", "text", "analyzer", "cjk")))));
    HttpResponse<String> response = send("PUT", "/" + index, mapping);
    if (response.statusCode() == 400) {
      JsonNode body = json.readTree(response.body());
      if ("resource_already_exists_exception".equals(body.path("error").path("type").asText())) return;
    }
    check(response);
  }

  public JsonNode search(Map<String, Object> query) {
    HttpResponse<String> response = send("POST", "/" + index + "/_search", json.valueToTree(query));
    if (response.statusCode() == 404) {
      ensureIndex();
      response = send("POST", "/" + index + "/_search", json.valueToTree(query));
    }
    check(response);
    return json.readTree(response.body());
  }

  public void upsert(Long postId, Long tripId, Long userId, String content) {
    ensureIndex();
    check(send("PUT", "/" + index + "/_doc/" + postId, json.valueToTree(Map.of(
        "id", postId, "trip_id", tripId, "user_id", userId,
        "content", content == null ? "" : content))));
  }

  public void delete(Long postId) {
    HttpResponse<String> response = send("DELETE", "/" + index + "/_doc/" + postId, null);
    if (response.statusCode() != 404) check(response);
  }

  private HttpResponse<String> send(String method, String path, JsonNode body) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
      if (!apiKey.isBlank()) request.header("Authorization", "ApiKey " + apiKey);
      request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JournalSearchException("ES request interrupted", e);
    } catch (IOException e) { throw new JournalSearchException("ES unavailable", e); }
  }

  private void check(HttpResponse<String> response) {
    if (response.statusCode() / 100 != 2)
      throw new JournalSearchException("ES returned HTTP " + response.statusCode());
  }
}
