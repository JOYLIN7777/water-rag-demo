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
    void 已注册权限码不出现在快捷列表() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 模拟所有扫描到的码都已注册：从清单取真实节点码，避免新增细粒度码后测试过期
        String manifest = new String(java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/static/assets/perm-manifest.js")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        List<String> all = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("code: '([a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*)'").matcher(manifest);
        while (m.find()) all.add(m.group(1));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(all);
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
        assertTrue(list.stream().anyMatch(x -> String.valueOf(x.get("code")).startsWith("doc:write")),
                "应包含问答页上传按钮的 doc:write 系列权限码");
        assertTrue(list.stream().allMatch(x -> x.get("sources") instanceof List), "每条都要带来源");
    }
}
