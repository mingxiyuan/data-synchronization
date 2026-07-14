package com.scxd.dialect;

import java.util.List;

/**
 * 数据库方言接口 - 抽象不同数据库的SQL语法差异
 */
public interface DatabaseDialect {

    /**
     * 获取数据库类型标识
     */
    String getDbType();

    /**
     * 获取分页SQL
     * @param sql 原始查询SQL
     * @param offset 偏移量(从0开始)
     * @param limit 每页大小
     * @return 分页SQL
     */
    String getPageSql(String sql, long offset, int limit);

    /**
     * 获取MERGE INTO SQL (存在则更新, 不存在则插入) — 单行, 用于addBatch
     * @param columns 全部列名
     * @param primaryKeys 主键列
     * @param tableName 表名
     * @return MERGE SQL模板
     */
    String getMergeSql(List<String> columns, List<String> primaryKeys, String tableName);

    /**
     * 获取批量MERGE SQL — N行数据拼成一条SQL, 降级用
     * Oracle/DM: MERGE INTO ... USING (SELECT ... FROM dual UNION ALL ...)
     * MySQL: INSERT INTO ... VALUES (...), (...) ON DUPLICATE KEY UPDATE ...
     * PG: INSERT INTO ... VALUES (...), (...) ON CONFLICT ... DO UPDATE SET ...
     * @param columns 全部列名
     * @param primaryKeys 主键列
     * @param tableName 表名
     * @param rowCount 数据行数
     * @return 单条批量MERGE SQL
     */
    default String getMergeBatchSql(List<String> columns, List<String> primaryKeys, String tableName, int rowCount) {
        // 默认用MERGE+UNION ALL, Oracle/DM直接用
        String q = getQuote();
        String selectCols = columns.stream().map(c -> "? " + q + c + q).collect(java.util.stream.Collectors.joining(", "));
        String row = "SELECT " + selectCols + " FROM dual";
        StringBuilder using = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) using.append(" UNION ALL ");
            using.append(row);
        }
        String onClause = primaryKeys.stream()
                .map(pk -> "t." + q + pk + q + " = s." + q + pk + q)
                .collect(java.util.stream.Collectors.joining(" AND "));
        String insertCols = columns.stream().map(c -> q + c + q).collect(java.util.stream.Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "s." + q + c + q).collect(java.util.stream.Collectors.joining(", "));
        String updateSet = columns.stream()
                .filter(c -> !primaryKeys.contains(c))
                .map(c -> "t." + q + c + q + " = s." + q + c + q)
                .collect(java.util.stream.Collectors.joining(", "));

        return "MERGE INTO " + q + tableName + q + " t" +
                " USING (" + using + ") s" +
                " ON (" + onClause + ")" +
                " WHEN MATCHED THEN UPDATE SET " + updateSet +
                " WHEN NOT MATCHED THEN INSERT(" + insertCols + ") VALUES(" + insertVals + ")";
    }

    /** 日期参数占位符: Oracle/DM用TO_DATE, MySQL/PG直接? */
    default String getDatePlaceholder() {
        return "?";
    }

    /**
     * 获取INSERT ONLY SQL (仅插入, 不处理冲突)
     * @param columns 全部列名
     * @param tableName 表名
     * @return INSERT SQL
     */
    String getInsertSql(List<String> columns, String tableName);

    /**
     * 获取标识符引用符
     * Oracle/DM: 双引号或空, MySQL: 反引号
     */
    String getQuote();

    /**
     * 引用标识符(表名/列名)
     */
    default String quote(String identifier) {
        String q = getQuote();
        return q + identifier + q;
    }

    /**
     * 获取查询总记录数的SQL
     */
    default String getCountSql(String sql) {
        return "SELECT COUNT(1) FROM (" + sql + ") tcount";
    }

    /**
     * 清空表SQL: 默认TRUNCATE, 不支持的数据厓覆写
     */
    default String getTruncateSql(String tableName) {
        return "TRUNCATE TABLE " + quote(tableName);
    }

    /**
     * 获取查询当前最大标记值的SQL (增量同步用)
     * @param markerField 标记字段
     * @param tableName 表名
     * @param where 条件
     * @return SQL
     */
    default String getMaxMarkerSql(String markerField, String tableName, String where) {
        String sql = "SELECT MAX(" + quote(markerField) + ") FROM " + quote(tableName);
        if (where != null && !where.trim().isEmpty()) {
            sql += " WHERE " + where;
        }
        return sql;
    }
}
