package com.joylin.waterkb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.config.EsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.joylin.waterkb.config.RagProperties;

/**
 * ES 向量存储适配器：实现 Spring AI 的 VectorStore 接口，底层完全走 ES。
 *
 * <p>为什么做适配器而不是改所有调用方：DocumentIngestService / RagChatService
 * 已经稳定跑通且单测完备，用 VectorStore 接口抽象它们与底层存储的解耦。
 * 这里实现同一接口、内部委托 ES，就完成了"完全替换为 ES"——
 * 上层代码零改动，单测零改动，切换成本只在这一层。</p>
 *
 * <p>入库时把 Document 转成 ES source（含向量 + 权限字段），
 * 检索时委托 EsHybridSearchService 做 BM25+kNN+RRF（权限过滤在那一层强制注入）。</p>
 */
@Component
public class EsVectorStoreAdapter implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(EsVectorStoreAdapter.class);

    private final EsIndexService es;
    private final EsProperties props;
    private final EmbeddingModel embeddingModel;
    private final EsHybridSearchService hybridSearch;

    public EsVectorStoreAdapter(EsIndexService es, EsProperties props,
                                EmbeddingModel embeddingModel, EsHybridSearchService hybridSearch) {
        this.es = es;
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.hybridSearch = hybridSearch;
    }

    @Override
    public void add(List<Document> documents) {
        // 批量向量化（沿用 RagProperties 的 batch size 由调用方控制，这里处理传入的一批）
        List<float[]> vectors = embeddingModel.embed(documents.stream().map(Document::getText).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("id", d.getId() != null ? d.getId() : UUID.randomUUID().toString());
            src.put("content", d.getText());
            float[] vec = i < vectors.size() ? vectors.get(i) : new float[props.getDimensions()];
            src.put("embedding", toList(vec));
            Map<String, Object> meta = d.getMetadata();
            copyIfPresent(src, meta, "fileName");
            copyIfPresent(src, meta, "tenantId");
            copyIfPresent(src, meta, "visibleSubtree");
            if (meta.get("chunkIndex") != null) src.put("chunkIndex", meta.get("chunkIndex"));
            if (meta.get("uploadedAt") != null) src.put("uploadedAt", meta.get("uploadedAt"));
            // 权限字段：上传时由 DocumentIngestService 注入，缺省按公开+调用者
            copyIfPresent(src, meta, "ownerUser");
            if (meta.get("allowedGroups") instanceof List<?> groups) {
                src.put("allowedGroups", groups.stream().map(String::valueOf).toList());
            } else {
                src.put("allowedGroups", List.of("public"));
            }
            rows.add(src);
        }
        es.bulkIndex(rows);
    }

    @Override
    public void delete(List<String> idList) {
        int n = es.deleteByIds(idList);
        log.info("按 id 删除 {} 条切片", n);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        log.warn("Filter 表达式删除未实现（作品集用按文件名删除即可）");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // 委托混合检索：BM25+kNN+RRF，权限过滤在服务内部强制注入
        return hybridSearch.search(request.getQuery());
    }

    @Override
    public Optional<Object> getNativeClient() {
        return Optional.of(es);
    }

    @Override
    public String getName() {
        return "elasticsearch-hybrid";
    }

    private static void copyIfPresent(Map<String, Object> dst, Map<String, Object> src, String key) {
        if (src != null && src.get(key) != null) dst.put(key, src.get(key));
    }

    private static List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
