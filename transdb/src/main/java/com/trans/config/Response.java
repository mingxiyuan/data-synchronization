package com.trans.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * 统一响应体
 * 格式对齐 API_DOC.md: { code, message, data, timestamp }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Response implements Serializable {

    private Integer code;
    private String message;
    private Object data;
    private Long timestamp;

    public Response() {
        this.timestamp = System.currentTimeMillis();
    }

    public Response(ResultCodeEnum rCode) {
        this.code = rCode.getCode();
        this.message = rCode.getMsg();
        this.timestamp = System.currentTimeMillis();
    }

    // ======================== 成功 ========================

    public static Response success() {
        Response r = new Response();
        r.code = ResultCodeEnum.SUCCESS.getCode();
        r.message = ResultCodeEnum.SUCCESS.getMsg();
        return r;
    }

    public static Response success(Object data) {
        Response r = success();
        r.data = data;
        return r;
    }

    // ======================== 失败 ========================

    public static Response error(ResultCodeEnum code, String message) {
        Response r = new Response();
        r.code = code.getCode();
        r.message = message;
        return r;
    }

    public static Response error(String message) {
        return error(ResultCodeEnum.SYSTEM_ERROR, message);
    }

    public static Response paramError(String message) {
        return error(ResultCodeEnum.PARAM_ERROR, message);
    }

    public static Response dbConnError(String message) {
        return error(ResultCodeEnum.DB_CONN_ERROR, message);
    }

    public static Response configNotFound(String message) {
        return error(ResultCodeEnum.CONFIG_NOT_FOUND, message);
    }

    public static Response taskExecError(String message) {
        return error(ResultCodeEnum.TASK_EXEC_ERROR, message);
    }

    public static Response sqlValidateError(String message) {
        return error(ResultCodeEnum.SQL_VALIDATE_ERROR, message);
    }

    public static Response markerRequired(String message) {
        return error(ResultCodeEnum.MARKER_REQUIRED, message);
    }

    // ======================== getter/setter ========================

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", timestamp=" + timestamp +
                '}';
    }
}
