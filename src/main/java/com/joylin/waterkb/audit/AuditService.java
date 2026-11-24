package com.joylin.waterkb.audit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计日志仓库：PostgreSQL 落库（模型配置同款 JdbcTemplate 方案，零新增依赖）。
 *
 * <p>为什么必须落库而不是只打日志：日志会被滚动清理、无法结构化查询；
 * 审计是合规刚需——谁在什么时候删了知识文档、谁越权调了工具，必须可追溯、
 * 可按租户过滤。审计表本身也带 tenantId，与数据隔离保持一致。</p>
 *
 * <p>注意：审计写入失败绝不能影响主流程——catch 住只告警，
 * 这是所有"辅助能力"的通用原则。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                  id BIGSERIAL PRIMARY KEY,
                  ts TIMESTAMP NOT NULL,
                  tenant_id VARCHAR(100) NOT NULL,
                  user_id VARCHAR(100) NOT NULL,
                  groups TEXT,
                  action VARCHAR(200) NOT NULL,
                  resource VARCHAR(500),
                  detail TEXT,
                  ip VARCHAR(100),
                  status INT,
                  duration_ms BIGINT,
                  result VARCHAR(20)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_tenant_ts ON audit_log(tenant_id, ts DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id, ts DESC)");
            log.info("审计表 audit_log 已就绪");
        } catch (Exception e) {
            log.error("审计表初始化失败，审计功能不可用：{}", e.getMessage());
        }
    }

    /** 写一条审计（失败只告警，不影响主流程） */
    public void record(AuditLog entry) {
        try {
            jdbc.update("""
                INSERT INTO audit_log(ts,tenant_id,user_id,groups,action,resource,detail,ip,status,duration_ms,result)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,
                    Timestamp.valueOf(entry.ts()),
                    entry.tenantId(), entry.userId(), entry.groups(),
                    entry.action(), entry.resource(), entry.detail(), entry.ip(),
                    entry.status(), entry.durationMs(), entry.result());
        } catch (Exception e) {
            log.warn("审计写入失败：{}", e.getMessage());
        }
    }

    /** 便捷构造（供 Filter 和 Controller 埋点） */
    public static AuditLog of(String tenant, String user, String groups, String action, String resource,
                              String detail, String ip, int status, long durationMs, String result) {
        return new AuditLog(java.time.LocalDateTime.now(), tenant, user, groups, action,
                resource, detail, ip, status, durationMs, result);
    }

    /** 查询审计日志：按当前租户强制过滤，只返回最近 limit 条 */
    public List<Map<String, Object>> recent(String tenant, int limit) {
        try {
            return jdbc.queryForList("""
                SELECT ts,tenant_id,user_id,groups,action,resource,detail,ip,status,duration_ms,result
                FROM audit_log WHERE tenant_id=? ORDER BY ts DESC LIMIT ?
                """, tenant, Math.min(Math.max(limit, 1), 500));
        } catch (Exception e) {
            log.warn("审计查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 查询全部审计日志（审计部门专用）：不限租户，只返回最近 limit 条 */
    public List<Map<String, Object>> recentAll(int limit) {
        try {
            return jdbc.queryForList("""
                SELECT ts,tenant_id,user_id,groups,action,resource,detail,ip,status,duration_ms,result
                FROM audit_log ORDER BY ts DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
        } catch (Exception e) {
            log.warn("全量审计查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 全量审计统计（审计部门专用） */
    public Map<String, Object> statsAll() {
        try {
            return jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE result='DENIED') AS denied,
                       COUNT(*) FILTER (WHERE result='ERROR') AS errors
                FROM audit_log
                """);
        } catch (Exception e) {
            log.warn("全量审计统计失败：{}", e.getMessage());
            return Map.of("total", 0L, "denied", 0L, "errors", 0L);
        }
    }

    /** 审计统计：总条数 / 拒绝数 / 错误数 */
    public Map<String, Object> stats(String tenant) {
        try {
            return jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE result='DENIED') AS denied,
                       COUNT(*) FILTER (WHERE result='ERROR') AS errors
                FROM audit_log WHERE tenant_id=?
                """, tenant);
        } catch (Exception e) {
            log.warn("审计统计失败：{}", e.getMessage());
            return Map.of("total", 0L, "denied", 0L, "errors", 0L);
        }
    }
}
