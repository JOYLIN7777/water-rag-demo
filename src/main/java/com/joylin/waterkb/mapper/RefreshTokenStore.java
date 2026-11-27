package com.joylin.waterkb.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌会话存储：refresh token 白名单 + access token 登出黑名单。
 *
 * <p>有了它，登出才能"真正失效"——JWT 本身无状态，光清前端 cookie 服务端仍认旧 token，
 * 是常见安全漏洞。这里用 jti 作键：登出把 access 的 jti 拉黑到过期为止，同时删掉对应 refresh。</p>
 *
 * <p>Redis 不可用时降级到进程内 ConcurrentHashMap（单实例可用；多实例请务必启用 Redis），
 * 保证作品集在无本机 Redis 时登录链路仍可演示。</p>
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final String REFRESH_PREFIX = "water:auth:refresh:";
    private static final String BLACKLIST_PREFIX = "water:auth:blacklist:";

    private final StringRedisTemplate redis;
    // key=jti, value=username；内存降级
    private final Map<String, String> refreshMem = new ConcurrentHashMap<>();
    private final Map<String, Long> blacklistMem = new ConcurrentHashMap<>();
    private volatile boolean redisDown = false;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 保存 refresh token（jti -> username），带过期 */
    public void saveRefresh(String jti, String username, long ttlSeconds) {
        tryRedis(() -> redis.opsForValue().set(REFRESH_PREFIX + jti, username, Duration.ofSeconds(ttlSeconds)));
        if (redisDown) refreshMem.put(jti, username);
    }

    /** 校验 refresh token 的 jti 是否有效且属于该用户 */
    public boolean isRefreshValid(String jti, String username) {
        if (jti == null || jti.isBlank()) return false;
        try {
            String v = redis.opsForValue().get(REFRESH_PREFIX + jti);
            redisDown = false;
            if (v != null) return v.equals(username);
            return false;
        } catch (Exception e) {
            redisDown = true;
            return username.equals(refreshMem.get(jti));
        }
    }

    /** 删除 refresh token（刷新时轮换 / 登出） */
    public void removeRefresh(String jti) {
        tryRedis(() -> redis.delete(REFRESH_PREFIX + jti));
        if (redisDown) refreshMem.remove(jti);
    }

    /** 拉黑 access token，直到其自然过期（ttl<=0 不拉黑） */
    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank() || ttlSeconds <= 0) return;
        tryRedis(() -> redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds)));
        if (redisDown) blacklistMem.put(jti, System.currentTimeMillis() / 1000 + ttlSeconds);
    }

    /** access token 是否已被登出拉黑 */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean hit = redis.hasKey(BLACKLIST_PREFIX + jti);
            redisDown = false;
            return Boolean.TRUE.equals(hit);
        } catch (Exception e) {
            redisDown = true;
            Long exp = blacklistMem.get(jti);
            if (exp == null) return false;
            if (exp < System.currentTimeMillis() / 1000) { blacklistMem.remove(jti); return false; }
            return true;
        }
    }

    private void tryRedis(Runnable op) {
        try { op.run(); redisDown = false; }
        catch (Exception e) { redisDown = true; log.debug("Redis 不可用，令牌会话降级到内存：{}", e.getMessage()); }
    }
}
