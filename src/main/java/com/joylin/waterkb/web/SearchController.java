package com.joylin.waterkb.web;

import com.joylin.waterkb.search.EsHybridSearchService;
import com.joylin.waterkb.search.EsIndexService;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.security.UserContext;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检索实验接口（作品集演示用）：同一问题跑三种检索模式对比 + 查看当前权限上下文。
 *
 * <p>面试可现场演示：BM25 命中关键词但语义弱、kNN 语义准但字面弱、
 * RRF 融合两者兼得；切换 X-User / X-Groups 头能看到行级权限生效。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final EsHybridSearchService hybrid;
    private final EsIndexService es;

    public SearchController(EsHybridSearchService hybrid, EsIndexService es) {
        this.hybrid = hybrid;
        this.es = es;
    }

    /** 三种模式对比（带来源片段），一次请求返回三组结果。
     *  权限：search:use——菜单权限页没勾「检索对比」的角色，接口层同样拒绝（前端隐藏入口只是第一道门） */
    @RequirePermission(Permission.SEARCH_USE)
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String q) {
        return Map.of(
                "question", q,
                "identity", Map.of("user", UserContext.get().userId(), "groups", UserContext.get().groups()),
                "bm25", toHits(hybrid.search(q, EsHybridSearchService.Mode.BM25)),
                "knn", toHits(hybrid.search(q, EsHybridSearchService.Mode.KNN)),
                "hybrid", toHits(hybrid.search(q, EsHybridSearchService.Mode.HYBRID)));
    }

    /** ES 健康与索引状态 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("healthy", es.healthy());
    }

    private List<Map<String, Object>> toHits(List<Document> docs) {
        return docs.stream().limit(5).map(d -> Map.<String, Object>of(
                "file", String.valueOf(d.getMetadata().getOrDefault("fileName", "")),
                "chunk", d.getMetadata().getOrDefault("chunkIndex", 0),
                "score", d.getMetadata().getOrDefault("score", 0),
                "text", d.getText().length() > 120 ? d.getText().substring(0, 120) + "…" : d.getText())).toList();
    }
}
