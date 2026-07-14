package com.trans.model.entity;

import lombok.Data;
import java.util.Date;

/**
 * 数据库连接配置
 * 字段名对齐API_DOC.md:
 * - type (文档) -> dbType (数据库列DB_TYPE)
 * - user (文档) -> username (数据库列USERNAME)
 * - database (文档) -> databaseName (数据库列DATABASE_NAME)
 * 使用@JsonProperty实现前端字段名与Java字段名的映射
 */
@Data
public class SyncDbConn {
    private String id;
    /** 连接名称 */
    private String name;
    /** 数据库类型: mysql / postgresql / oracle / sqlserver / mongodb / dm */
    private String dbType;
    /** 主机地址 */
    private String host;
    /** 端口 */
    private Integer port;
    /** 数据库名/SID/实例名 */
    private String databaseName;
    /** JDBC连接地址(可由host/port/database自动拼接) */
    private String jdbcUrl;
    /** 用户名 */
    private String username;
    /** 密码 */
    private String password;
    /** 驱动类(可根据dbType自动填充) */
    private String driverClass;
    /** Schema/模式名(DM等数据库schema不一定等于username, 从JDBC URL中提取) */
    private String schema;
    /** 备注 */
    private String remark;
    /** 1=有效, 0=无效 */
    private Integer status;
    /** 密码脱敏标识(不存库, 仅返回前端) */
    private Boolean passwordMasked;
    /** 当前是否已连通(不存库, 仅返回前端) */
    private Boolean connected;
    private Date createTime;
    private Date updateTime;
}
