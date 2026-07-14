# 项目问题清单（data-synchronization）

> 分析对象：`transdb`（Spring Boot 后端）+ `dbtrans-dashboard`（前端）
> 分析时间：2026-07-14
> 使用方式：按优先级逐个过，每条标注了位置、问题、建议。

优先级说明：
- **P0**：高危，可能导致丢数据 / 安全泄露 / 严重性能问题，优先修
- **P1**：中危，数据损坏风险或明显性能瓶颈
- **P2**：低危，架构 / 可维护性 / 体验优化

---

## 一、安全问题（P0）

### 1. 数据库密码明文硬编码并提交到 Git ✅ 已修复（环境变量占位符方案）
- 位置：`transdb/src/main/resources/application-dev.properties`
```properties
spring.datasource.url=jdbc:oracle:thin:@11.101.2.12:1521/gongsi
spring.datasource.password=scxdcj
```
- 问题：生产库地址、账号、明文密码直接进版本库（git status 显示该文件已被修改跟踪）。一旦仓库泄露，数据库直接暴露。
- 建议：改用环境变量 / 配置中心 / 启动参数注入；把 `application-dev.properties` 从库中移除并加进 `.gitignore`；立即轮换数据库密码。

### 2. 登录接口无防暴力破解
- 位置：`SyncUserServiceImpl.login` + `SaTokenConfigure`（放行 `/api/v1/auth/login`）
- 问题：没有失败次数限制 / 账号锁定 / 验证码 / 限流，配合放行的登录接口，可被撞库。
- 建议：加失败计数 + 锁定（如 5 次锁定 10 分钟），或加登录限流（IP 维度）。

---

## 二、正确性 Bug（P0，会导致丢数据 / 漏同步）

### 3. marker（增量水位）全部用字符串 `compareTo` 比较
- 位置：`SyncExecServiceImpl` 的 `getMaxMarker`、`buildMarkerRange`、断点续传比较、流式 `currentMarker.compareTo(endMarker)` 等
```java
// getMaxMarker 示例
if (max == null || s.compareTo(max) > 0) max = s;
```
- 问题：水位线是**字符串字典序**比较，只有当 marker 是标准 `YYYY-MM-DD...` 日期串（字典序=时间序）时才正确。若用户把**数字自增 ID** 当 marker，`"10" < "9"` 会导致水位线错乱 → 漏数据或死循环。而 `buildWhere` 对 Oracle 又强制 `TO_DATE(?)`，数字 marker 会直接抛错。等于隐性地"只支持日期型 marker"，但界面没有任何约束提示。
- 建议：marker 比较根据字段类型走对应比较（数值用 `BigDecimal`、时间用 `Timestamp`），或在任务配置处显式声明 marker 类型并做校验。

### 4. marker 被截断到 19 字符，丢失毫秒精度
- 位置：`SyncExecServiceImpl` 约 953 行 + `WhereClause.gt/le`
```java
String mvStr = mv.toString();
if (mvStr.length() > 19) mvStr = mvStr.substring(0, 19);
currentMarker = mvStr;
```
- 问题：截断到秒级。若同一秒内有多个不同毫秒的行，续传 / 窗口边界用 `> 秒级值` 会**重复或漏掉同秒记录**。
- 建议：保留完整精度（至少毫秒），或在边界处理上用 `>= 且去重 + 主键兜底` 避免重复。

### 5. BLOB 超限静默置 null 丢数据
- 位置：`JDBCUtils.java` 约 110 行
```java
if (len > maxBlobSize) {
    log.warn(...);
    value = null;   // 直接变 null 写入目标
}
```
- 问题：超过阈值的 BLOB 直接变 null 写入目标，数据静默损坏，只有一行 warn 日志，用户无感知。
- 建议：记为失败行 / 跳过整行并计入错误数，或提供配置让超限时整任务失败，而不是悄悄丢数据。

---

## 三、性能问题

### 6. 并行读取实际退化为串行 —— 持锁做数据库 IO（P0）
- 位置：`SyncExecServiceImpl.claimBatch` 约 723–755 行
```java
batchLock.lock();
try {
    try (Connection sourceConn = getConn(sourceDbConn)) {
        List<Map<String, Object>> rows = jdbcUtils.queryOut(...);  // 锁内做整批出库查询
```
- 问题：`sourceParallelism` 个 worker 抢同一把 `batchLock`，锁内做整批出库查询，其它 worker 全阻塞。所谓"并行分页"基本变成串行读取 + 并行写入。
- 建议：把"分配区间"和"实际查询"分离 —— 锁内只算 `[start, end]` 区间并登记，锁外各 worker 自行查询源库，释放锁的占用。

### 7. 日志列表全量加载内存再分页（P0）
- 位置：`SyncLogServiceImpl.java` 约 62–80 行
```java
List<SyncTaskLog> allLogs = logMapper.listByCondition(params);  // 全查进内存
int total = allLogs.size();
pageList = allLogs.subList(fromIndex, toIndex);                  // 内存切片
```
- 问题：每次翻页都把所有匹配日志全查进内存再 `subList`。配合问题 8（日志无限增长），随时间必然拖慢甚至 OOM。
- 建议：下推 `LIMIT/OFFSET` + `COUNT` 到 SQL，分页交给数据库。

### 8. 日志表无任何清理 / 归档机制（P0）
- 位置：全项目搜不到删除历史日志的逻辑，`SYNC_TASK_LOG` 只增不减。
- 问题：日志表无限膨胀，既拖慢查询（见 #7），也占满磁盘。
- 建议：加定时清理（保留 N 天，如 30/90 天）或分区归档。

### 9. 每个批次都重复查询目标表元数据（P1）
- 位置：`JDBCUtils.java` 约 241 行（`mergeInto` 内 `getColumns(...)`）
- 问题：每批都 `getMetaData().getColumns()`（还可能触发第二次不带 schema 的兜底查询）。高并发多批次下元数据查询开销可观。
- 建议：按 `(connId, table, schema)` 缓存列信息与主键。

### 10. 每次借连接都做一次 AES 解密（P1）
- 位置：`SyncExecServiceImpl.getConn` 约 1599 行
```java
String decryptedPassword = passwordEncryptService.aesDecrypt(conn.getPassword());
```
- 问题：`getConn` 在 worker 循环里被高频调用，每次都解密密码。连接池已按 connId 缓存，密码只在建池时需要。
- 建议：只在 `createDataSource` 时解密一次，或缓存解密后的明文。

### 11. 连接池 maxActive=20 与写并发不匹配（P1）
- 位置：`DataSourceUtils`（maxActive=20, maxWait=30s） vs `mergeExecutor`（最大 40 线程）
- 问题：40 个写线程并发打同一个目标库，但单目标连接池只有 20，超出后等 `maxWait=30s` 才抛错。并行度参数之间缺乏一致性校验，压力下易报"获取连接超时"。
- 建议：让目标连接池上限 ≥ 写线程数，或加参数一致性校验 / 文档说明。

### 12. 每次同步前多次全表扫描（P1）
- 位置：`queryCount`（`SELECT * FROM (...) t` 外包再 count）、`queryMinMarker`、`queryMaxMarker` 各自全表聚合
- 问题：一次同步启动前对大表要扫好几遍，且 `queryCount` 用子查询 count 较慢。
- 建议：合并成一条 SQL 同时取 min/max/count，并依赖 marker 字段索引。

---

## 四、可扩展性 / 架构（P2）

### 13. 全内存状态，无法多实例部署
- 位置：`SyncExecServiceImpl` 的 `runningTasks`、`progressMap`、`taskStartTimes`、`cancelledTasks`；`ScheduleRunner` 的 `lastTriggerMinute`
- 问题：都是单机 `ConcurrentHashMap`。一旦部署多实例：调度会**重复触发**、进度查询打到别的实例查不到、停止/暂停信号失效。
- 建议：若有多实例需求，引入分布式锁/协调（数据库行锁、Redis），状态外置。至少文档明确"仅支持单实例"。

### 14. 废弃 API 与漏触发风险
- 位置：`ScheduleRunnerServiceImpl.isCronMatch` 约 80–92 行
```java
@SuppressWarnings("deprecation")
CronSequenceGenerator gen = new CronSequenceGenerator(cron);
```
- 问题：`CronSequenceGenerator` 已废弃（建议 `CronExpression`）。判断逻辑是"下一次执行点距现在 < 60s"，若 `@Scheduled(fixedRate=60s)` 因 GC/负载抖动 >60s，会**整分钟漏触发**且不补偿。
- 建议：改用 `CronExpression` 计算下次触发时间并做补偿（记录上次触发时间，错过则补跑）。

---

## 五、代码质量 / 可维护性（P2）

### 15. 大量死代码
- 位置：`SyncExecServiceImpl` 约 1304 行起标注"死代码(旧分段模型)"，`doSyncSegment`（两个重载）、`readAndMap`、`applyFieldMapping` 等约 130 行仅服务于废弃路径。
- 建议：删除死代码，降低阅读和维护成本。

### 16. `SyncExecServiceImpl` 单类 1600+ 行、方法参数爆炸
- 位置：`runParallelSync` / `batchWorkerLoop` / `writeBatchRange` 等方法参数多达 20+ 个
- 建议：抽出 `SyncContext` 上下文对象封装这些参数，提升可读性与可测试性。

### 17. 中断 / 取消机制混用两套
- 位置：`stopTask` 用 `future.cancel(true)`，注释又说 Java8 `CompletableFuture.cancel` 不中断线程，实际依赖 `cancelledTasks` 标志轮询。
- 问题：两套机制并存，边界情况（如刚好卡在 JDBC 阻塞调用）无法及时停止。
- 建议：统一用 `cancelledTasks` 轮询 + 在可中断点检查，或明确一处为唯一停止信号。

### 18. `SyncLogServiceImpl` 死方法 / 语义混乱
- 位置：`toInt` / `toLong`（约 263–273 行）未使用；`parseStatusFilter` 把 `warning` 映射成 `RUNNING`，语义混乱。
- 建议：删除死方法，修正状态映射语义。

### 19. 前端使用不便
- 位置：`dbtrans-dashboard/js/app.js`（进度轮询）
- 问题：
  - 进度靠前端 `setInterval` 轮询 `/progress`，任务多时请求密集，建议 SSE/WebSocket。
  - `getProgress` 任务结束即从 `progressMap` 移除并返回 `NOT_FOUND`，前端只能靠"查不到"判断结束，语义不明确。
- 建议：任务结束保留一条最终进度记录（带状态）一段时间，或返回明确的 `FINISHED` 状态。

---

## 建议修复顺序

| 优先级 | 事项 |
|---|---|
| P0 | #1 密码明文、#3 marker 字符串比较、#6 持锁 IO、#7 日志内存分页、#8 日志无清理 |
| P1 | #4 marker 截断、#5 BLOB 丢数据、#9 元数据缓存、#10 解密缓存、#11 连接数匹配、#12 全表扫描 |
| P2 | #13 多实例、#14 Cron、#15 死代码、#16 大方法、#17 取消机制、#18 死方法、#19 前端体验 |

---

## 待确认 / 讨论点
- 是否真的有多实例部署需求？（决定 #13 是否要投入）
- 任务配置界面是否允许数字型 marker？（决定 #3 修复范围）
- 日志保留天数希望是多少天？（决定 #8 清理策略）
