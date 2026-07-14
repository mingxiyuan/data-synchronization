package com.trans.dialect;

/**
 * 达梦数据库方言
 * DM兼容Oracle语法, 直接复用OracleDialect即可
 */
public class DmDialect extends OracleDialect {

    @Override
    public String getDbType() {
        return "dm";
    }

    @Override
    public String getPageSql(String sql, long offset, int limit) {
        // DM支持LIMIT语法, 更简洁
        return sql + " LIMIT " + offset + ", " + limit;
    }
}
