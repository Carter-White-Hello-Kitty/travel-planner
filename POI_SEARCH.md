# POI 搜索本地使用

## 配置与启动

1. 保持 README 的本地 MySQL 配置；Docker MySQL 使用宿主机端口 `53306`。
2. 在 Google Cloud 项目启用 Places API (New) 和计费，为后端创建受限制的 API Key。
3. 在 IDEA → Run → Edit Configurations → Environment variables 中添加 `GOOGLE_PLACES_API_KEY`，值只在本机填写。不要提交或打印。终端启动也可提前设置该环境变量。
4. Working directory 设为项目根目录，使用默认 `dev`，启动 `TravelPlannerApplication`；终端可运行 `./mvnw spring-boot:run`。

未配置 Key 不阻止整个应用启动；搜索时返回 HTTP 503、`POI_SEARCH_NOT_CONFIGURED`。连接超时默认 2 秒，单次请求超时默认 5 秒，可分别用 `GOOGLE_PLACES_CONNECT_TIMEOUT`、`GOOGLE_PLACES_REQUEST_TIMEOUT` 覆盖，例如 `3s`、`8s`。

## Postman 测试

服务地址为 `http://localhost:8085`。保留同一个 cookie jar。

1. `GET /auth/csrf`，保存响应 `data.token`，保留 `XSRF-TOKEN` cookie。
2. 用已有账户调用 `POST /auth/login`，JSON 为 `email`、`password`。设置请求头：`Content-Type: application/json`、`Origin: http://localhost:3000`、`X-Requested-With: XMLHttpRequest`、`X-XSRF-TOKEN: <CSRF token>`。保存响应中的 `data.accessToken`。尚无账户时可按相同请求头调用 `/auth/register` 注册后再登录。
3. `POST /pois/search`，设置 `Content-Type: application/json`、`Authorization: Bearer <accessToken>`、`X-XSRF-TOKEN: <CSRF token>`，继续携带 CSRF cookie，body：

```json
{
  "keyword": "museum",
  "latitude": 39.3299,
  "longitude": -76.6205,
  "radius": 5000
}
```

成功格式为 `{"message":"POIs retrieved successfully","data":{"pois":[...]}}`。地点包含现有 PoiVO 的全部字段，`id` 为 null；`googlePlaceId` 是 Google 的 place ID，`category` 优先使用 primaryType，否则取第一个 types，评分等缺失时返回 null。无结果时 pois 是空数组。

keyword 非空且不超过 200 字符，latitude 为 -90～90，longitude 为 -180～180，radius 为 1～50000 米。参数错误为 400、`INVALID_REQUEST`；未认证为 401，缺少或错误 CSRF 为 403。Google 非成功状态、连接失败或格式错误为 502、`POI_SEARCH_UPSTREAM_ERROR`；超时为 504、`POI_SEARCH_TIMEOUT`。错误不会伪装成空数组，也不返回 Google 原始错误内容或 Key。

## 范围与限制

调用 `POST https://places.googleapis.com/v1/places:searchText`，请求体包含 textQuery、pageSize: 20、locationBias.circle；请求头包含 X-Goog-Api-Key 和明确的 X-Goog-FieldMask。

locationBias 只是搜索偏好。后端使用返回坐标与请求中心计算 Haversine 球面距离，仅保留距离不大于 radius 的地点，支持跨日期变更线。当前仅处理 Google 第一页最多 20 条候选结果，不分页，不保证列出区域内全部地点；过滤后可能不足 20 条甚至为空。没有数据库读写，返回的数据库 id 一律为 null。

官方文档：[Text Search (New)](https://developers.google.com/maps/documentation/places/web-service/text-search)、[searchText REST 请求格式](https://developers.google.com/maps/documentation/places/web-service/reference/rest/v1/places/searchText)。Text Search 的 locationRestriction 支持矩形；本实现用圆形 bias 加本地距离过滤保证返回范围。

## 自动化验证

```bash
./mvnw -Dtest=PoiSearchServiceTest,PoiSearchControllerTest,GooglePlacesClientTest test
```

- GooglePlacesClientTest 使用本地 HTTP 模拟服务器和虚构 Key，覆盖请求格式、字段映射、空结果、Google 错误、损坏响应、连接失败、缺少 Key 和超时。
- PoiSearchServiceTest 使用模拟 client，覆盖参数校验、半径过滤、跨日期变更线和极点。
- PoiSearchControllerTest 使用 MockMvc 和原有 AuthConfig/BoundaryFilter/CSRF，仅模拟认证服务和 Google client；覆盖成功格式、null id、参数和 JSON 错误、空结果、上游失败，以及认证/CSRF 拒绝。无需数据库，不关闭安全过滤链。

这些测试不会真实调用 Google，不使用真实 Key，也不执行会清理测试库的认证集成测试。上述 Postman 步骤在配置真实 Key 后会调用 Google，并可能产生 Google API 费用。
