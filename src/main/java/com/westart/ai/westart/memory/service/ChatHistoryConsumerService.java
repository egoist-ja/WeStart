package com.westart.ai.westart.memory.service;

/**
 * 聊天历史消费管理接口。
 *
 * <p>负责确保指定用户的聊天历史消费者持续运行。</p>
 */
public interface ChatHistoryConsumerService {

    /**
     * 启动指定用户的聊天历史消费。
     *
     * <p>重复调用不会创建新的消费者。</p>
     *
     * @param userId 微信用户ID（from_user_id）
     */
    void startConsuming(String userId);
}
