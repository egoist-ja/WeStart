package com.westart.ai.westart.DTO;

/**
 * 用户消息搜索请求参数
 * @param wechatUserId
 * @param query
 * @param maxResults
 */
public record ChatMemorySearchRequest(
        String wechatUserId, //用户ID
        String query, //查询语句
        int maxResults, //最大结果数量
        String expr, //过滤表达式
        float[] embedding //搜索向量
) { }
