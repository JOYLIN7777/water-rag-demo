package com.joylin.waterkb.common;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 全局异常处理：统一返回 {code, message} 结构，避免堆栈泄露给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务校验类错误：400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        return Map.of("code", 400, "message", e.getMessage());
    }

    /** 上传文件超限：400 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Map<String, Object> handleMaxUpload(MaxUploadSizeExceededException e) {
        return Map.of("code", 400, "message", "文件超过大小限制（20MB）");
    }

    /** 兜底：500 */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleOther(Exception e) {
        return Map.of("code", 500, "message", "服务内部异常，请稍后重试");
    }
}
