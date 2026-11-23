package com.joylin.waterkb.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.search.EsIndexService;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 向量库管理操作（文档清单 / 按文件名删除）——底层已从 pgvector 切到 ES。
 *
 * <p>保留原类名是为了让 Controller 与前端零改动，内部全部委托 EsIndexService。
 * 这是"完全替换存储底座但上层 contracts 不变"的落地样例。</p>
 */
@Repository
public class VectorStoreAdminRepository {

    private final EsIndexService es;

    public VectorStoreAdminRepository(EsIndexService es) {
        this.es = es;
    }

    /** 文档清单：从 ES 聚合按文件名分组，返回与旧版一致的结构 */
    public List<Map<String, Object>> listDocuments() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode buckets = es.listDocuments();
            Iterator<JsonNode> it = buckets.elements();
            while (it.hasNext()) {
                JsonNode b = it.next();
                JsonNode owner = b.path("owner").path("buckets");
                JsonNode groups = b.path("groups").path("buckets");
                List<String> groupNames = new ArrayList<>();
                Iterator<JsonNode> git = groups.elements();
                while (git.hasNext()) groupNames.add(git.next().path("key").asText());
                out.add(Map.of(
                        "fileName", b.path("key").asText(),
                        "chunks", b.path("doc_count").asInt(),
                        "uploadedAt", b.path("latest").path("value_as_string").asText(""),
                        "ownerUser", owner.isEmpty() ? "" : owner.get(0).path("key").asText(),
                        "allowedGroups", groupNames));
            }
        } catch (Exception e) {
            return List.of(); // ES 未就绪时返回空清单，不让接口 500
        }
        return out;
    }

    public int deleteByFileName(String fileName) {
        return es.deleteByFileName(fileName);
    }

    /** 单份文档的全部切片（供分片树状预览） */
    public Map<String, Object> listChunks(String fileName) {
        try {
            return es.listChunks(fileName);
        } catch (Exception e) {
            // ES 未就绪时返回空结果，不让接口 500
            return Map.of("total", 0, "truncated", false, "chunks", List.of());
        }
    }
}
