package com.joylin.waterkb.mapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 *
 */
@Service
public class OrgStore {

    private static final Logger log = LoggerFactory.getLogger(OrgStore.class);
    public static final String DEFAULT_KEY = "default";

    private final OrgMapper mapper;
    private final SchemaSeedMapper seedMapper;
    private volatile boolean ready;

    public OrgStore() { this(null, null); }

    @Autowired
    public OrgStore(OrgMapper mapper, SchemaSeedMapper seedMapper) { this.mapper = mapper; this.seedMapper = seedMapper; }

    @PostConstruct
    public void init() { ensure(); }

    /**

     * 建表 + 补 parent_key 列 + 补默认组织 + 把历史账号里出现过的 tenant 补登记（幂等）

     */
    private void ensure() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            try {
                // 只在一条组织都没有时建第一个顶层组织。
                // 不再专门造一个叫「默认组织」的内置项：组织就是组织，
                // 第一个组织叫什么、要不要改名、最后要不要删，都由使用者决定，
                // 系统只守住一条底线——不能删到一个都不剩（账号必须有归属）。
                seedMapper.seedTopOrg(DEFAULT_KEY, "顶层组织");
                // 旧数据兜底：账号里出现过但组织表没有的 tenant，按标识名补一条，
                // 否则这些账号在"所属组织"下拉里会凭空消失。
                seedMapper.seedOrgsFromUsers();
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
     */
    public List<Map<String, Object>> list() {
        ensure();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            // 按创建先后排：第一个建的自然在最上面，不再把某个 key 特殊排前
            for (Map<String, Object> row : mapper.list()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", row.get("key"));
                m.put("name", row.get("name"));
                m.put("parentKey", row.get("parentKey") == null ? "" : row.get("parentKey"));
                m.put("builtin", false);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("读取组织清单失败：{}", e.getMessage());
        }
        if (out.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("key", DEFAULT_KEY);
            fallback.put("name", "顶层组织");
            fallback.put("parentKey", "");
            fallback.put("builtin", false);
            out.add(fallback);
        }
        return out;
    }

    public boolean exists(String key) {
        ensure();
        if (key == null || key.isBlank()) return false;
        try {
            Long n = (long) mapper.exists(key.trim());
            return n != null && n > 0;
        } catch (Exception e) { return false; }
    }

    /**
     * 新建组织（可挂在任意节点下）。
     *
     * @return 新组织键
     */
    public String create(String name, String key, String parentKey) {
        ensure();
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 20) throw new IllegalArgumentException("组织名称需 2-20 个字");
        String k = key == null ? "" : key.trim().toLowerCase();
        if (k.isEmpty()) {
            // 前端总会传按名称生成的英文标识；API 直调时取名称里的 ASCII 兜底
            String ascii = n.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
            k = "org-" + (ascii.isEmpty() ? "unit" : ascii);
            if (k.length() > 28) k = k.substring(0, 28).replaceAll("-$", "");
        } else if (!k.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("组织标识需以小写字母开头，2-32 位，只能含小写字母、数字、短横线");
        }
        // 重名不再报错：追加 2 个随机字母，直到不冲突（按用户约定的生成规则）
        if (exists(k)) {
            String base = k;
            do { k = base + randomLetters(2); } while (exists(k));
        }
        String parent = parentKey == null ? "" : parentKey.trim();
        if (!parent.isEmpty() && !exists(parent)) {
            throw new IllegalArgumentException("上级组织不存在：" + parent);
        }
        if (parent.equals(k)) throw new IllegalArgumentException("上级组织不能是自己");
        try {
            mapper.insert(k, n, parent.isEmpty() ? null : parent);
        } catch (Exception e) {
            throw new IllegalStateException("组织创建失败：" + e.getMessage());
        }
        return k;
    }

    /**

     * 兼容旧调用：建在顶层

     */
    public String create(String name, String key) {
        return create(name, key, null);
    }

    private static String randomLetters(int n) {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) ('a' + rnd.nextInt(26)));
        return sb.toString();
    }

    /**

     * 重命名组织：只改显示名，组织键与上级关系都不动

     */
    public boolean rename(String key, String name) {
        ensure();
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 20) throw new IllegalArgumentException("组织名称需 2-20 个字");
        try {
            return mapper.rename(n, key) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("组织改名失败：" + e.getMessage());
        }
    }

    /**
     * 调整上级组织（把整棵子树挪走，键值都不变）。
     *
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
        mapper.setParent(parent.isEmpty() ? null : parent, key);
    }

    /**
     * 祖先链（从直接上级一路到根，不含自己）。
     *
     */
    public List<String> ancestorKeys(String key) {
        ensure();
        List<String> out = new ArrayList<>();
        if (key == null || key.isBlank()) return out;
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Map<String, Object> m : list()) {
            parentOf.put(String.valueOf(m.get("key")), String.valueOf(m.getOrDefault("parentKey", "")));
        }
        Set<String> seen = new LinkedHashSet<>();
        String cur = key;
        while (true) {
            String parent = parentOf.get(cur);
            if (parent == null || parent.isEmpty() || !seen.add(parent)) break;
            out.add(parent);
            cur = parent;
        }
        return out;
    }

    /**

     * key 自身 + 所有子孙的键（BFS；一次读全表，组织规模在几十上百，没必要递归查库）

     */
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

    /**

     * 下级组织个数（不含自身）

     */
    public int childCount(String key) {
        ensure();
        try {
            Long n = (long) mapper.childCount(key);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /**

     * 组织总数（用于守住"不能删到一个都不剩"）

     */
    public int count() {
        ensure();
        try {
            Long n = (long) mapper.count();
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /**
     * 删除组织。
     *
     */
    public void delete(String key, int members) {
        ensure();
        int children = childCount(key);
        if (children > 0) throw new IllegalArgumentException("该组织下还有 " + children + " 个子组织，请先处理子组织");
        if (members > 0) throw new IllegalArgumentException("该组织还有 " + members + " 个成员，请先把成员移到其它组织");
        if (count() <= 1) throw new IllegalArgumentException("至少要保留一个组织：账号必须有归属单位，删掉最后一个组织相当于让所有人无处可去");
        try {
            mapper.delete(key);
        } catch (Exception e) {
            throw new IllegalStateException("组织删除失败：" + e.getMessage());
        }
    }

    /**

     * 组织键 → 名称（取不到就回键本身，界面不会空着）

     */
    public Map<String, String> nameMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list()) {
            out.put(String.valueOf(m.get("key")), String.valueOf(m.get("name")));
        }
        return out;
    }
}
