package com.joylin.waterkb.web;

import com.joylin.waterkb.audit.AuditService;
import com.joylin.waterkb.security.OrgStore;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * 日志导出：type=login（登录日志）/ operation（操作日志），服务端最多导出 5000 条。
     * 页面上的前端导出只覆盖已加载的 500 条，这里给合规归档用完整数据。
     */
    @GetMapping(value = "/logs/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "all") String type) {
        String kind = "login".equals(type) ? "login" : "operation";
        boolean all = isAuditDepartment(UserContext.get());
        List<Map<String, Object>> rows = audit.export(UserContext.get().tenantId(), all, kind, 5000);
        String csv = toCsv(rows);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + (kind.equals("login") ? "login-log" : "operation-log")
                        + "-" + LocalDate.now() + ".csv\"");
        return ResponseEntity.ok().headers(headers)
                .body(("\ufeff" + csv).getBytes(StandardCharsets.UTF_8));
    }

    /** CSV 转义：双引号翻倍，字段统一加引号，避免逗号/换行破坏列结构 */
    private String toCsv(List<Map<String, Object>> rows) {
        String[] keys = {"时间", "部门", "账号", "角色组", "动作", "资源", "状态", "耗时(ms)", "结果", "来源IP", "详情"};
        String[] fields = {"ts", "tenant_id", "user_id", "groups", "action", "resource",
                "status", "duration_ms", "result", "ip", "detail"};
        StringBuilder sb = new StringBuilder(String.join(",", keys)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String f : fields) {
                String v = String.valueOf(row.getOrDefault(f, ""));
                cells.add('"' + v.replace("\"", "\"\"") + '"');
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
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
