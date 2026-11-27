package com.joylin.waterkb.mapper;

import com.joylin.waterkb.model.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 审计日志数据访问。
 */
@Mapper
public interface AuditMapper {

    /**
     * 写入审计。
     */
    int insert(@Param("ts") Timestamp ts, @Param("tenantId") String tenantId,
               @Param("userId") String userId, @Param("groups") String groups,
               @Param("action") String action, @Param("resource") String resource,
               @Param("detail") String detail, @Param("ip") String ip,
               @Param("status") int status, @Param("durationMs") long durationMs,
               @Param("result") String result);

    /**
     * 登录日志。
     */
    List<Map<String, Object>> loginLogs(@Param("tenant") String tenant, @Param("limit") int limit);

    /**
     * 本部门审计。
     */
    List<Map<String, Object>> recent(@Param("tenant") String tenant, @Param("limit") int limit);

    /**
     * 全部审计。
     */
    List<Map<String, Object>> recentAll(@Param("limit") int limit);

    /**
     * 按类型导出。
     */
    List<Map<String, Object>> export(@Param("tenant") String tenant, @Param("all") boolean all,
                                     @Param("type") String type, @Param("limit") int limit);

    /**
     * 本部门统计。
     */
    Map<String, Object> stats(@Param("tenant") String tenant);

    /**
     * 全量统计。
     */
    Map<String, Object> statsAll();
}
