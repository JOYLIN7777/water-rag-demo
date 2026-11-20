package com.joylin.waterkb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.EsProperties;
import com.joylin.waterkb.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索 DSL、RRF 融合与权限过滤单测（不依赖 ES 实例）。
 *
 * 验证三个最关键的正确性：
 * 1. RRF 按排名融合、跨路去重、Top-K 截断；
 * 2. BM25 / kNN DSL 结构合法且权限 filter 强制注入；
 * 3. 匿名只能看 public，登录用户能看自己的组。
 */
class EsHybridSearchServiceTest {

    private EsHybridSearchService service;
    private EsProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        props = new EsProperties();
        props.setIndex("test_index");
        props.setTopK(5);
        props.setDimensions(4);
        props.setKnnCandidates(50);
        props.setRrfRankConstant(60);
        service = new EsHybridSearchService(null, props, null);
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private Document doc(String id, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("esId", id);
        meta.put("fileName", id + ".md");
        return new Document(text, meta);
    }

    @Test
    void 权限过滤包含用户与组() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("zhangsan", List.of("water-dept", "admin")));
        String filter = service.buildPermissionFilter();
        assertTrue(filter.contains("zhangsan"));
        assertTrue(filter.contains("water-dept"));
        assertTrue(filter.contains("admin"));
        assertTrue(filter.contains("public"));
        assertTrue(filter.contains("minimum_should_match"));
    }

    @Test
    void 权限过滤强制包含租户隔离() {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("u", List.of("g1"), "dept-b", "jwt"));
        String filter = service.buildPermissionFilter();
        assertTrue(filter.contains("tenantId"));
        assertTrue(filter.contains("dept-b"));
        assertFalse(filter.contains("\"tenantId\":\"default\""), "非默认租户不能放宽到 default");

        UserContext.CURRENT_TEST(new UserContext.CurrentUser("u", List.of(), "default", "header"));
        String def = service.buildPermissionFilter();
        assertTrue(def.contains("must_not"), "默认租户需兼容历史无tenantId数据");
    }

    @Test
    void 匿名用户只匹配公开() {
        String filter = service.buildPermissionFilter();
        assertTrue(filter.contains("anonymous"));
        assertTrue(filter.contains("public"));
        assertFalse(filter.contains("admin"));
    }

    @Test
    void RRF跨路去重且双路命中排在前面() {
        List<Document> fused = service.rrfFuse(
                List.of(doc("a", "A"), doc("b", "B")),
                List.of(doc("b", "B"), doc("c", "C")));
        assertEquals(3, fused.size());
        assertEquals("B", fused.get(0).getText(), "两路都命中的文档应排第一");
        assertEquals("A", fused.get(1).getText());
        assertEquals("C", fused.get(2).getText());
    }

    @Test
    void RRF按TopK截断() {
        props.setTopK(2);
        List<Document> fused = service.rrfFuse(
                List.of(doc("a", "A"), doc("b", "B"), doc("c", "C")),
                List.of(doc("d", "D"), doc("e", "E")));
        assertEquals(2, fused.size());
    }

    @Test
    void BM25与KNN模式DSL正确且权限强制注入() throws Exception {
        UserContext.CURRENT_TEST(new UserContext.CurrentUser("u", List.of("g1")));
        float[] vec = {0.1f, 0.2f, 0.3f, 0.4f};

        JsonNode bm25 = mapper.readTree(service.buildDsl("问题", vec, EsHybridSearchService.Mode.BM25));
        assertNotNull(bm25.path("query").path("bool").path("must").get(0).path("match"));
        assertTrue(bm25.path("query").path("bool").path("filter").get(0).toString().contains("ownerUser"));
        assertEquals(20, bm25.path("size").asInt(), "候选窗口应大于 Top-K");

        JsonNode knn = mapper.readTree(service.buildDsl("问题", vec, EsHybridSearchService.Mode.KNN));
        assertEquals("embedding", knn.path("knn").path("field").asText());
        assertEquals(4, knn.path("knn").path("query_vector").size());
        assertTrue(knn.path("knn").path("filter").get(0).toString().contains("g1"));
        assertTrue(knn.path("query").isMissingNode(), "kNN 必须是顶层字段，不能塞进 query");

        // ES 8.13 不支持 retriever API，HYBRID 不能用黑盒 retriever
        JsonNode hybrid = mapper.readTree(service.buildDsl("问题", vec, EsHybridSearchService.Mode.HYBRID));
        assertTrue(hybrid.path("retriever").isMissingNode());
    }
}
