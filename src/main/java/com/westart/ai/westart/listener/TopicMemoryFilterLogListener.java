package com.westart.ai.westart.listener;

import static com.westart.ai.westart.service.ChatHistoryService.ROLE_USER;
import com.westart.ai.westart.DTO.TopicMemorySummaryDTO;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主题记忆过滤日志监听器，同时实现LangChain4j ChatModelListener和内部过滤日志收集。
 *
 * <p>每条消息的过滤、选择、总结结果先收集，批次结束时统一按消息聚合输出到
 * logs/topic-memory-filter.log：USER消息的完整链路在前，AI消息在后。
 * 首次出现的消息标记去重，重试跳过。</p>
 */
@Slf4j(topic = "TOPIC_MEMORY_FILTER_LOG")
@Component
public class TopicMemoryFilterLogListener implements ChatModelListener {

    private static final int PREVIEW_LIMIT = 300;
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<Map<String, Entry>> batch = new ThreadLocal<>();

    // ===== ChatModelListener =====

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 不记录
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        // 不记录
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        // 不记录
    }

    // ===== 批次生命周期 =====

    public void begin() { batch.set(new LinkedHashMap<>()); }
    public void end() { batch.remove(); }

    /** 检查消息是否已处理过，用于去重跳过。 */
    public boolean isDuplicate(String msgId) { return SEEN.contains(msgId); }
    /** 标记消息已处理（写入成功后调用）。 */
    public void markSeen(String msgId) { SEEN.add(msgId); }

    // ===== 各阶段数据收集 =====

    public void filter(String msgId, String role, String content, boolean passed, String reason) {
        entry(msgId, role, content).filterPassed = passed;
        entry(msgId, role, content).filterReason = passed ? null : reason;
    }

    public void stage1(String msgId, boolean selected, boolean batchNoUser) {
        Entry e = batch.get().get(msgId); if (e == null) return;
        e.stage1Selected = selected; e.stage1NoUser = batchNoUser;
    }

    public void stage2(String msgId, List<String> topicNames) {
        Entry e = batch.get().get(msgId); if (e == null) return;
        e.stage2Topics = topicNames;
    }

    // ===== 输出 =====

    /** 按USER先AI后输出过滤和第一阶段结果。 */
    public void flush() {
        for (Entry e : sortedEntries()) {
            if (!SEEN.add(e.msgId)) continue;
            if (e.filterPassed) {
                log.info("[本地过滤] 通过 messageId={} role={} 内容={}", e.msgId, e.role, preview(e.content));
            } else {
                log.info("[本地过滤] 拒绝 messageId={} role={} 原因={} 内容={}",
                        e.msgId, e.role, e.filterReason, preview(e.content));
                continue;
            }
            if (e.stage1Selected) {
                log.info("[第一阶段] 选中 messageId={} role={} 内容={}", e.msgId, e.role, preview(e.content));
            } else {
                String reason = e.stage1NoUser ? "选中结果不含USER消息" : "模型未选择";
                log.info("[第一阶段] 拒绝 messageId={} role={} 原因={} 内容={}",
                        e.msgId, e.role, reason, preview(e.content));
            }
        }
    }

    /** 输出第二阶段引用关系。 */
    public void flushStage2() {
        for (Entry e : sortedEntries()) {
            if (e.stage2Topics == null) continue;
            if (e.stage2Topics.isEmpty()) {
                log.info("[第二阶段] 未引用 messageId={} role={} 内容={}", e.msgId, e.role, preview(e.content));
            } else {
                log.info("[第二阶段] 已引用 messageId={} role={} → 主题={} 内容={}",
                        e.msgId, e.role, e.stage2Topics, preview(e.content));
            }
        }
    }

    /** 输出主题摘要。 */
    public void topics(List<TopicMemorySummaryDTO.TopicChunk> chunks) {
        if (chunks == null) return;
        for (TopicMemorySummaryDTO.TopicChunk chunk : chunks) {
            log.info("[第二阶段] 生成主题 主题名={} 分类={} 摘要={}",
                    chunk.topicName(), chunk.category(), chunk.topicSummary());
        }
    }

    // ===== 内部工具 =====

    private List<Entry> sortedEntries() {
        Map<String, Entry> map = batch.get(); if (map == null || map.isEmpty()) return List.of();
        List<Entry> sorted = new ArrayList<>(map.values());
        sorted.sort((a, b) -> {
            if (ROLE_USER.equals(a.role) && !ROLE_USER.equals(b.role)) return -1;
            if (!ROLE_USER.equals(a.role) && ROLE_USER.equals(b.role)) return 1;
            return 0;
        });
        return sorted;
    }

    private Entry entry(String msgId, String role, String content) {
        return batch.get().computeIfAbsent(msgId, id -> new Entry(id, role, content));
    }

    private static String preview(String content) {
        if (content == null || content.isBlank()) return "";
        String s = content.replace('\r', ' ').replace('\n', ' ');
        return s.length() <= PREVIEW_LIMIT ? s : s.substring(0, PREVIEW_LIMIT) + "…";
    }

    private static class Entry {
        final String msgId, role, content;
        boolean filterPassed; String filterReason;
        boolean stage1Selected; boolean stage1NoUser;
        List<String> stage2Topics;
        Entry(String msgId, String role, String content) { this.msgId = msgId; this.role = role; this.content = content; }
    }
}
