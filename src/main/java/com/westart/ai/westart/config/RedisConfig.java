package com.westart.ai.westart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis客户端配置。
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * 配置聊天记忆使用的字符串RedisTemplate。
     *
     * <p>聊天消息的JSON编解码由LangChain4j专用序列化器负责，
     * RedisTemplate仅负责字符串的读写。</p>
     *
     * @param connectionFactory Redis连接工厂
     * @return 聊天记忆RedisTemplate
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisSerializer<String> stringSerializer = RedisSerializer.string();

        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);
        redisTemplate.setValueSerializer(stringSerializer);
        redisTemplate.setHashValueSerializer(stringSerializer);
        return redisTemplate;
    }
}
