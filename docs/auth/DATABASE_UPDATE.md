# Authentication v0.2.0 数据库变更与本地更新

本次同步基线：origin/test e5f96e377d6ba544437e027692316a4c2ccd3d83。
使用团队现有MySQL数据库travel_planner，用户ID沿用BIGINT。

## 表结构变更

只新增以下Auth私有表，定义见src/main/resources/auth-schema.sql：

| 表 | 关键字段与用途 |
| --- | --- |
| auth_sessions | id(UUID字符串)、user_id、expires_at、revoked_at、created_at；保存设备会话及即时撤销状态 |
| auth_refresh_tokens | id、session_id、token_hash、used、expires_at、created_at；active_session生成列及唯一索引保证每会话仅一个未使用令牌 |
| auth_mail_jobs | id、email、status、token_hash、encrypted_payload、attempts、next_attempt_at、expires_at、created_at；持久邮件作业与重试 |
| auth_rate_limits | bucket_key、count、expires_at；HMAC标识的固定窗口计数 |
| auth_worker_lock | 单行id=1；事务行锁协调多个实例的邮件worker |

不删除、重建或修改users、pwd_reset_tokens及其他业务公共表的字段。
原src/main/resources/schema.sql保留不变；application.yaml仅追加auth-schema.sql初始化位置及Auth配置导入。

## 已有本地MySQL开发库

1. 切换并更新feature/auth-v0.2.0，在项目根目录准备原有.env数据库配置。
2. 确认travel_planner数据库已经存在，并沿用现有用户与数据；正常更新不需要清空数据库。
3. 配置认证密钥：本地可使用auth-local profile；正式环境按DEVELOPMENT.md配置持久密钥与SMTP。
4. 应用启动时按顺序执行原schema.sql及新增auth-schema.sql。CREATE TABLE IF NOT EXISTS不会覆盖现有表。

若要先手动检查并执行新增表脚本，可在项目根目录打开MySQL客户端：

```text
mysql -h <DB_HOST> -P <DB_PORT> -u <DB_USERNAME> -p travel_planner
```

在MySQL命令行中执行：

```sql
SOURCE src/main/resources/auth-schema.sql;
SHOW TABLES LIKE 'auth_%';
```

请只在自己负责的开发库执行。存在不同版本的同名Auth表时，应先对照字段和索引；
CREATE TABLE IF NOT EXISTS不会修复结构差异，需团队另行审查增量迁移，不能删除现有表来规避。

## 隔离开发与测试（推荐验证方式）

```powershell
docker compose -p flag-auth-v020 -f compose.auth.yaml up -d --wait
.\scripts\Prepare-AuthTestDb.ps1
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=auth-local"
```

Compose使用localhost:53306，开发库travel_planner、测试库travel_planner_auth_test。
测试profile只连接专用测试库；认证测试清理前核对数据库名。
不要把测试profile指向团队共享库或生产库。

## 回退

本功能尚未合并时，切回test即可运行旧代码；保留新增Auth表不会影响旧版公共实体。
不提供自动删表回退，避免误删令牌、会话或队友数据。
