package com.joylin.waterkb.tool;

import com.joylin.waterkb.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 工具权限：默认拒绝、组命中放行、管理员放行三条路径。 */
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
