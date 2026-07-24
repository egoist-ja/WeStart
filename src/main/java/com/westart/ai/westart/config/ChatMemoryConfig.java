package com.westart.ai.westart.config;

import com.alibaba.dashscope.tokenizers.QwenTokenizer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import io.micrometer.common.util.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LangChain4j聊天记忆配置。
 */
@Configuration(proxyBeanMethods = false)
public class ChatMemoryConfig {

    private static final int MAX_MEMORY_TOKENS = 800_000;

    /**
     * Qwen本地分词器，用于估算聊天记忆占用的Token数量。
     */
    private final QwenTokenizer qwenTokenizer = new QwenTokenizer();

    /**
     * 创建使用Redis存储的Token窗口聊天记忆。
     *
     * @param redisChatMemory Redis聊天记忆存储
     * @param tokenCountEstimator Token数量估算器
     * @return Token窗口聊天记忆
     */
    @Bean
    public ChatMemoryProvider redisChatMemoryProvider(
            RedisChatMemory redisChatMemory,
            TokenCountEstimator tokenCountEstimator) {
        return memoryId -> TokenWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(redisChatMemory)
                .maxTokens(MAX_MEMORY_TOKENS, tokenCountEstimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    /**
     * 创建基于Qwen本地分词器的Token数量估算器。
     *
     * @return Token数量估算器
     */
    @Bean
    public TokenCountEstimator tokenCountEstimator() {
        return new TokenCountEstimator() {

            /**
             * 估算文本的Token数量。
             *
             * @param text 待估算文本
             * @return Token数量
             */
            @Override
            public int estimateTokenCountInText(String text) {
                if (StringUtils.isBlank(text)) {
                    return 0;
                }
                return qwenTokenizer.encodeOrdinary(text).size();
            }

            /**
             * 估算单条聊天消息的Token数量。
             *
             * @param message 待估算聊天消息
             * @return Token数量
             */
            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                Objects.requireNonNull(message, "message不能为空");
                return estimateTokenCountInText(
                        ChatMessageSerializer.messageToJson(message));
            }

            /**
             * 估算多条聊天消息的Token总数。
             *
             * @param messages 待估算聊天消息
             * @return Token总数
             */
            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                if (messages == null) {
                    return 0;
                }

                List<ChatMessage> messageList = new ArrayList<>();
                for (ChatMessage message : messages) {
                    messageList.add(Objects.requireNonNull(message, "message不能为空"));
                }
                if (messageList.isEmpty()) {
                    return 0;
                }
                return estimateTokenCountInText(
                        ChatMessageSerializer.messagesToJson(messageList));
            }
        };
    }
}
