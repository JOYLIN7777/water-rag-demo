package com.joylin.waterkb.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.memory.RedisConversationStore;
import com.joylin.waterkb.model.ModelRegistry;
import com.joylin.waterkb.resilience.LlmCircuitBreaker;
import com.joylin.waterkb.tool.WaterInfoTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答核心服务：检索 → 拼装 Prompt → 流式生成（含熔断降级）。
 *
 * <p>设计决策（为什么手写 RAG 链路而不用 Spring AI 的 QuestionAnswerAdvisor）：
 * Advisor 一步到位但把"向量化、检索、拼装"都封装进黑盒，检索结果拿不到、
 * 来源没法溯源、降级做不了。手写链路每一步都可观测、可干预，工程化能力
 * （引用溯源、熔断降级）正是在这些缝隙里长出来的。</p>
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final VectorStore vectorStore;
    private final ModelRegistry modelRegistry;
    private final RedisConversationStore historyStore;
    private final PromptTemplateLoader promptLoader;
    private final LlmCircuitBreaker breaker;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    private final WaterInfoTool waterInfoTool;

    public RagChatService(VectorStore vectorStore,
                          ModelRegistry modelRegistry,
                          WaterInfoTool waterInfoTool,
                          RedisConversationStore historyStore,
                          PromptTemplateLoader promptLoader,
                          LlmCircuitBreaker breaker,
                          RagProperties props,
                          ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.modelRegistry = modelRegistry;
        this.waterInfoTool = waterInfoTool;
        this.historyStore = historyStore;
        this.promptLoader = promptLoader;
        this.breaker = breaker;
        this.props = props;
        this.objectMapper = objectMapper;
        // 不在构造时固定 ChatClient：每次问答从 ModelRegistry 取当前激活模型，
        // 这样管理页切换模型后无需重启容器即可生效。
    }

    /**
     * RAG 问答主链路，返回 SSE 事件流。
     *
     * <p>事件类型约定：
     * sources —— 本次回答引用的知识库片段（可溯源）；
     * message —— 回答文本增量（逐 token 推送）；
     * done —— 结束标记。</p>
     */
    public Flux<ServerSentEvent<String>> chatStream(String sessionId, String question) {
        // ===== 阶段 1：相似度检索 =====
        // pgvector 内部会先把问题交给 EmbeddingModel 向量化，再用 HNSW 索引做余弦相似度检索
        // 检索失败不阻塞问答：降级为无上下文模式（模型只靠自身知识 + 工具）
        final List<Document> hits = retrieve(question);

        // ===== 阶段 2：拼装知识库上下文 =====
        String context = hits.isEmpty() ? "（知识库中未检索到相关内容）" : formatContext(hits);

        // ===== 阶段 3：取带会话 ID 的历史对话（Redis） =====
        String history = historyStore.recentAsText(sessionId, props.getHistoryMaxTurns());

        // ===== 阶段 4：渲染外置 Prompt 模板 =====
        String systemPrompt = promptLoader.render(props.getRagSystemPrompt(),
                Map.of("context", context, "history", history));

        // 前置 sources 事件：前端可展示"本次回答引用了哪些片段"
        String sourcesJson = toSourcesJson(hits);

        // ===== 阶段 5：流式生成（超时 / 异常 → 熔断计数 + 降级） =====
        StringBuilder answerBuf = new StringBuilder();
        boolean[] degraded = {false};
        Flux<String> tokens;

        if (!breaker.allowCall()) {
            // 熔断打开期：不再请求大模型，直接降级返回检索原文，快速失败
            degraded[0] = true;
            String fallback = degradeText("AI 服务处于熔断保护中", hits);
            answerBuf.append(fallback);
            tokens = Flux.just(fallback);
        } else {
            // 每次调用动态取当前激活的 Chat 模型（含 Tool Calling 工具注册）
            ChatClient chatClient;
            try {
                chatClient = modelRegistry.currentChatClient(waterInfoTool);
            } catch (Exception ex) {
                // 配置缺失/构建失败也走同一条降级路径，不让整个问答接口 500
                breaker.recordFailure();
                degraded[0] = true;
                log.error("Chat 模型构建失败，触发降级：{}", ex.getMessage());
                String fallback = degradeText("模型配置异常，已自动降级", hits);
                answerBuf.append(fallback);
                tokens = Flux.just(fallback);
                return Flux.just(sse("sources", sourcesJson))
                        .concatWith(tokens.map(t -> sse("message", t)))
                        .concatWith(Mono.just(sse("done", "[DONE]")));
            }
            tokens = chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .stream()
                    .content()   // 只要回答文本的 Flux（工具调用过程由框架内部消化）
                    .timeout(Duration.ofSeconds(props.getStreamTimeoutSeconds()))
                    .doOnNext(answerBuf::append)
                    .onErrorResume(ex -> {
                        // 超时 / API 异常：熔断计数 +1，并降级输出检索原文
                        breaker.recordFailure();
                        degraded[0] = true;
                        log.error("模型调用失败，触发降级：{}", ex.getMessage());
                        String fallback = degradeText("AI 服务出现异常，已自动降级", hits);
                        answerBuf.setLength(0);
                        answerBuf.append(fallback);
                        return Flux.just(fallback);
                    })
                    .doFinally(sig -> {
                        // 正常完成 → 熔断计数清零；两条路径都落对话历史
                        if (sig == SignalType.ON_COMPLETE && !degraded[0]) {
                            breaker.recordSuccess();
                        }
                        historyStore.append(sessionId, "user", question);
                        historyStore.append(sessionId, "assistant", answerBuf.toString());
                    });
        }

        // ===== 阶段 6：包装成 SSE 事件流 =====
        return Flux.just(sse("sources", sourcesJson))
                .concatWith(tokens.map(t -> sse("message", t)))
                .concatWith(Mono.just(sse("done", "[DONE]")));
    }

    /**
     * 执行向量检索，异常时返回空列表（降级为无上下文模式）。
     * 单独抽方法保证返回值单次赋值，可在后续 lambda 中安全引用。
     */
    private List<Document> retrieve(String question) {
        try {
            List<Document> result = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(props.getTopK())
                    .similarityThreshold(props.getSimilarityThreshold())
                    .build());
            return result == null ? List.of() : result;
        } catch (Exception e) {
            log.error("向量检索失败，本次回答将不带知识库上下文", e);
            return List.of();
        }
    }

    /**
     * 把检索片段拼成 Prompt 上下文，每段带来源文件名（提示模型可溯源）。
     */
    private String formatContext(List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : hits) {
            Map<String, Object> meta = d.getMetadata();
            sb.append("【片段").append(i++).append("｜来源：")
                    .append(meta.getOrDefault("fileName", "未知文件")).append("】\n")
                    .append(d.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 降级文案：大模型不可用时，把检索到的原文直接给用户兜底。
     * 这是 RAG 特有的降级红利——即使没有生成环节，检索结果本身仍有参考价值。
     */
    private String degradeText(String reason, List<Document> hits) {
        StringBuilder sb = new StringBuilder("【服务降级】").append(reason)
                .append("，以下为知识库检索到的原文片段，供直接参考：\n");
        if (hits.isEmpty()) {
            return sb.append("\n（知识库中暂无相关内容，请稍后重试）").toString();
        }
        for (Document d : hits) {
            Map<String, Object> meta = d.getMetadata();
            sb.append("\n【来源：").append(meta.getOrDefault("fileName", "未知文件"))
                    .append(" · 第").append(meta.getOrDefault("chunkIndex", "?")).append("段】\n")
                    .append(preview(d.getText(), 200)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 引用片段序列化为 JSON（sources 事件载荷）。
     */
    private String toSourcesJson(List<Document> hits) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Document d : hits) {
                list.add(Map.of(
                        "fileName", String.valueOf(d.getMetadata().getOrDefault("fileName", "")),
                        "chunkIndex", String.valueOf(d.getMetadata().getOrDefault("chunkIndex", "")),
                        "preview", preview(d.getText(), 80)));
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String preview(String text, int max) {
        String t = text.replace("\n", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.builder(data).event(event).build();
    }
}
