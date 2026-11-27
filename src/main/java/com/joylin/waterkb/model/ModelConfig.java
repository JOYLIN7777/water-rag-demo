package com.joylin.waterkb.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 模型配置表实体。
 */
@Table(name = "model_config")
public class ModelConfig {

    /**
     * 主键。
     */
    @Id
    @Column(name = "id", insertable = false)
    private Long id;

    /**
     * 展示名称。
     */
    @Column(name = "name")
    @Schema(description = "展示名称")
    private String name;

    /**
     * 模型类型。
     */
    @Column(name = "type")
    @Schema(description = "模型类型")
    private String type;

    /**
     * 接口地址。
     */
    @Column(name = "base_url")
    @Schema(description = "接口地址")
    private String baseUrl;

    /**
     * 接口密钥。
     */
    @Column(name = "api_key")
    @Schema(description = "接口密钥")
    private String apiKey;

    /**
     * 模型标识。
     */
    @Column(name = "model_name")
    @Schema(description = "模型标识")
    private String model;

    /**
     * 向量维度。
     */
    @Column(name = "dimensions")
    @Schema(description = "向量维度")
    private Integer dimensions;

    /**
     * 启用标志。
     */
    @Column(name = "enabled")
    @Schema(description = "启用标志")
    private boolean enabled = true;

    /**
     * 激活标志。
     */
    @Column(name = "is_active")
    @Schema(description = "激活标志")
    private boolean active;

    /**
     * 默认标志。
     */
    @Column(name = "is_default")
    @Schema(description = "默认标志")
    private boolean isDefault;

    /**
     * 创建时间。
     */
    @Column(name = "created_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 获取主键。
     */
    public Long getId() { return id; }

    /**
     * 设置主键。
     */
    public void setId(Long id) { this.id = id; }

    /**
     * 获取展示名称。
     */
    public String getName() { return name; }

    /**
     * 设置展示名称。
     */
    public void setName(String name) { this.name = name; }

    /**
     * 获取模型类型。
     */
    public String getType() { return type; }

    /**
     * 设置模型类型。
     */
    public void setType(String type) { this.type = type; }

    /**
     * 获取接口地址。
     */
    public String getBaseUrl() { return baseUrl; }

    /**
     * 设置接口地址。
     */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * 获取接口密钥。
     */
    public String getApiKey() { return apiKey; }

    /**
     * 设置接口密钥。
     */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /**
     * 获取模型标识。
     */
    public String getModel() { return model; }

    /**
     * 设置模型标识。
     */
    public void setModel(String model) { this.model = model; }

    /**
     * 获取向量维度。
     */
    public Integer getDimensions() { return dimensions; }

    /**
     * 设置向量维度。
     */
    public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }

    /**
     * 获取启用标志。
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置启用标志。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取激活标志。
     */
    public boolean isActive() { return active; }

    /**
     * 设置激活标志。
     */
    public void setActive(boolean active) { this.active = active; }

    /**
     * 获取默认标志。
     */
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }

    /**
     * 设置默认标志。
     */
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    /**
     * 获取创建时间。
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 设置创建时间。
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * 获取更新时间。
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * 设置更新时间。
     */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 获取脱敏密钥。
     */
    public String maskedKey() {
        String k = apiKey == null ? "" : apiKey;
        if (k.isEmpty()) return "（无需 Key）";
        if (k.length() <= 8) return "****";
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }
}
