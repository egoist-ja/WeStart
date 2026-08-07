package com.westart.ai.westart.memory.dto;

import java.time.Instant;

/**
 * 等待长期记忆分析的业务消息。
 *
 * @param messageId 业务消息唯一标识
 * @param wechatUserId 消息所属微信用户ID
 * @param role 消息角色
 * @param content 消息内容
 * @param createdAt 消息创建时间
 */
public record MessageDTO(
        String messageId,
        String wechatUserId,
        String role,
        String content,
        Instant createdAt) {
}
