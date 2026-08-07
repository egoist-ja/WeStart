package com.westart.ai.westart.memory.repository;

import com.westart.ai.westart.memory.dto.ChatMemorySearchRequest;
import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.List;

/**
 * 用户主题记忆向量仓储。
 */
public interface UserTopicMemoryVectorRepository {

    /**
     * 批量新增或覆盖用户主题记忆向量。
     *
     * @param memories 用户主题记忆列表
     * @return Milvus Upsert结果
     */
    UpsertResp upsertBatch(List<UserTopicMemoryVector> memories);

    /**
     * 混合搜索用户主题记忆。
     *
     * @param searchRequest 主题记忆搜索请求
     * @return Milvus搜索结果
     */
    SearchResp search(ChatMemorySearchRequest searchRequest);
}
