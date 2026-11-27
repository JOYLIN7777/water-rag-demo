package com.joylin.waterkb.security;

import static com.joylin.waterkb.common.ErrorCode.FORBIDDEN;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.service.AuditService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import com.joylin.waterkb.service.RolePermissions;

/**
 * 权限拦截器：在 Controller 方法执行前校验 {@link RequirePermission} 声明的权限码。
 *
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final RolePermissions rolePermissions;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public PermissionInterceptor(RolePermissions rolePermissions, AuditService audit, ObjectMapper mapper) {
        this.rolePermissions = rolePermissions;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // SSE 这类异步请求在流完成后会触发一次 ASYNC dispatch 重新经过拦截器链。
        // 那时身份 Filter 的 finally 已清理 ThreadLocal（get() 得到 anonymous），
        // 且响应已用 getOutputStream() 提交——放行这次 dispatch，否则会误判匿名、
        // 走 403 分支调 getWriter()，与 getOutputStream() 冲突导致连接被异常关闭，
        // 浏览器端表现为流式回答后冒出 network error。
        if (request.getDispatcherType() == DispatcherType.ASYNC) return true;
        if (!(handler instanceof HandlerMethod hm)) return true;

        RequirePermission ann = hm.getMethodAnnotation(RequirePermission.class);
        if (ann == null) ann = hm.getBeanType().getAnnotation(RequirePermission.class);
        if (ann == null) return true;

        UserContext.CurrentUser user = UserContext.get();
        if (rolePermissions.allows(user, ann.value(), ann.all())) return true;

        String reason = "权限不足：需要 " + String.join(" / ", ann.value());
        audit.record(AuditService.of(user.tenantId(), user.userId(), "",
                request.getMethod() + " " + request.getRequestURI(), request.getRequestURI(),
                reason, request.getRemoteAddr(), HttpServletResponse.SC_FORBIDDEN, 0, "DENIED"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("code", FORBIDDEN, "message", "没有操作权限"));
        return false;
    }
}
