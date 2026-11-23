package com.joylin.waterkb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public UsageController(ObjectMapper mapper,
                           @Value("${usage.bailian.workspace-id:${BAILIAN_WORKSPACE_ID:}}") String workspaceId,
                           @Value("${usage.bailian.region:cn-beijing}") String region) {
        this.mapper = mapper;
        this.apiKey = System.getenv("AI_API_KEY");
        this.workspaceId = workspaceId == null ? "" : workspaceId.trim();
        this.region = region == null || region.isBlank() ? "cn-beijing" : region.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 今日 / 本月请求次数与 Token 消耗，以及按模型明细 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("todayRequests", 0);
        totals.put("todayTokens", 0);
        totals.put("monthRequests", 0);
        totals.put("monthTokens", 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totals", totals);
        result.put("models", List.of());

        if (workspaceId.isBlank() || apiKey == null || apiKey.isBlank()) {
            result.put("configured", false);
            result.put("hint", "尚未配置工作空间 ID。请设置环境变量 BAILIAN_WORKSPACE_ID 后重启服务，即可查看百炼官方用量。");
            return result;
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
            totals.put("todayRequests", dayTotals.get("requests"));
            totals.put("todayTokens", dayTotals.get("tokens"));
            totals.put("monthRequests", monthTotals.get("requests"));
            totals.put("monthTokens", monthTotals.get("tokens"));

            result.put("models", modelRows(month));
            result.put("configured", true);
            result.put("hint", "数据来自阿里云百炼官方用量接口，按当前工作空间统计，可能有分钟级延迟。");
            return result;
        } catch (Exception e) {
            result.put("configured", false);
            result.put("hint", "暂时无法获取官方用量：" + safeMessage(e) + "。请检查工作空间 ID 与 API Key 权限。");
            return result;
        }
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
