package com.joylin.waterkb.common;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 种子变更对账：解决「种子 JSON 改了，但数据库旧记录还在，重启后又从 DB 读回来」的问题。
 *
 * <p>只处理代码里显式声明的"已废弃项"，不碰管理员在后台的其它改动。
 * 每个版本只执行一次（seed_migration 表记录），升级后不会反复覆盖用户配置。</p>
 */
@Component
public class SeedReconciler {

    private static final Logger log = LoggerFactory.getLogger(SeedReconciler.class);
    private final JdbcTemplate jdbc;

    /** 版本号：每次新增废弃项时 +1 */
    private static final String VERSION = "2026-11-27-tree-and-docwrite";

    /** 已废弃的内置权限节点：菜单不再显示 */
    private static final List<String> RETIRED_NODES = List.of("tree:use");

    /** 已废弃的内置角色默认权限：(角色, 权限) */
    private static final List<String[]> REMOVED_ROLE_PERMS = List.<String[]>of(
            new String[]{"audit", "doc:write"});

    public SeedReconciler(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void reconcile() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS seed_migration ("
                    + "version VARCHAR(100) PRIMARY KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seed_migration WHERE version=?", Integer.class, VERSION);
            if (n != null && n > 0) return;

            for (String code : RETIRED_NODES) {
                jdbc.update("UPDATE perm_node SET retired=TRUE WHERE code=?", code);
                log.info("种子对账：废弃权限节点 {}", code);
            }
            for (String[] pair : REMOVED_ROLE_PERMS) {
                jdbc.update("DELETE FROM role_permissions WHERE role=? AND permission=?", pair[0], pair[1]);
                log.info("种子对账：移除角色 {} 的权限 {}", pair[0], pair[1]);
            }
            jdbc.update("INSERT INTO seed_migration(version) VALUES(?)", VERSION);
        } catch (Exception e) {
            log.warn("种子对账跳过（表不可用）：{}", e.getMessage());
        }
    }
}
