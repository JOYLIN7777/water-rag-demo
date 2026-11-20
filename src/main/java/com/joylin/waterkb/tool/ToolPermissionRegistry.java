package com.joylin.waterkb.tool;

import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具权限注册表：模型想调工具，先过这道门。
 *
 * <p>为什么必须做：Tool Calling 让大模型能触发真实副作用（查库、发通知、改数据），
 * 如果只靠 prompt 约束"别让某人调"，等于没有约束。企业平台必须在执行层强制校验，
 * 且拒绝要看得见——这里是整个 agent 工具权限治理的单一入口。</p>
 *
 * <p>规则模型（默认拒绝）：
 * <ul>
 *   <li>每个工具配 allowUsers / allowGroups / allowTenants 三组白名单；</li>
 *   <li>命中任一组即放行；三组都空=仅管理员可用（默认拒绝）；</li>
 *   <li>带 "admin" 组的用户绕过白名单（平台管理员）；</li>
 *   <li>匿名一律拒绝。</li>
 * </ul></p>
 */
@Component
public class ToolPermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionRegistry.class);
    public static final String TOOL_WATER_QUERY = "queryRealtimeWater";

    /** 演示规则：水情查询仅 water-dept 组、hydro-admin 用户可用（可按租户细化） */
    private final Map<String, Rule> rules = Map.of(
            TOOL_WATER_QUERY, new Rule(
                    Set.of("hydro-admin"),
                    Set.of("water-dept"),
                    Set.of(), // 不限租户
                    "查询实时水情（含水位/流量，属敏感运行数据）"));

    /** 校验当前用户是否有权调用工具；拒绝返回可读原因，放行返回 null。 */
    public String denyReason(String tool) {
        UserContext.CurrentUser user = UserContext.get();
        if (user.isAnonymous()) return "未认证用户不得调用工具";

        Rule rule = rules.get(tool);
        if (rule == null) return "工具未注册或已禁用：" + tool;
        if (user.hasGroup("admin")) return null; // 平台管理员对已注册工具全局放行

        boolean ok = rule.allowUsers.contains(user.userId())
                || rule.allowGroups.stream().anyMatch(user::hasGroup)
                || (!rule.allowTenants.isEmpty() && rule.allowTenants.contains(user.tenantId()));
        if (!ok) {
            String reason = "无权调用工具 " + tool + "（需要用户 " + rule.allowUsers
                    + " 或组 " + rule.allowGroups + "，当前用户 " + user.userId()
                    + " 组 " + user.groups() + "）";
            log.warn("工具权限拒绝：{}", reason);
            return reason;
        }
        return null;
    }

    /** 供前端展示工具清单 + 当前用户是否有权 */
    public List<Map<String, Object>> listForCurrentUser() {
        UserContext.CurrentUser user = UserContext.get();
        return rules.entrySet().stream().map(e -> Map.<String, Object>of(
                "tool", e.getKey(),
                "description", e.getValue().description,
                "allowed", denyReason(e.getKey()) == null,
                "allowUsers", e.getValue().allowUsers,
                "allowGroups", e.getValue().allowGroups)).toList();
    }

    private record Rule(Set<String> allowUsers, Set<String> allowGroups,
                        Set<String> allowTenants, String description) {}
}
