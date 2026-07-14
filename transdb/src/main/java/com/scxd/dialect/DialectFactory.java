package com.scxd.dialect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言工厂 - 根据数据库类型获取对应方言实例
 */
public class DialectFactory {

    private static final Map<String, DatabaseDialect> DIALECT_CACHE = new ConcurrentHashMap<>();

    static {
        DIALECT_CACHE.put("oracle", new OracleDialect());
        DIALECT_CACHE.put("mysql", new MySqlDialect());
        DIALECT_CACHE.put("dm", new DmDialect());
        DIALECT_CACHE.put("postgresql", new PostgreSqlDialect());
    }

    /**
     * 根据数据库类型获取方言
     * @param dbType oracle / mysql / dm / postgresql
     */
    public static DatabaseDialect getDialect(String dbType) {
        if (dbType == null || dbType.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        DatabaseDialect dialect = DIALECT_CACHE.get(dbType.toLowerCase());
        if (dialect == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
        return dialect;
    }

    /**
     * 根据JDBC URL自动推断数据库类型
     */
    public static String inferDbType(String jdbcUrl) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("JDBC URL不能为空");
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:oracle:")) {
            return "oracle";
        } else if (lower.startsWith("jdbc:mysql:")) {
            return "mysql";
        } else if (lower.startsWith("jdbc:dm:")) {
            return "dm";
        } else if (lower.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        throw new IllegalArgumentException("无法从JDBC URL推断数据库类型: " + jdbcUrl);
    }
}
