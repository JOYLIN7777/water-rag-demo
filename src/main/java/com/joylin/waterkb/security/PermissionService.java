package com.joylin.waterkb.security;

import com.joylin.waterkb.tool.ToolPermissionRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限聚合服务：把散落在各处的权限规则聚合成一份"当前用户能做什么"的视图，
 * 供企业治理中心与个人中心可视化展示。
 *
 * <p>权限分三层，均为实时读取 UserContext（调用方无法伪造）：
 * <ol>
 *   <li><b>功能权限</b>：菜单/页面访问，按用户组决定；admin 组拥有全部；</li>
 *   <li><b>数据权限</b>：租户隔离 + 文档行级权限（owner / 组 / public）；</li>
 *   <li><b>工具权限</b>：Tool Calling 触发真实方法的白名单校验。</li>
 * </ol></p>
 */
@Service
public class PermissionService {

    private final ToolPermissionRegistry toolRegistry;

    /** 功能菜单定义：key / 名称 / 需要的组（空=所有认证用户） */
    private static final List<Map<String, Object>> MENUS = List.of(
            menu("智能问答", "index.html", List.of()),
            menu("知识库文档", "manage.html", List.of()),
            menu("检索实验", "search.html", List.of()),
            menu("模型配置", "models.html", List.of("admin")),
            menu("会话树", "tree.html", List.of()),
            menu("企业治理", "governance.html", List.of("admin")),
            menu("个人中心", "profile.html", List.of())
    );

    public PermissionService(ToolPermissionRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    private static Map<String, Object> menu(String name, String href, List<String> groups) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("href", href); m.put("groups", groups);
        return m;
    }

    /** 聚合当前用户权限视图 */
    public Map<String, Object> currentPermissions() {
        UserContext.CurrentUser u = UserContext.get();
        boolean admin = u.hasGroup("admin");
        boolean anonymous = u.isAnonymous();

        // 功能权限
        List<Map<String, Object>> menus = new ArrayList<>();
        for (Map<String, Object> m : MENUS) {
            @SuppressWarnings("unchecked")
            List<String> need = (List<String>) m.get("groups");
            boolean allowed = anonymous ? need.isEmpty() : (admin || need.isEmpty() || need.stream().anyMatch(u::hasGroup));
            Map<String, Object> item = new LinkedHashMap<>(m);
            item.put("allowed", allowed);
            menus.add(item);
        }

        // 数据权限
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant", u.tenantId());
        data.put("tenantIsolated", true);                 // 全系统强制租户隔离
        data.put("rowLevelScopes", List.of("owner（本人上传）", "allowedGroups（指定组）", "public（公开）"));
        data.put("adminBypass", admin);

        // 工具权限
        List<Map<String, Object>> tools = anonymous ? List.of() : toolRegistry.listForCurrentUser();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", u.userId());
        result.put("groups", u.groups());
        result.put("tenant", u.tenantId());
        result.put("authType", u.authType());
        result.put("anonymous", anonymous);
        result.put("admin", admin);
        result.put("menus", menus);
        result.put("data", data);
        result.put("tools", tools);
        return result;
    }
}
