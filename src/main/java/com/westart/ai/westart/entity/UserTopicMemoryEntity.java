package com.westart.ai.westart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户主题记忆MySQL实体，对应user_topic_memory表。
 *
 * <p>该实体只描述关系型数据库字段，不包含Milvus向量字段。</p>
 */
@Getter
@Setter
@TableName("user_topic_memory")
public class UserTopicMemoryEntity {

    /**
     * 主题记忆唯一标识，由MySQL生成。
     */
    @TableId(value = "topic_memory_id", type = IdType.AUTO)
    private Long topicMemoryId;

    /**
     * 微信用户唯一标识。
     */
    @TableField("wechat_user_id")
    private String wechatUserId;

    /**
     * 简短的主题名称。
     */
    @TableField("topic_name")
    private String topicName;

    /**
     * 用于检索的主题摘要。
     */
    @TableField("topic_summary")
    private String topicSummary;

    /**
     * 主题分类，如饮食/工作/技术/生活，由模型输出。
     */
    @TableField("category")
    private String category;

    /**
     * 主题发生时间。
     */
    @TableField("topic_occurred_at")
    private Instant topicOccurredAt;
}
