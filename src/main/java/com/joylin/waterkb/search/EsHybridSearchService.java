package com.joylin.waterkb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.config.EsProperties;
import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 混合检索服务（作品集核心）：BM25 全文 + kNN 向量 + RRF 融合 + 行级权限强制过滤。
 *
 * <p>为什么用 RRF 而不是加权求和：BM25 分数与余弦相似度量纲完全不同，
 * 直接加权需要调权重且不稳定；RRF（Reciprocal Rank Fusion）只看排名不看分数，
 * 公式 score(d)=Σ 1/(k+rank_i)，无需调参就能稳定融合两路结果——
 * 这是 ES 官方推荐的 hybrid search 方案。</p>
 *
 * <p>为什么在应用层做 RRF：ES retriever API 从 8.15 才提供，项目镜像是 8.13.4；
 * 且应用层融合能跨版本、可直接看到两路中间结果（检索实验页的 A/B 对比），
 * 比黑盒 retriever 更可控。代价是两次 HTTP 往返，内网 ES 可忽略。</p>
 *
 * <p>权限不可绕过：每次查询都从 UserContext 强制读取当前用户，
 * 构造 filter（ownerUser=自己 OR allowedGroups 命中用户组 OR 公开），
 * 注入到两路检索的 query 中。调用方无法传参覆盖。</p>
 */
@Service
public class EsHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(EsHybridSearchService.class);

    private final EsIndexService es;
    private final EsProperties props;
    private final EmbeddingModel embeddingModel;

    public EsHybridSearchService(EsIndexService es, EsProperties props, EmbeddingModel embeddingModel) {
        this.es = es;
        this.props = props;
        this.embeddingModel = embeddingModel;
    }

    /** 检索模式：用于 A/B 对比演示 */
    public enum Mode { BM25, KNN, HYBRID }

    /** 混合检索（默认 HYBRID）。权限过滤强制注入，调用方无法关闭。 */
    public List<Document> search(String question) {
        return search(question, Mode.HYBRID);
    }

    public List<Document> search(String question, Mode mode) {
        try {
            String filter = buildPermissionFilter();
            // RRF 的候选窗口要比最终 Top-K 大，否则融合后可选范围不足
            int window = Math.max(props.getTopK() * 4, 20);
            switch (mode) {
                case BM25 -> {
                    return parseHits(es.search(props.getIndex(), buildBm25Dsl(question, filter, window)));
                }
                case KNN -> {
                    float[] vector = embeddingModel.embed(question);
                    return parseHits(es.search(props.getIndex(), buildKnnDsl(vector, filter, window)));
                }
                case HYBRID -> {
                    float[] vector = embeddingModel.embed(question);
                    List<Document> bm25 = parseHits(es.search(props.getIndex(), buildBm25Dsl(question, filter, window)));
                    List<Document> knn = parseHits(es.search(props.getIndex(), buildKnnDsl(vector, filter, window)));
                    return rrfFuse(bm25, knn);
                }
                default -> throw new IllegalArgumentException("未知检索模式：" + mode);
            }
        } catch (Exception e) {
            log.error("ES 混合检索失败，降级为空：{}", e.getMessage());
            return List.of();
        }
    }

    /** 兼容旧调用/单测的统一入口：HYBRID 模式返回其中一路 DSL（融合逻辑在 Java 侧） */
    String buildDsl(String question, float[] vector, Mode mode) {
        String filter = buildPermissionFilter();
        int window = Math.max(props.getTopK() * 4, 20);
        if (mode == Mode.KNN) return buildKnnDsl(vector, filter, window);
        return buildBm25Dsl(question, filter, window);
    }

    /** 纯全文 DSL：match + 权限 filter。standard analyzer 对中文按字切分，
     *  单字召回宽但精度低——这正是要用向量检索弥补的地方。 */
    String buildBm25Dsl(String question, String filter, int window) {
        return "{\"size\":" + window + ",\"query\":{\"bool\":{\"must\":[{\"match\":{\"content\":"
                + jsonString(question) + "}}],\"filter\":[" + filter + "]}}}";
    }

    /** 纯向量 DSL：ES 8.13 的 knn 是顶层字段，不能塞进 query。 */
    String buildKnnDsl(float[] vector, String filter, int window) {
        return "{\"size\":" + window + ",\"knn\":{\"field\":\"embedding\",\"query_vector\":["
                + joinVector(vector) + "],\"k\":" + window + ",\"num_candidates\":"
                + props.getKnnCandidates() + ",\"filter\":[" + filter + "]}}";
    }

    /**
     * 应用层 RRF 融合：按 esId 去重，score=Σ1/(k+rank)，rank 从 1 开始，取 Top-K。
     * 与 ES 8.15 retriever 的 rrf 算法一致，但跨版本可用且结果可解释。
     */
    List<Document> rrfFuse(List<Document> bm25, List<Document> knn) {
        int k = props.getRrfRankConstant();
        Map<String, Fused> fused = new LinkedHashMap<>();
        accumulate(fused, bm25, k);
        accumulate(fused, knn, k);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score).reversed())
                .limit(props.getTopK())
                .map(f -> {
                    Document d = f.doc();
                    // 展示给前端的 score 必须是 RRF 融合分，否则实验页会把 BM25 原始分
                    // 误标成 RRF 结果；原始分保留在 rawScore 供排障
                    d.getMetadata().put("rawScore", d.getMetadata().get("score"));
                    d.getMetadata().put("score", f.score());
                    return d;
                })
                .toList();
    }

    private void accumulate(Map<String, Fused> fused, List<Document> docs, int k) {
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String id = String.valueOf(d.getMetadata().getOrDefault("esId", d.getText()));
            double gain = 1.0 / (k + i + 1);
            fused.compute(id, (key, old) -> old == null ? new Fused(d, gain) : old.add(gain));
        }
    }

    private record Fused(Document doc, double score) {
        Fused add(double gain) { return new Fused(doc, score + gain); }
    }

    /**
     * 行级权限过滤条件：owner=自己 OR 用户组命中 allowedGroups OR 公开。
     * 这是整个权限模型的唯一入口，任何检索都必须经过这里。
     */
    String buildPermissionFilter() {
        UserContext.CurrentUser user = UserContext.get();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"bool\":{\"should\":[")
                .append("{\"term\":{\"ownerUser\":").append(jsonString(user.userId())).append("}}")
                .append(",{\"term\":{\"allowedGroups\":\"public\"}}");
        for (String group : user.groups()) {
            sb.append(",{\"term\":{\"allowedGroups\":").append(jsonString(group)).append("}}");
        }
        sb.append("],\"minimum_should_match\":1}}");
        return sb.toString();
    }

    /** 解析 ES hits → Spring AI Document（保留 esId 供 RRF 去重、fileName/chunkIndex 供引用溯源） */
    private List<Document> parseHits(JsonNode root) {
        List<Document> out = new ArrayList<>();
        JsonNode hits = root.path("hits").path("hits");
        Iterator<JsonNode> it = hits.elements();
        while (it.hasNext()) {
            JsonNode hit = it.next();
            JsonNode src = hit.path("_source");
            String text = src.path("content").asText("");
            if (text.isEmpty()) continue;
            Map<String, Object> meta = new HashMap<>();
            meta.put("esId", hit.path("_id").asText(""));
            meta.put("fileName", src.path("fileName").asText("未知文件"));
            meta.put("chunkIndex", src.path("chunkIndex").asInt(0));
            meta.put("score", hit.path("_score").asDouble(0));
            out.add(new Document(text, meta));
        }
        return out;
    }

    private static String joinVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
