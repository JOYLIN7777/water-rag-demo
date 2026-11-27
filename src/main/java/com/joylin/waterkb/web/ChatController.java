package com.joylin.waterkb.web;

import com.joylin.waterkb.memory.ChatSessionStore;
import com.joylin.waterkb.memory.ConversationTreeStore;
import com.joylin.waterkb.rag.RagChatService;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.tool.ToolPermissionRegistry;
import com.joylin.waterkb.tool.WaterInfoTool;
import com.joylin.waterkb.web.dto.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 问答与工具接口。
 *
 * <p>为什么用 SSE 而不是 WebSocket：问答是单向的服务器→客户端推送，
 * SSE 基于 HTTP、自带断线重连、兼容 Nginx 反代，比 WebSocket 轻量；
 * WebSocket 的双向通道对 LLM 问答场景是过剩设计。</p>
 */
@RestController
@RequestMapping("/api/chat")
@RequirePermission(Permission.CHAT_USE)
public class ChatController {

    private final RagChatService ragChatService;
    private final ConversationTreeStore treeStore;
    private final ChatSessionStore sessionStore;
    private final WaterInfoTool waterInfoTool;
    private final ToolPermissionRegistry toolPermissions;
    private final com.joylin.waterkb.model.ModelConfigRepository models;

    public ChatController(RagChatService ragChatService,
                          ConversationTreeStore treeStore,
                          ChatSessionStore sessionStore,
                          WaterInfoTool waterInfoTool,
                          ToolPermissionRegistry toolPermissions,
                          com.joylin.waterkb.model.ModelConfigRepository models) {
        this.ragChatService = ragChatService;
        this.treeStore = treeStore;
        this.sessionStore = sessionStore;
        this.waterInfoTool = waterInfoTool;
        this.toolPermissions = toolPermissions;
        this.models = models;
    }

    /**
     * RAG 问答（SSE 流式）。
     * parentId 为空则顺着当前 head 继续；传历史节点 ID 则从该点分叉。
     * 事件流：sources（引用片段）→ message（增量文本）→ done（结束）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody @Valid ChatRequest req) {
        // 全部 CHAT 模型被禁用时明确告诉用户，而不是让它变成一次「连接中断」网络错误
        return Flux.defer(() -> {
            com.joylin.waterkb.model.ModelConfig chosen = req.modelId() == null
                    ? models.findActive("CHAT") : models.findById(req.modelId());
            if (chosen == null || !"CHAT".equalsIgnoreCase(chosen.getType()) || !chosen.isEnabled()) {
                return Flux.just(ServerSentEvent.<String>builder().event("error")
                        .data("所选对话模型不可用（已禁用或未激活），请联系管理员在「模型设置」中启用。")
                        .build());
            }
            return ragChatService.chatStream(req.sessionId(), req.question(), req.parentId(), req.modelId());
        });
    }

    /**
     * 首页推荐问题：从当前账号最近的提问里去重整理，不是写死的示例。
     * 没历史时返回空列表，由前端回退到默认引导语。
     */
    /** 显式校验会话归属：sessionId 必须属于当前账号，避免只靠 Redis key 隐式隔离 */
    private void assertOwned(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        if (!sessionStore.exists(sessionId)) throw new IllegalArgumentException("会话不存在或不属于当前账号");
    }

    /** 推荐问题的切入词：覆盖知识库常见主题，按时间轮换，避免每次都是那几条 */
    private static final String[] SUGGEST_SEEDS = {
            "水库调度 汛限水位 防洪限制", "监测设备 布设 运维 预警", "闸门 启闭 操作规程 流量",
            "应急预案 响应 分级 处置", "水质 水生态 监测 评价", "工程 除险加固 设计 标准",
            "巡视检查 安全监测 巡检路线", "降雨 洪水 预报 调度方案"
    };

    /** 从片段真实内容里提炼一个短主题，再套多样问法，避免千篇一律的「主要包含哪些内容」 */
    private String questionFromChunk(String text, int variant) {
        String t = String.valueOf(text == null ? "" : text)
                .replaceAll("【片段[^】]*】", " ").replaceAll("\\s+", " ").trim();
        if (t.length() < 12) return "";
        // 取一个完整分句；全是数字/符号的片段（表格、参数行）直接丢弃
        String[] parts = t.split("(?<=[。；！？])");
        String clause = parts.length > 0 ? parts[0].trim() : t;
        clause = clause.replaceAll("^[0-9一二三四五六七八九十、.．\\s]+", "").trim();
        if (clause.length() > 34 || clause.replaceAll("[^\\u4e00-\\u9fa5]", "").length() < 8) return "";
        if (clause.length() < 6) return "";
        // 去掉句尾标点，按主题词长度择问法
        String core = clause.replaceAll("[。；！？,，、]$", "");
        String[] templates = {
                "关于「%s」，具体是怎么规定的？",
                "%s，需要满足哪些要求？",
                "实际工作中如何处理「%s」？",
                "「%s」涉及哪些关键要点？",
                "%s的依据是什么？"
        };
        return String.format(templates[variant % templates.length], core);
    }

    @GetMapping("/suggestions")
    public List<String> suggestions(@RequestParam(required = false, defaultValue = "4") int limit) {
        // 优先让模型基于知识库真实片段整理问题（缓存 10 分钟，句式多样）
        List<String> byModel = ragChatService.suggestQuestions(SUGGEST_SEEDS, Math.max(1, Math.min(limit, 8)));
        if (!byModel.isEmpty()) return byModel;

        // 模型不可用时回退规则：从真实片段提炼主题套不同问法，仍比只套标题好
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int base = (int) (System.currentTimeMillis() / 60000) % SUGGEST_SEEDS.length;
        int variant = (int) (System.currentTimeMillis() / 60000) % 5;
        for (int k = 0; k < 3 && out.size() < limit; k++) {
            String seed = SUGGEST_SEEDS[(base + k) % SUGGEST_SEEDS.length];
            for (org.springframework.ai.document.Document d : ragChatService.sampleChunks(seed, 3)) {
                String q = questionFromChunk(d.getText(), variant + out.size());
                if (q != null && q.length() >= 10 && q.length() <= 60) out.add(q);
                if (out.size() >= limit * 2) break;
            }
        }
        // 片段不足时回退到文档标题（仍带不同问法），保证首页不空
        if (out.size() < limit) {
            for (Map<String, Object> d : ragChatService.visibleDocuments()) {
                String name = String.valueOf(d.getOrDefault("fileName", "")).trim();
                if (name.isEmpty() || "null".equals(name)) continue;
                String baseName = name.replaceAll("\\.[^.]+$", "").replaceAll("[_\\-]", " ").trim();
                if (baseName.isEmpty()) continue;
                out.add(String.format("《%s》中有哪些关键要求？", baseName));
                if (out.size() >= limit) break;
            }
        }
        return new ArrayList<>(out).stream().limit(Math.max(1, Math.min(limit, 8))).toList();
    }

    /**
     * 查看当前分支的历史记录（线性链，兼容原有问答页）。
     */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String sessionId) {
        assertOwned(sessionId);
        return treeStore.historyList(sessionId);
    }

    /**
     * 查看完整消息树：所有节点 + 当前 head + 命名分支。
     * 前端据此画分支图，节点父子关系由 parentId 表达。
     */
    @GetMapping("/tree")
    public Map<String, Object> tree(@RequestParam String sessionId) {
        return Map.of(
                "sessionId", sessionId,
                "head", treeStore.head(sessionId) == null ? "" : treeStore.head(sessionId),
                "nodes", treeStore.allNodes(sessionId),
                "branches", treeStore.branches(sessionId));
    }

    /**
     * rewind：把当前 head 移到历史节点。不删除任何节点，旧分支随时可切回。
     */
    @PostMapping("/rewind")
    public ResponseEntity<Map<String, Object>> rewind(@RequestParam String sessionId,
                                                      @RequestParam String nodeId) {
        if (!treeStore.rewind(sessionId, nodeId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "节点不存在：" + nodeId));
        }
        return ResponseEntity.ok(Map.of("ok", true, "head", nodeId));
    }

    /**
     * fork：给某个历史节点起分支名（标记分叉起点）。
     * 真正的分叉发生在下一次带 parentId 的问答——新消息挂在该节点下，
     * 原分支不受影响。
     */
    @PostMapping("/fork")
    public ResponseEntity<Map<String, Object>> fork(@RequestParam String sessionId,
                                                    @RequestParam String nodeId,
                                                    @RequestParam String name) {
        if (!treeStore.fork(sessionId, nodeId, name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "节点不存在或分支名为空"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "nodeId", nodeId, "name", name));
    }

    /**
     * 清空某会话的整棵树（所有分支一起删）。
     */
    @DeleteMapping("/history")
    public Map<String, Object> clear(@RequestParam String sessionId) {
        treeStore.clear(sessionId);
        return Map.of("cleared", true, "sessionId", sessionId);
    }

    // ==================== 会话清单（永久存储） ====================
    // 侧栏的会话列表以前只存在浏览器 localStorage，换设备/清缓存就全没了。
    // 现在清单落 PostgreSQL，Redis 只负责消息内容：清缓存后清单还在、点开还能读回历史。

    /**
     * 首屏引导：只返回会话清单。消息由 /api/chat/history 按需拉当前会话，
     * 不再一次把全部会话的历史灌进前端（实测 17 会话 × 30 条 = 248KB，
     * 其中 16 个会话的消息根本用不到，还和随后的 history 请求重复）。
     */
    @GetMapping("/bootstrap")
    public List<Map<String, Object>> bootstrap() {
        return sessionStore.list();
    }

    /**
     * 提问框可选模型：所有有 chat:use 的角色都能看，内容由管理员在模型设置里配置。
     * 只返回 id/名称/模型标识，绝不带 base-url 与 api-key 等敏感信息。
     */
    @GetMapping("/models")
    public java.util.List<java.util.Map<String, Object>> chatModels() {
        java.util.List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (var c : models.findAll()) {
            if (!"CHAT".equalsIgnoreCase(c.getType()) || !c.isActive() || !c.isEnabled()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("model", c.getModel());
            m.put("active", c.isActive());
            m.put("isDefault", c.isDefault());
            out.add(m);
        }
        return out;
    }

    /** 当前账号的全部会话，按最近更新倒序 */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return sessionStore.list();
    }

    /** 新建会话（幂等：同一个 sessionId 重复提交只刷新时间） */
    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.getOrDefault("sessionId", "")).trim();
        if (id.isEmpty()) throw new IllegalArgumentException("sessionId 不能为空");
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title"));
        sessionStore.upsert(id, title);
        return Map.of("ok", true, "sessionId", id);
    }

    /** 刷新会话：补标题（仅在原标题为空时生效）并更新时间戳 */
    @PutMapping("/sessions/{sessionId}")
    public Map<String, Object> touchSession(@PathVariable String sessionId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        Object title = body == null ? null : body.get("title");
        if (title == null || String.valueOf(title).isBlank()) {
            sessionStore.upsert(sessionId, "");
        } else {
            sessionStore.rename(sessionId, String.valueOf(title));
        }
        return Map.of("ok", true, "sessionId", sessionId);
    }

    /** 删除会话：清单行与消息树一起删，避免"列表没了，内容还占着 Redis" */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        treeStore.clear(sessionId);
        boolean removed = sessionStore.delete(sessionId);
        return Map.of("ok", true, "sessionId", sessionId, "removed", removed);
    }

    /**
     * 实时水情工具直调（不经过大模型，用于单独验证工具权限与逻辑）。
     * 越权时返回 JSON 错误而非水情数据。
     */
    @PostMapping("/tool/water")
    public String queryWater(@RequestParam String station) {
        return waterInfoTool.queryRealtimeWater(station);
    }

    /** 当前身份可见的工具清单及是否有权调用（作品集演示工具权限治理） */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return toolPermissions.listForCurrentUser();
    }
}
