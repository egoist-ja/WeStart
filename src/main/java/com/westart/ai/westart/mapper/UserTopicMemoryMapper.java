package com.westart.ai.westart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.entity.UserTopicMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户主题记忆Mapper，负责user_topic_memory表的数据访问。
 */
@Mapper
public interface UserTopicMemoryMapper extends BaseMapper<UserTopicMemoryEntity> {
}
