package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**

 * 模型用量 Mapper（MyBatis）：model_usage 的写入与统计。DDL 由 UsageRecorder 负责。

 */
@Mapper
public interface UsageMapper {

    int insert(@Param("userId") String userId, @Param("model") String model,
               @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens,
               @Param("durationMs") long durationMs, @Param("sessionId") String sessionId,
               @Param("question") String question, @Param("tenantId") String tenantId);

    /**

     * 逐次明细：tenant/user 为空则不过滤

     */
    List<Map<String, Object>> records(@Param("start") Timestamp start, @Param("tenant") String tenant,
                                      @Param("user") String user, @Param("limit") int limit);

    /**

     * 请求次数：tenant/user 为空则不过滤

     */
    long count(@Param("start") Timestamp start, @Param("tenant") String tenant, @Param("user") String user);

    /**

     * Token 合计：tenant/user 为空则不过滤

     */
    long tokens(@Param("start") Timestamp start, @Param("tenant") String tenant, @Param("user") String user);

    /**

     * 按模型汇总

     */
    List<Map<String, Object>> modelRows(@Param("start") Timestamp start, @Param("tenant") String tenant,
                                        @Param("user") String user);
}
