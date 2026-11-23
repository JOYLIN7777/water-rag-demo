package com.joylin.waterkb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.JwtService;
import com.joylin.waterkb.security.PermissionService;
import com.joylin.waterkb.security.RefreshTokenStore;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.security.UserStore;
import com.joylin.waterkb.security.UserStore.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证端点：真实登录（用户名密码 → access + refresh）、刷新、登出、身份回显。
 *
 * <p>另保留 {@code /token} 演示签发端点（作品集现场一键复现 OIDC 链路用），
 * 可通过 {@code security.auth.issue-endpoint=false} 关闭。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwt;
    private final UserStore users;
    private final RefreshTokenStore store;
    private final AuditService audit;
    private final PermissionService permissions;
    private final long accessTtl;
    private final long refreshTtl;
    private final boolean issueEndpoint;

    public AuthController(JwtService jwt, UserStore users, RefreshTokenStore store, AuditService audit,
                          PermissionService permissions,
                          @Value("${security.auth.access-ttl-seconds:1800}") long accessTtl,
                          @Value("${security.auth.refresh-ttl-seconds:604800}") long refreshTtl,
                          @Value("${security.auth.issue-endpoint:false}") boolean issueEndpoint) {
        this.jwt = jwt;
        this.users = users;
        this.store = store;
        this.audit = audit;
        this.permissions = permissions;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.issueEndpoint = issueEndpoint;
    }

    /** 登录：校验用户名密码，签发 access + refresh。密码错误与用户不存在返回同样错误，防用户枚举。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   jakarta.servlet.http.HttpServletRequest req) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        UserAccount u = users.authenticate(username, password);
        if (u == null) {
            audit.record(AuditService.of("default", username == null ? "anonymous" : username, "",
                    "POST /api/auth/login", "/api/auth/login", "Login failed",
                    req.getRemoteAddr(), HttpStatus.UNAUTHORIZED.value(), 0, "DENIED"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "用户名或密码错误"));
        }
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        String access = jwt.issue(u.username(), u.groups(), u.tenant(), accessTtl, "access", accessJti);
        String refresh = jwt.issue(u.username(), u.groups(), u.tenant(), refreshTtl, "refresh", refreshJti);
        store.saveRefresh(refreshJti, u.username(), refreshTtl);

        audit.record(AuditService.of(u.tenant(), u.username(), String.join(",", u.groups()),
                "POST /api/auth/login", "/api/auth/login", "Login success",
                req.getRemoteAddr(), HttpStatus.OK.value(), 0, "ALLOW"));
        return ResponseEntity.ok(Map.of(
                "accessToken", access,
                "refreshToken", refresh,
                "tokenType", "Bearer",
                "expiresIn", accessTtl,
                "user", u.username(),
                "displayName", u.displayName(),
                "groups", u.groups(),
                "tenant", u.tenant()));
    }

    /** 刷新：refresh token 换新的 access + refresh（旧 refresh 立即作废，防重放） */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("refreshToken");
        UserContext.CurrentUser principal = token == null ? null : jwt.verify(token);
        JsonNode claims = jwt.parseClaimsUnverified(token);
        if (principal == null || claims == null || !"refresh".equals(claims.path("type").asText(""))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "无效或过期的刷新令牌"));
        }
        String oldJti = claims.path("jti").asText("");
        if (!store.isRefreshValid(oldJti, principal.userId())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "刷新令牌已失效，请重新登录"));
        }
        store.removeRefresh(oldJti);
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        String access = jwt.issue(principal.userId(), principal.groups(), principal.tenantId(), accessTtl, "access", accessJti);
        String refresh = jwt.issue(principal.userId(), principal.groups(), principal.tenantId(), refreshTtl, "refresh", refreshJti);
        store.saveRefresh(refreshJti, principal.userId(), refreshTtl);
        return ResponseEntity.ok(Map.of(
                "accessToken", access, "refreshToken", refresh,
                "tokenType", "Bearer", "expiresIn", accessTtl));
    }

    /** 登出：拉黑当前 access 的 jti + 删除对应 refresh（需带当前 access token） */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(jakarta.servlet.http.HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            JsonNode claims = jwt.parseClaimsUnverified(token);
            if (claims != null) {
                String jti = claims.path("jti").asText(null);
                store.blacklist(jti, claims.path("exp").asLong(0) - System.currentTimeMillis() / 1000);
            }
        }
        UserContext.CurrentUser u = UserContext.get();
        audit.record(AuditService.of(u.tenantId(), u.userId(), String.join(",", u.groups()),
                "POST /api/auth/logout", "/api/auth/logout", "Logout",
                req.getRemoteAddr(), HttpStatus.OK.value(), 0, "ALLOW"));
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    /** 回显当前身份 */
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

    /** 当前用户的完整权限视图：功能菜单 + 数据权限 + 工具权限 */
    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        return permissions.currentPermissions();
    }

    /** 修改密码：校验原密码，新密码至少 8 位；成功后旧 token 仍有效到过期（可主动登出） */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body,
                                            jakarta.servlet.http.HttpServletRequest req) {
        UserContext.CurrentUser u = UserContext.get();
        if (u.isAnonymous()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "请先登录"));
        }
        String oldPwd = body == null ? null : body.get("oldPassword");
        String newPwd = body == null ? null : body.get("newPassword");
        boolean ok = users.changePassword(u.userId(), oldPwd, newPwd);
        audit.record(AuditService.of(u.tenantId(), u.userId(), String.join(",", u.groups()),
                "POST /api/auth/change-password", "/api/auth/change-password",
                ok ? "Password changed" : "Password change failed",
                req.getRemoteAddr(), ok ? HttpStatus.OK.value() : HttpStatus.BAD_REQUEST.value(), 0,
                ok ? "ALLOW" : "DENIED"));
        if (!ok) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "原密码错误，或新密码长度不足 8 位"));
        }
        return ResponseEntity.ok(Map.of("message", "密码已修改，请使用新密码下次登录"));
    }

    /** 演示 token 签发（默认关闭，作品集需要时打开） */
    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestParam String user,
                                   @RequestParam(required = false, defaultValue = "") String groups,
                                   @RequestParam(required = false, defaultValue = "default") String tenant,
                                   @RequestParam(required = false, defaultValue = "3600") long ttlSeconds) {
        if (!issueEndpoint) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "演示签发端点已关闭"));
        }
        List<String> list = java.util.Arrays.stream(groups.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(Map.of(
                "token", jwt.issue(user, list, tenant, ttlSeconds),
                "tokenType", "Bearer", "expiresIn", ttlSeconds,
                "user", user, "groups", list, "tenant", tenant));
    }
}
