package com.joylin.waterkb.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * React SPA 路由回退。
 *
 */
@RestController
public class ConsolePageController {

    @RequestMapping(value = {
            "/login.html", "/index.html",
            "/users.html", "/org.html", "/roles.html", "/perms.html",
            "/docs.html", "/models.html", "/usage.html", "/loginlogs.html", "/audit.html",
            "/manage.html", "/search.html", "/settings.html", "/profile.html",
            "/forgot.html", "/admin.html"
    })
    public ResponseEntity<byte[]> spaFallback() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        if (!resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(resource.getContentAsByteArray());
    }
}
