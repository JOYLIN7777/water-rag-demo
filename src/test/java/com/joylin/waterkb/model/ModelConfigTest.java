package com.joylin.waterkb.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelConfig 纯逻辑单测：API Key 脱敏（企业安全基线）。
 */
class ModelConfigTest {

    @Test
    void 空Key显示为无需Key() {
        ModelConfig c = new ModelConfig();
        c.setApiKey("");
        assertEquals("（无需 Key）", c.maskedKey());
    }

    @Test
    void 短Key完全遮蔽() {
        ModelConfig c = new ModelConfig();
        c.setApiKey("sk-1234");
        assertEquals("****", c.maskedKey());
    }

    @Test
    void 长Key只露前后各四位() {
        ModelConfig c = new ModelConfig();
        c.setApiKey("sk-ws-H.YXLIXEH.LQti.MEUCIQC");
        String masked = c.maskedKey();
        assertTrue(masked.startsWith("sk-w"));
        assertTrue(masked.endsWith("IQC"));
        assertTrue(masked.contains("****"));
        assertFalse(masked.contains("LQti")); // 中间段不能泄漏
    }
}
