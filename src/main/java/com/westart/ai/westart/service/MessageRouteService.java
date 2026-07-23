package com.westart.ai.westart.service;

import com.westart.ai.westart.DTO.MessageContent;
import com.westart.ai.westart.DTO.SegmentResult;
import dev.langchain4j.data.message.Content;

import java.util.List;

/**
 * 消息路由接口
 */
public interface MessageRouteService {

    /**
     * 对当前用户消息批次进行语义分块和任务分类。
     *
     * @param userId 微信用户ID
     * @param messages 带原始消息索引的批次内容
     * @return 有效路由片段
     */
    List<SegmentResult> classifyMessages(String userId, List<MessageContent> messages);

    /**
     * 按路由模型返回的索引提取语义片段内容。
     *
     * @param messages 当前批次的索引化消息
     * @param selectedIndexes 路由片段引用的消息索引
     * @return 不可变语义片段内容
     */
    List<Content> selectSegmentContents(
            List<MessageContent> messages,
            List<Integer> selectedIndexes);
}
