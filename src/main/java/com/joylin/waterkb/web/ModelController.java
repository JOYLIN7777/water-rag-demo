package com.joylin.waterkb.web;

import com.joylin.waterkb.model.ModelConfig;
import com.joylin.waterkb.model.ModelConfigRepository;
import com.joylin.waterkb.model.ModelRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理接口（作品集重点：模型可插拔、数据出域可控的落地证据）。
 *
 * 端点：
 * GET    /api/models              列表（API Key 脱敏）
 * GET    /api/models/{id}         详情（API Key 脱敏）
 * POST   /api/models              新增
 * PUT    /api/models/{id}         修改（API Key 传空表示保持原值）
 * DELETE /api/models/{id}         删除
 * POST   /api/models/{id}/activate 激活（EMBEDDING 校验维度兼容）
 * POST   /api/models/{id}/test     连通性测试（真实调用并计时）
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelConfigRepository repository;
    private final ModelRegistry registry;

    public ModelController(ModelConfigRepository repository, ModelRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @GetMapping
    public List<ModelConfig> list() {
        return repository.findAll().stream().map(ModelController::masked).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelConfig> get(@PathVariable long id) {
        ModelConfig c = repository.findById(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(masked(c));
    }

    /** 脱敏副本：API Key 只回显前 4 后 4，绝不把明文发到前端 */
    private static ModelConfig masked(ModelConfig src) {
        ModelConfig c = new ModelConfig();
        c.setId(src.getId());
        c.setName(src.getName());
        c.setType(src.getType());
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.maskedKey());
        c.setModel(src.getModel());
        c.setDimensions(src.getDimensions());
        c.setEnabled(src.isEnabled());
        c.setActive(src.isActive());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        return c;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ModelConfig body) {
        validate(body);
        repository.insert(body.getName(), body.getType(), body.getBaseUrl(), body.getApiKey(),
                body.getModel(), body.getDimensions(), false);
        return Map.of("message", "模型配置已创建", "name", body.getName());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody ModelConfig body) {
        validate(body);
        if (repository.update(id, body.getName(), body.getType(), body.getBaseUrl(), body.getApiKey(),
                body.getModel(), body.getDimensions(), body.isEnabled()) == 0) {
            throw new IllegalArgumentException("模型配置不存在：" + id);
        }
        // 改的是激活配置 → 缓存与代理一起刷新
        ModelConfig current = repository.findById(id);
        if (current != null && current.isActive()) registry.invalidate();
        return Map.of("message", "模型配置已更新", "id", id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        ModelConfig c = repository.findById(id);
        if (c == null) throw new IllegalArgumentException("模型配置不存在：" + id);
        if (c.isActive()) throw new IllegalArgumentException("不能删除正在使用的模型，请先激活其他配置");
        repository.delete(id);
        return Map.of("message", "已删除", "id", id);
    }

    /** 激活：维度校验 → 更新 active → 清缓存 + 换代理 */
    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable long id) {
        ModelConfig c = repository.findById(id);
        if (c == null) throw new IllegalArgumentException("模型配置不存在：" + id);
        if (!c.isEnabled()) throw new IllegalArgumentException("该配置已禁用，无法激活");
        registry.validateBeforeActivate(c);
        if (repository.activate(id) == 0) throw new IllegalArgumentException("激活失败：" + id);
        registry.invalidate();
        return Map.of("message", "已激活：" + c.getName(), "type", c.getType());
    }

    /** 连通性测试：真实调用模型端点，返回耗时与错误 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id) {
        ModelConfig c = repository.findById(id);
        if (c == null) throw new IllegalArgumentException("模型配置不存在：" + id);
        return Map.of("name", c.getName(), "type", c.getType(), "result", registry.test(c));
    }

    private void validate(ModelConfig c) {
        if (c.getName() == null || c.getName().isBlank()) throw new IllegalArgumentException("名称不能为空");
        if (!"CHAT".equalsIgnoreCase(c.getType()) && !"EMBEDDING".equalsIgnoreCase(c.getType())) {
            throw new IllegalArgumentException("type 只能是 CHAT 或 EMBEDDING");
        }
        if (c.getBaseUrl() == null || !c.getBaseUrl().startsWith("http")) {
            throw new IllegalArgumentException("base-url 必须以 http:// 或 https:// 开头");
        }
        if (c.getModel() == null || c.getModel().isBlank()) throw new IllegalArgumentException("模型名不能为空");
        if ("EMBEDDING".equalsIgnoreCase(c.getType()) && c.getDimensions() == null) {
            throw new IllegalArgumentException("EMBEDDING 配置必须填写维度");
        }
    }
}
