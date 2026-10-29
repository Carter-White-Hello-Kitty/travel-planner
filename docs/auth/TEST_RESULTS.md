# v0.2.0 本地验证结果

验证日期：2026-10-28。本文件记录本版上传前已完成的本地验证。

## Maven verify

### 最新test同步与团队规范重构后的验证

基线：test e5f96e377d6ba544437e027692316a4c2ccd3d83。
命令：mvnw.cmd -B -ntp clean verify。
结果：BUILD SUCCESS；26 tests，0 failures，0 errors，0 skipped。
组成：20项认证集成测试、5项公共Result/返回类型/Service分离兼容测试、1项context测试。
最新test已移除旧实体示例测试，本分支保留上游该修改，因此不再计入它。
根README与最新test一致，认证指南在docs/auth/DEVELOPMENT.md。
重构后的可执行JAR已重新通过真实HTTP/CSRF/SMTP-Mailpit smoke检查，注册至密码恢复及登出流程均PASS。

### 初始v0.2.0上传前记录

命令：mvnw.cmd -B -ntp verify
结果：BUILD SUCCESS；22 tests，0 failures，0 errors，0 skipped。
其中20项认证集成测试，另含仓库原有1项实体持久化测试及1项context测试。
Java编译目标为17，javap验证AuthService.class major version 61；测试执行JDK为本机Temurin 21.0.12。
数据库为真实MySQL 8.4（专用库travel_planner_auth_test），未用H2替代。

## 覆盖点

邮箱归一化/Argon2id、注册重复/未知字段、密码Unicode长度；
JWT签名/过期/会话、真实CSRF Cookie握手、非法来源/媒体类型/8KiB；
刷新单次轮换/重放持久撤销、并发刷新、会话绝对过期；
设备隔离/幂等登出；单次重置/并发重置/全部设备撤销；
未知邮箱一致响应、SMTP失败持久重试、数据库故障拒绝访问；
数值userId与团队表一致；跨域DELETE预检；共享Ownership越权拒绝。
团队现有实体和持久化测试源码没有修改。

## 打包后真实HTTP与SMTP

运行：java -jar target/travel-planner-0.2.0-auth-SNAPSHOT.jar --spring.profiles.active=auth-local --server.port=18086
检查：python scripts/auth-smoke.py
结果：PASS — real HTTP, CSRF, registration, login, refresh, SMTP/Mailpit, reset, revocation, logout。
邮件进入localhost:18025的Mailpit，未向外部邮箱发送。
脚本断言登录响应user.id为数值。验证结束后临时后端进程已停止。

## 提交检查

新增Auth私有表；原schema.sql、公共实体/Repository和原有测试源码保留。
不提交.env、实际密钥、数据库卷、target/JAR、运行日志。
上传前通过git diff --cached --check并检查暂存文件清单。
