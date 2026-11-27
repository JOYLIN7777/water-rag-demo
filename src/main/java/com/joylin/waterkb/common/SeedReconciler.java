package com.joylin.waterkb.common;

import com.joylin.waterkb.mapper.SchemaSeedMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 种子变更对账：解决「种子 JSON 改了，但数据库旧记录还在，重启后又从 DB 读回来」的问题。
 *
 */
@Component
public class SeedReconciler {

    private static final Logger log = LoggerFactory.getLogger(SeedReconciler.class);
    private final SchemaSeedMapper mapper;

    /**
     * 版本号：每次新增废弃项时 +1
     */
    private static final String VERSION = "2026-11-27-tree-and-docwrite";

    /**
     * 已废弃的内置权限节点：菜单不再显示
     */
    private static final List<String> RETIRED_NODES = List.of("tree:use");

    /**
     * 已废弃的内置角色默认权限：(角色, 权限)
     */
    private static final List<String[]> REMOVED_ROLE_PERMS = List.<String[]>of(
            new String[]{"audit", "doc:write"});

    /**
     * 构造种子对账器。
     */
    public SeedReconciler(SchemaSeedMapper mapper) { this.mapper = mapper; }

    /**
     * 启动时校验生产配置。
     */
    @PostConstruct
    public void reconcile() {
        try {
            if (mapper.hasMigration(VERSION) > 0) return;

            for (String code : RETIRED_NODES) {
                mapper.retireNode(code);
                log.info("种子对账：废弃权限节点 {}", code);
            }
            for (String[] pair : REMOVED_ROLE_PERMS) {
                mapper.deleteRolePermission(pair[0], pair[1]);
                log.info("种子对账：移除角色 {} 的权限 {}", pair[0], pair[1]);
            }
            mapper.markMigration(VERSION);
        } catch (Exception e) {
            log.warn("种子对账跳过（表不可用）：{}", e.getMessage());
        }
    }
}
