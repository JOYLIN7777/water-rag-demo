package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 权限节点数据访问。
 */
@Mapper
public interface PermissionNodeMapper {

    /**
     * 查询全部权限码。
     */
    List<String> listAllCodes();

    /**
     * 查询全部有效节点。
     */
    List<Map<String, Object>> listAll();

    /**
     * 判断节点是否存在。
     */
    boolean exists(@Param("code") String code);

    /**
     * 页面发现时补齐路由。
     */
    int discoverRoute(@Param("route") String route, @Param("code") String code);

    /**
     * 页面发现时新增节点。
     */
    int discoverInsert(@Param("code") String code, @Param("name") String name,
                       @Param("type") String type, @Param("module") String module,
                       @Param("parent") String parent, @Param("route") String route);

    /**
     * 刷新节点活跃时间。
     */
    int touch(@Param("code") String code);

    /**
     * 同步内置节点信息。
     */
    int syncBuiltin(@Param("name") String name, @Param("type") String type,
                    @Param("route") String route, @Param("order") int order,
                    @Param("module") String module, @Param("code") String code);

    /**
     * 清单同步时新增内置节点。
     */
    int syncInsert(@Param("code") String code, @Param("name") String name,
                   @Param("type") String type, @Param("module") String module,
                   @Param("route") String route, @Param("order") int order);

    /**
     * 修正上级节点。
     */
    int setParent(@Param("parent") String parent, @Param("code") String code);

    /**
     * 查询未下线内置节点。
     */
    List<String> listActiveBuiltins();

    /**
     * 标记节点下线。
     */
    int retire(@Param("code") String code);

    /**
     * 新增节点。
     */
    int create(@Param("code") String code, @Param("name") String name, @Param("type") String type,
               @Param("module") String module, @Param("desc") String desc,
               @Param("parentCode") String parentCode, @Param("routePath") String routePath,
               @Param("orderNum") int orderNum);

    /**
     * 编辑节点。
     */
    int update(@Param("code") String code, @Param("name") String name, @Param("type") String type,
               @Param("module") String module, @Param("desc") String desc,
               @Param("parentCode") String parentCode, @Param("routePath") String routePath,
               @Param("orderNum") int orderNum);

    /**
     * 判断内置节点。
     */
    Boolean isBuiltin(@Param("code") String code);

    /**
     * 删除节点。
     */
    int delete(@Param("code") String code);

    /**
     * 删除角色配置中的权限码。
     */
    int deleteFromRoles(@Param("code") String code);
}
