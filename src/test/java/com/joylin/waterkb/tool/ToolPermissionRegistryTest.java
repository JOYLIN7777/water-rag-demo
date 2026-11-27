package com.joylin.waterkb.tool;

import com.joylin.waterkb.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.joylin.waterkb.service.ToolPermissionRegistry;

/** 工具权限：统一走 tool:water_query 权限码，默认拒绝、授权角色放行、管理员全权限放行。 */
class ToolPermissionRegistryTest {

    private final ToolPermissionRegistry registry = new ToolPermissionRegistry();

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void 匿名默认拒绝() {
        assertNotNull(registry.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY));
    }

    @Test
    void 普通用户无组被拒() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("zhangsan", List.of()));
        assertNotNull(registry.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY));
    }

    @Test
    void waterDept组放行() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("lisi", List.of("water-dept")));
        assertNull(registry.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY));
    }

    @Test
    void viewer问答角色放行() {
        // 只读角色在角色种子里配了 tool:water_query，按权限码放行
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("test", List.of("viewer")));
        assertNull(registry.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY));
    }

    @Test
    void 管理员全局放行() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("someone", List.of("admin")));
        assertNull(registry.denyReason(ToolPermissionRegistry.TOOL_WATER_QUERY));
    }

    @Test
    void 未注册工具默认拒绝() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("admin", List.of("admin")));
        assertNotNull(registry.denyReason("unknownTool"));
    }
}
