package com.scxd.service.impl;

import com.scxd.config.Response;
import com.scxd.dialect.DialectFactory;
import com.scxd.mapper.SyncDbConnMapper;
import com.scxd.mapper.SyncTaskLogMapper;
import com.scxd.mapper.SyncTaskMapper;
import com.scxd.model.dto.TodaySummaryDto;
import com.scxd.model.entity.SyncTask;
import com.scxd.model.entity.SyncTaskLog;
import com.scxd.model.enums.SyncStatus;
import com.scxd.service.DashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 看板/实时监控服务实现
 * 基于SYNC_TASK_LOG和SYNC_TASK表聚合统计数据
 * 已重构：使用SQL条件过滤替代Java内存过滤
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private SyncTaskLogMapper logMapper;

    @Autowired
    private SyncTaskMapper taskMapper;

    @Autowired
    private SyncDbConnMapper connMapper;

    @Value("${spring.datasource.url:}")
    private String metadataDbUrl;

    @Autowired
    @Qualifier("syncExecutor")
    private ExecutorService syncExecutor;

    @Autowired
    @Qualifier("mergeExecutor")
    private ExecutorService mergeExecutor;

    /** 最大保留采样数(约6小时, 每5分钟一次) */
    private static final int MAX_SAMPLES = 72;

    /** 延迟计算窗口(毫秒), 默认30秒 */
    private static final long LATENCY_WINDOW_MS = 30 * 1000L;

    /**
     * 性能指标采样缓冲区(内存中保留最近N分钟的数据点)
     */
    private final Deque<MetricSample> metricBuffer = new ConcurrentLinkedDeque<>();

    /** 获取元数据库类型 */
    private String getMetadataDbType() {
        if (metadataDbUrl == null || metadataDbUrl.isEmpty()) {
            return "oracle";
        }
        try {
            return DialectFactory.inferDbType(metadataDbUrl);
        } catch (Exception e) {
            return "oracle";
        }
    }

    @Override
    public Response overview() {
        try {
            long now = System.currentTimeMillis();
            long tenSecAgo = now - 10 * 1000;
            long twentySecAgo = now - 20 * 1000;
            long latencyWindowStart = now - LATENCY_WINDOW_MS;

            // 1. 当前RPS(最近10秒内完成的任务, 按其平均速率贡献; 避免累计SUCCESS_COUNT直接除以窗口导致虚高)
            Map<String, Object> recentParams = new HashMap<>();
            recentParams.put("endTimeAfter", new Date(tenSecAgo));
            List<SyncTaskLog> recentLogs = logMapper.listByCondition(recentParams);
            long currentRps = 0;
            for (SyncTaskLog l : recentLogs) {
                if (l.getStartTime() != null && l.getEndTime() != null && l.getSuccessCount() != null && l.getSuccessCount() > 0) {
                    long durSec = Math.max(1, (l.getEndTime().getTime() - l.getStartTime().getTime()) / 1000);
                    currentRps += l.getSuccessCount() / durSec;
                }
            }
            // 如果近期无完成日志, 从运行中任务SUCCESS_COUNT实时算RPS(加时间过滤防僵尸数据)
            if (currentRps == 0) {
                Map<String, Object> rParams = new HashMap<>();
                rParams.put("status", SyncStatus.RUNNING.getCode());
                rParams.put("startTimeAfter", new Date(now - 24 * 60 * 60 * 1000L));
                List<SyncTaskLog> runningNow = logMapper.listByCondition(rParams);
                if (!runningNow.isEmpty()) {
                    SyncTaskLog rl = runningNow.get(0);
                    if (rl.getStartTime() != null && rl.getSuccessCount() != null && rl.getSuccessCount() > 0) {
                        long sec = Math.max(1, (System.currentTimeMillis() - rl.getStartTime().getTime()) / 1000);
                        currentRps = rl.getSuccessCount() / sec;
                    }
                }
            }

            // 2. RPS趋势(对比上一周期, 同样用平均速率避免累计值虚高)
            Map<String, Object> prevParams = new HashMap<>();
            prevParams.put("endTimeAfter", new Date(twentySecAgo));
            prevParams.put("endTimeBefore", new Date(tenSecAgo));
            List<SyncTaskLog> prevLogs = logMapper.listByCondition(prevParams);
            long prevRps = 0;
            for (SyncTaskLog l : prevLogs) {
                if (l.getStartTime() != null && l.getEndTime() != null && l.getSuccessCount() != null && l.getSuccessCount() > 0) {
                    long durSec = Math.max(1, (l.getEndTime().getTime() - l.getStartTime().getTime()) / 1000);
                    prevRps += l.getSuccessCount() / durSec;
                }
            }
            String rpsTrend;
            if (prevRps > 0) {
                double change = ((double) (currentRps - prevRps) / prevRps) * 100;
                rpsTrend = (change >= 0 ? "+" : "") + String.format("%.0f%%", change);
            } else if (currentRps > 0) {
                rpsTrend = "+100%";
            } else {
                rpsTrend = "N/A";
            }

            // 3. 平均延迟(仅统计最近30秒内完成的任务, 用每千行处理时间而非全任务时长)
            Map<String, Object> latencyParams = new HashMap<>();
            latencyParams.put("status", SyncStatus.SUCCESS.getCode());
            latencyParams.put("endTimeAfter", new Date(latencyWindowStart));
            List<SyncTaskLog> successLogs = logMapper.listByCondition(latencyParams);
            double avgLatencyMs = 0;
            long latencyCount = 0;
            double totalLatencyPer1k = 0;
            for (SyncTaskLog l : successLogs) {
                if (l.getStartTime() != null && l.getEndTime() != null) {
                    long durMs = l.getEndTime().getTime() - l.getStartTime().getTime();
                    int done = (l.getSuccessCount() != null ? l.getSuccessCount() : 0)
                            + (l.getFailCount() != null ? l.getFailCount() : 0);
                    if (done > 0) {
                        totalLatencyPer1k += (double) durMs / (done / 1000.0);
                        latencyCount++;
                    }
                }
            }
            if (latencyCount > 0) {
                avgLatencyMs = Math.round(totalLatencyPer1k / latencyCount * 100.0) / 100.0;
            } else {
                Map<String, Object> rParams2 = new HashMap<>();
                rParams2.put("status", SyncStatus.RUNNING.getCode());
                rParams2.put("startTimeAfter", new Date(now - 24 * 60 * 60 * 1000L));
                List<SyncTaskLog> runningNow2 = logMapper.listByCondition(rParams2);
                if (!runningNow2.isEmpty()) {
                    SyncTaskLog rl3 = runningNow2.get(0);
                    if (rl3.getStartTime() != null && rl3.getTotalCount() != null && rl3.getTotalCount() > 0) {
                        long elapsedMs = System.currentTimeMillis() - rl3.getStartTime().getTime();
                        int done = (rl3.getSuccessCount() != null ? rl3.getSuccessCount() : 0)
                                + (rl3.getFailCount() != null ? rl3.getFailCount() : 0);
                        if (done > 0) avgLatencyMs = Math.round((double) elapsedMs / (done / 1000.0));
                    }
                }
            }

            // 4. 延迟状态
            String latencyStatus = "stable";
            if (avgLatencyMs > 5000) {
                latencyStatus = "critical";
            } else if (avgLatencyMs > 2000) {
                latencyStatus = "warning";
            }

            // 5. 今日同步行数（数据库端聚合，按元数据库类型选择方言）
            TodaySummaryDto todaySummary;
            if ("mysql".equals(getMetadataDbType())) {
                todaySummary = logMapper.todaySummaryMysql();
            } else {
                todaySummary = logMapper.todaySummary();
            }
            long todayRowsSynced = 0;
            long todayDurationMs = 0;
            if (todaySummary != null) {
                todayRowsSynced = todaySummary.getTotalRowsSynced() != null ? todaySummary.getTotalRowsSynced() : 0;
                todayDurationMs = todaySummary.getTotalDurationMs() != null ? todaySummary.getTotalDurationMs() : 0L;
            }

            // 6. 任务运行时长(今日)
            long taskDurationHours = todayDurationMs / (1000 * 60 * 60);

            // 7. 吞吐量
            double throughputMbps = 0;
            if (todayDurationMs > 0) {
                double totalBytes = todayRowsSynced * 500.0;
                double totalSeconds = todayDurationMs / 1000.0;
                throughputMbps = Math.round(totalBytes / totalSeconds / 1024 / 1024 * 100.0) / 100.0;
            }

            // 8. 全量完成度（聚合所有运行中任务的进度）
            int fullSyncProgress = -1;
            Map<String, Object> runningParams = new HashMap<>();
            runningParams.put("status", SyncStatus.RUNNING.getCode());
            List<SyncTaskLog> runningLogs = logMapper.listByCondition(runningParams);
            if (!runningLogs.isEmpty()) {
                long runningTotal = 0;
                long runningDone = 0;
                for (SyncTaskLog runningLog : runningLogs) {
                    if (runningLog.getTotalCount() != null && runningLog.getTotalCount() > 0) {
                        runningTotal += runningLog.getTotalCount();
                        runningDone += (runningLog.getSuccessCount() != null ? runningLog.getSuccessCount() : 0)
                                + (runningLog.getFailCount() != null ? runningLog.getFailCount() : 0);
                    }
                }
                if (runningTotal > 0) {
                    fullSyncProgress = (int) ((double) runningDone / runningTotal * 100);
                    fullSyncProgress = Math.min(fullSyncProgress, 99);
                } else {
                    fullSyncProgress = 0;
                }
            }

            // 9. CDC状态
            String cdcStatus = "idle";
            boolean hasRunningTask = !runningLogs.isEmpty();
            if (hasRunningTask) {
                cdcStatus = "active";
            }
            Map<String, Object> failParams = new HashMap<>();
            failParams.put("status", SyncStatus.FAILED.getCode());
            failParams.put("endTimeAfter", new Date(tenSecAgo));
            List<SyncTaskLog> recentFailLogs = logMapper.listByCondition(failParams);
            if (!recentFailLogs.isEmpty()) {
                cdcStatus = "error";
            }

            // 10. 最近事件(最近24小时的最后一条日志，避免全表扫描)
            Map<String, Object> lastCdcEvent = null;
            Map<String, Object> lastEventParams = new HashMap<>();
            lastEventParams.put("endTimeAfter", new Date(now - 24 * 60 * 60 * 1000L));
            List<SyncTaskLog> recentEventLogs = logMapper.listByCondition(lastEventParams);
            for (SyncTaskLog l : recentEventLogs) {
                if (l.getStartTime() != null) {
                    SyncTask task = taskMapper.getById(l.getTaskId());
                    if (task != null) {
                        lastCdcEvent = new LinkedHashMap<>();
                        lastCdcEvent.put("table", task.getSourceTable());
                        lastCdcEvent.put("operation", SyncStatus.SUCCESS.matches(l.getStatus()) ? "SYNC_COMPLETE" : "SYNC_RUNNING");
                        lastCdcEvent.put("time", l.getStartTime());
                        break;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current_rps", currentRps);
            result.put("rps_trend", rpsTrend);
            result.put("latency_ms", avgLatencyMs);
            result.put("latency_status", latencyStatus);
            result.put("today_rows_synced", todayRowsSynced);
            result.put("task_duration_hours", taskDurationHours);
            result.put("throughput_mbps", throughputMbps);
            result.put("bandwidth_usage_pct", throughputMbps > 0 ? Math.min((int) (throughputMbps / 100 * 100), 100) : 0);
            result.put("full_sync_progress", fullSyncProgress);
            result.put("cdc_status", cdcStatus);
            result.put("last_cdc_event", lastCdcEvent);

            // 记录采样
            recordSample(currentRps, avgLatencyMs);

            return Response.success(result);
        } catch (Exception e) {
            log.error("看板概览查询失败", e);
            return Response.error("数据库连接不可用: " + e.getMessage());
        }
    }

    @Override
    public Response perfMetrics(Integer minutes, Integer intervalSec) {
        if (minutes == null || minutes <= 0) minutes = 30;
        if (intervalSec == null || intervalSec <= 0) intervalSec = 300;
        try {
            // 使用SQL过滤：只查指定时间范围内的日志
            long now = System.currentTimeMillis();
            long startTime = now - (long) minutes * 60 * 1000;

            Map<String, Object> params = new HashMap<>();
            params.put("endTimeAfter", new Date(startTime));
            List<SyncTaskLog> filteredLogs = logMapper.listByCondition(params);

            // 按时间窗口分组统计
            Map<String, List<SyncTaskLog>> windowMap = new TreeMap<>();
            SimpleDateFormat keyFormat = new SimpleDateFormat("HH:mm");

            for (SyncTaskLog l : filteredLogs) {
                if (l.getEndTime() == null) continue;

                // 计算所属时间窗口
                long windowIndex = l.getEndTime().getTime() / (intervalSec * 1000);
                long windowStart = windowIndex * intervalSec * 1000;
                String windowKey = keyFormat.format(new Date(windowStart));

                windowMap.computeIfAbsent(windowKey, k -> new ArrayList<>()).add(l);
            }

            // 构建输出
            List<String> labels = new ArrayList<>();
            List<Long> rps = new ArrayList<>();
            List<Double> latencyMs = new ArrayList<>();

            SimpleDateFormat labelFormat = new SimpleDateFormat("HH:mm");
            // 生成时间窗口
            for (long t = (startTime / (intervalSec * 1000)) * (intervalSec * 1000);
                 t <= now; t += intervalSec * 1000L) {
                String label = labelFormat.format(new Date(t));
                labels.add(label);

                List<SyncTaskLog> windowLogs = windowMap.get(label);
                if (windowLogs == null || windowLogs.isEmpty()) {
                    rps.add(0L);
                    latencyMs.add(0.0);
                } else {
                    // RPS: 该窗口内成功行数 / 窗口秒数
                    long windowRows = 0;
                    long windowLatencyTotal = 0;
                    int windowLatencyCount = 0;
                    for (SyncTaskLog l : windowLogs) {
                        windowRows += l.getSuccessCount() != null ? l.getSuccessCount() : 0;
                        if (l.getStartTime() != null && l.getEndTime() != null) {
                            windowLatencyTotal += l.getEndTime().getTime() - l.getStartTime().getTime();
                            windowLatencyCount++;
                        }
                    }
                    rps.add(windowRows / intervalSec);
                    latencyMs.add(windowLatencyCount > 0
                            ? Math.round(windowLatencyTotal * 100.0 / windowLatencyCount / 1000.0) / 100.0
                            : 0.0);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("labels", labels);
            result.put("rps", rps);
            result.put("latency_ms", latencyMs);

            return Response.success(result);
        } catch (Exception e) {
            log.error("性能曲线查询失败", e);
            return Response.error("数据库连接不可用: " + e.getMessage());
        }
    }

    @Override
    public Response syncDistribution() {
        try {
            List<SyncTask> allTasks = taskMapper.listAll();
            List<SyncTaskLog> allLogs = logMapper.listAll();

            // 按源表名统计同步行数
            Map<String, Long> tableRowMap = new LinkedHashMap<>();
            for (SyncTaskLog l : allLogs) {
                String taskId = l.getTaskId();
                SyncTask task = null;
                for (SyncTask t : allTasks) {
                    if (t.getId().equals(taskId)) {
                        task = t;
                        break;
                    }
                }
                String tableName = task != null ? task.getSourceTable() : "unknown";
                long rows = l.getTotalCount() != null ? l.getTotalCount() : 0;
                tableRowMap.merge(tableName, rows, Long::sum);
            }

            // 排序取TOP 5
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(tableRowMap.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            List<String> labels = new ArrayList<>();
            List<Integer> data = new ArrayList<>();

            long totalRows = sorted.stream().mapToLong(Map.Entry::getValue).sum();
            int topN = Math.min(5, sorted.size());
            long otherRows = 0;

            for (int i = 0; i < sorted.size(); i++) {
                Map.Entry<String, Long> entry = sorted.get(i);
                if (i < topN) {
                    labels.add(entry.getKey());
                    int pct = totalRows > 0 ? (int) (entry.getValue() * 100 / totalRows) : 0;
                    data.add(pct);
                } else {
                    otherRows += entry.getValue();
                }
            }

            if (otherRows > 0 && sorted.size() > topN) {
                labels.add("other");
                int pct = totalRows > 0 ? (int) (otherRows * 100 / totalRows) : 0;
                data.add(pct);
            }

            // 如果没有任何数据, 返回空列表
            if (labels.isEmpty()) {
                labels.add("no_data");
                data.add(0);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("labels", labels);
            result.put("data", data);

            return Response.success(result);
        } catch (Exception e) {
            log.error("同步分布查询失败", e);
            return Response.error("数据库连接不可用: " + e.getMessage());
        }
    }

    @Override
    public Response runtimeMetrics() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();

            // 线程池状态
            if (syncExecutor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor se = (ThreadPoolExecutor) syncExecutor;
                m.put("sync_pool_size", se.getPoolSize());
                m.put("sync_active_threads", se.getActiveCount());
                m.put("sync_queue_size", se.getQueue().size());
                m.put("sync_completed_tasks", se.getCompletedTaskCount());
            }
            if (mergeExecutor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor me = (ThreadPoolExecutor) mergeExecutor;
                m.put("merge_pool_size", me.getPoolSize());
                m.put("merge_active_threads", me.getActiveCount());
                m.put("merge_queue_size", me.getQueue().size());
                m.put("merge_completed_tasks", me.getCompletedTaskCount());
            }

            // 今日统计
            TodaySummaryDto today = getTodaySummary();
            int todayTotal = today != null && today.getTotalExecutions() != null ? today.getTotalExecutions() : 0;
            int todaySuccess = today != null && today.getSuccessCount() != null ? today.getSuccessCount() : 0;
            m.put("today_executions", todayTotal);
            m.put("today_success_rate", todayTotal > 0 ? String.format("%.1f%%", todaySuccess * 100.0 / todayTotal) : "N/A");

            return Response.success(m);
        } catch (Exception e) {
            log.error("运行时指标查询失败", e);
            return Response.error("查询失败: " + e.getMessage());
        }
    }

    private TodaySummaryDto getTodaySummary() {
        try {
            return "mysql".equals(getMetadataDbType()) ? logMapper.todaySummaryMysql() : logMapper.todaySummary();
        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 采样记录 ========================

    private void recordSample(long rps, double latencyMs) {
        MetricSample sample = new MetricSample();
        sample.timestamp = System.currentTimeMillis();
        sample.rps = rps;
        sample.latencyMs = latencyMs;
        metricBuffer.addLast(sample);
        // 裁剪缓冲区
        while (metricBuffer.size() > MAX_SAMPLES) {
            metricBuffer.removeFirst();
        }
    }

    private static class MetricSample {
        long timestamp;
        long rps;
        double latencyMs;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }
}
