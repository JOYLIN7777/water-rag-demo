package com.joylin.waterkb.common;

import org.springframework.ai.retry.NonTransientAiException;
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

    /**
     * 大模型 API 调用失败：502。
     * 不吞错误详情——之前这里被兜底成"服务内部异常"，上传失败的真实原因
     * （如 DashScope 的 batch size 限制）在页面上完全看不到，排查成本高。
     * 现在把 API 返回的原始 message 透出，前端上传区可直接看到失败原因。
     */
    @ExceptionHandler(NonTransientAiException.class)
    public Map<String, Object> handleAiError(NonTransientAiException e) {
        return Map.of("code", 502, "message", "大模型接口调用失败：" + e.getMessage());
    }

    /** 兜底：500 */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleOther(Exception e) {
        return Map.of("code", 500, "message", "服务内部异常，请稍后重试");
    }
}
