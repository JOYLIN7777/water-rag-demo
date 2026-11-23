package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织（原名"空间"）登记表，<b>树状</b>结构。
 *
 * <p>为什么是树而不是一层清单：企业里的组织天然分层（集团 → 分公司 → 部门 → 班组），
 * 一层清单只能靠名字前缀凑层级，改名、调架构时全是手工活。这里用 parent_key 自关联，
 * 任意层都能挂子组织，前端按父子关系画树。</p>
 *
 * <p>组织键（org_key）是数据隔离的真正依据：文档（ES 的 tenantId）、会话（Redis key）
 * 与用量都按它隔离，所以<b>改名不改键</b>，<b>挪上级也不改键</b>——改键等于把数据搬走，不做。</p>
 *
 * <p>层级只影响"归属关系与展示"，不改变隔离粒度：账号仍然只绑定一个组织键（tenant），
 * 不会因为挂在子组织下就自动继承父组织的文档可见范围。要看子组织的成员，用
 * {@link #subtreeKeys(String)} 做汇总查询，这是刻意的选择——隔离边界越简单越不容易漏权限。</p>
 */
@Service
public class OrgStore {

    private static final Logger log = LoggerFactory.getLogger(OrgStore.class);
    public static final String DEFAULT_KEY = "default";

    private final JdbcTemplate jdbc;
    private volatile boolean ready;

    public OrgStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void init() { ensure(); }

    /** 建表 + 补 parent_key 列 + 补默认组织 + 把历史账号里出现过的 tenant 补登记（幂等） */
    private void ensure() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            try {
                jdbc.execute("CREATE TABLE IF NOT EXISTS org_unit ("
                        + "org_key VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                        + "parent_key VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                // 老库升级：上一版表里没有 parent_key，缺列时补上（历史组织全部落在顶层）
                jdbc.execute("ALTER TABLE org_unit ADD COLUMN IF NOT EXISTS parent_key VARCHAR(64)");
                jdbc.update("INSERT INTO org_unit(org_key,name,parent_key) VALUES(?,?,NULL) "
                        + "ON CONFLICT (org_key) DO NOTHING", DEFAULT_KEY, "默认组织");
                // 旧数据兜底：账号里出现过但组织表没有的 tenant，按标识名补一条，
                // 否则这些账号在"所属组织"下拉里会凭空消失。
                jdbc.update("INSERT INTO org_unit(org_key,name,parent_key) "
                        + "SELECT DISTINCT tenant, tenant, NULL FROM app_users ON CONFLICT (org_key) DO NOTHING");
                ready = true;
            } catch (Exception e) {
                // app_users 可能还没建（Bean 初始化顺序），下次调用再试
                log.debug("组织表初始化延后：{}", e.getMessage());
            }
        }
    }

    /**
     * 组织清单（扁平，含 parentKey；成员数由调用方按账号列表统计）。
     *
     * <p>返回扁平表而不是嵌套 JSON：前端要同时用于画树、做下拉、算面包屑，
     * 扁平一份数据三种用法都能派生，嵌套结构反而每处都得再拍平。</p>
     */
    public List<Map<String, Object>> list() {
        ensure();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            jdbc.query("SELECT org_key,name,parent_key FROM org_unit "
                    + "ORDER BY (org_key=?) DESC, created_at", rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                String key = rs.getString("org_key");
                m.put("key", key);
                m.put("name", rs.getString("name"));
                String parent = rs.getString("parent_key");
                m.put("parentKey", parent == null ? "" : parent);
                m.put("builtin", DEFAULT_KEY.equals(key));
                out.add(m);
            }, DEFAULT_KEY);
        } catch (Exception e) {
            log.warn("读取组织清单失败：{}", e.getMessage());
        }
        if (out.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("key", DEFAULT_KEY);
            fallback.put("name", "默认组织");
            fallback.put("parentKey", "");
            fallback.put("builtin", true);
            out.add(fallback);
        }
        return out;
    }

    public boolean exists(String key) {
        ensure();
        if (key == null || key.isBlank()) return false;
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM org_unit WHERE org_key=?", Long.class, key.trim());
            return n != null && n > 0;
        } catch (Exception e) { return false; }
    }

    /**
     * 新建组织（可挂在任意节点下）。
     *
     * @param name      组织名称（2-20 字）
     * @param key       组织标识；留空自动生成 org-xxxxxx（中文名没法转拼音，不硬猜）
     * @param parentKey 上级组织键；空字符串/null 表示顶层
     * @return 新组织键
     */
    public String create(String name, String key, String parentKey) {
        ensure();
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 20) throw new IllegalArgumentException("组织名称需 2-20 个字");
        String k = key == null ? "" : key.trim().toLowerCase();
        if (k.isEmpty()) {
            k = "org-" + Long.toString(System.currentTimeMillis(), 36);
        } else if (!k.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("组织标识需以小写字母开头，2-32 位，只能含小写字母、数字、短横线");
        }
        if (exists(k)) throw new IllegalArgumentException("组织标识「" + k + "」已被占用");
        String parent = parentKey == null ? "" : parentKey.trim();
        if (!parent.isEmpty() && !exists(parent)) {
            throw new IllegalArgumentException("上级组织不存在：" + parent);
        }
        if (parent.equals(k)) throw new IllegalArgumentException("上级组织不能是自己");
        try {
            jdbc.update("INSERT INTO org_unit(org_key,name,parent_key) VALUES(?,?,?)",
                    k, n, parent.isEmpty() ? null : parent);
        } catch (Exception e) {
            throw new IllegalStateException("组织创建失败：" + e.getMessage());
        }
        return k;
    }

    /** 兼容旧调用：建在顶层 */
    public String create(String name, String key) {
        return create(name, key, null);
    }

    /** 重命名组织：只改显示名，组织键与上级关系都不动 */
    public boolean rename(String key, String name) {
        ensure();
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 20) throw new IllegalArgumentException("组织名称需 2-20 个字");
        try {
            return jdbc.update("UPDATE org_unit SET name=? WHERE org_key=?", n, key) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("组织改名失败：" + e.getMessage());
        }
    }

    /**
     * 调整上级组织（把整棵子树挪走，键值都不变）。
     *
     * <p>防环：新上级不能是自己或自己的子孙，否则树会变成环——画树的递归直接爆栈，
     * 而且这些组织的成员从此无法从任何一个根走到。</p>
     */
    public void setParent(String key, String newParentKey) {
        ensure();
        if (!exists(key)) throw new IllegalArgumentException("组织不存在：" + key);
        String parent = newParentKey == null ? "" : newParentKey.trim();
        if (parent.equals(key)) throw new IllegalArgumentException("上级组织不能是自己");
        if (!parent.isEmpty()) {
            if (!exists(parent)) throw new IllegalArgumentException("上级组织不存在：" + parent);
            if (subtreeKeys(key).contains(parent)) throw new IllegalArgumentException("不能把组织挪到自己的下级里");
        }
        jdbc.update("UPDATE org_unit SET parent_key=? WHERE org_key=?",
                parent.isEmpty() ? null : parent, key);
    }

    /** key 自身 + 所有子孙的键（BFS；一次读全表，组织规模在几十上百，没必要递归查库） */
    public Set<String> subtreeKeys(String key) {
        ensure();
        Set<String> out = new LinkedHashSet<>();
        if (key == null || key.isBlank()) return out;
        List<Map<String, Object>> all = list();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(key);
        out.add(key);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Map<String, Object> m : all) {
                String k = String.valueOf(m.get("key"));
                String p = String.valueOf(m.getOrDefault("parentKey", ""));
                if (cur.equals(p) && out.add(k)) queue.add(k);
            }
        }
        return out;
    }

    /** 下级组织个数（不含自身） */
    public int childCount(String key) {
        ensure();
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM org_unit WHERE parent_key=?", Long.class, key);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /**
     * 删除组织。
     *
     * <p>只允许删"叶子且无人"的组织：还有成员的话那些账号会掉进一个不存在的组织，
     * 登录后文档与历史全空；还有子组织的话子组织会变成孤儿，树上再也点不到。
     * 默认组织是兜底归属，永不删。</p>
     */
    public void delete(String key, int members) {
        ensure();
        if (DEFAULT_KEY.equals(key)) throw new IllegalArgumentException("默认组织是兜底归属，不能删除");
        int children = childCount(key);
        if (children > 0) throw new IllegalArgumentException("该组织下还有 " + children + " 个子组织，请先处理子组织");
        if (members > 0) throw new IllegalArgumentException("该组织还有 " + members + " 个成员，请先把成员移到其它组织");
        try {
            jdbc.update("DELETE FROM org_unit WHERE org_key=?", key);
        } catch (Exception e) {
            throw new IllegalStateException("组织删除失败：" + e.getMessage());
        }
    }

    /** 组织键 → 名称（取不到就回键本身，界面不会空着） */
    public Map<String, String> nameMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list()) {
            out.put(String.valueOf(m.get("key")), String.valueOf(m.get("name")));
        }
        return out;
    }
}
