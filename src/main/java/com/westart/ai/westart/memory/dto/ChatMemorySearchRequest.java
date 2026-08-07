package com.westart.ai.westart.memory.dto;

/**
 * 用户主题记忆向量搜索请求。
 *
 * @param wechatUserId 微信用户ID
 * @param query 原始检索文本
 * @param maxResults 最大返回数量
 * @param expr Milvus标量过滤表达式
 * @param embedding 检索文本对应的稠密向量
 */
public record ChatMemorySearchRequest(
        String wechatUserId,
        String query,
        int maxResults,
        String expr,
        float[] embedding
) { }
