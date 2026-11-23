package com.joylin.waterkb.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 模型注册表：按数据库中的激活配置动态构造 ChatModel / EmbeddingModel，带缓存。
 *
 * <p>核心设计（作品集亮点）：
 * <ol>
 *   <li>公有云与内网私有模型统一走 OpenAI 兼容协议，换 base-url + model 即可切换；</li>
 *   <li>缓存 key = 配置全字段指纹，改配置自动失效重建，不需要重启容器；</li>
 *   <li>Embedding 切换前校验维度必须等于向量库现有维度，避免换模型后入库直接失败；</li>
 *   <li>连通性测试真实调用模型端点并计时，便于上线前验证内网可达性。</li>
 * </ol></p>
 */
@Service
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ModelConfigRepository repository;
    /** 代理 EmbeddingModel：VectorStore 启动时绑定它，切换配置只换内部 delegate */
    private final DelegatingEmbeddingModel delegatingEmbeddingModel;
    /** 缓存：fingerprint → 模型实例 */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

    public ModelRegistry(ModelConfigRepository repository, DelegatingEmbeddingModel delegatingEmbeddingModel) {
        this.repository = repository;
        this.delegatingEmbeddingModel = delegatingEmbeddingModel;
        // 启动时把当前激活的 embedding 同步给代理（VectorStore 构造时会调用 dimensions()）
        refreshDelegates();
    }

    /** 配置变更后刷新：重建缓存 + 切换代理 delegate */
    public synchronized void refreshDelegates() {
        ModelConfig emb = repository.findActive("EMBEDDING");
        if (emb != null) {
            delegatingEmbeddingModel.setDelegate(embeddingModel(emb));
            log.info("Embedding 代理已切换至 {}（{}，维度 {}）", emb.getName(), emb.getModel(), emb.getDimensions());
        } else {
            log.warn("未找到激活的 EMBEDDING 配置，向量化将不可用");
        }
    }

    /** 当前激活的 ChatModel（不存在时抛异常，错误由全局处理器转成可读信息） */
    public ChatModel currentChatModel() {
        ModelConfig c = repository.findActive("CHAT");
        if (c == null) throw new IllegalStateException("未配置激活的 CHAT 模型，请到「模型配置」页激活一个");
        return chatModel(c);
    }

    /** 当前激活的 ChatClient（每次调用构建，工具由调用方注册） */
    public ChatClient currentChatClient(Object... tools) {
        ChatClient.Builder builder = ChatClient.builder(currentChatModel());
        if (tools.length > 0) builder.defaultTools(tools);
        return builder.build();
    }

    /** 按配置构造并缓存 ChatModel */
    public ChatModel chatModel(ModelConfig c) {
        String fp = fingerprint(c);
        return chatCache.computeIfAbsent(fp, k -> {
            OpenAiApi api = buildApi(c);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(c.getModel())
                    .temperature(0.3)
                    // 流式请求显式要求返回 usage（stream_options.include_usage）：
                    // 默认不返回，导致用量统计的输入/输出 Token 全是 0（2026-11-23 实测）
                    .streamUsage(true)
                    .build();
            log.info("构建 ChatModel：{} @ {}（缓存 miss）", c.getModel(), c.getBaseUrl());
            return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();
        });
    }

    /** 按配置构造并缓存 EmbeddingModel */
    public EmbeddingModel embeddingModel(ModelConfig c) {
        String fp = fingerprint(c);
        return embeddingCache.computeIfAbsent(fp, k -> {
            OpenAiApi api = buildApi(c);
            log.info("构建 EmbeddingModel：{} @ {}（缓存 miss）", c.getModel(), c.getBaseUrl());
            // 必须显式指定 model：否则 Spring AI 默认用 text-embedding-ada-002，
            // 公有云/内网模型一律报 model_not_found（实测踩坑）
            var options = org.springframework.ai.openai.OpenAiEmbeddingOptions.builder()
                    .model(c.getModel())
                    .build();
            return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options);
        });
    }

    /**
     * 激活前校验：EMBEDDING 配置的维度必须与向量库一致。
     * 切到不同维度的模型会让存量向量失效、新数据入库报维度错误，必须提前拦住。
     */
    public void validateBeforeActivate(ModelConfig c) {
        if (!"EMBEDDING".equalsIgnoreCase(c.getType())) return;
        Integer actual = repository.vectorDimension();
        if (actual == null) return; // 空表：以配置维度为准
        int wanted = c.getDimensions() == null ? -1 : c.getDimensions();
        if (wanted != actual) {
            throw new IllegalArgumentException("维度不兼容：向量库当前为 " + actual + " 维，该模型为 " + wanted +
                    " 维。切换需先清空并重建向量库（会丢失全部已入库文档），请在管理页确认后再操作");
        }
    }

    /**
     * 连通性测试：真实调用一次模型端点并计时。
     * CHAT 用极短 prompt，EMBEDDING 用单条文本（绕开 DashScope 的 batch≤10 限制）。
     */
    public String test(ModelConfig c) {
        long start = System.currentTimeMillis();
        try {
            if ("CHAT".equalsIgnoreCase(c.getType())) {
                ChatModel model = chatModel(c);
                var response = model.call(new org.springframework.ai.chat.prompt.Prompt(
                        new org.springframework.ai.chat.messages.UserMessage("请只回复 OK 两个字")));
                String text = response == null || response.getResult() == null ? "" :
                        String.valueOf(response.getResult().getOutput().getText());
                return "OK · 耗时 " + (System.currentTimeMillis() - start) + "ms · 回复：" +
                        (text.length() > 50 ? text.substring(0, 50) + "…" : text);
            }
            EmbeddingModel model = embeddingModel(c);
            float[] vec = model.embed("连通性测试");
            return "OK · 耗时 " + (System.currentTimeMillis() - start) + "ms · 向量维度 " + (vec == null ? 0 : vec.length);
        } catch (Exception e) {
            return "FAIL · 耗时 " + (System.currentTimeMillis() - start) + "ms · " +
                    (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 配置变更（新增/编辑/删除/激活）后调用：清缓存并刷新代理 */
    public synchronized void invalidate() {
        chatCache.clear();
        embeddingCache.clear();
        refreshDelegates();
    }

    private OpenAiApi buildApi(ModelConfig c) {
        // 内网模型常不校验 Key，空 Key 用占位符避免 Spring AI 启动校验失败
        String key = c.getApiKey() == null || c.getApiKey().isBlank() ? "no-key-required" : c.getApiKey();
        return OpenAiApi.builder()
                .baseUrl(c.getBaseUrl())
                .apiKey(key)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                            setConnectTimeout(Duration.ofSeconds(10));
                            setReadTimeout(Duration.ofSeconds(60));
                        }}))
                .build();
    }

    /** 配置指纹：任一影响调用的字段变化都会生成新实例 */
    private String fingerprint(ModelConfig c) {
        return c.getType() + "|" + c.getBaseUrl() + "|" + c.getApiKey() + "|" + c.getModel() + "|" + c.getDimensions();
    }
}
