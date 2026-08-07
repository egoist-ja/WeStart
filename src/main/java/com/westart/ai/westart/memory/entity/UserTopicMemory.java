package com.westart.ai.westart.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.westart.ai.westart.memory.domain.TopicMemoryCategory;
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
public class UserTopicMemory {

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

    /** 主题记忆的领域分类。 */
    @TableField("category")
    private TopicMemoryCategory category;

    /**
     * 主题发生时间。
     */
    @TableField("topic_occurred_at")
    private Instant topicOccurredAt;
}
