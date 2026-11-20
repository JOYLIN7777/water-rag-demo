package com.joylin.waterkb.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

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
    // 种子配置：与 application.yml 的 AI_* 环境变量保持一致
    private final String seedApiKey;
    private final String seedBaseUrl;
    private final String seedChatModel;
    private final String seedEmbeddingModel;
    private final int seedDimensions;

    public ModelConfigRepository(JdbcTemplate jdbc,
                                 @Value("${spring.ai.openai.api-key:}") String seedApiKey,
                                 @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String seedBaseUrl,
                                 @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String seedChatModel,
                                 @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}") String seedEmbeddingModel,
                                 @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int seedDimensions) {
        this.jdbc = jdbc;
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
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        // 每种类型至多一个 active：partial unique index 保证数据一致性
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_model_active_type " +
                "ON model_config(type) WHERE is_active = TRUE");
        seedIfEmpty();
    }

    /** 首次启动（表空）时导入环境变量配置，保持原有 Docker 部署无需额外配置 */
    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM model_config", Integer.class);
        if (count != null && count > 0) return;
        insert("DashScope · " + seedChatModel, "CHAT", seedBaseUrl, seedApiKey, seedChatModel, null, true);
        insert("DashScope · " + seedEmbeddingModel, "EMBEDDING", seedBaseUrl, seedApiKey, seedEmbeddingModel, seedDimensions, true);
        log.info("模型配置表为空，已从环境变量导入种子配置（chat={}, embedding={}, dims={}）",
                seedChatModel, seedEmbeddingModel, seedDimensions);
    }

    public List<ModelConfig> findAll() {
        return jdbc.query("SELECT * FROM model_config ORDER BY id", MAPPER);
    }

    public ModelConfig findById(long id) {
        List<ModelConfig> list = jdbc.query("SELECT * FROM model_config WHERE id=?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public ModelConfig findActive(String type) {
        List<ModelConfig> list = jdbc.query("SELECT * FROM model_config WHERE type=? AND is_active=TRUE AND enabled=TRUE", MAPPER, type);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 激活指定配置：先把同类型全部取消激活，再激活目标（事务由调用方保证或单条更新即可） */
    public int activate(long id) {
        ModelConfig c = findById(id);
        if (c == null) return 0;
        jdbc.update("UPDATE model_config SET is_active=FALSE, updated_at=CURRENT_TIMESTAMP WHERE type=?", c.getType());
        return jdbc.update("UPDATE model_config SET is_active=TRUE, updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    public int insert(String name, String type, String baseUrl, String apiKey, String model, Integer dimensions, boolean active) {
        return jdbc.update("INSERT INTO model_config(name,type,base_url,api_key,model_name,dimensions,is_active) " +
                        "VALUES(?,?,?,?,?,?,?)", name, type, baseUrl, apiKey == null ? "" : apiKey, model, dimensions, active);
    }

    /** 更新（API Key 传 null 表示保持原值，便于脱敏回显后编辑不改 Key） */
    public int update(long id, String name, String type, String baseUrl, String apiKey, String model, Integer dimensions, boolean enabled) {
        ModelConfig old = findById(id);
        if (old == null) return 0;
        // 空值或脱敏串（含 ****）都表示保持原 Key，避免把掩码写回数据库
        String key = (apiKey == null || apiKey.isEmpty() || apiKey.contains("****")) ? old.getApiKey() : apiKey;
        return jdbc.update("UPDATE model_config SET name=?,type=?,base_url=?,api_key=?,model_name=?,dimensions=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                name, type, baseUrl, key, model, dimensions, enabled, id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM model_config WHERE id=?", id);
    }

    /** 统计切片表向量维度，用于校验 embedding 切换的兼容性（只读查询） */
    public Integer vectorDimension() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT vector_dims(embedding) AS dim FROM vector_store LIMIT 1");
            return rows.isEmpty() ? null : ((Number) rows.get(0).get("dim")).intValue();
        } catch (Exception e) {
            return null; // 表不存在或空表：放行，由配置维度为准
        }
    }
}
