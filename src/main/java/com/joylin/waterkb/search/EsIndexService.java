package com.joylin.waterkb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.EsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 基础设施：索引初始化、健康检查、REST 调用封装。
 *
 * <p>索引设计（企业级混合检索的地基）：
 * <ul>
 *   <li>content：text 类型，standard analyzer（中文按字符切分，BM25 召回宽、精度低，
 *       正好由向量检索弥补——这是两路融合的意义）；</li>
 *   <li>embedding：dense_vector 1024 维 + HNSW 索引，余弦相似度；</li>
 *   <li>ownerUser / allowedGroups：行级权限字段——文档归谁、哪些组可见；</li>
 *   <li>fileName / chunkIndex：来源溯源，对应回答的引用片段。</li>
 * </ul></p>
 *
 * <p><b>编码坑（实测踩过）</b>：RestClient 的 body(String) 走 StringHttpMessageConverter，
 * 默认按 ISO-8859-1 编码，中文全部变成 '?'，导致写入 ES 的文档和查询 DSL 里的中文词
 * 都失真、BM25 恒返回 0。这里所有请求/响应统一用 byte[] + 显式 UTF-8，
 * 绕开 converter 默认字符集，保证中文语料和中文查询原样到达 ES。</p>
 */
@Service
public class EsIndexService {

    private static final Logger log = LoggerFactory.getLogger(EsIndexService.class);
    private static final MediaType JSON_UTF8 = MediaType.valueOf("application/json;charset=UTF-8");
    private static final MediaType NDJSON_UTF8 = MediaType.valueOf("application/x-ndjson;charset=UTF-8");

    private final EsProperties props;
    private final ObjectMapper mapper;
    private final RestClient client;

    public EsIndexService(EsProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.client = RestClient.builder()
                .baseUrl(props.getUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, JSON_UTF8.toString())
                .build();
    }

    /** 启动时确保索引存在且含 tenantId 字段（幂等，兼容老索引平滑升级） */
    @PostConstruct
    public void init() {
        try {
            if (!exists(props.getIndex())) {
                createIndex(props.getIndex());
                log.info("ES 索引 {} 已创建（{} 维向量 + HNSW + tenantId）", props.getIndex(), props.getDimensions());
            } else {
                log.info("ES 索引 {} 已存在，复用", props.getIndex());
                ensureTenantField();
            }
        } catch (Exception e) {
            // ES 未启动时不要让整个应用起不来——问答仍可用（检索降级为空）
            log.error("ES 索引初始化失败，检索将降级为空：{}", e.getMessage());
        }
    }

    /**
     * 老索引平滑升级：补 tenantId mapping（ES 允许给已有索引加新字段，无需 reindex）。
     * 历史数据没有该字段时按 default 租户处理，由查询侧 terms 兼容逻辑覆盖。
     */
    private void ensureTenantField() {
        try {
            JsonNode mapping = parse(client.get().uri("/" + props.getIndex() + "/_mapping")
                    .retrieve().body(byte[].class));
            boolean has = !mapping.path(props.getIndex()).path("mappings").path("properties")
                    .path("tenantId").isMissingNode();
            if (!has) {
                String patch = "{\"properties\":{\"tenantId\":{\"type\":\"keyword\"}}}";
                client.put().uri("/" + props.getIndex() + "/_mapping")
                        .contentType(JSON_UTF8)
                        .body(patch.getBytes(StandardCharsets.UTF_8))
                        .retrieve().toBodilessEntity();
                log.info("ES 索引 {} 已补 tenantId 字段", props.getIndex());
            }
        } catch (Exception e) {
            log.warn("补 tenantId mapping 失败：{}", e.getMessage());
        }
    }

    public boolean healthy() {
        try {
            JsonNode root = parse(client.get().uri("/").retrieve().body(byte[].class));
            return root.path("version").path("number").asText().startsWith("8");
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists(String index) {
        try {
            client.head().uri("/" + index).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private void createIndex(String index) {
        String mapping = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0
              },
              "mappings": {
                "properties": {
                  "content": {"type": "text"},
                  "embedding": {
                    "type": "dense_vector",
                    "dims": %d,
                    "index": true,
                    "similarity": "cosine",
                    "index_options": {"type": "hnsw", "m": 16, "ef_construction": 100}
                  },
                  "fileName": {"type": "keyword"},
                  "chunkIndex": {"type": "integer"},
                  "uploadedAt": {"type": "date"},
                  "ownerUser": {"type": "keyword"},
                  "allowedGroups": {"type": "keyword"},
                  "tenantId": {"type": "keyword"}
                }
              }
            }
            """.formatted(props.getDimensions());
        client.put().uri("/" + index)
                .contentType(JSON_UTF8)
                .body(mapping.getBytes(StandardCharsets.UTF_8))
                .retrieve().toBodilessEntity();
    }

    /** 通用 POST 查询：返回 ES 原始 JSON，由调用方解析 */
    public JsonNode search(String index, String dsl) {
        return postJson("/" + index + "/_search", dsl);
    }

    /** 统一的 JSON POST：请求和响应都显式 UTF-8 */
    private JsonNode postJson(String uri, String body) {
        byte[] raw = client.post().uri(uri)
                .contentType(JSON_UTF8)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve().body(byte[].class);
        return parse(raw);
    }

    /** bulk 写入：items 是 {indexName, id, sourceMap} 列表 */
    public int bulkIndex(List<Map<String, Object>> docs) {
        if (docs.isEmpty()) return 0;
        StringBuilder ndjson = new StringBuilder();
        try {
            for (Map<String, Object> d : docs) {
                ndjson.append("{\"index\":{\"_index\":\"").append(props.getIndex())
                        .append("\",\"_id\":\"").append(escape(d.get("id").toString())).append("\"}}\n");
                Map<String, Object> source = new java.util.LinkedHashMap<>(d);
                source.remove("id");
                ndjson.append(mapper.writeValueAsString(source)).append("\n");
            }
            byte[] raw = client.post().uri("/_bulk?refresh=wait_for")
                    .contentType(NDJSON_UTF8)
                    .body(ndjson.toString().getBytes(StandardCharsets.UTF_8))
                    .retrieve().body(byte[].class);
            JsonNode root = parse(raw);
            if (root.path("errors").asBoolean()) {
                log.warn("ES bulk 部分失败：{}", root.path("items"));
            }
            return root.path("items").size();
        } catch (Exception e) {
            throw new IllegalStateException("ES bulk 入库失败：" + e.getMessage(), e);
        }
    }

    /** 按文档 id 列表删除（VectorStore 接口要求） */
    public int deleteByIds(List<String> ids) {
        if (ids.isEmpty()) return 0;
        String values = String.join(",", ids.stream().map(EsIndexService::quote).toList());
        String dsl = "{\"query\":{\"ids\":{\"values\":[" + values + "]}}}";
        try {
            return postJson("/" + props.getIndex() + "/_delete_by_query?refresh=true", dsl).path("deleted").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("ES 按 id 删除失败：" + e.getMessage(), e);
        }
    }

    /** 按文件名删除（先 _delete_by_query，再 refresh）——强制带租户与账号可见范围 */
    public int deleteByFileName(String fileName) {
        // fileName 本身就是 keyword 类型，没有 .keyword 子字段
        String dsl = "{\"query\":{\"bool\":{\"must\":["
                + "{\"term\":{\"fileName\":" + quote(fileName) + "}}," + visibilityQuery() + "]}}}";
        try {
            return postJson("/" + props.getIndex() + "/_delete_by_query?refresh=true", dsl).path("deleted").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("ES 删除失败：" + e.getMessage(), e);
        }
    }

    /**
     * 某个组织名下的切片数。
     *
     * <p>删除组织前必须问一句"这里还有没有东西"：只有账号、没有文档的组织可以直接删；
     * 只要有文档，说明删掉就会有数据变成谁都看不见的孤儿。</p>
     */
    public long countByTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) return 0;
        try {
            String dsl = "{\"query\":{\"term\":{\"tenantId\":" + quote(tenant) + "}}}";
            return postJson("/" + props.getIndex() + "/_count", dsl).path("count").asLong(0);
        } catch (Exception e) {
            log.warn("统计租户分片数失败 {}：{}", tenant, e.getMessage());
            return 0;
        }
    }

    /**
     * 把某个组织名下的全部分片改挂到另一个组织（组织合并/删除时用）。
     *
     * <p>为什么必须做这一步：账号的 tenant 迁走了，文档的 tenantId 还留着旧键，
     * 而检索和文档清单都按 tenantId 过滤——旧组织一删，这批文档就变成"系统里有
     * 但谁都看不到、也删不掉"的孤儿数据。实测踩过一次（3724 个分片失联）。</p>
     *
     * @return 实际改挂的切片数
     */
    public long migrateTenant(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) return 0;
        try {
            String dsl = """
                {"query":{"term":{"tenantId":%s}},
                 "script":{"source":"ctx._source.tenantId = params.to","params":{"to":%s}}}
                """.formatted(quote(from), quote(to));
            JsonNode r = postJson("/" + props.getIndex() + "/_update_by_query?refresh=true&conflicts=proceed", dsl);
            long n = r.path("updated").asLong(0);
            log.info("ES 租户迁移 {} → {}：{} 个分片", from, to, n);
            return n;
        } catch (Exception e) {
            log.warn("ES 租户迁移失败 {} → {}：{}", from, to, e.getMessage());
            throw new IllegalStateException("文档迁移失败：" + e.getMessage(), e);
        }
    }

    /** 文档清单：按 fileName 聚合切片数与权限字段，只看当前账号可见的文档 */
    public JsonNode listDocuments() {
        String dsl = """
            {"size":0,"query":%s,"aggs":{"files":{"terms":{"field":"fileName","size":1000},
              "aggs":{"latest":{"max":{"field":"uploadedAt"}},
                      "owner":{"terms":{"field":"ownerUser","size":1}},
                      "groups":{"terms":{"field":"allowedGroups","size":10}}}}}}
            """.formatted(visibilityQuery());
        return search(props.getIndex(), dsl).path("aggregations").path("files").path("buckets");
    }

    /**
     * 某份文档的全部切片（按 chunkIndex 升序），供后台「分片预览」树状展开。
     *
     * <p>一次取回而不是分页：单份文档切片数在几十到几百量级（按 300~500 字一片），
     * 展开时才不用二次请求。上限 1000 片并回传真实总数，超大文档如实告诉调用方
     * "共 N 片、只返回了 M 片"，不做伪分页。</p>
     *
     * <p>权限：必须复用检索同一份 {@link #visibilityQuery()}，否则会出现
     * "能通过后台看到别人文档的全文切片"的越权读——这比检索越权更直接。</p>
     */
    public java.util.Map<String, Object> listChunks(String fileName) {
        int limit = 1000;
        String dsl = """
            {"size":%d,"_source":["chunkIndex","content","uploadedAt"],
             "query":{"bool":{"must":[{"term":{"fileName":%s}},%s]}},
             "sort":[{"chunkIndex":{"order":"asc"}}]}
            """.formatted(limit, quote(fileName), visibilityQuery());
        JsonNode root = search(props.getIndex(), dsl);
        JsonNode hits = root.path("hits");
        int total = hits.path("total").path("value").asInt(0);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (JsonNode h : hits.path("hits")) {
            JsonNode src = h.path("_source");
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("chunkIndex", src.path("chunkIndex").asInt());
            m.put("content", src.path("content").asText(""));
            m.put("uploadedAt", src.path("uploadedAt").asText(""));
            out.add(m);
        }
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("total", total);
        res.put("truncated", total > out.size());
        res.put("chunks", out);
        return res;
    }

    /**
     * 账号可见范围（与检索 EsHybridSearchService 同一规则）：
     * 租户隔离 AND（自己上传 OR 组可见 OR public）。管理员看全租户，便于后台维护。
     */
    private String visibilityQuery() {
        com.joylin.waterkb.security.UserContext.CurrentUser user =
                com.joylin.waterkb.security.UserContext.get();
        if (user.hasGroup("admin")) return tenantQuery(user.tenantId());
        StringBuilder sb = new StringBuilder("{\"bool\":{\"filter\":[")
                .append(tenantClause(user.tenantId()))
                .append(",{\"bool\":{\"should\":[")
                .append("{\"term\":{\"ownerUser\":").append(quote(user.userId())).append("}}")
                .append(",{\"term\":{\"allowedGroups\":\"public\"}}");
        for (String group : user.groups()) {
            sb.append(",{\"term\":{\"allowedGroups\":").append(quote(group)).append("}}");
        }
        return sb.append("],\"minimum_should_match\":1}}]}}").toString();
    }

    /** 租户过滤子句：tenantId=当前租户；历史无该字段的数据仅在 default 租户下可见 */
    private String tenantClause(String tenant) {
        if ("default".equals(tenant)) {
            return "{\"bool\":{\"should\":["
                    + "{\"term\":{\"tenantId\":\"default\"}},"
                    + "{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"tenantId\"}}}}"
                    + "],\"minimum_should_match\":1}}";
        }
        return "{\"term\":{\"tenantId\":" + quote(tenant) + "}}";
    }

    /** 供查询 DSL 的顶层 query 使用（返回完整 query 对象） */
    private String tenantQuery(String tenant) {
        return "{\"bool\":{\"filter\":[" + tenantClause(tenant) + "]}}";
    }

    private JsonNode parse(byte[] raw) {
        try {
            return mapper.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("ES 响应解析失败：" + e.getMessage(), e);
        }
    }

    private static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
