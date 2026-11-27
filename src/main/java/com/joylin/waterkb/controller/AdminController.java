package com.joylin.waterkb.controller;

import com.joylin.waterkb.service.AuditService;
import com.joylin.waterkb.service.PasswordResetService;
import com.joylin.waterkb.service.PermissionCatalog;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.service.RolePermissions;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.mapper.UserStore;
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
import com.joylin.waterkb.mapper.ChatSessionStore;
import com.joylin.waterkb.mapper.OrgStore;
import com.joylin.waterkb.security.UnregisteredPermScanner;
import com.joylin.waterkb.service.EsIndexService;
import com.joylin.waterkb.service.LoginCryptoService;

/**
 * 管理后台接口：用户查询与维护、角色权限、登录日志。
 *
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
    private final com.joylin.waterkb.mapper.OrgStore orgs;
    private final com.joylin.waterkb.service.EsIndexService es;
    private final com.joylin.waterkb.mapper.ChatSessionStore chatSessions;
    private final com.joylin.waterkb.service.LoginCryptoService crypto;
    private final com.joylin.waterkb.security.UnregisteredPermScanner unregisteredPermScanner;

    public AdminController(UserStore users, RolePermissions rolePermissions,
                           PermissionCatalog permCatalog, AuditService audit,
                           PasswordResetService passwordReset,
                           com.joylin.waterkb.mapper.OrgStore orgs,
                           com.joylin.waterkb.service.EsIndexService es,
                           com.joylin.waterkb.mapper.ChatSessionStore chatSessions,
                           com.joylin.waterkb.service.LoginCryptoService crypto,
                           com.joylin.waterkb.security.UnregisteredPermScanner unregisteredPermScanner) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.permCatalog = permCatalog;
        this.audit = audit;
        this.passwordReset = passwordReset;
        this.orgs = orgs;
        this.es = es;
        this.chatSessions = chatSessions;
        this.crypto = crypto;
        this.unregisteredPermScanner = unregisteredPermScanner;
    }

    // ---------- 组织（界面上叫"组织"，底层仍是账号的 tenant 字段） ----------

    /**
     * 组织清单 + 成员数：组织架构页的主数据。
     *
     */
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
            int all = 0;
            for (String k : orgs.subtreeKeys(key)) all += counts.getOrDefault(k, 0);
            row.put("membersAll", all);
            row.put("children", orgs.childCount(key));
            out.add(row);
        }
        return out;
    }

    /**

     * 新建组织：标识留空则自动生成；parentKey 决定挂在哪个节点下（空=顶层）

     */
    @PostMapping("/orgs")
    public Map<String, Object> createOrg(@RequestBody Map<String, Object> body) {
        String key = orgs.create(str(body.get("name")), str(body.get("key")), str(body.get("parentKey")));
        return Map.of("message", "组织已创建", "key", key);
    }

    /**
     * 更新组织：改名与调整上级都可选。
     *
     */
    @PutMapping("/orgs/{key}")
    public Map<String, Object> updateOrg(@PathVariable String key, @RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        if (!name.isBlank() && !orgs.rename(key, name)) throw new IllegalArgumentException("组织不存在：" + key);
        if (body.containsKey("parentKey")) orgs.setParent(key, str(body.get("parentKey")));
        return Map.of("message", "组织已更新", "key", key);
    }

    /**
     * 删除组织。
     *
     */
    @DeleteMapping("/orgs/{key}")
    public Map<String, Object> deleteOrg(@PathVariable String key,
                                         @RequestParam(required = false) String moveTo) {
        List<UserStore.UserAccount> members = users.list().stream()
                .filter(u -> key.equals(u.tenant())).toList();
        long docs = es.countByTenant(key);
        int sessions = chatSessions.countByTenant(key);
        String target = moveTo == null ? "" : moveTo.trim();
        boolean moving = !target.isEmpty();

        if (!moving && (!members.isEmpty() || docs > 0 || sessions > 0)) {
            throw new IllegalArgumentException("该组织下还有 " + members.size() + " 个账号、"
                    + docs + " 个知识库分片、" + sessions + " 个会话。请选择迁移目标，连数据一起搬走后再删");
        }

        int movedUsers = 0;
        long movedDocs = 0;
        int movedSessions = 0;
        if (moving) {
            if (target.equals(key)) throw new IllegalArgumentException("不能把数据迁到正在删除的组织");
            if (!orgs.exists(target)) throw new IllegalArgumentException("目标组织不存在：" + target);
            for (UserStore.UserAccount u : members) {
                if (users.update(u.username(), u.displayName(), u.groups(), target, u.enabled())) movedUsers++;
            }
            movedDocs = es.migrateTenant(key, target);
            movedSessions = chatSessions.migrateTenant(key, target);
        }

        long left = users.list().stream().filter(u -> key.equals(u.tenant())).count();
        orgs.delete(key, (int) left);

        StringBuilder msg = new StringBuilder("组织已删除");
        if (movedUsers + movedDocs + movedSessions > 0) {
            msg.append("，迁到 ").append(target).append("：")
                    .append(movedUsers).append(" 个账号、")
                    .append(movedDocs).append(" 个分片、")
                    .append(movedSessions).append(" 个会话");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", msg.toString());
        out.put("key", key);
        out.put("movedUsers", movedUsers);
        out.put("movedDocs", movedDocs);
        out.put("movedSessions", movedSessions);
        return out;
    }

    /**

     * 用户列表：只返回可展示字段，绝不带出密码哈希

     */
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
            m.put("gender", u.gender());
            m.put("phone", u.phone());
            m.put("avatar", u.avatar());
            out.add(m);
        }
        return out;
    }

    /**

     * 新增账号

     */
    /**
     * 管理端设置密码：页面传 encrypted=true 时先解密，兼容内部脚本的明文调用
     */
    private String passwordOf(Map<String, Object> body) {
        if (body == null) return null;
        return Boolean.parseBoolean(String.valueOf(body.get("encrypted")))
                ? crypto.decrypt(str(body.get("password"))) : str(body.get("password"));
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        if (!users.create(username, passwordOf(body), strList(body.get("groups")),
                str(body.get("tenant")), str(body.get("displayName")))) {
            throw new IllegalArgumentException("账号已存在，或初始密码不足 8 位");
        }
        // 资料字段与启用状态
        if (body.containsKey("gender") || body.containsKey("phone")) {
            users.updateProfile(username, str(body.get("gender")), str(body.get("phone")), "");
        }
        boolean enabled = !body.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (!enabled) users.setEnabled(username, false);
        return Map.of("message", "账号已创建", "username", username.toLowerCase());
    }

    /**

     * 编辑账号：显示名、角色组、空间、启用状态

     */
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
        // 系统管理员账号：角色/组织/状态是系统底座不可动，但资料字段（显示名、性别、手机号、头像）允许编辑。
        // 之前整账号拒绝，导致在用户管理里点保存必然 400。
        if (before != null && before.groups().contains("admin")) {
            boolean sameGroups = groups != null && groups.equals(before.groups());
            String wantedTenant = str(body.get("tenant"));
            boolean sameTenant = wantedTenant.isEmpty() || wantedTenant.equals(before.tenant());
            boolean sameEnabled = enabled == before.enabled();
            if (!sameGroups || !sameTenant || !sameEnabled) {
                throw new IllegalArgumentException("系统管理员的角色、所属组织和启用状态不可修改");
            }
            String gender = body.containsKey("gender") ? str(body.get("gender")) : before.gender();
            String phone = body.containsKey("phone") ? str(body.get("phone")) : before.phone();
            String avatar = body.containsKey("avatar") ? str(body.get("avatar")) : before.avatar();
            users.updateProfile(name, gender, phone, avatar);
            return Map.of("message", "账号已更新", "username", name);
        }
        // 非 admin 调用者不得自行提权进 admin 组
        boolean callerAdmin = rolePermissions.permissionsOf(UserContext.get()).contains(Permission.ADMIN_ACCESS);
        if (!callerAdmin && groups.contains("admin")) {
            throw new IllegalArgumentException("只有管理员可以授予管理员角色");
        }
        if (!users.update(name, str(body.get("displayName")), groups, str(body.get("tenant")), enabled)) {
            throw new IllegalArgumentException("账号不存在：" + name);
        }
        // 资料字段可选：没传的保留原值，避免编辑一项就把另外两项清空
        if (body.containsKey("gender") || body.containsKey("phone") || body.containsKey("avatar")) {
            UserStore.UserAccount a = users.find(name);
            if (a != null) users.updateProfile(name,
                    body.containsKey("gender") ? str(body.get("gender")) : a.gender(),
                    body.containsKey("phone") ? str(body.get("phone")) : a.phone(),
                    body.containsKey("avatar") ? str(body.get("avatar")) : a.avatar());
        }
        return Map.of("message", "账号已更新", "username", name);
    }

    /**

     * 删除账号：保护自己与最后一个管理员

     */
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

    /**

     * 重置密码：不要求原密码，仅管理员可操作

     */
    @PostMapping("/users/{username}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String username, @RequestBody Map<String, Object> body) {
        String name = username.toLowerCase();
        UserStore.UserAccount target = users.find(name);
        if (target != null && target.groups().contains("admin")) {
            throw new IllegalArgumentException("系统管理员账号的密码不可在用户管理中重置");
        }
        if (!users.resetPassword(name, passwordOf(body))) {
            throw new IllegalArgumentException("账号不存在，或新密码不足 8 位");
        }
        return Map.of("message", "密码已重置", "username", name);
    }

    // ---------- 密码重置申请（忘记密码流程的管理侧） ----------

    /**
     * 待处理的密码重置申请：待审批 + 已发码未使用（后者便于回答"码给了怎么没用"）。
     *
     */
    @GetMapping("/password-resets")
    public List<Map<String, Object>> passwordResets() {
        List<Map<String, Object>> rows = passwordReset.list();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("username"));
            String display = "";
            UserStore.UserAccount acc = users.find(name);
            if (acc != null && acc.displayName() != null) display = acc.displayName().trim();
            row.put("displayName", display);
        }
        return rows;
    }

    /**

     * 批准申请：生成一次性重置码并返回明文（仅此一次），由管理员通过可靠渠道告知本人

     */
    @PostMapping("/password-resets/{id}/approve")
    public Map<String, Object> approvePasswordReset(@PathVariable long id) {
        String code = passwordReset.approve(id, UserContext.get().userId());
        return Map.of("code", code, "ttlMinutes", 30,
                "message", "重置码已生成，请通过可靠渠道告知本人：30 分钟内有效，用一次即作废");
    }

    /**

     * 驳回申请

     */
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
    private static int intOf(Object v) {
        if (v == null) return 0;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        String s = str(v);
        return s.isEmpty() ? List.of() : Arrays.stream(s.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList();
    }

    /**

     * 角色 → 权限码矩阵

     */
    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return rolePermissions.roleMatrix();
    }

    /**

     * 编辑角色权限：保存后立即生效（内存缓存重建），admin 恒为全权限不可改

     */
    @PutMapping("/roles/{role}/permissions")
    public Map<String, Object> setRolePermissions(@PathVariable String role,
                                                  @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        String roleName = str(body.get("roleName"));
        rolePermissions.setPermissions(role, permissions, roleName);
        return Map.of("message", "角色已保存并实时生效", "role", role.toLowerCase(),
                "name", rolePermissions.roleName(role));
    }

    /**

     * 删除角色定义：删除后该角色回落内置默认（无记录的角色按无权限处理）

     */
    @DeleteMapping("/roles/{role}")
    public Map<String, Object> deleteRole(@PathVariable String role) {
        boolean removed = rolePermissions.deleteRole(role);
        return Map.of("message", removed ? "角色定义已删除" : "该角色没有自定义配置", "role", role.toLowerCase());
    }

    // ==================== 权限节点目录（菜单权限页） ====================

    /**

     * 全部权限节点（内置 + 自定义）

     */
    @GetMapping("/perms")
    public List<Map<String, Object>> perms() {
        return permCatalog.list();
    }

    /**

     * 新增自定义权限节点

     */
    @PostMapping("/perms")
    public Map<String, Object> createPerm(@RequestBody Map<String, Object> body) {
        permCatalog.create(str(body.get("code")), str(body.get("name")),
                str(body.get("type")), str(body.get("module")), str(body.get("desc")),
                str(body.get("parentCode")), str(body.get("routePath")), intOf(body.get("orderNum")));
        return Map.of("message", "权限节点已创建", "code", str(body.get("code")));
    }

    /**

     * 页面自动发现：把本页声明的 data-perm 节点补进权限树，返回新增项

     */
    @PostMapping("/perms/discover")
    public Map<String, Object> discoverPerms(@RequestBody List<Map<String, Object>> body) {
        return permCatalog.discover(body);
    }

    /**

     * 声明式清单对账：新增/改名/改路由/改层级，清单里消失的内置节点标记下线

     */
    @PostMapping("/perms/sync")
    public Map<String, Object> syncPerms(@RequestBody List<Map<String, Object>> body) {
        return permCatalog.sync(body);
    }

    /**

     * 代码里所有在用的权限码（含已注册），供新增弹窗校验手填码是否真能生效

     */
    @GetMapping("/perms/used-codes")
    public java.util.Set<String> usedPermCodes() {
        return unregisteredPermScanner.allUsedCodes();
    }

    /**

     * 代码里在用、但权限树还没建的权限码（供新增权限弹窗快捷选择，选了就能控制真实功能）

     */
    @GetMapping("/perms/unused-codes")
    public List<Map<String, Object>> unusedPermCodes() {
        return unregisteredPermScanner.scan();
    }

    /**

     * 编辑节点显示信息；内置节点不可改标识、不可删除

     */
    @PutMapping("/perms/{code}")
    public Map<String, Object> updatePerm(@PathVariable String code, @RequestBody Map<String, Object> body) {
        permCatalog.update(code, str(body.get("name")), str(body.get("type")),
                str(body.get("module")), str(body.get("desc")),
                str(body.get("parentCode")), str(body.get("routePath")), intOf(body.get("orderNum")));
        return Map.of("message", "权限节点已更新", "code", code);
    }

    /**

     * 删除节点：仅自定义节点可删，同时清理各角色的该权限配置

     */
    @DeleteMapping("/perms/{code}")
    public Map<String, Object> deletePerm(@PathVariable String code) {
        permCatalog.delete(code);
        // 数据库里的角色配置已同步清理，重载内存角色矩阵让变更立即生效
        rolePermissions.refresh();
        return Map.of("message", "权限节点已删除", "code", code);
    }

    /**

     * 登录日志：从审计里按登录相关动作过滤（含成功与失败）。审计员凭 loginlog:read 可读

     */
    @GetMapping("/login-logs")
    @RequirePermission(Permission.LOGINLOG_READ)
    public List<Map<String, Object>> loginLogs(@RequestParam(required = false, defaultValue = "100") int limit) {
        String tenant = UserContext.get().tenantId();
        List<Map<String, Object>> out = new ArrayList<>(audit.loginLogs(tenant, Math.min(Math.max(limit, 1), 10000)));
        return out;
    }
}
