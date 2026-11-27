package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;
import com.joylin.waterkb.mapper.PermissionNodeMapper;
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
        PermissionNodeMapper mapper = mock(PermissionNodeMapper.class);
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());

        // 动态取"代码里实际在用的全部权限码"作为已注册集合——新增权限码时测试不用跟着改
        java.util.Set<String> used = new UnregisteredPermScanner(mapper, mapping).allUsedCodes();
        when(mapper.listAllCodes()).thenReturn(new java.util.ArrayList<>(used));
        assertTrue(new UnregisteredPermScanner(mapper, mapping).scan().isEmpty(), "全部在用码已注册时应为空");
    }

    @Test
    void 未注册的在用码会被列出并带来源() {
        PermissionNodeMapper mapper = mock(PermissionNodeMapper.class);
        when(mapper.listAllCodes()).thenReturn(List.of());
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());
        List<Map<String, Object>> list = new UnregisteredPermScanner(mapper, mapping).scan();
        assertFalse(list.isEmpty(), "无任何已注册码时应扫出 React 组件里的权限声明");
        assertTrue(list.stream().anyMatch(x -> String.valueOf(x.get("code")).startsWith("doc:write")),
                "应包含问答页上传按钮的 doc:write 系列权限码");
        assertTrue(list.stream().allMatch(x -> x.get("sources") instanceof List), "每条都要带来源");
    }
}
