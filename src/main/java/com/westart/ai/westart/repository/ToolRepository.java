package com.westart.ai.westart.repository;

import com.westart.ai.westart.entity.ToolEntity;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.List;

/**
 * RAG-MCP动态注入工具
 */
public interface ToolRepository {

    /**
     * 批量新增或更新工具实体。
     *
     * @param toolEntities 工具实体列表
     * @return Milvus写入结果
     */
    UpsertResp upsertBatch(List<ToolEntity> toolEntities);

    /**
     * 删除不在有效主键列表中的工具实体。
     *
     * @param activeIds 当前有效的工具主键列表
     */
    void deleteInactiveTools(List<String> activeIds);

    /**
     * 混合搜索工具。
     *
     * @param query 查询文本
     * @param maxResults 最大结果数量
     * @param embedding 查询向量
     * @return Milvus搜索结果
     */
    SearchResp search(String query, int maxResults, float[] embedding);
}
