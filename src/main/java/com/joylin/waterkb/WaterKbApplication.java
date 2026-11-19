package com.joylin.waterkb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 水利知识库智能问答系统（RAG）启动类。
 *
 * <p>整体链路：文档上传解析 → 段落重叠切片 → Embedding 入 pgvector →
 * 用户提问向量化检索 Top-K → Prompt 拼装 → 大模型流式生成（SSE），
 * 全程支持 Tool Calling、Redis 会话历史、熔断降级。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaterKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaterKbApplication.class, args);
    }
}
