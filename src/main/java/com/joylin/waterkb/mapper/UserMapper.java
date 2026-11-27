package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 账号表 Mapper（MyBatis）：app_users 的 DML。DDL 由 UserStore 负责。 */
@Mapper
public interface UserMapper {

    List<Map<String, Object>> listAll();

    int upsert(@Param("username") String username, @Param("passwordHash") String passwordHash,
               @Param("groups") String groups, @Param("tenant") String tenant,
               @Param("displayName") String displayName, @Param("enabled") boolean enabled,
               @Param("gender") String gender, @Param("phone") String phone, @Param("avatar") String avatar);

    int delete(@Param("username") String username);
}
