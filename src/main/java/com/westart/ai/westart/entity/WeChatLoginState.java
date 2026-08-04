package com.westart.ai.westart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 微信登录状态实体，对应wechat_login_state表，用于应用重启后恢复用户登录状态。
 */
@Getter
@Setter
@TableName("wechat_login_state")
public class WeChatLoginState {

    /**
     * 微信用户唯一标识，作为登录状态主键，用于区分并恢复不同用户的客户端会话。
     */
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    /**
     * 微信机器人唯一标识，用于恢复登录上下文并确定消息所属的机器人实例。
     */
    @TableField("bot_id")
    private String botId;

    /**
     * 加密后的机器人访问令牌，用于应用重启后重新建立已授权的iLink客户端。
     */
    @TableField("bot_token_ciphertext")
    private String botTokenCiphertext;

    /**
     * iLink业务接口基础地址，用于恢复客户端后向原登录服务地址发送请求。
     */
    @TableField("base_url")
    private String baseUrl;

    /**
     * 登录状态最后更新时间，用于判断持久化记录的新旧并辅助排查恢复问题。
     */
    @TableField("updated_at")
    private Instant updatedAt;
}
