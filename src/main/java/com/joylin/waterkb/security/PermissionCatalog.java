package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限节点目录：菜单权限页的数据源，支持查看、编辑、新增、删除。
 *
 * <p>设计要点：
 * <ul>
 *   <li>节点 = {权限标识 code, 名称, 类型(功能菜单/操作按钮), 所属模块, 说明}；</li>
 *   <li><b>内置节点</b>（Permission.ALL 的 18 个码 + 5 个分组目录）绑定接口级校验，首次启动写入
 *       perm_node 表，可以改显示信息但<b>不可删除、不可改标识</b>——code 是
 *       &#64;RequirePermission 的绑定契约；</li>
 *   <li><b>自定义节点</b>可增删改，配合角色权限编辑分配给角色，前端按 code 控制入口显隐；</li>
 *   <li>RolePermissions 保存角色权限时按本目录校验合法性。</li>
 * </ul></p>
 */
@Service
public class PermissionCatalog {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalog.class);

    /**
     * 内置节点按真实导航排列，权限树即页面地图：
     * 工作台 = 问答侧栏；管理后台 = 后台侧栏（权限配置目录 / 业务管理 / 日志审计）。
     * 列依次为 code, 名称, 类型, 模块(分组), 说明, 上级code, 路由, 排序。
     * 目录节点（*:root）不绑定接口权限码，仅作树形分组，role 不可勾选。
     */
    /**
     * 内置节点种子从 perm-nodes.json 读取，避免与前端 perm-manifest.js 双份维护。
     * 首次启动写入，之后以库为准；结构字段（层级/路由/排序）每次启动按种子校正，
     * 名称只在仍是旧名时改，管理员改过的显示名保留。
     */
    private List<Map<String, Object>> builtInNodes() {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("perm-nodes.json")) {
            if (in == null) throw new IllegalStateException("perm-nodes.json 不存在");
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            List<Map<String, Object>> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root.path("nodes")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", n.path("code").asText());
                row.put("name", n.path("name").asText());
                row.put("type", n.path("type").asText("menu"));
                row.put("module", n.path("module").asText(""));
                row.put("desc", n.path("desc").asText(""));
                row.put("parent", n.path("parent").asText(""));
                row.put("route", n.path("route").asText(""));
                row.put("order", n.path("order").asInt(0));
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("权限节点种子读取失败：" + e.getMessage(), e);
        }
    }

    /** 内置节点改名（旧名 → 新名）：只改仍用旧名的行，用户自定义的名称不覆盖 */
    private static final Map<String, String[]> RENAMES = Map.of(
            "system:root", new String[]{"系统管理", "管理后台"},
            "admin:access", new String[]{"管理后台", "进入管理后台"},
            "tree:use", new String[]{"问答分支", "会话结构"},
            "user:read", new String[]{"用户查看", "用户管理"},
            "audit:read", new String[]{"审计日志", "操作日志"});

    private final JdbcTemplate jdbc;

    public PermissionCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perm_node ("
                + "code VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                + "node_type VARCHAR(16) NOT NULL DEFAULT 'menu', module VARCHAR(32) NOT NULL, "
                + "description VARCHAR(256), builtin BOOLEAN NOT NULL DEFAULT FALSE, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        // 若依风格三级树字段：平滑升级，旧库也能直接启动
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS parent_code VARCHAR(64) DEFAULT ''");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS route_path VARCHAR(128) DEFAULT ''");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS order_num INT DEFAULT 0");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS retired BOOLEAN DEFAULT FALSE");
        // 内置节点首次初始化写入，之后以库为准（用户改过的显示信息不会被启动覆盖）
        List<Map<String, Object>> builtins = builtInNodes();
        for (Map<String, Object> n : builtins) {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,parent_code,route_path,order_num) "
                            + "VALUES(?,?,?,?,?,TRUE,?,?,?) ON CONFLICT (code) DO NOTHING",
                    n.get("code"), n.get("name"), n.get("type"), n.get("module"), n.get("desc"),
                    n.get("parent"), n.get("route"), n.get("order"));
        }
        // 存量内置节点按真实导航重排（结构字段以种子为准，避免老库层级对不上页面）；
        // 名称只在仍是旧名时改，管理员改过的显示名保留。
        for (Map<String, Object> n : builtins) {
            jdbc.update("UPDATE perm_node SET parent_code=?, route_path=?, order_num=?, node_type=?, module=? "
                            + "WHERE code=? AND builtin=TRUE",
                    n.get("parent"), n.get("route"), n.get("order"), n.get("type"), n.get("module"), n.get("code"));
        }
        RENAMES.forEach((code, pair) -> jdbc.update(
                "UPDATE perm_node SET name=? WHERE code=? AND builtin=TRUE AND name=?",
                pair[1], code, pair[0]));
    }

    /** 全部权限节点（内置 + 自定义），按模块与内置顺序排列 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("SELECT code,name,node_type,module,description,builtin,parent_code,route_path,order_num "
                + "FROM perm_node WHERE COALESCE(retired,FALSE)=FALSE ORDER BY builtin DESC, order_num, code", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("nodeType", rs.getString("node_type"));
            m.put("module", rs.getString("module"));
            m.put("description", rs.getString("description"));
            m.put("builtin", rs.getBoolean("builtin"));
            m.put("parentCode", rs.getString("parent_code") == null ? "" : rs.getString("parent_code"));
            m.put("routePath", rs.getString("route_path") == null ? "" : rs.getString("route_path"));
            m.put("orderNum", rs.getInt("order_num"));
            out.add(m);
        });
        return out;
    }

    /** 权限标识是否存在于目录（角色保存时的合法性校验用） */
    public boolean exists(String code) {
        Boolean hit = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM perm_node WHERE code=?", Boolean.class, code);
        return Boolean.TRUE.equals(hit);
    }

    /**
     * 页面自动发现：前端上报本页出现的 data-perm 节点，未知权限码自动建目录项。
     * 只补新增、不改名不删节点——管理员改过的显示名与手动加的节点始终保留。
     */
    public Map<String, Object> discover(List<Map<String, Object>> items) {
        List<String> added = new ArrayList<>();
        int updated = 0;
        List<Map<String, Object>> safeItems = items == null ? List.of() : items;
        for (Map<String, Object> item : safeItems) {
            String code = s(item.get("code"));
            // 目录节点（含点号）由内置目录维护，不接受页面上报，避免自动生成野目录
            if (!code.matches("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$")) continue;
            String type = s(item.get("type"));
            if (!"menu".equals(type) && !"btn".equals(type)) continue;
            String name = s(item.get("name"));
            if (name.length() > 32) name = name.substring(0, 32);
            if (name.isEmpty()) name = code;
            String parent = s(item.get("parentCode"));
            String route = s(item.get("routePath"));
            String module = s(item.get("module"));
            if (module.isEmpty()) module = "自定义";
            try {
                if (exists(code)) {
                    // 内置节点的规范路由由目录定义，页面出现在哪里都不覆盖；
                    // 自定义节点才按页面声明补齐路由
                    jdbc.update("UPDATE perm_node SET route_path=CASE "
                                    + "WHEN ?='' OR (builtin=TRUE AND route_path<>'') THEN route_path ELSE ? END, "
                                    + "last_seen_at=CURRENT_TIMESTAMP WHERE code=?",
                            route, route, code);
                    updated++;
                } else {
                    jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,"
                                    + "parent_code,route_path,order_num,last_seen_at) "
                                    + "VALUES(?,?,?,?,?,FALSE,?,?,0,CURRENT_TIMESTAMP)",
                            code, name, type, module, "页面自动发现", parent, route);
                    added.add(code);
                }
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发上报：另一请求已建好，视为更新即可
                jdbc.update("UPDATE perm_node SET last_seen_at=CURRENT_TIMESTAMP WHERE code=?", code);
                updated++;
            }
        }
        return Map.of("added", added, "updated", updated);
    }

    /**
     * 声明式清单对账：以前端 perm-manifest.js 为准同步内置节点。
     * - 清单里的节点：新增或按清单修正 name/层级/路由/排序/类型；
     * - 清单里消失的内置节点：标记"已下线"并从权限树隐藏（不硬删，角色授权不瞬间丢失）；
     * - 用户手工创建的自定义节点：完全不碰。
     */
    public Map<String, Object> sync(List<Map<String, Object>> items) {
        List<String> added = new ArrayList<>(), updated = new ArrayList<>(), retired = new ArrayList<>();
        List<Map<String, Object>> safe = items == null ? List.of() : items;
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> item : safe) {
            String code = s(item.get("code"));
            if (!code.matches("^[a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*$")) continue;
            String name = s(item.get("name"));
            if (name.isEmpty()) name = code;
            String type = normalizeType(s(item.get("type")));
            String parent = s(item.get("parentCode"));
            if (!parent.isEmpty() && !exists(parent)) parent = "";
            String route = s(item.get("routePath"));
            String module = s(item.get("module"));
            int order = 0;
            try { order = Integer.parseInt(String.valueOf(item.getOrDefault("orderNum", 0))); } catch (Exception ignored) { }
            codes.add(code);
            try {
                if (exists(code)) {
                    // 只同步内置节点；同名自定义节点保留管理员的手工配置
                    jdbc.update("UPDATE perm_node SET name=?,node_type=?,route_path=?,order_num=?,"
                                    + "module=CASE WHEN ?='' THEN module ELSE ? END,retired=FALSE,last_seen_at=CURRENT_TIMESTAMP "
                                    + "WHERE code=? AND builtin=TRUE",
                            name, type, route, order, module, module, code);
                    updated.add(code);
                } else {
                    jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,"
                                    + "parent_code,route_path,order_num,last_seen_at) "
                                    + "VALUES(?,?,?,?,'页面清单定义',TRUE,'',?,?,CURRENT_TIMESTAMP)",
                            code, name, type, module.isEmpty() ? "自定义" : module, route, order);
                    added.add(code);
                }
            } catch (org.springframework.dao.DuplicateKeyException e) {
                updated.add(code);
            }
        }
        // 第二阶段：所有节点都建好后统一修正父子关系，避免清单里父节点排在子节点后面导致层级被清空
        for (Map<String, Object> item : safe) {
            String code = s(item.get("code"));
            String parent = s(item.get("parentCode"));
            if (code.isEmpty() || parent.isEmpty()) continue;
            if (!codes.contains(parent) && !exists(parent)) continue;
            jdbc.update("UPDATE perm_node SET parent_code=? WHERE code=? AND builtin=TRUE", parent, code);
        }
        // 清单中消失的内置节点：标记下线，不删除
        try {
            List<String> builtins = jdbc.queryForList(
                    "SELECT code FROM perm_node WHERE builtin=TRUE AND retired=FALSE", String.class);
            for (String code : builtins) {
                if (codes.contains(code)) continue;
                jdbc.update("UPDATE perm_node SET retired=TRUE WHERE code=?", code);
                retired.add(code);
            }
        } catch (Exception e) {
            log.warn("权限清单下线扫描失败：{}", e.getMessage());
        }
        return Map.of("added", added, "updated", updated, "retired", retired);
    }

    private static String s(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    /** 新增自定义节点：标识格式 xxx:yyy 且不与现有节点冲突（兼容旧 5 参调用） */
    public void create(String code, String name, String type, String module, String desc) {
        create(code, name, type, module, desc, "", "", 0);
    }

    /** 若依风格新增：带上级节点、路由路径、显示顺序 */
    public void create(String code, String name, String type, String module, String desc,
                       String parentCode, String routePath, int orderNum) {
        validateCode(code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = normalizeType(type);
        try {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,parent_code,route_path,order_num) "
                            + "VALUES(?,?,?,?,?,FALSE,?,?,?)",
                    code.trim(), name.trim(), t,
                    module == null || module.isBlank() ? "自定义" : module.trim(),
                    desc == null ? "" : desc.trim(),
                    parentCode == null ? "" : parentCode.trim(),
                    routePath == null ? "" : routePath.trim(), orderNum);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("权限标识已存在：" + code);
        }
    }

    /** 编辑节点：内置节点只能改显示信息（code 不可变、不可删）；自定义节点全可改（兼容旧 5 参调用） */
    public void update(String code, String name, String type, String module, String desc) {
        update(code, name, type, module, desc, "", "", 0);
    }

    /** 若依风格编辑：可改上级节点、路由路径、显示顺序 */
    public void update(String code, String name, String type, String module, String desc,
                       String parentCode, String routePath, int orderNum) {
        if (!exists(code)) throw new IllegalArgumentException("权限节点不存在：" + code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = normalizeType(type);
        jdbc.update("UPDATE perm_node SET name=?, node_type=?, module=?, description=?, parent_code=?, route_path=?, order_num=? WHERE code=?",
                name.trim(), t,
                module == null || module.isBlank() ? "自定义" : module.trim(),
                desc == null ? "" : desc.trim(),
                parentCode == null ? "" : parentCode.trim(),
                routePath == null ? "" : routePath.trim(), orderNum, code);
    }

    private String normalizeType(String type) {
        return "btn".equals(type) ? "btn" : ("directory".equals(type) ? "directory" : "menu");
    }

    /** 删除节点：内置节点是接口校验契约，不允许删除 */
    public void delete(String code) {
        if (!exists(code)) throw new IllegalArgumentException("权限节点不存在：" + code);
        Boolean builtin = jdbc.queryForObject("SELECT builtin FROM perm_node WHERE code=?",
                Boolean.class, code);
        if (Boolean.TRUE.equals(builtin)) {
            throw new IllegalArgumentException("内置权限节点不可删除（它是接口校验的绑定契约）");
        }
        jdbc.update("DELETE FROM perm_node WHERE code=?", code);
        // 目录里删掉的权限码，各角色配置里也要同步清理
        jdbc.update("DELETE FROM role_permissions WHERE permission=?", code);
    }

    /** code 只允许小写字母/数字/下划线/连字符，且必须带一个冒号分层（如 report:view） */
    private void validateCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$")) {
            throw new IllegalArgumentException("权限标识格式：小写字母开头，形如 report:view");
        }
    }
}
