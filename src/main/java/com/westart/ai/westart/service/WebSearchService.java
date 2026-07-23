package com.westart.ai.westart.service;

public interface WebSearchService {

    /**
     * 查询互联网实时信息。
     *
     * @param query 用户需要搜索的问题或关键词
     * @return 经过整理、适合交给大模型回答的搜索材料
     */
    String searchWeb(String query);

}
