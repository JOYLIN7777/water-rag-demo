package com.joylin.waterkb.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话消息树存储（pi-mono 风格 fork/rewind 的地基）。
 *
 * <p>数据结构：一个会话 = 3 个 Redis key
 * <ul>
 *   <li>tree:{sid}——Hash，field=nodeId，value=节点 JSON（parentId 构成树）；</li>
 *   <li>head:{sid}——String，当前所在节点（回答从这里继续，rewind 改的就是它）；</li>
 *   <li>branches:{sid}——Hash，branchName=fork 起点 nodeId（命名分支）。</li>
 * </ul>
 * 根节点是虚拟的 "root"，不落 Redis。第一条消息的 parentId="root"。</p>
 *
 * <p>为什么不用线性 List：线性历史只能"删掉后面几条"来回退，一旦回退就永久丢失
 * 那条思路；树模型保留所有分叉，rewind 只移动 head 指针，随时能切回来继续——
 * 这正是 pi-mono 这类 coding agent 的 rewind 语义。注意工具副作用（如调外部 API）
 * 不会随 rewind 回滚，这里只回滚对话上下文。</p>
 *
 * <p>TTL 规则与旧线性历史一致：ttlHours<=0 表示永久（用户明确要求历史永久保存）。</p>
 */
@Service
public class ConversationTreeStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationTreeStore.class);
    public static final String ROOT = "root";

    private static final String TREE = "water:rag:tree:";
    private static final String HEAD = "water:rag:head:";
    private static final String BRANCH = "water:rag:branches:";

    /**
     * Redis key 加租户前缀：同名 sessionId 在不同租户下是完全隔离的两棵树。
     * 从 UserContext 取租户，调用方无法传参切换——这是多租户隔离的关键落点。
     */
    private String key(String prefix, String sessionId) {
        return keyFor(prefix, UserContext.get().tenantId(), sessionId);
    }

    private String keyFor(String prefix, String tenant, String sessionId) {
        String t = tenant == null || tenant.isBlank() ? "default" : tenant;
        return prefix + t + ":" + sessionId;
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RagProperties props;

    public ConversationTreeStore(StringRedisTemplate redis, ObjectMapper mapper, RagProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 追加节点，返回新节点 ID。
     *
     * @param parentId 父节点；null/"root" 表示挂到根
     * @param branch   分支名（仅在该节点标记，可空）
     */
    public String append(String sessionId, String parentId, String role, String content,
                         List<Map<String, Object>> sources, String branch) {
        return append(sessionId, UserContext.get().tenantId(), parentId, role, content, sources, branch);
    }

    /**
     * 显式传租户的 append——给 Reactor 异步回调用。
     * ThreadLocal 在 SSE 流完成时已被 Filter 清除，异步线程读不到原租户，
     * 必须由同步阶段捕获身份快照传进来，否则消息会串到 default 租户。
     */
    public String append(String sessionId, String tenant, String parentId, String role, String content,
                         List<Map<String, Object>> sources, String branch) {
        String id = UUID.randomUUID().toString();
        String parent = parentId == null || parentId.isBlank() ? ROOT : parentId;
        // 防悬空节点：前端传来的 parentId 必须真实存在，否则拒收并回退到当前 head。
        // 不校验的话树会悄悄长出孤儿分支——节点还在 Redis 里但画不出来、检索不到。
        if (!ROOT.equals(parent) && get(sessionId, tenant, parent).isEmpty()) {
            log.warn("parentId={} 不存在，回退到当前 head", parent);
            String h = head(sessionId, tenant);
            parent = h == null ? ROOT : h;
        }
        TreeNode node = new TreeNode(id, parent, role, content, LocalDateTime.now().toString(),
                sources == null ? List.of() : sources, branch);
        try {
            redis.opsForHash().put(keyFor(TREE, tenant, sessionId), id, mapper.writeValueAsString(node));
            redis.opsForValue().set(keyFor(HEAD, tenant, sessionId), id);
            if (branch != null && !branch.isBlank()) {
                redis.opsForHash().put(keyFor(BRANCH, tenant, sessionId), branch, id);
            }
            refreshTtl(sessionId, tenant);
        } catch (Exception e) {
            log.warn("消息树写入失败（不影响本次回答）", e);
        }
        return id;
    }

    /** 当前 head（null 表示会话还没有消息） */
    public String head(String sessionId) {
        return head(sessionId, UserContext.get().tenantId());
    }

    public String head(String sessionId, String tenant) {
        return redis.opsForValue().get(keyFor(HEAD, tenant, sessionId));
    }

    /** rewind：把 head 移到指定节点；节点必须存在，否则拒绝（防止指针悬空）。 */
    public boolean rewind(String sessionId, String nodeId) {
        if (nodeId == null || !get(sessionId, nodeId).isPresent()) return false;
        redis.opsForValue().set(key(HEAD, sessionId), nodeId);
        refreshTtl(sessionId);
        return true;
    }

    /** 取单个节点 */
    public java.util.Optional<TreeNode> get(String sessionId, String nodeId) {
        return get(sessionId, UserContext.get().tenantId(), nodeId);
    }

    public java.util.Optional<TreeNode> get(String sessionId, String tenant, String nodeId) {
        Object raw = redis.opsForHash().get(keyFor(TREE, tenant, sessionId), nodeId);
        if (raw == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(mapper.readValue(raw.toString(), TreeNode.class));
        } catch (Exception e) {
            log.warn("节点反序列化失败：{}", nodeId, e);
            return java.util.Optional.empty();
        }
    }

    /**
     * 从某节点沿 parentId 回溯到根，再正序返回。
     * 这就是"当前分支的对话链"——Prompt 历史只取这条链，兄弟分支不会串进来。
     */
    public List<TreeNode> pathTo(String sessionId, String fromNodeId) {
        String cursor = fromNodeId == null ? head(sessionId) : fromNodeId;
        Deque<TreeNode> stack = new ArrayDeque<>();
        Set<Object> seen = new java.util.HashSet<>();
        while (cursor != null && !ROOT.equals(cursor) && seen.add(cursor)) {
            java.util.Optional<TreeNode> n = get(sessionId, cursor);
            if (n.isEmpty()) break;
            stack.push(n.get());
            cursor = n.get().parentId();
        }
        return new ArrayList<>(stack);
    }

    /** 当前 head 所在分支的完整消息链 */
    public List<TreeNode> path(String sessionId) {
        return pathTo(sessionId, null);
    }

    /** 取整棵树的所有节点（供前端画树） */
    public List<TreeNode> allNodes(String sessionId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(TREE, sessionId));
        List<TreeNode> out = new ArrayList<>(entries.size());
        for (Object raw : entries.values()) {
            try {
                out.add(mapper.readValue(raw.toString(), TreeNode.class));
            } catch (Exception e) {
                log.warn("节点反序列化失败，跳过", e);
            }
        }
        out.sort((a, b) -> a.ts().compareTo(b.ts()));
        return out;
    }

    /** 命名分支表：branchName → fork 起点 nodeId */
    public Map<String, String> branches(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(BRANCH, sessionId));
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    /** 命名分支：把某个节点标记为新分支的起点（真正的分叉在下一次 append 发生） */
    public boolean fork(String sessionId, String fromNodeId, String branchName) {
        if (branchName == null || branchName.isBlank()) return false;
        if (!get(sessionId, fromNodeId).isPresent()) return false;
        redis.opsForHash().put(key(BRANCH, sessionId), branchName, fromNodeId);
        refreshTtl(sessionId);
        return true;
    }

    /**
     * 拼 Prompt 历史：只取当前分支最近 maxTurns 轮（一轮=user+assistant 两条）。
     */
    public String recentAsText(String sessionId, int maxTurns) {
        List<TreeNode> path = path(sessionId);
        int from = Math.max(0, path.size() - maxTurns * 2);
        StringBuilder sb = new StringBuilder();
        for (TreeNode n : path.subList(from, path.size())) {
            sb.append("user".equals(n.role()) ? "用户" : "助手").append("：")
                    .append(n.content()).append('\n');
        }
        return sb.length() > 0 ? sb.toString() : "（无）";
    }

    /** 兼容旧接口：当前分支的线性消息列表 */
    public List<Map<String, String>> historyList(String sessionId) {
        List<Map<String, String>> out = new ArrayList<>();
        for (TreeNode n : path(sessionId)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", n.role());
            m.put("content", n.content());
            m.put("ts", n.ts());
            m.put("id", n.id());
            m.put("parentId", n.parentId());
            out.add(m);
        }
        return out;
    }

    /** 清空整个会话（树、head、分支一起删） */
    public void clear(String sessionId) {
        redis.delete(java.util.List.of(key(TREE, sessionId), key(HEAD, sessionId), key(BRANCH, sessionId)));
    }

    private void refreshTtl(String sessionId) {
        refreshTtl(sessionId, UserContext.get().tenantId());
    }

    private void refreshTtl(String sessionId, String tenant) {
        if (props.getHistoryTtlHours() > 0) {
            Duration ttl = Duration.ofHours(props.getHistoryTtlHours());
            redis.expire(keyFor(TREE, tenant, sessionId), ttl);
            redis.expire(keyFor(HEAD, tenant, sessionId), ttl);
            redis.expire(keyFor(BRANCH, tenant, sessionId), ttl);
        }
    }
}
