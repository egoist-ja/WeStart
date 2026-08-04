package com.westart.ai.westart.repository.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.westart.ai.westart.entity.WeChatLoginState;
import com.westart.ai.westart.infra.WeChatLoginTokenCipher;
import com.westart.ai.westart.mapper.WeChatLoginStateMapper;
import com.westart.ai.westart.repository.WeChatLoginStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 微信登录状态仓储实现，封装MySQL访问、实体转换和登录令牌加解密逻辑。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WeChatLoginStateRepositoryImpl implements WeChatLoginStateRepository {

    private final WeChatLoginStateMapper loginStateMapper;
    private final WeChatLoginTokenCipher tokenCipher;

    /**
     * 新增或更新指定用户的登录状态。
     *
     * @param loginContext SDK登录上下文
     */
    @Override
    public void save(LoginContext loginContext) {
        Objects.requireNonNull(loginContext, "loginContext不能为空");
        validateLoginContext(loginContext);

        WeChatLoginState loginState = new WeChatLoginState();
        loginState.setUserId(loginContext.getUserId());
        loginState.setBotId(loginContext.getBotId());
        loginState.setBotTokenCiphertext(tokenCipher.encrypt(loginContext.getBotToken()));
        loginState.setBaseUrl(loginContext.getBaseUrl());
        loginState.setUpdatedAt(Instant.now());

        int affectedRows = updateLoginState(loginState);
        if (affectedRows == 0) {
            try {
                affectedRows = loginStateMapper.insert(loginState);
            } catch (DuplicateKeyException ignored) {
                affectedRows = updateLoginState(loginState);
            }
        }
        if (affectedRows <= 0) {
            throw new IllegalStateException(
                    "微信登录状态保存失败，userId=" + loginContext.getUserId());
        }
    }

    /**
     * 查询全部可恢复的登录上下文。
     *
     * @return 可恢复的登录上下文列表
     */
    @Override
    public List<LoginContext> findAll() {
        List<WeChatLoginState> loginStates = loginStateMapper.selectList(
                Wrappers.lambdaQuery());
        if (loginStates.isEmpty()) {
            return List.of();
        }

        List<LoginContext> loginContexts = new ArrayList<>(loginStates.size());
        for (WeChatLoginState loginState : loginStates) {
            try {
                loginContexts.add(toLoginContext(loginState));
            } catch (RuntimeException exception) {
                log.error(
                        "微信登录状态解析失败，跳过当前记录，userId={}",
                        loginState.getUserId(),
                        exception);
            }
        }
        return loginContexts;
    }

    /**
     * 删除指定用户的持久化登录状态。
     *
     * @param userId 微信用户唯一标识
     */
    @Override
    public void deleteByUserId(String userId) {
        requireNotBlank(userId, "userId");
        loginStateMapper.delete(
                Wrappers.<WeChatLoginState>lambdaQuery()
                        .eq(WeChatLoginState::getUserId, userId));
    }

    /**
     * 将持久化实体转换为SDK登录上下文。
     *
     * @param loginState 微信登录状态实体
     * @return SDK登录上下文
     */
    private LoginContext toLoginContext(WeChatLoginState loginState) {
        requireNotBlank(loginState.getUserId(), "userId");
        requireNotBlank(loginState.getBotId(), "botId");
        requireNotBlank(loginState.getBotTokenCiphertext(), "botTokenCiphertext");
        requireNotBlank(loginState.getBaseUrl(), "baseUrl");
        return new LoginContext(
                tokenCipher.decrypt(loginState.getBotTokenCiphertext()),
                loginState.getUserId(),
                loginState.getBotId(),
                loginState.getBaseUrl());
    }

    /**
     * 根据用户标识更新已有的微信登录状态。
     *
     * @param loginState 微信登录状态实体
     * @return 受影响行数
     */
    private int updateLoginState(WeChatLoginState loginState) {
        return loginStateMapper.update(
                loginState,
                Wrappers.<WeChatLoginState>lambdaUpdate()
                        .eq(WeChatLoginState::getUserId, loginState.getUserId()));
    }

    /**
     * 校验SDK登录上下文中的持久化必需字段。
     *
     * @param loginContext SDK登录上下文
     */
    private void validateLoginContext(LoginContext loginContext) {
        requireNotBlank(loginContext.getUserId(), "userId");
        requireNotBlank(loginContext.getBotId(), "botId");
        requireNotBlank(loginContext.getBotToken(), "botToken");
        requireNotBlank(loginContext.getBaseUrl(), "baseUrl");
    }

    /**
     * 校验字符串字段不为空。
     *
     * @param value 字段值
     * @param fieldName 字段名称
     */
    private void requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }
}
