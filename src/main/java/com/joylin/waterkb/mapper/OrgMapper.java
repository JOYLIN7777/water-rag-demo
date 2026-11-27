package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 组织表 Mapper（MyBatis）：org_unit 的 DML。DDL 由 OrgStore 负责。
 */
@Mapper
public interface OrgMapper {

    List<Map<String, Object>> list();

    int exists(@Param("key") String key);

    int insert(@Param("key") String key, @Param("name") String name, @Param("parent") String parent);

    int rename(@Param("name") String name, @Param("key") String key);

    int setParent(@Param("parent") String parent, @Param("key") String key);

    int childCount(@Param("key") String key);

    int count();

    int delete(@Param("key") String key);
}
