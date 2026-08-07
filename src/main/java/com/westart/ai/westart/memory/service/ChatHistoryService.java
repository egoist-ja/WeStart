package com.westart.ai.westart.memory.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.memory.dto.MessageDTO;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 聊天历史消息服务。
 *
 * <p>业务服务只负责提交消息，具体的Redis Stream写入由实现类处理。</p>
 */
public interface ChatHistoryService {

    /** 聊天消息角色常量：用户消息。 */
    String ROLE_USER = "USER";
    /** 聊天消息角色常量：AI回答。 */
    String ROLE_AI = "AI";

    /**
     * Redis Stream中的一条聊天历史消息。
     *
     * <p>recordId只用于消费确认，messageId才是业务消息的唯一标识。</p>
     */
    record StreamMessage(
            String recordId,
            String messageId,
            String memoryId,
            String role,
            String content,
            Instant createdAt) {

        /**
         * 转换为等待主题记忆分析的业务消息。
         * Redis消费确认字段不进入业务DTO，当前memoryId作为微信用户ID传递。
         *
         * @return 业务消息DTO
         */
        public MessageDTO toMessageDTO() {
            return new MessageDTO(messageId, memoryId, role, content, createdAt);
        }
    }

    /**
     * 将微信用户原始消息写入聊天历史Stream。
     *
     * @param memoryId 稳定聊天记忆ID
     * @param message 微信原始消息
     */
    void publishUserMessage(String memoryId, WeixinMessage message);

    /**
     * 将AI最终文本回答写入聊天历史Stream。
     *
     * @param memoryId 稳定聊天记忆ID
     * @param content AI最终文本回答
     */
    void publishAiMessage(String memoryId, String content);

    /**
     * 使用消费者组读取指定用户的一批尚未消费的聊天消息。
     *
     * <p>本方法只负责读取，不会确认消息。后续必须在记忆分析和数据库事务全部成功后，
     * 再调用{@link #acknowledgeUserMessages(String, List)}确认。</p>
     *
     * @param userId 微信用户ID
     * @return 按Redis Stream顺序排列的消息批次
     */
    List<StreamMessage> readUserMessageBatch(String userId);

    /**
     * 重新领取指定用户超过空闲时间且尚未确认的Pending消息。
     *
     * <p>达到最大投递次数的消息会先写入死信Stream，再确认原消息。</p>
     *
     * @param userId 微信用户ID
     * @return 本次需要重新处理的Pending消息
     */
    List<StreamMessage> readUserRetryMessageBatch(String userId);

    /**
     * 确认指定用户已经完成记忆分析和数据库事务的Stream消息。
     *
     * @param userId 微信用户ID
     * @param recordIds Redis Stream Record ID集合
     */
    void acknowledgeUserMessages(String userId, List<String> recordIds);

    /**
     * 查询已经创建聊天历史Stream的微信用户。
     *
     * <p>该索引用于应用重启后恢复每个用户独立的Stream消费者。</p>
     *
     * @return 已登记的微信用户ID
     */
    Set<String> findRegisteredHistoryUserIds();
}
