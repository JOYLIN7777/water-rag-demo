package com.joylin.waterkb.rag;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板加载器：从 classpath 读取外置提示词模板并做占位符替换。
 *
 * <p>设计决策：不用 Spring AI 自带的 PromptTemplate（底层 StringTemplate 4），
 * 因为 ST4 对文本中的大括号敏感——而我们的模板内容里可能出现 JSON 示例等
 * 含 {} 的文本，容易解析报错。这里改用最朴素的 "{key}" 字面量替换，
 * 行为可预期，模板维护成本最低。</p>
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
     * @param location 模板位置，如 classpath:prompts/rag-system.st
     * @param vars     占位符名 → 替换内容
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
