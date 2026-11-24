package com.joylin.waterkb.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 全局异常处理：统一返回 {code, message} 结构与真实 HTTP 状态码。
 *
 * <p>教训：早期版本返回 Map 时 HTTP 状态码恒为 200，前端靠 res.ok 判断成败
 * 全部失效——上传被拒时页面显示的是"✓ 解析完成（undefined 个切片）"。
 * 现在所有错误都带正确状态码，前端 r.ok 即可正确分流。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** multipart 配置（用于超限提示动态显示真实上限，避免提示文案与配置脱节） */
    private final MultipartProperties multipartProperties;

    public GlobalExceptionHandler(MultipartProperties multipartProperties) {
        this.multipartProperties = multipartProperties;
    }

    /** 业务校验类错误：400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
    }

    /** 上传文件超限：400（上限文案动态读取并格式化为可读单位，避免出现 524288000B 这种原始字节数） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        var max = multipartProperties.getMaxFileSize();
        String readable = max.toGigabytes() >= 1 ? max.toGigabytes() + "GB" : max.toMegabytes() + "MB";
        return ResponseEntity.badRequest().body(Map.of("code", 400,
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
                .body(Map.of("code", 502, "message", "大模型接口调用失败：" + e.getMessage()));
    }

    /** 静态资源不存在（例如已删除的旧页面）应返回 404，不要被兜底成 500 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", "页面不存在或已下线"));
    }

    /** 兜底：500（必须把异常打进日志，否则排查时无迹可循） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常：{}", e.getMessage(), e);
        return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "服务内部异常，请稍后重试"));
    }
}
