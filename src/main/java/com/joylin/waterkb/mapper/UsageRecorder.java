package com.joylin.waterkb.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模型用量记录：每次真实调用对话模型后落一条记录。
 *
 *
 */
@Service
public class UsageRecorder {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final UsageMapper mapper;

    public UsageRecorder() { this(null); }

    @Autowired
    public UsageRecorder(UsageMapper mapper) { this.mapper = mapper; }


    /**


     * 记录一次真实模型调用；任何失败都静默吞掉，绝不影响问答主链路


     */
    public void record(String userId, String model, long promptTokens, long completionTokens, long durationMs) {
        record(userId, model, promptTokens, completionTokens, durationMs, null, null);
    }

    /**
     * 记录一次真实模型调用，并带上会话与提问原文。
     *
     */
    public void record(String userId, String model, long promptTokens, long completionTokens,
                       long durationMs, String sessionId, String question) {
        record(userId, model, promptTokens, completionTokens, durationMs, sessionId, question, "default");
    }

    /**

     * tenantId 参与写入，查询时才能按部门过滤（admin 传 null 表示看全库）

     */
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
     */
    public List<Map<String, Object>> records(String scope, int limit) {
        return records(scope, limit, null);
    }

    /**

     * 只看某个账号的明细（非 admin 用）

     */
    public List<Map<String, Object>> recordsOfUser(String scope, int limit, String user) {
        return records(scope, limit, null, user);
    }

    /**

     * tenant 非空时只返回本部门明细；admin 传 null 看全库

     */
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

    /**

     * 只看某个账号的汇总（非 admin 用）

     */
    public Map<String, Object> summaryOfUser(String user) {
        return summary(null, user);
    }

    /**

     * tenant 非空时只统计本部门；admin 传 null 看全库

     */
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
