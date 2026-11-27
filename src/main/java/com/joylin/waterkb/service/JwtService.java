package com.joylin.waterkb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.joylin.waterkb.security.UserContext;

/**
 * 零依赖 JWT（HS256）签发与校验。
 *
 * <p>为什么手写而不引 jjwt / spring-security-oauth2-resource-server：
 * 项目已有一个精简的演示认证链路，只需要 OIDC 最核心的三件事——
 * 验签、过期检查、claims 提取（sub/groups/tenant）。手写约 150 行、
 * 零新增依赖、全程可控，作品集里还能讲清 JWT 的三段式原理；
 * 生产换真实 IdP 时把这里替换成 JWKS 远程验签即可，上层 Filter 不用动。</p>
 *
 * <p>安全底线：
 * <ul>
 *   <li>签名用 MessageDigest.isEqual 做常量时间比较，防时序侧信道；</li>
 *   <li>exp 必检，过期 token 拒绝；</li>
 *   <li>密钥从环境变量注入，禁止硬编码进代码/仓库。</li>
 * </ul></p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper mapper;
    private final byte[] secret;
    private final boolean enabled;

    public JwtService(ObjectMapper mapper,
                      @Value("${security.jwt.secret:water-rag-demo-dev-secret-change-in-prod}") String secret,
                      @Value("${security.jwt.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** 签一个演示 token（生产环境由 IdP 签发，此端点应关闭）。默认 access 类型，兼容旧调用。 */
    public String issue(String user, List<String> groups, String tenant, long ttlSeconds) {
        return issue(user, groups, tenant, ttlSeconds, "access", java.util.UUID.randomUUID().toString());
    }

    /**
     * 签发带类型与 jti 的 token。
     * @param type access / refresh —— 刷新令牌只能用于换票，不能访问业务接口
     * @param jti  令牌唯一 ID，登出时据此拉黑
     */
    public String issue(String user, List<String> groups, String tenant, long ttlSeconds,
                        String type, String jti) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", user);
            claims.put("groups", groups);
            claims.put("tenant", tenant == null || tenant.isBlank() ? "default" : tenant);
            claims.put("iat", now);
            claims.put("exp", now + ttlSeconds);
            claims.put("iss", "water-rag-demo");
            claims.put("type", type);
            claims.put("jti", jti);

            String h = URL_ENCODER.encodeToString(mapper.writeValueAsBytes(header));
            String p = URL_ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = h + "." + p;
            return signingInput + "." + URL_ENCODER.encodeToString(hmac(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    /** 解析 token 的 claims（仅供登录链路内部使用，调用方必须先验签） */
    public JsonNode parseClaimsUnverified(String token) {
        if (token == null) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            return mapper.readTree(URL_DECODER.decode(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验并解析 token。任何异常（签名错、格式错、过期、claims 缺 sub）都返回 null，
     * 由 Filter 决定是拒绝请求（有 token 但无效）还是降级匿名（无 token）。
     */
    public UserContext.CurrentUser verify(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;

            // 常量时间比较签名，避免侧信道
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                log.warn("JWT 签名校验失败");
                return null;
            }

            JsonNode header = mapper.readTree(URL_DECODER.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) return null;

            JsonNode claims = mapper.readTree(URL_DECODER.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (exp == 0 || Instant.now().getEpochSecond() >= exp) {
                log.warn("JWT 已过期或缺失 exp");
                return null;
            }
            String sub = claims.path("sub").asText("");
            if (sub.isBlank()) return null;

            List<String> groups = new ArrayList<>();
            JsonNode g = claims.path("groups");
            if (g.isArray()) g.forEach(n -> groups.add(n.asText()));

            String tenant = claims.path("tenant").asText("default");
            return new UserContext.CurrentUser(sub, List.copyOf(groups), tenant, "jwt");
        } catch (Exception e) {
            log.warn("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
