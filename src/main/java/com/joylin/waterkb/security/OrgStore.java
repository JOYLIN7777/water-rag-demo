package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织（原名"空间"）登记表。
 *
 * <p>为什么要有这张表：账号的 tenant 字段以前是裸字符串，界面上的中文名写死在前端
 * （只有 default → 默认空间一条），既不能新建组织，也不能改名——组织架构页看着像
 * 只能看不能动。现在组织名落库，改名即时生效，本系统内的所有页面都从同一个来源取名。</p>
 *
 * <p>组织键（org_key）是数据隔离的真正依据：文档（ES 的 tenantId）、会话（Redis key）
 * 与用量都按它隔离，所以<b>改名不改键</b>——改键等于把数据搬走，不做。</p>
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

    /** 建表 + 补默认组织 + 把历史账号里出现过的 tenant 补登记（幂等，失败不影响主链路） */
    private void ensure() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            try {
                jdbc.execute("CREATE TABLE IF NOT EXISTS org_unit ("
                        + "org_key VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbc.update("INSERT INTO org_unit(org_key,name) VALUES(?,?) ON CONFLICT (org_key) DO NOTHING",
                        DEFAULT_KEY, "默认组织");
                // 旧数据兜底：账号里出现过但组织表没有的 tenant，按标识名补一条，
                // 否则这些账号在"所属组织"下拉里会凭空消失。
                jdbc.update("INSERT INTO org_unit(org_key,name) "
                        + "SELECT DISTINCT tenant, tenant FROM app_users ON CONFLICT (org_key) DO NOTHING");
                ready = true;
            } catch (Exception e) {
                // app_users 可能还没建（Bean 初始化顺序），下次调用再试
                log.debug("组织表初始化延后：{}", e.getMessage());
            }
        }
    }

    /** 组织清单（不含成员数，成员数由调用方按账号列表统计） */
    public List<Map<String, Object>> list() {
        ensure();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            jdbc.query("SELECT org_key,name FROM org_unit ORDER BY (org_key=?) DESC, created_at", rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", rs.getString("org_key"));
                m.put("name", rs.getString("name"));
                m.put("builtin", DEFAULT_KEY.equals(rs.getString("org_key")));
                out.add(m);
            }, DEFAULT_KEY);
        } catch (Exception e) {
            log.warn("读取组织清单失败：{}", e.getMessage());
        }
        if (out.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("key", DEFAULT_KEY);
            fallback.put("name", "默认组织");
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
     * 新建组织。
     *
     * @param name 组织名称（2-20 字，可重复但要提醒）
     * @param key  组织标识；留空自动生成 org-xxxxxx（中文名没法转拼音，不硬猜）
     * @return 新组织键
     */
    public String create(String name, String key) {
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
        try {
            jdbc.update("INSERT INTO org_unit(org_key,name) VALUES(?,?)", k, n);
        } catch (Exception e) {
            throw new IllegalStateException("组织创建失败：" + e.getMessage());
        }
        return k;
    }

    /** 重命名组织：只改显示名，组织键不动（键是数据隔离依据） */
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
     * 删除组织。
     *
     * <p>只允许删空组织：还有成员的必须先改归属，否则那些账号会掉进一个不存在的组织，
     * 登录后看到的文档与历史都是空的。默认组织是兜底归属，永不删。</p>
     */
    public void delete(String key, int members) {
        ensure();
        if (DEFAULT_KEY.equals(key)) throw new IllegalArgumentException("默认组织是兜底归属，不能删除");
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
