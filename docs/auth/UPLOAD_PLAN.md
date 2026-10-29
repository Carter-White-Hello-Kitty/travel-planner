# 上传审批计划：Authentication v0.2.0

状态：用户已确认按本计划上传新分支并创建Draft PR。实际分支和PR结果以GitHub页面为准。
仓库：https://github.com/Carter-White-Hello-Kitty/travel-planner
基线：82a44d93b1bac611ea35bf687cb79ed1ab3c86aa（检查时master/test相同）。
本地工作副本：D:/Laioffer/Flag/travel-planner-auth-v0.2.0
旧独立版：D:/Laioffer/Flag/backend，保持原样。

## 请求批准的远程动作

1. 上传前重新读取远程test/master及分支名，确认有无新提交。
2. 创建新的远程分支 feature/auth-v0.2.0，推送本地审查通过的提交。
   如该分支名被占用，先报告，不覆盖或强推既有分支。
3. 创建目标为test的Draft Pull Request：
   feat(auth): add v0.2.0 MySQL authentication module
4. 在PR说明中列明API差异、文件范围、验证结果、前端CSRF调用和团队接入方式。
5. 返回分支链接、PR链接和提交SHA，供用户/组长审核；合并由团队决定。

批准范围是新分支与Draft PR；不包括合并、修改master/test、发布Release或改动公共Google Doc。

## 上传内容与预期展示

- src/main/java/com/travel/auth：六个接口、JWT、密码、会话、邮件、限流及安全配置。
- auth-schema.sql：新增私有表，与现有MySQL公共实体共享数值userId。
- Maven依赖及项目版本0.2.0-auth-SNAPSHOT。
- MySQL/Mailpit本地配置、专用测试库、认证集成测试。
- README、API兼容说明、变更日志、运行与密钥生成脚本。

GitHub默认master页面保持当前内容；切换feature/auth-v0.2.0可查看新版README和认证代码。
Draft PR的Files changed集中展示新增认证包，以及pom.xml/application.yaml/.gitignore的少量接入变更。
本地旧版没有替换，仓库原提交历史保留；认证代码在新分支可审查、可撤回。
实际.env、PEM/对称密钥、数据库卷、target/JAR、测试运行日志均不上传。

## 明确的契约变化

- 用户ID：采用团队Long/BIGINT，用户已确认；JSON为数值，JWT sub为该ID字符串。
- CSRF：保留标准Token握手，前端GET /auth/csrf后携带X-XSRF-TOKEN及Cookie。
  公共API尚未写此要求，需团队同步；公共Google Doc没有由本次操作修改。
- CORS补充PUT/PATCH/DELETE，所有业务写请求也保留标准CSRF校验。
- 其他模块资源归属和行程状态检查由相应负责人接入，Auth不代写其CRUD。

测试汇总见 TEST_RESULTS.md。最终以上传前的git diff、测试报告及用户确认内容为准。
