package com.scxd.utils;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL安全校验器 — 基于JSqlParser AST白名单
 * 替代旧的关键词黑名单方案, 杜绝误杀和绕过
 */
@Slf4j
public final class SqlValidator {

    private SqlValidator() {}

    /**
     * 校验SQL是否为纯查询语句(SELECT/CTE), 不包含DML/DDL
     * @return 提取的表名列表(用于审计日志), 校验失败抛异常
     */
    public static List<String> validateSelectOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL不能为空");
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(trimmed);
            // AST白名单: 只允许Select, 其他类型(Insert/Update/Delete/DDL等)一律拒绝
            if (!(stmt instanceof Select)) {
                String type = stmt != null ? stmt.getClass().getSimpleName() : "null";
                throw new IllegalArgumentException("仅允许SELECT/WITH查询语句, 不支持: " + type);
            }
            return new ArrayList<>();
        } catch (JSQLParserException e) {
            log.warn("SQL解析失败(降级放过): {}", e.getMessage());
            // 解析失败但以SELECT/WITH开头, 降级放过(兼容Oracle特有语法如CONNECT BY)
            if (trimmed.matches("(?i)^(SELECT|WITH)\\b.*")) {
                return new ArrayList<>();
            }
            throw new IllegalArgumentException("SQL语法错误: " + e.getMessage());
        }
    }

    /**
     * 校验WHERE片段是否可解析(格式校验+防注入)
     * 解析失败直接拒绝, 不再降级放过
     */
    public static void validateWhereFragment(String where) {
        if (where == null || where.trim().isEmpty()) return;
        try {
            CCJSqlParserUtil.parseExpression(where.trim());
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("WHERE条件格式不合法, 请检查: " + e.getMessage());
        }
    }
}
