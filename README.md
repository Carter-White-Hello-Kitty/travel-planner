# Travel Planner Backend

旅行规划应用的 Java 后端服务。提供用户认证（JWT）、行程、游记、心愿单、POI 搜索等功能。

## 技术栈

- Java 17
- Spring Boot 4.1.2
- Spring Data JPA（Hibernate）
- Spring Security（JWT 无状态认证）
- MySQL 8
- Maven（仓库自带 Maven Wrapper，无需单独安装）
- Lombok

## 前置要求

- JDK 17+
- Docker Desktop（跑自动化测试用）
- MySQL 8（本地开发用，可本地安装，也可复用 Docker）

## 环境配置（Profile）

项目通过 Spring Profile 区分环境：

| Profile | 用途 | 数据库 |
|---------|------|--------|
| `dev`（默认） | 本地开发 | 本地 MySQL `3306` 的 `travel_planner` |
| `test` | 自动化测试 | Docker MySQL `53306` 的 `travel_planner_auth_test` |
| `prod` | 生产 | 环境变量注入（yaml 里无真实密钥） |

---

## 一、本地开发（起服务）

### 1. 克隆仓库

```bash
git clone https://github.com/Carter-White-Hello-Kitty/travel-planner.git
cd travel-planner
```

### 2. 建本地数据库

在本地 MySQL 里创建开发库：

```sql
CREATE DATABASE travel_planner
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

### 3. 配置 `.env`

在项目根目录创建 `.env` 文件（**已被 `.gitignore` 忽略，不会提交**）：

```dotenv
DB_HOST=localhost
DB_PORT=3306
DB_USERNAME=root
DB_PASSWORD=你的MySQL密码
```

### 4. 启动服务

```bash
./mvnw spring-boot:run
```

默认使用 `dev` profile，监听 `http://localhost:8085`。启动时会自动执行 `schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，幂等）。

---

## 二、本地测试（跑自动化测试）

### 1. 启动 Docker（测试库 + 假邮箱）

```bash
docker compose up -d
```

会启动两个容器：

- **MySQL 8.4**：`localhost:53306`，用户 `travel` / 密码 `local-only-change-me`，自动创建测试库 `travel_planner_auth_test`
- **Mailpit**（假邮箱）：SMTP `localhost:11025`，网页收件箱 `http://localhost:18025`

> 测试库由 `scripts/auth-init-test-db.sql` 在首次启动时自动创建。

### 2. 跑测试

```bash
./mvnw clean verify
```

测试会连 Docker 的 `travel_planner_auth_test`（每测一个自动清空一次）。21 个测试全绿即通过。

---

## 三、密码重置测试（可选）

本地开发时，密码重置的邮件会发到 Docker 的 Mailpit：

```bash
# 终端 1：起服务
./mvnw spring-boot:run

# 终端 2：跑端到端冒烟测试（注册 → 登录 → 刷新 → 重置 → 收邮件 → 登出）
SMOKE_URL=http://localhost:8085 python3 scripts/auth-smoke.py
```

输出 `PASS` 即整条认证链路（含真发邮件）都通。邮件在 http://localhost:18025 查看。

---

## 项目结构

```
com.travel
├── common/          # 共享内核：Result、ApiError、ApiSupport、GlobalExceptionHandler
├── auth/            # 认证模块（controller/service/entity/repository/config/security/support/constant）
├── itinerary/       # 行程
├── journal/         # 游记
├── wishlist/        # 心愿单
└── poisearch/       # POI 搜索
```

团队代码约定：

- Controller 统一返回 `com.travel.common.Result`。
- Service 采用接口 + 实现类分离（`service` 放接口，`service/impl` 放实现）。
- 数据库等环境相关配置用环境变量注入（`.env` / 环境变量），**不要把密码或密钥提交到 GitHub**。

---

## GitHub 团队协作流程

分支用途：

- `master`：稳定版本。
- `test`：团队集成与测试分支。
- `feature/<功能名>`：单个功能的开发分支，从最新的 `test` 创建。

组员开发一个功能时：

```bash
# 开始新功能前，先更新 test
git checkout test
git pull origin test

# 从 test 创建功能分支
git checkout -b feature/功能名

# 开发完成后提交并推送
git add .
git commit -m "feat: 描述本次改动"
git push -u origin feature/功能名
```

随后在 GitHub 上创建 Pull Request：

- 功能分支合并目标选择 `test`。
- 在 PR 的 **Reviewers** 中指定组长审查。
- 根据审查意见修改代码并再次推送，PR 会自动包含新提交。
- 获得批准后，由有权限的组长合并 PR。

## 提交前检查

```bash
git status
git diff
```

确认只提交本次功能需要的文件，并且**没有 `.env`、密码、访问令牌或其他本地私密配置**（`.gitignore` 已忽略这些文件，但提交前仍需自查）。
