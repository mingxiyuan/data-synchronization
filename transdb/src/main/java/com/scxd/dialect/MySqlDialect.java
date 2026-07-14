package com.scxd.dialect;

import java.util.List;
import java.util.stream.Collectors;

public class MySqlDialect implements DatabaseDialect {

    @Override
    public String getDbType() {
        return "mysql";
    }

    @Override
    public String getPageSql(String sql, long offset, int limit) {
        return sql + " LIMIT " + offset + ", " + limit;
    }

    @Override
    public String getMergeSql(List<String> columns, List<String> primaryKeys, String tableName) {
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "?").collect(Collectors.joining(", "));

        // ON DUPLICATE KEY UPDATE: 非主键列
        String updateSet = columns.stream()
                .filter(col -> !primaryKeys.contains(col))
                .map(col -> quote(col) + " = VALUES(" + quote(col) + ")")
                .collect(Collectors.joining(", "));

        String sql = "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES(" + insertVals + ")";
        if (!updateSet.isEmpty()) {
            sql += " ON DUPLICATE KEY UPDATE " + updateSet;
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
        String updateSet = columns.stream()
                .filter(col -> !primaryKeys.contains(col))
                .map(col -> quote(col) + " = VALUES(" + quote(col) + ")")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES " + values;
        if (!updateSet.isEmpty()) {
            sql += " ON DUPLICATE KEY UPDATE " + updateSet;
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
        return "`";
    }
}
