# Travel Planner · Authentication v0.2.0

在团队现有 Spring Boot 4.1.2 / Java 17 / Maven / MySQL 项目中加入 Authentication & Authorization。
本地准备分支：`feature/auth-v0.2.0`；本次准备基于 `82a44d93b1bac611ea35bf687cb79ed1ab3c86aa`。
本版通过独立功能分支提交；对应Draft PR供团队审核，不自动合并到test或master。

## 启动

需要 Java 17+ 和 Docker Desktop。在项目根目录执行：

```powershell
docker compose -p flag-auth-v020 -f compose.auth.yaml up -d --wait
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=auth-local"
```

- 后端：http://localhost:8085
- 本地邮件收件箱：http://localhost:18025
- MySQL：localhost:53306；开发库travel_planner，专用测试库travel_planner_auth_test。
- auth-local使用开发密钥和HTTP Cookie。正式环境不使用此profile；配置JWT/对称密钥、SMTP与数据库秘密。
- 开发密钥每次重启变化，已有JWT失效，待发邮件加密载荷无法恢复；正式环境必须使用持久密钥。

## 接口

六个POST路径保留公共API：`/auth/register`、`/auth/login`、`/auth/request-reset-pwd`、
`/auth/reset-pwd`、`/auth/logout`、`/auth/refresh`。
另有 GET /auth/csrf（标准CSRF握手）、GET /auth/me（受保护的身份联调端点）。

公共API对应情况、两项明确差异、跨模块接入说明见 [API兼容说明](docs/auth/API_COMPATIBILITY.md)。

### 前端调用

Spring Security保留标准CSRF校验。在同一Cookie会话中，每次写请求前获取当前CSRF Token：

```javascript
const base = "http://localhost:8085";
const { data } = await fetch(base + "/auth/csrf", {
  credentials: "include"
}).then(r => r.json());
const result = await fetch(base + "/auth/login", {
  method: "POST",
  credentials: "include",
  headers: {
    "Content-Type": "application/json",
    "X-Requested-With": "XMLHttpRequest",
    "X-XSRF-TOKEN": data.token
  },
  body: JSON.stringify({
    email: "user@example.com",
    password: "TravelPlanner!2026"
  })
}).then(r => r.json());
```

默认Origin白名单：http://localhost:3000；Origin由浏览器生成。
AccessToken只存在前端内存，通过Bearer头发送；RefreshToken为HttpOnly Cookie。
刷新须做跨标签页单次请求协调；旧RefreshToken重放会撤销当前设备会话。

## 验证与打包

Compose初始化脚本自动创建独立测试库和授权。已有卷若缺少测试库，先运行 scripts/Prepare-AuthTestDb.ps1。
测试资源自动选择auth-test；认证测试清理前会核对库名，拒绝清理其他数据库。
仓库原有实体持久化测试和context测试也运行在专用测试库中，原测试源码未修改。

```powershell
.\mvnw.cmd -B -ntp verify
```

报告在 target/surefire-reports；可执行包 target/travel-planner-0.2.0-auth-SNAPSHOT.jar。
真实HTTP/SMTP smoke检查：

```powershell
java -jar target/travel-planner-0.2.0-auth-SNAPSHOT.jar --spring.profiles.active=auth-local --server.port=18086
# 另一终端：
python scripts/auth-smoke.py
```

该脚本仅允许localhost，在开发库创建独立smoke账户，邮件只进入本地Mailpit。

## 结构

- src/main/java/com/travel/auth：认证接口、安全配置、JWT/密码、MySQL会话、限流、邮件作业。
- src/main/resources/auth-schema.sql：仅新增Auth私有表；原schema.sql及实体不修改。
- docs/auth：API差异、发布变更说明、上传计划。
- scripts/New-AuthSecrets.ps1：生成RSA与独立AES/HMAC密钥，已有secrets目录拒绝覆盖。

## 正式配置

AUTH_ORIGINS、FRONTEND_URL使用实际可信HTTPS域名；COOKIE_SECURE=true，AUTH_DEV_KEYS=false。
JWT_PRIVATE_KEY/JWT_PUBLIC_KEY为PKCS#8/X.509 PEM文件路径，RSA至少2048位。
AUTH_MAIL_KEY和AUTH_RATE_KEY各为32随机字节base64；不能使用同一个密钥。
SMTP_HOST/PORT/USER/PASSWORD/AUTH/TLS、MAIL_FROM按发送服务配置。
数据库设置DB_HOST、DB_PORT、DB_USERNAME、DB_PASSWORD；不要沿用仓库原有开发默认口令。
MySQL连接应使用UTC并强制会话UTC（connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true）。

.env、实际密钥、数据库数据、target构建产物不提交。
实现使用参数化SQL和事务；所有业务路径默认要求Bearer身份。
CORS允许GET/POST/PUT/PATCH/DELETE/OPTIONS，写请求保持CSRF保护，队友前端需复用以上握手。
