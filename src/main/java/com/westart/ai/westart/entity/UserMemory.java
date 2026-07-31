package com.westart.ai.westart.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 用户长期记忆实体，对应user_memory表。
 *
 * <p>数据库使用wechat_user_id和memory_key联合主键，实体不声明单列TableId。</p>
 */
@Data
@TableName("user_memory")
public class UserMemory {

    /**
     * 微信消息发送者的from_user_id。
     */
    @TableField("wechat_user_id")
    private String wechatUserId;

    /**
     * 由后端代码维护的长期记忆键。
     */
    @TableField("memory_key")
    private String memoryKey;

    /**
     * 用户画像内容。
     */
    private String content;

    /**
     * 本次画像更新对应的来源用户消息ID。
     */
    @TableField("source_message_id")
    private String sourceMessageId;

    /**
     * 画像内容更新次数，首次写入为1。
     */
    private Integer version;

    /**
     * 画像创建时间。
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * 画像最后更新时间。
     */
    @TableField("updated_at")
    private Instant updatedAt;
}
