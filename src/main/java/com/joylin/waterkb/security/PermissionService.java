package com.joylin.waterkb.security;

import com.joylin.waterkb.tool.ToolPermissionRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final RolePermissions rolePermissions;
    private final PermissionCatalog permCatalog;

    /**
     * 功能菜单：从权限目录（数据库）读取 node_type=menu 且配置了路由的节点，
     * 改菜单只改库/种子 JSON，不再改 Java 常量。个人中心不需要权限码，单独保留。
     */
    private List<Map<String, Object>> menus() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> n : permCatalog.list()) {
                String type = String.valueOf(n.getOrDefault("nodeType", ""));
                String route = String.valueOf(n.getOrDefault("routePath", ""));
                if (!"menu".equals(type) || route.isEmpty() || "null".equals(route)) continue;
                String code = String.valueOf(n.getOrDefault("code", ""));
                // 个人中心对所有已登录用户开放，不参与权限码判定
                List<String> required = (!code.isEmpty() && !"null".equals(code) && !"profile:read".equals(code))
                        ? List.of(code) : List.of();
                Map<String, Object> item = menu(String.valueOf(n.getOrDefault("name", code)), route, required);
                String parent = String.valueOf(n.getOrDefault("parentCode", ""));
                String icon = String.valueOf(n.getOrDefault("icon", ""));
                item.put("group", "null".equals(parent) ? "" : parent);
                item.put("icon", "null".equals(icon) ? "" : icon);
                out.add(item);
            }
        } catch (Exception ignored) {
            // 目录不可用时返回空菜单，不编造
        }
        // 个人中心已在 perm-nodes.json 里作为菜单节点，不再代码追加
        return out;
    }

    public PermissionService(ToolPermissionRegistry toolRegistry, RolePermissions rolePermissions,
                             PermissionCatalog permCatalog) {
        this.toolRegistry = toolRegistry;
        this.rolePermissions = rolePermissions;
        this.permCatalog = permCatalog;
    }

    private static Map<String, Object> menu(String name, String href, List<String> permissions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("href", href); m.put("permissions", permissions);
        return m;
    }

    /** 聚合当前用户权限视图 */
    public Map<String, Object> currentPermissions() {
        UserContext.CurrentUser u = UserContext.get();
        boolean admin = u.hasGroup("admin");
        boolean anonymous = u.isAnonymous();

        // 功能权限：按权限码判定，与服务端接口拦截使用同一套规则
        Set<String> owned = rolePermissions.permissionsOf(u);
        List<Map<String, Object>> menus = new ArrayList<>();
        for (Map<String, Object> m : menus()) {
            @SuppressWarnings("unchecked")
            List<String> need = (List<String>) m.get("permissions");
            boolean allowed = need.isEmpty() || need.stream().anyMatch(owned::contains);
            Map<String, Object> item = new LinkedHashMap<>(m);
            item.put("allowed", allowed);
            menus.add(item);
        }

        // 侧栏分组目录（从权限树的 directory 节点读，改分组名只改库）
        List<Map<String, Object>> navGroups = new ArrayList<>();
        try {
            for (Map<String, Object> n : permCatalog.list()) {
                if (!"directory".equals(String.valueOf(n.getOrDefault("nodeType", "")))) continue;
                String code = String.valueOf(n.getOrDefault("code", ""));
                if (code.isEmpty() || "null".equals(code)) continue;
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("code", code);
                g.put("name", n.getOrDefault("name", code));
                g.put("order", n.getOrDefault("orderNum", 0));
                navGroups.add(g);
            }
        } catch (Exception ignored) { }
        navGroups.sort((a, b) -> Integer.compare(
                ((Number) a.get("order")).intValue(), ((Number) b.get("order")).intValue()));

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
        result.put("permissions", owned);          // 前端按权限码控制入口显示
        // 全量权限码 = 权限目录（数据库，含清单自动生成与自定义节点）为准，注解码兜底，
        // 这样后台新增节点后无需改 Java 常量就能下发到前端编辑器
        List<String> allPerms = new ArrayList<>();
        try {
            for (Map<String, Object> n : permCatalog.list()) {
                String code = String.valueOf(n.getOrDefault("code", ""));
                if (!code.isEmpty() && !"null".equals(code) && !allPerms.contains(code)) allPerms.add(code);
            }
        } catch (Exception ignored) {
            // 目录读取失败时退化为内置权限码
        }
        for (String code : Permission.ALL) if (!allPerms.contains(code)) allPerms.add(code);
        result.put("allPermissions", allPerms);
        result.put("roleMatrix", rolePermissions.roleMatrix());
        result.put("menus", menus);
        result.put("navGroups", navGroups);
        result.put("data", data);
        result.put("tools", tools);
        return result;
    }
}
