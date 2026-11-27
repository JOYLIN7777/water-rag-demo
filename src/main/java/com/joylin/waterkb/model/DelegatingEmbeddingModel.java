package com.joylin.waterkb.model;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Objects;
import com.joylin.waterkb.service.ModelRegistry;

/**
 * 可切换的向量模型代理。
 */
@Primary
@Component
public class DelegatingEmbeddingModel implements EmbeddingModel {

    private volatile EmbeddingModel delegate;

    public DelegatingEmbeddingModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String baseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}") String model) {
        String key = apiKey == null || apiKey.isBlank() ? "no-key-required" : apiKey;
        // 必须显式指定 model，否则默认 text-embedding-ada-002 在 DashScope 上不存在
        this.delegate = new OpenAiEmbeddingModel(
                OpenAiApi.builder().baseUrl(baseUrl).apiKey(key).build(),
                MetadataMode.NONE,
                org.springframework.ai.openai.OpenAiEmbeddingOptions.builder().model(model).build());
    }

    public void setDelegate(EmbeddingModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为 null");
    }

    public EmbeddingModel currentDelegate() {
        return delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
