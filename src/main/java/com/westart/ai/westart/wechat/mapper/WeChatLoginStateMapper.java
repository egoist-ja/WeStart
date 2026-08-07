package com.westart.ai.westart.wechat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.wechat.entity.WeChatLoginState;
import org.apache.ibatis.annotations.Mapper;

/**
 * 微信登录状态Mapper，负责wechat_login_state表的数据访问。
 */
@Mapper
public interface WeChatLoginStateMapper extends BaseMapper<WeChatLoginState> {
}
