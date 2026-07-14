package com.scxd.service.impl;

import com.scxd.config.Response;
import com.scxd.dialect.DialectFactory;
import com.scxd.mapper.SyncTaskLogMapper;
import com.scxd.model.dto.TaskLogQueryDto;
import com.scxd.model.dto.TodaySummaryDto;
import com.scxd.model.entity.SyncTaskLog;
import com.scxd.model.enums.SyncStatus;
import com.scxd.service.SyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class SyncLogServiceImpl implements SyncLogService {

    @Autowired
    private SyncTaskLogMapper logMapper;

    @Value("${spring.datasource.url:}")
    private String metadataDbUrl;

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
    public Response list(TaskLogQueryDto query) {
        // 构建SQL过滤条件，在数据库端完成过滤
        Map<String, Object> params = new HashMap<>();

        // 状态过滤：转为数据库STATUS值
        if (query.getStatus() != null && !query.getStatus().trim().isEmpty()
                && !"all".equalsIgnoreCase(query.getStatus())) {
            Integer statusCode = parseStatusFilter(query.getStatus());
            if (statusCode != null) {
                params.put("status", statusCode);
            }
        }
        if (query.getTaskId() != null && !query.getTaskId().trim().isEmpty()) {
            params.put("taskId", query.getTaskId().trim());
        }

        // 关键字过滤
        if (query.getKeyword() != null && !query.getKeyword().trim().isEmpty()) {
            params.put("keyword", query.getKeyword().trim());
        }

        // 使用带条件的查询，数据库端过滤
        List<SyncTaskLog> allLogs = logMapper.listByCondition(params);

        // 计算总数（已经是过滤后的结果）
        int total = allLogs.size();

        // 分页
        int page = query.getPage() != null ? query.getPage() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        if (pageSize > 100) pageSize = 100;

        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<SyncTaskLog> pageList;
        if (fromIndex >= total) {
            pageList = Collections.emptyList();
        } else {
            pageList = allLogs.subList(fromIndex, toIndex);
        }

        // 构造返回格式
        List<Map<String, Object>> logList = new ArrayList<>();
        for (SyncTaskLog logEntity : pageList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", logEntity.getId());
            item.put("exec_time", logEntity.getStartTime());
            item.put("status", formatStatus(logEntity.getStatus()));
            item.put("rows_total", logEntity.getTotalCount());
            item.put("duration_seconds", calcDurationSeconds(logEntity));
            item.put("duration_display", calcDurationDisplay(logEntity));
            item.put("insert_count", logEntity.getInsertCount() != null ? logEntity.getInsertCount() : 0);
            item.put("update_count", logEntity.getUpdateCount() != null ? logEntity.getUpdateCount() : 0);
            item.put("error_count", logEntity.getFailCount() != null ? logEntity.getFailCount() : 0);
            item.put("detail_summary", logEntity.getErrorMsg() != null ? logEntity.getErrorMsg() : "无异常上报");
            item.put("source_database", "");
            item.put("target_database", "");
            logList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", logList);
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total", total);
        pagination.put("total_pages", (total + pageSize - 1) / pageSize);
        result.put("pagination", pagination);

        // 汇总
        long successCount = allLogs.stream().filter(l -> SyncStatus.SUCCESS.matches(l.getStatus())).count();
        long failCount = allLogs.stream().filter(l -> SyncStatus.FAILED.matches(l.getStatus())).count();
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_exec", total);
        summary.put("success_rate", total > 0 ? String.format("%.1f%%", successCount * 100.0 / total) : "0%");
        summary.put("error_count", failCount);
        result.put("summary", summary);

        return Response.success(result);
    }

    @Override
    public Response detail(String id) {
        SyncTaskLog logEntity = logMapper.getById(id);
        if (logEntity == null) {
            return Response.configNotFound("日志不存在");
        }

        Map<String, Object> basic = new LinkedHashMap<>();
        basic.put("job_id", logEntity.getId());
        basic.put("exec_time", logEntity.getStartTime());
        basic.put("status", formatStatus(logEntity.getStatus()));
        basic.put("duration", calcDurationDisplay(logEntity));
        basic.put("rows_total", logEntity.getTotalCount() + " rows");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("insert_count", logEntity.getInsertCount() != null ? logEntity.getInsertCount() : 0);
        stats.put("update_count", logEntity.getUpdateCount() != null ? logEntity.getUpdateCount() : 0);
        stats.put("error_count", logEntity.getFailCount() != null ? logEntity.getFailCount() : 0);
        stats.put("skip_count", logEntity.getSkipCount() != null ? logEntity.getSkipCount() : 0);

        // 时间线
        List<Map<String, Object>> timeline = new ArrayList<>();
        if (logEntity.getStartTime() != null) {
            Map<String, Object> t1 = new LinkedHashMap<>();
            t1.put("time", formatTime(logEntity.getStartTime()));
            t1.put("event", "任务 " + logEntity.getId() + " 开始执行");
            timeline.add(t1);
        }
        if (logEntity.getEndTime() != null) {
            Map<String, Object> t2 = new LinkedHashMap<>();
            t2.put("time", formatTime(logEntity.getEndTime()));
            String event = SyncStatus.SUCCESS.matches(logEntity.getStatus()) ? "任务已完成" : "任务执行失败";
            t2.put("event", event + "，总耗时 " + calcDurationDisplay(logEntity));
            timeline.add(t2);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basic", basic);
        result.put("stats", stats);
        result.put("timeline", timeline);
        result.put("errors", logEntity.getErrorMsg() != null ? Collections.singletonList(logEntity.getErrorMsg()) : Collections.emptyList());

        return Response.success(result);
    }

    @Override
    public Response todaySummary() {
        // 使用数据库端聚合查询，避免全量加载
        try {
            TodaySummaryDto summary;
            if ("mysql".equals(getMetadataDbType())) {
                summary = logMapper.todaySummaryMysql();
            } else {
                summary = logMapper.todaySummary();
            }
            if (summary == null) {
                summary = new TodaySummaryDto();
                summary.setTotalExecutions(0);
                summary.setSuccessCount(0);
                summary.setFailCount(0);
                summary.setWarningCount(0);
                summary.setTotalRowsSynced(0);
                summary.setTotalErrorRows(0);
                summary.setTotalDurationMs(0L);
            }

            int totalExecutions = summary.getTotalExecutions() != null ? summary.getTotalExecutions() : 0;
            int successCount = summary.getSuccessCount() != null ? summary.getSuccessCount() : 0;
            int failCount = summary.getFailCount() != null ? summary.getFailCount() : 0;
            int warningCount = summary.getWarningCount() != null ? summary.getWarningCount() : 0;
            int totalRowsSynced = summary.getTotalRowsSynced() != null ? summary.getTotalRowsSynced() : 0;
            int totalErrorRows = summary.getTotalErrorRows() != null ? summary.getTotalErrorRows() : 0;
            long totalDurationMs = summary.getTotalDurationMs() != null ? summary.getTotalDurationMs() : 0L;

            double avgDurationMinutes = totalExecutions > 0 ? (totalDurationMs / 60000.0 / totalExecutions) : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_executions", totalExecutions);
            result.put("success_count", successCount);
            result.put("fail_count", failCount);
            result.put("warning_count", warningCount);
            result.put("success_rate", totalExecutions > 0 ? (double) successCount / totalExecutions : 0);
            result.put("total_rows_synced", totalRowsSynced);
            result.put("total_error_rows", totalErrorRows);
            result.put("avg_duration_minutes", Math.round(avgDurationMinutes * 10.0) / 10.0);

            return Response.success(result);
        } catch (Exception e) {
            log.error("今日日志统计查询失败", e);
            return Response.error("数据库连接不可用: " + e.getMessage());
        }
    }

    // ======================== 辅助方法 ========================

    private Integer parseStatusFilter(String status) {
        switch (status.toLowerCase()) {
            case "success": return SyncStatus.SUCCESS.getCode();
            case "fail": return SyncStatus.FAILED.getCode();
            case "running": return SyncStatus.RUNNING.getCode();
            case "warning": return SyncStatus.RUNNING.getCode();
            default: return null;
        }
    }

    private String formatStatus(Integer status) {
        if (status == null) return "unknown";
        if (SyncStatus.RUNNING.matches(status)) return "running";
        if (SyncStatus.SUCCESS.matches(status)) return "success";
        if (SyncStatus.FAILED.matches(status)) return "fail";
        return "unknown";
    }

    private Long calcDurationSeconds(SyncTaskLog logEntity) {
        if (logEntity.getStartTime() != null && logEntity.getEndTime() != null) {
            return (logEntity.getEndTime().getTime() - logEntity.getStartTime().getTime()) / 1000;
        }
        return null;
    }

    private String calcDurationDisplay(SyncTaskLog logEntity) {
        Long seconds = calcDurationSeconds(logEntity);
        if (seconds == null) return "-";
        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    private String formatTime(Date date) {
        if (date == null) return "";
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return String.format("%02d:%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND));
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }
}
