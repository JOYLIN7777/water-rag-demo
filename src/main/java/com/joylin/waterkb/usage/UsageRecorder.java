package com.joylin.waterkb.usage;

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

    public UsageRecorder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_model_usage_ts ON model_usage(ts DESC)");
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
        try {
            jdbc.update("INSERT INTO model_usage(user_id,model,prompt_tokens,completion_tokens,duration_ms,"
                            + "session_id,question) VALUES(?,?,?,?,?,?,?)",
                    userId == null ? "anonymous" : userId,
                    model == null || model.isBlank() ? "unknown" : model,
                    Math.max(0, promptTokens), Math.max(0, completionTokens), Math.max(0, durationMs),
                    sessionId == null ? "" : sessionId,
                    question == null ? "" : question);
        } catch (Exception ignored) { }
    }

    /**
     * 逐次调用明细（用量页钻取用）。
     *
     * @param scope today=今天 0 点起；month=本月 1 日 0 点起
     * @param limit 最多返回条数（默认 200，上限 500）
     */
    public List<Map<String, Object>> records(String scope, int limit) {
        int cap = Math.min(Math.max(limit <= 0 ? 200 : limit, 1), 500);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = "month".equalsIgnoreCase(scope) ? today.withDayOfMonth(1) : today;
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(from.atStartOfDay(ZONE).toLocalDateTime());
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            jdbc.query("SELECT ts,user_id,model,prompt_tokens,completion_tokens,duration_ms,session_id,question "
                            + "FROM model_usage WHERE ts >= ? ORDER BY ts DESC LIMIT ?", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts", rs.getTimestamp("ts") == null ? null : rs.getTimestamp("ts").toLocalDateTime().toString());
                row.put("user", rs.getString("user_id"));
                row.put("model", rs.getString("model"));
                row.put("promptTokens", rs.getLong("prompt_tokens"));
                row.put("completionTokens", rs.getLong("completion_tokens"));
                row.put("totalTokens", rs.getLong("prompt_tokens") + rs.getLong("completion_tokens"));
                row.put("durationMs", rs.getLong("duration_ms"));
                row.put("sessionId", rs.getString("session_id"));
                row.put("question", rs.getString("question"));
                out.add(row);
            }, start, cap);
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 本地聚合：今日/本月请求次数与 Token 消耗 + 按模型明细。
     * 返回结构与百炼官方 summary 一致，前端无需区分数据来源。
     */
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now(ZONE);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("todayRequests", count("ts >= ?", java.sql.Timestamp.valueOf(today.atStartOfDay(ZONE).toLocalDateTime())));
        totals.put("todayTokens", tokens("ts >= ?", java.sql.Timestamp.valueOf(today.atStartOfDay(ZONE).toLocalDateTime())));
        java.sql.Timestamp monthStart = java.sql.Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay(ZONE).toLocalDateTime());
        totals.put("monthRequests", count("ts >= ?", monthStart));
        totals.put("monthTokens", tokens("ts >= ?", monthStart));

        List<Map<String, Object>> models = new ArrayList<>();
        try {
            jdbc.query("SELECT model, COUNT(*) AS reqs, COALESCE(SUM(prompt_tokens),0) AS pt, "
                            + "COALESCE(SUM(completion_tokens),0) AS ct FROM model_usage "
                            + "WHERE ts >= ? GROUP BY model ORDER BY reqs DESC", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("model", rs.getString("model"));
                row.put("requests", rs.getLong("reqs"));
                row.put("inputTokens", rs.getLong("pt"));
                row.put("outputTokens", rs.getLong("ct"));
                models.add(row);
            }, monthStart);
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

    private long count(String where, Object arg) {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM model_usage WHERE " + where, Long.class, arg);
            return n == null ? 0 : n;
        } catch (Exception e) { return 0; }
    }

    private long tokens(String where, Object arg) {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(prompt_tokens+completion_tokens),0) FROM model_usage WHERE " + where,
                    Long.class, arg);
            return n == null ? 0 : n;
        } catch (Exception e) { return 0; }
    }
}
