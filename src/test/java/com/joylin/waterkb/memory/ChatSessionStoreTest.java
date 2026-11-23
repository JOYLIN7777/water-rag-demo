package com.joylin.waterkb.memory;

import com.joylin.waterkb.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话清单存储的降级行为。
 *
 * <p>会话列表是辅助功能：数据库不可用时必须安静地退化成"只有浏览器本地缓存"，
 * 绝不能因为建表失败把问答页拖下水。这几个用例就是在守这条线。</p>
 */
class ChatSessionStoreTest {

    private final ChatSessionStore store = new ChatSessionStore();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 无数据库时所有操作安全降级() {
        assertDoesNotThrow(() -> {
            assertTrue(store.list().isEmpty(), "没有数据库时清单应为空而不是抛异常");
            assertFalse(store.upsert("session-1", "云溪水库汛限水位"));
            assertFalse(store.rename("session-1", "改名"));
            assertFalse(store.delete("session-1"));
        });
    }

    @Test
    void 空会话标识不写入() {
        assertFalse(store.upsert("", "标题"), "空 sessionId 直接拒绝");
        assertFalse(store.upsert(null, "标题"), "null sessionId 直接拒绝");
        assertFalse(store.delete("   "), "空白 sessionId 不当作有效删除");
    }

    @Test
    void 有身份上下文时也不因缺库崩溃() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser(
                "analyst", List.of("analyst"), "default", "jwt"));
        assertDoesNotThrow(() -> {
            assertTrue(store.list().isEmpty());
            assertFalse(store.upsert("session-2", ""));
        });
    }
}
