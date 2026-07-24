package com.westart.ai.westart.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 用redis实现ChatMemory
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemoryStore {

    private static final Duration CHAT_MEMORY_TTL = Duration.ofHours(3L);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 获取指定会话的聊天记忆。
     *
     * @param memoryId 聊天记忆ID
     * @return 聊天消息列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String messagesJson =
                redisTemplate.opsForValue().get(memoryId.toString());
        if (StringUtils.isBlank(messagesJson)) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(messagesJson);
    }

    /**
     * 更新指定会话的聊天记忆。
     *
     * @param memoryId 聊天记忆ID
     * @param messages 当前聊天记忆中的完整消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            log.warn("消息记忆为空");
            return;
        }
        String messagesJson = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(
                memoryId.toString(),
                messagesJson,
                CHAT_MEMORY_TTL);
    }

    /**
     * 删除指定会话的聊天记忆。
     *
     * @param memoryId 聊天记忆ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(memoryId.toString());
    }
}
