package com.scxd.config;

public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCodeEnum httpCodeEnum) {
        super(httpCodeEnum.getMsg());
        this.code = httpCodeEnum.getCode();
    }

    public BusinessException(ResultCodeEnum httpCodeEnum, String msg) {
        super(msg);
        this.code = httpCodeEnum.getCode();
    }

    public BusinessException(ResultCodeEnum httpCodeEnum, Throwable msg) {
        super(msg);
        this.code = httpCodeEnum.getCode();
    }

    public int getCode() {
        return code;
    }
}
