package com.joylin.waterkb.security;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 行级权限上下文：把"当前请求是谁"放进 ThreadLocal，检索层强制读取并注入过滤条件。
 *
 * <p>设计决策（作品集核心卖点）：
 * <ol>
 *   <li><b>不能靠调用方自觉</b>——检索服务自己从上下文拿用户，调用方无法传参绕过；</li>
 *   <li>Demo 级认证用请求头 X-User / X-Groups 模拟（作品集可演示，生产换 JWT/OIDC 只改这一个 Filter）；</li>
 *   <li>缺失身份头时降级为 anonymous，只能看公开文档（allowedGroups 含 public 或 owner= anonymous），
 *       默认拒绝而不是默认放行——这是企业权限设计的第一原则。</li>
 * </ol></p>
 */
@Component
public class UserContext extends OncePerRequestFilter {

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    /** 当前用户（检索层调用） */
    public static CurrentUser get() {
        CurrentUser u = CURRENT.get();
        return u == null ? CurrentUser.ANONYMOUS : u;
    }

    public static void clear() { CURRENT.remove(); }

    /** 供单测注入上下文（生产请求由 Filter 设置，测试无 HTTP 请求故开放此入口） */
    public static void CURRENT_TEST(CurrentUser user) { CURRENT.set(user); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader("X-User");
        String groups = request.getHeader("X-Groups");
        Set<String> groupSet = new LinkedHashSet<>();
        if (groups != null && !groups.isBlank()) {
            Arrays.stream(groups.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).forEach(groupSet::add);
        }
        CURRENT.set(new CurrentUser(
                user == null || user.isBlank() ? "anonymous" : user.trim(),
                List.copyOf(groupSet)));
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove(); // 防止线程池复用导致身份串台
        }
    }

    /** 不可变的当前用户值对象 */
    public record CurrentUser(String userId, List<String> groups) {
        public static final CurrentUser ANONYMOUS = new CurrentUser("anonymous", List.of());

        public boolean isAnonymous() { return "anonymous".equals(userId); }
    }
}
