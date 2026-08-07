package com.westart.ai.westart.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.memory.entity.MemorySourceMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主题记忆来源消息Mapper，负责memory_source_message表的数据访问。
 */
@Mapper
public interface MemorySourceMessageMapper extends BaseMapper<MemorySourceMessage> {
}
