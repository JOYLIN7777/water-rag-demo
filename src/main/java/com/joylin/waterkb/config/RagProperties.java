package com.joylin.waterkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 相关可调参数，全部外置到 application.yml 的 rag 前缀下。
 *
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 检索返回的相似片段数 Top-K
     */
    private int topK = 5;

    /**
     * 相似度阈值：低于该值的片段直接丢弃，防止低相关内容诱导模型幻觉
     */
    private double similarityThreshold = 0.4;

    /**
     * 单个切片最大字符数
     */
    private int chunkMaxChars = 600;

    /**
     * 相邻切片重叠字符数
     */
    private int chunkOverlap = 100;

    /**
     * 拼入 Prompt 的历史对话轮数（一轮 = 用户一问 + 助手一答）
     */
    private int historyMaxTurns = 6;

    /**
     * Redis 历史记录最多保留条数（超出后从头部裁剪）
     */
    private int historyMaxEntries = 40;

    /**
     * Redis 历史过期时间（小时）
     */
    private int historyTtlHours = 24;

    /**
     * 流式回答整体超时（秒）
     */
    private int streamTimeoutSeconds = 60;

    /**
     * 熔断阈值：连续失败多少次后打开熔断
     */
    private int breakerFailureThreshold = 3;

    /**
     * 熔断打开后多少秒内直接降级（半开重试等待期）
     */
    private int breakerOpenSeconds = 30;

    /**
     * RAG 系统提示词模板位置（classpath 资源）
     */
    private String ragSystemPrompt = "classpath:prompts/rag-system.st";

    /**
     * 向量化入库单批最大条数。
     * DashScope text-embedding-v3 接口限制单次请求不超过 10 条文本，
     * 超过会报 "batch size is invalid, it should not be larger than 10"，
     * 所以入库时按此值分批调用。
     */
    private int embeddingBatchSize = 10;

    /**
     * 获取TopK。
     */
    public int getTopK() {
        return topK;
    }
    /**
     * 设置TopK。
     */
    public void setTopK(int topK) {
        this.topK = topK;
    }

    /**
     * 获取SimilarityThreshold。
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }
    /**
     * 设置SimilarityThreshold。
     */
    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 获取ChunkMaxChars。
     */
    public int getChunkMaxChars() {
        return chunkMaxChars;
    }
    /**
     * 设置ChunkMaxChars。
     */
    public void setChunkMaxChars(int chunkMaxChars) {
        this.chunkMaxChars = chunkMaxChars;
    }

    /**
     * 获取ChunkOverlap。
     */
    public int getChunkOverlap() {
        return chunkOverlap;
    }
    /**
     * 设置ChunkOverlap。
     */
    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 获取HistoryMaxTurns。
     */
    public int getHistoryMaxTurns() {
        return historyMaxTurns;
    }
    /**
     * 设置HistoryMaxTurns。
     */
    public void setHistoryMaxTurns(int historyMaxTurns) {
        this.historyMaxTurns = historyMaxTurns;
    }

    /**
     * 获取HistoryMaxEntries。
     */
    public int getHistoryMaxEntries() {
        return historyMaxEntries;
    }
    /**
     * 设置HistoryMaxEntries。
     */
    public void setHistoryMaxEntries(int historyMaxEntries) {
        this.historyMaxEntries = historyMaxEntries;
    }

    /**
     * 获取HistoryTtlHours。
     */
    public int getHistoryTtlHours() {
        return historyTtlHours;
    }
    /**
     * 设置HistoryTtlHours。
     */
    public void setHistoryTtlHours(int historyTtlHours) {
        this.historyTtlHours = historyTtlHours;
    }

    /**
     * 获取StreamTimeoutSeconds。
     */
    public int getStreamTimeoutSeconds() {
        return streamTimeoutSeconds;
    }
    /**
     * 设置StreamTimeoutSeconds。
     */
    public void setStreamTimeoutSeconds(int streamTimeoutSeconds) {
        this.streamTimeoutSeconds = streamTimeoutSeconds;
    }

    /**
     * 获取BreakerFailureThreshold。
     */
    public int getBreakerFailureThreshold() {
        return breakerFailureThreshold;
    }
    /**
     * 设置BreakerFailureThreshold。
     */
    public void setBreakerFailureThreshold(int breakerFailureThreshold) {
        this.breakerFailureThreshold = breakerFailureThreshold;
    }

    /**
     * 获取BreakerOpenSeconds。
     */
    public int getBreakerOpenSeconds() {
        return breakerOpenSeconds;
    }
    /**
     * 设置BreakerOpenSeconds。
     */
    public void setBreakerOpenSeconds(int breakerOpenSeconds) {
        this.breakerOpenSeconds = breakerOpenSeconds;
    }

    /**
     * 获取RagSystemPrompt。
     */
    public String getRagSystemPrompt() {
        return ragSystemPrompt;
    }
    /**
     * 设置RagSystemPrompt。
     */
    public void setRagSystemPrompt(String ragSystemPrompt) {
        this.ragSystemPrompt = ragSystemPrompt;
    }

    /**
     * 获取EmbeddingBatchSize。
     */
    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }
    /**
     * 设置EmbeddingBatchSize。
     */
    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }
}
