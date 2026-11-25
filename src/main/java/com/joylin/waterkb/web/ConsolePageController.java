package com.joylin.waterkb.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Vue SPA 路由回退。
 *
 * <p>前端迁移到 Vue 3 + Vite 后，所有页面路径（/login.html、/users.html、
 * /admin.html/users.html 等）都由 Vue Router 在浏览器端解析。这里把这些路径
 * 统一回退到同一份 index.html，避免出现 404 或静态页与 Vue 页混杂。</p>
 */
@RestController
public class ConsolePageController {

    /** 旧 static 版的独立页面路径 + Vue 版嵌套路径，统一回退到 SPA 入口 */
    @RequestMapping(value = {
            "/login.html", "/index.html",
            "/users.html", "/org.html", "/roles.html", "/perms.html",
            "/docs.html", "/models.html", "/usage.html", "/loginlogs.html", "/audit.html",
            "/manage.html", "/search.html", "/tree.html", "/settings.html", "/profile.html",
            "/forgot.html",
            "/admin.html", "/admin.html/users.html", "/admin.html/org.html",
            "/admin.html/roles.html", "/admin.html/perms.html", "/admin.html/docs.html",
            "/admin.html/models.html", "/admin.html/usage.html",
            "/admin.html/loginlogs.html", "/admin.html/audit.html"
    })
    public ResponseEntity<byte[]> spaFallback() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(resource.getContentAsByteArray());
    }
}
