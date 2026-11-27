package com.joylin.waterkb.controller;

import com.joylin.waterkb.service.EsIndexService;
import com.joylin.waterkb.mapper.SchemaSeedMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 综合健康检查：把 ES / PostgreSQL / Redis 三个依赖的状态一次返回，
 * 供运维和前端状态胶囊使用。原 /api/search/health 保持兼容（只看 ES）。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final EsIndexService es;
    private final SchemaSeedMapper seedMapper;
    private final StringRedisTemplate redis;

    public HealthController(EsIndexService es, SchemaSeedMapper seedMapper, StringRedisTemplate redis) {
        this.es = es;
        this.seedMapper = seedMapper;
        this.redis = redis;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("es", probe(es::healthy));
        components.put("db", probe(() -> {
            return seedMapper.ping() == 1;
        }));
        components.put("redis", probe(() -> {
            redis.hasKey("__health_probe__");
            return true;
        }));
        boolean allUp = components.values().stream().allMatch(v -> Boolean.TRUE.equals(v));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allUp ? "UP" : "DOWN");
        result.put("components", components);
        return result;
    }

    private boolean probe(java.util.function.BooleanSupplier p) {
        try { return p.getAsBoolean(); } catch (Exception e) { return false; }
    }
}
