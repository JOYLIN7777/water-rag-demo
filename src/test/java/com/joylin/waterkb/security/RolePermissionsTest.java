package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 企业级 RBAC 权限判定单测：确保默认拒绝、admin 全权限、角色不可越权 */
class RolePermissionsTest {

    private final RolePermissions service = new RolePermissions();

    @Test
    void admin拥有全部权限码() {
        UserContext.CurrentUser admin =
                new UserContext.CurrentUser("admin", List.of("admin", "water-dept"), "default", "jwt");
        Set<String> owned = service.permissionsOf(admin);
        for (String p : Permission.ALL) {
            assertTrue(owned.contains(p), "admin 应拥有权限：" + p);
        }
        assertTrue(service.allows(admin, new String[]{Permission.MODEL_WRITE, Permission.AUDIT_READ,
                Permission.ADMIN_ACCESS, Permission.USER_WRITE}, true));
    }

    @Test
    void 分析员可问答上传但不可改模型或进后台() {
        UserContext.CurrentUser analyst =
                new UserContext.CurrentUser("analyst", List.of("water-dept", "analyst"), "default", "jwt");
        assertTrue(service.allows(analyst, new String[]{Permission.CHAT_USE}, false));
        assertTrue(service.allows(analyst, new String[]{Permission.DOC_WRITE}, false));
        assertTrue(service.allows(analyst, new String[]{Permission.DOC_DELETE}, false));
        assertTrue(service.allows(analyst, new String[]{Permission.USAGE_READ}, false));

        assertFalse(service.allows(analyst, new String[]{Permission.MODEL_WRITE}, false), "分析员不得改模型");
        assertFalse(service.allows(analyst, new String[]{Permission.AUDIT_READ}, false), "分析员不得看审计");
        assertFalse(service.allows(analyst, new String[]{Permission.ADMIN_ACCESS}, false), "分析员不得进后台");
        assertFalse(service.allows(analyst, new String[]{Permission.USER_WRITE}, false), "分析员不得管用户");
    }

    @Test
    void 只读用户只能问答查看不能上传删除() {
        UserContext.CurrentUser viewer =
                new UserContext.CurrentUser("guest", List.of("viewer"), "default", "jwt");
        assertTrue(service.allows(viewer, new String[]{Permission.CHAT_USE}, false));
        assertTrue(service.allows(viewer, new String[]{Permission.DOC_READ}, false));
        assertFalse(service.allows(viewer, new String[]{Permission.DOC_WRITE}, false));
        assertFalse(service.allows(viewer, new String[]{Permission.DOC_DELETE}, false));
    }

    @Test
    void 匿名用户与未知角色默认拒绝() {
        assertFalse(service.allows(UserContext.CurrentUser.ANONYMOUS,
                new String[]{Permission.CHAT_USE}, false));
        UserContext.CurrentUser unknown =
                new UserContext.CurrentUser("x", List.of("unknown-role"), "default", "jwt");
        assertTrue(service.permissionsOf(unknown).isEmpty(), "未知角色不应获得任何权限");
        assertFalse(service.allows(unknown, new String[]{Permission.DOC_READ}, false));
    }

    @Test
    void 多角色权限取并集但不产生越权() {
        UserContext.CurrentUser multi =
                new UserContext.CurrentUser("u", List.of("viewer", "analyst"), "default", "jwt");
        assertTrue(service.allows(multi, new String[]{Permission.CHAT_USE}, false));
        assertTrue(service.allows(multi, new String[]{Permission.DOC_WRITE}, false));
        assertFalse(service.allows(multi, new String[]{Permission.ADMIN_ACCESS}, false));
    }

    @Test
    void 角色矩阵带中文显示名且内置角色回落正确() {
        var matrix = service.roleMatrix();
        assertEquals(3, matrix.size(), "无数据库时只应有三个内置角色，不能冒出运维人员等历史硬编码");
        assertTrue(matrix.stream().anyMatch(m -> "admin".equals(m.get("role"))
                && "系统管理员".equals(m.get("name"))));
        assertTrue(matrix.stream().anyMatch(m -> "analyst".equals(m.get("role"))
                && "水利分析员".equals(m.get("name"))));
        assertTrue(matrix.stream().anyMatch(m -> "viewer".equals(m.get("role"))
                && "只读用户".equals(m.get("name"))));
        assertEquals("系统管理员", service.roleName("admin"));
        // 历史账号里可能残留 ops 组，显示名仍要正确，但它不该出现在角色定义清单里
        assertEquals("运维人员", service.roleName("ops"));
    }

    @Test
    void all模式要求全部权限都满足() {
        UserContext.CurrentUser analyst =
                new UserContext.CurrentUser("analyst", List.of("analyst"), "default", "jwt");
        assertTrue(service.allows(analyst,
                new String[]{Permission.CHAT_USE, Permission.DOC_WRITE}, true));
        assertFalse(service.allows(analyst,
                new String[]{Permission.CHAT_USE, Permission.MODEL_WRITE}, true),
                "缺少任一权限时 all 模式必须拒绝");
    }
}
