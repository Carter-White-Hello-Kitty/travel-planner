# Travel Planner 后端增量迁移交付记录

日期：2026-11-11（Asia/Shanghai）。当前仅准备交付，所有改动未暂存；未执行 commit、push、merge 或创建 PR。

## 基线与范围

- 功能分支：`feature/poi-wishlist-itinerary`；PR 目标：`test`。
- origin：`https://github.com/Carter-White-Hello-Kitty/travel-planner.git`。
- 开始前及最终验证期间均成功执行 `git fetch origin`。HEAD 与最新已获取的 origin/test 都为 `6174ee673f94fd0f321641d4d9dd9f4aa9a317b0`，基线未改变，无需整合。
- 原始 travel-planner-test 仅作只读来源；前端交付目录未修改，检查时工作区干净。
- 对16个既有业务/配置/测试文件生成明确白名单的逐项增量补丁，先 git apply --check 再应用。未 rsync、整体覆盖 src 或直接复制同名业务文件。
- 团队认证生产代码及原认证测试、JWT、CSRF过滤链、Journal生产代码、既有Journal集成测试、Redis幂等、状态枚举、行程Repository、照片上传生产实现、pom.xml、原Compose和CI均保留。

## 模块迁移结果

### Google Places / Maps

- 保留团队搜索、区域过滤与基础详情；扩展展示详情、营业时间、电话、网站、最多三张照片及作者署名。
- 新增 GET /pois/{id}、POST /pois/photo、POST /pois/map。
- 照片资源格式校验，仅返回 HTTPS Googleusercontent 媒体 URL，不返回服务端密钥请求地址。
- Google资源400/404分别映射400 INVALID_REQUEST/404 RESOURCE_NOT_FOUND；供应商403/429/500保持502；Places超时为504。上游正文不透传。
- 新增固定Google端点的静态地图代理，测试通过包级构造器注入本地HTTP端点；生产端点不由请求指定。保留认证和CSRF要求。
- application.yaml仅增量加入 Maps 配置占位符，没有迁入密钥。

### Wishlist

- 新增 GET /wishlist/ids 及按所有者查询的Repository投影，不请求Google。
- 重复收藏先查询，再访问Google；保留唯一约束及并发冲突处理。
- 区分非法Place ID与资源404，保留已删除资源的列表降级；分页、持久化、所有者权限保留。

### Itinerary

- 增加可空Trip.destination和响应字段，保留旧GeneratedTrip构造器；测试旧JSON缺少destination的反序列化兼容性。
- 新增 POST /trips/manual、PUT /trips/{id}/plan。手动计划1–15天，每天最多10个地点，禁止重复地点。
- 整体保存仅Planning；复用父Trip行锁、归属校验、事务及现有Repository。支持排序、跨天移动、删除地点和缩短天数；已有POI行ID保持稳定。
- 新地点校验失败不改变原计划；Repository失败发生在元数据flush之后时，事务仍能回滚原名称、目的地、日期和地点。
- 自动生成上限14调整为15；其规划器、请求哈希、Redis幂等、状态流转、打卡、草稿删除和Journal关联实现不替换。
- 团队状态枚举的isEditable仍包含Ongoing；本次新增整体保存接口明确仅限Planning，不改变团队其他编辑规则。

### 公共异常

仅增加MethodArgumentTypeMismatchException处理器，返回400 INVALID_REQUEST。保留已有JSON、Bean Validation、ApiError、数据库和未知异常契约。

### 测试与脚本

- 两个新增live脚本不发送fullName，密码运行时随机生成；必须显式设置SMOKE_ALLOW_WRITES=1，默认拒绝写操作。
- manual-plan-smoke只验证无Google依赖的真实HTTP与数据库业务，不再请求Google或地图。失败断言非零退出，finally尝试删除自己的QA行程及登出。
- persistence-acceptance仍为真实Places验收，支持独立Compose参数与数据库名，SQL检查仅SELECT；通过API创建/删除自身QA数据，失败返回非零。未取得本轮真实Google凭证，故未运行。
- 两个脚本都创建独立随机QA账号；无账号删除产品接口，账号保留在指定测试库。不得对生产或共享环境启用写入许可。
- 原认证HTTP smoke仅增加可配置本地Mailpit地址及随机密码，原验证流程保留。
- 未将真实Google脚本加入CI。新增独立Compose与验证runner便于复现隔离验证。

## 测试命令与结果

最终执行：

```sh
python3 scripts/validation/run-isolated.py
```

runner内部执行以下命令（项目名为随机隔离项目，数据库凭证仅在内存环境中随机生成）：

```sh
docker compose -p <isolated-project> -f compose.validation.yaml up -d --wait
./mvnw --batch-mode clean verify
python3 scripts/run-smokes.py
SMOKE_ALLOW_WRITES=1 python3 scripts/live/manual-plan-smoke.py
docker compose -p <isolated-project> -f compose.validation.yaml stop
```

上述后两个Python命令必须使用runner设置的隔离环境；不要直接对默认开发后端复跑。runner只继承PATH/HOME/JAVA_HOME/TMPDIR/LANG，不继承Google、AWS、数据库或代理凭证；交付目录存在.env时拒绝执行。测试用Java target 17。

| 检查 | 最终结果 |
| --- | --- |
| Maven clean verify、全量编译与打包 | 157/157通过，0 failure/error/skipped |
| 新库初始化、重复schema执行 | 通过，独立validation_new数据库 |
| 旧表destination升级、再次执行、旧行程保留 | 通过，独立validation_legacy数据库；确认旧行程仍1条且destination列仅1列 |
| 原认证HTTP smoke | 1个脚本通过；真实HTTP/CSRF/注册/登录/refresh/邮件重置/撤销/登出 |
| 手动行程HTTP smoke | 14项断言通过；Google与地图明确跳过 |
| 两个live脚本的语法与默认写入拒绝 | 通过；检查未执行真实写入 |
| git diff --check | 通过 |
| 受保护模块差异检查 | 认证、Journal生产代码、原CI及pom等无变化 |
| 敏感信息扫描 | 候选文件未发现真实Google/GitHub/AWS Key、JWT或私钥格式；不打印匹配值 |

最终Maven逐类结果（参数化测试按实际运行数量统计）：

| 测试类 | 通过数量 |
| --- | ---: |
| auth.AuthIntegrationTest | 20 |
| itinerary.GenerateTripRequestValidationTest | 2 |
| itinerary.TripGenerationControllerTest | 5 |
| itinerary.TripGenerationIdempotencyIntegrationTest | 13 |
| itinerary.TripGenerationIntegrationTest | 6 |
| itinerary.TripLifecycleIntegrationTest | 30 |
| itinerary.TripStatusTest | 7 |
| itinerary.service.impl.TripGenerationServicePlanningTest | 3 |
| journal.TravelPostPublishIntegrationTest | 11 |
| journal.service.TravelPostPhotoServiceTest | 4 |
| journal.service.TravelPostPublishServiceTest | 17 |
| poisearch.PoiSearchControllerTest | 6 |
| poisearch.PoiSearchServiceTest | 3 |
| poisearch.client.GoogleMapClientTest | 3 |
| poisearch.client.GooglePlacesClientTest | 9 |
| wishlist.WishlistIntegrationTest | 17 |
| wishlist.WishlistPersistenceIntegrationTest | 1 |

新增测试覆盖地图供应商错误/内容类型/大小/连接/超时、Places详情照片超时、请求边界、旧缓存JSON、membership隔离且不请求Google、手动保存与start串行、事务回滚和非Planning/CSRF拒绝。原自动行程、Redis幂等和并发打卡测试也运行通过。

照片上传生产代码未改；新增4项mock S3单元测试覆盖合法图片、非法/空/超限文件、缺配置及SDK错误。它们不代表真实S3上传成功。

最早两次完整验证分别146与153项通过，随后补齐最终测试到157项；没有通过忽略失败或跳过测试获得最终结果。

## 隔离环境与证据

验证项目每次使用独立随机Compose项目名和独立数据卷；MySQL54316、Redis57389、Mailpit18135/11135，HTTP后端8087。Maven使用travel_planner_test和Redis1，但实例与开发实例完全不同。Schema检查使用同一独立MySQL内另外两个临时数据库。

最终证据：`.validation/summary.json`、`.validation/maven-clean-verify.log`、`.validation/schema-new.log`、`.validation/schema-legacy.log`、`.validation/auth-http-smoke.log`、`.validation/manual-http-smoke.log`，以及target/surefire-reports。`.validation/`与target被忽略，不进入Git交付。验证进程及自建容器已停止，隔离卷保留；本轮未删除数据卷或清理开发库/共享Redis。

## 前后端认证契约差异（待单独确认前端适配）

核对的前端提交为 `e18bbe9`，未修改其文件。所有响应字段均指Result.data内部，不包含任何真实Token值。

| 接口 | 团队后端契约 | 已提交前端行为与影响 |
| --- | --- | --- |
| POST /auth/register | 请求仅email、password；响应user={id,email} | 请求额外fullName被拒绝；期望fullName且不会落库 |
| POST /auth/login | 请求email、password；响应user={id,email},accessToken,tokenType,expiresIn | 请求与Token处理兼容，但User类型要求fullName，需规范化为null |
| POST /auth/refresh | 请求{}，携带refresh Cookie及CSRF；响应accessToken,tokenType,expiresIn | 请求、Cookie和CSRF恢复流程兼容；响应不含完整用户 |
| GET /auth/me | Bearer请求；响应{userId} | getCurrentUser直接当作{id,email,fullName}，刷新页面后用户对象字段缺失 |

最小前端适配建议（本轮未实施）：

1. 注册API仅序列化email/password；注册页不承诺持久化姓名，去掉或禁用姓名输入需单独确认。
2. 登录/注册响应统一规范化fullName=null，保留后端返回的id/email。
3. /auth/me显式读取userId，映射为id；重新恢复会话时email不可从此接口取得，应允许email=null并使用中性展示，不能伪造邮箱。需要重新登录以重新取得真实email，或由认证负责人另行提供资料接口。
4. 调整User类型及AuthContext/Profile的缺失资料展示，保持登录/刷新Token与CSRF实现；前端live-smoke同样删除fullName注册字段。
5. 补充团队契约的注册、登录、refresh后/me、Profile缺失资料回归测试，再执行前端检查。

## 数据库升级风险

只增加trips.destination可空列；未迁入users.full_name，不删除、重建、改名业务表。
兼容脚本先查information_schema，缺列才ALTER；已验证新库、旧库数据保留及重复执行。
但MySQL DDL并非可回滚业务事务，ALTER需要权限且可能持有元数据锁；检查后ALTER仍可能在多个实例同时启动时竞争。正式环境建议由单一受控迁移步骤执行并先备份，不承诺无锁升级。

## 敏感信息与未验证项目

- 未读取原始.env或真实密钥；未迁入API Key、Token、私钥或真实密码。新增Maps配置只有环境变量占位符。
- 新增live脚本与认证HTTP smoke无固定QA密码。既有Maven测试中的固定字符串和原Compose开发默认值仍保留，属于原有测试/本地配置，不代表真实账户；没有以“无固定密码”描述整个仓库。
- 真实Google搜索/详情/照片和persistence-acceptance本轮未执行；旧开发目录历史成功不替代交付分支验收。
- Maps Static云端未启用，真实地图验收待完成；本地HTTP测试服务器和mock结果不冒充真实服务。
- 真实S3上传/下载未执行，无AWS凭证；仅编译与mock回归通过。
- 未运行GitHub Actions远程CI或浏览器验收，未创建PR。前端认证契约仍需单独适配确认。
- 全量计划保存无乐观版本号，两个Planning保存仍为后提交覆盖；仅验证行锁防止与start产生不一致。
- GoogleMapClient在读取完整响应后检查大小，超大上游响应的内存保护可另行加固；本轮保留已开发实现。

## 最终文件清单与Git结果

共32个文件：18个修改、14个新增，无删除；全部未暂存。

```text
 M .gitignore
 M scripts/smoke/auth-smoke.py
 M src/main/java/com/travel/common/GlobalExceptionHandler.java
 M src/main/java/com/travel/itinerary/entity/Trip.java
 M src/main/java/com/travel/itinerary/service/impl/TripServiceImpl.java
 M src/main/java/com/travel/itinerary/vo/GenerateTripRequest.java
 M src/main/java/com/travel/itinerary/vo/GeneratedTrip.java
 M src/main/java/com/travel/poisearch/client/GooglePlacesClient.java
 M src/main/java/com/travel/wishlist/controller/WishlistController.java
 M src/main/java/com/travel/wishlist/repository/WishlistRepository.java
 M src/main/java/com/travel/wishlist/service/WishlistService.java
 M src/main/java/com/travel/wishlist/service/impl/WishlistServiceImpl.java
 M src/main/resources/application.yaml
 M src/main/resources/schema.sql
 M src/test/java/com/travel/itinerary/TripLifecycleIntegrationTest.java
 M src/test/java/com/travel/poisearch/PoiSearchControllerTest.java
 M src/test/java/com/travel/poisearch/client/GooglePlacesClientTest.java
 M src/test/java/com/travel/wishlist/WishlistIntegrationTest.java
?? BACKEND_HANDOFF.md
?? compose.validation.yaml
?? scripts/live/manual-plan-smoke.py
?? scripts/live/persistence-acceptance.py
?? scripts/validation/run-isolated.py
?? src/main/java/com/travel/itinerary/controller/ManualTripController.java
?? src/main/java/com/travel/itinerary/service/ManualTripService.java
?? src/main/java/com/travel/itinerary/vo/SaveTripRequest.java
?? src/main/java/com/travel/poisearch/client/GoogleMapClient.java
?? src/main/java/com/travel/poisearch/controller/PoiDisplayController.java
?? src/main/java/com/travel/poisearch/vo/PlaceDetails.java
?? src/test/java/com/travel/itinerary/GenerateTripRequestValidationTest.java
?? src/test/java/com/travel/journal/service/TravelPostPhotoServiceTest.java
?? src/test/java/com/travel/poisearch/client/GoogleMapClientTest.java
```

`git diff --stat`（Git默认不统计未跟踪文件，新增文件已在上方status列全）：

```text
 .gitignore                                         |   2 +
 scripts/smoke/auth-smoke.py                        |  15 +-
 .../com/travel/common/GlobalExceptionHandler.java  |   6 +
 .../java/com/travel/itinerary/entity/Trip.java     |   3 +
 .../itinerary/service/impl/TripServiceImpl.java    |   7 +-
 .../travel/itinerary/vo/GenerateTripRequest.java   |   2 +-
 .../com/travel/itinerary/vo/GeneratedTrip.java     |   8 +-
 .../poisearch/client/GooglePlacesClient.java       |  78 +++++++++-
 .../wishlist/controller/WishlistController.java    |   5 +
 .../wishlist/repository/WishlistRepository.java    |   3 +
 .../travel/wishlist/service/WishlistService.java   |   2 +
 .../wishlist/service/impl/WishlistServiceImpl.java |  11 +-
 src/main/resources/application.yaml                |   2 +
 src/main/resources/schema.sql                      |  10 ++
 .../itinerary/TripLifecycleIntegrationTest.java    | 161 +++++++++++++++++++++
 .../travel/poisearch/PoiSearchControllerTest.java  |  29 +++-
 .../poisearch/client/GooglePlacesClientTest.java   |  61 +++++++-
 .../travel/wishlist/WishlistIntegrationTest.java   |  30 +++-
 18 files changed, 415 insertions(+), 20 deletions(-)
```

`git diff --check`通过；`git diff --cached --name-only`为空。HEAD和最新获取的origin/test仍相同。最终提交/推送/PR须等待用户确认。
