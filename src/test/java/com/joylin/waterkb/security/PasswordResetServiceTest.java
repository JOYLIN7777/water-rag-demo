package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.joylin.waterkb.mapper.UserStore;
import com.joylin.waterkb.service.PasswordResetService;

/**
 * 忘记密码的参数校验单测：不连数据库，只验证"错在哪一步"的判定顺序。
 *
 * <p>这些分支是最容易被用户撞到的（少填、密码太短、账号不存在），
 * 必须给出可操作的提示而不是 500 或空指针。</p>
 */
class PasswordResetServiceTest {

    private final PasswordHasher hasher = new PasswordHasher();
    private final UserStore users;

    PasswordResetServiceTest() {
        users = new UserStore(hasher, "admin|Admin@123456|admin|default|系统管理员");
        users.init();
    }

    /** 不调 init()、jdbc 传 null：校验分支都在碰数据库之前返回 */
    private PasswordResetService svc() {
        return new PasswordResetService(null, users, hasher);
    }

    @Test
    void 缺参数与弱密码在校验阶段就被拦下() {
        PasswordResetService s = svc();
        assertEquals("请填写账号与重置码", s.confirm("", "ABCDEFGH", "NewPass@123"));
        assertEquals("请填写账号与重置码", s.confirm("admin", "   ", "NewPass@123"));
        assertEquals("新密码至少 8 位", s.confirm("admin", "ABCDEFGH", "1234567"));
        assertEquals("账号不存在，或重置码已失效", s.confirm("nobody", "ABCDEFGH", "NewPass@123"));
    }

    @Test
    void 申请与列表在无数据库时降级而不是抛异常() {
        PasswordResetService s = svc();
        assertDoesNotThrow(() -> s.request("admin", "127.0.0.1"));
        assertDoesNotThrow(() -> s.request("nobody", "127.0.0.1"));
        assertDoesNotThrow(() -> s.request(null, null));
        assertTrue(s.list().isEmpty(), "无数据库时返回空列表，页面不显示区块");
    }

    @Test
    void 重置码不存在时不会误改密码() {
        PasswordResetService s = svc();
        String err = s.confirm("admin", "ZZZZZZZZ", "NewPass@123");
        assertNotNull(err, "没有可用重置码时必须返回错误");
        // 密码必须还是原来的
        assertNotNull(users.authenticate("admin", "Admin@123456"));
    }
}
