package com.joylin.waterkb.web;

import com.joylin.waterkb.security.OrgStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织名称字典（所有登录用户可读）。
 *
 * <p>个人中心、治理页、问答页都要显示"所属组织：水利业务组"这类文字。
 * 名称统一由服务端给出，前端就不再写死 default→默认空间 这条映射——
 * 管理员改了组织名，所有页面立刻跟着变。</p>
 *
 * <p>只暴露名称，不含成员数等管理信息；改名/新建/删除仍然只在 {@code /api/admin/orgs}。</p>
 */
@RestController
@RequestMapping("/api")
public class OrgController {

    private final OrgStore orgs;

    public OrgController(OrgStore orgs) { this.orgs = orgs; }

    @GetMapping("/orgs")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : orgs.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", m.get("key"));
            row.put("name", m.get("name"));
            out.add(row);
        }
        return out;
    }
}
