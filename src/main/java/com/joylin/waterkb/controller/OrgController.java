package com.joylin.waterkb.controller;

import com.joylin.waterkb.mapper.OrgStore;
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
 *
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
            // 前端侧栏/用户树的层级全靠 parentKey；之前只发 key+name，
            // 数据库里明明是树，界面却把所有部门都画成顶层兄弟。
            row.put("parentKey", m.getOrDefault("parentKey", ""));
            out.add(row);
        }
        return out;
    }
}
