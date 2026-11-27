package com.joylin.waterkb.common;

import static com.joylin.waterkb.common.ErrorCode.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BindException;

import java.util.Map;

/**
 * 全局异常处理：统一返回 {code, message} 结构与真实 HTTP 状态码。
 *
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * multipart 配置（用于超限提示动态显示真实上限，避免提示文案与配置脱节）
     */
    private final MultipartProperties multipartProperties;

    public GlobalExceptionHandler(MultipartProperties multipartProperties) {
        this.multipartProperties = multipartProperties;
    }

    /**
     * 业务校验类错误：400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", INVALID_ARGUMENT, "message", e.getMessage()));
    }

    /**
     * 参数校验失败：400（取首条校验消息，避免前端拿到一堆字段错误）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().isEmpty() ? "参数校验失败"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("code", INVALID_ARGUMENT, "message", msg == null ? "参数校验失败" : msg));
    }

    /**
     * 参数绑定失败：400
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, Object>> handleBind(BindException e) {
        String msg = e.getBindingResult().getAllErrors().isEmpty() ? "参数绑定失败"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("code", INVALID_ARGUMENT, "message", msg == null ? "参数绑定失败" : msg));
    }

    /**
     * 状态冲突：409（Service 层 IllegalStateException 明确表达业务状态不对）
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", HttpStatus.CONFLICT.value(), "message", e.getMessage() == null ? "状态冲突" : e.getMessage()));
    }

    /**
     * 数据完整性冲突：409（唯一约束、外键约束等）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", HttpStatus.CONFLICT.value(), "message", "数据已存在或关联冲突"));
    }

    /**
     * 上传文件超限：413（上限文案动态读取并格式化为可读单位，避免出现 524288000B 这种原始字节数）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        var max = multipartProperties.getMaxFileSize();
        String readable = max.toGigabytes() >= 1 ? max.toGigabytes() + "GB" : max.toMegabytes() + "MB";
        return ResponseEntity.status(UPLOAD_TOO_LARGE).body(Map.of("code", UPLOAD_TOO_LARGE,
                "message", "文件超过大小限制（上限 " + readable + "）"));
    }

    /**
     * 大模型 API 调用失败：502。
     * 不吞错误详情——把 API 返回的原始 message 透出，前端可直接看到失败原因
     * （如 DashScope 的 batch size 限制、Key 无效等）。
     */
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, Object>> handleAiError(NonTransientAiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", AI_UPSTREAM, "message", "大模型接口调用失败：" + e.getMessage()));
    }

    /**
     * 静态资源不存在（例如已删除的旧页面）应返回 404，不要被兜底成 500
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", NOT_FOUND, "message", "页面不存在或已下线"));
    }

    /**
     * 兜底：500（必须把异常打进日志，否则排查时无迹可循）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常：{}", e.getMessage(), e);
        // 响应头带 requestId：用户报错时把它贴过来，就能查到对应日志与审计行
        return ResponseEntity.internalServerError()
                .header("X-Request-Id", org.slf4j.MDC.get("requestId") == null ? "" : org.slf4j.MDC.get("requestId"))
                .body(Map.of("code", INTERNAL, "message", "服务内部异常，请稍后重试"));
    }
}
