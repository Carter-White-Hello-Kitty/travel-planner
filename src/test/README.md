# 测试目录约定

测试类按被测模块放在 `src/test/java/com/travel/<模块名>/`，包名与目录一致：

| 模块 | 测试目录 |
| --- | --- |
| 认证 | `auth/` |
| 行程 | `itinerary/` |
| 游记 | `journal/` |
| 心愿单 | `wishlist/` |
| POI 搜索 | `poisearch/` |
| 公共组件 | `common/` |

当前项目由 Maven Surefire 在 `test` 阶段运行以 `Test` 结尾的类，例如 `AuthIntegrationTest`。不要只把类命名为 `*IT`：项目尚未配置 Maven Failsafe，单纯改名可能导致测试不被默认执行。

集成测试统一使用 `test` profile、`travel_planner_test` 数据库和 Redis 数据库 1。各模块的测试可以准备自己的数据，但必须清理自己创建的数据库记录及 Redis key，不能连接或清空开发库或开发用 Redis 数据库 0。业务表结构统一维护在 `src/main/resources/schema.sql`；`scripts/init-test-db.sql` 只创建空测试数据库和授权，不定义模块业务表。

可放在 `src/test/resources/` 的文件包括测试数据和样例请求；新增文件时按模块建立子目录，避免与其他模块混放。
