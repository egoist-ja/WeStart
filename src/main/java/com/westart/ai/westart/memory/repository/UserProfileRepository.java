package com.westart.ai.westart.memory.repository;

import com.westart.ai.westart.memory.entity.UserProfile;

/**
 * 用户自画像存储仓库
 */
public interface UserProfileRepository {

    /**
     * 插入或更新用户自画像
     * @param userProfile
     * @return
     */
    int upsert(UserProfile userProfile);

    /**
     * 根据用户的微信用户ID查询对应的用户自画像
     * @param userId
     * @return
     */
    UserProfile selectByUserId(String userId);
}
