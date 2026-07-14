`transdb` 是一个 **异构数据库同步平台后端（Spring Boot）**，核心能力：

- **源 → 目标** 多数据库之间的数据同步（Oracle / MySQL / 达梦 DM / PostgreSQL）。
- 多种同步模式：全量、增量、CLEAN_INSERT（先清后插）。
- **断点续传**：同步任务可暂停 / 重启，从中断点继续，不重复、不丢数据。
- **多任务并发**：线程池调度，支持大表分段 + 并行写入竞争模型。
- **安全管理**：数据库密码 RSA 传输加密 + AES 存储加密；Web 层 Sa-Token 登录鉴权。
- 配套前端 `dbtrans-dashboard` 为**原生 JS 多页应用**（非 Vue/Layui 等框架），通过 CDN 引入 `Chart.js`（图表）、`CodeMirror`（SQL 编辑器）、`sql-formatter`（SQL 格式化），自写 `js/app.js`、`js/api.js` 等模块组织逻辑。

技术栈：Spring Boot、Druid 连接池、JSqlParser、Sa-Token、Hikari/Druid、Java 原生线程池。
