package com.joylin.waterkb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;

/**
 * 水利知识库智能问答系统（RAG）启动类。
 *
 * <p>整体链路：文档上传解析 → 段落重叠切片 → Embedding 入 Elasticsearch →
 * 用户提问混合检索（BM25+kNN+RRF，强制行级权限过滤）→ Prompt 拼装 →
 * 大模型流式生成（SSE），全程支持 Tool Calling、Redis 会话历史、熔断降级。</p>
 *
 * <p>排除 pgvector 自动配置：检索底座完全切换到 ES，由 EsVectorStoreAdapter
 * 实现同一 VectorStore 接口，上层 RAG 代码零改动。</p>
 */
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WaterKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaterKbApplication.class, args);
    }
}
