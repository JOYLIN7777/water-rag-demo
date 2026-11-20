package com.joylin.waterkb.web;

import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计查询端点（强制按当前租户过滤，调用方无法查看别的租户审计）。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false, defaultValue = "100") int limit) {
        String tenant = UserContext.get().tenantId();
        return Map.of("tenant", tenant, "logs", audit.recent(tenant, limit));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        String tenant = UserContext.get().tenantId();
        Map<String, Object> s = audit.stats(tenant);
        return Map.of("tenant", tenant,
                "total", s.getOrDefault("total", 0L),
                "denied", s.getOrDefault("denied", 0L),
                "errors", s.getOrDefault("errors", 0L));
    }
}
