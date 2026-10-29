# Authentication v0.2.0 — API与团队仓库对照

检查日期：2026-10-28。
来源：[公共API文档](https://docs.google.com/document/d/11pOKwrFaAaEaSDE5_OEaBUkw-nWkVdtVqj6ZO_uMAXg/edit)；
[仓库基线](https://github.com/Carter-White-Hello-Kitty/travel-planner/tree/82a44d93b1bac611ea35bf687cb79ed1ab3c86aa)。
后续已同步test的e5f96e377d6ba544437e027692316a4c2ccd3d83，并按团队README调整响应类型及Service分层。

## 保持一致的契约

六个POST路径、请求参数、成功状态码和message/data包装保持一致。
register:201；login:200；request-reset-pwd:202；reset-pwd/logout/refresh:200。
邮箱归一化、Unicode码点密码校验、未知字段400、请求体8KiB限制、429/Retry-After均保留。
JWT通常900秒，会话绝对7天，重置令牌15分钟/单次使用。
RefreshToken通过HttpOnly/Secure/SameSite=Lax/Path=/auth Cookie传递且每次轮换。
退出只撤销当前设备；改密撤销全部设备；重放撤销持久提交；会话存储不可用拒绝访问。
申请重置时不查询用户是否存在，统一202，后台持久作业投递并重试。

## 两项明确差异

| 项目 | 公共API文字 | v0.2.0实现及原因 |
| --- | --- | --- |
| 用户ID | 注册/登录示例为UUID | 使用JSON数值Long，与团队users.id BIGINT、User.id及所有关联实体一致。用户已确认此选择；公共文档尚未修改。JWT sub是该数值ID的字符串表示，可信currentUserId为Long。 |
| CSRF | Origin + X-Requested-With | 额外要求标准X-XSRF-TOKEN及对应Cookie。先GET /auth/csrf，并在每次写请求前同步Token；避免关闭Spring Security防护。此附加联调要求需团队确认后同步公共文档。 |

GET /auth/me是新增的受保护联调端点，不替代六个POST。
CORS拒绝的恶意跨源请求由安全框架返回403，正文可能是Invalid CORS request，浏览器不应依赖读取此正文。

## 队友接入

控制器用 @AuthenticationPrincipal TokenService.Identity identity 获取Long userId。
先从数据库加载资源，再调用 Ownership.requireOwner(identity, ownerId)。
资源不存在由业务模块返回404；别人的资源403；日志还需在发布时检查实时trip状态，否则409。
不会通过请求中的user_id决定身份。RefreshToken Cookie不能用于业务接口身份认证。

认证错误处理器只作用于com.travel.auth且具有明确优先级，保留团队GlobalExceptionHandler。
Controller统一声明返回com.travel.common.Result；该公共类型兼容保留两参数构造器及原success/failure工厂，新增可选error和自定义成功message。
普通响应仍只有message/data，认证失败才输出error；序列化兼容性有专门测试。
AuthService只定义业务接口，AuthServiceImpl实现事务逻辑，AuthUserStore负责共享用户查询与行锁。
未改动公共实体/Repository和schema.sql；私有Auth表通过额外auth-schema.sql初始化。
主项目仍为Java17/Maven/MySQL，不引入第二套Gradle、PostgreSQL或重复启动类。

## 需要团队确认的剩余边界

公共文档同步数值user.id与CSRF握手；刷新接口仍标为补充提案。
所有业务路径默认认证，公开POI/日志浏览是否开放需负责人明确后配置，不能猜测放开。
认证模块不实现队友的收藏、行程、POI和日志CRUD。
现有数据库增量初始化使用CREATE TABLE IF NOT EXISTS，不删除/重建公共表；私有表后续变更应纳入团队迁移规范。
