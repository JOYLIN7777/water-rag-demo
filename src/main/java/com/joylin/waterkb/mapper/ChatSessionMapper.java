package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 会话清单 Mapper（MyBatis）：chat_session 表的 DML。DDL 由 ChatSessionStore 负责。
 */
@Mapper
public interface ChatSessionMapper {

    List<Map<String, Object>> listByUser(@Param("userId") String userId);

    int countByTenant(@Param("tenant") String tenant);

    int migrateTenant(@Param("from") String from, @Param("to") String to);

    int upsert(@Param("sessionId") String sessionId, @Param("userId") String userId,
               @Param("tenant") String tenant, @Param("title") String title);

    int exists(@Param("userId") String userId, @Param("sessionId") String sessionId);

    int rename(@Param("title") String title, @Param("userId") String userId,
               @Param("sessionId") String sessionId);

    int delete(@Param("userId") String userId, @Param("sessionId") String sessionId);
}
