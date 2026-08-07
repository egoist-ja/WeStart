package com.westart.ai.westart.memory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 主题记忆来源消息MySQL实体，对应memory_source_message表。
 *
 * 只保存经过语义细筛并进入长期记忆链路的消息。
 */
@Getter
@Setter
@TableName("memory_source_message")
public class MemorySourceMessage {

    /** 微信用户唯一标识，与消息ID共同组成业务主键。 */
    @TableField("wechat_user_id")
    private String wechatUserId;

    /** 业务消息唯一标识。 */
    @TableField("message_id")
    private String messageId;

    /** 消息角色。 */
    @TableField("role")
    private String role;

    /** 消息文本内容。 */
    @TableField("content")
    private String content;

    /** 消息发生时间。 */
    @TableField("occurred_at")
    private Instant occurredAt;
}
