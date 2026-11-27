package com.joylin.waterkb.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import com.joylin.waterkb.model.ModelConfig;

/**
 * 模型配置仓库：JdbcTemplate 直接建表 + CRUD。
 *
 * <p>与 VectorStoreAdminRepository 同一套风格——不引入 JPA/MyBatis，
 * 一个 Demo 作品不需要 ORM，SQL 直白可查。表在启动时自动建（幂等），
 * 首次启动为空时从环境变量种子导入现有 DashScope 配置，保证 Docker 部署零改动。</p>
 */
@Repository
public class ModelConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigRepository.class);

    private final JdbcTemplate jdbc;
    private final ModelConfigMapper mapper;
    // 种子配置：与 application.yml 的 AI_* 环境变量保持一致
    private final String seedApiKey;
    private final String seedBaseUrl;
    private final String seedChatModel;
    private final String seedEmbeddingModel;
    private final int seedDimensions;

    public ModelConfigRepository(JdbcTemplate jdbc, ModelConfigMapper mapper,
                                 @Value("${spring.ai.openai.api-key:}") String seedApiKey,
                                 @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String seedBaseUrl,
                                 @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String seedChatModel,
                                 @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}") String seedEmbeddingModel,
                                 @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int seedDimensions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.seedApiKey = seedApiKey;
        this.seedBaseUrl = seedBaseUrl;
        this.seedChatModel = seedChatModel;
        this.seedEmbeddingModel = seedEmbeddingModel;
        this.seedDimensions = seedDimensions;
    }

    private static final RowMapper<ModelConfig> MAPPER = (rs, i) -> {
        ModelConfig c = new ModelConfig();
        c.setId(rs.getLong("id"));
        c.setName(rs.getString("name"));
        c.setType(rs.getString("type"));
        c.setBaseUrl(rs.getString("base_url"));
        c.setApiKey(rs.getString("api_key"));
        c.setModel(rs.getString("model_name"));
        c.setDimensions((Integer) rs.getObject("dimensions"));
        c.setEnabled(rs.getBoolean("enabled"));
        c.setActive(rs.getBoolean("is_active"));
        c.setDefault(rs.getBoolean("is_default"));
        c.setCreatedAt(rs.getString("created_at"));
        c.setUpdatedAt(rs.getString("updated_at"));
        return c;
    };

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS model_config (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(200) NOT NULL,
                type VARCHAR(20) NOT NULL CHECK (type IN ('CHAT','EMBEDDING')),
                base_url VARCHAR(500) NOT NULL,
                api_key VARCHAR(1000),
                model_name VARCHAR(200) NOT NULL,
                dimensions INTEGER,
                enabled BOOLEAN NOT NULL DEFAULT TRUE,
                is_active BOOLEAN NOT NULL DEFAULT FALSE,
                is_default BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("ALTER TABLE model_config ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE");
        // 每种类型至多一个 active：partial unique index 保证数据一致性
        // CHAT 支持多个同时激活（提问框可选），只有 EMBEDDING 必须唯一（向量库维度只能有一套）
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_model_active_embedding " +
                "ON model_config(type) WHERE is_active = TRUE AND type = 'EMBEDDING'");
        seedIfEmpty();
    }

    /** 首次启动（表空）时导入环境变量配置，保持原有 Docker 部署无需额外配置 */
    private void seedIfEmpty() {
        Integer count = mapper.count();
        if (count != null && count > 0) return;
        insert("DashScope · " + seedChatModel, "CHAT", seedBaseUrl, seedApiKey, seedChatModel, null, true);
        insert("DashScope · " + seedEmbeddingModel, "EMBEDDING", seedBaseUrl, seedApiKey, seedEmbeddingModel, seedDimensions, true);
        log.info("模型配置表为空，已从环境变量导入种子配置（chat={}, embedding={}, dims={}）",
                seedChatModel, seedEmbeddingModel, seedDimensions);
    }

    public List<ModelConfig> findAll() { return mapper.findAll(); }

    public ModelConfig findById(long id) { return mapper.findById(id); }

    public ModelConfig findActive(String type) {
        // 最后激活的作为全局默认模型，对所有角色生效（多模型同时激活时按 updated_at 倒序）
        List<ModelConfig> list = mapper.findActive(type);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全局默认模型：显式 is_default 优先；历史数据没有默认时回落到最后激活的 */
    public ModelConfig findDefault(String type) {
        List<ModelConfig> list = mapper.findDefault(type);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 设为默认：同类型先清旧默认，再设置目标并顺带激活 */
    public int setDefault(long id) {
        ModelConfig c = findById(id);
        if (c == null) return 0;
        mapper.clearDefault(c.getType());
        mapper.setDefault(id);
        return 1;
    }

    /** 激活指定配置：先把同类型全部取消激活，再激活目标（事务由调用方保证或单条更新即可） */
    public int activate(long id) {
        ModelConfig c = findById(id);
        if (c == null) return 0;
        if ("EMBEDDING".equalsIgnoreCase(c.getType())) {
            mapper.clearActiveByType(c.getType());
        }
        return mapper.activate(id);
    }

    /** 禁用配置时一并取消激活，否则列表还显示「使用中」，代理仍在调一个已禁用的模型 */
    public int deactivate(long id) {
        return mapper.deactivate(id);
    }

    public int insert(String name, String type, String baseUrl, String apiKey, String model, Integer dimensions, boolean active) {
        return mapper.insert(name, type, baseUrl, apiKey == null ? "" : apiKey, model, dimensions, active);
    }

    /** 更新（API Key 传 null 表示保持原值，便于脱敏回显后编辑不改 Key） */
    public int update(long id, String name, String type, String baseUrl, String apiKey, String model, Integer dimensions, boolean enabled) {
        ModelConfig old = findById(id);
        if (old == null) return 0;
        // 空值或脱敏串（含 ****）都表示保持原 Key，避免把掩码写回数据库
        String key = (apiKey == null || apiKey.isEmpty() || apiKey.contains("****")) ? old.getApiKey() : apiKey;
        return mapper.update(id, name, type, baseUrl, key, model, dimensions, enabled);
    }

    public int delete(long id) {
        return mapper.delete(id);
    }

    /** ES 索引维度（来自 es.dimensions 配置），用于校验 embedding 切换兼容性 */
    public Integer vectorDimension() {
        return seedDimensions; // ES mapping 固定该维度，切不同维度模型必须拒绝
    }
}
