package com.joylin.waterkb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Bearer Token 认证入口（OIDC/SSO 骨架）。
 *
 * <p>规则：
 * <ul>
 *   <li>请求带 Authorization: Bearer xxx → 校验；有效则把身份写入 request attribute，
 *       交给 UserContext Filter 装填 ThreadLocal；无效/过期直接 401，绝不降级——
 *       带 token 却放行是典型的认证绕过漏洞；</li>
 *   <li>请求不带 token → 放行，由 UserContext 降级到 X-User/X-Groups 演示头或 anonymous；</li>
 *   <li>/api/auth/token（签发演示 token）本身不需要认证。</li>
 * </ul></p>
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtService jwt, ObjectMapper mapper) {
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!jwt.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            UserContext.CurrentUser principal = jwt.verify(token);
            if (principal == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getWriter(),
                        Map.of("code", 401, "message", "Invalid or expired bearer token"));
                return;
            }
            request.setAttribute(UserContext.JWT_PRINCIPAL, principal);
        }
        chain.doFilter(request, response);
    }
}
