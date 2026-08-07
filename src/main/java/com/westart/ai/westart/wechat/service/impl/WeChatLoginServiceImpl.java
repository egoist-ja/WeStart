package com.westart.ai.westart.wechat.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ConnectFailedException;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.wechat.dto.ILinkClientSession;
import com.westart.ai.westart.wechat.dto.LoginSessionResult;
import com.westart.ai.westart.wechat.infra.ILinkClientFactory;
import com.westart.ai.westart.wechat.repository.WeChatLoginStateRepository;
import com.westart.ai.westart.wechat.service.UserThreadService;
import com.westart.ai.westart.wechat.service.WeChatLoginService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.UUID;

/**
 * 微信客户端登录服务实现，负责多客户端会话的创建、扫码登录和退出编排。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeChatLoginServiceImpl implements WeChatLoginService {

    private static final String QR_CODE_EXPIRED_MESSAGE = "qrcode expired";
    private static final String LOGIN_CANCELLED_MESSAGE = "login cancelled";
    private static final String LOGIN_TIMEOUT_MESSAGE = "login timeout";
    private static final String HTTP_UNAUTHORIZED_CODE = "code=401";
    private static final String HTTP_FORBIDDEN_CODE = "code=403";

    private final ILinkClientFactory iLinkClientFactory;
    private final ILinkClientSessionRegistry sessionRegistry;
    private final UserThreadService userThreadService;
    private final WeChatLoginStateRepository loginStateRepository;

    /**
     * 应用启动完成后恢复全部已持久化的微信登录会话。
     *
     * <p>单个会话恢复失败不会阻断其他会话，也不会影响应用启动。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreLoginSessions() {
        List<LoginContext> loginContexts;
        try {
            loginContexts = loginStateRepository.findAll();
        } catch (RuntimeException exception) {
            log.error("读取微信持久化登录状态失败，跳过启动恢复", exception);
            return;
        }
        if (loginContexts.isEmpty()) {
            return;
        }

        int restoredCount = 0;
        for (LoginContext loginContext : loginContexts) {
            if (restoreLoginSession(loginContext)) {
                restoredCount++;
            }
        }
        log.info(
                "微信登录运行时会话恢复完成，记录数量={}，成功数量={}，失败数量={}",
                loginContexts.size(),
                restoredCount,
                loginContexts.size() - restoredCount);
    }

    /**
     * 创建独立的iLink客户端会话并发起扫码登录。
     *
     * @return 登录会话标识及二维码内容
     */
    @Override
    public LoginSessionResult createLogin() {
        String sessionId = UUID.randomUUID().toString();
        ILinkClient client = iLinkClientFactory.createClient(
                sessionId,
                failure -> handleHeartbeatFailure(sessionId, failure));
        ILinkClientSession session = new ILinkClientSession(sessionId, client);
        boolean registered = false;

        try {
            sessionRegistry.register(session);
            registered = true;

            String qrCodeContent = client.executeLogin();
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
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return sessionRegistry.find(sessionId)
                .map(session -> session.client().getLoginStatus())
                .orElseGet(this::expiredLoginStatus);
    }

    /**
     * 创建登录会话已失效的状态结果。
     *
     * @return 已失效的登录状态
     */
    private LoginStatus expiredLoginStatus() {
        LoginStatus loginStatus = new LoginStatus();
        loginStatus.toExpired();
        return loginStatus;
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

        String userId = resolveUserId(sessionId);
        cleanupLoginSession(sessionId, userId);
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
            handleLoginFailure(sessionId, throwable);
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

        persistLoginState(loginContext, sessionId);
        try {
            activateSession(sessionId, loginContext);
        } catch (RuntimeException exception) {
            closeFailedSession(session, true, exception);
            log.error(
                    "微信扫码登录后的会话激活失败，sessionId={}，userId={}",
                    sessionId,
                    loginContext.getUserId(),
                    exception);
            return;
        }
        log.info(
                "微信扫码登录成功，sessionId={}，userId={}，botId={}",
                sessionId,
                loginContext.getUserId(),
                loginContext.getBotId());
    }

    /**
     * 持久化扫码登录成功后的SDK登录上下文。
     *
     * <p>持久化失败不影响当前已建立的登录会话，但应用重启后需要重新扫码。</p>
     *
     * @param loginContext SDK登录上下文
     * @param sessionId 登录会话唯一标识
     */
    private void persistLoginState(
            LoginContext loginContext,
            String sessionId) {
        try {
            loginStateRepository.save(loginContext);
        } catch (RuntimeException exception) {
            log.error(
                    "微信登录状态持久化失败，重启后需要重新扫码，sessionId={}，userId={}",
                    sessionId,
                    loginContext.getUserId(),
                    exception);
        }
    }

    /**
     * 恢复单个持久化登录会话。
     *
     * @param loginContext SDK登录上下文
     * @return 恢复成功时返回true
     */
    private boolean restoreLoginSession(LoginContext loginContext) {
        if (loginContext == null) {
            log.error("微信持久化登录上下文为空，跳过当前记录");
            return false;
        }

        String sessionId = UUID.randomUUID().toString();
        ILinkClient client;
        try {
            client = iLinkClientFactory.createClient(
                    sessionId,
                    loginContext,
                    failure -> handleHeartbeatFailure(sessionId, failure));
        } catch (RuntimeException exception) {
            log.error(
                    "创建恢复登录客户端失败，userId={}",
                    loginContext.getUserId(),
                    exception);
            return false;
        }

        ILinkClientSession session = new ILinkClientSession(sessionId, client);
        boolean registered = false;
        try {
            sessionRegistry.register(session);
            registered = true;
            activateSession(sessionId, loginContext);
            log.info(
                    "微信登录运行时会话恢复成功，sessionId={}，userId={}，botId={}",
                    sessionId,
                    loginContext.getUserId(),
                    loginContext.getBotId());
            return true;
        } catch (RuntimeException exception) {
            closeFailedSession(session, registered, exception);
            log.error(
                    "微信登录状态恢复失败，userId={}",
                    loginContext.getUserId(),
                    exception);
            return false;
        }
    }

    /**
     * 启动会话消息线程并注册用户与会话的关联。
     *
     * @param sessionId 登录会话唯一标识
     * @param loginContext SDK登录上下文
     */
    private void activateSession(
            String sessionId,
            LoginContext loginContext) {
        userThreadService.startSession(sessionId);
        sessionRegistry.registerUser(loginContext.getUserId(), sessionId);
    }

    /**
     * 处理iLink客户端心跳失败。
     *
     * <p>会话过期或服务端明确返回401、403时清理失效登录状态；
     * 临时网络故障保留登录状态，由SDK在后续心跳中继续尝试连接。</p>
     *
     * @param sessionId 登录会话唯一标识
     * @param throwable 心跳异常
     */
    private void handleHeartbeatFailure(
            String sessionId,
            Throwable throwable) {
        try {
            if (!isAuthenticationFailure(throwable)) {
                log.warn(
                        "微信客户端心跳失败，保留登录状态等待恢复，sessionId={}，原因={}",
                        sessionId,
                        throwable == null ? null : throwable.getMessage());
                return;
            }
            invalidateLoginSession(sessionId);
        } catch (RuntimeException exception) {
            log.error(
                    "处理微信登录状态失效异常，sessionId={}",
                    sessionId,
                    exception);
        }
    }

    /**
     * 清理服务端已经拒绝的登录会话及持久化凭证。
     *
     * @param sessionId 登录会话唯一标识
     */
    private void invalidateLoginSession(String sessionId) {
        String userId = resolveUserId(sessionId);
        cleanupLoginSession(sessionId, userId);
        log.warn(
                "微信登录凭证已经失效，已清理登录状态，sessionId={}，userId={}",
                sessionId,
                userId);
    }

    /**
     * 清理指定登录会话的运行时资源和持久化状态。
     *
     * <p>各清理步骤相互隔离，单个步骤失败时仍继续执行剩余步骤。</p>
     *
     * @param sessionId 登录会话唯一标识
     * @param userId 微信用户标识，不存在时传入null
     */
    private void cleanupLoginSession(
            String sessionId,
            String userId) {
        RuntimeException cleanupFailure = null;
        try {
            userThreadService.stopSession(sessionId);
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
        }

        try {
            if (!sessionRegistry.closeAndRemove(sessionId)) {
                log.info("iLink客户端会话不存在，无需重复关闭，sessionId={}", sessionId);
            }
        } catch (RuntimeException exception) {
            cleanupFailure = mergeFailure(cleanupFailure, exception);
        }

        if (userId != null) {
            try {
                loginStateRepository.deleteByUserId(userId);
            } catch (RuntimeException exception) {
                cleanupFailure = mergeFailure(cleanupFailure, exception);
            }
        }
        if (cleanupFailure != null) {
            throw new IllegalStateException(
                    "微信登录会话未完全清理，sessionId=" + sessionId,
                    cleanupFailure);
        }
    }

    /**
     * 根据运行时会话查询微信用户标识。
     *
     * @param sessionId 登录会话唯一标识
     * @return 微信用户标识；会话或登录上下文不存在时返回null
     */
    private String resolveUserId(String sessionId) {
        return sessionRegistry.find(sessionId)
                .map(ILinkClientSession::client)
                .map(ILinkClient::getLoginContext)
                .map(LoginContext::getUserId)
                .filter(userId -> !userId.isBlank())
                .orElse(null);
    }

    /**
     * 判断心跳失败是否由服务端拒绝登录凭证导致。
     *
     * @param throwable 心跳异常
     * @return 异常链中包含会话过期异常、HTTP 401或403时返回true
     */
    private boolean isAuthenticationFailure(Throwable throwable) {
        Throwable failure = throwable;
        while (failure != null) {
            if (failure instanceof SessionExpiredException) {
                return true;
            }
            String message = failure.getMessage();
            if (message != null
                    && (message.contains(HTTP_UNAUTHORIZED_CODE)
                    || message.contains(HTTP_FORBIDDEN_CODE))) {
                return true;
            }
            failure = failure.getCause();
        }
        return false;
    }

    /**
     * 合并退出登录过程中产生的多个异常。
     *
     * @param existingFailure 已记录异常
     * @param newFailure 新异常
     * @return 合并后的异常
     */
    private RuntimeException mergeFailure(
            RuntimeException existingFailure,
            RuntimeException newFailure) {
        if (existingFailure == null) {
            return newFailure;
        }
        existingFailure.addSuppressed(newFailure);
        return existingFailure;
    }

    /**
     * 根据登录失败原因选择日志级别，正常终止场景不输出日志。
     *
     * @param sessionId 登录会话唯一标识
     * @param throwable SDK异步登录异常
     */
    private void handleLoginFailure(String sessionId, Throwable throwable) {
        Throwable failure = unwrapCompletionException(throwable);
        if (failure instanceof CancellationException
                || isConnectFailure(failure, QR_CODE_EXPIRED_MESSAGE)
                || isConnectFailure(failure, LOGIN_CANCELLED_MESSAGE)) {
            return;
        }
        if (isConnectFailure(failure, LOGIN_TIMEOUT_MESSAGE)) {
            log.warn("微信扫码登录超时，sessionId={}", sessionId);
            return;
        }
        log.error("微信扫码登录失败，sessionId={}", sessionId, failure);
    }

    /**
     * 解包CompletableFuture对真实异常的包装。
     *
     * @param throwable 待解包异常
     * @return SDK产生的真实异常
     */
    private Throwable unwrapCompletionException(Throwable throwable) {
        Throwable failure = throwable;
        while (failure instanceof CompletionException
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    /**
     * 判断异常是否为指定的SDK连接失败场景。
     *
     * @param failure 真实登录异常
     * @param expectedMessage SDK异常消息
     * @return 匹配指定连接失败场景时返回true
     */
    private boolean isConnectFailure(
            Throwable failure,
            String expectedMessage) {
        return failure instanceof ConnectFailedException
                && expectedMessage.equals(failure.getMessage());
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
            userThreadService.stopSession(session.sessionId());
        } catch (RuntimeException stopException) {
            originalException.addSuppressed(stopException);
            log.error(
                    "停止登录失败会话的消息线程异常，sessionId={}",
                    session.sessionId(),
                    stopException);
        }
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
