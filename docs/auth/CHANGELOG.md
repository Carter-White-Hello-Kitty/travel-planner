# Authentication v0.2.0

- 从独立v0.1.0实现适配到团队现有Spring Boot 4.1.2 / Java17 / Maven / MySQL。
- 用户身份改为Long，与团队现有主键和外键一致；会话ID仍为内部UUID。
- 保留注册、登录、密码恢复、退出、刷新和JWT即时会话撤销。
- 将PostgreSQL RETURNING/ON CONFLICT/advisory-lock/部分唯一索引替换为MySQL实现。
- 保留团队现有公共实体、Repository、schema.sql、异常处理器和启动类。
- 认证异常处理限定包范围，CORS补充PUT/PATCH/DELETE以便队友接入。
- 新增独立MySQL/Mailpit Compose配置、专用测试库、测试profile及联调说明。
- 继续保留标准CSRF Token，与公共文档差异明确记录，未擅自修改公共文档。

旧版仍在本地 D:/Laioffer/Flag/backend；本分支基线及原有提交历史保留。
