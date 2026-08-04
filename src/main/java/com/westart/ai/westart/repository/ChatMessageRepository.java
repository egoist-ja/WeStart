package com.westart.ai.westart.repository;

import com.westart.ai.westart.DTO.ChatMemorySearchRequest;
import com.westart.ai.westart.entity.UserTopicMemory;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.List;

/**
 * 用户主题记忆向量仓储。
 */
public interface ChatMessageRepository {

    /**
     * 批量插入用户主题记忆。
     *
     * @param messages 用户主题记忆列表
     * @return Milvus插入结果
     */
    InsertResp insertBatch(List<UserTopicMemory> messages);

    /**
     * 混合搜索用户主题记忆。
     *
     * @param searchRequest 主题记忆搜索请求
     * @return Milvus搜索结果
     */
    SearchResp search(ChatMemorySearchRequest searchRequest);
}
