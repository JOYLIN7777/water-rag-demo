package com.joylin.waterkb.web;

import com.joylin.waterkb.memory.RedisConversationStore;
import com.joylin.waterkb.rag.RagChatService;
import com.joylin.waterkb.tool.WaterInfoTool;
import com.joylin.waterkb.web.dto.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
public class ChatController {

    private final RagChatService ragChatService;
    private final RedisConversationStore historyStore;
    private final WaterInfoTool waterInfoTool;

    public ChatController(RagChatService ragChatService,
                          RedisConversationStore historyStore,
                          WaterInfoTool waterInfoTool) {
        this.ragChatService = ragChatService;
        this.historyStore = historyStore;
        this.waterInfoTool = waterInfoTool;
    }

    /**
     * RAG 问答（SSE 流式）。
     * 事件流：sources（引用片段）→ message（增量文本）→ done（结束）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody @Valid ChatRequest req) {
        return ragChatService.chatStream(req.sessionId(), req.question());
    }

    /**
     * 查看某会话的历史记录（Redis）。
     */
    @GetMapping("/history")
    public List<Map<String, String>> history(@RequestParam String sessionId) {
        return historyStore.getHistory(sessionId);
    }

    /**
     * 清空某会话的历史记录。
     */
    @DeleteMapping("/history")
    public Map<String, Object> clear(@RequestParam String sessionId) {
        historyStore.clear(sessionId);
        return Map.of("cleared", true, "sessionId", sessionId);
    }

    /**
     * 实时水情工具直调（不经过大模型，用于单独验证工具逻辑）。
     */
    @PostMapping("/tool/water")
    public String queryWater(@RequestParam String station) {
        return waterInfoTool.queryRealtimeWater(station);
    }
}
