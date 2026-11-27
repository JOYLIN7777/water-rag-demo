package com.joylin.waterkb.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.joylin.waterkb.service.EsIndexService;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 向量库管理操作（文档清单 / 按文件名删除）——底层已从 pgvector 切到 ES。
 *
 */
@Repository
public class VectorStoreAdminRepository {

    private final EsIndexService es;

    public VectorStoreAdminRepository(EsIndexService es) {
        this.es = es;
    }

    /**
     * 文档清单：解析 ES composite（部门,文件名）聚合桶，返回列表行结构。
     * 同名文档在不同部门是独立条目（fileName 相同但 tenantId 不同），
     * 前端据此显示部门徽标、删除时后端也按部门范围拦截。
     */
    public List<Map<String, Object>> listDocuments() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode buckets = es.listDocuments();
            Iterator<JsonNode> it = buckets.elements();
            while (it.hasNext()) {
                JsonNode b = it.next();
                JsonNode owner = b.path("owner").path("buckets");
                // composite key：t=部门键（missing_bucket 时缺该字段 → 视为 default 历史）
                JsonNode key = b.path("key");
                String tenantId = key.has("t") ? key.path("t").asText("") : "default";
                boolean subtree = false;
                for (JsonNode s : b.path("sub").path("buckets")) {
                    if (s.path("key").asBoolean()) subtree = true;
                }
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                String fullName = key.path("f").asText();
                row.put("fileName", fullName);
                row.put("displayName", fullName.substring(fullName.lastIndexOf('/') + 1));
                row.put("chunks", b.path("doc_count").asInt());
                row.put("uploadedAt", b.path("latest").path("value_as_string").asText(""));
                row.put("ownerUser", owner.isEmpty() ? "" : owner.get(0).path("key").asText());
                row.put("tenantId", tenantId);
                row.put("visibleSubtree", subtree);
                out.add(row);
            }
        } catch (Exception e) {
            return List.of(); // ES 未就绪时返回空清单，不让接口 500
        }
        return out;
    }

    public int deleteByFileName(String fileName, String tenantId) {
        return es.deleteByFileName(fileName, tenantId);
    }

    /**

     * 可见范围内查文件归属部门（删除失败时区分"不存在"与"归属别部门"）

     */
    public String findDocTenant(String fileName) {
        return es.findDocTenant(fileName);
    }

    /**

     * 单份文档的全部切片（供分片树状预览）

     */
    public Map<String, Object> listChunks(String fileName) {
        try {
            return es.listChunks(fileName);
        } catch (Exception e) {
            // ES 未就绪时返回空结果，不让接口 500
            return Map.of("total", 0, "truncated", false, "chunks", List.of());
        }
    }
}
