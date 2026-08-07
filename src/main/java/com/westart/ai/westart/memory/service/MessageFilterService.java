package com.westart.ai.westart.memory.service;

import com.westart.ai.westart.memory.dto.MessageDTO;

import java.util.List;

/**
 * 记忆消息过滤接口。
 */
public interface MessageFilterService {

    /**
     * 使用本地规则过滤确定无长期记忆价值的消息。
     *
     * @param messages 待过滤消息
     * @return 保持原始顺序的粗筛结果
     */
    List<MessageDTO> looseFilter(List<MessageDTO> messages);

    /**
     * 使用模型过滤语义上没有长期记忆价值的消息。
     *
     * @param messages 本地粗筛后的消息
     * @return 保持原始顺序的细筛结果
     */
    List<MessageDTO> strictFilter(List<MessageDTO> messages);
}
