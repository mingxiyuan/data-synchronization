package com.scxd.service.impl;

import com.scxd.config.BusinessException;
import com.scxd.config.Response;
import com.scxd.config.ResultCodeEnum;
import com.scxd.dialect.DatabaseDialect;
import com.scxd.dialect.DialectFactory;
import com.scxd.mapper.SyncDbConnMapper;
import com.scxd.mapper.SyncTaskMapper;
import com.scxd.model.dto.SqlTestRequestDto;
import com.scxd.model.entity.SyncDbConn;
import com.scxd.model.entity.SyncTask;
import com.scxd.service.PasswordEncryptService;
import com.scxd.service.SyncPreviewService;
import com.scxd.utils.DataSourceUtils;
import com.scxd.utils.JDBCUtils;
import com.scxd.utils.SqlValidator;
import com.scxd.utils.WhereClause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.*;

@Slf4j
@Service
public class SyncPreviewServiceImpl implements SyncPreviewService {

    @Autowired private SyncTaskMapper taskMapper;
    @Autowired private SyncDbConnMapper connMapper;
    @Autowired private JDBCUtils jdbcUtils;
    @Autowired private DataSourceUtils dataSourceUtils;
    @Autowired private PasswordEncryptService passwordEncryptService;

    @Override
    public Response preview(String taskId) {
        try {
            SyncTask task = taskMapper.getById(taskId);
            if (task == null) return Response.configNotFound("任务不存在");
            SyncDbConn conn = connMapper.getById(task.getSourceConnId());
            if (conn == null) return Response.configNotFound("源库连接不存在");
            DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
            String effectiveSource = resolveSource(task.getSourceTable(), task.getSourceSql());
            WhereClause where = buildWhere(task, dialect);

            try (Connection sourceConn = getConn(conn)) {
                long totalCount = jdbcUtils.queryCount(sourceConn, dialect, effectiveSource, where);
                List<Map<String, Object>> sampleData = jdbcUtils.queryOut(sourceConn, dialect,
                        effectiveSource, null, where, 0, 10);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("totalCount", totalCount);
                r.put("sampleData", sampleData);
                return Response.success(r);
            }
        } catch (Exception e) {
            log.error("预览失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response sqlBuild(String dbId, SqlTestRequestDto req) {
        try {
            SyncDbConn conn = connMapper.getById(dbId);
            if (conn == null) return Response.configNotFound("数据库连接不存在");
            DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
            String whereSql = buildWhereSql(req, dialect);
            String fullSql = jdbcUtils.buildSelectSql(req.getSourceSql(), dialect, whereSql);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("builtSql", fullSql);
            return Response.success(result);
        } catch (Exception e) {
            log.error("构建SQL失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response sqlTest(String dbId, SqlTestRequestDto req) {
        try {
            SyncDbConn conn = connMapper.getById(dbId);
            if (conn == null) return Response.configNotFound("数据库连接不存在");
            DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
            String whereSql = buildWhereSql(req, dialect);
            String effectiveSource = resolveSource(null, req.getSourceSql());
            WhereClause where = whereSql != null ? new WhereClause(whereSql, new ArrayList<>()) : null;
            int limit = req.getLimit() != null && req.getLimit() > 0 ? req.getLimit() : 1;

            long start = System.currentTimeMillis();
            try (Connection sourceConn = getConn(conn)) {
                long totalCount = jdbcUtils.queryCount(sourceConn, dialect, effectiveSource, where);
                List<Map<String, Object>> rows = jdbcUtils.queryOut(sourceConn, dialect,
                        effectiveSource, null, where, 0, limit);
                List<String> columns = new ArrayList<>();
                List<List<Object>> rowData = new ArrayList<>();
                if (!rows.isEmpty()) {
                    columns.addAll(rows.get(0).keySet());
                    for (Map<String, Object> row : rows) {
                        rowData.add(new ArrayList<>(row.values()));
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("columns", columns);
                result.put("rows", rowData);
                result.put("totalRows", totalCount);
                result.put("execTimeMs", System.currentTimeMillis() - start);
                return Response.success(result);
            }
        } catch (Exception e) {
            log.error("SQL测试执行失败", e);
            String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
            return Response.error(msg.length() > 500 ? msg.substring(0, 500) : msg);
        }
    }

    @Override
    public Response sourceMax(String taskId) {
        try {
            SyncTask task = taskMapper.getById(taskId);
            if (task == null) return Response.configNotFound("任务不存在");
            if (task.getMarkerField() == null || task.getMarkerField().trim().isEmpty()) {
                return Response.paramError("该任务未设置增量字段");
            }
            SyncDbConn conn = connMapper.getById(task.getSourceConnId());
            if (conn == null) return Response.configNotFound("源库连接不存在");
            DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
            String effectiveSource = resolveSource(task.getSourceTable(), task.getSourceSql());

            try (Connection sourceConn = getConn(conn)) {
                long start = System.currentTimeMillis();
                String maxVal = jdbcUtils.queryMaxMarker(sourceConn, dialect, effectiveSource,
                        task.getMarkerField(), null);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("taskId", taskId);
                r.put("sourceMaxValue", maxVal);
                r.put("execTimeMs", System.currentTimeMillis() - start);
                return Response.success(r);
            }
        } catch (Exception e) {
            log.error("查询源表最大标记值失败: taskId={}", taskId, e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response batchSourceMax(List<String> taskIds) {
        try {
            List<SyncTask> tasks;
            if (taskIds == null || taskIds.isEmpty()) {
                tasks = taskMapper.listAll();
            } else {
                tasks = new ArrayList<>();
                for (String id : taskIds) {
                    SyncTask t = taskMapper.getById(id);
                    if (t != null) tasks.add(t);
                }
            }
            Map<String, Object> results = new LinkedHashMap<>();
            for (SyncTask task : tasks) {
                if (task.getMarkerField() == null || task.getMarkerField().trim().isEmpty()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sourceMaxValue", null);
                    r.put("error", "未设置增量字段");
                    results.put(task.getId(), r);
                    continue;
                }
                try {
                    SyncDbConn conn = connMapper.getById(task.getSourceConnId());
                    if (conn == null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("sourceMaxValue", null);
                        r.put("error", "源库连接不存在");
                        results.put(task.getId(), r);
                        continue;
                    }
                    DatabaseDialect dialect = DialectFactory.getDialect(conn.getDbType());
                    String effectiveSource = resolveSource(task.getSourceTable(), task.getSourceSql());
                    try (Connection sourceConn = getConn(conn)) {
                        String maxVal = jdbcUtils.queryMaxMarker(sourceConn, dialect, effectiveSource,
                                task.getMarkerField(), null);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("sourceMaxValue", maxVal);
                        results.put(task.getId(), r);
                    }
                } catch (Exception e) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sourceMaxValue", null);
                    r.put("error", e.getMessage());
                    results.put(task.getId(), r);
                }
            }
            return Response.success(results);
        } catch (Exception e) {
            log.error("批量查询源表最大标记值失败", e);
            return Response.error(e.getMessage());
        }
    }

    // ======================== helpers ========================

    private String resolveSource(String sourceTable, String sourceSql) {
        return (sourceSql != null && !sourceSql.trim().isEmpty()) ? sourceSql : sourceTable;
    }

    private Connection getConn(SyncDbConn conn) throws Exception {
        String decryptedPassword = passwordEncryptService.aesDecrypt(conn.getPassword());
        return dataSourceUtils.getConnection(conn.getId(), conn.getDbType(),
                conn.getJdbcUrl(), conn.getUsername(), decryptedPassword, conn.getDriverClass());
    }

    private WhereClause buildWhere(SyncTask task, DatabaseDialect dialect) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (task.getMarkerField() != null && !task.getMarkerField().trim().isEmpty()
                && task.getLastMarkerValue() != null && !task.getLastMarkerValue().trim().isEmpty()) {
            String dbType = dialect != null ? dialect.getDbType() : "oracle";
            String mf = WhereClause.validateIdentifier(task.getMarkerField().trim(), "markerField");
            String mfQ = dialect != null ? dialect.quote(mf) : mf;
            if ("mysql".equals(dbType) || "postgresql".equals(dbType)) {
                where.append(mfQ).append(" > ?");
            } else {
                where.append(mfQ).append(" > TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS.FF6')");
            }
            params.add(task.getLastMarkerValue());
        }
        if (task.getSourceWhere() != null && !task.getSourceWhere().trim().isEmpty()) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(").append(task.getSourceWhere().trim()).append(")");
        }
        return where.length() > 0 ? new WhereClause(where.toString(), params) : null;
    }

    private String buildWhereSql(SqlTestRequestDto req, DatabaseDialect dialect) {
        StringBuilder where = new StringBuilder();
        String dbType = dialect != null ? dialect.getDbType() : "oracle";
        if (req.getMarkerField() != null && !req.getMarkerField().trim().isEmpty()
                && req.getLastMarkerValue() != null && !req.getLastMarkerValue().trim().isEmpty()) {
            String mf = WhereClause.validateIdentifier(req.getMarkerField().trim(), "markerField");
            String mfQ = dialect != null ? dialect.quote(mf) : mf;
            if ("mysql".equals(dbType) || "postgresql".equals(dbType)) {
                where.append(mfQ).append(" > '").append(req.getLastMarkerValue()).append("'");
            } else {
                where.append(mfQ).append(" > TO_TIMESTAMP('").append(req.getLastMarkerValue()).append("', 'YYYY-MM-DD HH24:MI:SS.FF6')");
            }
        }
        if (req.getSourceWhere() != null && !req.getSourceWhere().trim().isEmpty()) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(").append(req.getSourceWhere().trim()).append(")");
        }
        return where.length() > 0 ? where.toString() : null;
    }
}
