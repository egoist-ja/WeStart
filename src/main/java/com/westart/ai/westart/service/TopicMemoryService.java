package com.westart.ai.westart.service;

import com.westart.ai.westart.DTO.MessageDTO;
import com.westart.ai.westart.DTO.TopicMemorySummaryDTO;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 用户主题记忆业务流程服务。
 *
 * <p>业务调用方只依赖该接口，不直接依赖模型、过滤器或持久化实现。</p>
 */
public interface TopicMemoryService {

    /**
     * 本地粗过滤后调用第一阶段模型，并按输入顺序返回通过校验的消息ID。
     *
     * @param segments TopicMemoryMessageAdapter转换后的文本片段
     * @return 当前批次中具有主题记忆价值的消息ID
     */
    List<String> selectTopicMessageIds(List<TextSegment> segments);

    /**
     * 根据第一阶段模型选中的消息ID，生成按语义主题分块的总结JSON。
     *
     * @param messages 当前批次的原始业务消息
     * @param selectedMessageIds 第一阶段模型筛选并校验后的消息ID
     * @return 第二阶段模型生成的语义分块JSON
     */
    String summarizeTopic(List<MessageDTO> messages, List<String> selectedMessageIds);

    /**
     * 解析并校验第二阶段模型生成的语义分块JSON。
     *
     * @param summaryJson 第二阶段模型返回的原始JSON
     * @param messages 当前批次的原始业务消息
     * @param selectedMessageIds 第一阶段模型筛选并校验后的消息ID
     * @return 完成结构和业务校验的语义分块DTO
     */
    TopicMemorySummaryDTO validateTopicSummary(
            String summaryJson,
            List<MessageDTO> messages,
            List<String> selectedMessageIds);

    /**
     * 将通过校验的语义分块DTO与来源消息字段合并为最终主题记忆JSON。
     *
     * @param validatedSummary validateTopicSummary返回的合法DTO
     * @param messages 当前批次的原始业务消息
     * @param selectedMessageIds 第一阶段模型筛选并校验后的消息ID
     * @return 可交给MySQL和Milvus写入模块的最终主题记忆JSON
     */
    String assembleTopicMemoryJson(
            TopicMemorySummaryDTO validatedSummary,
            List<MessageDTO> messages,
            List<String> selectedMessageIds);
}
