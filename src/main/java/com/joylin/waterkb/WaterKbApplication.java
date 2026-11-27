package com.joylin.waterkb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;

/**
 * 水利知识库智能问答系统（RAG）启动类。
 *
 *
 */
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WaterKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaterKbApplication.class, args);
    }
}
