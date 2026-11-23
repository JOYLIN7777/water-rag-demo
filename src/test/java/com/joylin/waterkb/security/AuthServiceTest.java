package com.joylin.waterkb.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 登录核心单测：密码哈希不可逆且带盐、校验正确/错误密码、用户库加载与认证。 */
class AuthServiceTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void 密码哈希带盐且不可逆() {
        String h1 = hasher.hash("Admin@123456");
        String h2 = hasher.hash("Admin@123456");
        // 同一密码两次哈希不同（随机盐），且不含明文，也不等于常见摘要
        assertNotEquals(h1, h2);
        assertFalse(h1.contains("Admin@123456"));
        assertTrue(h1.startsWith("pbkdf2$"));
        assertEquals(4, h1.split("\\$").length);
    }

    @Test
    void 正确密码通过错误密码拒绝() {
        String h = hasher.hash("correct-horse");
        assertTrue(hasher.matches("correct-horse", h));
        assertFalse(hasher.matches("wrong", h));
        assertFalse(hasher.matches("", h));
    }

    @Test
    void 损坏的哈希串不会抛异常而是判不匹配() {
        assertFalse(hasher.matches("x", "not-a-hash"));
        assertFalse(hasher.matches("x", "pbkdf2$abc$bad$bad"));
    }

    @Test
    void 用户库启动时哈希明文且认证生效() {
        String line = "admin|Admin@123456|admin,water-dept|dept-a|系统管理员";
        UserStore store = new UserStore(hasher, line);
        store.init();
        // 明文密码不驻留在账号对象里
        UserStore.UserAccount u = store.authenticate("admin", "Admin@123456");
        assertNotNull(u);
        assertFalse(u.toString().contains("Admin@123456"));
        assertEquals("dept-a", u.tenant());
        assertEquals(List.of("admin", "water-dept"), u.groups());
        // 错误密码 / 不存在用户均返回 null
        assertNull(store.authenticate("admin", "wrong"));
        assertNull(store.authenticate("nobody", "Admin@123456"));
    }

    @Test
    void refresh与access令牌类型区分() {
        JwtService jwt = new JwtService(new com.fasterxml.jackson.databind.ObjectMapper(), "unit-test-secret", true);
        String access = jwt.issue("u", List.of(), "default", 3600, "access", "jti-a");
        String refresh = jwt.issue("u", List.of(), "default", 3600, "refresh", "jti-r");
        assertEquals("access", jwt.parseClaimsUnverified(access).path("type").asText());
        assertEquals("refresh", jwt.parseClaimsUnverified(refresh).path("type").asText());
        assertEquals("jti-a", jwt.parseClaimsUnverified(access).path("jti").asText());
        // 两者验签都能解析出身份，类型校验由 Filter/Controller 层负责
        assertNotNull(jwt.verify(access));
        assertNotNull(jwt.verify(refresh));
    }
}
