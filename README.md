# Travel Planner Backend

Travel Planner 项目的 Java 后端服务。项目使用 Spring Boot、Spring Web MVC、Spring Data JPA 和 MySQL。

> 当前仓库是后端开发骨架，已包含基础实体、Repository、统一响应类型和异常处理结构。各业务模块的 API 与业务逻辑由团队成员在对应 feature 分支中逐步实现。

## 技术要求

- Java 17
- MySQL
- Maven Wrapper（仓库已提供，无需单独安装 Maven）

## 项目结构

```text
src/
├── main/
│   ├── java/com/travel/
│   │   ├── common/       # 通用响应类型等
│   │   ├── config/       # 应用配置相关
│   │   ├── controller/  # HTTP API 控制器
│   │   ├── entity/      # JPA 实体
│   │   ├── exception/   # 全局异常处理
│   │   ├── repositories/# Spring Data JPA Repository
│   │   ├── service/     # 业务接口与实现
│   │   └── vo/          # 视图对象
│   └── resources/
│       ├── application.yaml
│       └── schema.sql
└── test/                 # 自动化测试
```

团队代码约定：

- Controller 统一使用 `com.travel.common.Result` 作为响应格式。
- Service 采用接口与实现类分离的方式。
- 数据库连接等本地配置通过环境变量提供，不要把密码或其他密钥提交到 GitHub。

## 首次启动

### 1. 克隆仓库

```bash
git clone https://github.com/Carter-White-Hello-Kitty/travel-planner.git
cd travel-planner
```

### 2. 创建本地数据库

应用连接的数据库名是 `travel_planner`。先在本地 MySQL 中创建该数据库：

```sql
CREATE DATABASE travel_planner
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

### 3. 配置本地数据库连接

在项目根目录创建 `.env` 文件：

```dotenv
DB_HOST=localhost
DB_PORT=3306
DB_USERNAME=你的MySQL用户名
DB_PASSWORD=你的MySQL密码
```

`.env` 是本地文件，不要提交到 GitHub。仓库的 `.gitignore` 已忽略 `.env` 文件。

`application.yaml` 使用以上变量连接数据库；服务默认监听 `8085` 端口。

### 4. 启动服务

macOS / Linux：

```bash
./mvnw spring-boot:run
```

Windows：

```bat
mvnw.cmd spring-boot:run
```

服务启动后，地址为：`http://localhost:8085`。

## 数据库初始化说明

应用启动时会执行 `src/main/resources/schema.sql` 中的建表语句。数据库 `travel_planner` 必须先存在，否则应用无法连接。

当前仓库通过基础 SQL 文件初始化表结构。新增或修改数据库结构时，需要同步考虑已有开发者的数据库；`CREATE TABLE IF NOT EXISTS` 不会自动修改已经存在的表。涉及数据库结构变更时，请在 PR 描述中说明变更内容和本地更新步骤。

## 运行测试

```bash
./mvnw test
```

目前的 Spring 集成测试会启动应用并连接 MySQL。运行前请确保 MySQL 已启动、`travel_planner` 数据库已创建，并且 `.env` 配置有效。

## GitHub 团队协作流程

分支用途：

- `master`：稳定版本。
- `test`：团队集成与测试分支。
- `feature/<功能名>`：单个功能的开发分支，从最新的 `test` 创建。

组员开发一个功能时：

```bash
# 第一次在本地创建并跟踪远程 test 分支
git checkout -b test --track origin/test

# 开始新功能前，先更新 test
git checkout test
git pull origin test

# 从 test 创建功能分支
git checkout -b feature/功能名

# 开发完成后提交并推送功能分支
git add .
git commit -m "feat: 描述本次改动"
git push -u origin feature/功能名
```

随后在 GitHub 上创建 Pull Request：

- 组员的功能分支合并目标选择 `test`。
- 在 PR 的 **Reviewers** 中指定组长审查。
- 根据审查意见修改代码并再次推送到同一个功能分支；PR 会自动包含新提交。
- 获得批准后，由有权限的组长在 GitHub 上合并 PR。

功能在 `test` 验证通过后，由组长创建从 `test` 到 `master` 的 PR，审查并合并。不要直接向受保护的 `test` 或 `master` 推送代码。

如果本地 Git 版本不支持 `git switch`，使用 `git checkout` 切换分支。

## 提交前检查

```bash
git status
git diff
```

确认只提交本次功能需要的文件，并且没有 `.env`、密码、访问令牌或其他本地私密配置。
