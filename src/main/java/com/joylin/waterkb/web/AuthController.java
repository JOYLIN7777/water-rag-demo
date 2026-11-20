package com.joylin.waterkb.web;

import com.joylin.waterkb.security.JwtService;
import com.joylin.waterkb.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证演示端点（作品集用：现场签 token → 带 token 访问 → 看身份生效）。
 *
 * <p>生产环境必须关闭 /token 端点（security.jwt.issue-endpoint=false），
 * 由真正的 IdP 签发 token；这里保留是为了让评审能一键复现 OIDC 链路。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwt;

    public AuthController(JwtService jwt) {
        this.jwt = jwt;
    }

    /** 签发演示 token：sub=用户，groups=组，tenant=租户，TTL 默认 1 小时 */
    @PostMapping("/token")
    public Map<String, Object> token(@RequestParam String user,
                                     @RequestParam(required = false, defaultValue = "") String groups,
                                     @RequestParam(required = false, defaultValue = "default") String tenant,
                                     @RequestParam(required = false, defaultValue = "3600") long ttlSeconds) {
        List<String> list = java.util.Arrays.stream(groups.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return Map.of(
                "token", jwt.issue(user, list, tenant, ttlSeconds),
                "tokenType", "Bearer",
                "expiresIn", ttlSeconds,
                "user", user,
                "groups", list,
                "tenant", tenant);
    }

    /** 回显当前请求解析出的身份（验证 JWT 是否生效、租户/组是否正确） */
    @GetMapping("/me")
    public Map<String, Object> me() {
        UserContext.CurrentUser u = UserContext.get();
        return Map.of(
                "user", u.userId(),
                "groups", u.groups(),
                "tenant", u.tenantId(),
                "authType", u.authType(),
                "anonymous", u.isAnonymous());
    }
}
