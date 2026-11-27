package com.joylin.waterkb.service;

import com.joylin.waterkb.mapper.RolePermissionMapper;
import com.joylin.waterkb.mapper.SchemaSeedMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.UserContext;

/**
 * 角色 → 权限码映射（企业级 RBAC 的唯一判定处）。
 *
 */
@Service
public class RolePermissions {

    /**
     * 角色种子从 roles.json 读取（仅首次启动写入 role_definitions / role_permissions），
     * 运行期一律以库为准：后台角色定义页可直接新增角色、改名、改权限，不需要改代码。
     */
    private static final class Seed {
        final String code, name;
        final java.util.Set<String> permissions;
        Seed(String code, String name, java.util.Set<String> permissions) {
            this.code = code; this.name = name; this.permissions = permissions;
        }
    }
    private static java.util.List<Seed> loadSeeds() {
        try (java.io.InputStream in = RolePermissions.class.getClassLoader().getResourceAsStream("roles.json")) {
            if (in == null) throw new IllegalStateException("roles.json 不存在");
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            java.util.List<Seed> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root.path("roles")) {
                java.util.Set<String> perms = new java.util.LinkedHashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode p : n.path("permissions")) perms.add(p.asText());
                out.add(new Seed(n.path("code").asText(), n.path("name").asText(), perms));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("角色种子读取失败：" + e.getMessage(), e);
        }
    }

    /**

     * 可为 null：单测与无数据库环境只用内置默认

     */
    private final RolePermissionMapper mapper;
    private final SchemaSeedMapper seedMapper;
    /**
     * 权限节点目录：自定义权限码的合法性校验来源
     */
    private final PermissionCatalog catalog;

    /**

     * 运行期角色权限表（以库为准），volatile 保证并发读到最新快照

     */
    private volatile Map<String, Set<String>> effective = Map.of();

    /**

     * 角色中文显示名（role_definitions 表），与权限矩阵一起下发给后台页面

     */
    private volatile Map<String, String> roleNames = Map.of();

    /**

     * 无数据库环境（单测）：用 roles.json 种子初始化内存视图，不写库

     */
    public RolePermissions() {
        this.catalog = null; this.mapper = null; this.seedMapper = null;
        Map<String, Set<String>> fromSeed = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Seed seed : loadSeeds()) {
            names.put(seed.code, seed.name);
            if (!seed.permissions.contains("*")) fromSeed.put(seed.code, seed.permissions);
        }
        this.effective = Map.copyOf(fromSeed);
        this.roleNames = Map.copyOf(names);
    }

    /**
     * 兼容单测：不接 Mapper。
     */
    public RolePermissions(PermissionCatalog catalog) { this(catalog, null, null); }

    @Autowired
    public RolePermissions(PermissionCatalog catalog, RolePermissionMapper mapper, SchemaSeedMapper seedMapper) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.seedMapper = seedMapper;
    }

    @PostConstruct
    public void init() {
        if (mapper == null) return;
        try {
            // 种子仅首次写入：库里已有该角色就不覆盖（后台改过的名称/权限保留）
            for (Seed seed : loadSeeds()) {
                seedMapper.seedRoleName(seed.code, seed.name);
                if (!"admin".equals(seed.code) && !seed.permissions.contains("*")) {
                    for (String perm : seed.permissions) {
                        seedMapper.seedRolePermission(seed.code, perm);
                    }
                }
            }
            refresh();
        } catch (Exception e) {
            // 持久化表不可用时保持空视图（默认拒绝），不臆造权限
        }
    }

    /**
     * 从数据库整体重载角色权限到内存视图。
     */
    public void refresh() {
        if (mapper == null) { effective = Map.of(); roleNames = Map.of(); return; }
        Map<String, Set<String>> fromDb = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.listAllPermissions()) {
            fromDb.computeIfAbsent(String.valueOf(row.get("role")).trim(), k -> new LinkedHashSet<>())
                    .add(String.valueOf(row.get("permission")).trim());
        }
        effective = Map.copyOf(fromDb);
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.listAllNames()) {
            names.put(String.valueOf(row.get("role")).trim(), String.valueOf(row.get("roleName")).trim());
        }
        roleNames = Map.copyOf(names);
    }

    /**
     * admin 拥有的全部权限码：以权限目录（数据库）为准，而不是写死的 Permission.ALL。
     * 这样后台/清单里新增节点后，admin 自动拥有，不用改 Java 常量。
     * 无 catalog 的单测环境回落到内置常量集。
     */
    private Set<String> adminPermissions() {
        if (catalog == null) return java.util.Set.copyOf(Permission.ALL);
        try {
            Set<String> fromDb = new LinkedHashSet<>();
            for (Map<String, Object> row : catalog.list()) {
                String code = String.valueOf(row.getOrDefault("code", ""));
                if (!code.isEmpty() && !"null".equals(code)) fromDb.add(code);
            }
            // 注解层的接口权限码必须一并给 admin，哪怕目录暂时没同步到
            fromDb.addAll(Permission.ALL);
            return Set.copyOf(fromDb);
        } catch (Exception e) {
            return java.util.Set.copyOf(Permission.ALL);  // 目录暂不可用时 admin 仍需全权限
        }
    }

    /**

     * 获取某用户拥有的全部权限码（只读视图）

     */
    public Set<String> permissionsOf(UserContext.CurrentUser user) {
        if (user == null || user.isAnonymous()) return Set.of();
        if (user.hasGroup("admin")) return adminPermissions();
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : effective.entrySet()) {
            if (user.hasGroup(e.getKey())) result.addAll(e.getValue());
        }
        return Set.copyOf(result);
    }

    /**

     * 是否拥有其中任意一个权限；all=true 时要求全部拥有

     */
    public boolean allows(UserContext.CurrentUser user, String[] needed, boolean all) {
        if (needed == null || needed.length == 0) return true;
        if (user == null || user.isAnonymous()) return false;
        if (user.hasGroup("admin")) return true;   // 超级角色直接放行
        Set<String> owned = permissionsOf(user);
        if (all) return List.of(needed).stream().allMatch(owned::contains);
        return List.of(needed).stream().anyMatch(owned::contains);
    }

    /**

     * 供前端展示：系统中所有角色及其权限（带中文显示名）

     */
    public List<Map<String, Object>> roleMatrix() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(effective.keySet());
        all.addAll(roleNames.keySet());   // 只建了名称还没配权限的角色也要能看到
        // Map.copyOf 的 keySet 顺序不保证稳定，直接遍历会让角色列表每次刷新都不一样。
        // 固定按角色码排序，admin 作为超级角色放最后，便于对照。
        List<String> roles = new ArrayList<>(all);
        roles.sort((a, b) -> {
            boolean aa = "admin".equals(a), ba = "admin".equals(b);
            if (aa != ba) return aa ? 1 : -1;
            return a.compareToIgnoreCase(b);
        });
        roles.forEach(role -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("name", roleNames.getOrDefault(role, role));
            m.put("permissions", role.equals("admin")
                    ? List.copyOf(adminPermissions()) : List.copyOf(effective.getOrDefault(role, Set.of())));
            m.put("builtin", "admin".equals(role));   // 只有 admin 不可删，其余都是库里可配的
            out.add(m);
        });
        return out;
    }

    /**

     * 角色中文显示名：DB 自定义名称优先，内置回落，未知角色原样返回

     */
    public String roleName(String role) {
        String key = normalize(role);
        return roleNames.getOrDefault(key, key);
    }

    /**
     * 设置角色权限（后台编辑保存）。admin 恒为全权限不可改；权限码必须全部合法。
     * 写库成功后重建内存视图，下一次接口调用立即生效，无需重启。
     */
    public void setPermissions(String role, List<String> permissions) {
        setPermissions(role, permissions, null);
    }

    /**

     * displayName 非空时同时落角色名称，供后台列表/用户管理下拉统一显示

     */
    public void setPermissions(String role, List<String> permissions, String displayName) {
        String name = normalize(role);
        if (name.isEmpty()) throw new IllegalArgumentException("角色名不能为空");
        if (name.equals("admin")) throw new IllegalArgumentException("管理员角色拥有全部权限，不可修改");
        if (permissions == null || permissions.stream().anyMatch(p ->
                !Permission.ALL.contains(p) && (catalog == null || !catalog.exists(p)))) {
            throw new IllegalArgumentException("存在不合法的权限码");
        }
        if (mapper == null) throw new IllegalStateException("当前环境无数据库，角色权限不可配置");
        mapper.deletePermissions(name);
        for (String p : new LinkedHashSet<>(permissions)) {
            mapper.insertPermission(name, p);
        }
        String label = displayName == null ? "" : displayName.trim();
        if (!label.isEmpty()) {
            mapper.upsertName(name, label);
        }
        refresh();
    }

    /**

     * 删除角色定义：admin 不可删；DB 中没有记录的角色无需删除（内置默认不可移除）

     */
    public boolean deleteRole(String role) {
        String name = normalize(role);
        if (name.equals("admin")) throw new IllegalArgumentException("管理员角色不可删除");
        if (mapper == null) throw new IllegalStateException("当前环境无数据库，角色权限不可配置");
        int rows = mapper.deletePermissions(name);
        mapper.deleteName(name);
        refresh();
        return rows > 0;
    }

    private static String normalize(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}
