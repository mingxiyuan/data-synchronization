package com.scxd.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scxd.config.BusinessException;
import com.scxd.config.Response;
import com.scxd.config.ResultCodeEnum;
import com.scxd.dialect.DatabaseDialect;
import com.scxd.dialect.DialectFactory;
import com.scxd.mapper.SyncDbConnMapper;
import com.scxd.mapper.SyncTaskLogMapper;
import com.scxd.mapper.SyncTaskMapper;
import com.scxd.model.dto.QueryOutRequestDto;
import com.scxd.model.dto.SyncMergeDto;
import com.scxd.model.dto.SyncProgress;
import com.scxd.model.entity.SyncDbConn;
import com.scxd.model.entity.SyncTask;
import com.scxd.model.entity.SyncTaskLog;
import com.scxd.model.enums.SyncMode;
import com.scxd.model.enums.SyncStatus;
import com.scxd.service.SyncExecService;
import com.scxd.service.PasswordEncryptService;
import com.scxd.utils.DataSourceUtils;
import com.scxd.utils.JDBCUtils;
import com.scxd.utils.SqlValidator;
import com.scxd.utils.WhereClause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class SyncExecServiceImpl implements SyncExecService {

    @Autowired
    private SyncTaskMapper taskMapper;

    @Autowired
    private SyncDbConnMapper connMapper;

    @Autowired
    private SyncTaskLogMapper logMapper;

    @Autowired
    private DataSourceUtils dataSourceUtils;

    @Autowired
    private JDBCUtils jdbcUtils;

    @Autowired
    private PasswordEncryptService passwordEncryptService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 运行中的任务: taskId -> Future, 用于取消正在执行的任务 */
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /** 任务启动时间: taskId -> 启动时间戳, 用于超时检测 */
    private final ConcurrentHashMap<String, Long> taskStartTimes = new ConcurrentHashMap<>();

    /** 已取消的任务ID集合, 分段工作线程轮询此标志以主动退避(Java 8 CompletableFuture.cancel不中断线程) */
    private final ConcurrentHashMap<String, Boolean> cancelledTasks = new ConcurrentHashMap<>();

    /** 任务进度缓存: taskId -> SyncProgress */
    private final ConcurrentHashMap<String, SyncProgress> progressMap = new ConcurrentHashMap<>();

    /** 单次任务最大执行时长(毫秒): 12小时 */
    private static final long MAX_EXECUTION_MS = 12 * 60 * 60 * 1000L;

    /** 入库子批次大小, 建议为出库批次的1/2~1/4以利用mergeExecutor并行写入 */
    @Value("${transdb.sync.sub-batch-size:500}")
    private int subBatchSize;

    /** 源库并行读取分段数, 大表可分2-4段并行出库 */
    @Value("${transdb.sync.source-parallelism:2}")
    private int sourceParallelism;

    @Autowired
    @Qualifier("syncExecutor")
    private ExecutorService syncExecutor;

    @Autowired
    @Qualifier("mergeExecutor")
    private ExecutorService mergeExecutor;

    /**
     * 启动时清理僵尸任务: JVM重启后, 所有running状态的任务实际上已中断
     */
    @PostConstruct
    public void recoverRunningTasks() {
        List<SyncTask> runningTasks = taskMapper.listByStatuses(Arrays.asList("running"));
        if (runningTasks.isEmpty()) return;
        log.info("检测到{}个僵尸任务(running状态), 正在重置为error...", runningTasks.size());
        Date now = new Date();
        for (SyncTask task : runningTasks) {
            taskMapper.updateRunStatus(task.getId(), "error", now);
            // 将对应的RUNNING日志也标记为失败
            SyncTaskLog lastLog = logMapper.getLatestByTaskId(task.getId());
            if (lastLog != null && lastLog.getStatus() != null && lastLog.getStatus() == 1) {
                lastLog.setStatus(SyncStatus.FAILED.getCode());  // 3=FAILED
                lastLog.setEndTime(now);
                lastLog.setErrorMsg("系统异常重启, 任务中断");
                logMapper.updateResult(lastLog);
            }
            log.info("僵尸任务[{}]已重置为error", task.getName());
        }
    }

    @Override
    public Response execTask(String taskId) {
        SyncTask task = taskMapper.getById(taskId);
        if (task == null) {
            return Response.configNotFound("任务不存在");
        }
        // task.status: 1=有效, 0=已删除(SyncStatus.RUNNING也是1但语义不同, 直接判1)
        if (task.getStatus() == null || task.getStatus() != 1) {
            return Response.error(ResultCodeEnum.TASK_EXEC_ERROR, "任务已禁用");
        }

        // 原子操作防止重复提交: compute确保get+put是原子操作
        boolean[] submitted = new boolean[1];
        try {
            runningTasks.compute(taskId, (k, existingFuture) -> {
                if (existingFuture != null && !existingFuture.isDone()) {
                    submitted[0] = false;
                    return existingFuture; // 保持现有任务
                }
                submitted[0] = true;
                cancelledTasks.remove(taskId); // 清除上次运行的取消信号
                Future<?> newFuture = syncExecutor.submit(() -> doSync(task));
                taskStartTimes.put(taskId, System.currentTimeMillis());
                return newFuture;
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("任务[{}]提交失败: 线程池排队已满", taskId);
            return Response.error(ResultCodeEnum.TASK_EXEC_ERROR, "当前同步任务较多, 请稍后重试");
        }

        if (!submitted[0]) {
            return Response.error(ResultCodeEnum.TASK_EXEC_ERROR, "任务正在运行中, 请等待当前周期完成");
        }
        return Response.success("任务已提交执行");
    }

    @Override
    public Response getProgress(String taskId) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            // 任务已结束/不存在, 返回空进度让前端停止轮询
            SyncProgress empty = new SyncProgress();
            empty.setTaskId(taskId);
            empty.setStatus("NOT_FOUND");
            return Response.success(empty);
        }
        Long startTime = taskStartTimes.get(taskId);
        if (startTime != null) {
            progress.setElapsedMs(System.currentTimeMillis() - startTime);
        }
        return Response.success(progress);
    }

    @Override
    public boolean stopTask(String taskId) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            cancelledTasks.put(taskId, Boolean.TRUE);
            future.cancel(true);
            runningTasks.remove(taskId);
            taskStartTimes.remove(taskId);
            log.info("任务[{}]已发送中断信号", taskId);
            return true;
        }
        taskStartTimes.remove(taskId);
        return false;
    }

    @Override
    public Response queryOut(QueryOutRequestDto request) {
        SyncDbConn conn = connMapper.getById(request.getConnId());
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());

        int pageNum = request.getPageNum() != null ? request.getPageNum() : 1;
        int pageSize = request.getPageSize() != null ? request.getPageSize() : 1000;
        long offset = (long) (pageNum - 1) * pageSize;

        try (Connection connection = getConn(conn)) {
            String effectiveSource = resolveSource(request.getTableName(), request.getSourceSql());
            WhereClause where = wrapRawWhere(request.getWhere());
            long total = jdbcUtils.queryCount(connection, dialect, effectiveSource, where);
            List<Map<String, Object>> data = jdbcUtils.queryOut(connection, dialect,
                    effectiveSource, request.getColumns(), where, offset, pageSize);

            Map<String, Object> result = new HashMap<>();
            result.put("tableName", request.getTableName());
            result.put("totalCount", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("data", data);
            return Response.success(result);
        } catch (Exception e) {
            log.error("出库查询失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response mergeInto(SyncMergeDto request) {
        SyncDbConn conn = connMapper.getById(request.getConnId());
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
        int syncMode = request.getSyncMode() != null ? request.getSyncMode() : SyncMode.MERGE.getCode();

        List<Map<String, Object>> data = request.getData();
        if (data == null || data.isEmpty()) {
            return Response.paramError("数据不能为空");
        }

        // 解析自定义主键
        List<String> customPks = parseCustomPk(request.getCustomPk());

        try (Connection connection = getConn(conn)) {
            // 获取主键
            List<String> primaryKeys;
            if (!customPks.isEmpty()) {
                primaryKeys = customPks;
            } else {
                primaryKeys = jdbcUtils.getPrimaryKeys(connection, request.getTableName(), dialect, resolveSchema(conn));
                if (primaryKeys.isEmpty()) {
                    return Response.paramError("目标表未设置主键且未自定义主键，无法同步");
                }
            }

            // CLEAN_INSERT模式先清空
            if (syncMode == SyncMode.CLEAN_INSERT.getCode()) {
                jdbcUtils.cleanTable(connection, dialect, request.getTableName());
            }

            // 分批入库
            int batchSize = 1000;
            int size = data.size();
            int batchCount = (size + batchSize - 1) / batchSize;
            int totalSuccess = 0;

            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < batchCount; i++) {
                int from = i * batchSize;
                int to = Math.min(from + batchSize, size);
                // 必须new ArrayList包装, subList是视图非线程安全
                List<Map<String, Object>> batch = new ArrayList<>(data.subList(from, to));
                int finalSyncMode = syncMode;
                List<String> finalPrimaryKeys = primaryKeys;
                String targetSchema = resolveSchema(conn);

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try (Connection batchConn = getConn(conn)) {
                        batchConn.setAutoCommit(false);
                        int count = jdbcUtils.mergeInto(batchConn, dialect, request.getTableName(),
                                finalPrimaryKeys, batch, finalSyncMode, targetSchema, false);
                        batchConn.commit();
                        return count;
                    } catch (Exception e) {
                        log.error("批量入库失败", e);
                        throw new RuntimeException(e);
                    }
                }, mergeExecutor));
            }

            for (CompletableFuture<Integer> future : futures) {
                try {
                    totalSuccess += future.get(calcTimeoutMs(batchSize), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.error("入库任务执行异常", e);
                    return Response.taskExecError("入库失败: " + e.getMessage());
                }
            }
            return Response.success(totalSuccess);
        } catch (Exception e) {
            log.error("入库失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response preview(String taskId) {
        SyncTask task = taskMapper.getById(taskId);
        if (task == null) {
            return Response.configNotFound("任务不存在");
        }

        SyncDbConn sourceConn = connMapper.getById(task.getSourceConnId());
        if (sourceConn == null) {
            return Response.configNotFound("源库连接不存在");
        }
        DatabaseDialect sourceDialect = DialectFactory.getDialect(sourceConn.getDbType());

        try (Connection sourceConnection = getConn(sourceConn)) {
            // 构建查询条件
            WhereClause where = buildWhere(task, sourceDialect);
            String effectiveSource = resolveSource(task.getSourceTable(), task.getSourceSql());
            long total = jdbcUtils.queryCount(sourceConnection, sourceDialect, effectiveSource, where);
            // 预览只取前10条
            List<Map<String, Object>> data = jdbcUtils.queryOut(sourceConnection, sourceDialect,
                    effectiveSource, null, where, 0, 10);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("taskName", task.getName());
            result.put("sourceTable", task.getSourceTable());
            result.put("targetTable", task.getTargetTable());
            result.put("totalCount", total);
            result.put("sampleData", data);
            result.put("where", where != null ? where.getSql() : null);
            return Response.success(result);
        } catch (Exception e) {
            log.error("预览失败", e);
            return Response.error(e.getMessage());
        }
    }

    // ======================== 核心同步逻辑 ========================

    private void doSync(SyncTask task) {
        // ========== 前置准备 ==========
        // Oracle/DM加引号后区分大小写, 表名/字段名统一转大写
        if (task.getTargetTable() != null) task.setTargetTable(task.getTargetTable().toUpperCase());
        if (task.getSourceTable() != null) task.setSourceTable(task.getSourceTable().toUpperCase());

        // INSERT_ONLY模式不支持断点续传, 强制从头开始
        boolean noUpdate = Boolean.TRUE.equals(task.getNoUpdate());

        // 断点续传: checkpoint非空=上次是暂停/崩溃, 可续传; 空=已停止, 从头开始
        String savedCheckpoint = task.getCheckpointData();
        boolean isResume = !noUpdate && savedCheckpoint != null && !savedCheckpoint.isEmpty();
        int resumeBaseRows = 0;
        if (isResume) {
            log.info("任务[{}]检测到断点数据, 从断点续传", task.getName());
            // 标记上次RUNNING日志为完成(被中断), 同时取出上次已同步行数用于累加显示
            SyncTaskLog lastRunningLog = logMapper.getLatestByTaskId(task.getId());
            if (lastRunningLog != null) {
                resumeBaseRows = lastRunningLog.getSuccessCount() != null ? lastRunningLog.getSuccessCount() : 0;
                lastRunningLog.setStatus(SyncStatus.SUCCESS.getCode());
                lastRunningLog.setEndTime(new Date());
                lastRunningLog.setErrorMsg("任务重新启动, 从断点续传");
                logMapper.updateResult(lastRunningLog);
            }
        }

        // 创建日志
        SyncTaskLog taskLog = new SyncTaskLog();
        taskLog.setId(UUID.randomUUID().toString().replace("-", ""));
        taskLog.setTaskId(task.getId());
        taskLog.setTaskName(task.getName());
        taskLog.setSyncMode(task.getSyncMode());
        taskLog.setStatus(SyncStatus.RUNNING.getCode());
        taskLog.setStartTime(new Date());
        taskLog.setTotalCount(0);
        taskLog.setSuccessCount(0);
        taskLog.setInsertCount(0);
        taskLog.setUpdateCount(0);
        taskLog.setSkipCount(0);
        logMapper.insert(taskLog);

        SyncProgress progress = new SyncProgress();
        progress.setTaskId(task.getId());
        progress.setTaskName(task.getName());
        progress.setStatus("RUNNING");
        progress.setSuccessRows(resumeBaseRows);  // 续传时显示累加值, 不归零
        progressMap.put(task.getId(), progress);

        final ScheduledExecutorService progressFlusher = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger totalSuccess = new AtomicInteger(resumeBaseRows);
        final AtomicInteger totalFail = new AtomicInteger(0);
        try {
            SyncDbConn sourceDbConn = connMapper.getById(task.getSourceConnId());
            SyncDbConn targetDbConn = connMapper.getById(task.getTargetConnId());
            if (sourceDbConn == null)
                throw new BusinessException(ResultCodeEnum.CONFIG_NOT_FOUND, "源库连接不存在");
            if (targetDbConn == null)
                throw new BusinessException(ResultCodeEnum.CONFIG_NOT_FOUND, "目标库连接不存在");

            DatabaseDialect sourceDialect = DialectFactory.getDialect(sourceDbConn.getDbType());
            DatabaseDialect targetDialect = DialectFactory.getDialect(targetDbConn.getDbType());
            WhereClause where = buildWhere(task, sourceDialect);
            String effectiveSource = resolveSource(task.getSourceTable(), task.getSourceSql());

            // 查询总数
            long totalCount;
            try (Connection sourceConn = getConn(sourceDbConn)) {
                totalCount = jdbcUtils.queryCount(sourceConn, sourceDialect, effectiveSource, where);
            }
            taskLog.setTotalCount((int) totalCount);
            logMapper.updateResult(taskLog);
            progress.setTotalRows(totalCount);
            if (totalCount == 0) {
                taskLog.setStatus(SyncStatus.SUCCESS.getCode());
                taskLog.setEndTime(new Date());
                logMapper.updateResult(taskLog);
                taskMapper.updateRunStatus(task.getId(), "stopped", new Date());
                return;
            }

            Map<String, String> fieldMapping = parseFieldMapping(task.getFieldMapping());
            List<String> customPks = parseCustomPk(task.getCustomPk());
            if (customPks.isEmpty() && task.getPrimaryKeys() != null && !task.getPrimaryKeys().trim().isEmpty())
                customPks = parseCustomPk(task.getPrimaryKeys());

            int finalSyncMode = task.getSyncMode() != null ? task.getSyncMode() : SyncMode.MERGE.getCode();
            boolean needClean = finalSyncMode == SyncMode.CLEAN_INSERT.getCode();

            List<String> primaryKeys;
            try (Connection targetMetaConn = getConn(targetDbConn)) {
                if (!customPks.isEmpty()) {
                    primaryKeys = customPks;
                } else {
                    primaryKeys = jdbcUtils.getPrimaryKeys(targetMetaConn, task.getTargetTable(),
                            targetDialect, resolveSchema(targetDbConn));
                    if (primaryKeys.isEmpty())
                        throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "目标表无主键，无法同步");
                }
            }
            List<String> finalPrimaryKeys = primaryKeys;
            String targetSchema = resolveSchema(targetDbConn);
            int batchSize = task.getBatchSize() != null ? task.getBatchSize() : 2000;

            // ========== 全量同步清表 ==========
            if (needClean && !isResume) {
                try (Connection cleanConn = getConn(targetDbConn)) {
                    jdbcUtils.cleanTable(cleanConn, targetDialect, task.getTargetTable());
                }
            }
            progress.setStatus(isResume ? "RESUMING" : (needClean ? "CLEANED" : "SYNCING"));

            // ========== 读取模式分支 ==========
            // Oracle/DM加引号后区分大小写, 统一转大写
            String rawMarker = task.getMarkerField();
            final String markerField = rawMarker != null ? rawMarker.toUpperCase() : null;

            // ========== 首次同步先补NULL-marker行(只做一次, 续传跳过) ==========
            // 只在真正首次同步时补NULL(没续传点 & marker从未写过), stopped重启跳过
            boolean isFirstSync = !isResume
                    && (task.getLastMarkerValue() == null || task.getLastMarkerValue().isEmpty());
            if (isFirstSync && markerField != null && !markerField.trim().isEmpty()) {
                try (Connection sourceConn = getConn(sourceDbConn)) {
                    long nullCount = jdbcUtils.queryCount(sourceConn, sourceDialect,
                            effectiveSource, WhereClause.isNull(markerField));
                    if (nullCount > 0) {
                        log.info("任务[{}]首次同步, 先处理{}条NULL-marker行", task.getName(), nullCount);
                        syncNullRows(sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                                effectiveSource, fieldMapping, task, markerField,
                                finalPrimaryKeys, batchSize, finalSyncMode, targetSchema, noUpdate,
                                totalSuccess, totalFail, progress);
                    }
                } catch (Exception e) {
                    log.warn("NULL-marker行同步失败: {}", e.getMessage());
                }
            }

            String fetchMode = task.getFetchMode() != null ? task.getFetchMode() : "parallel";
            if ("streaming".equals(fetchMode) && markerField != null && !markerField.trim().isEmpty()) {
                doStreamingSync(sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                        effectiveSource, fieldMapping, where, task, markerField, isResume,
                        batchSize, finalSyncMode, finalPrimaryKeys, targetSchema, noUpdate,
                        progress, taskLog, totalSuccess, totalFail, totalCount);
                return;
            }

            // ========== Batch竞争模型 (parallel) ==========
            runParallelSync(sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                    effectiveSource, fieldMapping, where, task, markerField,
                    isResume, noUpdate, batchSize, finalSyncMode, finalPrimaryKeys,
                    targetSchema, totalSuccess, totalFail, totalCount,
                    progress, taskLog, progressFlusher);

            // 全部完成
            progress.setStatus("COMPLETED");
            progress.setSuccessRows(totalSuccess.get());
            progress.setFailRows(totalFail.get());
            finishTask(task, taskLog, totalSuccess.get());
            if (markerField != null && !markerField.trim().isEmpty()) {
                try (Connection sourceConn = getConn(sourceDbConn)) {
                    updateMarkerIfNeeded(task, sourceConn, sourceDialect, effectiveSource);
                }
            }
            log.info("任务[{}]同步完成: 总数={}, 成功={}", task.getName(), totalCount, totalSuccess.get());

        } catch (InterruptedException e) {
            // 中断已在上面处理, 不重复
        } catch (Exception e) {
            boolean isUserCancel = cancelledTasks.containsKey(task.getId());
            if (isUserCancel) {
                SyncTask latestTask = taskMapper.getById(task.getId());
                boolean isPaused = latestTask != null && "paused".equals(latestTask.getRunStatus());
                int synced = totalSuccess.get();
                if (isPaused) {
                    log.info("任务[{}]被暂停, 已同步{}行, 下次续传", task.getName(), synced);
                } else {
                    log.info("任务[{}]被停止, 已同步{}行, 重新开始", task.getName(), synced);
                    taskLog.setStatus(SyncStatus.SUCCESS.getCode());
                    taskLog.setEndTime(new Date());
                }
                taskLog.setSuccessCount(synced);
                logMapper.updateResult(taskLog);
            } else {
                log.error("任务[{}]同步异常", task.getName(), e);
                progress.setStatus("FAILED");
                progress.setSuccessRows(totalSuccess.get());
                progress.setFailRows(totalFail.get());
                taskLog.setStatus(SyncStatus.FAILED.getCode());
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.length() > 2000)
                    errMsg = errMsg.substring(0, 2000) + "...";
                taskLog.setErrorMsg(errMsg);
                taskLog.setEndTime(new Date());
                logMapper.updateResult(taskLog);
                taskMapper.updateRunStatus(task.getId(), "error", new Date());
            }
        } finally {
            progressFlusher.shutdown();
            runningTasks.remove(task.getId());
            taskStartTimes.remove(task.getId());
            progressMap.remove(task.getId());
        }
    }

    // ======================== 并行Batch竞争模型 ========================

    /** 并行分页同步: 多线程竞争batch, 有索引时性能最佳 */
    private void runParallelSync(SyncDbConn sourceDbConn, SyncDbConn targetDbConn,
                                  DatabaseDialect sourceDialect, DatabaseDialect targetDialect,
                                  String effectiveSource, Map<String, String> fieldMapping,
                                  WhereClause where, SyncTask task, String markerField,
                                  boolean isResume, boolean noUpdate, int batchSize,
                                  int finalSyncMode, List<String> finalPrimaryKeys,
                                  String targetSchema, AtomicInteger totalSuccess,
                                  AtomicInteger totalFail, long totalCount,
                                  SyncProgress progress, SyncTaskLog taskLog,
                                  ScheduledExecutorService progressFlusher) throws InterruptedException {
        // 确定起始marker
        String startMarker;
        if (isResume && markerField != null && !markerField.trim().isEmpty()) {
            try (Connection targetConn = getConn(targetDbConn)) {
                startMarker = jdbcUtils.queryMaxMarker(targetConn, targetDialect,
                        task.getTargetTable(), markerField, null);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (startMarker == null) startMarker = "";
            log.info("任务[{}]续传起点: {}", task.getName(), startMarker);
        } else {
            try (Connection sourceConn = getConn(sourceDbConn)) {
                startMarker = jdbcUtils.queryMinMarker(sourceConn, sourceDialect,
                        effectiveSource, markerField, wrapRawWhere(task.getSourceWhere()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (startMarker == null) startMarker = "";
        }

        // 快照终点
        String endMarker = null;
        if (markerField != null && !markerField.trim().isEmpty()) {
            try (Connection sourceConn = getConn(sourceDbConn)) {
                endMarker = jdbcUtils.queryMaxMarker(sourceConn, sourceDialect,
                        effectiveSource, markerField, wrapRawWhere(task.getSourceWhere()));
                log.info("任务[{}]同步窗口: start={}, end={}", task.getName(), startMarker, endMarker);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        final String snapshotEndMarker = endMarker;

        // 全NULL降级
        if (markerField != null && !markerField.trim().isEmpty()
                && snapshotEndMarker == null && totalCount > 0) {
            log.info("任务[{}]源表{}全为NULL, 降级PK分页同步", task.getName(), markerField);
            syncNullRows(sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                    effectiveSource, fieldMapping, task, markerField,
                    finalPrimaryKeys, batchSize, finalSyncMode, targetSchema, noUpdate,
                    totalSuccess, totalFail, progress);
            progress.setStatus("COMPLETED");
            progress.setSuccessRows(totalSuccess.get());
            progress.setFailRows(totalFail.get());
            finishTask(task, taskLog, totalSuccess.get());
            return;
        }

        // 共享状态
        final ReentrantLock batchLock = new ReentrantLock();
        final String[] completedMarker = {startMarker};
        final String[] maxCompletedEnd = {startMarker};
        final List<BatchRange> incompleteRanges = Collections.synchronizedList(new ArrayList<>());

        // 续传加载间隙
        if (isResume && markerField != null && !markerField.trim().isEmpty()) {
            String savedJson = task.getCheckpointData();
            if (savedJson != null && !savedJson.isEmpty()) {
                Object[] parsed = parseCheckpointJson(savedJson);
                if (parsed != null) {
                    String jsonCompleted = (String) parsed[0];
                    if (jsonCompleted != null && !jsonCompleted.isEmpty()
                            && jsonCompleted.compareTo(startMarker) > 0) {
                        completedMarker[0] = jsonCompleted;
                    }
                    @SuppressWarnings("unchecked")
                    List<BatchRange> savedGaps = (List<BatchRange>) parsed[1];
                    if (!savedGaps.isEmpty()) {
                        incompleteRanges.addAll(savedGaps);
                        log.info("任务[{}]加载{}个未完成间隙, 起点={}", task.getName(), savedGaps.size(), completedMarker[0]);
                    }
                }
            }
        }
        maxCompletedEnd[0] = completedMarker[0];
        final boolean[] hasMore = {true};

        int parallelism = sourceParallelism;
        List<CompletableFuture<Void>> threadFutures = new ArrayList<>();

        for (int t = 0; t < parallelism; t++) {
            threadFutures.add(CompletableFuture.runAsync(() -> batchWorkerLoop(
                    sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                    effectiveSource, fieldMapping, where, task, markerField,
                    snapshotEndMarker, noUpdate, batchSize, finalSyncMode,
                    finalPrimaryKeys, targetSchema, batchLock, completedMarker,
                    maxCompletedEnd, incompleteRanges, hasMore,
                    totalSuccess, totalFail, progress), syncExecutor));
        }

        // 进度刷入定时任务
        progressFlusher.scheduleAtFixedRate(() -> {
            try {
                if (cancelledTasks.containsKey(task.getId())) return;
                progress.setSuccessRows(totalSuccess.get());
                progress.setFailRows(totalFail.get());
                taskLog.setSuccessCount(totalSuccess.get());
                logMapper.updateResult(taskLog);
                saveCheckpoint(task, completedMarker[0], incompleteRanges);
            } catch (Exception e) {
                log.warn("进度刷入失败: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);

        // 等待线程完成
        for (CompletableFuture<Void> f : threadFutures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                log.info("任务[{}]收到中断, 保存断点...", task.getName());
                saveCheckpoint(task, completedMarker[0], incompleteRanges);
                taskLog.setSuccessCount(totalSuccess.get());
                progressFlusher.shutdown();
                threadFutures.forEach(tf -> tf.cancel(true));
                throw e;
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 单个batch工作线程: 循环抢gap→写→标记完成 */
    private void batchWorkerLoop(SyncDbConn sourceDbConn, SyncDbConn targetDbConn,
                                  DatabaseDialect sourceDialect, DatabaseDialect targetDialect,
                                  String effectiveSource, Map<String, String> fieldMapping,
                                  WhereClause where, SyncTask task, String markerField,
                                  String snapshotEndMarker, boolean noUpdate, int batchSize,
                                  int syncMode, List<String> primaryKeys, String targetSchema,
                                  ReentrantLock batchLock, String[] completedMarker,
                                  String[] maxCompletedEnd, List<BatchRange> incompleteRanges,
                                  boolean[] hasMore, AtomicInteger totalSuccess,
                                  AtomicInteger totalFail, SyncProgress progress) {
        while (true) {
            if (cancelledTasks.containsKey(task.getId())) {
                saveCheckpoint(task, completedMarker[0], incompleteRanges);
                return;
            }
            BatchRange myRange = claimBatch(task, sourceDbConn, sourceDialect, effectiveSource,
                    where, markerField, snapshotEndMarker, batchSize, batchLock,
                    completedMarker, maxCompletedEnd, incompleteRanges, hasMore);
            if (myRange == null) break;

            boolean writeOk = writeBatchRange(targetDbConn, targetDialect, effectiveSource, sourceDbConn,
                    sourceDialect, fieldMapping, where, task, markerField,
                    myRange, batchSize, syncMode, primaryKeys, targetSchema, noUpdate,
                    totalSuccess, totalFail, progress);
            if (!writeOk) continue;  // 失败跳过, 不推进水位线

            markBatchDone(myRange, batchLock, completedMarker, maxCompletedEnd, incompleteRanges);
        }
    }

    /** 抢一个batch: gap优先, 其次新batch */
    private BatchRange claimBatch(SyncTask task, SyncDbConn sourceDbConn,
                                   DatabaseDialect sourceDialect, String effectiveSource,
                                   WhereClause where, String markerField, String snapshotEndMarker,
                                   int batchSize, ReentrantLock batchLock, String[] completedMarker,
                                   String[] maxCompletedEnd, List<BatchRange> incompleteRanges,
                                   boolean[] hasMore) {
        BatchRange myRange = null;
        batchLock.lock();
        try {
            if (cancelledTasks.containsKey(task.getId())) return null;
            for (BatchRange r : incompleteRanges) {
                if (!r.claimed) { r.claimed = true; myRange = r; break; }
            }
            if (myRange == null && hasMore[0]) {
                try (Connection sourceConn = getConn(sourceDbConn)) {
                    String claimStart = maxCompletedEnd[0];
                    log.info("CLAIM [{}] start={} end={} gaps={}",
                            Thread.currentThread().getName(),
                            claimStart, snapshotEndMarker, incompleteRanges.size());
                    WhereClause markerRange = buildMarkerRange(markerField,
                            claimStart, snapshotEndMarker, sourceDialect);
                    WhereClause batchWhere = WhereClause.combine(where, markerRange);
                    String orderBy = markerField != null ? sourceDialect.quote(markerField) + " ASC" : null;
                    List<Map<String, Object>> rows = jdbcUtils.queryOut(
                            sourceConn, sourceDialect, effectiveSource, null,
                            batchWhere, orderBy, 0, batchSize);
                    if (rows.isEmpty()) {
                        hasMore[0] = false;
                    } else {
                        String batchEnd = getMaxMarker(rows, markerField);
                        myRange = new BatchRange(claimStart, batchEnd, rows);
                        if (batchEnd != null && batchEnd.compareTo(maxCompletedEnd[0]) > 0) {
                            maxCompletedEnd[0] = batchEnd;
                        }
                        incompleteRanges.add(myRange);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            batchLock.unlock();
        }
        return myRange;
    }

    /** 写一个batch, 返回true表示成功 */
    private boolean writeBatchRange(SyncDbConn targetDbConn, DatabaseDialect targetDialect,
                                  String effectiveSource, SyncDbConn sourceDbConn,
                                  DatabaseDialect sourceDialect, Map<String, String> fieldMapping,
                                  WhereClause where, SyncTask task, String markerField,
                                  BatchRange myRange, int batchSize, int syncMode,
                                  List<String> primaryKeys, String targetSchema, boolean noUpdate,
                                  AtomicInteger totalSuccess, AtomicInteger totalFail,
                                  SyncProgress progress) {
        try {
            int written;
            if (myRange.rows != null) {
                written = writeRows(targetDbConn, targetDialect, task.getTargetTable(),
                        primaryKeys, myRange.rows, fieldMapping, syncMode, targetSchema, noUpdate);
            } else {
                written = writeBatch(sourceDbConn, sourceDialect, targetDbConn, targetDialect,
                        effectiveSource, fieldMapping, where, task, markerField,
                        myRange.start, myRange.end, batchSize, syncMode,
                        primaryKeys, targetSchema, noUpdate);
            }
            totalSuccess.addAndGet(written);
            progress.setSuccessRows(totalSuccess.get());
            return true;
        } catch (Exception e) {
            log.error("批次写入失败 [{} ~ {}]: {}", myRange.start, myRange.end, e.getMessage());
            totalFail.incrementAndGet();
            progress.setFailRows(totalFail.get());
            return false;
        }
    }

    /** 标记batch完成, 移除并推进水位线 */
    private void markBatchDone(BatchRange myRange, ReentrantLock batchLock,
                                String[] completedMarker, String[] maxCompletedEnd,
                                List<BatchRange> incompleteRanges) {
        batchLock.lock();
        try {
            boolean removed = incompleteRanges.remove(myRange);
            if (removed) {
                if (myRange.end != null && myRange.end.compareTo(maxCompletedEnd[0]) > 0) {
                    maxCompletedEnd[0] = myRange.end;
                }
                if (incompleteRanges.isEmpty()) {
                    completedMarker[0] = maxCompletedEnd[0];
                } else {
                    String min = null;
                    for (BatchRange r : incompleteRanges) {
                        if (min == null || r.start.compareTo(min) < 0) min = r.start;
                    }
                    if (min != null) completedMarker[0] = min;
                }
            }
        } finally {
            batchLock.unlock();
        }
    }

    // ======================== 流式同步 ========================

    /** 流式单次扫描同步: 一次全表排序, 游标流式读取, 无索引时比并行分页快N倍 */
    private void doStreamingSync(SyncDbConn sourceDbConn, SyncDbConn targetDbConn,
                                  DatabaseDialect sourceDialect, DatabaseDialect targetDialect,
                                  String effectiveSource, Map<String, String> fieldMapping,
                                  WhereClause where, SyncTask task, String markerField,
                                  boolean isResume, int batchSize, int syncMode,
                                  List<String> primaryKeys, String targetSchema, boolean noUpdate,
                                  SyncProgress progress, SyncTaskLog taskLog,
                                  AtomicInteger totalSuccess, AtomicInteger totalFail, long totalCount) {
        // 确定起始marker
        String startMarker = "";
        if (isResume) {
            try (Connection targetConn = getConn(targetDbConn)) {
                startMarker = jdbcUtils.queryMaxMarker(targetConn, targetDialect,
                        task.getTargetTable(), markerField, null);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (startMarker == null) startMarker = "";
            log.info("任务[{}]流式续传起点: {}", task.getName(), startMarker);
        } else {
            try (Connection sourceConn = getConn(sourceDbConn)) {
                startMarker = jdbcUtils.queryMinMarker(sourceConn, sourceDialect,
                        effectiveSource, markerField, wrapRawWhere(task.getSourceWhere()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (startMarker == null) startMarker = "";
        }

        // 快照终点
        String endMarker = null;
        try (Connection sourceConn = getConn(sourceDbConn)) {
            endMarker = jdbcUtils.queryMaxMarker(sourceConn, sourceDialect,
                    effectiveSource, markerField, wrapRawWhere(task.getSourceWhere()));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("任务[{}]流式同步窗口: start={}, end={}", task.getName(), startMarker, endMarker);

        // ===== 先同步NULL-marker行 =====
        try (Connection sourceConn = getConn(sourceDbConn)) {
            WhereClause nullWhere = WhereClause.isNull(markerField);
            long nullCount = jdbcUtils.queryCount(sourceConn, sourceDialect, effectiveSource, nullWhere);
            if (nullCount > 0) {
                log.info("任务[{}]流式先同步{}条NULL-marker行", task.getName(), nullCount);
                syncNullRows(sourceDbConn, targetDbConn, sourceDialect, targetDialect,
                        effectiveSource, fieldMapping, task, markerField,
                        primaryKeys, batchSize, syncMode, targetSchema, noUpdate,
                        totalSuccess, totalFail, progress);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 全NULL降级: 没有非NULL行, 跳过主游标
        if (endMarker == null) {
            progress.setStatus("COMPLETED");
            progress.setSuccessRows(totalSuccess.get());
            progress.setFailRows(totalFail.get());
            finishTask(task, taskLog, totalSuccess.get());
            return;
        }

        // ===== 主游标: 非NULL行 =====
        final String[] lastCheckpointed = {startMarker};
        final long[] lastFlushMs = {System.currentTimeMillis()};

        // 拼流式SQL
        String selectCols = "*";
        String baseSql = "SELECT " + selectCols + " FROM (" + effectiveSource + ") t";
        WhereClause markerRange = buildMarkerRange(markerField, startMarker, endMarker, sourceDialect);
        WhereClause combined = WhereClause.combine(where, markerRange);
        List<Object> whereParams = new ArrayList<>();
        if (combined != null && combined.hasSql()) {
            baseSql += " WHERE " + combined.getSql();
            whereParams.addAll(combined.getParams());
        }
        String orderBy = sourceDialect.quote(markerField) + " ASC";
        baseSql += " ORDER BY " + orderBy;
        log.info("流式同步SQL: {}", baseSql);

        try (Connection sourceConn = getConn(sourceDbConn)) {
            sourceConn.setAutoCommit(false);
            try (PreparedStatement ps = sourceConn.prepareStatement(baseSql)) {
                ps.setFetchSize(batchSize);
                ps.setFetchDirection(java.sql.ResultSet.FETCH_FORWARD);
                int idx = 1;
                for (Object p : whereParams) {
                    ps.setObject(idx++, p);
                }

                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    // 获取列元数据
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> colNames = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        colNames.add(meta.getColumnLabel(i).toUpperCase());
                    }

                    List<Map<String, Object>> buffer = new ArrayList<>(batchSize);
                    String currentMarker = startMarker;
                    progress.setStatus("STREAMING");

                    while (rs.next()) {
                        if (cancelledTasks.containsKey(task.getId())) {
                            // 暂停/停止: 保存checkpoint
                            saveStreamingCheckpoint(task, currentMarker);
                            progress.setStatus("PAUSED");
                            return;
                        }

                        // 读一行
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(colNames.get(i - 1), rs.getObject(i));
                        }
                        // 字段映射
                        Map<String, Object> mapped = mapRow(row, fieldMapping);
                        buffer.add(mapped);
                        // 更新当前marker
                        Object mv = row.get(markerField.toUpperCase());
                        if (mv != null) {
                            String mvStr = mv.toString();
                            if (mvStr.length() > 19) mvStr = mvStr.substring(0, 19);
                            currentMarker = mvStr;
                        }

                        // 到达快照终点, 停止
                        if (endMarker != null && currentMarker != null
                                && !currentMarker.isEmpty() && currentMarker.compareTo(endMarker) >= 0) {
                            break;
                        }

                        if (buffer.size() >= batchSize) {
                            flushStreamingBatch(targetDbConn, targetDialect, task, primaryKeys,
                                    buffer, syncMode, targetSchema, noUpdate, totalSuccess, totalFail, progress);
                            buffer.clear();
                            // 10秒刷checkpoint
                            if (System.currentTimeMillis() - lastFlushMs[0] > 10000) {
                                saveStreamingCheckpoint(task, currentMarker);
                                lastCheckpointed[0] = currentMarker;
                                taskLog.setSuccessCount(totalSuccess.get());
                                logMapper.updateResult(taskLog);
                                lastFlushMs[0] = System.currentTimeMillis();
                            }
                        }
                    }

                    // 剩余行
                    if (!buffer.isEmpty()) {
                        flushStreamingBatch(targetDbConn, targetDialect, task, primaryKeys,
                                buffer, syncMode, targetSchema, noUpdate, totalSuccess, totalFail, progress);
                    }

                    // 完成, 清除checkpoint
                    taskMapper.clearCheckpoint(task.getId());
                    log.info("任务[{}]流式同步完成: 成功={}, 失败={}", task.getName(), totalSuccess.get(), totalFail.get());
                }
            }
        } catch (Exception e) {
            // 异常时保存checkpoint
            saveStreamingCheckpoint(task, lastCheckpointed[0]);
            log.error("任务[{}]流式同步异常 @marker={}: {}", task.getName(), lastCheckpointed[0], e.getMessage());
            progress.setStatus("FAILED");
            progress.setSuccessRows(totalSuccess.get());
            progress.setFailRows(totalFail.get());
            taskLog.setStatus(SyncStatus.FAILED.getCode());
            taskLog.setErrorMsg(e.getMessage());
            taskLog.setSuccessCount(totalSuccess.get());
            logMapper.updateResult(taskLog);
            return;
        }

        progress.setStatus("COMPLETED");
        progress.setSuccessRows(totalSuccess.get());
        progress.setFailRows(totalFail.get());
        finishTask(task, taskLog, totalSuccess.get());
    }

    /** 流式批量写入目标表 */
    private void flushStreamingBatch(SyncDbConn targetDbConn, DatabaseDialect targetDialect,
                                      SyncTask task, List<String> primaryKeys,
                                      List<Map<String, Object>> batch, int syncMode,
                                      String targetSchema, boolean noUpdate,
                                      AtomicInteger totalSuccess, AtomicInteger totalFail,
                                      SyncProgress progress) {
        try (Connection targetConn = getConn(targetDbConn)) {
            targetConn.setAutoCommit(false);
            int written = jdbcUtils.mergeInto(targetConn, targetDialect, task.getTargetTable(),
                    primaryKeys, batch, syncMode, targetSchema, noUpdate);
            targetConn.commit();
            totalSuccess.addAndGet(written);
            progress.setSuccessRows(totalSuccess.get());
        } catch (Exception e) {
            log.error("流式写入失败: {}", e.getMessage());
            totalFail.incrementAndGet();
            progress.setFailRows(totalFail.get());
        }
    }

    /** 保存并行模式checkpoint */
    private void saveCheckpoint(SyncTask task, String completedMarker, List<BatchRange> incompleteRanges) {
        try {
            String json = buildCheckpointJson(completedMarker, incompleteRanges);
            taskMapper.updateCheckpoint(task.getId(), json);
        } catch (Exception e) {
            log.warn("保存checkpoint失败: {}", e.getMessage());
        }
    }

    /** 流式checkpoint: 只存完成的marker值, 无间隙 */
    private void saveStreamingCheckpoint(SyncTask task, String marker) {
        try {
            String json = buildCheckpointJson(marker != null ? marker : "", Collections.emptyList());
            taskMapper.updateCheckpoint(task.getId(), json);
        } catch (Exception e) {
            log.warn("流式checkpoint保存失败: {}", e.getMessage());
        }
    }

    // ======================== 辅助方法 ========================

    /**
    /** 根据行数动态计算批次超时: 最低1分钟, 按10ms/行估算, 最高30分钟 */
    private long calcTimeoutMs(int recordCount) {
        long estimated = recordCount * 10L;
        return Math.max(60_000L, Math.min(estimated, 30 * 60_000L));
    }

    /**
     * 正常/中断完成: 写日志并更新任务状态为stopped
     */
    private void finishTask(SyncTask task, SyncTaskLog taskLog, int totalSuccess) {
        taskLog.setStatus(SyncStatus.SUCCESS.getCode());
        taskLog.setSuccessCount(totalSuccess);
        taskLog.setEndTime(new Date());
        logMapper.updateResult(taskLog);
        taskMapper.updateRunStatus(task.getId(), "stopped", new Date());
    }

    /**
     * 增量同步: 更新marker字段(如果配置了)
     */
    private void updateMarkerIfNeeded(SyncTask task, Connection sourceConn,
                                       DatabaseDialect sourceDialect, String effectiveSource) {
        if (task.getMarkerField() != null && !task.getMarkerField().trim().isEmpty()) {
            try {
                String maxMarker = jdbcUtils.queryMaxMarker(sourceConn, sourceDialect,
                        effectiveSource, task.getMarkerField(), wrapRawWhere(task.getSourceWhere()));
                if (maxMarker != null) {
                    taskMapper.updateMarker(task.getId(), maxMarker, new Date());
                }
            } catch (Exception e) {
                log.warn("更新marker失败, 下次执行将重新同步: {}", e.getMessage());
            }
        }
    }

    // ======================== Batch竞争模型辅助方法 ========================

    /** 从数据行中提取最大marker值 */
    private String getMaxMarker(List<Map<String, Object>> rows, String markerField) {
        String max = null;
        for (Map<String, Object> row : rows) {
            Object val = row.get(markerField.toUpperCase());
            if (val != null) {
                String s = val.toString();
                if (max == null || s.compareTo(max) > 0) max = s;
            }
        }
        return max;
    }

    /** 构建 marker > value 的WHERE条件 (writeBatch用) */
    private WhereClause buildMarkerWhere(String markerField, String markerValue, DatabaseDialect dialect) {
        if (markerField == null || markerField.trim().isEmpty() || markerValue == null || markerValue.isEmpty())
            return null;
        return WhereClause.gt(markerField, markerValue, dialect);
    }

    /** 构建 claim 阶段的 marker 区间条件 (含快照终点 + NULL 处理) */
    private WhereClause buildMarkerRange(String markerField, String currentMarker, String endMarker, DatabaseDialect dialect) {
        if (markerField == null || markerField.trim().isEmpty()) return null;

        boolean hasStart = currentMarker != null && !currentMarker.isEmpty();
        boolean hasEnd = endMarker != null && !endMarker.isEmpty();

        if (!hasStart && !hasEnd) {
            // 全NULL: 只取NULL行
            return WhereClause.isNull(markerField);
        }
        if (!hasStart) {
            // 第一轮: 含NULL + 所有到end的非NULL
            return WhereClause.or(WhereClause.isNull(markerField), WhereClause.le(markerField, endMarker, dialect));
        }
        if (!hasEnd) {
            return WhereClause.gt(markerField, currentMarker, dialect);
        }
        // 正常区间: marker > start AND marker <= end
        WhereClause gt = WhereClause.gt(markerField, currentMarker, dialect);
        WhereClause le = WhereClause.le(markerField, endMarker, dialect);
        return WhereClause.combine(gt, le);
    }

    /** 补充同步 NULL-marker 行 (用主键分页) */
    private void syncNullRows(SyncDbConn sourceDbConn, SyncDbConn targetDbConn,
                               DatabaseDialect sourceDialect, DatabaseDialect targetDialect,
                               String effectiveSource, Map<String, String> fieldMapping,
                               SyncTask task, String markerField, List<String> primaryKeys,
                               int batchSize, int syncMode, String targetSchema, boolean noUpdate,
                               AtomicInteger totalSuccess, AtomicInteger totalFail, SyncProgress progress) {
        String pkField = primaryKeys.get(0);
        String lastPk = "";
        WhereClause nullCond = WhereClause.isNull(markerField);

        try (Connection sourceConn = getConn(sourceDbConn)) {
            while (true) {
                // 停止/暂停检查
                if (cancelledTasks.containsKey(task.getId()) || Thread.currentThread().isInterrupted()) {
                    log.info("NULL行同步收到中断信号, 停止");
                    return;
                }
                WhereClause pkWhere = lastPk.isEmpty() ? null : WhereClause.gtGeneric(pkField, lastPk);
                WhereClause combined = WhereClause.combine(nullCond, pkWhere);
                String orderBy = sourceDialect.quote(pkField) + " ASC";
                List<Map<String, Object>> rows = jdbcUtils.queryOut(
                        sourceConn, sourceDialect, effectiveSource, null,
                        combined, orderBy, 0, batchSize);
                if (rows.isEmpty()) break;

                // 字段映射
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    mapped.add(mapRow(row, fieldMapping));
                }
                // 写入
                try (Connection targetConn = getConn(targetDbConn)) {
                    targetConn.setAutoCommit(false);
                    int written = jdbcUtils.mergeInto(targetConn, targetDialect, task.getTargetTable(),
                            primaryKeys, mapped, syncMode, targetSchema, noUpdate);
                    targetConn.commit();
                    totalSuccess.addAndGet(written);
                    progress.setSuccessRows(totalSuccess.get());
                } catch (Exception e) {
                    log.error("NULL行写入失败: {}", e.getMessage());
                    totalFail.incrementAndGet();
                    progress.setFailRows(totalFail.get());
                }
                // 更新游标
                Map<String, Object> lastRow = rows.get(rows.size() - 1);
                Object pkVal = lastRow.get(pkField.toUpperCase());
                if (pkVal != null) lastPk = pkVal.toString();
            }
        } catch (Exception e) {
            log.error("NULL行同步异常: {}", e.getMessage());
        }
    }

    /** 批次范围 — 带认领标记防止崩溃丢失间隙。rows非空=新批次预取数据, 空=续传间隙需回源查 */
    private static class BatchRange {
        final String start, end;
        volatile boolean claimed;
        volatile List<Map<String, Object>> rows;
        /** 续传间隙 */
        BatchRange(String start, String end) { this.start = start; this.end = end; this.claimed = false; }
        /** 新批次(带预取数据, 省掉writeBatch重查源表) */
        BatchRange(String start, String end, List<Map<String, Object>> rows) {
            this.start = start; this.end = end; this.claimed = true; this.rows = rows;
        }
    }

    /** 序列化断点到JSON */
    private String buildCheckpointJson(String completedMarker, List<BatchRange> incompleteRanges) {
        try {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("c", completedMarker == null ? "" : completedMarker);
            List<String[]> gaps = new ArrayList<>();
            for (BatchRange r : incompleteRanges) {
                gaps.add(new String[]{r.start, r.end});
            }
            json.put("g", gaps);
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            log.error("序列化断点JSON失败", e);
            return "{\"c\":\"\",\"g\":[]}";
        }
    }

    /** 从JSON反序列化断点, 所有间隙重置为未认领 */
    private Object[] parseCheckpointJson(String json) {
        if (json == null || json.isEmpty() || !json.startsWith("{")) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            String completed = root.has("c") ? root.get("c").asText() : "";
            List<BatchRange> gaps = new ArrayList<>();
            if (root.has("g")) {
                for (JsonNode g : root.get("g")) {
                    BatchRange r = new BatchRange(g.get(0).asText(), g.get(1).asText());
                    r.claimed = false;  // 重启后重置为未认领
                    gaps.add(r);
                }
            }
            return new Object[]{completed, gaps};
        } catch (Exception e) {
            log.warn("解析断点JSON失败, 将从头开始: {}", e.getMessage());
            return null;
        }
    }

    /** 直接用预取数据写入目标表(省一次源表查询) */
    private int writeRows(SyncDbConn targetDbConn, DatabaseDialect targetDialect,
                           String targetTable, List<String> primaryKeys,
                           List<Map<String, Object>> rows, Map<String, String> fieldMapping,
                           int syncMode, String targetSchema, boolean noUpdate) {
        if (rows.isEmpty()) return 0;
        List<Map<String, Object>> mapped = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            mapped.add(mapRow(row, fieldMapping));
        }
        try (Connection targetConn = getConn(targetDbConn)) {
            targetConn.setAutoCommit(false);
            int count = jdbcUtils.mergeInto(targetConn, targetDialect, targetTable,
                    primaryKeys, mapped, syncMode, targetSchema, noUpdate);
            targetConn.commit();
            return count;
        } catch (Exception e) {
            throw new RuntimeException("写入目标表失败: " + e.getMessage(), e);
        }
    }

    /** 读取一批源数据, 映射字段, 写入目标表, 返回写入行数 */
    private int writeBatch(SyncDbConn sourceDbConn, DatabaseDialect sourceDialect,
                           SyncDbConn targetDbConn, DatabaseDialect targetDialect,
                           String effectiveSource, Map<String, String> fieldMapping,
                           WhereClause baseWhere, SyncTask task, String markerField,
                           String rangeStart, String rangeEnd, int batchSize,
                           int syncMode, List<String> primaryKeys, String targetSchema,
                           boolean noUpdate) throws Exception {
        try (Connection sourceConn = getConn(sourceDbConn)) {
            WhereClause rangeWhere = buildMarkerWhere(markerField, rangeStart, sourceDialect);
            WhereClause combined = WhereClause.combine(baseWhere, rangeWhere);
            String orderBy = markerField != null ? sourceDialect.quote(markerField) + " ASC" : null;
            List<Map<String, Object>> rows = jdbcUtils.queryOut(
                    sourceConn, sourceDialect, effectiveSource, null, combined, orderBy, 0, batchSize);
            if (rows.isEmpty()) return 0;
            // 字段映射
            List<Map<String, Object>> mapped = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                mapped.add(mapRow(row, fieldMapping));
            }
            // 写入目标
            try (Connection targetConn = getConn(targetDbConn)) {
                int count = jdbcUtils.mergeInto(targetConn, targetDialect, task.getTargetTable(),
                        primaryKeys, mapped, syncMode, targetSchema, noUpdate);
                targetConn.commit();
                return count;
            }
        }
    }

    /** 单行字段映射 */
    private Map<String, Object> mapRow(Map<String, Object> row, Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) return row;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            Object val = row.get(e.getKey());
            if (val != null || row.containsKey(e.getKey())) {
                result.put(e.getValue(), val);
            }
        }
        return result.isEmpty() ? row : result;
    }

    // ======================== 死代码(旧分段模型, 已被batch竞争取代) ========================
    /**
     * 处理单个分段: 出库→映射→入库, 支持pipeline预取
     */
    private int doSyncSegment(Connection sourceConn, DatabaseDialect sourceDialect,
                               SyncDbConn targetDbConn, DatabaseDialect targetDialect,
                               String effectiveSource, Map<String, String> fieldMapping,
                               WhereClause where, SyncTask task, long segOffset, long segLimit,
                               int batchSize, int syncMode, List<String> primaryKeys, String targetSchema,
                               AtomicInteger batchProgress) {
        int segSuccess = 0;
        long batchCount = (segLimit + batchSize - 1) / batchSize;
        boolean noUpdate = Boolean.TRUE.equals(task.getNoUpdate());

        // 预取第一批
        List<Map<String, Object>> mappedData = readAndMap(sourceConn, sourceDialect,
                effectiveSource, fieldMapping, where, segOffset, batchSize);

        for (long i = 0; i < batchCount; i++) {
            if (Thread.currentThread().isInterrupted() || cancelledTasks.containsKey(task.getId())) {
                log.info("分段[{}]收到中断信号, 停止处理, 已完成{}批", segOffset, i);
                break;
            }
            if (mappedData.isEmpty()) break;

            // 预取下一批(在入库期间并行出库)
            CompletableFuture<List<Map<String, Object>>> nextReadFuture = null;
            if (i + 1 < batchCount) {
                final long nextOffset = segOffset + (i + 1) * batchSize;
                nextReadFuture = CompletableFuture.supplyAsync(() ->
                        readAndMap(sourceConn, sourceDialect, effectiveSource,
                                fieldMapping, where, nextOffset, batchSize));
            }

            // 细分小批次并行入库
            int sbs = Math.min(subBatchSize, mappedData.size());
            int subBatchCount = (mappedData.size() + sbs - 1) / sbs;
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            int batchWritten = mappedData.size();

            for (int j = 0; j < subBatchCount; j++) {
                int from = j * sbs;
                int to = Math.min(from + sbs, mappedData.size());
                List<Map<String, Object>> subBatch = new ArrayList<>(mappedData.subList(from, to));

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try (Connection targetConn = getConn(targetDbConn)) {
                        targetConn.setAutoCommit(false);
                        int count = jdbcUtils.mergeInto(targetConn, targetDialect, task.getTargetTable(),
                                primaryKeys, subBatch, syncMode, targetSchema, noUpdate);
                        targetConn.commit();
                        return count;
                    } catch (Exception e) {
                        log.error("入库写入失败", e);
                        throw new RuntimeException(e);
                    }
                }, mergeExecutor));
            }

            for (CompletableFuture<Integer> future : futures) {
                try {
                    int c = future.get(calcTimeoutMs(sbs), TimeUnit.MILLISECONDS);
                    segSuccess += c;
                    batchWritten += c;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("入库写入被中断", e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new RuntimeException(e);
                }
            }
            if (batchProgress != null) {
                int added = batchProgress.addAndGet(batchWritten);
            }

            if (nextReadFuture != null) {
                mappedData = nextReadFuture.join();
            }
        }
        return segSuccess;
    }

    /**
     * CLEAN_INSERT专用: 共享目标连接版本, 不自行commit, 由调用方统一提交。
     * 注意: 此重载不自行commit, subBatch直接写共享连接。
     */
    private int doSyncSegment(Connection sourceConn, DatabaseDialect sourceDialect,
                               Connection sharedTargetConn, DatabaseDialect targetDialect,
                               String effectiveSource, Map<String, String> fieldMapping,
                               WhereClause where, SyncTask task, long segOffset, long segLimit,
                               int batchSize, int syncMode, List<String> primaryKeys, String targetSchema,
                               AtomicInteger batchProgress) {
        int segSuccess = 0;
        long batchCount = (segLimit + batchSize - 1) / batchSize;
        boolean noUpdate = Boolean.TRUE.equals(task.getNoUpdate());

        List<Map<String, Object>> mappedData = readAndMap(sourceConn, sourceDialect,
                effectiveSource, fieldMapping, where, segOffset, batchSize);

        for (long i = 0; i < batchCount; i++) {
            if (Thread.currentThread().isInterrupted() || cancelledTasks.containsKey(task.getId())) {
                log.info("分段[{}]收到中断信号, 停止处理, 已完成{}批", segOffset, i);
                break;
            }
            if (mappedData.isEmpty()) break;

            CompletableFuture<List<Map<String, Object>>> nextReadFuture = null;
            if (i + 1 < batchCount) {
                final long nextOffset = segOffset + (i + 1) * batchSize;
                nextReadFuture = CompletableFuture.supplyAsync(() ->
                        readAndMap(sourceConn, sourceDialect, effectiveSource,
                                fieldMapping, where, nextOffset, batchSize));
            }

            // 直接写入共享连接, 不新建连接不自行commit
            int sbs = Math.min(subBatchSize, mappedData.size());
            int subBatchCount = (mappedData.size() + sbs - 1) / sbs;
            int batchWritten = mappedData.size();
            for (int j = 0; j < subBatchCount; j++) {
                int from = j * sbs;
                int to = Math.min(from + sbs, mappedData.size());
                List<Map<String, Object>> subBatch = new ArrayList<>(mappedData.subList(from, to));
                int count = jdbcUtils.mergeInto(sharedTargetConn, targetDialect, task.getTargetTable(),
                        primaryKeys, subBatch, syncMode, targetSchema, noUpdate);
                segSuccess += count;
                batchWritten += count;
            }
            if (batchProgress != null) {
                int added = batchProgress.addAndGet(batchWritten);
            }

            if (nextReadFuture != null) {
                mappedData = nextReadFuture.join();
            }
        }
        return segSuccess;
    }

    /**
     * 出库 + 字段映射, 返回映射后的数据
     */
    private List<Map<String, Object>> readAndMap(Connection sourceConn, DatabaseDialect sourceDialect,
                                                  String effectiveSource, Map<String, String> fieldMapping,
                                                  WhereClause where, long offset, int batchSize) {
        List<Map<String, Object>> sourceData = jdbcUtils.queryOut(
                sourceConn, sourceDialect, effectiveSource, null, where, offset, batchSize);
        if (sourceData.isEmpty()) {
            return Collections.emptyList();
        }
        return applyFieldMapping(sourceData, fieldMapping);
    }

    /**
     * 解析有效数据源: sourceSql不为空则用自定义SQL, 否则用sourceTable
     */
    String resolveSource(String sourceTable, String sourceSql) {
        return (sourceSql != null && !sourceSql.trim().isEmpty()) ? sourceSql : sourceTable;
    }

    /**
     * 构建参数化查询条件(含增量标记), 支持多数据库方言
     * markerValue使用PreparedStatement参数占位符, 杜绝SQL注入
     * @param task 同步任务
     * @param dialect 数据库方言(用于生成方言相关的增量条件), 为null时使用通用比较
     */
    private WhereClause buildWhere(SyncTask task, DatabaseDialect dialect) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        // 增量条件
        if (task.getMarkerField() != null && !task.getMarkerField().trim().isEmpty()
                && task.getLastMarkerValue() != null && !task.getLastMarkerValue().trim().isEmpty()) {
            String dbType = dialect != null ? dialect.getDbType() : "oracle";
            String markerField = WhereClause.validateIdentifier(task.getMarkerField().trim(), "markerField");
            switch (dbType) {
                case "mysql":
                case "postgresql":
                    where.append(dialect.quote(markerField)).append(" > ?");
                    params.add(task.getLastMarkerValue());
                    break;
                default:
                    // Oracle/DM: TO_DATE兼容DATE和TIMESTAMP, 截断.0后缀
                    String mv = task.getLastMarkerValue();
                    if (mv != null && mv.length() > 19) mv = mv.substring(0, 19);
                    where.append(dialect.quote(markerField)).append(" > TO_DATE(?, 'YYYY-MM-DD HH24:MI:SS')");
                    params.add(mv);
                    break;
            }
        }
        if (task.getSourceWhere() != null && !task.getSourceWhere().trim().isEmpty()) {
            String sourceWhere = task.getSourceWhere().trim();
            SqlValidator.validateWhereFragment(sourceWhere);
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("(").append(sourceWhere).append(")");
        }
        return where.length() > 0 ? new WhereClause(where.toString(), params) : null;
    }

    /**
     * 将原始WHERE片段包装为WhereClause
     * 用于前端传入的WHERE条件和管理后台配置的sourceWhere(无参数化占位符)
     */
    private WhereClause wrapRawWhere(String rawWhere) {
        if (rawWhere == null || rawWhere.trim().isEmpty()) {
            return null;
        }
        return new WhereClause(rawWhere.trim());
    }

    /**
     * 解析字段映射JSON
     * 支持两种格式:
     * 1. 对象格式: {"SRC_COL":"TGT_COL"}  → 源字段名 -> 目标字段名
     * 2. 数组格式: [{"srcField":"LSH","tgtField":"LSH","isPk":true}]  → 提取srcField->tgtField
     * @return sourceCol -> targetCol
     */
    private Map<String, String> parseFieldMapping(String fieldMappingJson) {
        if (fieldMappingJson == null || fieldMappingJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            String trimmed = fieldMappingJson.trim();
            if (trimmed.startsWith("[")) {
                // 数组格式: [{"srcField":"LSH","tgtField":"LSH","isPk":true}]
                JsonNode arr = objectMapper.readTree(trimmed);
                Map<String, String> result = new LinkedHashMap<>();
                for (JsonNode item : arr) {
                    String srcField = item.has("srcField") ? item.get("srcField").asText() : null;
                    String tgtField = item.has("tgtField") ? item.get("tgtField").asText() : null;
                    if (srcField != null && tgtField != null) {
                        result.put(srcField.toUpperCase(), tgtField.toUpperCase());
                    }
                }
                return result;
            } else {
                // 对象格式: {"SRC_COL":"TGT_COL"}
                return objectMapper.readValue(fieldMappingJson, Map.class);
            }
        } catch (Exception e) {
            log.warn("字段映射JSON解析失败, 将按同名映射: {}", fieldMappingJson);
            return Collections.emptyMap();
        }
    }

    /**
     * 解析自定义主键
     */
    private List<String> parseCustomPk(String customPk) {
        if (customPk == null || customPk.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> pks = new ArrayList<>();
        for (String pk : customPk.split(",")) {
            String trimmed = pk.trim();
            if (!trimmed.isEmpty()) {
                pks.add(trimmed.toUpperCase());
            }
        }
        return pks;
    }

    /**
     * 应用字段映射
     * 仅保留映射关系中存在的字段, 未映射的源字段丢弃
     * @param sourceData 源数据
     * @param fieldMapping 源字段名 -> 目标字段名
     */
    private List<Map<String, Object>> applyFieldMapping(List<Map<String, Object>> sourceData, Map<String, String> fieldMapping) {
        if (fieldMapping.isEmpty()) {
            return sourceData;
        }
        List<Map<String, Object>> result = new ArrayList<>(sourceData.size());
        for (Map<String, Object> row : sourceData) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey().toUpperCase();
                // 查找映射: 先精确匹配, 再忽略大小写匹配
                String targetKey = null;
                for (Map.Entry<String, String> mapping : fieldMapping.entrySet()) {
                    if (mapping.getKey().equalsIgnoreCase(key)) {
                        targetKey = mapping.getValue().toUpperCase();
                        break;
                    }
                }
                // 仅保留在映射关系中的字段, 未映射的源字段丢弃
                if (targetKey != null) {
                    newRow.put(targetKey, entry.getValue());
                }
            }
            result.add(newRow);
        }
        return result;
    }

    Connection getConn(SyncDbConn conn) throws Exception {
        // 密码从数据库读出, 需AES解密后才能用于JDBC连接
        String decryptedPassword = passwordEncryptService.aesDecrypt(conn.getPassword());
        return dataSourceUtils.getConnection(conn.getId(), conn.getDbType(),
                conn.getJdbcUrl(), conn.getUsername(), decryptedPassword, conn.getDriverClass());
    }

    /**
     * 解析连接的schema, 优先用conn中已设置的schema, 否则用username兜底
     * Oracle中schema不等于username时必须显式配置schema字段
     */
    String resolveSchema(SyncDbConn conn) {
        if (conn.getSchema() != null && !conn.getSchema().trim().isEmpty()) {
            return conn.getSchema().toUpperCase();
        }
        // 兜底用username(Oracle默认schema等于username)
        return conn.getUsername() != null ? conn.getUsername().toUpperCase() : null;
    }

    /**
     * 定时清理空闲数据源连接池(每5分钟执行一次)
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanIdleDataSources() {
        dataSourceUtils.cleanIdleDataSources();
    }

    /**
     * 超时任务检测(每5分钟检查一次)
     * 单次执行超过12小时的任务将被强制终止(仅终止本次执行, 不影响下个周期)
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void checkTimeoutTasks() {
        if (taskStartTimes.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<String> timeoutTaskIds = new ArrayList<>();

        for (Map.Entry<String, Long> entry : taskStartTimes.entrySet()) {
            String taskId = entry.getKey();
            long startTime = entry.getValue();
            if (now - startTime > MAX_EXECUTION_MS) {
                timeoutTaskIds.add(taskId);
            }
        }

        for (String taskId : timeoutTaskIds) {
            log.warn("任务[{}]已执行超过12小时, 强制终止本次执行", taskId);
            boolean stopped = stopTask(taskId);
            if (stopped) {
                // 更新任务状态为error(超时)
                taskMapper.updateRunStatus(taskId, "error", new Date());
                // 更新最新的日志记录
                SyncTaskLog latestLog = logMapper.getLatestByTaskId(taskId);
                if (latestLog != null) {
                    latestLog.setStatus(SyncStatus.FAILED.getCode());
                    latestLog.setErrorMsg("任务执行超过12小时, 已被系统强制终止");
                    latestLog.setEndTime(new Date());
                    logMapper.updateResult(latestLog);
                }
                taskStartTimes.remove(taskId);
            }
        }
    }
}