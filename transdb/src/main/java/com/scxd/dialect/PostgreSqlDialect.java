package com.scxd.dialect;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL / 瀚高数据库方言
 * 瀚高(HighGo DB)基于PostgreSQL, 使用标准PG驱动和协议
 */
public class PostgreSqlDialect implements DatabaseDialect {

    @Override
    public String getDbType() {
        return "postgresql";
    }

    @Override
    public String getPageSql(String sql, long offset, int limit) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String getMergeSql(List<String> columns, List<String> primaryKeys, String tableName) {
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "?").collect(Collectors.joining(", "));

        String pkCols = primaryKeys.stream().map(this::quote).collect(Collectors.joining(", "));
        String updateSet = columns.stream()
                .filter(col -> !primaryKeys.contains(col))
                .map(col -> quote(col) + " = EXCLUDED." + quote(col))
                .collect(Collectors.joining(", "));

        String sql = "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES(" + insertVals + ")";
        if (!updateSet.isEmpty()) {
            sql += " ON CONFLICT (" + pkCols + ") DO UPDATE SET " + updateSet;
        } else {
            sql += " ON CONFLICT (" + pkCols + ") DO NOTHING";
        }
        return sql;
    }

    @Override
    public String getMergeBatchSql(List<String> columns, List<String> primaryKeys, String tableName, int rowCount) {
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String oneRow = "(" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) values.append(", ");
            values.append(oneRow);
        }
        String pkCols = primaryKeys.stream().map(this::quote).collect(Collectors.joining(", "));
        String updateSet = columns.stream()
                .filter(col -> !primaryKeys.contains(col))
                .map(col -> quote(col) + " = EXCLUDED." + quote(col))
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES " + values;
        if (!updateSet.isEmpty()) {
            sql += " ON CONFLICT (" + pkCols + ") DO UPDATE SET " + updateSet;
        } else {
            sql += " ON CONFLICT (" + pkCols + ") DO NOTHING";
        }
        return sql;
    }

    @Override
    public String getInsertSql(List<String> columns, String tableName) {
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES(" + insertVals + ")";
    }

    @Override
    public String getQuote() {
        return "\"";
    }
}
