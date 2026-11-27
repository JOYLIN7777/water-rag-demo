package com.joylin.waterkb.security;

import static com.joylin.waterkb.common.ErrorCode.UNAUTHORIZED;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * 认证入口（企业级登录 + OIDC 骨架）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>白名单</b>（登录、刷新、健康检查、静态资源）直接放行；</li>
 *   <li>带 Bearer token → 校验签名/过期/类型(必须 access)/登出黑名单，有效才装填身份；
 *       任何无效都直接 401，绝不降级——"带 token 却放行"是认证绕过漏洞；</li>
 *   <li>不带 token：{@code requireLogin=true}（默认）时对 API 请求返回 401；
 *       {@code =false} 时降级到 X-User/X-Groups 演示头或 anonymous，保留作品集现场演示能力；</li>
 *   <li>401 全部显式记审计，否则认证失败逃逸出审计视野是合规大忌。</li>
 * </ul></p>
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 无需登录即可访问的路径前缀 */
    private static final Set<String> WHITE_LIST = Set.of(
            "/api/auth/login", "/api/auth/refresh", "/api/auth/public-key", "/api/search/health", "/api/health", "/", "/index.html", "/login.html",
            // 忘记密码：用户还没登录才需要这两个接口和页面，必须放行
            "/api/auth/password-reset/request", "/api/auth/password-reset/confirm", "/forgot.html"
    );

    private final JwtService jwt;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final RefreshTokenStore store;
    private final boolean requireLogin;
    private final boolean jwtEnabled;

    public JwtAuthFilter(JwtService jwt, ObjectMapper mapper, AuditService audit, RefreshTokenStore store,
                         @Value("${security.auth.require-login:true}") boolean requireLogin) {
        this.jwt = jwt;
        this.mapper = mapper;
        this.audit = audit;
        this.store = store;
        this.requireLogin = requireLogin;
        this.jwtEnabled = jwt.enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isWhite(path)) { chain.doFilter(request, response); return; }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean hasBearer = auth != null && auth.startsWith("Bearer ");

        if (hasBearer) {
            // JWT 功能被显式关闭时，带 token 也无意义，按未认证处理
            if (jwtEnabled) {
                String token = auth.substring(7).trim();
                UserContext.CurrentUser principal = jwt.verify(token);
                JsonNode claims = jwt.parseClaimsUnverified(token);
                String type = claims == null ? null : claims.path("type").asText("access");
                String jti = claims == null ? null : claims.path("jti").asText(null);
                boolean refreshToken = "refresh".equals(type);
                boolean blacklisted = store.isBlacklisted(jti);
                if (principal == null || refreshToken || blacklisted) {
                    reject(request, response, refreshToken ? "Refresh token cannot access APIs" :
                            (blacklisted ? "Token has been logged out" : "Invalid or expired bearer token"));
                    return;
                }
                request.setAttribute(UserContext.JWT_PRINCIPAL, principal);
                // 日志按人和租户可查
                org.slf4j.MDC.put("userId", principal.userId());
                org.slf4j.MDC.put("tenantId", principal.tenantId());
                try { chain.doFilter(request, response); }
                finally { org.slf4j.MDC.remove("userId"); org.slf4j.MDC.remove("tenantId"); }
                return;
            }
            reject(request, response, "Authentication is disabled");
            return;
        }

        // 无 token：强制登录模式拦截 API；演示模式放行给 UserContext 降级
        if (requireLogin && path.startsWith("/api/")) {
            reject(request, response, "Login required");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isWhite(String path) {
        if (WHITE_LIST.contains(path)) return true;
        // 静态资源一律放行，否则登录页的 css/js 会被拦截无法渲染
        return path.startsWith("/assets/") || path.endsWith(".css") || path.endsWith(".js")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".svg");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        audit.record(AuditService.of("default", "anonymous", "",
                request.getMethod() + " " + request.getRequestURI(), request.getRequestURI(),
                reason, request.getRemoteAddr(), HttpServletResponse.SC_UNAUTHORIZED, 0, "DENIED"));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("code", UNAUTHORIZED, "message", reason));
    }
}
