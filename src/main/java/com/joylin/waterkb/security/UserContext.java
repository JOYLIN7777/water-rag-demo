package com.joylin.waterkb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一身份上下文：把"当前请求是谁 / 属于哪个租户 / 用什么方式认证"放进 ThreadLocal。
 *
 */
@Component
@Order(2) // JwtAuthFilter 是 @Order(1)，必须先解析 token
public class UserContext extends OncePerRequestFilter {

    /**

     * JWT Filter 写入 request attribute 的身份对象键

     */
    public static final String JWT_PRINCIPAL = "water.rag.jwt.principal";

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    public static CurrentUser get() {
        CurrentUser u = CURRENT.get();
        return u == null ? CurrentUser.ANONYMOUS : u;
    }

    public static void clear() { CURRENT.remove(); }

    /**

     * 供单测注入上下文（生产请求由 Filter 设置）

     */
    public static void CURRENT_TEST(CurrentUser user) { CURRENT.set(user); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object jwtPrincipal = request.getAttribute(JWT_PRINCIPAL);
        if (jwtPrincipal instanceof CurrentUser cu) {
            CURRENT.set(cu);
        } else {
            // 演示头：X-User / X-Groups / X-Tenant（生产由 JWT 覆盖，这里保留便于作品集现场演示）
            String user = request.getHeader("X-User");
            String groups = request.getHeader("X-Groups");
            String tenant = request.getHeader("X-Tenant");
            Set<String> groupSet = new LinkedHashSet<>();
            if (groups != null && !groups.isBlank()) {
                Arrays.stream(groups.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).forEach(groupSet::add);
            }
            CURRENT.set(new CurrentUser(
                    user == null || user.isBlank() ? "anonymous" : user.trim(),
                    List.copyOf(groupSet),
                    tenant == null || tenant.isBlank() ? "default" : tenant.trim(),
                    "header"));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
        }
    }

    /**

     * 不可变的当前用户值对象。两参数构造保留兼容旧调用/单测（默认租户 default）。

     */
    public record CurrentUser(String userId, List<String> groups, String tenantId, String authType) {

        public CurrentUser(String userId, List<String> groups) {
            this(userId, groups, "default", "header");
        }

        public static final CurrentUser ANONYMOUS =
                new CurrentUser("anonymous", List.of(), "default", "anonymous");

        public boolean isAnonymous() { return "anonymous".equals(userId); }

        public boolean hasGroup(String group) { return groups.contains(group); }
    }
}
