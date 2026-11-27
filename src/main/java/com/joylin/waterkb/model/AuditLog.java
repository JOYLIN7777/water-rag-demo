package com.joylin.waterkb.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 审计日志表实体。
 */
@Table(name = "audit_log")
public record AuditLog(
        /**
         * 记录时间。
         */
        @Column(name = "ts") @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") @Schema(description = "记录时间") LocalDateTime ts,

        /**
         * 租户标识。
         */
        @Column(name = "tenant_id") @Schema(description = "租户标识") String tenantId,

        /**
         * 用户账号。
         */
        @Column(name = "user_id") @Schema(description = "用户账号") String userId,

        /**
         * 用户组。
         */
        @Column(name = "groups") @Schema(description = "用户组") String groups,

        /**
         * 操作动作。
         */
        @Column(name = "action") @Schema(description = "操作动作") String action,

        /**
         * 资源路径。
         */
        @Column(name = "resource") @Schema(description = "资源路径") String resource,

        /**
         * 操作细节。
         */
        @Column(name = "detail") @Schema(description = "操作细节") String detail,

        /**
         * 来源地址。
         */
        @Column(name = "ip") @Schema(description = "来源地址") String ip,

        /**
         * 响应状态。
         */
        @Column(name = "status") @Schema(description = "响应状态") int status,

        /**
         * 耗时毫秒。
         */
        @Column(name = "duration_ms") @Schema(description = "耗时毫秒") long durationMs,

        /**
         * 操作结果。
         */
        @Column(name = "result") @Schema(description = "操作结果") String result) {
}
