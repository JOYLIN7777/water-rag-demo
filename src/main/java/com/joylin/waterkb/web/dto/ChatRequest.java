package com.joylin.waterkb.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求体。
 *
 * @param sessionId 会话 ID：前端生成，用于隔离 Redis 中的消息树
 * @param question  用户问题
 * @param parentId  从哪条消息继续：null=当前 head（默认顺延）；
 *                  传某个历史节点 ID=从那里分叉（fork），祖先链作为本次上下文，
 *                  该节点之后的旧分支保留不删（pi-mono rewind 语义）
 */
public record ChatRequest(

        @NotBlank(message = "sessionId 不能为空")
        String sessionId,

        @NotBlank(message = "question 不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question,

        String parentId) {
}
