package com.joylin.waterkb.web;

import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.OrgStore;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计查询端点。
 *
 * <p>默认强制按当前租户过滤——部门账号只能看本部门审计，符合数据隔离。
 * 例外：<b>审计部门</b>（组织名含「审计」或账号带 audit 角色）要看全系统操作记录，
 * 这是合规审计的固有职责，否则审计部门查不到别的部门的操作就失去意义。
 * 能否进入本端点已由类级 {@code @RequirePermission(AUDIT_READ)} 把住，
 * 这里只决定"进来看多少"，不会让无审计权限的账号越权。</p>
 */
@RestController
@RequestMapping("/api/audit")
@RequirePermission(Permission.AUDIT_READ)
public class AuditController {

    private final AuditService audit;
    private final OrgStore orgStore;

    public AuditController(AuditService audit, OrgStore orgStore) {
        this.audit = audit;
        this.orgStore = orgStore;
    }

    /** 审计部门 = 组织名含「审计」或账号带 audit 角色；二者都不满足则只看本部门 */
    private boolean isAuditDepartment(UserContext.CurrentUser user) {
        if (user.hasGroup("admin") || user.hasGroup("audit")) return true;
        String orgName = orgStore.nameMap().getOrDefault(user.tenantId(), "");
        return orgName.contains("审计");
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false, defaultValue = "100") int limit) {
        String tenant = UserContext.get().tenantId();
        boolean all = isAuditDepartment(UserContext.get());
        return Map.of("tenant", tenant, "scope", all ? "all" : "tenant",
                "logs", all ? audit.recentAll(limit) : audit.recent(tenant, limit));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        String tenant = UserContext.get().tenantId();
        boolean all = isAuditDepartment(UserContext.get());
        Map<String, Object> s = all ? audit.statsAll() : audit.stats(tenant);
        return Map.of("tenant", tenant, "scope", all ? "all" : "tenant",
                "total", s.getOrDefault("total", 0L),
                "denied", s.getOrDefault("denied", 0L),
                "errors", s.getOrDefault("errors", 0L));
    }
}
