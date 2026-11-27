package com.joylin.waterkb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求体。
 *
 */
public record ChatRequest(

        @NotBlank(message = "sessionId 不能为空")
        String sessionId,

        @NotBlank(message = "question 不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question,

        String parentId,

        Long modelId) {
}
