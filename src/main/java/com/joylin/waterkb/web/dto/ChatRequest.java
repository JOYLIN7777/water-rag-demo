package com.joylin.waterkb.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求体。
 *
 * @param sessionId 会话 ID：前端生成（UUID），用于隔离 Redis 中的对话历史
 * @param question  用户问题
 */
public record ChatRequest(

        @NotBlank(message = "sessionId 不能为空")
        String sessionId,

        @NotBlank(message = "question 不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question) {
}
