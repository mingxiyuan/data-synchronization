package com.scxd.dialect;

import java.util.List;
import java.util.stream.Collectors;

public class OracleDialect implements DatabaseDialect {

    @Override
    public String getDbType() {
        return "oracle";
    }

    @Override
    public String getPageSql(String sql, long offset, int limit) {
        // Oracle 12c+ 语法, 兼容11g的ROWNUM方式
        return "SELECT * FROM (SELECT a.*, ROWNUM rn FROM (" + sql + ") a WHERE ROWNUM <= " + (offset + limit) + ") WHERE rn > " + offset;
    }

    @Override
    public String getMergeSql(List<String> columns, List<String> primaryKeys, String tableName) {
        // USING部分: select ? col1, ? col2 ... from dual
        String usingCols = primaryKeys.stream()
                .map(pk -> "? " + quote(pk))
                .collect(Collectors.joining(", "));

        // ON条件: t1.pk1 = t2.pk1 and t1.pk2 = t2.pk2
        String onClause = primaryKeys.stream()
                .map(pk -> "t1." + quote(pk) + " = t2." + quote(pk))
                .collect(Collectors.joining(" AND "));

        // INSERT列和VALUES
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "?").collect(Collectors.joining(", "));

        // UPDATE SET: 非主键列
        String updateSet = columns.stream()
                .filter(col -> !primaryKeys.contains(col))
                .map(col -> "t1." + quote(col) + " = ?")
                .collect(Collectors.joining(", "));

        String sql = "MERGE INTO " + quote(tableName) + " t1 USING (SELECT " + usingCols + " FROM dual) t2" +
                " ON (" + onClause + ")";

        sql += " WHEN MATCHED THEN UPDATE SET " + updateSet;
        sql += " WHEN NOT MATCHED THEN INSERT(" + insertCols + ") VALUES(" + insertVals + ")";
        return sql;
    }

    @Override
    public String getInsertSql(List<String> columns, String tableName) {
        String insertCols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String insertVals = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + quote(tableName) + "(" + insertCols + ") VALUES(" + insertVals + ")";
    }

    @Override
    public String getDatePlaceholder() {
        return "TO_DATE(?, 'YYYY-MM-DD HH24:MI:SS')";
    }

    @Override
    public String getQuote() {
        // 加双引号: 标识符已统一大写, 安全; 且防止保留字/关键字冲突
        return "\"";
    }
}
