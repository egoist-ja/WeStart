package com.westart.ai.westart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 聊天历史消息实体，对应chat_message表。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /**
     * 消息唯一标识，用户消息使用微信消息ID，AI消息使用UUID。
     */
    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;

    /**
     * 微信消息发送者的from_user_id。
     */
    @TableField("wechat_user_id")
    private String wechatUserId;

    /**
     * 消息角色，当前取值为USER或AI。
     */
    private String role;

    /**
     * 完整消息内容。
     */
    private String content;

    /**
     * 消息产生时间。
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * 是否已经完成长期记忆分析。
     */
    @TableField("memory_processed")
    private Boolean memoryProcessed;
}
