package com.westart.ai.westart.memory.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 主题记忆的Milvus索引状态。
 */
public enum TopicMemoryIndexStatus {

    /** 尚未成功写入Milvus。 */
    PENDING("PENDING"),

    /** 已经成功写入Milvus。 */
    DONE("DONE");

    /** MySQL持久化使用的状态值。 */
    @EnumValue
    private final String value;

    /**
     * 创建主题记忆索引状态。
     *
     * @param value MySQL持久化值
     */
    TopicMemoryIndexStatus(String value) {
        this.value = value;
    }

    /**
     * 获取MySQL持久化使用的状态值。
     *
     * @return 索引状态值
     */
    public String value() {
        return value;
    }
}
