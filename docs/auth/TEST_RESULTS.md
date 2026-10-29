# v0.2.0 本地验证结果

验证日期：2026-10-28。本文件记录本版上传前已完成的本地验证。

## Maven verify

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
