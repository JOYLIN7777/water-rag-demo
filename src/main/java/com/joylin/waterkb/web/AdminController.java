package com.joylin.waterkb.web;

import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.RolePermissions;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.security.UserStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台的只读聚合接口：用户、角色、菜单权限、登录日志。
 *
 * <p>只做查询，不提供用户增删改——当前用户库是内存实现，
 * 做 CRUD 会是"看起来能改、重启就丢"的假功能，对作品集是减分项。
 * 真实的写操作应接数据库用户表后开放。全部端点要求 ADMIN_ACCESS 权限。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequirePermission(Permission.ADMIN_ACCESS)
public class AdminController {

    private final UserStore users;
    private final RolePermissions rolePermissions;
    private final AuditService audit;

    public AdminController(UserStore users, RolePermissions rolePermissions, AuditService audit) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.audit = audit;
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

    /** 角色 → 权限码矩阵 */
    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return rolePermissions.roleMatrix();
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
