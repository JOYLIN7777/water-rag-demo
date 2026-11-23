package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限码映射（企业级 RBAC 的唯一判定处）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>admin 是超级角色</b>：代码层面恒为全部权限，后台不可修改、不可删除——
 *       这是安全底线，防止管理员把自己锁在门外；</li>
 *   <li><b>权限可配置</b>：内置角色（analyst/viewer）可在后台调整权限，也可新增自定义角色，
 *       全部持久化到 role_permissions 表，修改实时生效（内存缓存，写时失效重建）；</li>
 *   <li>数据库没有记录的角色回落内置默认——未做过配置的系统行为与之前完全一致；</li>
 *   <li>一个用户可属于多个角色，权限取并集；匿名用户没有任何权限（默认拒绝）。</li>
 * </ul></p>
 */
@Service
public class RolePermissions {

    /** 管理员：所有权限，包括管理后台、模型配置、审计、用量、用户管理 */
    private static final Set<String> ADMIN = Set.copyOf(Permission.ALL);

    /** 分析员内置默认：日常问答与文档操作，可看用量，但不能改模型、看审计、进管理后台 */
    private static final Set<String> ANALYST = Set.of(
            Permission.CHAT_USE, Permission.DOC_READ, Permission.DOC_WRITE,
            Permission.DOC_DELETE, Permission.SEARCH_USE, Permission.TREE_USE,
            Permission.USAGE_READ);

    /** 只读用户内置默认：只能问答、看文档、做检索对比，不能上传或删除 */
    private static final Set<String> VIEWER = Set.of(
            Permission.CHAT_USE, Permission.DOC_READ, Permission.SEARCH_USE, Permission.TREE_USE);

    /** 内置默认（角色名与登录配置里的 groups 保持一致），DB 有配置的角色会覆盖同名的默认 */
    private static final Map<String, Set<String>> BUILT_IN = Map.of(
            "admin", ADMIN,
            "analyst", ANALYST,
            "viewer", VIEWER);

    /** 可为 null：单测与无数据库环境只用内置默认 */
    private final JdbcTemplate jdbc;

    /**
     * 运行期角色权限表（内置默认 + DB 覆盖 + 自定义角色合并后的最终视图）。
     * volatile 保证拦截线程读到最新快照；写操作在事务提交后整体替换。
     */
    private volatile Map<String, Set<String>> effective = BUILT_IN;

    public RolePermissions() { this.jdbc = null; }

    @Autowired
    public RolePermissions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void init() {
        if (jdbc == null) return;
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS role_permissions ("
                    + "role VARCHAR(64) NOT NULL, permission VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY (role, permission))");
            refresh();
        } catch (Exception e) {
            // 持久化表不可用时退化为纯内置默认，权限校验照常工作
        }
    }

    /** 从数据库重建最终视图：DB 有记录的角色【整体替换】内置默认（后台勾选清单即全部权限），无记录的回落内置默认 */
    private void refresh() {
        Map<String, Set<String>> fromDb = new LinkedHashMap<>();
        jdbc.query("SELECT role, permission FROM role_permissions ORDER BY role, permission", rs -> {
            fromDb.computeIfAbsent(rs.getString("role").trim(), k -> new LinkedHashSet<>())
                    .add(rs.getString("permission").trim());
        });
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        BUILT_IN.forEach((k, v) -> merged.put(k, new LinkedHashSet<>(v)));
        fromDb.forEach(merged::put);
        effective = Map.copyOf(merged);
    }

    /** 获取某用户拥有的全部权限码（只读视图） */
    public Set<String> permissionsOf(UserContext.CurrentUser user) {
        if (user == null || user.isAnonymous()) return Set.of();
        if (user.hasGroup("admin")) return ADMIN;
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : effective.entrySet()) {
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

    /** 供前端展示：系统中所有角色及其权限 */
    public List<Map<String, Object>> roleMatrix() {
        List<Map<String, Object>> out = new ArrayList<>();
        effective.forEach((role, perms) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("permissions", role.equals("admin") ? Permission.ALL : List.copyOf(perms));
            m.put("builtin", BUILT_IN.containsKey(role));
            out.add(m);
        });
        return out;
    }

    /**
     * 设置角色权限（后台编辑保存）。admin 恒为全权限不可改；权限码必须全部合法。
     * 写库成功后重建内存视图，下一次接口调用立即生效，无需重启。
     */
    public void setPermissions(String role, List<String> permissions) {
        String name = normalize(role);
        if (name.isEmpty()) throw new IllegalArgumentException("角色名不能为空");
        if (name.equals("admin")) throw new IllegalArgumentException("管理员角色拥有全部权限，不可修改");
        if (permissions == null || permissions.stream().anyMatch(p -> !Permission.ALL.contains(p))) {
            throw new IllegalArgumentException("存在不合法的权限码");
        }
        if (jdbc == null) throw new IllegalStateException("当前环境无数据库，角色权限不可配置");
        jdbc.update("DELETE FROM role_permissions WHERE role=?", name);
        for (String p : new LinkedHashSet<>(permissions)) {
            jdbc.update("INSERT INTO role_permissions(role,permission) VALUES(?,?)", name, p);
        }
        refresh();
    }

    /** 删除角色定义：admin 不可删；DB 中没有记录的角色无需删除（内置默认不可移除） */
    public boolean deleteRole(String role) {
        String name = normalize(role);
        if (name.equals("admin")) throw new IllegalArgumentException("管理员角色不可删除");
        if (jdbc == null) throw new IllegalStateException("当前环境无数据库，角色权限不可配置");
        int rows = jdbc.update("DELETE FROM role_permissions WHERE role=?", name);
        refresh();
        return rows > 0;
    }

    private static String normalize(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}
