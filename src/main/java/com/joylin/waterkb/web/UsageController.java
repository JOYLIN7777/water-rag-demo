package com.joylin.waterkb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.usage.UsageRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计：对接阿里云百炼官方 /api/v1/agentstudio/usage/summary 接口。
 *
 * <p>关键说明：
 * <ul>
 *   <li>该接口属于百炼 Managed Agents API，endpoint 形如
 *       https://{workspace_id}.cn-beijing.maas.aliyuncs.com/api/v1/agentstudio，
 *       需要工作空间 ID，通过环境变量 BAILIAN_WORKSPACE_ID 注入；</li>
 *   <li>API Key 只从环境变量 AI_API_KEY 读取，绝不写进代码、配置或日志；</li>
 *   <li>未配置工作空间 ID 或接口不可用时返回 configured=false 与明确提示，
 *       前端优雅降级，不编造用量数据。</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/usage")
@RequirePermission(Permission.USAGE_READ)
public class UsageController {

    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String apiKey;
    private final String workspaceId;
    private final String region;
    private final UsageRecorder recorder;

    public UsageController(ObjectMapper mapper,
                           @Value("${usage.bailian.workspace-id:${BAILIAN_WORKSPACE_ID:}}") String workspaceId,
                           @Value("${usage.bailian.region:cn-beijing}") String region,
                           UsageRecorder recorder) {
        this.mapper = mapper;
        this.apiKey = System.getenv("AI_API_KEY");
        this.workspaceId = workspaceId == null ? "" : workspaceId.trim();
        this.region = region == null || region.isBlank() ? "cn-beijing" : region.trim();
        this.recorder = recorder;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 今日 / 本月请求次数与 Token 消耗，以及按模型明细。
     *
     * <p>统计口径以<b>本系统真实调用记录</b>为主：本系统走 DashScope 兼容模式直连，
     * 官方 agentstudio 用量接口统计的是工作空间内的 Agent 调用，实测对兼容模式调用
     * 返回 0。因此官方数据仅在确有数值时才作为对账参考覆盖展示，否则一律以本地记录
     * 为准，保证页面任何情况都有真实数据。</p>
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        // 用量明细里有提问原文，普通角色只能看本部门；admin 看全库
        String scopeTenant = UserContext.get().hasGroup("admin") ? null : UserContext.get().tenantId();
        Map<String, Object> local = recorder.summary(scopeTenant);
        // 未配置官方工作空间：直接用本地真实调用记录
        if (workspaceId.isBlank() || apiKey == null || apiKey.isBlank()) {
            return local;
        }

        try {
            LocalDate today = LocalDate.now(ZONE);
            JsonNode day = call(range(today.atStartOfDay(ZONE), today.plusDays(1).atStartOfDay(ZONE), "day"));
            JsonNode month = call(range(today.withDayOfMonth(1).atStartOfDay(ZONE),
                    today.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZONE), "month"));

            Map<String, Object> dayTotals = new LinkedHashMap<>();
            accumulate(dayTotals, day);
            Map<String, Object> monthTotals = new LinkedHashMap<>();
            accumulate(monthTotals, month);
            long officialMonthRequests = asLong(monthTotals.get("requests"));

            // 官方有数据才用官方口径（工作空间级，含控制台等全部调用，适合与阿里云账单对账）
            if (officialMonthRequests > 0) {
                Map<String, Object> totals = new LinkedHashMap<>();
                totals.put("todayRequests", dayTotals.get("requests"));
                totals.put("todayTokens", dayTotals.get("tokens"));
                totals.put("monthRequests", monthTotals.get("requests"));
                totals.put("monthTokens", monthTotals.get("tokens"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("totals", totals);
                result.put("models", modelRows(month));
                result.put("configured", true);
                result.put("localTotals", local.get("totals"));   // 本系统口径供对照
                result.put("hint", "数据来自阿里云百炼官方用量（工作空间级，含全部来源，可能有分钟级延迟）；本系统的真实调用记录见上方对照。");
                return result;
            }

            // 官方返回 0：口径不含兼容模式直连调用，以本地记录为准并说明原因
            local.put("hint", "阿里云百炼官方用量当前为 0（其口径只统计工作空间内的 Agent 调用，不含本系统的兼容模式直连调用），以下为本系统的真实调用记录。");
            return local;
        } catch (Exception e) {
            // 官方接口异常时降级为本地记录，避免整页空白
            local.put("hint", "官方用量暂时不可用（" + safeMessage(e) + "），已切换为本系统调用记录。");
            return local;
        }
    }

    /**
     * 逐次调用明细：把「今日/本月消耗」钻取到每一次问答。
     *
     * <p>只对本系统自己的调用记录可用——官方接口只给汇总，没有单次粒度。
     * 官方口径命中时前端不给钻取入口，避免点了看不到东西。</p>
     */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(defaultValue = "today") String scope,
                                       @RequestParam(defaultValue = "200") int limit) {
        String scopeTenant = UserContext.get().hasGroup("admin") ? null : UserContext.get().tenantId();
        List<Map<String, Object>> rows = recorder.records(scope, limit, scopeTenant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "month".equalsIgnoreCase(scope) ? "month" : "today");
        result.put("records", rows);
        result.put("count", rows.size());
        result.put("source", "local");
        return result;
    }

    private long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }

    /** 构造官方接口要求的 RFC3339 时间范围请求体 */
    private Map<String, Object> range(java.time.ZonedDateTime start, java.time.ZonedDateTime end, String granularity) {
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("start_time", start.format(RFC3339));
        tr.put("end_time", end.format(RFC3339));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dimension", "workspace");
        body.put("time_range", tr);
        body.put("granularity", granularity);
        return body;
    }

    /** 调用官方接口并取出 data.usage 节点 */
    private JsonNode call(Map<String, Object> body) throws Exception {
        String url = "https://" + workspaceId + "." + region + ".maas.aliyuncs.com/api/v1/agentstudio/usage/summary";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        return root.path("data").path("usage");
    }

    /** 从 usage.total 提取请求次数与总 Token，写入目标 Map */
    private void accumulate(Map<String, Object> out, JsonNode usage) {
        JsonNode total = usage.path("total");
        out.put("requests", value(total, "model_call_count"));
        out.put("tokens", value(total, "total_tokens"));
    }

    private long value(JsonNode node, String field) {
        JsonNode v = node.path(field).path("value");
        return v.isNumber() ? v.asLong() : 0L;
    }

    /** 按模型汇总明细 */
    private List<Map<String, Object>> modelRows(JsonNode usage) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode models = usage.path("models");
        if (!models.isArray()) return rows;
        for (JsonNode m : models) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", m.path("model_name").asText("未知模型"));
            JsonNode u = m.path("usage");
            row.put("requests", value(u, "call_count"));
            row.put("inputTokens", value(u, "input_tokens"));
            row.put("outputTokens", value(u, "output_tokens"));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("requests"), (long) a.get("requests")));
        return rows;
    }

    /** 错误信息只回传简短原因，避免把 API Key 或响应正文泄露到前端 */
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "接口调用失败";
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}
