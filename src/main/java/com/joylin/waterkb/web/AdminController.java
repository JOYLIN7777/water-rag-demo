package com.joylin.waterkb.web;

import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.PasswordResetService;
import com.joylin.waterkb.security.PermissionCatalog;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.RolePermissions;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.security.UserStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台接口：用户查询与维护、角色权限、登录日志。
 *
 * <p>账号 CRUD 写入 app_users 表，重启后仍生效；写操作全部要求 ADMIN_ACCESS 权限。
 * 防止管理员把自己锁在外面：禁止删除自己、禁止把自己禁用或移除 admin 组、
 * 禁止删除/降级最后一个管理员。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequirePermission(Permission.ADMIN_ACCESS)
public class AdminController {

    private final UserStore users;
    private final RolePermissions rolePermissions;
    private final PermissionCatalog permCatalog;
    private final AuditService audit;
    private final PasswordResetService passwordReset;
    private final com.joylin.waterkb.security.OrgStore orgs;

    public AdminController(UserStore users, RolePermissions rolePermissions,
                           PermissionCatalog permCatalog, AuditService audit,
                           PasswordResetService passwordReset,
                           com.joylin.waterkb.security.OrgStore orgs) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.permCatalog = permCatalog;
        this.audit = audit;
        this.passwordReset = passwordReset;
        this.orgs = orgs;
    }

    // ---------- 组织（界面上叫"组织"，底层仍是账号的 tenant 字段） ----------

    /** 组织清单 + 成员数：组织架构页的主数据 */
    @GetMapping("/orgs")
    public List<Map<String, Object>> orgList() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UserStore.UserAccount u : users.list()) {
            counts.merge(u.tenant() == null ? "default" : u.tenant(), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : orgs.list()) {
            String key = String.valueOf(m.get("key"));
            Map<String, Object> row = new LinkedHashMap<>(m);
            row.put("members", counts.getOrDefault(key, 0));
            out.add(row);
        }
        return out;
    }

    /** 新建组织：标识留空则自动生成 */
    @PostMapping("/orgs")
    public Map<String, Object> createOrg(@RequestBody Map<String, Object> body) {
        String key = orgs.create(str(body.get("name")), str(body.get("key")));
        return Map.of("message", "组织已创建", "key", key);
    }

    /** 组织改名：只改显示名，组织标识（数据隔离依据）保持不变 */
    @PutMapping("/orgs/{key}")
    public Map<String, Object> renameOrg(@PathVariable String key, @RequestBody Map<String, Object> body) {
        if (!orgs.rename(key, str(body.get("name")))) throw new IllegalArgumentException("组织不存在：" + key);
        return Map.of("message", "组织已更新", "key", key);
    }

    /** 删除组织：默认组织与仍有成员的组织都拒绝 */
    @DeleteMapping("/orgs/{key}")
    public Map<String, Object> deleteOrg(@PathVariable String key) {
        long members = users.list().stream()
                .filter(u -> key.equals(u.tenant())).count();
        orgs.delete(key, (int) members);
        return Map.of("message", "组织已删除", "key", key);
    }

    /** 用户列表：只返回可展示字段，绝不带出密码哈希 */
    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserStore.UserAccount u : users.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.username());
            m.put("displayName", u.displayName());
            m.put("groups", u.groups());
            m.put("tenant", u.tenant());
            m.put("enabled", u.enabled());
            out.add(m);
        }
        return out;
    }

    /** 新增账号 */
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        if (!users.create(username, str(body.get("password")), strList(body.get("groups")),
                str(body.get("tenant")), str(body.get("displayName")))) {
            throw new IllegalArgumentException("账号已存在，或初始密码不足 8 位");
        }
        return Map.of("message", "账号已创建", "username", username.toLowerCase());
    }

    /** 编辑账号：显示名、角色组、空间、启用状态 */
    @PutMapping("/users/{username}")
    public Map<String, Object> updateUser(@PathVariable String username, @RequestBody Map<String, Object> body) {
        String name = username.toLowerCase();
        String me = UserContext.get().userId();
        List<String> groups = strList(body.get("groups"));
        boolean enabled = !body.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (name.equals(me) && (!enabled || !groups.contains("admin"))) {
            throw new IllegalArgumentException("不能禁用自己或移除自己的管理员角色");
        }
        UserStore.UserAccount before = users.find(name);
        if (before != null && before.groups().contains("admin")
                && (!enabled || !groups.contains("admin")) && isLastAdmin(name)) {
            throw new IllegalArgumentException("不能停用或降级最后一个管理员");
        }
        if (!users.update(name, str(body.get("displayName")), groups, str(body.get("tenant")), enabled)) {
            throw new IllegalArgumentException("账号不存在：" + name);
        }
        return Map.of("message", "账号已更新", "username", name);
    }

    /** 删除账号：保护自己与最后一个管理员 */
    @DeleteMapping("/users/{username}")
    public Map<String, Object> deleteUser(@PathVariable String username) {
        String name = username.toLowerCase();
        if (name.equals(UserContext.get().userId())) {
            throw new IllegalArgumentException("不能删除当前登录账号");
        }
        if (isLastAdmin(name)) throw new IllegalArgumentException("不能删除最后一个管理员");
        if (!users.delete(name)) throw new IllegalArgumentException("账号不存在：" + name);
        return Map.of("message", "账号已删除", "username", name);
    }

    /** 重置密码：不要求原密码，仅管理员可操作 */
    @PostMapping("/users/{username}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String username, @RequestBody Map<String, Object> body) {
        String name = username.toLowerCase();
        if (!users.resetPassword(name, str(body.get("password")))) {
            throw new IllegalArgumentException("账号不存在，或新密码不足 8 位");
        }
        return Map.of("message", "密码已重置", "username", name);
    }

    // ---------- 密码重置申请（忘记密码流程的管理侧） ----------

    /** 待处理的密码重置申请：待审批 + 已发码未使用（后者便于回答"码给了怎么没用"） */
    @GetMapping("/password-resets")
    public List<Map<String, Object>> passwordResets() {
        return passwordReset.list();
    }

    /** 批准申请：生成一次性重置码并返回明文（仅此一次），由管理员通过可靠渠道告知本人 */
    @PostMapping("/password-resets/{id}/approve")
    public Map<String, Object> approvePasswordReset(@PathVariable long id) {
        String code = passwordReset.approve(id, UserContext.get().userId());
        return Map.of("code", code, "ttlMinutes", 30,
                "message", "重置码已生成，请通过可靠渠道告知本人：30 分钟内有效，用一次即作废");
    }

    /** 驳回申请 */
    @PostMapping("/password-resets/{id}/reject")
    public Map<String, Object> rejectPasswordReset(@PathVariable long id) {
        passwordReset.reject(id, UserContext.get().userId());
        return Map.of("message", "已驳回该申请");
    }

    private boolean isLastAdmin(String candidate) {
        int admins = 0;
        for (UserStore.UserAccount u : users.list()) {
            if (u.enabled() && u.groups().contains("admin") && !u.username().equals(candidate)) admins++;
        }
        return admins == 0;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        String s = str(v);
        return s.isEmpty() ? List.of() : Arrays.stream(s.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList();
    }

    /** 角色 → 权限码矩阵 */
    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return rolePermissions.roleMatrix();
    }

    /** 编辑角色权限：保存后立即生效（内存缓存重建），admin 恒为全权限不可改 */
    @PutMapping("/roles/{role}/permissions")
    public Map<String, Object> setRolePermissions(@PathVariable String role,
                                                  @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        rolePermissions.setPermissions(role, permissions);
        return Map.of("message", "角色权限已保存并实时生效", "role", role.toLowerCase());
    }

    /** 删除角色定义：删除后该角色回落内置默认（无记录的角色按无权限处理） */
    @DeleteMapping("/roles/{role}")
    public Map<String, Object> deleteRole(@PathVariable String role) {
        boolean removed = rolePermissions.deleteRole(role);
        return Map.of("message", removed ? "角色定义已删除" : "该角色没有自定义配置", "role", role.toLowerCase());
    }

    // ==================== 权限节点目录（菜单权限页） ====================

    /** 全部权限节点（内置 + 自定义） */
    @GetMapping("/perms")
    public List<Map<String, Object>> perms() {
        return permCatalog.list();
    }

    /** 新增自定义权限节点 */
    @PostMapping("/perms")
    public Map<String, Object> createPerm(@RequestBody Map<String, Object> body) {
        permCatalog.create(str(body.get("code")), str(body.get("name")),
                str(body.get("type")), str(body.get("module")), str(body.get("desc")));
        return Map.of("message", "权限节点已创建", "code", str(body.get("code")));
    }

    /** 编辑节点显示信息；内置节点不可改标识、不可删除 */
    @PutMapping("/perms/{code}")
    public Map<String, Object> updatePerm(@PathVariable String code, @RequestBody Map<String, Object> body) {
        permCatalog.update(code, str(body.get("name")), str(body.get("type")),
                str(body.get("module")), str(body.get("desc")));
        return Map.of("message", "权限节点已更新", "code", code);
    }

    /** 删除节点：仅自定义节点可删，同时清理各角色的该权限配置 */
    @DeleteMapping("/perms/{code}")
    public Map<String, Object> deletePerm(@PathVariable String code) {
        permCatalog.delete(code);
        // 数据库里的角色配置已同步清理，重载内存角色矩阵让变更立即生效
        rolePermissions.refresh();
        return Map.of("message", "权限节点已删除", "code", code);
    }

    /** 登录日志：从审计里按登录相关动作过滤（含成功与失败） */
    @GetMapping("/login-logs")
    public List<Map<String, Object>> loginLogs(@RequestParam(required = false, defaultValue = "100") int limit) {
        String tenant = UserContext.get().tenantId();
        List<Map<String, Object>> all = audit.recent(tenant, Math.min(Math.max(limit, 1), 500));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : all) {
            String action = String.valueOf(row.getOrDefault("action", ""));
            // 只认真正的登录/刷新端点；不能用 contains("login")，否则查询本接口自身也会被算进去
            if (action.endsWith("/api/auth/login") || action.endsWith("/api/auth/refresh")) {
                out.add(row);
            }
        }
        return out;
    }
}
