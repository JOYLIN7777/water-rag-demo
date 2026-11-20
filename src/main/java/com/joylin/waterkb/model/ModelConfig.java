package com.joylin.waterkb.model;

/**
 * 模型配置：一条记录描述一个 OpenAI 兼容端点上的 chat 或 embedding 模型。
 *
 * <p>设计决策：企业作品集的核心卖点——模型可插拔。公有云（DashScope/DeepSeek）
 * 和内网私有部署（vLLM/Ollama/Xinference 暴露的 OpenAI 兼容接口）用同一套结构描述，
 * 切换只改数据库配置，不改代码、不重启容器。</p>
 */
public class ModelConfig {

    private Long id;
    /** 展示名，如 "DashScope-qwen-plus" / "内网-Qwen3.5-397B" */
    private String name;
    /** 模型类型：CHAT / EMBEDDING */
    private String type;
    /** OpenAI 兼容 base-url，如 https://dashscope.aliyuncs.com/compatible-mode 或 http://10.0.0.5:8000/v1 */
    private String baseUrl;
    /** API Key；内网模型可为空字符串（很多内网服务不校验） */
    private String apiKey;
    /** 模型名，如 qwen-plus / text-embedding-v3 / Qwen3.5-397B-A17B */
    private String model;
    /** 向量维度：仅 EMBEDDING 类型有意义，必须与 vector_store 表维度一致 */
    private Integer dimensions;
    /** 是否启用（禁用的配置不参与激活） */
    private boolean enabled = true;
    /** 是否为该类型当前激活的配置 */
    private boolean active;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    /** API Key 脱敏回显：只露前 4 后 4，中间星号 */
    public String maskedKey() {
        String k = apiKey == null ? "" : apiKey;
        if (k.isEmpty()) return "（无需 Key）";
        if (k.length() <= 8) return "****";
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }
}
