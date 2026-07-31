package com.westart.ai.westart.service;

/**
 * 聊天历史消费线程管理服务。
 *
 * <p>每个微信用户拥有独立的虚拟线程，负责从该用户的Redis Stream中
 * 批量读取消息、写入MySQL并触发用户画像分析。</p>
 */
public interface ChatHistoryThreadService {

    /**
     * 启动指定用户的聊天历史消费线程。
     *
     * <p>同一用户重复调用时不会创建新线程。线程在用户首次发消息时启动，
     * 持续消费该用户Redis Stream中的消息，攒批后写入MySQL并执行画像分析。</p>
     *
     * @param userId 微信用户ID（from_user_id）
     */
    void startUserProcessing(String userId);

    /**
     * 停止指定用户的聊天历史消费线程。
     *
     * @param userId 微信用户ID
     */
    void stopUserProcessing(String userId);

    /**
     * 获取当前活跃的消费线程数量。
     *
     * @return 活跃线程数
     */
    int getActiveUserCount();
}
