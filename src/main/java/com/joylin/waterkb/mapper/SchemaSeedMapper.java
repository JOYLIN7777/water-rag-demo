package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 启动种子与数据对账数据访问。
 */
@Mapper
public interface SchemaSeedMapper {

    /**
     * 首次写入内置权限节点。
     */
    int seedPermissionNode(Map<String, Object> node);

    /**
     * 重排内置节点结构。
     */
    int syncPermissionNode(Map<String, Object> node);

    /**
     * 权限节点改名。
     */
    int renamePermissionNode(@Param("code") String code, @Param("oldName") String oldName,
                             @Param("newName") String newName);

    /**
     * 首次写入角色名称。
     */
    int seedRoleName(@Param("role") String role, @Param("name") String name);

    /**
     * 首次写入角色权限。
     */
    int seedRolePermission(@Param("role") String role, @Param("permission") String permission);

    /**
     * 组织为空时建顶层组织。
     */
    int seedTopOrg(@Param("key") String key, @Param("name") String name);

    /**
     * 补齐账号里出现过的组织。
     */
    int seedOrgsFromUsers();

    /**
     * 模型表为空时插入初始配置。
     */
    int seedModel(@Param("name") String name, @Param("type") String type, @Param("baseUrl") String baseUrl,
                  @Param("apiKey") String apiKey, @Param("model") String model,
                  @Param("dimensions") Integer dimensions);

    /**
     * 查询种子迁移版本是否已执行。
     */
    int hasMigration(@Param("version") String version);

    /**
     * 标记权限节点下线。
     */
    int retireNode(@Param("code") String code);

    /**
     * 删除角色权限。
     */
    int deleteRolePermission(@Param("role") String role, @Param("permission") String permission);

    /**
     * 记录种子迁移版本。
     */
    int markMigration(@Param("version") String version);

    /**
     * 数据库探活。
     */
    int ping();
}
