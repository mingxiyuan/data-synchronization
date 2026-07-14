package com.trans.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.trans.config.Response;
import com.trans.model.dto.*;
import com.trans.model.entity.SyncDbConn;
import com.trans.model.entity.SyncTask;
import com.trans.service.DashboardService;
import com.trans.service.PasswordEncryptService;
import com.trans.service.SyncConnService;
import com.trans.service.SyncExecService;
import com.trans.service.SyncLogService;
import com.trans.service.SyncPreviewService;
import com.trans.service.SyncTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * 数据同步管理系统 - 统一API入口
 * 基础路径: /api/v1
 */
@RestController
@RequestMapping("/api/v1")
public class SyncController {

    @Autowired
    private SyncConnService connService;

    @Autowired
    private SyncTaskService taskService;

    @Autowired
    private SyncExecService execService;

    @Autowired
    private SyncPreviewService previewService;

    @Autowired
    private SyncLogService logService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private PasswordEncryptService passwordEncryptService;

    @Autowired
    private ObjectMapper objectMapper;

    // ======================== 二、数据库连接管理 ========================

    /**
     * 2.1 获取所有数据库连接列表
     * GET /api/v1/databases
     */
    @GetMapping("/databases")
    public Response databaseList() {
        return connService.list();
    }

    /**
     * 2.2 新增数据库连接
     * POST /api/v1/databases
     * 前端字段: type/user/database, 实体字段: dbType/username/databaseName
     */
    @PostMapping("/databases")
    public Response databaseAdd(@RequestBody Map<String, Object> body) {
        SyncDbConn conn = mapToSyncDbConn(body);
        conn.setPassword(passwordEncryptService.resolvePassword(
                conn.getPassword(), getBool(body, "encrypted"), getStr(body, "keyId")));
        return connService.add(conn);
    }

    /**
     * 2.2 编辑数据库连接
     * PUT /api/v1/databases/{id}
     */
    @PutMapping("/databases/{id}")
    public Response databaseUpdate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        SyncDbConn conn = mapToSyncDbConn(body);
        conn.setPassword(passwordEncryptService.resolvePassword(
                conn.getPassword(), getBool(body, "encrypted"), getStr(body, "keyId")));
        return connService.update(id, conn);
    }

    /**
     * 前端JSON字段 -> SyncDbConn实体映射
     * API_DOC字段: type/user/database -> 实体: dbType/username/databaseName
     */
    private SyncDbConn mapToSyncDbConn(Map<String, Object> body) {
        SyncDbConn conn = new SyncDbConn();
        conn.setName(getStr(body, "name"));
        // type <-> dbType
        conn.setDbType(getStr(body, "type"));
        if (conn.getDbType() == null) conn.setDbType(getStr(body, "dbType"));
        conn.setHost(getStr(body, "host"));
        conn.setPort(getInt(body, "port"));
        // database <-> databaseName
        conn.setDatabaseName(getStr(body, "database"));
        if (conn.getDatabaseName() == null) conn.setDatabaseName(getStr(body, "databaseName"));
        // user <-> username
        conn.setUsername(getStr(body, "user"));
        if (conn.getUsername() == null) conn.setUsername(getStr(body, "username"));
        conn.setPassword(getStr(body, "password"));
        conn.setRemark(getStr(body, "remark"));
        // url字段优先于jdbcUrl
        conn.setJdbcUrl(getStr(body, "url"));
        if (conn.getJdbcUrl() == null) conn.setJdbcUrl(getStr(body, "jdbcUrl"));
        conn.setDriverClass(getStr(body, "driverClass"));
        conn.setSchema(getStr(body, "schema"));
        // 确保status不为null, 避免Oracle JdbcType OTHER错误
        Integer status = getInt(body, "status");
        conn.setStatus(status != null ? status : 1);
        return conn;
    }

    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Boolean getBool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    /**
     * 2.3 删除数据库连接
     * DELETE /api/v1/databases/{id}
     */
    @DeleteMapping("/databases/{id}")
    public Response databaseDelete(@PathVariable String id) {
        return connService.delete(id);
    }

    /**
     * 2.4 测试数据库连通性(用参数直接测试)
     * POST /api/v1/databases/test
     */
    @PostMapping("/databases/test")
    public Response databaseTest(@RequestBody TestConnRequestDto dto) {
        dto.setPassword(passwordEncryptService.resolvePassword(
                dto.getPassword(), dto.getEncrypted(), dto.getKeyId()));
        return connService.testConnection(dto);
    }

    /**
     * 测试已保存连接的连通性
     * POST /api/v1/databases/{id}/test
     */
    @PostMapping("/databases/{id}/test")
    public Response databaseTestById(@PathVariable String id) {
        return connService.testConnectionById(id);
    }

    // ======================== 三、同步配置管理 ========================

    /**
     * 3.1 获取同步配置
     * GET /api/v1/sync/config
     */
    @GetMapping("/sync/config")
    public Response syncConfig() {
        return taskService.getConfig();
    }

    /**
     * 3.2 保存/部署同步配置
     * POST /api/v1/sync/config/deploy
     */
    @PostMapping("/sync/config/deploy")
    public Response syncConfigDeploy(@RequestBody SyncConfigDeployDto dto) {
        return taskService.deploy(dto);
    }

    /**
     * 3.3 获取可用的源表列表(用于映射弹窗下拉)
     * GET /api/v1/databases/{dbId}/tables?role=source
     */
    @GetMapping("/databases/{dbId}/tables")
    public Response databaseTables(@PathVariable String dbId,
                                   @RequestParam(required = false, defaultValue = "source") String role) {
        return connService.getTableList(dbId, role);
    }

    /**
     * 获取指定表的字段列表
     * GET /api/v1/databases/{dbId}/tables/{tableName}/columns
     */
    @GetMapping("/databases/{dbId}/tables/{tableName}/columns")
    public Response databaseColumns(@PathVariable String dbId, @PathVariable String tableName) {
        return connService.getColumns(dbId, tableName);
    }

    /**
     * 获取指定表的主键
     * GET /api/v1/databases/{dbId}/tables/{tableName}/primaryKeys
     */
    @GetMapping("/databases/{dbId}/tables/{tableName}/primaryKeys")
    public Response databasePrimaryKeys(@PathVariable String dbId, @PathVariable String tableName) {
        return connService.getPrimaryKeys(dbId, tableName);
    }

    /**
     * 根据SQL获取列名和数据类型
     * POST /api/v1/databases/{id}/sql-columns
     * 请求体: { "sql": "SELECT a.id, b.name FROM ..." }
     * 返回: { "code": 0, "data": { "columns": [{ "name": "id", "type": "NUMBER" }, ...] } }
     */
    @PostMapping("/databases/{id}/sql-columns")
    public Response databaseSqlColumns(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String sql = getStr(body, "sql");
        return connService.getSqlColumns(id, sql);
    }

    /**
     * 构建完整预览SQL(含增量条件+sourceWhere)
     * POST /api/v1/databases/{id}/sql-build
     */
    @PostMapping("/databases/{id}/sql-build")
    public Response databaseSqlBuild(@PathVariable String id, @RequestBody SqlTestRequestDto req) {
        return previewService.sqlBuild(id, req);
    }

    @PostMapping("/databases/{id}/sql-test")
    public Response databaseSqlTest(@PathVariable String id, @RequestBody SqlTestRequestDto req) {
        return previewService.sqlTest(id, req);
    }

    // ======================== 同步任务CRUD ========================

    /**
     * 获取同步任务列表
     * GET /api/v1/sync/tasks
     */
    @GetMapping("/sync/tasks")
    public Response taskList() {
        return taskService.list();
    }

    /**
     * 新增同步任务
     * POST /api/v1/sync/tasks
     * 前端字段: source_db_id/target_db_id/src_table/tgt_table/increment_field/field_mappings/schedule_id/primary_keys
     * 实体字段: sourceConnId/targetConnId/sourceTable/targetTable/markerField/fieldMapping/scheduleId/primaryKeys
     */
    @PostMapping("/sync/tasks")
    public Response taskAdd(@RequestBody Map<String, Object> body) {
        SyncTask task = mapToSyncTask(body);
        return taskService.add(task);
    }

    /**
     * 编辑同步任务
     * PUT /api/v1/sync/tasks/{id}
     */
    @PutMapping("/sync/tasks/{id}")
    public Response taskUpdate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        SyncTask task = mapToSyncTask(body);
        return taskService.update(id, task);
    }

    /**
     * 前端JSON字段 -> SyncTask实体映射
     */
    private SyncTask mapToSyncTask(Map<String, Object> body) {
        SyncTask task = new SyncTask();
        task.setName(getStr(body, "name"));
        // source_db_id <-> sourceConnId
        task.setSourceConnId(getStr(body, "source_db_id"));
        if (task.getSourceConnId() == null) task.setSourceConnId(getStr(body, "sourceConnId"));
        // target_db_id <-> targetConnId
        task.setTargetConnId(getStr(body, "target_db_id"));
        if (task.getTargetConnId() == null) task.setTargetConnId(getStr(body, "targetConnId"));
        // src_table <-> sourceTable
        task.setSourceTable(getStr(body, "src_table"));
        if (task.getSourceTable() == null) task.setSourceTable(getStr(body, "sourceTable"));
        // src_sql <-> sourceSql (自定义SQL, 非表名时使用)
        task.setSourceSql(getStr(body, "src_sql"));
        if (task.getSourceSql() == null) task.setSourceSql(getStr(body, "sourceSql"));
        // tgt_table <-> targetTable
        task.setTargetTable(getStr(body, "tgt_table"));
        if (task.getTargetTable() == null) task.setTargetTable(getStr(body, "targetTable"));
        task.setStrategy(getStr(body, "strategy"));
        task.setFetchMode(getStr(body, "fetch_mode"));
        if (task.getFetchMode() == null) task.setFetchMode(getStr(body, "fetchMode"));
        task.setTaskGroup(getStr(body, "task_group"));
        if (task.getTaskGroup() == null) task.setTaskGroup(getStr(body, "taskGroup"));
        task.setScheduleId(getStr(body, "schedule_id"));
        if (task.getScheduleId() == null) task.setScheduleId(getStr(body, "scheduleId"));
        // increment_field <-> markerField
        task.setMarkerField(getStr(body, "increment_field"));
        if (task.getMarkerField() == null) task.setMarkerField(getStr(body, "markerField"));
        // field_mappings: 前端传JSON数组, 实体存String
        Object fm = body.get("field_mappings");
        if (fm != null) {
            try {
                task.setFieldMapping(fm instanceof String ? (String) fm : objectMapper.writeValueAsString(fm));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            task.setFieldMapping(getStr(body, "fieldMapping"));
        }
        task.setCustomPk(getStr(body, "customPk"));
        task.setPrimaryKeys(getStr(body, "primary_keys"));
        if (task.getPrimaryKeys() == null) task.setPrimaryKeys(getStr(body, "primaryKeys"));
        task.setSourceWhere(getStr(body, "sourceWhere"));
        task.setNoUpdate(getBool(body, "no_update"));
        task.setRemark(getStr(body, "remark"));
        // runStatus: 前端可能传 status 表示运行状态
        task.setRunStatus(getStr(body, "status"));
        if (task.getRunStatus() == null) task.setRunStatus(getStr(body, "runStatus"));
        return task;
    }

    /**
     * 删除同步任务
     * DELETE /api/v1/sync/tasks/{id}
     */
    @DeleteMapping("/sync/tasks/{id}")
    public Response taskDelete(@PathVariable String id) {
        return taskService.delete(id);
    }

    /**
     * 获取同步任务详情
     * GET /api/v1/sync/tasks/{id}
     */
    @GetMapping("/sync/tasks/{id}")
    public Response taskDetail(@PathVariable String id) {
        return taskService.detail(id);
    }

    /**
     * 启动同步任务
     * POST /api/v1/sync/tasks/{id}/start
     */
    @PostMapping("/sync/tasks/{id}/start")
    public Response taskStart(@PathVariable String id) {
        return taskService.start(id);
    }

    /**
     * 暂停同步任务
     * POST /api/v1/sync/tasks/{id}/pause
     */
    @PostMapping("/sync/tasks/{id}/pause")
    public Response taskPause(@PathVariable String id) {
        return taskService.pause(id);
    }

    /**
     * 停止同步任务(完全终止, 不同于暂停)
     * POST /api/v1/sync/tasks/{id}/stop
     */
    @PostMapping("/sync/tasks/{id}/stop")
    public Response taskStop(@PathVariable String id) {
        return taskService.stop(id);
    }

    /**
     * 获取任务同步进度
     * GET /api/v1/sync/tasks/{id}/progress
     */
    @GetMapping("/sync/tasks/{id}/progress")
    public Response taskProgress(@PathVariable String id) {
        return execService.getProgress(id);
    }

    /**
     * 查询单个任务源表最大标记值
     * POST /api/v1/sync/tasks/{id}/source-max
     */
    @PostMapping("/sync/tasks/{id}/source-max")
    public Response taskSourceMax(@PathVariable String id) {
        return previewService.sourceMax(id);
    }

    @PostMapping("/sync/tasks/source-max")
    public Response batchSourceMax(@RequestBody(required = false) Map<String, Object> body) {
        List<String> taskIds = null;
        if (body != null && body.get("taskIds") instanceof List) {
            taskIds = (List<String>) body.get("taskIds");
        }
        return previewService.batchSourceMax(taskIds);
    }

    /**
     * 修改任务的last_marker_value(用于数据补录)
     * PUT /api/v1/sync/tasks/{id}/marker
     */
    @PutMapping("/sync/tasks/{id}/marker")
    public Response taskUpdateMarker(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String markerValue = body != null ? (String) body.get("markerValue") : null;
        if (markerValue == null || markerValue.trim().isEmpty()) {
            return Response.paramError("markerValue不能为空");
        }
        return taskService.updateMarker(id, markerValue);
    }

    // ======================== 四、同步执行 ========================

    /**
     * 执行同步任务
     * POST /api/v1/sync/exec/{taskId}
     */
    @PostMapping("/sync/exec/{taskId}")
    public Response execTask(@PathVariable String taskId) {
        return execService.execTask(taskId);
    }

    /**
     * 单独出库查询
     * POST /api/v1/sync/queryOut
     */
    @PostMapping("/sync/queryOut")
    public Response queryOut(@RequestBody QueryOutRequestDto request) {
        return execService.queryOut(request);
    }

    /**
     * 单独入库
     * POST /api/v1/sync/mergeInto
     */
    @PostMapping("/sync/mergeInto")
    public Response mergeInto(@RequestBody SyncMergeDto request) {
        return execService.mergeInto(request);
    }

    /**
     * 预览同步数据(不实际执行)
     * GET /api/v1/sync/preview/{taskId}
     */
    @GetMapping("/sync/preview/{taskId}")
    public Response preview(@PathVariable String taskId) {
        return execService.preview(taskId);
    }

    // ======================== 四、看板 / 实时监控 ========================

    /**
     * 4.1 看板概览数据
     * GET /api/v1/dashboard/overview
     */
    @GetMapping("/dashboard/overview")
    public Response dashboardOverview() {
        return dashboardService.overview();
    }

    /**
     * 4.2 性能曲线数据(最近N分钟)
     * GET /api/v1/dashboard/metrics/perf?minutes=30&interval_sec=300
     */
    @GetMapping("/dashboard/metrics/perf")
    public Response dashboardPerfMetrics(@RequestParam(required = false, defaultValue = "30") Integer minutes,
                                         @RequestParam(value = "interval_sec", required = false, defaultValue = "300") Integer intervalSec) {
        return dashboardService.perfMetrics(minutes, intervalSec);
    }

    /**
     * 4.3 同步对象分布数据
     * GET /api/v1/dashboard/metrics/sync-distribution
     */
    @GetMapping("/dashboard/metrics/sync-distribution")
    public Response dashboardSyncDistribution() {
        return dashboardService.syncDistribution();
    }

    /**
     * 4.4 运行时实时指标(线程池/连接池/今日统计)
     * GET /api/v1/dashboard/metrics/runtime
     */
    @GetMapping("/dashboard/metrics/runtime")
    public Response dashboardRuntimeMetrics() {
        return dashboardService.runtimeMetrics();
    }

    // ======================== 五、任务日志 ========================

    /**
     * 5.1 任务历史列表(分页)
     * GET /api/v1/logs/tasks
     */
    @GetMapping("/logs/tasks")
    public Response logList(TaskLogQueryDto query) {
        return logService.list(query);
    }

    /**
     * 5.2 任务详情
     * GET /api/v1/logs/tasks/{taskId}/detail
     */
    @GetMapping("/logs/tasks/{taskId}/detail")
    public Response logDetail(@PathVariable String taskId) {
        return logService.detail(taskId);
    }

    /**
     * 5.3 今日汇总统计
     * GET /api/v1/logs/today-summary
     */
    @GetMapping("/logs/today-summary")
    public Response todaySummary() {
        return logService.todaySummary();
    }
}
