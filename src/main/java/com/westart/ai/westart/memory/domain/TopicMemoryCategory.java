package com.westart.ai.westart.memory.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.Arrays;

/**
 * 用户主题记忆分类。
 *
 * <p>模型返回未知或空分类时统一归入{@link #OTHER}，保证持久化分类始终有效。</p>
 */
public enum TopicMemoryCategory {

    /** 饮食偏好及饮食习惯。 */
    DIET("饮食"),

    /** 工作相关主题。 */
    WORK("工作"),

    /** 技术相关主题。 */
    TECHNOLOGY("技术"),

    /** 日常生活相关主题。 */
    LIFE("生活"),

    /** 健康相关主题。 */
    HEALTH("健康"),

    /** 娱乐相关主题。 */
    ENTERTAINMENT("娱乐"),

    /** 无法归入其他分类的主题。 */
    OTHER("其他");

    /** MySQL持久化及模型交互使用的中文分类值。 */
    @EnumValue
    private final String value;

    TopicMemoryCategory(String value) {
        this.value = value;
    }

    /**
     * 获取用于模型交互和持久化的分类值。
     *
     * @return 中文分类值
     */
    public String value() {
        return value;
    }

    /**
     * 根据模型返回值解析主题分类。
     *
     * @param value 模型返回的分类值
     * @return 对应主题分类；值为空或未知时返回{@link #OTHER}
     */
    public static TopicMemoryCategory fromValue(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalizedValue = value.trim();
        return Arrays.stream(values())
                .filter(category -> category.value.equals(normalizedValue))
                .findFirst()
                .orElse(OTHER);
    }
}
