package com.scxd.service.impl;

import com.scxd.config.Response;
import com.scxd.dialect.DialectFactory;
import com.scxd.mapper.SyncDbConnMapper;
import com.scxd.model.dto.TestConnRequestDto;
import com.scxd.model.dto.TestConnResultDto;
import com.scxd.model.entity.SyncDbConn;
import com.scxd.service.SyncConnService;
import com.scxd.service.PasswordEncryptService;
import com.scxd.utils.DataSourceUtils;
import com.scxd.utils.JDBCUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

@Slf4j
@Service
public class SyncConnServiceImpl implements SyncConnService {

    @Autowired
    private SyncDbConnMapper connMapper;

    @Autowired
    private DataSourceUtils dataSourceUtils;

    @Autowired
    private JDBCUtils jdbcUtils;

    @Autowired
    private PasswordEncryptService passwordEncryptService;

    @Override
    public Response list() {
        try {
            List<SyncDbConn> list = connMapper.listAll();
            // 构造对齐API_DOC的返回格式
            List<Map<String, Object>> connList = new ArrayList<>();
            for (SyncDbConn c : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.getId());
                item.put("name", c.getName());
                item.put("type", c.getDbType());
                item.put("host", c.getHost());
                item.put("port", c.getPort());
                item.put("database", c.getDatabaseName());
                item.put("user", c.getUsername());
                item.put("password_masked", true);
                item.put("remark", c.getRemark());
                item.put("url", c.getJdbcUrl());
                item.put("connected", false); // 列表不做实时检测
                item.put("created_at", c.getCreateTime());
                item.put("updated_at", c.getUpdateTime());
                connList.add(item);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("list", connList);
            result.put("total", connList.size());
            return Response.success(result);
        } catch (Exception e) {
            log.error("查询数据库连接列表失败", e);
            return Response.error("数据库连接不可用: " + e.getMessage());
        }
    }

    @Override
    public Response add(SyncDbConn conn) {
        fillConnFields(conn);
        conn.setId(UUID.randomUUID().toString().replace("-", ""));
        conn.setStatus(1);
        Date now = new Date();
        conn.setCreateTime(now);
        conn.setUpdateTime(now);
        // AES加密密码后存库
        conn.setPassword(passwordEncryptService.aesEncrypt(conn.getPassword()));
        connMapper.insert(conn);
        // 返回对齐API_DOC格式
        return Response.success(toApiResponse(conn));
    }

    @Override
    public Response update(String id, SyncDbConn conn) {
        SyncDbConn existing = connMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("连接不存在");
        }
        conn.setId(id);
        fillConnFields(conn);
        conn.setUpdateTime(new Date());
        // 如果密码为空则保留原密码, 否则AES加密后存库
        if (conn.getPassword() == null || conn.getPassword().trim().isEmpty()) {
            conn.setPassword(existing.getPassword());
        } else {
            // 密码已是RSA解密后的明文, 需要AES加密存库
            conn.setPassword(passwordEncryptService.aesEncrypt(conn.getPassword()));
        }
        connMapper.update(conn);
        // 更新后移除旧数据源缓存
        dataSourceUtils.removeDataSource(id);
        return Response.success(toApiResponse(conn));
    }

    @Override
    public Response delete(String id) {
        SyncDbConn existing = connMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("连接不存在");
        }
        connMapper.deleteLogic(id, new Date());
        dataSourceUtils.removeDataSource(id);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted_id", id);
        return Response.success(result);
    }

    @Override
    public Response testConnection(TestConnRequestDto dto) {
        TestConnResultDto result = new TestConnResultDto();
        String dbType = dto.getType();
        // 优先使用前端传入的完整JDBC URL, 否则根据host/port/database拼接
        String jdbcUrl = dto.getUrl();
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            if (dbType == null || dbType.trim().isEmpty()) {
                return Response.paramError("数据库类型和URL不能同时为空");
            }
            jdbcUrl = buildJdbcUrl(dbType, dto.getHost(), dto.getPort(), dto.getDatabase());
        }
        // 如果有完整URL, 从URL推断dbType(更准确)
        try {
            String inferredDbType = DialectFactory.inferDbType(jdbcUrl);
            if (inferredDbType != null) {
                dbType = inferredDbType;
            }
        } catch (IllegalArgumentException ignored) {
            // URL格式无法推断时, 使用前端传入的type
        }
        String driverClass = DataSourceUtils.inferDriverClass(dbType);

        log.info("测试连接: dbType={}, jdbcUrl={}, user={}", dbType, jdbcUrl, dto.getUser());

        // 使用DriverManager直连测试, 不创建连接池, 避免后台重试锁定Oracle账号
        long start = System.currentTimeMillis();
        try (Connection connection = dataSourceUtils.testConnectionDirect(dbType, jdbcUrl,
                dto.getUser(), dto.getPassword(), driverClass)) {
            long latency = System.currentTimeMillis() - start;
            if (connection != null && !connection.isClosed()) {
                result.setConnected(true);
                result.setLatencyMs(latency);
                DatabaseMetaData meta = connection.getMetaData();
                result.setVersion(meta.getDatabaseProductVersion());
                result.setError(null);
                return Response.success(result);
            }
            result.setConnected(false);
            result.setError("连接失败");
            return Response.success(result);
        } catch (Exception e) {
            log.error("测试连接失败", e);
            result.setConnected(false);
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setError(e.getMessage());
            return Response.success(result);
        }
    }

    @Override
    public Response testConnectionById(String id) {
        SyncDbConn conn = connMapper.getById(id);
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }

        TestConnResultDto result = new TestConnResultDto();
        // 使用DriverManager直连测试, 不创建连接池; 密码从数据库读出需AES解密
        long start = System.currentTimeMillis();
        try (Connection connection = dataSourceUtils.testConnectionDirect(conn.getDbType(),
                conn.getJdbcUrl(), conn.getUsername(), passwordEncryptService.aesDecrypt(conn.getPassword()), conn.getDriverClass())) {
            long latency = System.currentTimeMillis() - start;
            if (connection != null && !connection.isClosed()) {
                result.setConnected(true);
                result.setLatencyMs(latency);
                DatabaseMetaData meta = connection.getMetaData();
                result.setVersion(meta.getDatabaseProductVersion());
                result.setError(null);
                return Response.success(result);
            }
            result.setConnected(false);
            result.setError("连接失败");
            return Response.success(result);
        } catch (Exception e) {
            log.error("测试连接失败", e);
            result.setConnected(false);
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setError(e.getMessage());
            return Response.success(result);
        }
    }

    @Override
    public Response getTableList(String dbId, String role) {
        SyncDbConn conn = connMapper.getById(dbId);
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        try (Connection connection = getConn(conn)) {
            String schema = resolveSchema(conn);
            List<String> tables = jdbcUtils.getTableList(connection, schema);
            // 构造返回格式对齐API_DOC
            List<Map<String, Object>> tableList = new ArrayList<>();
            for (String tableName : tables) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", tableName);
                item.put("rows_estimate", 0);
                item.put("comment", "");
                tableList.add(item);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("tables", tableList);
            return Response.success(result);
        } catch (Exception e) {
            log.error("获取表列表失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response getColumns(String connId, String tableName) {
        SyncDbConn conn = connMapper.getById(connId);
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        try (Connection connection = getConn(conn)) {
            String schema = resolveSchema(conn);
            return Response.success(jdbcUtils.getColumns(connection, tableName, DialectFactory.getDialect(conn.getDbType()), schema));
        } catch (Exception e) {
            log.error("获取字段列表失败", e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response getSqlColumns(String connId, String sql) {
        SyncDbConn conn = connMapper.getById(connId);
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        if (sql == null || sql.trim().isEmpty()) {
            return Response.paramError("SQL不能为空");
        }
        String cleanSql = sql.trim();
        // 校验: 只允许SELECT/WITH开头的只读查询, 禁止多语句和危险关键字
        if (!cleanSql.matches("(?is)^(SELECT|WITH)\\s.*")) {
            return Response.paramError("SQL必须以SELECT或WITH开头(只读查询)");
        }
        if (cleanSql.contains(";")) {
            return Response.paramError("SQL不允许包含分号(禁止多语句)");
        }
        String wrappedSql = "SELECT * FROM (" + cleanSql + ") sql_columns WHERE 1=0";
        try (Connection connection = getConn(conn);
             PreparedStatement ps = connection.prepareStatement(wrappedSql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            List<Map<String, String>> columns = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                Map<String, String> col = new LinkedHashMap<>();
                col.put("name", meta.getColumnLabel(i));
                col.put("type", meta.getColumnTypeName(i));
                columns.add(col);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("columns", columns);
            return Response.success(data);
        } catch (SQLException e) {
            log.error("获取SQL列信息失败: connId={}, sql={}", connId, sql, e);
            return Response.error("SQL解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("获取SQL列信息失败: connId={}, sql={}", connId, sql, e);
            return Response.error(e.getMessage());
        }
    }

    @Override
    public Response getPrimaryKeys(String connId, String tableName) {
        SyncDbConn conn = connMapper.getById(connId);
        if (conn == null) {
            return Response.configNotFound("连接不存在");
        }
        try (Connection connection = getConn(conn)) {
            String schema = resolveSchema(conn);
            return Response.success(jdbcUtils.getPrimaryKeys(connection, tableName, DialectFactory.getDialect(conn.getDbType()), schema));
        } catch (Exception e) {
            log.error("获取主键失败", e);
            return Response.error(e.getMessage());
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 实体转API_DOC格式响应
     */
    private Map<String, Object> toApiResponse(SyncDbConn conn) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", conn.getId());
        item.put("name", conn.getName());
        item.put("type", conn.getDbType());
        item.put("host", conn.getHost());
        item.put("port", conn.getPort());
        item.put("database", conn.getDatabaseName());
        item.put("user", conn.getUsername());
        item.put("password_masked", true);
        item.put("remark", conn.getRemark());
        item.put("connected", false);
        item.put("created_at", conn.getCreateTime());
        item.put("updated_at", conn.getUpdateTime());
        return item;
    }

    /**
     * 自动填充conn的dbType/driverClass/jdbcUrl/schema
     */
    private void fillConnFields(SyncDbConn conn) {
        // 如果dbType为空，先从url推断
        if (conn.getDbType() == null || conn.getDbType().trim().isEmpty()) {
            if (conn.getJdbcUrl() != null && !conn.getJdbcUrl().trim().isEmpty()) {
                conn.setDbType(DialectFactory.inferDbType(conn.getJdbcUrl()));
            }
        }
        // 自动填充driverClass
        if (conn.getDriverClass() == null || conn.getDriverClass().trim().isEmpty()) {
            if (conn.getDbType() != null) {
                conn.setDriverClass(DataSourceUtils.inferDriverClass(conn.getDbType()));
            }
        }
        // 如果jdbcUrl为空但host/port/database有值，则自动拼接
        if ((conn.getJdbcUrl() == null || conn.getJdbcUrl().trim().isEmpty())
                && conn.getHost() != null && conn.getPort() != null) {
            conn.setJdbcUrl(buildJdbcUrl(conn.getDbType(), conn.getHost(), conn.getPort(), conn.getDatabaseName()));
        }
        // 从JDBC URL中提取schema(DM等数据库schema不一定等于username)
        if (conn.getSchema() == null || conn.getSchema().trim().isEmpty()) {
            String extractedSchema = extractSchemaFromUrl(conn.getDbType(), conn.getJdbcUrl());
            if (extractedSchema != null) {
                conn.setSchema(extractedSchema);
            } else {
                // 兜底: 默认使用username作为schema
                conn.setSchema(conn.getUsername());
            }
        }
    }

    /**
     * 根据数据库类型构建JDBC URL
     */
    private String buildJdbcUrl(String dbType, String host, Integer port, String database) {
        if (host == null) host = "localhost";
        switch (dbType.toLowerCase()) {
            case "oracle":
                int oraclePort = port != null ? port : 1521;
                String sid = database != null ? database : "orcl";
                return "jdbc:oracle:thin:@" + host + ":" + oraclePort + ":" + sid;
            case "mysql":
                int mysqlPort = port != null ? port : 3306;
                String schema = database != null ? database : "test";
                return "jdbc:mysql://" + host + ":" + mysqlPort + "/" + schema + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
            case "dm":
                int dmPort = port != null ? port : 5236;
                String dmSchema = database != null ? database : "SYSDBA";
                return "jdbc:dm://" + host + ":" + dmPort + "/" + dmSchema;
            case "postgresql":
                int pgPort = port != null ? port : 5432;
                String pgDb = database != null ? database : "postgres";
                return "jdbc:postgresql://" + host + ":" + pgPort + "/" + pgDb;
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }

    private Connection getConn(SyncDbConn conn) throws Exception {
        // 密码从数据库读出, 需AES解密后才能用于JDBC连接
        String decryptedPassword = passwordEncryptService.aesDecrypt(conn.getPassword());
        return dataSourceUtils.getConnection(conn.getId(), conn.getDbType(),
                conn.getJdbcUrl(), conn.getUsername(), decryptedPassword, conn.getDriverClass());
    }

    /**
     * 解析连接的schema, 优先用conn中已设置的schema, 否则从URL提取或用username兜底
     */
    private String resolveSchema(SyncDbConn conn) {
        if (conn.getSchema() != null && !conn.getSchema().trim().isEmpty()) {
            return conn.getSchema().toUpperCase();
        }
        // 从URL提取
        String extracted = extractSchemaFromUrl(conn.getDbType(), conn.getJdbcUrl());
        if (extracted != null) {
            return extracted.toUpperCase();
        }
        // 兜底用username
        return conn.getUsername() != null ? conn.getUsername().toUpperCase() : null;
    }

    /**
     * 从JDBC URL中提取schema
     * DM: jdbc:dm://host:port?SCHEMA=YSP 或 jdbc:dm://host:port/db?SCHEMA=YSP
     * Oracle: jdbc:oracle:thin:@host:port:sid (无schema概念, 返回null)
     * MySQL: jdbc:mysql://host:port/db (schema即数据库名)
     */
    private String extractSchemaFromUrl(String dbType, String jdbcUrl) {
        if (jdbcUrl == null || dbType == null) return null;
        switch (dbType.toLowerCase()) {
            case "dm": {
                // 从URL参数中提取SCHEMA, 如 ?SCHEMA=YSP 或 &SCHEMA=YSP
                String upper = jdbcUrl.toUpperCase();
                int idx = upper.indexOf("SCHEMA=");
                if (idx >= 0) {
                    int start = idx + "SCHEMA=".length();
                    int end = start;
                    while (end < jdbcUrl.length() && jdbcUrl.charAt(end) != '&' && jdbcUrl.charAt(end) != '#') {
                        end++;
                    }
                    return jdbcUrl.substring(start, end);
                }
                // DM URL格式: jdbc:dm://host:port/schemaName, 如果没有SCHEMA参数则取路径部分
                // 但路径部分通常不是schema, 所以返回null让兜底逻辑处理
                return null;
            }
            case "mysql": {
                // MySQL: jdbc:mysql://host:port/dbName?params
                // schema即数据库名, 从路径提取
                try {
                    int afterPort = jdbcUrl.indexOf('/', jdbcUrl.indexOf("//") + 2);
                    if (afterPort >= 0) {
                        int end = afterPort + 1;
                        while (end < jdbcUrl.length() && jdbcUrl.charAt(end) != '?' && jdbcUrl.charAt(end) != '#') {
                            end++;
                        }
                        return jdbcUrl.substring(afterPort + 1, end);
                    }
                } catch (Exception ignored) {}
                return null;
            }
            case "oracle":
                // Oracle用username作为schema, 不从URL提取
                return null;
            default:
                return null;
        }
    }
}
