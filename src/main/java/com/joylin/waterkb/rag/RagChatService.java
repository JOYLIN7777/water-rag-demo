package com.joylin.waterkb.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.memory.ConversationTreeStore;
import com.joylin.waterkb.memory.TreeNode;
import com.joylin.waterkb.model.ModelConfigRepository;
import com.joylin.waterkb.model.ModelRegistry;
import com.joylin.waterkb.resilience.LlmCircuitBreaker;
import com.joylin.waterkb.security.UserContext;
import com.joylin.waterkb.tool.WaterInfoTool;
import com.joylin.waterkb.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ModelConfigRepository modelConfigRepository;
    private final ConversationTreeStore historyStore;
    private final PromptTemplateLoader promptLoader;
    private final LlmCircuitBreaker breaker;
    private final RagProperties props;
    private final ObjectMapper objectMapper;
    private final UsageRecorder usageRecorder;

    private final WaterInfoTool waterInfoTool;

    public RagChatService(VectorStore vectorStore,
                          ModelRegistry modelRegistry,
                          WaterInfoTool waterInfoTool,
                          ConversationTreeStore historyStore,
                          PromptTemplateLoader promptLoader,
                          LlmCircuitBreaker breaker,
                          RagProperties props,
                          ObjectMapper objectMapper,
                          ModelConfigRepository modelConfigRepository,
                          UsageRecorder usageRecorder) {
        this.vectorStore = vectorStore;
        this.modelRegistry = modelRegistry;
        this.modelConfigRepository = modelConfigRepository;
        this.waterInfoTool = waterInfoTool;
        this.historyStore = historyStore;
        this.promptLoader = promptLoader;
        this.breaker = breaker;
        this.props = props;
        this.objectMapper = objectMapper;
        this.usageRecorder = usageRecorder;
        // 不在构造时固定 ChatClient：每次问答从 ModelRegistry 取当前激活模型，
        // 这样管理页切换模型后无需重启容器即可生效。
    }

    /**
     * RAG 问答主链路，返回 SSE 事件流。
     *
     * <p>事件类型约定：
     * sources —— 本次回答引用的知识库片段（可溯源）；
     * message —— 回答文本增量（逐 token 推送）；
     * done —— 结束标记，载荷为 JSON：{@code {"done":true,"durationMs":1234,
     * "usage":{"promptTokens":1194,"completionTokens":229,"totalTokens":1423,"model":"qwen-plus"}}}，
     * 前端据此展示"本次调用的消耗"；降级/熔断分支带 {@code "degraded":true} 且无 usage。</p>
     */
    public Flux<ServerSentEvent<String>> chatStream(String sessionId, String question, String parentId) {
        // 同步捕获身份快照：SSE 的 doFinally 在 Reactor 异步线程执行，
        // 那时 Filter 已 clear ThreadLocal，直接读会拿到 anonymous/default（多租户串台）。
        final String tenantAtStart = UserContext.get().tenantId();
        final String userAtStart = UserContext.get().userId();
        final long startAt = System.currentTimeMillis();
        final AtomicReference<Usage> usageRef = new AtomicReference<>();

        // ===== 阶段 1：相似度检索 =====
        // ES 混合检索内部会先把问题交给 EmbeddingModel 向量化，再跑 BM25+kNN+RRF
        // 检索失败不阻塞问答：降级为无上下文模式（模型只靠自身知识 + 工具）
        final List<Document> hits = retrieve(question);

        // ===== 阶段 2：拼装知识库上下文 =====
        String context = hits.isEmpty() ? "（知识库中未检索到相关内容）" : formatContext(hits);

        // ===== 阶段 3：取指定分支的祖先链作为上下文 =====
        // parentId=null 走当前 head；传历史节点则从该点分叉，只带它的祖先链，
        // 兄弟分支的消息不会串进 Prompt——这是 fork 的语义基础。
        String history = historyChainText(sessionId, parentId);

        // ===== 阶段 4：渲染外置 Prompt 模板 =====
        String systemPrompt = promptLoader.render(props.getRagSystemPrompt(),
                Map.of("context", context, "history", history));

        // 前置 sources 事件：前端可展示"本次回答引用了哪些片段"
        String sourcesJson = toSourcesJson(hits);

        // ===== 阶段 5：流式生成（超时 / 异常 → 熔断计数 + 降级） =====
        // 关键时序：历史落库必须在 done 事件【之前】完成。
        // 之前放在 doFinally 里，而 Reactor 的 concatWith 会先订阅并发出 done，
        // 再回调 doFinally——前端收到 done 立刻切会话时历史还没写，回来就空了。
        // 现在所有分支统一走下面的 persist Mono，done 在其后发出。
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
            tokens = buildTokens(question, systemPrompt, answerBuf, degraded, hits, usageRef);
        }

        // 统一收尾：先落库（同步、可观测），再发 done。所有分支（正常/熔断/降级）都经过这里。
        // 落库异常必须自己吃掉：SSE 响应已以 text/event-stream 提交，一旦异常外溢到
        // Spring MVC 的 @ExceptionHandler，容器会试图写 JSON 错误体而触发
        // "getOutputStream() has already been called"，最后粗暴关闭连接，浏览器显示 network error。
        // 收尾同时把本次消耗（usage/耗时）序列化进 done 载荷——落库与回显用同一份数据，
        // 保证"页面显示的 token"与"用量统计里的 token"永远对得上。
        Mono<String> persistThenDone = Mono.fromCallable(() -> {
            if (!degraded[0]) breaker.recordSuccess();
            long durationMs = System.currentTimeMillis() - startAt;

            // 先把本轮消耗算出来，再落库。两个用途共用这一份数据：
            // ① model_usage 逐条记录（用量页点总量钻到每一次提问）；
            // ② 写进 assistant 节点（切会话/换浏览器重看历史，"本次 N tokens"才不会消失）。
            // 之前先落库后算用量，节点里就没这个字段，回看历史全靠前端内存，一切会话就没了。
            Map<String, Object> usage = null;
            if (!degraded[0]) {
                Usage u = usageRef.get();
                int promptTokens = u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens();
                int completionTokens = u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
                int totalTokens = u == null || u.getTotalTokens() == null
                        ? promptTokens + completionTokens : u.getTotalTokens();
                String model = activeChatModelName();
                usageRecorder.record(userAtStart, model, promptTokens, completionTokens, durationMs,
                        sessionId, question);
                usage = new LinkedHashMap<>();
                usage.put("promptTokens", promptTokens);
                usage.put("completionTokens", completionTokens);
                usage.put("totalTokens", totalTokens);
                usage.put("model", model);
                usage.put("durationMs", durationMs);
            }

            Map<String, Object> nodes = null;
            try {
                nodes = appendExchange(sessionId, tenantAtStart, userAtStart, parentId, question,
                        answerBuf.toString(), hits, usage);
            } catch (Exception e) {
                log.error("对话历史落库失败（不影响本次回答）", e);
            }

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("done", true);
            done.put("durationMs", durationMs);
            // 回传本轮两个节点的 id 与父节点：前端「从这里再问」要靠它定位父节点，
            // 否则刚问完的那一轮在页面上没有 id，只能等下次刷新会话才拿得到。
            if (nodes != null) done.put("nodes", nodes);
            if (degraded[0]) {
                // 熔断/降级分支没有真实请求模型，不记用量、也不回消耗值
                // （回 0 会让用户误以为这次调用免费，属于数据失真）
                done.put("degraded", true);
            } else if (usage != null) {
                done.put("usage", usage);
            }
            try {
                return objectMapper.writeValueAsString(done);
            } catch (Exception e) {
                return "[DONE]";
            }
        });

        // ===== 阶段 6：包装成 SSE 事件流 =====
        // concatWith 是串行订阅：sources → 所有 message → persist 完成 → done
        // onErrorResume 是最后一道保险：任何残余异常都收敛成 done 事件，
        // 绝不让 onError 传播到 servlet 异步分发，避免连接被异常关闭。
        return Flux.just(sse("sources", sourcesJson))
                .concatWith(tokens.map(t -> sse("message", t)))
                .concatWith(persistThenDone.map(payload -> sse("done", payload)))
                .onErrorResume(ex -> {
                    log.error("SSE 流异常，已安全收尾", ex);
                    return Flux.just(sse("done", "{\"done\":true,\"error\":true}"));
                });
    }

    /** 当前激活的对话模型名（用量记录用）；取不到回落 unknown */
    private String activeChatModelName() {
        try {
            com.joylin.waterkb.model.ModelConfig c = modelConfigRepository.findActive("CHAT");
            return c == null || c.getModel() == null ? "unknown" : c.getModel();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 构建模型输出的 token 流。模型构建失败也降级为检索原文，不让问答接口 500。
     * 独立成方法避免 try/catch 里 chatClient 的编译期确定赋值歧义。
     * 用 chatResponse() 而非 content()：流式响应的 usage 元数据挂在最后一个
     * ChatResponse 上，只有完整对象流才能拿到输入/输出 Token 用于用量统计。
     */
    private Flux<String> buildTokens(String question, String systemPrompt, StringBuilder answerBuf,
                                     boolean[] degraded, List<Document> hits, AtomicReference<Usage> usageRef) {
        ChatClient chatClient;
        try {
            chatClient = modelRegistry.currentChatClient(waterInfoTool);
        } catch (Exception ex) {
            breaker.recordFailure();
            degraded[0] = true;
            log.error("Chat 模型构建失败，触发降级：{}", ex.getMessage());
            String fallback = degradeText("模型配置异常，已自动降级", hits);
            answerBuf.append(fallback);
            return Flux.just(fallback);
        }
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .stream()
                .chatResponse()
                .timeout(Duration.ofSeconds(props.getStreamTimeoutSeconds()))
                .doOnNext(resp -> {
                    if (resp.getResult() != null && resp.getResult().getOutput() != null
                            && resp.getResult().getOutput().getText() != null) {
                        answerBuf.append(resp.getResult().getOutput().getText());
                    }
                    if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                            && resp.getMetadata().getUsage().getTotalTokens() != null
                            && resp.getMetadata().getUsage().getTotalTokens() > 0) {
                        usageRef.set(resp.getMetadata().getUsage());
                    }
                })
                .map(resp -> (resp.getResult() != null && resp.getResult().getOutput() != null
                        && resp.getResult().getOutput().getText() != null)
                        ? resp.getResult().getOutput().getText() : "")
                .onErrorResume(ex -> {
                    breaker.recordFailure();
                    degraded[0] = true;
                    log.error("模型调用失败，触发降级：{}", ex.getMessage());
                    String fallback = degradeText("AI 服务出现异常，已自动降级", hits);
                    answerBuf.setLength(0);
                    answerBuf.append(fallback);
                    return Flux.just(fallback);
                });
    }

    /**
     * 取祖先链文本：parentId 为空走当前 head，否则从指定节点回溯到根。
     * 只保留最近 maxTurns 轮，防止长会话撑爆 token 预算。
     */
    private String historyChainText(String sessionId, String parentId) {
        try {
            List<TreeNode> nodes = parentId == null || parentId.isBlank()
                    ? historyStore.path(sessionId)
                    : historyStore.pathTo(sessionId, parentId);
            int from = Math.max(0, nodes.size() - props.getHistoryMaxTurns() * 2);
            StringBuilder sb = new StringBuilder();
            for (TreeNode n : nodes.subList(from, nodes.size())) {
                sb.append("user".equals(n.role()) ? "用户" : "助手").append("：")
                        .append(n.content()).append('\n');
            }
            return sb.length() > 0 ? sb.toString() : "（无）";
        } catch (Exception e) {
            log.warn("读取对话树上下文失败，按无历史处理", e);
            return "（无）";
        }
    }

    /**
     * 落树：user 挂到 parentId（或当前 head），assistant 再挂到 user 下。
     * 同一次问答的两条消息用父子关系绑定，rewind 到 user 即丢弃这次回答，
     * rewind 到 assistant 则完整保留——可精确控制回滚粒度。
     */
    /**
     * 落库一轮问答（问 + 答两个节点），并把节点 id 返回给调用方。
     *
     * @return {@code questionId/answerId/parentId}；调用方据此在 done 事件里回传，前端才知道刚问的这轮在哪
     */
    private Map<String, Object> appendExchange(String sessionId, String tenant, String user, String parentId, String question,
                                String answer, List<Document> hits, Map<String, Object> usage) {
        // 全程用显式 tenant+user：异步回调线程没有 ThreadLocal 身份，不能读 UserContext
        String parent = parentId == null || parentId.isBlank()
                ? historyStore.head(sessionId, tenant, user) : parentId;
        // 会话第一问时 head 还不存在：显式落成 root，别把空串回给前端——
        // 前端要拿它判断「这一轮是不是第一问」，空串会被当成"没有定位信息"。
        if (parent == null || parent.isBlank()) parent = ConversationTreeStore.ROOT;
        String userNode = historyStore.append(sessionId, tenant, user, parent, "user", question, null, null);
        String answerNode = historyStore.append(sessionId, tenant, user, userNode, "assistant", answer,
                toSourceMaps(hits), null, usage);
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("questionId", userNode);
        ids.put("answerId", answerNode);
        ids.put("parentId", parent == null ? "" : parent);
        return ids;
    }

    /** 把检索命中转成可存入节点 JSON 的来源片段（回显引用卡片用） */
    private List<Map<String, Object>> toSourceMaps(List<Document> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : hits) {
            out.add(Map.of(
                    "fileName", String.valueOf(d.getMetadata().getOrDefault("fileName", "")),
                    "chunkIndex", String.valueOf(d.getMetadata().getOrDefault("chunkIndex", "")),
                    "preview", preview(d.getText(), 80)));
        }
        return out;
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
