package com.joylin.waterkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 连接与检索参数，外置到 application.yml 的 es 前缀下。
 *
 */
@ConfigurationProperties(prefix = "es")
public class EsProperties {

    /**
     * ES 服务地址（容器内用服务名 http://elasticsearch:9200）
     */
    private String url = "http://localhost:9200";
    private String index = "water_knowledge";
    /**
     * 向量维度：必须与当前 Embedding 模型一致（text-embedding-v3 = 1024）
     */
    private int dimensions = 1024;

    /**
     * kNN 候选数（HNSW ef_search 概念，越大越准越慢）
     */
    private int knnCandidates = 100;
    /**
     * 最终返回条数
     */
    private int topK = 5;
    /**
     * RRF 平滑常数：ES 官方建议 60 起步，越大越抹平头部差异
     */
    private int rrfRankConstant = 60;
    /**
     * 全文检索的最低分数阈值（BM25 分数，0 表示不设阈值）
     */
    private double minScore = 0;

    /**
     * 获取Url。
     */
    public String getUrl() {
        return url;
    }
    /**
     * 设置Url。
     */
    public void setUrl(String url) {
        this.url = url;
    }
    /**
     * 获取Index。
     */
    public String getIndex() {
        return index;
    }
    /**
     * 设置Index。
     */
    public void setIndex(String index) {
        this.index = index;
    }
    /**
     * 获取Dimensions。
     */
    public int getDimensions() {
        return dimensions;
    }
    /**
     * 设置Dimensions。
     */
    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }
    /**
     * 获取KnnCandidates。
     */
    public int getKnnCandidates() {
        return knnCandidates;
    }
    /**
     * 设置KnnCandidates。
     */
    public void setKnnCandidates(int knnCandidates) {
        this.knnCandidates = knnCandidates;
    }
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
     * 获取RrfRankConstant。
     */
    public int getRrfRankConstant() {
        return rrfRankConstant;
    }
    /**
     * 设置RrfRankConstant。
     */
    public void setRrfRankConstant(int rrfRankConstant) {
        this.rrfRankConstant = rrfRankConstant;
    }
    /**
     * 获取MinScore。
     */
    public double getMinScore() {
        return minScore;
    }
    /**
     * 设置MinScore。
     */
    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }
}
