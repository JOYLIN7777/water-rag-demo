package com.joylin.waterkb.service;

import com.joylin.waterkb.service.AuditService;
import com.joylin.waterkb.security.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.joylin.waterkb.audit.AuditFilter;

/**
 * Tool Calling 示例：实时水情查询工具（mock 数据）。
 *
 * <p>设计决策：
 * <ul>
 *   <li>用 @Tool 注解向模型声明工具语义——何时调用完全由模型自主决策，
 *       这是 Tool Calling 的核心：模型输出"工具调用意图"，Spring AI 框架
 *       负责执行方法并把结果回填给模型继续生成；</li>
 *   <li>description 写清"什么场景该调用我"很关键，模型是靠这段描述判断
 *       是否调用的，写得越具体误触发越少；</li>
 *   <li>返回 JSON 字符串便于模型解析字段。真实场景里这里应替换为
 *       水文数据库查询或第三方水情接口调用。</li>
 * </ul></p>
 */
@Component
public class WaterInfoTool {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolPermissionRegistry permissions;
    private final AuditService audit;

    public WaterInfoTool(ToolPermissionRegistry permissions, AuditService audit) {
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 各站点水位/流量的基准值（模拟实时报值 = 基准值 + 随机抖动） */
    private static final Map<String, double[]> BASE = Map.of(
            "云溪水库", new double[]{45.10, 1200.0},
            "东风水库", new double[]{28.35, 320.0},
            "红旗闸站", new double[]{12.80, 95.0}
    );

    /**
     * 查询实时水情。工具方法本体，由大模型在需要时自动调用。
     *
     * @param station 站点名称
     * @return JSON 格式的水情数据
     */
    @Tool(description = "查询指定站点（水库/河道/闸站）的实时水情，包含水位（米）、流量（立方米每秒）与数据时间。" +
            "当用户询问实时水位、实时流量、当前水情、汛情动态等知识库无法提供的动态信息时调用本工具")
    public String queryRealtimeWater(
            @ToolParam(description = "站点名称，例如：云溪水库") String station) {

        // 权限守卫：Tool Calling 触发的是真实方法，必须在执行前强制校验。
        // 返回结构化拒绝结果给模型，模型会据此告诉用户"无权限"，而不是静默失败。
        String denied = permissions.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY);
        if (denied != null) {
            // HTTP 层面工具调用仍返回 200（结构化错误给模型），AuditFilter 判不出拒绝，
            // 必须在这里显式记审计，否则越权调用在审计里显示为"成功"。
            UserContext.CurrentUser u = UserContext.get();
            audit.record(AuditService.of(u.tenantId(), u.userId(), String.join(",", u.groups()),
                    "TOOL_DENIED " + ToolPermissionRegistry.TOOL_WATER_QUERY,
                    station, denied, "", 403, 0, "DENIED"));
            // 给模型的是用户视角提示，不再把权限码、白名单等技术细节带到回答里；
            // 详细原因只进审计日志，排障时查审计即可。
            return "{\"error\":\"PERMISSION_DENIED\",\"message\":\"当前账号未开通实时水情查询权限，请联系管理员开通\"}";
        }

        // 未收录站点给一组中性默认值，避免模型拿到 null 后胡编
        UserContext.CurrentUser u = UserContext.get();
        audit.record(AuditService.of(u.tenantId(), u.userId(), String.join(",", u.groups()),
                "TOOL_CALL " + ToolPermissionRegistry.TOOL_WATER_QUERY,
                station, "查询 " + station + " 实时水情", "", 200, 0, "SUCCESS"));

        double[] base = BASE.getOrDefault(station, new double[]{20.00, 150.0});
        double level = round1(base[0] + ThreadLocalRandom.current().nextDouble(-0.3, 0.3));
        double flow = round1(base[1] * (1 + ThreadLocalRandom.current().nextDouble(-0.10, 0.10)));
        String now = LocalDateTime.now().format(TS_FMT);

        return "{\"station\":\"" + station + "\","
                + "\"waterLevel\":" + level + ","
                + "\"flow\":" + flow + ","
                + "\"unitLevel\":\"m\",\"unitFlow\":\"m³/s\","
                + "\"time\":\"" + now + "\","
                + "\"source\":\"模拟数据（mock）\"}";
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
