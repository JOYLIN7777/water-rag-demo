package com.joylin.waterkb.security;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色 → 权限码映射（企业级 RBAC 的唯一判定处）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>admin 是超级角色</b>，拥有系统全部权限码，新增权限无需改 admin 配置；</li>
 *   <li>其余角色按最小权限原则显式列出，没列出的权限一律拒绝（默认拒绝）；</li>
 *   <li>一个用户可属于多个角色，权限取并集；</li>
 *   <li>匿名用户没有任何权限，静态页面可见不代表接口可调用。</li>
 * </ul></p>
 */
@Service
public class RolePermissions {

    /** 管理员：所有权限，包括管理后台、模型配置、审计、用量、用户管理 */
    private static final Set<String> ADMIN = Set.copyOf(Permission.ALL);

    /** 分析员：日常问答与文档操作，可看用量，但不能改模型、看审计、进管理后台 */
    private static final Set<String> ANALYST = Set.of(
            Permission.CHAT_USE, Permission.DOC_READ, Permission.DOC_WRITE,
            Permission.DOC_DELETE, Permission.SEARCH_USE, Permission.TREE_USE,
            Permission.USAGE_READ);

    /** 只读用户：只能问答、看文档、做检索对比，不能上传或删除 */
    private static final Set<String> VIEWER = Set.of(
            Permission.CHAT_USE, Permission.DOC_READ, Permission.SEARCH_USE, Permission.TREE_USE);

    /** 角色名 → 权限集合（角色名与登录配置里的 groups 保持一致） */
    private static final java.util.Map<String, Set<String>> ROLES = java.util.Map.of(
            "admin", ADMIN,
            "analyst", ANALYST,
            "viewer", VIEWER);

    /** 获取某用户拥有的全部权限码（只读视图） */
    public Set<String> permissionsOf(UserContext.CurrentUser user) {
        if (user == null || user.isAnonymous()) return Set.of();
        if (user.hasGroup("admin")) return ADMIN;
        Set<String> result = new LinkedHashSet<>();
        for (java.util.Map.Entry<String, Set<String>> e : ROLES.entrySet()) {
            if (user.hasGroup(e.getKey())) result.addAll(e.getValue());
        }
        return Set.copyOf(result);
    }

    /** 是否拥有其中任意一个权限；all=true 时要求全部拥有 */
    public boolean allows(UserContext.CurrentUser user, String[] needed, boolean all) {
        if (needed == null || needed.length == 0) return true;
        if (user == null || user.isAnonymous()) return false;
        if (user.hasGroup("admin")) return true;   // 超级角色直接放行
        Set<String> owned = permissionsOf(user);
        if (all) return List.of(needed).stream().allMatch(owned::contains);
        return List.of(needed).stream().anyMatch(owned::contains);
    }

    /** 供前端展示：系统中所有角色及其权限，admin 显示为全部权限码 */
    public List<java.util.Map<String, Object>> roleMatrix() {
        return ROLES.entrySet().stream().map(e -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("role", e.getKey());
            m.put("permissions", e.getKey().equals("admin") ? Permission.ALL : List.copyOf(e.getValue()));
            return m;
        }).toList();
    }
}
