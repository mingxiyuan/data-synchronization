package com.scxd.config;

/**
 * 统一响应码枚举
 */
public enum ResultCodeEnum {

    SUCCESS(0, "success"),
    FAIL(-1, "失败"),
    PARAM_ERROR(1001, "参数校验失败"),
    AUTH_ERROR(1002, "未登录或token过期"),
    DB_CONN_ERROR(2001, "数据库连接失败"),
    CONFIG_NOT_FOUND(2002, "配置不存在"),
    TASK_EXEC_ERROR(2003, "任务执行异常"),
    SQL_VALIDATE_ERROR(3001, "SQL校验不通过"),
    MARKER_REQUIRED(3002, "增量字段未设置"),
    SYSTEM_ERROR(5001, "服务器内部错误");

    private final Integer code;
    private final String msg;

    ResultCodeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
