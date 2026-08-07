package com.westart.ai.westart.wechat.repository;

import com.github.wechat.ilink.sdk.core.login.LoginContext;

import java.util.List;

/**
 * 微信登录状态仓储，负责持久化和恢复SDK登录上下文。
 */
public interface WeChatLoginStateRepository {

    /**
     * 新增或更新指定用户的登录状态。
     *
     * @param loginContext SDK登录上下文
     */
    void save(LoginContext loginContext);

    /**
     * 查询全部可恢复的登录上下文。
     *
     * @return 可恢复的登录上下文列表
     */
    List<LoginContext> findAll();

    /**
     * 删除指定用户的持久化登录状态。
     *
     * @param userId 微信用户唯一标识
     */
    void deleteByUserId(String userId);
}
