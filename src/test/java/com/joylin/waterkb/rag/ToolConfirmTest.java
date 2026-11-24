package com.joylin.waterkb.rag;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具确认追问识别：用户答"需要"时补一条强指令，让模型接着上一轮的工具提议直接调用。
 * 方法是私有的，用反射验证——不动可见性，避免为测试把内部逻辑暴露成 public。
 */
class ToolConfirmTest {

    private String directive(String question, String history) throws Exception {
        Method m = RagChatService.class.getDeclaredMethod("toolConfirmDirective", String.class, String.class);
        m.setAccessible(true);
        // 用 Unsafe 绕过实例构造：该方法不依赖任何字段，只是纯函数
        sun.misc.Unsafe unsafe;
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
        RagChatService svc = (RagChatService) unsafe.allocateInstance(RagChatService.class);
        return (String) m.invoke(svc, question, history);
    }

    @Test
    void 用户答需要且上文提议过工具则注入强指令() throws Exception {
        String history = "用户：现在水位多少？\n助手：如需确认东溪闸当前水位，我可为您调用实时水情查询工具。是否需要？\n";
        String d = directive("需要", history);
        assertNotNull(d, "应识别为工具确认");
        assertTrue(d.contains("queryRealtimeWater"), "指令要点名工具");
        assertTrue(d.contains("东溪闸"), "应从历史提取站点");
        assertTrue(d.contains("不要再说"), "要阻止模型再答找不到内容");
    }

    @Test
    void 肯定句变体也能识别() throws Exception {
        String history = "助手：是否需要我调用实时水情查询工具？\n";
        assertNotNull(directive("好的", history), "好的=确认");
        assertNotNull(directive("查一下", history), "查一下=确认");
        assertNotNull(directive("请调用", history), "请调用=确认");
    }

    @Test
    void 普通追问或未提议工具不注入() throws Exception {
        String history = "助手：是否需要我调用实时水情查询工具？\n";
        assertNull(directive("闸门有哪些？", history), "非肯定句不注入");
        assertNull(directive("需要", "用户：你好\n助手：你好\n"), "上文没提工具不注入");
        assertNull(directive("需要", ""), "无历史不注入");
    }
}
