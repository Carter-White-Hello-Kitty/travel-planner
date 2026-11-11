# Travel Journal 搜索（Sida）

本模块是团队 Java/Spring 实现，Python 实验不属于正式运行依赖。

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
- 搜索范围：按组长确认，所有用户均可搜索所有已发布、关联 Completed/Cancelled 行程的日志，不按作者过滤。沿用团队现有登录要求，未改为匿名接口。
- `keyword`：可选，去除首尾空格，最多 200 个 Unicode 码点。空关键词列出所有作者的已发布日志。
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

最新 test 已提供 `TravelPostPublishService.TravelPostPublished` 事件。新增 `TravelPostPublishedListener` 同步监听该事件，并在发布的同一事务中调用 `TravelPostIndexService.enqueue(event.id())`，无需改搭档的发布实现。事件监听不是异步，也不是 AFTER_COMMIT，因此队列与帖子一起提交/回滚。

`enqueue` 强制要求已有事务。未来新增修改或删除接口时，可在其事务内调用 enqueue；不要在 Controller 或事务提交后直接调用。新增表 `journal_search_jobs` 已加入 schema.sql。

worker 每轮扫描一页 MySQL 历史帖子（100 条）补队列，再处理最多 100 个任务。ES 成功后删除对应 revision 的任务；失败保留并在下一轮重试。revision 避免并发更新时误删新任务。稳定数据也周期重扫，支持 ES 索引丢失后恢复。大规模数据不应持续全表扫描，MVP 后可以换为只处理事件并单独提供重建任务。

关联非 Completed/Cancelled 行程时，worker 删除对应 ES 文档。MySQL 查询结果再次校验当前记录存在性与行程终态，避免旧索引返回已删除或未结束的内容。content/照片从 MySQL 获取，照片 TEXT 同时支持当前逗号分隔格式及 JSON 数组。草稿若未来也存在 travel_posts，必须加入明确发布状态过滤后才能沿用此 worker；当前实体没有草稿字段，不应把草稿写入该表。

新数据是最终一致，可能等待 worker 周期及 ES refresh 后才能搜索。worker 失败不会影响 POST 已提交的数据；队列需由团队监控，当前日志只记录失败类型。ES 命中总数来自索引，若删除/权限变更还未同步，items 会安全过滤，但总数暂时可能偏大。

不支持在多个实例无协调地反复全量对账；当前方案面向本地/单实例 MVP。物理删除历史帖且未 enqueue 的情况不会主动清理旧文档，但搜索不会返回已删除的 MySQL 记录；实现删除接口时也应同事务 enqueue。

## 文件职责

- controller/TravelPostSearchController：接参数与当前用户，返回 Result。
- service/TravelPostSearchService 与 impl：校验、权限、查询与 DTO 转换。
- search/ElasticsearchGateway：HTTP、mapping、match 查询、索引写入与删除。
- service/TravelPostIndexService：持久化任务、数据库事务与同步。
- search/JournalIndexWorker：可关闭的历史补建、定时重试。

## 验证命令

完整测试默认包含真实 ES 集成测试。先启动 MySQL、Redis、Mailpit 和 ES，再运行普通 Maven 命令，无需额外测试开关：

```bash
docker compose --profile journal up -d --wait
./mvnw clean verify
```

CI 也使用相同的 Compose profile。集成测试使用 MockMvc、真实 MySQL 测试库和真实 ES；测试期间关闭索引 worker，由测试确定性地处理索引任务。定时 worker 的自动运行由下文的 HTTP 冒烟脚本验证。

模块测试不依赖 MySQL 或真实 ES（HTTP 网关使用本机测试服务器）：

```bash
./mvnw -Dtest=TravelPostSearchTest,ElasticsearchGatewayTest test
```

只运行游记搜索集成测试时，也需要团队 Docker MySQL 测试库（53306）和 ES（19200）：

```bash
docker compose --profile journal up -d --wait
./mvnw -Dtest=JournalSearchIntegrationTest test
```

测试使用唯一 ES 临时索引和自己创建的数据库记录，完成后清理；仅允许 `travel_planner_test`。覆盖真实认证、中文搜索、分页、跨用户可见性、非终态排除、队列事务回滚与同步。


## 实际 HTTP 冒烟验证

可重复执行脚本 `scripts/smoke/journal-search-smoke.py` 使用 Python 标准库。它与其他可执行脚本一起位于 `scripts/smoke/`，由 `scripts/run-smokes.py` 的完整套件运行；CI 的 `--ci` 模式只运行无需外部配置的冒烟脚本，ES 由集成测试覆盖。

先启动真实后端，绑定 localhost:18085，数据库明确配置为 travel_planner_test（不加载 src/test/resources 的 test profile）；启用开发认证密钥、关闭安全 Cookie，保持邮件 worker 运行以支持完整冒烟套件，并启用搜索索引 worker、把同步间隔设为 2000ms。ES 索引必须是专用测试索引。MySQL/Redis/ES 分别需要 53306/56379/19200。

命令示例（本机 Docker Compose 默认测试密码，仅供本地）：

```bash
java -jar target/travel-planner-0.2.0-auth-SNAPSHOT.jar \
  --server.port=18085 \
  --spring.datasource.url='jdbc:mysql://localhost:53306/travel_planner_test?serverTimezone=UTC&characterEncoding=UTF-8' \
  --spring.datasource.username=travel \
  --spring.datasource.password=local-only-change-me \
  --spring.data.redis.database=1 \
  --auth.development-keys=true --auth.cookie-secure=false \
  --journal.search.worker-enabled=true \
  --journal.search.sync-delay-ms=2000 \
  --journal.search.index=journal-http-smoke-local
```

另一个终端运行：

```bash
SMOKE_URL=http://localhost:18085 python3 scripts/smoke/journal-search-smoke.py
```

验证了真实 HTTP 注册、登录、CSRF、搭档 POST 发布、后台自动索引、GET 搜索、照片 URL 返回、分页、无结果、用户隔离及 400/401/403/409。Completed 和 Cancelled 两次发布到搜索分别约 2.2s、2.1s；未手动触发索引方法。测试行程通过 SQL 准备，不测试 Google 行程生成或真实 S3 上传；测试加速至 2s，不能据此承诺默认 30s 周期下的延迟。

第一轮首次索引就绪时曾返回 503，后续 ES 正常、自动同步也已发生；未定位具体原因，不声称冷启动零错误。脚本会明确记录 503 并在 60 秒内重试，持续失败仍退出非零；第二轮通过。生产搜索 Java 代码未因此调整。建议后续补充 ES 就绪检查/初始化耗时诊断。

脚本只清理本次账户及其帖子、行程和队列，临时 ES 索引需在停止专用后端后单独清理。本次执行的临时索引与后端均已清理，MySQL/Redis/已有 ES 服务保持运行。


## 2026-11-09 可见范围调整

组长确认日志对所有用户可见。移除 ES 查询与 MySQL 回查中的作者限制，关键词搜索和空关键词列表均共享。登录要求仍沿用认证模块；发布接口的行程所有权、Completed/Cancelled 校验保持不变。不会开放其他用户的行程详情接口，也不在搜索响应增加用户邮箱或账号信息。现有 ES 索引无需重建，保留的 user_id 只是内部元数据。

上文 11 月 8 日的用户隔离测试为历史行为记录；当前验收改为用户 B 能搜到用户 A 发布的帖子，但不能替 A 的行程发布。私人草稿仍应在前端或独立存储中，不应写入当前 travel_posts 表。


当前验收要求：用户 B 可以按关键词和空关键词列表查看用户 A 的帖子；替其他用户行程发布仍为 403，非终态发布仍为 409，未登录搜索仍为 401。
