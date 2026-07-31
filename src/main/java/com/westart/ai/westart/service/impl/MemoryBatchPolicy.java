package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.service.ChatHistoryService;
import dev.langchain4j.model.TokenCountEstimator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 用户聊天历史批量处理策略。
 *
 * <p>消息数量、内容Token数、最早消息等待时间或用户空闲时间达到任一阈值时，
 * 允许执行一次长期记忆分析。</p>
 */
@Component
@RequiredArgsConstructor
public class MemoryBatchPolicy {

    /**
     * 与短期记忆保持一致的Token估算器。
     */
    private final TokenCountEstimator tokenCountEstimator;

    /**
     * 单次长期记忆分析的消息数量阈值。
     */
    @Value("${westart.memory.history-batch-size}")
    private int batchSize;

    /**
     * 单次长期记忆分析的消息内容Token阈值。
     */
    @Value("${westart.memory.history-token-threshold}")
    private int tokenThreshold;

    /**
     * 本批第一条消息允许等待的最长时间。
     */
    @Value("${westart.memory.history-max-wait}")
    private Duration maxWait;

    /**
     * 最近一条消息到达后判定用户空闲的时间。
     */
    @Value("${westart.memory.history-idle-timeout}")
    private Duration idleTimeout;

    /**
     * 校验批量处理配置。
     */
    @PostConstruct
    public void validateConfiguration() {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("聊天历史批量消息数必须大于0");
        }
        if (tokenThreshold <= 0) {
            throw new IllegalArgumentException("聊天历史批量Token阈值必须大于0");
        }
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("聊天历史最长等待时间必须大于0");
        }
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("聊天历史用户空闲时间必须大于0");
        }
    }

    /**
     * 判断当前缓存消息是否应当进入长期记忆处理。
     *
     * @param messages 当前用户等待处理的消息
     * @param firstReceivedAt 本批第一条消息被消费者读取的时间
     * @param lastReceivedAt 本批最近一条消息被消费者读取的时间
     * @param now 当前时间
     * @return 达到任一批量条件时返回true
     */
    public boolean shouldProcess(
            List<ChatHistoryService.StreamMessage> messages,
            Instant firstReceivedAt,
            Instant lastReceivedAt,
            Instant now) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (messages.size() >= batchSize) {
            return true;
        }
        int tokenCount = messages.stream()
                .map(ChatHistoryService.StreamMessage::content)
                .mapToInt(tokenCountEstimator::estimateTokenCountInText)
                .sum();
        if (tokenCount >= tokenThreshold) {
            return true;
        }
        return hasElapsed(firstReceivedAt, now, maxWait)
                || hasElapsed(lastReceivedAt, now, idleTimeout);
    }

    private boolean hasElapsed(Instant start, Instant end, Duration threshold) {
        return start != null
                && end != null
                && !end.isBefore(start)
                && Duration.between(start, end).compareTo(threshold) >= 0;
    }
}
