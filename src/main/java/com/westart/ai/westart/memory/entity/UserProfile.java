package com.westart.ai.westart.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 用户长期画像实体，对应user_profile表。
 */
@Data
@TableName("user_profile")
public class UserProfile {

    /**
     * 微信消息发送者的from_user_id。
     */
    @TableId(value = "wechat_user_id", type = IdType.INPUT)
    private String wechatUserId;

    /**
     * 用户画像内容。
     */
    @TableField("profile_content")
    private String profileContent;

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
