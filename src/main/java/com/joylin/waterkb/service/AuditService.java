package com.joylin.waterkb.service;

import com.joylin.waterkb.mapper.AuditMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.joylin.waterkb.model.AuditLog;

/**
 * 审计日志仓库。
 *
 *
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditMapper mapper;

    public AuditService() { this(null); }

    @Autowired
    public AuditService(AuditMapper mapper) {
        this.mapper = mapper;
    }


    /**
     * 写一条审计（失败只告警，不影响主流程）
     */
    public void record(AuditLog entry) {
        try {
            mapper.insert(Timestamp.valueOf(entry.ts()), entry.tenantId(), entry.userId(), entry.groups(),
                    entry.action(), entry.resource(), entry.detail(), entry.ip(),
                    entry.status(), entry.durationMs(), entry.result());
        } catch (Exception e) {
            log.warn("审计写入失败：{}", e.getMessage());
        }
    }

    /**
     * 便捷构造（供 Filter 和 Controller 埋点）
     */
    public static AuditLog of(String tenant, String user, String groups, String action, String resource,
                              String detail, String ip, int status, long durationMs, String result) {
        return new AuditLog(java.time.LocalDateTime.now(), tenant, user, groups, action,
                resource, detail, ip, status, durationMs, result);
    }

    /**
     * 登录日志：直接在 SQL 层按登录/刷新动作过滤，避免高频业务日志把登录记录挤出最近 N 条
     */
    public List<Map<String, Object>> loginLogs(String tenant, int limit) {
        try {
            return mapper.loginLogs(tenant, Math.min(Math.max(limit, 1), 10000));
        } catch (Exception e) {
            log.warn("登录日志查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询审计日志：按当前租户强制过滤，只返回最近 limit 条
     */
    public List<Map<String, Object>> recent(String tenant, int limit) {
        try {
            return mapper.recent(tenant, Math.min(Math.max(limit, 1), 10000));
        } catch (Exception e) {
            log.warn("审计查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询全部审计日志（审计部门专用）：不限租户，只返回最近 limit 条
     */
    public List<Map<String, Object>> recentAll(int limit) {
        try {
            return mapper.recentAll(Math.min(Math.max(limit, 1), 10000));
        } catch (Exception e) {
            log.warn("全量审计查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 按类型导出：login=登录/登出/刷新令牌，operation=其余业务操作；审计部门不限租户
     */
    public List<Map<String, Object>> export(String tenant, boolean all, String type, int limit) {
        try {
            return mapper.export(tenant, all, type, Math.min(Math.max(limit, 1), 5000));
        } catch (Exception e) {
            log.warn("审计导出查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 全量审计统计（审计部门专用）
     */
    public Map<String, Object> statsAll() {
        try {
            return mapper.statsAll();
        } catch (Exception e) {
            log.warn("全量审计统计失败：{}", e.getMessage());
            return Map.of("total", 0L, "denied", 0L, "errors", 0L);
        }
    }

    /**
     * 审计统计：总条数 / 拒绝数 / 错误数
     */
    public Map<String, Object> stats(String tenant) {
        try {
            return mapper.stats(tenant);
        } catch (Exception e) {
            log.warn("审计统计失败：{}", e.getMessage());
            return Map.of("total", 0L, "denied", 0L, "errors", 0L);
        }
    }
}
