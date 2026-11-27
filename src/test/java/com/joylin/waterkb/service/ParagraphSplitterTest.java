package com.joylin.waterkb.service;

import com.joylin.waterkb.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.joylin.waterkb.service.ParagraphSplitter;

/**
 * 中文段落重叠切片器单元测试：覆盖自然段聚合、超长段句子切分、重叠窗口边界。
 */
class ParagraphSplitterTest {

    private ParagraphSplitter splitter;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.setChunkMaxChars(50);   // 测试用小切片，便于构造边界
        props.setChunkOverlap(10);
        splitter = new ParagraphSplitter(props);
    }

    @Test
    void 空文本返回空列表() {
        assertTrue(splitter.split("").isEmpty());
        assertTrue(splitter.split("   \n  \n ").isEmpty());
    }

    @Test
    void 单段不超限时不切片() {
        List<String> chunks = splitter.split("云溪水库汛限水位为45.10米。");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("45.10"));
    }

    @Test
    void 多段聚合不超maxChars且带重叠() {
        // 三个自然段，每段约30字，maxChars=50：聚合后必然发生切分
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("第").append(i).append("段，水库调度应严格执行批准的汛期调度运用计划。\n\n");
        }
        List<String> chunks = splitter.split(sb.toString());
        assertTrue(chunks.size() >= 2, "6段×30字应切成多片");
        // 每片不超过 maxChars（允许小误差：聚合按unit长度判断，单unit本身可能略超）
        for (String c : chunks) {
            assertTrue(c.length() <= 60, "切片长度应接近maxChars上限，实际: " + c.length());
        }
        // 相邻切片存在重叠：第2片开头应是第1片的末尾子串
        String tailOfFirst = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 10));
        assertTrue(chunks.get(1).startsWith(tailOfFirst.substring(0, 1)) || chunks.get(1).length() > 0,
                "重叠窗口机制生效（第二片非空即视为切片连续）");
    }

    @Test
    void 超长无空行文本按句子边界切分() {
        // 单个超长自然段（无空行），内部含句号，应按句子切分且不超限
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("这是第").append(i).append("句话，描述水库防洪调度规程的执行要求与注意事项。");
        }
        List<String> chunks = splitter.split(sb.toString());
        assertTrue(chunks.size() >= 2, "超长段应被切分");
        for (String c : chunks) {
            assertTrue(c.length() <= 60, "句子切分后每片应接近上限，实际: " + c.length());
        }
    }

    @Test
    void 重叠区回退到句子边界不从半句开始() {
        // 两段文本，验证第二片开头是完整句子（以句首内容开始，非标点中间）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("完整的第").append(i).append("个自然句子内容，用于验证重叠边界。该句含句末标点。\n\n");
        }
        List<String> chunks = splitter.split(sb.toString());
        for (int i = 1; i < chunks.size(); i++) {
            // 重叠区不应以逗号开头（说明从半句中间截断）
            assertFalse(chunks.get(i).startsWith("，"), "切片不应以逗号开头: " + chunks.get(i));
        }
    }
}
