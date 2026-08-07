package com.westart.ai.westart.memory.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.westart.ai.westart.memory.entity.UserProfile;
import com.westart.ai.westart.memory.mapper.UserProfileMapper;
import com.westart.ai.westart.memory.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户画像MySQL仓储实现。
 *
 * <p>封装用户画像的原子写入和按微信用户ID查询。</p>
 */
@Repository
@RequiredArgsConstructor
public class UserProfileRepositoryImpl implements UserProfileRepository {

    private final UserProfileMapper userProfileMapper;

    /**
     * 通过MySQL原生UPSERT完成新增或更新，避免先查询再写入产生额外数据库访问。
     */
    @Override
    public int upsert(UserProfile userProfile) {
        if (userProfile == null) {
            return 0;
        }
        String wechatUserId = userProfile.getWechatUserId();
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("微信用户ID不能为空");
        }
        return userProfileMapper.upsert(userProfile);
    }

    @Override
    public UserProfile selectByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }

        LambdaQueryWrapper<UserProfile> queryWrapper =
                new LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getWechatUserId, userId);
        return userProfileMapper.selectOne(queryWrapper);
    }
}
