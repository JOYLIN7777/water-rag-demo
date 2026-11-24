package com.joylin.waterkb.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.tool.ToolPermissionRegistry;
import com.joylin.waterkb.tool.WaterInfoTool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用户答「需要」后的确定性工具执行：查得到就注入真实结果，查不到就说用户视角提示。 */
class ToolConfirmationTest {

    private static String apply(RagChatService svc, String question, String history) throws Exception {
        Method m = RagChatService.class.getDeclaredMethod(
                "applyToolConfirmation", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, "基础提示", question, history);
    }

    private static RagChatService service(WaterInfoTool tool, ToolPermissionRegistry reg) {
        // 只用到工具与权限依赖，其余构造参数传 null
        return new RagChatService(null, null, tool, reg, null, null, null, null,
                new ObjectMapper(), null, null, null);
    }

    @Test
    void 确认后后端直接执行工具并注入真实数据() throws Exception {
        ToolPermissionRegistry reg = mock(ToolPermissionRegistry.class);
        when(reg.denyReason(anyString())).thenReturn(null);
        WaterInfoTool tool = mock(WaterInfoTool.class);
        when(tool.queryRealtimeWater("东溪闸")).thenReturn("{\"station\":\"东溪闸\",\"level\":12.35}");

        String prompt = apply(service(tool, reg), "需要",
                "助手：是否需要我调用实时水情查询工具查询东溪闸？\n用户：东溪闸\n");

        assertTrue(prompt.contains("实时水情工具已执行"), prompt);
        assertTrue(prompt.contains("12.35"), prompt);
        assertFalse(prompt.contains("PERMISSION_DENIED"), prompt);
        verify(tool).queryRealtimeWater("东溪闸");
    }

    @Test
    void 直接问实时水情也由后端执行工具() throws Exception {
        ToolPermissionRegistry reg = mock(ToolPermissionRegistry.class);
        when(reg.denyReason(anyString())).thenReturn(null);
        WaterInfoTool tool = mock(WaterInfoTool.class);
        when(tool.queryRealtimeWater("东溪闸")).thenReturn("{\"station\":\"东溪闸\",\"level\":20.0}");

        String prompt = apply(service(tool, reg), "东溪闸当前水位和流量是多少？", "（无）");
        assertTrue(prompt.contains("实时水情工具已执行"), prompt);
        assertTrue(prompt.contains("20.0"), prompt);
        verify(tool).queryRealtimeWater("东溪闸");
    }

    @Test
    void 无权限时不泄露技术细节() throws Exception {
        ToolPermissionRegistry reg = mock(ToolPermissionRegistry.class);
        when(reg.denyReason(anyString())).thenReturn("缺少 chat:use（内部原因）");
        WaterInfoTool tool = mock(WaterInfoTool.class);

        String prompt = apply(service(tool, reg), "需要",
                "助手：是否需要我调用实时水情查询工具查询东溪闸？\n");

        assertTrue(prompt.contains("工具权限提示"), prompt);
        assertTrue(prompt.contains("没有实时水情查询权限"), prompt);
        assertFalse(prompt.contains("chat:use"), prompt);
        verifyNoInteractions(tool);
    }

    @Test
    void 非确认短句不触发工具() throws Exception {
        ToolPermissionRegistry reg = mock(ToolPermissionRegistry.class);
        WaterInfoTool tool = mock(WaterInfoTool.class);
        String prompt = apply(service(tool, reg), "你好",
                "助手：是否需要我调用实时水情查询工具查询东溪闸？\n");
        assertEquals("基础提示", prompt);
        verifyNoInteractions(tool);
    }
}
