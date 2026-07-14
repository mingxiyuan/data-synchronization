package com.scxd.utils;

import com.scxd.config.Response;
import com.scxd.dialect.DatabaseDialect;
import com.scxd.dialect.DialectFactory;
import com.scxd.model.enums.SyncMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JDBCUtils {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceUtils dataSourceUtils;

    @Value("${spring.datasource.username}")
    private String schema;

    @Value("${transdb.max-clob-size:10485760}")
    private long maxClobSize;

    @Value("${transdb.max-blob-size:52428800}")
    private long maxBlobSize;

    // ======================== 出库: 查询数据 ========================

    /**
     * 出库 - 分页查询数据
     * @param conn 数据库连接
     * @param dialect 数据库方言
     * @param tableName 表名
     * @param columns 查询字段列表(空则SELECT *)
     * @param where 参数化查询条件(可null)
     * @param offset 偏移量(从0开始)
     * @param limit 每页大小
     * @return 查询结果列表
     */
    public List<Map<String, Object>> queryOut(Connection conn, DatabaseDialect dialect,
                                               String tableName, List<String> columns,
                                               WhereClause where, long offset, int limit) {
        return queryOut(conn, dialect, tableName, columns, where, null, offset, limit);
    }

    /** 带排序的出库查询 */
    public List<Map<String, Object>> queryOut(Connection conn, DatabaseDialect dialect,
                                               String tableName, List<String> columns,
                                               WhereClause where, String orderBy, long offset, int limit) {
        // 拼查询SQL
        String selectCols = (columns == null || columns.isEmpty()) ? "*" :
                columns.stream().map(dialect::quote).collect(Collectors.joining(", "));
        String baseSql = "SELECT " + selectCols + " FROM (" + buildFromClause(tableName, dialect) + ") t";
        if (where != null && where.hasSql()) {
            baseSql += " WHERE " + where.getSql();
        }
        if (orderBy != null && !orderBy.trim().isEmpty()) {
            baseSql += " ORDER BY " + orderBy;
        }

        // 拼分页SQL
        String pageSql = dialect.getPageSql(baseSql, offset, limit);
        log.debug("出库分页SQL: {}", pageSql);

        long queryStart = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(pageSql)) {
            // 设置参数化WHERE的参数值
            if (where != null && where.hasSql()) {
                setParams(ps, 1, where.getParams());
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String colLabel = meta.getColumnLabel(i).toUpperCase();
                        Object value = rs.getObject(i);
                        // 处理CLOB (限制大小, 防OOM)
                        if (value instanceof Clob) {
                            Clob clob = (Clob) value;
                            long len = clob.length();
                            if (len > maxClobSize) {
                                log.warn("CLOB字段[{}]超过大小限制: {} > {}, 将截断", colLabel, len, maxClobSize);
                                value = clob.getSubString(1, (int) maxClobSize) + "...[truncated]";
                            } else {
                                value = clob.getSubString(1, (int) len);
                            }
                        }
                        // 处理BLOB (限制大小, 防OOM)
                        if (value instanceof Blob) {
                            Blob blob = (Blob) value;
                            long len = blob.length();
                            if (len > maxBlobSize) {
                                log.warn("BLOB字段[{}]超过大小限制: {} > {}, 跳过处理", colLabel, len, maxBlobSize);
                                value = null;
                            } else {
                                byte[] bytes = blob.getBytes(1, (int) len);
                                value = Base64.getEncoder().encodeToString(bytes);
                            }
                        }
                        row.put(colLabel, value);
                    }
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            log.error("出库查询失败: table={}", tableName, e);
            throw new RuntimeException("出库查询失败: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - queryStart;
        if (elapsed > 3000) {
            log.warn("慢查询: table={}, elapsed={}ms, rows={}", tableName, elapsed, result.size());
        } else {
            log.debug("出库查询完成: table={}, elapsed={}ms, rows={}", tableName, elapsed, result.size());
        }
        return result;
    }

    /**
     * 出库 - 查询总记录数
     */
    public long queryCount(Connection conn, DatabaseDialect dialect, String tableName, WhereClause where) {
        String fromClause = buildFromClause(tableName, dialect);
        String baseSql = "SELECT * FROM (" + fromClause + ") t";
        if (where != null && where.hasSql()) {
            baseSql += " WHERE " + where.getSql();
        }
        String countSql = dialect.getCountSql(baseSql);
        log.debug("查询总数SQL: {}", countSql);
        try (PreparedStatement ps = conn.prepareStatement(countSql)) {
            if (where != null && where.hasSql()) {
                setParams(ps, 1, where.getParams());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("查询总数失败: table={}", tableName, e);
            throw new RuntimeException("查询总数失败: " + e.getMessage(), e);
        }
        return 0;
    }

    /**
     * 出库 - 查询最大标记值(增量同步用)
     */
    /** 查询源表最小标记值 */
    public String queryMinMarker(Connection conn, DatabaseDialect dialect, String tableName, String markerField, WhereClause where) {
        WhereClause.validateIdentifier(markerField, "markerField");
        String fromClause = buildFromClause(tableName, dialect);
        String sql = "SELECT MIN(" + dialect.quote(markerField) + ") FROM (" + fromClause + ") t";
        if (where != null && where.hasSql()) {
            sql += " WHERE " + where.getSql();
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (where != null && where.hasSql()) {
                setParams(ps, 1, where.getParams());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // 优先用getString, Oracle驱动返回标准格式YYYY-MM-DD HH24:MI:SS.FF6
                    String val = rs.getString(1);
                    if (val != null) return val.trim();
                    Object obj = rs.getObject(1);
                    return obj == null ? null : obj.toString();
                }
            }
        } catch (SQLException e) {
            log.error("查询最小标记值失败: table={}, marker={}", tableName, markerField, e);
            throw new RuntimeException("查询最小标记值失败: " + e.getMessage(), e);
        }
        return null;
    }

    public String queryMaxMarker(Connection conn, DatabaseDialect dialect, String tableName, String markerField, WhereClause where) {
        WhereClause.validateIdentifier(markerField, "markerField");
        String fromClause = buildFromClause(tableName, dialect);
        String sql = "SELECT MAX(" + dialect.quote(markerField) + ") FROM (" + fromClause + ") t";
        if (where != null && where.hasSql()) {
            sql += " WHERE " + where.getSql();
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (where != null && where.hasSql()) {
                setParams(ps, 1, where.getParams());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    if (val != null) return val.trim();
                    Object obj = rs.getObject(1);
                    return obj == null ? null : obj.toString();
                }
            }
        } catch (SQLException e) {
            log.error("查询最大标记值失败: table={}, marker={}", tableName, markerField, e);
            throw new RuntimeException("查询最大标记值失败: " + e.getMessage(), e);
        }
        return null;
    }

    // ======================== 入库: 写入数据 ========================

    /**
     * 入库 - MERGE/INSERT批量写入
     * 根据实际传入的数据列构建SQL, 仅处理数据中包含的列(目标表的子集)
     * @param conn 目标库连接
     * @param dialect 目标库方言
     * @param tableName 目标表名
     * @param primaryKeys 主键列表
     * @param data 数据列表
     * @param syncMode SyncMode: MERGE(1), INSERT_ONLY(2), CLEAN_INSERT(3)
     * @param schema 目标表schema, 为null时从Connection自动获取
     * @return 成功写入条数
     */
    public int mergeInto(Connection conn, DatabaseDialect dialect, String tableName,
                         List<String> primaryKeys, List<Map<String, Object>> data, int syncMode,
                         String schema, boolean noUpdate) {
        if (data == null || data.isEmpty()) {
            return 0;
        }
        // 获取目标表列信息, 用于类型判断
        Map<String, String> allTargetColumns = getColumns(conn, tableName, dialect, schema);
        Map<String, String> upperAllTargetColumns = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : allTargetColumns.entrySet()) {
            upperAllTargetColumns.put(entry.getKey().toUpperCase(), entry.getValue());
        }

        // 从实际数据中提取列名, 仅处理数据中包含的列(映射后的字段)
        // 这样当字段映射是目标表子集时, 不会用null覆盖目标表已有数据
        Map<String, String> upperColumns = new LinkedHashMap<>();
        Map<String, Object> firstRow = data.get(0);
        for (String col : firstRow.keySet()) {
            String upperCol = col.toUpperCase();
            String typeName = upperAllTargetColumns.get(upperCol);
            if (typeName != null) {
                upperColumns.put(upperCol, typeName);
            } else {
                // 数据中的列在目标表中不存在, 跳过并警告
                log.warn("入库跳过目标表{}中不存在的列: {}", tableName, upperCol);
            }
        }
        List<String> columnList = new ArrayList<>(upperColumns.keySet());

        // 主键统一转大写
        List<String> upperPrimaryKeys = new ArrayList<>();
        for (String pk : primaryKeys) {
            upperPrimaryKeys.add(pk.toUpperCase());
        }

        // 确定主键
        if (upperPrimaryKeys.isEmpty()) {
            upperPrimaryKeys = getPrimaryKeys(conn, tableName, dialect, schema);
            if (upperPrimaryKeys.isEmpty()) {
                throw new RuntimeException("目标表 " + tableName + " 未设置主键且未自定义主键，无法同步");
            }
        }

        // 将data中每行的key统一转大写, 方便查找
        List<Map<String, Object>> normalizedData = new ArrayList<>(data.size());
        for (Map<String, Object> row : data) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                newRow.put(entry.getKey().toUpperCase(), entry.getValue());
            }
            normalizedData.add(newRow);
        }

        boolean isMerge = (!noUpdate);
        int successCount;
        if (isMerge) {
            successCount = mergeWithUnionAll(conn, dialect, tableName, columnList, upperPrimaryKeys,
                    normalizedData, upperColumns, noUpdate);
        } else {
            successCount = insertBatch(conn, dialect, tableName, columnList, normalizedData, upperColumns);
        }
        return successCount;
    }

    /** MERGE: 先batch, DM返回空数组算成功, 冲突降级UNION ALL */
    private int mergeWithUnionAll(Connection conn, DatabaseDialect dialect, String tableName,
                                   List<String> columns, List<String> primaryKeys,
                                   List<Map<String, Object>> data, Map<String, String> columnTypes,
                                   boolean noUpdate) {
        if (data.isEmpty()) return 0;
        String sql = dialect.getMergeSql(columns, primaryKeys, tableName);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : data) {
                int idx = 1;
                for (String pk : primaryKeys) {
                    setParam(ps, idx++, row.get(pk), columnTypes.get(pk));
                }
                if (!noUpdate) {
                    for (String col : columns) {
                        if (primaryKeys.contains(col)) continue;
                        setParam(ps, idx++, row.get(col), columnTypes.get(col));
                    }
                }
                for (String col : columns) {
                    setParam(ps, idx++, row.get(col), columnTypes.get(col));
                }
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            // DM驱动batch有时返回空数组, 不报错就算全成功
            if (results.length == 0) {
                return data.size();
            }
            int successCount = 0;
            for (int r : results) {
                if (r != java.sql.Statement.EXECUTE_FAILED) {
                    successCount++;
                }
            }
            return successCount;
        } catch (java.sql.BatchUpdateException e) {
            // 唯一约束冲突, 降级UNION ALL一次跑完
            log.debug("MERGE批量冲突, 降级UNION ALL: {}", e.getMessage());
            return mergeUnionAll(conn, dialect, tableName, columns, primaryKeys, data, columnTypes, noUpdate);
        } catch (SQLException e) {
            log.error("入库写入失败: table={}", tableName, e);
            throw new RuntimeException("入库写入失败: " + e.getMessage(), e);
        }
    }

    /** 批量MERGE降级: 每方言自己拼SQL, JDBCUtils只负责设参执行 */
    private int mergeUnionAll(Connection conn, DatabaseDialect dialect, String tableName,
                               List<String> columns, List<String> primaryKeys,
                               List<Map<String, Object>> data, Map<String, String> columnTypes,
                               boolean noUpdate) {
        if (data.isEmpty()) return 0;
        String sql = dialect.getMergeBatchSql(columns, primaryKeys, tableName, data.size());

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Map<String, Object> row : data) {
                for (String col : columns) {
                    setParam(ps, idx++, row.get(col), columnTypes.get(col));
                }
            }
            ps.executeUpdate();
            return data.size();
        } catch (SQLException e) {
            log.error("批量MERGE降级写入失败: table={}", tableName, e);
            throw new RuntimeException("批量MERGE降级写入失败: " + e.getMessage(), e);
        }
    }

    /** INSERT批量: 标准addBatch/executeBatch */
    private int insertBatch(Connection conn, DatabaseDialect dialect, String tableName,
                             List<String> columns, List<Map<String, Object>> data,
                             Map<String, String> columnTypes) {
        if (data.isEmpty()) return 0;
        String sql = dialect.getInsertSql(columns, tableName);
        int successCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : data) {
                int idx = 1;
                for (String col : columns) {
                    setParam(ps, idx++, row.get(col), columnTypes.get(col));
                }
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            for (int r : results) {
                if (r != Statement.EXECUTE_FAILED) {
                    successCount++;
                }
            }
        } catch (SQLException e) {
            log.error("入库写入失败: table={}", tableName, e);
            throw new RuntimeException("入库写入失败: " + e.getMessage(), e);
        }
        return successCount;
    }

    /**
     * 入库 - 清空目标表(CLEAN_INSERT模式用)
     */
    public void cleanTable(Connection conn, DatabaseDialect dialect, String tableName) {
        String sql = dialect.getTruncateSql(tableName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
            log.info("清空目标表({}): {}", dialect.getDbType(), tableName);
        } catch (SQLException e) {
            log.error("清空目标表失败: {}", tableName, e);
            throw new RuntimeException("清空目标表失败: " + e.getMessage(), e);
        }
    }

    // ======================== 元数据查询 ========================

    /**
     * 获取表主键
     * @param schema 指定schema, 为null时从Connection自动获取
     */
    public List<String> getPrimaryKeys(Connection conn, String tableName, DatabaseDialect dialect, String schema) {
        List<String> list = new ArrayList<>();
        try {
            // 优先使用传入的schema, 否则从Connection获取
            String effectiveSchema = (schema != null && !schema.trim().isEmpty()) ? schema : getSchemaFromConnection(conn);
            ResultSet rs = conn.getMetaData().getPrimaryKeys(null, effectiveSchema, tableName);
            while (rs.next()) {
                list.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
            rs.close();
            // 若没找到, 不带schema再试一次
            if (list.isEmpty()) {
                rs = conn.getMetaData().getPrimaryKeys(null, null, tableName);
                while (rs.next()) {
                    list.add(rs.getString("COLUMN_NAME").toUpperCase());
                }
                rs.close();
            }
        } catch (SQLException e) {
            log.error("获取{}主键失败", tableName, e);
        }
        return list;
    }

    /**
     * 获取表字段信息 (字段名 -> 类型名)
     * @param schema 指定schema, 为null时从Connection自动获取
     */
    public Map<String, String> getColumns(Connection conn, String tableName, DatabaseDialect dialect, String schema) {
        Map<String, String> map = new LinkedHashMap<>();
        ResultSet rs = null;
        try {
            // 优先使用传入的schema, 否则从Connection获取
            String effectiveSchema = (schema != null && !schema.trim().isEmpty()) ? schema : getSchemaFromConnection(conn);
            rs = conn.getMetaData().getColumns(null, effectiveSchema, tableName, "%");
            while (rs.next()) {
                map.put(rs.getString("COLUMN_NAME").toUpperCase(), rs.getString("TYPE_NAME"));
            }
            // 若没找到, 不带schema再试
            if (map.isEmpty()) {
                rs.close();
                rs = conn.getMetaData().getColumns(null, null, tableName, "%");
                while (rs.next()) {
                    map.put(rs.getString("COLUMN_NAME").toUpperCase(), rs.getString("TYPE_NAME"));
                }
            }
        } catch (SQLException e) {
            log.error("获取{}字段失败", tableName, e);
        } finally {
            if (rs != null) {
                try { rs.close(); } catch (SQLException ignored) {}
            }
        }
        return map;
    }

    /**
     * 获取连接下的表列表
     * @param schema 指定schema, 为null时获取所有schema的表
     */
    public List<String> getTableList(Connection conn, String schema) {
        List<String> tables = new ArrayList<>();
        try {
            ResultSet rs = conn.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"});
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
            rs.close();
            // 不带schema再试
            if (tables.isEmpty()) {
                rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"});
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
                rs.close();
            }
        } catch (SQLException e) {
            log.error("获取表列表失败", e);
        }
        return tables;
    }

    // ======================== 兼容旧接口(不指定schema, 从Connection自动获取) ========================

    public List<String> getPrimaryKeys(Connection conn, String tableName) {
        return getPrimaryKeys(conn, tableName, DialectFactory.getDialect("oracle"), null);
    }

    public Map<String, String> getColumns(Connection conn, String tableName) {
        return getColumns(conn, tableName, DialectFactory.getDialect("oracle"), null);
    }

    /**
     * 旧版mergeInto(兼容原有Controller)
     */
    public Response mergeInto(List<Map<String, Object>> sourceDatas) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            log.error("获取数据库连接失败", e);
            return Response.error("获取数据库连接失败");
        }
        String tableName = (String) sourceDatas.get(0).get("TABLE");
        try {
            // 旧版使用默认数据源, schema从spring.datasource.username获取
            List<String> primaryKeys = getPrimaryKeys(connection, tableName);
            if (primaryKeys.isEmpty()) {
                return Response.error("目标表未设置主键，无法同步");
            }
            Map<String, String> columns = getColumns(connection, tableName);
            DatabaseDialect dialect = DialectFactory.getDialect("oracle");
            int count = mergeInto(connection, dialect, tableName, primaryKeys, sourceDatas, SyncMode.MERGE.getCode(), schema, false);
            return Response.success(count);
        } catch (Exception e) {
            log.error("入库失败", e);
            return Response.error("入库失败: " + e.getMessage());
        } finally {
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 判断是否为自定义SQL(而非纯表名): 以SELECT或WITH开头(SQL查询/CTE)
     * 通过JSqlParser AST白名单校验, 杜绝DDL/DML注入
     */
    private static boolean isCustomSql(String source) {
        if (source == null) return false;
        String trimmed = source.trim();
        boolean matches = trimmed.matches("(?i)^(SELECT|WITH)\\b.*");
        if (matches) {
            SqlValidator.validateSelectOnly(trimmed);
            log.info("执行自定义SQL: {}", trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
        }
        return matches;
    }

    /**
     * 构建FROM子句: 纯表名用quote包裹, 自定义SQL用子查询包裹
     */
    public String buildFromClause(String source, DatabaseDialect dialect) {
        if (isCustomSql(source)) {
            return "(" + source + ") t_src";
        }
        return dialect.quote(source);
    }

    /**
     * 构建完整SELECT SQL(供前端预览, 不含分页)
     */
    public String buildSelectSql(String source, DatabaseDialect dialect, String whereClause) {
        String cols = "*";
        return "SELECT " + cols + " FROM " + buildFromClause(source, dialect) + " t"
                + (whereClause != null && !whereClause.isEmpty() ? " WHERE " + whereClause : "");
    }

    private String getSchemaFromConnection(Connection conn) throws SQLException {
        try {
            return conn.getSchema();
        } catch (AbstractMethodError | Exception e) {
            // 部分驱动不支持getSchema
            return null;
        }
    }

    /**
     * 批量设置WHERE条件的参数值
     */
    private void setParams(PreparedStatement ps, int startIndex, List<Object> params) throws SQLException {
        int idx = startIndex;
        for (Object param : params) {
            ps.setObject(idx++, param);
        }
    }

    /**
     * 设置PreparedStatement参数(适配多数据库类型)
     */
    private void setParam(PreparedStatement ps, int index, Object value, String typeName) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
            return;
        }
        String typeUpper = typeName.toUpperCase();
        if (typeUpper.contains("DATE") || typeUpper.contains("TIMESTAMP") || typeUpper.contains("DATETIME")) {
            if (value instanceof java.util.Date) {
                ps.setTimestamp(index, new Timestamp(((java.util.Date) value).getTime()));
            } else {
                String strVal = value.toString();
                if (strVal.isEmpty()) {
                    ps.setNull(index, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(index, new Timestamp(DateUtils.dateStrFormatDate(strVal, "yyyy-MM-dd HH:mm:ss").getTime()));
                }
            }
        } else if (typeUpper.contains("NUMBER") || typeUpper.contains("INT") || typeUpper.contains("BIGINT")
                || typeUpper.contains("SMALLINT") || typeUpper.contains("TINYINT")) {
            if (value instanceof Number) {
                ps.setLong(index, ((Number) value).longValue());
            } else {
                String strVal = value.toString();
                if (strVal.isEmpty()) {
                    ps.setNull(index, Types.NUMERIC);
                } else {
                    ps.setLong(index, Long.parseLong(strVal));
                }
            }
        } else if (typeUpper.contains("DECIMAL") || typeUpper.contains("NUMERIC") || typeUpper.contains("DOUBLE")
                || typeUpper.contains("FLOAT") || typeUpper.contains("REAL")) {
            if (value instanceof Number) {
                ps.setDouble(index, ((Number) value).doubleValue());
            } else {
                String strVal = value.toString();
                if (strVal.isEmpty()) {
                    ps.setNull(index, Types.DECIMAL);
                } else {
                    ps.setDouble(index, Double.parseDouble(strVal));
                }
            }
        } else if (typeUpper.contains("BLOB")) {
            if (value instanceof byte[]) {
                InputStream is = new ByteArrayInputStream((byte[]) value);
                ps.setBinaryStream(index, is);
            } else {
                String strVal = value.toString();
                if (strVal.startsWith("data:image")) {
                    strVal = strVal.replaceAll("^data:image/[^;]+;base64,", "");
                    byte[] decoded = Base64.getDecoder().decode(strVal);
                    ps.setBinaryStream(index, new ByteArrayInputStream(decoded));
                } else {
                    ps.setBinaryStream(index, null);
                }
            }
        } else if (typeUpper.contains("CLOB") || typeUpper.contains("TEXT")) {
            ps.setString(index, value.toString());
        } else {
            ps.setString(index, value.toString());
        }
    }

}
