package com.scxd.utils;

import com.scxd.dialect.DatabaseDialect;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数化WHERE条件 — SQL片段与参数值分离, 防止SQL注入
 */
public class WhereClause {
    private final String sql;
    private final List<Object> params;

    public WhereClause(String sql) {
        this.sql = sql;
        this.params = new ArrayList<>();
    }

    public WhereClause(String sql, List<Object> params) {
        this.sql = sql;
        this.params = params != null ? params : new ArrayList<>();
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParams() {
        return params;
    }

    /** 是否有有效的WHERE条件 */
    public boolean hasSql() {
        return sql != null && !sql.trim().isEmpty();
    }

    /** 安全的字段名校验: 只允许字母、数字、下划线和点 */
    public static String validateIdentifier(String name, String fieldLabel) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_\\.]*")) {
            throw new IllegalArgumentException(fieldLabel + " 包含非法字符: " + name);
        }
        return name;
    }

    /** 创建 marker > value 的参数化条件 */
    public static WhereClause gt(String field, String value, DatabaseDialect dialect) {
        String f = validateIdentifier(field, "markerField");
        Object param = value;
        // 截断.0后缀(日期值通用处理)
        if (param != null && param.toString().length() > 19)
            param = param.toString().substring(0, 19);
        String placeholder = dialect != null ? dialect.getDatePlaceholder() : "?";
        String sql = f + " > " + placeholder;
        List<Object> params = new ArrayList<>();
        params.add(param);
        return new WhereClause(sql, params);
    }

    /** 合并两个WHERE条件(AND连接) */
    public static WhereClause combine(WhereClause a, WhereClause b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        String combined = a.sql + " AND " + b.sql;
        List<Object> params = new ArrayList<>(a.params);
        params.addAll(b.params);
        return new WhereClause(combined, params);
    }

    /** OR连接两个WHERE条件 */
    public static WhereClause or(WhereClause a, WhereClause b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        String sql = "(" + a.sql + " OR " + b.sql + ")";
        List<Object> params = new ArrayList<>(a.params);
        params.addAll(b.params);
        return new WhereClause(sql, params);
    }

    /** field IS NULL */
    public static WhereClause isNull(String field) {
        String f = validateIdentifier(field, "field");
        return new WhereClause(f + " IS NULL");
    }

    /** marker <= value (用于快照窗口上界) */
    public static WhereClause le(String field, String value, DatabaseDialect dialect) {
        String f = validateIdentifier(field, "markerField");
        Object param = value;
        if (param != null && param.toString().length() > 19)
            param = param.toString().substring(0, 19);
        String placeholder = dialect != null ? dialect.getDatePlaceholder() : "?";
        String sql = f + " <= " + placeholder;
        List<Object> params = new ArrayList<>();
        params.add(param);
        return new WhereClause(sql, params);
    }

    /** 通用 > (不转TO_DATE, 用于非日期字段如主键) */
    public static WhereClause gtGeneric(String field, String value) {
        String f = validateIdentifier(field, "field");
        List<Object> params = new ArrayList<>();
        params.add(value);
        return new WhereClause(f + " > ?", params);
    }

    /** marker > start AND marker <= end 的区间条件 (不含NULL) */
    public static WhereClause range(String field, String start, String end, DatabaseDialect dialect) {
        WhereClause gt = gt(field, start, dialect);
        WhereClause le = le(field, end, dialect);
        if (gt == null && le == null) return null;
        if (gt == null) return le;
        if (le == null) return gt;
        return combine(gt, le);
    }
}
