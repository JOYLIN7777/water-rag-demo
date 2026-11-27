package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 角色权限数据访问。
 */
@Mapper
public interface RolePermissionMapper {

    /**
     * 查询全部角色权限。
     */
    List<Map<String, Object>> listAllPermissions();

    /**
     * 查询全部角色名称。
     */
    List<Map<String, Object>> listAllNames();

    /**
     * 删除角色全部权限。
     */
    int deletePermissions(@Param("role") String role);

    /**
     * 新增角色权限。
     */
    int insertPermission(@Param("role") String role, @Param("permission") String permission);

    /**
     * 保存角色名称。
     */
    int upsertName(@Param("role") String role, @Param("name") String name);

    /**
     * 删除角色定义。
     */
    int deleteName(@Param("role") String role);
}
