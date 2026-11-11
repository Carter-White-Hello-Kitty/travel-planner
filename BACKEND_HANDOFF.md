# Travel Planner 后端整合交付记录

日期：2026-11-11（Asia/Shanghai）。本文替代此前“尚未整合test”的交付状态。

## 整合结果与Git范围

- 当前分支：feature/poi-wishlist-itinerary；未来PR目标：test。
- 已将业务增量本地提交为b1e7c99：Places展示详情/照片、Maps Static代理、Wishlist IDs、手动行程、destination及异常回归。
- 提交后执行git fetch origin，最新origin/test为6f88804284fe2a561a56900004a937439adfd2f7。
- 使用git merge --no-commit --no-ff origin/test，逐项解决冲突，在完整验证后完成本地合并提交。合并提交SHA见git log，包含origin/test及业务提交两个父提交。
- 未push，未创建或合并PR，未修改远程test/master。未修改原始开发目录或前端交付目录。

## 安全备份与冲突

备份位置：`/Users/wsw/Downloads/研究生/来offer/github/backend-integration-backup-fbb9ccf8b6`。

包含源码归档、SHA-256清单、业务补丁、原Git状态与refs，覆盖未跟踪业务文件；排除.env、密钥、target、.validation、日志与Python字节码。归档包含141个安全文件。

4处冲突及处理：

| 冲突 | 处理 |
| --- | --- |
| .gitignore | 保留双方规则：旧live产物、.validation及团队scripts/results均忽略 |
| schema.sql | 保留trips.destination列/重复安全升级，以及团队journal_search_jobs表，无删除业务表或数据 |
| manual-plan-smoke.py路径推断 | 不接受Git自动移动到scripts/smoke的建议，保留scripts/live手动入口 |
| persistence-acceptance.py路径推断 | 同样保留scripts/live，真实Places脚本不进入CI自动入口 |

三个旧tracked pyc的删除来自团队最新test，不是清理或重置工作区。没有执行破坏性Git或共享数据库操作。

## 保留与整合的功能

- Google Places New搜索及区域过滤保持；扩展详情、营业信息、照片资源/署名与安全URL、400/404/上游错误处理。
- Maps Static仍为服务端代理；没有更换地图方案，真实云端地图仍待启用验收。
- Wishlist分页/唯一性/所有者隔离保留，新增IDs投影、重复检查提前与正确错误分类。
- 手动计划POST /trips/manual、PUT /trips/{id}/plan，1–15天、每天至多10个地点、禁止重复；仅Planning可整体保存。排序、跨天移动、删除、稳定POI行ID及事务回滚保留。
- 自动生成15天边界、父行程锁、Redis幂等、状态流转、打卡和Journal关联保留。
- 最新Journal搜索、DTO、ES网关、索引worker/listener、任务表及全部相关测试合入。GET /travel-posts的keyword/page/pageSize契约、固定排序及共享发布可见范围未改。
- 认证/JWT/CSRF及Journal/上传生产源码与origin/test没有差异；没有迁入原开发目录的fullName认证改动。
- 共享异常层仅增加参数类型转换400，保留既有响应契约。

## CI与第三方费用安全

`.github/workflows/ci.yaml`、`scripts/run-smokes.py`与origin/test逐字节一致。完整保留：

```text
PR to test/master or push test/master
  → local Docker MySQL / Redis / Mailpit / Elasticsearch
  → Maven package -DskipTests
  → python scripts/run-smokes.py --ci
  → CI_SCRIPTS = {"auth-smoke.py"}
  → Maven verify
```

没有定时workflow，没有添加真实Google Places/Maps/S3脚本到CI，没有添加第三方生产Secrets映射。
真实Google/S3脚本虽然由团队放在scripts/smoke，但--ci白名单不会执行它们。不得只保留新脚本目录却退回旧的全目录runner，也不得去掉--ci。
本轮从未运行不带--ci的全量smoke runner。手动persistence-acceptance也未运行。

新增测试专用防护，不改变团队CI本身：

- pom.xml Surefire的argLine将google.places.api-key、google.maps.api-key、travel.photos.bucket/base-url置空，阻止标准测试继承真实环境值并调用收费服务。
- Surefire将auth.worker-enabled和journal.search.worker-enabled设为false；Journal集成ES默认绑定127.0.0.1:19200，隔离验证通过Maven参数更改到本地19216。
- application-test.yaml也明确关闭Journal worker。
- 新增CiProviderIsolationTest检查空供应商配置、禁用worker及本地ES目标，失败消息不输出配置值。
- 另用无效合成环境变量开启worker/设置伪供应商配置，单独运行隔离检查通过；未读取或使用真实凭证，也未访问伪URL。

结论限定为当前已审工作流及标准Maven测试入口。显式手动改写JVM参数/执行真实live脚本不属于此安全保证；GitHub Actions计算资源费用与第三方API调用费用也不同。

## 最终测试证据

运行入口：

```sh
python3 scripts/validation/run-isolated.py
```

内部执行：

```sh
docker compose -p <fresh-project> -f compose.validation.yaml up -d --wait
./mvnw --batch-mode clean verify -Djournal.integration.es-url=http://127.0.0.1:19216
python scripts/run-smokes.py --ci
SMOKE_ALLOW_WRITES=1 python scripts/live/manual-plan-smoke.py
SMOKE_ALLOW_WRITES=1 python scripts/validation/local-http-regression.py
docker compose -p <fresh-project> -f compose.validation.yaml stop
```

HTTP脚本只可使用runner设置的随机项目、端口和数据库环境，不应直接对开发库执行。runner只继承PATH/HOME/JAVA_HOME/TMPDIR/LANG，不继承真实供应商或数据库配置，并拒绝读取本地.env。

| 检查 | 结果 |
| --- | --- |
| 实际整合工作区全量编译、clean verify、打包 | 170/170通过，0 failure/error/skipped；较此前临时副本169项增加1项CI隔离检查 |
| 新库schema初始化并重复执行 | 通过 |
| 旧库destination升级、重复执行、既有行程保留 | 通过 |
| --ci认证HTTP smoke | 1个脚本通过：真实HTTP/CSRF/登录/refresh/SMTP重置/撤销/退出 |
| 无Google手动行程HTTP smoke | 14项断言通过 |
| Journal与业务HTTP回归 | 19/19通过 |
| 合成环境配置隔离单元检查 | 1/1通过，不使用真实凭证，无网络请求 |
| git diff --check / staged检查 | 通过 |
| CI与runner对比origin/test | 逐字节一致 |

170项Maven测试包含：原Auth、Journal发布、Mock S3上传、自动行程、Redis幂等/并发、Places/Maps本地HTTP服务器测试、Wishlist、手动计划回滚/并发start、最新真实本地ES集成和新增CI隔离。

19项HTTP回归包括：Completed/Cancelled发布、Ongoing/外人发布拒绝、缺CSRF拒绝、真实ES中文搜索及跨作者可见性、空关键词顺序、分页、无结果、非法分页、匿名拒绝、空Wishlist/IDs，以及Places/Maps/照片非法输入在接触供应商前返回400。

测试服务使用独立项目/卷、随机密码，本地MySQL54316、Redis57389、ES19216、Mailpit18135/11135和后端8087。所有写入仅针对独立测试实例；未清理开发库或共享Redis。自建进程/容器已停止，隔离数据卷和QA账号/帖子fixture保留。

证据在忽略目录.validation：summary.json、maven-clean-verify.log、schema-new.log、schema-legacy.log、auth-http-smoke.log、manual-http-smoke.log、journal-http-regression.log、provider-environment-guard.log；逐类结果在target/surefire-reports。它们不进入Git提交。

## 凭证、未验收项目与风险

- 源码、待提交文件及最终Git树进行凭证格式检查，未发现真实Google/GitHub/AWS Key、JWT或私钥；无.env、target、.validation或pyc进入最终树。
- 新live脚本和HTTP smoke使用随机QA密码；既有单元测试字符串与Compose本地默认值仍为测试配置，不是实际生产凭证。
- 本轮没有真实Google搜索/详情/照片、Maps Static或S3上传请求。相关功能通过Mock/本地服务器及非法输入HTTP回归，不伪报供应商真实验收。
- Maps云端API未启用，真实地图验收待完成；真实S3也未验收。浏览器交互/视觉及GitHub远程Actions未运行。
- destination只增加可空列；升级需要ALTER权限并可能持有元数据锁。多实例同时启动的检查后ALTER竞争仍需由单一受控迁移解决；DDL不承诺事务回滚。
- 前端认证适配和Journal搜索在其交付目录已完成，但本轮未改前端，也未重复其25项测试。前端旧交付记录中“后端尚未整合”的说明已被本次实际后端整合结果取代。
- 手动全量保存仍无乐观版本号；两个Planning保存以最后一次为准，不扩展本轮范围。

## PR准备结论

本地feature分支已包含最新已获取origin/test，冲突已解决，测试通过，CI白名单完整，可进入后端PR准备/审查阶段。
尚未推送，因此未创建PR，也未运行远程Actions；需用户另行确认推送及创建PR。最终git status应为空，提交历史以git log为准。
