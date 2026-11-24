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
        // 模拟所有扫描到的码都已注册
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(
                "chat:use", "doc:read", "doc:write", "doc:delete", "search:use", "tree:use",
                "admin:access", "user:read", "user:write", "org:read", "role:read", "perm:read",
                "admin:docs", "model:read", "model:write", "usage:read", "loginlog:read", "audit:read"));
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());
        UnregisteredPermScanner scanner = new UnregisteredPermScanner(jdbc, mapping);
        assertTrue(scanner.scan().isEmpty(), "真实页面/接口的权限码都已注册时，快捷列表应为空");
    }

    @Test
    void 未注册的在用码会被列出并带来源() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());
        UnregisteredPermScanner scanner = new UnregisteredPermScanner(jdbc, mapping);
        List<Map<String, Object>> list = scanner.scan();
        assertFalse(list.isEmpty(), "没有任何已注册码时，应扫出前端 data-perm 声明");
        assertTrue(list.stream().anyMatch(x -> "doc:write".equals(x.get("code"))), "应包含问答页上传按钮的 doc:write");
        assertTrue(list.stream().allMatch(x -> x.get("sources") instanceof List), "每条都要带来源");
    }
}
