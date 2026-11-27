package com.joylin.waterkb.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板加载器：从 classpath 读取外置提示词模板并做占位符替换。
 *
 */
@Component
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;

    public PromptTemplateLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 加载模板并替换占位符。
     *
     */
    public String render(String location, Map<String, String> vars) {
        Resource resource = resourceLoader.getResource(location);
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 模板加载失败：" + location, e);
        }
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }
}
