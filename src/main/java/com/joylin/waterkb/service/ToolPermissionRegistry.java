package com.joylin.waterkb.service;

import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.service.RolePermissions;
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
 *   <li><b>还必须有对应的功能权限码</b>（实时水情查询要求 chat:use）——
 *       工具是问答能力的延伸，后台撤掉某角色的问答权限后，工具同步失效，
 *       不必再维护第二套用户组白名单；</li>
 *   <li>匿名一律拒绝。</li>
 * </ul></p>
 */
@Component
public class ToolPermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionRegistry.class);
    public static final String TOOL_WATER_QUERY = "queryRealtimeWater";

    /** 允许使用工具的问答角色；最终还要过功能权限码校验（见 denyReason） */
    private final Map<String, Rule> rules = Map.of(
            TOOL_WATER_QUERY, new Rule(
                    Set.of(),  // 不再维护用户白名单，统一走权限码
                    Set.of(),  // 不再维护角色组白名单
                    Set.of(),  // 不限租户
                    "查询实时水情（含水位/流量，属敏感运行数据）",
                    Permission.TOOL_WATER_QUERY));

    private final RolePermissions rolePermissions;

    /** 单测用：不接数据库，只按内置角色默认权限判定 */
    public ToolPermissionRegistry() { this(new RolePermissions()); }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolPermissionRegistry(RolePermissions rolePermissions) {
        this.rolePermissions = rolePermissions;
    }

    /** 校验当前用户是否有权调用工具；拒绝返回可读原因，放行返回 null。 */
    public String denyReason(String tool) {
        UserContext.CurrentUser user = UserContext.get();
        if (user.isAnonymous()) return "未认证用户不得调用工具";

        Rule rule = rules.get(tool);
        if (rule == null) return "工具未注册或已禁用：" + tool;
        // 唯一门槛：工具专属权限码。取消旧的 hasGroup("admin") 短路与野组名白名单，
        // 管理员要能调工具，也在角色权限里配 tool:water_query（admin 默认全权限天然包含）。
        Set<String> owned = rolePermissions.permissionsOf(user);
        if (!owned.contains(rule.requiredPermission)) {
            String reason = "当前账号未开通实时水情查询权限，请联系管理员开通（功能权限：" + rule.requiredPermission + "）";
            log.warn("工具权限拒绝：用户 {} 缺少 {}", user.userId(), rule.requiredPermission);
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
                        Set<String> allowTenants, String description,
                        String requiredPermission) {}
}
