package com.joylin.waterkb.audit;

import com.joylin.waterkb.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import com.joylin.waterkb.security.JwtAuthFilter;
import com.joylin.waterkb.service.AuditService;
import com.joylin.waterkb.service.WaterInfoTool;

/**
 * 审计拦截器：所有 /api/** 请求自动落审计，无需在各 Controller 逐一埋点。
 *
 * <p>Order=3，在 JwtAuthFilter(1) 和 UserContext(2) 之后，
 * 因此能拿到解析好的租户/用户/组身份。</p>
 *
 * <p>SSE 流式问答也走这里：doFilter 返回时流可能还在推送，耗时记为首个事件前的时间，
 * 够用于审计"谁发起了问答"。sources/done 事件本身不单独记，避免刷屏。</p>
 */
@Component
@Order(3)
public class AuditFilter extends OncePerRequestFilter {

    private final AuditService audit;

    public AuditFilter(AuditService audit) {
        this.audit = audit;
    }

    /** 高频/无审计价值的端点跳过（健康检查、审计自身查询，避免审计表自我放大） */
    private static final Set<String> SKIP = Set.of(
            "/api/search/health", "/api/health", "/api/audit/logs", "/api/audit/stats",
            // 工具直调由 WaterInfoTool 自己埋点：HTTP 恒 200 但业务可能 DENIED，
            // Filter 只看状态码会误记 SUCCESS，跳过避免重复/失真
            "/api/chat/tool/water");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || SKIP.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        int status = 200;
        Exception failure = null;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } catch (Exception e) {
            status = 500;
            failure = e;
            throw e;
        } finally {
            try {
                UserContext.CurrentUser user = UserContext.get();
                String result = status == 401 || status == 403 ? "DENIED"
                        : (status >= 400 ? "ERROR" : "SUCCESS");
                audit.record(AuditService.of(
                        user.tenantId(), user.userId(), String.join(",", user.groups()),
                        request.getMethod() + " " + path,
                        path,
                        sanitize(request.getQueryString()),
                        clientIp(request), status, System.currentTimeMillis() - start, result));
            } catch (Exception e) {
                // 审计绝不影响主流程
                logger.warn("审计记录失败", e);
            }
        }
    }

    /** 查询串脱敏：API Key / token 等敏感参数值一律打码 */
    private String sanitize(String query) {
        if (query == null || query.isBlank()) return "";
        return query.replaceAll("(?i)(api[_-]?key|token|secret|password)=[^&]*", "$1=***");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
