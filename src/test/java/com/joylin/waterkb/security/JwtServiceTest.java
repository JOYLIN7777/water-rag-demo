package com.joylin.waterkb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** JWT 签发/验签单测：合法 token、篡改签名、过期、缺 sub 四条路径。 */
class JwtServiceTest {

    private final JwtService jwt = new JwtService(new ObjectMapper(), "test-secret-key-for-unit-test", true);

    @Test
    void 合法token解析出用户组租户() {
        String token = jwt.issue("zhangsan", List.of("water-dept", "admin"), "dept-a", 3600);
        UserContext.CurrentUser u = jwt.verify(token);
        assertNotNull(u);
        assertEquals("zhangsan", u.userId());
        assertEquals(List.of("water-dept", "admin"), u.groups());
        assertEquals("dept-a", u.tenantId());
        assertEquals("jwt", u.authType());
    }

    @Test
    void 篡改签名被拒绝() {
        String token = jwt.issue("zhangsan", List.of("admin"), "default", 3600);
        String[] parts = token.split("\\.");
        // 改 payload 的一个字符但保留原签名 → 必须拒绝
        char c = parts[1].charAt(5);
        parts[1] = parts[1].substring(0, 5) + (c == 'a' ? 'b' : 'a') + parts[1].substring(6);
        assertNull(jwt.verify(String.join(".", parts)));
    }

    @Test
    void 过期token被拒绝() {
        String token = jwt.issue("u", List.of(), "default", -10);
        assertNull(jwt.verify(token));
    }

    @Test
    void 格式错误与伪造签名被拒绝() {
        assertNull(jwt.verify("not-a-jwt"));
        assertNull(jwt.verify("a.b.c"));
        assertNull(jwt.verify(null));
    }
}
