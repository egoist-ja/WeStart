package com.westart.ai.westart.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.memory.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户长期记忆Mapper，负责user_profile表的数据访问。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 根据wechat_user_id原子地新增或更新用户画像。
     *
     * @param userProfile 待保存的用户画像
     * @return MySQL报告的受影响行数
     */
    int upsert(@Param("userProfile") UserProfile userProfile);
}
