package com.joylin.waterkb.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.service.AuditService;
import com.joylin.waterkb.service.JwtService;
import com.joylin.waterkb.service.LoginCryptoService;
import com.joylin.waterkb.service.PasswordResetService;
import com.joylin.waterkb.service.PermissionService;
import com.joylin.waterkb.mapper.RefreshTokenStore;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.mapper.UserStore;
import com.joylin.waterkb.mapper.UserStore.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证端点：真实登录（用户名密码 → access + refresh）、刷新、登出、身份回显。
 *
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwt;
    private final UserStore users;
    private final RefreshTokenStore store;
    private final AuditService audit;
    private final PermissionService permissions;
    private final PasswordResetService passwordReset;
    private final LoginCryptoService crypto;
    private final long accessTtl;
    private final long refreshTtl;
    private final boolean issueEndpoint;

    public AuthController(JwtService jwt, UserStore users, RefreshTokenStore store, AuditService audit,
                          PermissionService permissions, PasswordResetService passwordReset,
                          LoginCryptoService crypto,
                          @Value("${security.auth.access-ttl-seconds:1800}") long accessTtl,
                          @Value("${security.auth.refresh-ttl-seconds:604800}") long refreshTtl,
                          @Value("${security.auth.issue-endpoint:false}") boolean issueEndpoint) {
        this.jwt = jwt;
        this.users = users;
        this.store = store;
        this.audit = audit;
        this.permissions = permissions;
        this.passwordReset = passwordReset;
        this.crypto = crypto;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.issueEndpoint = issueEndpoint;
    }

    /**

     * 前端取公钥加密密码（RSA-OAEP/SHA-256），无需登录

     */
    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", crypto.publicKey(), "algorithm", "RSA-OAEP-SHA256");
    }

    /**

     * 页面传 encrypted=true 时，密码字段是前端用公钥加密后的 Base64 密文

     */
    private String passwordOf(Map<String, String> body, String field) {
        if (body == null) return null;
        String value = body.get(field);
        return Boolean.parseBoolean(String.valueOf(body.get("encrypted"))) ? crypto.decrypt(value) : value;
    }

    /**

     * 登录：校验用户名密码，签发 access + refresh。密码错误与用户不存在返回同样错误，防用户枚举。

     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   jakarta.servlet.http.HttpServletRequest req) {
        String username = body == null ? null : body.get("username");
        String password = passwordOf(body, "password");
        UserAccount u = users.authenticate(username, password);
        if (u == null) {
            audit.record(AuditService.of("default", username == null ? "anonymous" : username, "",
                    "POST /api/auth/login", "/api/auth/login", "Login failed",
                    req.getRemoteAddr(), HttpStatus.UNAUTHORIZED.value(), 0, "DENIED"));
            // 账号被管理员停用是常见运维场景，给明确提示，避免用户一直以为自己记错密码。
            // 用户不存在仍统一返回「用户名或密码错误」，不做用户枚举。
            UserAccount found = users.find(username);
            String message = (found != null && !found.enabled())
                    ? "账号已停用，请联系管理员" : "用户名或密码错误";
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", message));
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

    /**

     * 刷新：refresh token 换新的 access + refresh（旧 refresh 立即作废，防重放）

     */
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

    /**

     * 登出：拉黑当前 access 的 jti + 删除对应 refresh（需带当前 access token）

     */
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

    /**

     * 回显当前身份

     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        UserContext.CurrentUser u = UserContext.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", u.userId());
        m.put("groups", u.groups());
        m.put("tenant", u.tenantId());
        m.put("authType", u.authType());
        m.put("anonymous", u.isAnonymous());
        UserStore.UserAccount account = users.find(u.userId());
        if (account != null) {
            m.put("displayName", account.displayName());
            m.put("gender", account.gender());
            m.put("phone", account.phone());
            m.put("avatar", account.avatar());
        }
        return m;
    }

    /**

     * 当前用户修改自己的资料：性别、手机号、头像（头像为 data URL 或预设标识）

     */
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body) {
        String me = UserContext.get().userId();
        if (me == null || me.isBlank()) throw new IllegalArgumentException("请先登录");
        boolean ok = users.updateProfile(me, body.get("gender"), body.get("phone"), body.get("avatar"));
        if (!ok) throw new IllegalArgumentException("账号不存在");
        return Map.of("message", "个人资料已保存");
    }

    /**

     * 当前用户的完整权限视图：功能菜单 + 数据权限 + 工具权限

     */
    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        return permissions.currentPermissions();
    }

    /**

     * 修改密码：校验原密码，新密码至少 8 位；成功后旧 token 仍有效到过期（可主动登出）

     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body,
                                            jakarta.servlet.http.HttpServletRequest req) {
        UserContext.CurrentUser u = UserContext.get();
        if (u.isAnonymous()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "请先登录"));
        }
        String oldPwd = passwordOf(body, "oldPassword");
        String newPwd = passwordOf(body, "newPassword");
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

    /**
     * 忘记密码第一步：提交重置申请。
     *
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody Map<String, String> body,
                                                  jakarta.servlet.http.HttpServletRequest req) {
        String username = body == null ? null : body.get("username");
        passwordReset.request(username, req.getRemoteAddr());
        String who = username == null || username.isBlank() ? "anonymous" : username.trim().toLowerCase();
        audit.record(AuditService.of("default", who, "", "POST /api/auth/password-reset/request",
                "/api/auth/password-reset/request", "Password reset requested",
                req.getRemoteAddr(), HttpStatus.OK.value(), 0, "ALLOW"));
        return ResponseEntity.ok(Map.of(
                "message", "重置申请已提交。请联系系统管理员核身并获取重置码，拿到后在本页输入即可设置新密码。",
                "ttlMinutes", 30));
    }

    /**

     * 忘记密码第二步：凭一次性重置码设置新密码（成功即作废该码）

     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<?> confirmPasswordReset(@RequestBody Map<String, String> body,
                                                  jakarta.servlet.http.HttpServletRequest req) {
        String username = body == null ? null : body.get("username");
        String code = body == null ? null : body.get("code");
        String newPassword = passwordOf(body, "newPassword");
        String err = passwordReset.confirm(username, code, newPassword);
        String who = username == null || username.isBlank() ? "anonymous" : username.trim().toLowerCase();
        audit.record(AuditService.of("default", who, "", "POST /api/auth/password-reset/confirm",
                "/api/auth/password-reset/confirm",
                err == null ? "Password reset completed" : "Password reset failed",
                req.getRemoteAddr(), err == null ? HttpStatus.OK.value() : HttpStatus.BAD_REQUEST.value(), 0,
                err == null ? "ALLOW" : "DENIED"));
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("message", err));
        }
        return ResponseEntity.ok(Map.of("message", "密码已重置，请用新密码登录"));
    }

    /**

     * 演示 token 签发（默认关闭，作品集需要时打开）

     */
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
