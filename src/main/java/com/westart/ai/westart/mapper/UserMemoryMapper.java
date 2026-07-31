package com.westart.ai.westart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户长期记忆Mapper，负责user_memory表的数据访问。
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {
}
