package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 未注册权限码扫描：只列出代码在用、权限树没有的码 */
class UnregisteredPermScannerTest {

    @Test
    void 已注册权限码不出现在快捷列表() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 把 Vue 源码里所有 has('xxx') 都视为已注册，扫描结果应为空
        List<String> all = List.of("chat:use", "doc:read", "doc:write_chat",
                "doc:write_batch", "doc:write_admin", "doc:chunks", "doc:delete",
                "user:read", "user:write", "role:read", "perm:read", "admin:docs",
                "model:read", "model:write", "usage:read", "loginlog:read", "audit:read",
                "search:use", "tree:use", "loginlog:export", "audit:export");
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(all);
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());
        UnregisteredPermScanner scanner = new UnregisteredPermScanner(jdbc, mapping);
        assertTrue(scanner.scan().isEmpty(), "全部在用码都已注册时，快捷列表应为空");
    }

    @Test
    void 未注册的在用码会被列出并带来源() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());
        UnregisteredPermScanner scanner = new UnregisteredPermScanner(jdbc, mapping);
        List<Map<String, Object>> list = scanner.scan();
        assertFalse(list.isEmpty(), "没有任何已注册码时，应扫出 Vue 组件里的权限声明");
        assertTrue(list.stream().anyMatch(x -> String.valueOf(x.get("code")).startsWith("doc:write")),
                "应包含问答页上传按钮的 doc:write 系列权限码");
        assertTrue(list.stream().allMatch(x -> x.get("sources") instanceof List), "每条都要带来源");
    }
}
