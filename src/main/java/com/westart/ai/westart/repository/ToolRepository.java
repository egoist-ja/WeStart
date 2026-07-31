package com.westart.ai.westart.repository;

import com.westart.ai.westart.entity.ToolEntity;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.List;

/**
 * RAG-MCP动态注入工具
 */
public interface ToolRepository {

    /**
     * 批量插入
     * @param toolEntities
     * @return
     */
    public InsertResp insertBatch(List<ToolEntity> toolEntities);

    /**
     * 混合搜索
     * @param query
     * @param maxResults
     * @return
     */
    SearchResp search(String query,int maxResults,float[] embedding);
}
