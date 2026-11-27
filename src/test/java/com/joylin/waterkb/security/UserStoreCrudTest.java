package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.joylin.waterkb.mapper.UserStore;

/** 后台账号维护的纯内存单测：新增、编辑、禁用、重置密码、删除。 */
class UserStoreCrudTest {

    private final PasswordHasher hasher = new PasswordHasher();

    private UserStore store() {
        UserStore s = new UserStore(hasher, "admin|Admin@123456|admin,water-dept|default|系统管理员");
        s.init();
        return s;
    }

    @Test
    void 新增账号可用且密码不落明文() {
        UserStore s = store();
        assertTrue(s.create("zhangsan", "Zhang@123456", List.of("viewer"), "default", "张三"));
        UserStore.UserAccount u = s.authenticate("zhangsan", "Zhang@123456");
        assertNotNull(u);
        assertEquals("张三", u.displayName());
        assertEquals(List.of("viewer"), u.groups());
        assertFalse(u.toString().contains("Zhang@123456"));
        // 重复用户名、短密码都拒绝
        assertFalse(s.create("zhangsan", "Another@123", List.of("viewer"), "default", "重复"));
        assertFalse(s.create("lisi", "short", List.of("viewer"), "default", "李四"));
        assertEquals(2, s.list().size());
    }

    @Test
    void 编辑账号改显示名组与启用状态() {
        UserStore s = store();
        assertTrue(s.create("zhangsan", "Zhang@123456", List.of("viewer"), "default", "张三"));
        assertTrue(s.update("zhangsan", "张三丰", List.of("analyst"), "dept-b", false));
        UserStore.UserAccount u = s.find("zhangsan");
        assertEquals("张三丰", u.displayName());
        assertEquals(List.of("analyst"), u.groups());
        assertEquals("dept-b", u.tenant());
        assertFalse(u.enabled());
        // 停用后不能登录
        assertNull(s.authenticate("zhangsan", "Zhang@123456"));
        // 重新启用后可登录，密码未变
        assertTrue(s.update("zhangsan", "张三丰", List.of("analyst"), "dept-b", true));
        assertNotNull(s.authenticate("zhangsan", "Zhang@123456"));
        assertFalse(s.update("missing", "不存在", List.of(), "", true));
    }

    @Test
    void 重置密码后旧密码失效() {
        UserStore s = store();
        assertTrue(s.create("zhangsan", "Zhang@123456", List.of("viewer"), "default", "张三"));
        assertTrue(s.resetPassword("zhangsan", "NewPass@888"));
        assertNull(s.authenticate("zhangsan", "Zhang@123456"));
        assertNotNull(s.authenticate("zhangsan", "NewPass@888"));
        assertFalse(s.resetPassword("zhangsan", "123"));
        assertFalse(s.resetPassword("missing", "Whatever@888"));
    }

    @Test
    void 删除账号后不再可认证() {
        UserStore s = store();
        assertTrue(s.create("zhangsan", "Zhang@123456", List.of("viewer"), "default", "张三"));
        assertTrue(s.delete("zhangsan"));
        assertNull(s.find("zhangsan"));
        assertNull(s.authenticate("zhangsan", "Zhang@123456"));
        assertFalse(s.delete("zhangsan"));
        assertEquals(1, s.list().size());
    }
}
