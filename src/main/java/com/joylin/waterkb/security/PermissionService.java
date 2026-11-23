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

    /** 功能菜单定义：名称 / 地址 / 需要的权限码（空=所有认证用户） */
    private static final List<Map<String, Object>> MENUS = List.of(
            menu("智能问答", "index.html", List.of()),
            menu("知识库文档", "manage.html", List.of(Permission.DOC_READ)),
            menu("检索对比", "search.html", List.of(Permission.SEARCH_USE)),
            menu("问答分支", "tree.html", List.of(Permission.TREE_USE)),
            menu("模型设置", "models.html", List.of(Permission.MODEL_READ)),
            menu("权限与日志", "governance.html", List.of(Permission.AUDIT_READ)),
            menu("管理后台", "admin.html", List.of(Permission.ADMIN_ACCESS)),
            menu("个人中心", "profile.html", List.of())
    );

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
        for (Map<String, Object> m : MENUS) {
            @SuppressWarnings("unchecked")
            List<String> need = (List<String>) m.get("permissions");
            boolean allowed = need.isEmpty() || need.stream().anyMatch(owned::contains);
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
        result.put("permissions", owned);          // 前端按权限码控制入口显示
        // 全量权限码 = 内置权限 + 权限目录中的自定义节点（供权限编辑器勾选）
        List<String> allPerms = new ArrayList<>(Permission.ALL);
        try {
            for (Map<String, Object> n : permCatalog.list()) {
                String code = String.valueOf(n.get("code"));
                if (!allPerms.contains(code)) allPerms.add(code);
            }
        } catch (Exception ignored) {
            // 目录读取失败时退化为内置权限码
        }
        result.put("allPermissions", allPerms);
        result.put("roleMatrix", rolePermissions.roleMatrix());
        result.put("menus", menus);
        result.put("data", data);
        result.put("tools", tools);
        return result;
    }
}
