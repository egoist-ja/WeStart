package com.westart.ai.westart.memory.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 基于Redis的LangChain4j短期聊天记忆存储。
 *
 * <p>按聊天记忆ID保存序列化消息，并通过过期时间限制短期记忆生命周期。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemoryStore {

    private final RedisTemplate<String, String> redisTemplate;
    @Value("${westart.memory.redis-key-prefix}")
    private String redisKeyPrefix;
    @Value("${westart.memory.chat-ttl}")
    private Duration chatMemoryTtl;


    /**
     * 获取指定会话的聊天记忆。
     *
     * @param memoryId 聊天记忆ID
     * @return 聊天消息列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String messagesJson = redisTemplate.opsForValue().get(redisKey(memoryId));
        if (StringUtils.isBlank(messagesJson)) {
            log.debug("Redis短期记忆不存在或已经过期");
            return List.of();
        }
        List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(messagesJson);
        log.debug("Redis短期记忆读取成功，messageCount={}", messages.size());
        return messages;
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
            redisTemplate.delete(redisKey(memoryId));
            log.debug("Redis短期记忆为空，已删除对应Key");
            return;
        }
        String messagesJson = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(
                redisKey(memoryId),
                messagesJson,
                chatMemoryTtl);
        log.debug("Redis短期记忆更新成功，messageCount={}，ttl={}",
                messages.size(), chatMemoryTtl);
    }

    /**
     * 删除指定会话的聊天记忆。
     *
     * @param memoryId 聊天记忆ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(redisKey(memoryId));
        log.debug("Redis短期记忆删除成功");
    }

    /**
     * 生成带业务前缀的Redis短期记忆Key。
     *
     * @param memoryId 稳定聊天记忆ID
     * @return Redis短期记忆Key
     */
    private String redisKey(Object memoryId) {
        if (memoryId == null || StringUtils.isBlank(memoryId.toString())) {
            throw new IllegalArgumentException("memoryId不能为空");
        }
        return redisKeyPrefix + memoryId;
    }
}
