# Travel Journal 搜索（Sida）

本模块是团队 Java/Spring 实现，Python 实验不属于正式运行依赖。代码在 `feature/journal-search`，尚未推送。

## 运行与配置

沿用主 README 的 Java/MySQL/认证环境。ES 使用 JDK HttpClient 调用 REST API，JSON 复用项目已有 Jackson 3，无需新增 ES Java SDK。

已有实验 ES 监听 19200 时可直接复用，索引使用独立的 `travel-posts-java`。否则：

```bash
docker compose --profile journal up -d elasticsearch
```

不要同时运行两个绑定 19200 端口的容器。Compose 中 ES 关闭认证并仅绑定回环地址，只供本地开发。

在项目 `.env` 中增加（不提交 `.env`）：

```dotenv
JOURNAL_ES_URL=http://127.0.0.1:19200
JOURNAL_ES_INDEX=travel-posts-java
JOURNAL_SEARCH_WORKER_ENABLED=true
JOURNAL_SEARCH_SYNC_DELAY_MS=30000
```

正式远程 ES 应使用 HTTPS 和凭据；支持 `JOURNAL_ES_API_KEY` 环境变量。索引名变更后，worker 会重新同步已有 MySQL 帖子。worker 默认关闭，避免未开发日志的同学启动项目时依赖 ES。

## API（需要团队 review 的选择）

- 请求类型：`GET`
- 请求接口：`/travel-posts`
- 请求格式：URL 查询参数。
- 身份验证：沿用现有认证过滤器，`Authorization: Bearer <accessToken>`。
- 搜索范围：当前用户、关联 Completed/Cancelled 行程的日志。公开搜索尚未确认，暂不开放。
- `keyword`：可选，去除首尾空格，最多 200 个 Unicode 码点。空关键词列出当前用户日志。
- `page`：从 1 开始，默认 1。
- `pageSize`：1–100，默认 20；`page * pageSize <= 10000`。
- 排序：相关性降序、同分按 id 降序。空关键词所有分数相同，因此按 id 降序。
- 成功：200；无结果仍为 200、items=[]。
- 非法参数：400；未认证：401；ES 不可用：503；非预期内部错误：500。

```json
{
  "message": "Search successful.",
  "data": {
    "items": [{"id": 101, "trip_id": 12, "content": "海边散步", "photos": []}],
    "pagination": {"page": 1, "pageSize": 20, "totalItems": 1, "totalPages": 1}
  }
}
```

这是此前分页提案的实现，和原始 `data: []` 合同不同，需前端/组长确认。id/trip_id 为 BIGINT 对应整数。搜索 Controller 使用团队 Result，成功仅 message/data；共享认证与异常层目前带额外 error，未更改公共行为，和组长截图要求的差异需全组统一。

手动请求：先使用团队登录接口取得真实 token，替换 `<accessToken>`：

```bash
curl -G http://localhost:8085/travel-posts \
  -H 'Authorization: Bearer <accessToken>' \
  --data-urlencode 'keyword=海边' --data 'page=1' --data 'pageSize=20'
```

注意端口是正式 Spring 的 8085，不是 Python 的 18080。

## 发布负责人的接入方式

注入 `TravelPostIndexService`；在已有发布业务的同一个 `@Transactional` 方法中：

```java
TravelPost saved = travelPostRepository.save(post);
travelPostIndexService.enqueue(saved.getId());
```

`enqueue` 强制要求调用方已有事务。帖子和待同步任务一起提交/回滚，不要直接在 Controller 调用 enqueue。新增表 `journal_search_jobs` 已加入 schema.sql，无需改变 TravelPost 或搭档的 POST 设计。

worker 每轮扫描一页 MySQL 历史帖子（100 条）补队列，再处理最多 100 个任务。ES 成功后删除对应 revision 的任务；失败保留并在下一轮重试。revision 避免并发更新时误删新任务。稳定数据也周期重扫，支持 ES 索引丢失后恢复。大规模数据不应持续全表扫描，MVP 后可以换为只处理事件并单独提供重建任务。

关联非 Completed/Cancelled 行程时，worker 删除对应 ES 文档。MySQL 查询结果再次校验当前所有权和状态，避免旧索引造成越权。content/照片从 MySQL 获取，照片 TEXT 同时支持当前逗号分隔格式及 JSON 数组。草稿若未来也存在 travel_posts，必须加入明确发布状态过滤后才能沿用此 worker；当前实体没有草稿字段，不应把草稿写入该表。

新数据是最终一致，可能等待 worker 周期及 ES refresh 后才能搜索。worker 失败不会影响 POST 已提交的数据；队列需由团队监控，当前日志只记录失败类型。ES 命中总数来自索引，若删除/权限变更还未同步，items 会安全过滤，但总数暂时可能偏大。

不支持在多个实例无协调地反复全量对账；当前方案面向本地/单实例 MVP。物理删除历史帖且未 enqueue 的情况不会主动清理旧文档，但搜索不会返回已删除的 MySQL 记录；实现删除接口时也应同事务 enqueue。

## 文件职责

- controller/TravelPostSearchController：接参数与当前用户，返回 Result。
- service/TravelPostSearchService 与 impl：校验、权限、查询与 DTO 转换。
- search/ElasticsearchGateway：HTTP、mapping、match 查询、索引写入与删除。
- service/TravelPostIndexService：持久化任务、数据库事务与同步。
- search/JournalIndexWorker：可关闭的历史补建、定时重试。

## 验证命令

2026-11-01 已执行 `./mvnw -q clean verify -Djournal.es.integration=true`：32 项测试通过，0 失败/错误/跳过，包含原有 21 项测试和新增 11 项搜索相关测试。真实依赖为 MySQL 8.4、ES 9.5.5。使用本机 Java 24 编译，项目编译目标仍为 Java 17；未单独在 JDK 17 进程验证。真实集成使用 MockMvc 经过 Spring MVC 与认证过滤器，数据库与 ES 均为真实服务，不是启动外部 8085 服务的浏览器测试。

模块测试不依赖 MySQL 或真实 ES（HTTP 网关使用本机测试服务器）：

```bash
./mvnw -Dtest=TravelPostSearchTest,ElasticsearchGatewayTest test
```

真实集成测试需要团队 Docker MySQL 测试库（53306）和 ES（19200）：

```bash
docker compose up -d mysql mailpit
./mvnw -Dtest=JournalSearchIntegrationTest -Djournal.es.integration=true test
```

使用唯一 ES 临时索引和自己创建的数据库记录，完成后清理；仅允许 `travel_planner_auth_test`。覆盖真实认证、中文搜索、分页、用户隔离、非终态排除、队列事务回滚与同步。默认不执行此可选测试，不增加其他同学日常测试对 ES 的依赖。
