package com.trans.config;

import cn.dev33.satoken.exception.NotLoginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @org.springframework.beans.factory.annotation.Autowired
    private HttpServletRequest httpServletRequest;

    /**
     * 缺少请求体异常处理器
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Response parameterBodyMissingExceptionHandler(HttpMessageNotReadableException e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',请求体缺失'{}'", requestURI, e.getMessage());
        return Response.paramError("请求体缺失: " + e.getMessage());
    }

    /**
     * 请求方法异常
     */
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Response methodNotAllowedHandler(HttpRequestMethodNotSupportedException e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',请求方法不被允许'{}'", requestURI, e.getMessage());
        return Response.paramError("请求方法不被允许: " + e.getMessage());
    }

    /**
     * GET请求参数校验异常
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({MissingServletRequestParameterException.class})
    public Response bindExceptionHandler(MissingServletRequestParameterException e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',get方式请求参数'{}'必传", requestURI, e.getParameterName());
        return Response.paramError("缺少必要参数: " + e.getParameterName());
    }

    /**
     * POST请求的对象参数校验异常
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({MethodArgumentNotValidException.class})
    public Response methodArgumentNotValidHandler(MethodArgumentNotValidException e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',post方式请求参数异常'{}'", requestURI, e.getMessage());
        String msg = getValidExceptionMsg(e.getBindingResult().getAllErrors());
        return Response.paramError(msg != null ? msg : e.getMessage());
    }

    /**
     * 未登录异常(Sa-Token)
     */
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(NotLoginException.class)
    public Response notLoginExceptionHandler(NotLoginException e) {
        return Response.error(ResultCodeEnum.AUTH_ERROR, "未登录或token已过期");
    }

    /**
     * 业务类异常
     */
    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler(BusinessException.class)
    public Response businessExceptionHandler(BusinessException e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',捕获业务类异常'{}'", requestURI, e.getMessage());
        return Response.error(e.getMessage());
    }

    /**
     * 运行时异常 — 不向前端暴露异常详情, 生成requestId便于排查
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(RuntimeException.class)
    public Response runtimeExceptionHandler(RuntimeException e) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求[{}]地址'{}'发生异常", requestId, requestURI, e);
        return Response.error("系统错误，请联系管理员，错误ID: " + requestId);
    }

    /**
     * 系统级别异常
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Throwable.class)
    public Response throwableExceptionHandler(Throwable e) {
        String requestURI = httpServletRequest.getRequestURI();
        log.error("请求地址'{}',捕获系统级别异常", requestURI, e);
        return Response.error("服务器内部错误");
    }

    private String getValidExceptionMsg(List<ObjectError> errors) {
        if (!CollectionUtils.isEmpty(errors)) {
            StringBuilder sb = new StringBuilder();
            errors.forEach(error -> {
                if (error instanceof FieldError) {
                    sb.append(((FieldError) error).getField()).append(":");
                }
                sb.append(error.getDefaultMessage()).append(";");
            });
            String msg = sb.toString();
            return msg.substring(0, msg.length() - 1);
        }
        return null;
    }
}
