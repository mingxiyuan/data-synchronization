package com.scxd.service.impl;

import com.scxd.config.Response;
import com.scxd.mapper.ScheduleMapper;
import com.scxd.mapper.SyncDbConnMapper;
import com.scxd.mapper.SyncTaskLogMapper;
import com.scxd.mapper.SyncTaskMapper;
import com.scxd.model.dto.SyncConfigDeployDto;
import com.scxd.model.dto.TaskLogStatsDto;
import com.scxd.model.entity.Schedule;
import com.scxd.model.enums.SyncMode;
import com.scxd.model.enums.SyncStatus;
import com.scxd.model.entity.SyncDbConn;
import com.scxd.model.entity.SyncTask;
import com.scxd.model.entity.SyncTaskLog;
import com.scxd.service.SyncExecService;
import com.scxd.service.SyncTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class SyncTaskServiceImpl implements SyncTaskService {

    @Autowired
    private SyncTaskMapper taskMapper;

    @Autowired
    private SyncDbConnMapper connMapper;

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private SyncTaskLogMapper logMapper;

    @Autowired
    private SyncExecService execService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Response list() {
        List<SyncTask> list = taskMapper.listAll();
        // 批量查询关联信息，避免N+1查询
        // 1. 收集所有需要查询的ID
        Set<String> connIds = new HashSet<>();
        Set<String> scheduleIds = new HashSet<>();
        Set<String> taskIds = new HashSet<>();
        for (SyncTask task : list) {
            if (task.getSourceConnId() != null) connIds.add(task.getSourceConnId());
            if (task.getTargetConnId() != null) connIds.add(task.getTargetConnId());
            if (task.getScheduleId() != null) scheduleIds.add(task.getScheduleId());
            taskIds.add(task.getId());
        }

        // 2. 批量查询连接信息
        Map<String, SyncDbConn> connMap = new HashMap<>();
        for (String connId : connIds) {
            try {
                SyncDbConn conn = connMapper.getById(connId);
                if (conn != null) connMap.put(connId, conn);
            } catch (Exception ignored) {}
        }

        // 3. 批量查询调度信息
        Map<String, Schedule> scheduleMap = new HashMap<>();
        for (String scheduleId : scheduleIds) {
            try {
                Schedule schedule = scheduleMapper.getById(scheduleId);
                if (schedule != null) scheduleMap.put(scheduleId, schedule);
            } catch (Exception ignored) {}
        }

        // 4. 批量查询日志统计（一次SQL查询所有任务的统计）
        Map<String, TaskLogStatsDto> logStatsMap = new HashMap<>();
        if (!taskIds.isEmpty()) {
            try {
                List<TaskLogStatsDto> logStats = logMapper.countByTaskIdGroupByStatus(new ArrayList<>(taskIds));
                for (TaskLogStatsDto stat : logStats) {
                    logStatsMap.put(stat.getTaskId(), stat);
                }
            } catch (Exception e) {
                log.warn("批量查询任务日志统计失败: {}", e.getMessage());
            }
        }

        // 5. 组装结果
        List<Map<String, Object>> resultList = new ArrayList<>();
        for (SyncTask task : list) {
            Map<String, Object> item = taskToMap(task, connMap, scheduleMap, logStatsMap.get(task.getId()));
            resultList.add(item);
        }
        return Response.success(resultList);
    }

    @Override
    public Response add(SyncTask task) {
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        // name为空时自动生成: 源表→目标表
        if (task.getName() == null || task.getName().trim().isEmpty()) {
            String src = task.getSourceTable() != null ? task.getSourceTable() : "";
            String tgt = task.getTargetTable() != null ? task.getTargetTable() : "";
            task.setName(src + " → " + tgt);
        }
        task.setStatus(SyncStatus.RUNNING.getCode());
        // 新创建的任务默认为已停止
        task.setRunStatus("stopped");
        if (task.getTotalRowsSynced() == null) {
            task.setTotalRowsSynced(0L);
        }
        Date now = new Date();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        if (task.getBatchSize() == null) {
            task.setBatchSize(1000);
        }
        if (task.getSyncMode() == null) {
            task.setSyncMode(SyncMode.MERGE.getCode());
        }
        if (task.getStrategy() == null || task.getStrategy().trim().isEmpty()) {
            task.setStrategy("incremental");
        }
        // 根据strategy设置syncMode和markerField
        applyStrategy(task, task.getStrategy());
        // 从field_mappings中提取isPk=true的字段名作为primaryKeys
        extractPrimaryKeys(task);
        taskMapper.insert(task);

        // 构造返回map
        Map<String, Object> result = taskToMap(task, Collections.emptyMap(), Collections.emptyMap(), null);
        return Response.success(result);
    }

    @Override
    public Response update(String id, SyncTask task) {
        SyncTask existing = taskMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("任务不存在");
        }
        // 合并：前端只传部分字段，未传的保留原值
        if (task.getName() == null) task.setName(existing.getName());
        if (task.getSourceConnId() == null) task.setSourceConnId(existing.getSourceConnId());
        if (task.getTargetConnId() == null) task.setTargetConnId(existing.getTargetConnId());
        if (task.getSourceTable() == null) task.setSourceTable(existing.getSourceTable());
        if (task.getSourceSql() == null) task.setSourceSql(existing.getSourceSql());
        if (task.getTargetTable() == null) task.setTargetTable(existing.getTargetTable());
        if (task.getFieldMapping() == null) task.setFieldMapping(existing.getFieldMapping());
        if (task.getCustomPk() == null) task.setCustomPk(existing.getCustomPk());
        if (task.getMarkerField() == null) task.setMarkerField(existing.getMarkerField());
        if (task.getLastMarkerValue() == null) task.setLastMarkerValue(existing.getLastMarkerValue());
        if (task.getPrimaryKeys() == null) task.setPrimaryKeys(existing.getPrimaryKeys());
        if (task.getSourceWhere() == null) task.setSourceWhere(existing.getSourceWhere());
        if (task.getStrategy() == null) task.setStrategy(existing.getStrategy());
        if (task.getSyncMode() == null) task.setSyncMode(existing.getSyncMode());
        if (task.getBatchSize() == null) task.setBatchSize(existing.getBatchSize());
        if (task.getTaskGroup() == null) task.setTaskGroup(existing.getTaskGroup());
        if (task.getScheduleId() == null) task.setScheduleId(existing.getScheduleId());
        if (task.getRunStatus() == null) task.setRunStatus(existing.getRunStatus());
        if (task.getTotalRowsSynced() == null) task.setTotalRowsSynced(existing.getTotalRowsSynced());
        if (task.getStatus() == null) task.setStatus(existing.getStatus());
        if (task.getRemark() == null) task.setRemark(existing.getRemark());
        task.setId(id);
        task.setUpdateTime(new Date());
        // 根据strategy设置syncMode
        if (task.getStrategy() != null) {
            applyStrategy(task, task.getStrategy());
        }
        // 如果field_mappings更新了，重新提取primaryKeys
        if (task.getFieldMapping() != null) {
            extractPrimaryKeys(task);
        }
        taskMapper.update(task);
        return Response.success(taskToMap(taskMapper.getById(id), Collections.emptyMap(), Collections.emptyMap(), null));
    }

    @Override
    public Response delete(String id) {
        SyncTask existing = taskMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("任务不存在");
        }
        // 任务正在运行时需先停止
        if ("running".equals(existing.getRunStatus())) {
            execService.stopTask(id);
            taskMapper.updateRunStatus(id, "stopped", new Date());
        }
        taskMapper.deleteLogic(id, new Date());
        return Response.success("删除成功");
    }

    @Override
    public Response detail(String id) {
        SyncTask task = taskMapper.getById(id);
        if (task == null) {
            return Response.configNotFound("任务不存在");
        }
        return Response.success(taskToMap(task, Collections.emptyMap(), Collections.emptyMap(), null));
    }

    @Override
    public Response updateMarker(String id, String markerValue) {
        SyncTask task = taskMapper.getById(id);
        if (task == null) return Response.configNotFound("任务不存在");
        taskMapper.updateMarker(id, markerValue, new Date());
        return Response.success("标记值已更新");
    }

    @Override
    public Response start(String id) {
        SyncTask existing = taskMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("任务不存在");
        }
        if ("running".equals(existing.getRunStatus())) {
            return Response.error("任务已在运行中");
        }
        taskMapper.updateRunStatus(id, "running", new Date());
        // 异步执行同步任务
        try {
            execService.execTask(id);
        } catch (Exception e) {
            log.error("启动任务[{}]执行异常: {}", id, e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("message", "任务已启动");
        result.put("status", "running");
        return Response.success(result);
    }

    @Override
    public Response pause(String id) {
        SyncTask existing = taskMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("任务不存在");
        }
        if ("paused".equals(existing.getRunStatus()) || "stopped".equals(existing.getRunStatus())) {
            return Response.error("任务已暂停或已停止");
        }
        // 停止正在运行的同步任务
        execService.stopTask(id);
        taskMapper.updateRunStatus(id, "paused", new Date());
        Map<String, Object> result = new HashMap<>();
        result.put("message", "任务已暂停");
        result.put("status", "paused");
        return Response.success(result);
    }

    @Override
    public Response stop(String id) {
        SyncTask existing = taskMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("任务不存在");
        }
        // 如果正在运行, 先中断执行
        if ("running".equals(existing.getRunStatus())) {
            execService.stopTask(id);
        }
        // 清除断点: stop后重启从头开始, pause保留断点才能续传
        taskMapper.clearCheckpoint(id);
        taskMapper.updateRunStatus(id, "stopped", new Date());
        Map<String, Object> result = new HashMap<>();
        result.put("message", "任务已停止");
        result.put("status", "stopped");
        return Response.success(result);
    }

    @Override
    public Response deploy(SyncConfigDeployDto dto) {
        // 1. 保存配置: 每个mapping创建/更新一个SyncTask
        List<String> affectedTasks = new ArrayList<>();
        for (SyncConfigDeployDto.MappingItem item : dto.getMappings()) {
            SyncTask task = new SyncTask();
            if (item.getId() != null && !item.getId().trim().isEmpty()) {
                // 更新已有任务
                task = taskMapper.getById(item.getId());
                if (task == null) {
                    continue;
                }
                task.setSourceConnId(dto.getSourceDbId());
                task.setTargetConnId(dto.getTargetDbId());
                task.setSourceTable(item.getSrcTable());
                task.setSourceSql(item.getSrcSql());
                task.setTargetTable(item.getTgtTable());
                task.setStrategy(item.getStrategy());
                task.setSourceWhere(item.getFilter());
                task.setUpdateTime(new Date());
                // 根据strategy设置syncMode和markerField
                applyStrategy(task, item.getStrategy());
                taskMapper.update(task);
                affectedTasks.add(task.getId());
            } else {
                // 新增任务
                task.setId(UUID.randomUUID().toString().replace("-", ""));
                task.setName(item.getSrcTable() + " → " + item.getTgtTable());
                task.setSourceConnId(dto.getSourceDbId());
                task.setTargetConnId(dto.getTargetDbId());
                task.setSourceTable(item.getSrcTable());
                task.setSourceSql(item.getSrcSql());
                task.setTargetTable(item.getTgtTable());
                task.setStrategy(item.getStrategy());
                task.setSourceWhere(item.getFilter());
                task.setStatus(SyncStatus.RUNNING.getCode());
                task.setRunStatus("stopped");
                task.setTotalRowsSynced(0L);
                task.setBatchSize(1000);
                Date now = new Date();
                task.setCreateTime(now);
                task.setUpdateTime(now);
                applyStrategy(task, item.getStrategy());
                taskMapper.insert(task);
                affectedTasks.add(task.getId());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deploy_result", "success");
        result.put("affected_tasks", affectedTasks);
        result.put("deployed_at", new Date());
        return Response.success(result);
    }

    @Override
    public Response getConfig() {
        List<SyncTask> tasks = taskMapper.listAll();
        // 聚合为配置格式
        if (tasks.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("source_db_id", null);
            result.put("target_db_id", null);
            result.put("mappings", new ArrayList<>());
            result.put("saved_at", null);
            return Response.success(result);
        }

        // 取第一个任务的source/target作为默认
        SyncTask first = tasks.get(0);
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (SyncTask task : tasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", task.getId());
            m.put("src_table", task.getSourceTable());
            m.put("src_sql", task.getSourceSql());
            m.put("tgt_table", task.getTargetTable());
            m.put("strategy", task.getStrategy());
            m.put("filter", task.getSourceWhere());
            m.put("enabled", SyncStatus.RUNNING.matches(task.getStatus()));
            m.put("created_at", task.getCreateTime());
            mappings.add(m);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("source_db_id", first.getSourceConnId());
        result.put("target_db_id", first.getTargetConnId());
        result.put("mappings", mappings);
        result.put("saved_at", first.getUpdateTime());
        return Response.success(result);
    }

    /**
     * 将SyncTask转换为API_DOC格式Map，使用预查询的关联信息避免N+1
     */
    private Map<String, Object> taskToMap(SyncTask task,
                                          Map<String, SyncDbConn> connMap,
                                          Map<String, Schedule> scheduleMap,
                                          TaskLogStatsDto logStats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("source_db_id", task.getSourceConnId());
        m.put("target_db_id", task.getTargetConnId());

        // 冗余显示源库/目标库名称（从预查询的map中取）
        String sourceDbName = null, sourceDbType = null;
        String targetDbName = null, targetDbType = null;
        if (task.getSourceConnId() != null) {
            SyncDbConn sourceConn = connMap.get(task.getSourceConnId());
            if (sourceConn == null) {
                // 缓存未命中时单独查询
                try {
                    sourceConn = connMapper.getById(task.getSourceConnId());
                } catch (Exception ignored) {}
            }
            if (sourceConn != null) {
                sourceDbName = sourceConn.getName();
                sourceDbType = sourceConn.getDbType();
            }
        }
        if (task.getTargetConnId() != null) {
            SyncDbConn targetConn = connMap.get(task.getTargetConnId());
            if (targetConn == null) {
                try {
                    targetConn = connMapper.getById(task.getTargetConnId());
                } catch (Exception ignored) {}
            }
            if (targetConn != null) {
                targetDbName = targetConn.getName();
                targetDbType = targetConn.getDbType();
            }
        }
        m.put("source_db_name", sourceDbName);
        m.put("source_db_type", sourceDbType);
        m.put("target_db_name", targetDbName);
        m.put("target_db_type", targetDbType);
        m.put("src_table", task.getSourceTable());
        m.put("src_sql", task.getSourceSql());
        m.put("tgt_table", task.getTargetTable());
        m.put("strategy", task.getStrategy());
        m.put("task_group", task.getTaskGroup());
        m.put("schedule_id", task.getScheduleId());

        // 冗余显示调度名称（从预查询的map中取）
        String scheduleName = null;
        if (task.getScheduleId() != null) {
            Schedule schedule = scheduleMap.get(task.getScheduleId());
            if (schedule == null) {
                try {
                    schedule = scheduleMapper.getById(task.getScheduleId());
                } catch (Exception ignored) {}
            }
            if (schedule != null) {
                scheduleName = schedule.getName();
            }
        }
        m.put("schedule_name", scheduleName);
        m.put("increment_field", task.getMarkerField());
        m.put("last_marker_value", task.getLastMarkerValue());
        m.put("field_mappings", task.getFieldMapping());
        m.put("primary_keys", task.getPrimaryKeys());
        m.put("status", task.getRunStatus());
        m.put("last_run_time", task.getLastRunTime());
        m.put("next_run_time", task.getNextRunTime());
        m.put("total_rows_synced", task.getTotalRowsSynced());
        m.put("created_at", task.getCreateTime());
        m.put("name", task.getName());
        m.put("remark", task.getRemark());
        m.put("no_update", task.getNoUpdate());

        // 日志统计：优先使用批量查询结果，否则回退单任务查询
        if (logStats != null) {
            m.put("sync_count", logStats.getSyncCount() != null ? logStats.getSyncCount() : 0);
            m.put("success_count", logStats.getSuccessCount() != null ? logStats.getSuccessCount() : 0);
            m.put("fail_count", logStats.getFailCount() != null ? logStats.getFailCount() : 0);
            m.put("last_sync_time", logStats.getLastSyncTime());
        } else {
            try {
                List<SyncTaskLog> logs = logMapper.listByTaskId(task.getId());
                int syncCount = logs.size();
                int successCount = 0;
                int failCount = 0;
                Date lastSyncTime = null;
                for (SyncTaskLog logEntity : logs) {
                    if (SyncStatus.SUCCESS.matches(logEntity.getStatus())) {
                        successCount++;
                    } else if (SyncStatus.FAILED.matches(logEntity.getStatus())) {
                        failCount++;
                    }
                    if (logEntity.getStartTime() != null &&
                            (lastSyncTime == null || logEntity.getStartTime().after(lastSyncTime))) {
                        lastSyncTime = logEntity.getStartTime();
                    }
                }
                m.put("sync_count", syncCount);
                m.put("success_count", successCount);
                m.put("fail_count", failCount);
                m.put("last_sync_time", lastSyncTime);
            } catch (Exception e) {
                log.warn("查询任务[{}]日志聚合统计失败: {}", task.getId(), e.getMessage());
                m.put("sync_count", 0);
                m.put("success_count", 0);
                m.put("fail_count", 0);
                m.put("last_sync_time", null);
            }
        }

        return m;
    }

    /**
     * 根据strategy设置syncMode和markerField
     */
    private void applyStrategy(SyncTask task, String strategy) {
        if (strategy == null) {
            strategy = "incremental";
        }
        switch (strategy) {
            case "full":
                task.setSyncMode(SyncMode.CLEAN_INSERT.getCode());
                task.setMarkerField(null);
                task.setLastMarkerValue(null);
                break;
            case "cdc":
                task.setSyncMode(SyncMode.MERGE.getCode());
                break;
            case "incremental":
            default:
                task.setSyncMode(SyncMode.MERGE.getCode());
                break;
        }
    }

    /**
     * 从field_mappings JSON中提取isPk=true的字段名，设置到primaryKeys
     * JSON格式: [{"srcField":"LSH","tgtField":"LSH","isPk":true}, ...]
     */
    private void extractPrimaryKeys(SyncTask task) {
        String fieldMapping = task.getFieldMapping();
        if (fieldMapping == null || fieldMapping.trim().isEmpty()) {
            return;
        }
        try {
            JsonNode arr = objectMapper.readTree(fieldMapping);
            List<String> pkFields = new ArrayList<>();
            for (JsonNode item : arr) {
                if (item.has("isPk") && item.get("isPk").asBoolean(false)) {
                    String srcField = item.has("srcField") ? item.get("srcField").asText() : null;
                    if (srcField != null) {
                        pkFields.add(srcField);
                    }
                }
            }
            if (!pkFields.isEmpty()) {
                task.setPrimaryKeys(String.join(",", pkFields));
            }
        } catch (Exception e) {
            log.warn("解析field_mappings提取主键失败: {}", e.getMessage());
        }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }
}
