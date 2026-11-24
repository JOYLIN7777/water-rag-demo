package com.joylin.waterkb.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 后台各功能的独立页面路由。
 *
 * <p>此前用户管理/菜单权限/日志等都挤在 admin.html?panel=xxx，地址栏看不出页面，
 * 菜单权限目录也就没法按"真实页面"配置。这里给每个功能一个独立 .html 路径，
 * 内容仍由同一份 admin.html 渲染（避免复制几千行脚本导致多套页面不一致），
 * 页面里根据 location.pathname 激活对应面板。</p>
 */
@RestController
public class ConsolePageController {

    private static final String[] PAGES = {
            "/users.html", "/org.html", "/roles.html", "/perms.html",
            "/docs.html", "/usage.html", "/loginlogs.html", "/audit.html"
    };

    @RequestMapping(value = {"/users.html", "/org.html", "/roles.html", "/perms.html",
            "/docs.html", "/usage.html", "/loginlogs.html", "/audit.html"}, method = RequestMethod.GET)
    public ResponseEntity<byte[]> consolePage() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/admin.html");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(resource.getContentAsByteArray());
    }
}
