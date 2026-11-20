package com.joylin.waterkb.audit;

import java.time.LocalDateTime;

/**
 * 审计日志条目：谁、在哪个租户、什么时间、对什么资源、做了什么、结果如何。
 *
 * @param ts       时间
 * @param tenantId 租户
 * @param userId   用户
 * @param groups   用户组（逗号拼接）
 * @param action   动作（HTTP 方法 + 语义，如 POST /api/docs/upload）
 * @param resource 资源（请求路径）
 * @param detail   细节（查询参数摘要等，脱敏后）
 * @param ip       来源 IP
 * @param status   HTTP 状态
 * @param durationMs 耗时毫秒
 * @param result   SUCCESS / DENIED / ERROR
 */
public record AuditLog(
        LocalDateTime ts,
        String tenantId,
        String userId,
        String groups,
        String action,
        String resource,
        String detail,
        String ip,
        int status,
        long durationMs,
        String result) {
}
