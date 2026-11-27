package com.joylin.waterkb.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模型用量记录：每次真实调用对话模型后落一条记录。
 *
 * <p>为什么自己记：官方百炼用量接口需要单独的工作空间 ID（BAILIAN_WORKSPACE_ID），
 * 没配置时用量页只能显示引导文案，用户会觉得"没有效果"。本地记录让用量页
 * 在任何部署形态下都有真实数据；配置了官方接口后官方数据优先、本地作为降级。</p>
 *
 * <p>Token 数来自模型响应的 usage 元数据，兼容端点不返回时记 0（请求次数仍然准确）。</p>
 */
@Service
public class UsageRecorder {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final UsageMapper mapper;

    public UsageRecorder(JdbcTemplate jdbc) { this(jdbc, null); }

    @Autowired
    public UsageRecorder(JdbcTemplate jdbc, UsageMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @PostConstruct
    public void init() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS model_usage ("
                    + "id BIGSERIAL PRIMARY KEY, ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "user_id VARCHAR(64), model VARCHAR(128), "
                    + "prompt_tokens BIGINT DEFAULT 0, completion_tokens BIGINT DEFAULT 0, "
                    + "duration_ms BIGINT DEFAULT 0)");
            // 后加的两列：只汇总总量看不出"钱花在哪一次提问上"，明细要能对上问题。
            // 用 ADD COLUMN IF NOT EXISTS 增量升级，不动历史行（老数据 question 为空，明细里显示"—"）。
            jdbc.execute("ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS session_id VARCHAR(128)");
            jdbc.execute("ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS question TEXT");
            // 用量明细里带提问原文，必须按租户隔离，否则 analyst 能翻到其他部门的提问
            jdbc.execute("ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_model_usage_ts ON model_usage(ts DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_model_usage_tenant_ts ON model_usage(tenant_id, ts DESC)");
        } catch (Exception ignored) {
            // 表建不出来（如无 DB）只影响本地用量，主链路不受影响
        }
    }

    /** 记录一次真实模型调用；任何失败都静默吞掉，绝不影响问答主链路 */
    public void record(String userId, String model, long promptTokens, long completionTokens, long durationMs) {
        record(userId, model, promptTokens, completionTokens, durationMs, null, null);
    }

    /**
     * 记录一次真实模型调用，并带上会话与提问原文。
     *
     * <p>带提问是为了让用量页可以"点总量看明细"：只记 token 数的话，
     * 管理员看到今天涨了 30 万 token 却查不出是哪几次问答花的。</p>
     */
    public void record(String userId, String model, long promptTokens, long completionTokens,
                       long durationMs, String sessionId, String question) {
        record(userId, model, promptTokens, completionTokens, durationMs, sessionId, question, "default");
    }

    /** tenantId 参与写入，查询时才能按部门过滤（admin 传 null 表示看全库） */
    public void record(String userId, String model, long promptTokens, long completionTokens,
                       long durationMs, String sessionId, String question, String tenantId) {
        try {
            mapper.insert(
                    userId == null ? "anonymous" : userId,
                    model == null || model.isBlank() ? "unknown" : model,
                    Math.max(0, promptTokens), Math.max(0, completionTokens), Math.max(0, durationMs),
                    sessionId == null ? "" : sessionId,
                    question == null ? "" : question,
                    tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        } catch (Exception ignored) { }
    }

    /**
     * 逐次调用明细（用量页钻取用）。
     *
     * @param scope today=今天 0 点起；month=本月 1 日 0 点起
     * @param limit 最多返回条数（默认 200，上限 500）
     */
    public List<Map<String, Object>> records(String scope, int limit) {
        return records(scope, limit, null);
    }

    /** 只看某个账号的明细（非 admin 用） */
    public List<Map<String, Object>> recordsOfUser(String scope, int limit, String user) {
        return records(scope, limit, null, user);
    }

    /** tenant 非空时只返回本部门明细；admin 传 null 看全库 */
    public List<Map<String, Object>> records(String scope, int limit, String tenant) {
        return records(scope, limit, tenant, null);
    }

    private List<Map<String, Object>> records(String scope, int limit, String tenant, String user) {
        int cap = Math.min(Math.max(limit <= 0 ? 200 : limit, 1), 500);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = "month".equalsIgnoreCase(scope) ? today.withDayOfMonth(1) : today;
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(from.atStartOfDay(ZONE).toLocalDateTime());
        boolean scoped = tenant != null && !tenant.isBlank();
        boolean byUser = user != null && !user.isBlank();
        String where = "ts >= ?" + (scoped ? " AND tenant_id=?" : "") + (byUser ? " AND user_id=?" : "");
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> row : mapper.records(start, scoped ? tenant : null, byUser ? user : null, cap)) {
                Map<String, Object> r = new LinkedHashMap<>();
                java.sql.Timestamp ts = (java.sql.Timestamp) row.get("ts");
                r.put("ts", ts == null ? null : ts.toLocalDateTime().toString());
                r.put("user", row.get("user"));
                r.put("model", row.get("model"));
                long pt = ((Number) row.get("promptTokens")).longValue();
                long ct = ((Number) row.get("completionTokens")).longValue();
                r.put("promptTokens", pt);
                r.put("completionTokens", ct);
                r.put("totalTokens", pt + ct);
                r.put("durationMs", row.get("durationMs"));
                r.put("sessionId", row.get("sessionId"));
                r.put("question", row.get("question"));
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 本地聚合：今日/本月请求次数与 Token 消耗 + 按模型明细。
     * 返回结构与百炼官方 summary 一致，前端无需区分数据来源。
     */
    public Map<String, Object> summary() { return summary(null); }

    /** 只看某个账号的汇总（非 admin 用） */
    public Map<String, Object> summaryOfUser(String user) {
        return summary(null, user);
    }

    /** tenant 非空时只统计本部门；admin 传 null 看全库 */
    public Map<String, Object> summary(String tenant) {
        return summary(tenant, null);
    }

    private Map<String, Object> summary(String tenant, String user) {
        LocalDate today = LocalDate.now(ZONE);
        boolean scoped = tenant != null && !tenant.isBlank();
        boolean byUser = user != null && !user.isBlank();
        String extra = (scoped ? " AND tenant_id=?" : "") + (byUser ? " AND user_id=?" : "");
        String todayWhere = "ts >= ?" + extra;
        java.sql.Timestamp todayStart = java.sql.Timestamp.valueOf(today.atStartOfDay(ZONE).toLocalDateTime());
        Object[] todayArgs = byUser ? new Object[]{todayStart, user} : scoped ? new Object[]{todayStart, tenant} : new Object[]{todayStart};
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("todayRequests", count(todayStart, scoped ? tenant : null, byUser ? user : null));
        totals.put("todayTokens", tokens(todayStart, scoped ? tenant : null, byUser ? user : null));
        java.sql.Timestamp monthStart = java.sql.Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay(ZONE).toLocalDateTime());
        String monthWhere = "ts >= ?" + extra;
        Object[] monthArgs = byUser ? new Object[]{monthStart, user} : scoped ? new Object[]{monthStart, tenant} : new Object[]{monthStart};
        totals.put("monthRequests", count(monthStart, scoped ? tenant : null, byUser ? user : null));
        totals.put("monthTokens", tokens(monthStart, scoped ? tenant : null, byUser ? user : null));

        List<Map<String, Object>> models = new ArrayList<>();
        try {
            for (Map<String, Object> row : mapper.modelRows(monthStart, scoped ? tenant : null, byUser ? user : null)) {
                Map<String, Object> row2 = new LinkedHashMap<>();
                row2.put("model", row.get("model"));
                row2.put("requests", row.get("reqs"));
                row2.put("inputTokens", row.get("pt"));
                row2.put("outputTokens", row.get("ct"));
                models.add(row2);
            }
        } catch (Exception ignored) { }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totals", totals);
        result.put("models", models);
        result.put("configured", true);
        // 本地口径才有逐条明细（官方只给汇总），前端据此决定"点卡片看明细"是否可用
        result.put("localDrill", true);
        result.put("hint", "数据来自本系统的真实调用记录（实时统计）。配置 BAILIAN_WORKSPACE_ID 后可切换为阿里云百炼官方用量。");
        return result;
    }

    private long count(java.sql.Timestamp start, String tenant, String user) {
        try { return mapper.count(start, tenant, user); } catch (Exception e) { return 0; }
    }

    private long tokens(java.sql.Timestamp start, String tenant, String user) {
        try { return mapper.tokens(start, tenant, user); } catch (Exception e) { return 0; }
    }
}
