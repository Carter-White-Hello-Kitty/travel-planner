# Travel Planner Backend

旅行规划应用的 Java 后端服务。提供用户认证（JWT）、行程、游记、心愿单、POI 搜索等功能。

## 技术栈

- Java 17
- Spring Boot 4.1.2
- Spring Data JPA（Hibernate）
- Spring Data Redis
- Spring Security（JWT 无状态认证）
- MySQL 8
- Maven（仓库自带 Maven Wrapper，无需单独安装）
- Lombok

## 前置要求

- JDK 17+
- Docker Desktop（提供本地 MySQL、Redis 和 Mailpit）

## 环境配置（Profile）

项目通过 Spring Profile 区分环境：

| Profile | 用途 | 数据库 |
|---------|------|--------|
| `dev`（默认） | 本地开发、冒烟测试 | Docker MySQL `53306` 的 `travel_planner` |
| `test` | 自动化集成测试 | 同一个 Docker MySQL `53306` 的 `travel_planner_test` |
| `prod` | 生产 | 环境变量注入（yaml 里无真实密钥） |

---

## 一、本地开发（起服务）

### 1. 克隆仓库

```bash
git clone https://github.com/Carter-White-Hello-Kitty/travel-planner.git
cd travel-planner
```

### 2. 配置 `.env`

在项目根目录创建 `.env` 文件（**已被 `.gitignore` 忽略，不会提交**）：

```dotenv
DB_HOST=localhost
DB_PORT=53306
DB_USERNAME=travel
DB_PASSWORD=local-only-change-me
REDIS_PORT=56379
```

`DB_PORT` 和 `DB_PASSWORD` 同时供 Compose、开发服务和集成测试使用。示例密码仅用于本机；如需修改，请在首次启动 Docker 前修改 `.env`。已有数据卷不会因为修改密码或初始化脚本而重新初始化。

### 3. 启动本地依赖和服务

```bash
docker compose up -d --wait
./mvnw spring-boot:run
```

Compose 启动 MySQL、Redis 和 Mailpit；MySQL 容器内有开发库 `travel_planner` 和测试库 `travel_planner_test`。默认使用 `dev` profile 的 Java 服务连接开发库及 Redis 的数据库 0，监听 `http://localhost:8085`。Java 服务启动时会执行共享的 `schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，幂等）。Compose 不启动 Java 服务。

---

## 二、本地测试

### 1. 启动本地依赖（若已在开发时启动，可跳过）

```bash
docker compose up -d --wait
```

会启动三个容器：

- **MySQL 8.4**：`localhost:53306`，用户 `travel` / 默认密码 `local-only-change-me`；包含开发库 `travel_planner` 和独立测试库 `travel_planner_test`
- **Mailpit**（假邮箱）：SMTP `localhost:11025`，网页收件箱 `http://localhost:18025`
- **Redis**：`localhost:56379`；开发使用数据库 0，自动化测试使用数据库 1

开发库由 MySQL 镜像的 `MYSQL_DATABASE` 创建；测试库由 `scripts/init-test-db.sql` 创建。这两个动作只在空 MySQL 数据卷首次初始化时发生。业务表由 Java 服务启动时执行 `schema.sql` 创建。

若本机 `53306` 或 `56379` 端口已被占用，可在 `.env` 中修改 `DB_PORT` 或 `REDIS_PORT`，再启动 Compose、Java 服务和 Maven 测试。Mailpit 的 `11025`、`18025` 端口也必须空闲；只运行集成测试时，至少启动 `mysql` 和 `redis` 服务。

### 2. 冒烟测试

冒烟测试通过真实 HTTP 请求检查各模块的核心流程。先启动 Java 服务（`dev` profile，连接开发库 `travel_planner`），再另开终端运行所有冒烟脚本：

```bash
# 终端 1：启动后端，保持运行
./mvnw spring-boot:run

# 终端 2：运行所有冒烟脚本
SMOKE_URL=http://localhost:8085 python3 scripts/run-smokes.py
```

所有脚本通过时，统一入口会输出 `PASS`；任一脚本失败会返回非零退出码。冒烟测试会操作开发库，涉及邮件的流程可在 Mailpit 的 `http://localhost:18025` 查看。

### 3. 集成测试

MySQL 和 Redis 容器启动后直接运行，不需要先启动 Java 服务：

```bash
./mvnw clean verify
```

测试使用 `test` profile，连接 Docker 中独立的 `travel_planner_test`，不会清理开发库。测试类按模块放在 `src/test/java/com/travel/<模块名>/`；各模块负责隔离和清理自己创建的测试数据，具体约定见 `src/test/README.md`。

### 4. 测试约定

新模块的冒烟脚本放在 `scripts/smoke/`，按 `<模块名>-smoke.py` 命名，例如 `itinerary-smoke.py`。脚本应独立运行，通过真实 HTTP 验证一条核心流程，失败时返回非零退出码；不要连接生产环境。`scripts/run-smokes.py` 会按文件名顺序自动运行所有匹配 `*-smoke.py` 的文件，本地和 CI 使用同一个入口。CI 会先打包并运行冒烟测试，通过后再运行集成测试。

冒烟测试的第三方 Python 依赖统一写在根目录的 `requirements-smoke.txt`，并固定版本；当前脚本只用标准库，所以文件暂时没有包。新增依赖时，把依赖声明和使用它的冒烟脚本放在同一个 PR。CI 会先安装该文件中的依赖，再运行冒烟测试。本地需要这些依赖时，在项目根目录创建独立环境并安装：

```bash
python3 -m venv .venv-smoke
source .venv-smoke/bin/activate
python -m pip install -r requirements-smoke.txt
```

激活后照常运行 `python3 scripts/run-smokes.py`；`.venv-smoke/` 已加入 `.gitignore`。

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

## Travel Journal 搜索

Java 搜索接口、Elasticsearch 配置、与发布模块的事务交接及测试见 [搜索模块说明](docs/journal-search.md)。首次使用需启用索引 worker；默认不增加其他模块启动时对 ES 的依赖。
