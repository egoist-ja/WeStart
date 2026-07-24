package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.DTO.ILinkClientSession;
import com.westart.ai.westart.DTO.LoginSessionResult;
import com.westart.ai.westart.config.ILinkClientFactory;
import com.westart.ai.westart.service.UserThreadService;
import com.westart.ai.westart.service.WeChatLoginService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 微信客户端登录服务实现，负责多客户端会话的创建、扫码登录和退出编排。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeChatLoginServiceImpl implements WeChatLoginService {

    private final ILinkClientFactory iLinkClientFactory;
    private final ILinkClientSessionRegistry sessionRegistry;
    private final UserThreadService userThreadService;

    /**
     * 创建独立的iLink客户端会话并发起扫码登录。
     *
     * @return 登录会话标识及二维码内容
     */
    @Override
    public LoginSessionResult createLogin() {
        String sessionId = UUID.randomUUID().toString();
        ILinkClient client = iLinkClientFactory.createClient(sessionId);
        ILinkClientSession session = new ILinkClientSession(sessionId, client);
        boolean registered = false;

        try {
            sessionRegistry.register(session);
            registered = true;

            String qrCodeContent = client.executeLogin();
            if (StringUtils.isBlank(qrCodeContent)) {
                throw new IllegalStateException("微信扫码登录二维码内容为空");
            }
            client.getLoginFuture()
                    .whenComplete((loginContext, throwable) ->
                            completeLogin(session, loginContext, throwable));
            return new LoginSessionResult(sessionId, qrCodeContent);
        } catch (RuntimeException exception) {
            closeFailedSession(session, registered, exception);
            throw new IllegalStateException(
                    "微信扫码登录会话创建失败，sessionId=" + sessionId,
                    exception);
        }
    }

    /**
     * 获取指定iLink客户端会话的登录状态。
     *
     * @param sessionId 登录会话唯一标识
     * @return 登录状态
     */
    @Override
    public LoginStatus getLoginStatus(String sessionId) {
        return sessionRegistry.getRequired(sessionId)
                .client()
                .getLoginStatus();
    }

    /**
     * 停止消息处理任务并关闭指定iLink客户端会话。
     *
     * @param sessionId 登录会话唯一标识
     */
    @Override
    public void logout(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }

        userThreadService.stopSession(sessionId);
        if (!sessionRegistry.closeAndRemove(sessionId)) {
            log.info("iLink客户端会话不存在，无需重复退出，sessionId={}", sessionId);
        }
    }

    /**
     * 处理扫码登录结果，成功时启动会话消息线程，失败时释放会话资源。
     *
     * @param session 当前登录会话
     * @param loginContext SDK登录上下文
     * @param throwable 登录异常
     */
    private void completeLogin(
            ILinkClientSession session,
            LoginContext loginContext,
            Throwable throwable) {
        String sessionId = session.sessionId();
        if (throwable != null) {
            log.error("微信扫码登录失败，sessionId={}", sessionId, throwable);
            sessionRegistry.closeAndRemove(sessionId);
            return;
        }

        boolean sessionAvailable = sessionRegistry.find(sessionId)
                .filter(registeredSession -> registeredSession == session)
                .isPresent();
        if (!sessionAvailable) {
            log.info("微信扫码登录完成时会话已经关闭，sessionId={}", sessionId);
            return;
        }
        if (loginContext == null) {
            log.error("微信扫码登录成功但登录上下文为空，sessionId={}", sessionId);
            sessionRegistry.closeAndRemove(sessionId);
            return;
        }

        userThreadService.startSession(sessionId);
        log.info(
                "微信扫码登录成功，sessionId={}，userId={}，botId={}",
                sessionId,
                loginContext.getUserId(),
                loginContext.getBotId());
    }

    /**
     * 创建登录会话失败时释放已创建的客户端资源。
     *
     * @param session 创建失败的会话
     * @param registered 会话是否已经注册
     * @param originalException 原始异常
     */
    private void closeFailedSession(
            ILinkClientSession session,
            boolean registered,
            RuntimeException originalException) {
        try {
            if (registered) {
                sessionRegistry.closeAndRemove(session.sessionId());
            } else {
                session.client().close();
            }
        } catch (RuntimeException closeException) {
            originalException.addSuppressed(closeException);
            log.error(
                    "清理登录失败的iLink客户端会话异常，sessionId={}",
                    session.sessionId(),
                    closeException);
        }
    }
}
