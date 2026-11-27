package com.joylin.waterkb.web;

import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.usage.UsageRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 个人中心用量：任何角色都只看本人，不要求后台 usage:read 权限。
 * 与后台 /api/usage/summary（admin 全站、有权限者本部门）刻意分开，避免权限口径混淆。
 */
@RestController
@RequestMapping("/api/me")
public class PersonalUsageController {

    private final UsageRecorder recorder;

    public PersonalUsageController(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        Map<String, Object> mine = recorder.summaryOfUser(UserContext.get().userId());
        mine.put("scope", "self");
        mine.put("scopeLabel", "仅本人");
        return mine;
    }
}
