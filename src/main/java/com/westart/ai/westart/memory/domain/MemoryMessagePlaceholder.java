package com.westart.ai.westart.memory.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 长期记忆链路使用的非文本消息占位符。
 *
 * <p>聊天历史写入端使用占位文本表示不保存二进制内容的消息，过滤端通过同一组定义
 * 识别仅包含占位符的低价值消息。</p>
 */
public enum MemoryMessagePlaceholder {

    /** 图片消息占位符。 */
    IMAGE("[图片消息]"),

    /** 视频消息占位符。 */
    VIDEO("[视频消息]"),

    /** 当前不支持的微信消息占位符。 */
    UNSUPPORTED("[不支持的微信消息]");

    private static final Set<String> CONTENTS = Arrays.stream(values())
            .map(MemoryMessagePlaceholder::content)
            .collect(Collectors.toUnmodifiableSet());

    private final String content;

    MemoryMessagePlaceholder(String content) {
        this.content = content;
    }

    /**
     * 获取写入聊天历史的占位文本。
     *
     * @return 占位文本
     */
    public String content() {
        return content;
    }

    /**
     * 判断指定内容是否为记忆消息占位符。
     *
     * @param content 待判断的单行消息内容
     * @return 是占位符时返回{@code true}
     */
    public static boolean isPlaceholder(String content) {
        return CONTENTS.contains(content);
    }
}
