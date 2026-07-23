package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.service.WeChatLoginService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 微信机器人登录服务实现，管理全局唯一机器人账号的扫码注册与登录生命周期。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeChatLoginServiceImpl implements WeChatLoginService {

    private final ILinkClient iLinkClient;
    private volatile boolean closed;

    /**
     * 为全局唯一的微信机器人发起扫码注册，并异步记录登录授权结果。
     *
     * <p>该方法不创建普通用户登录会话。机器人登录后，所有用户消息由同一个
     * {@link ILinkClient} 拉取，并在消息处理层根据userId区分。</p>
     *
     * @return 用于生成登录二维码的内容
     */
    @Override
    public String createLogin() {
        if (closed) {
            throw new IllegalStateException("微信机器人客户端已关闭，无法再次注册");
        }
        if (iLinkClient.isLoggedIn()) {
            throw new IllegalStateException("微信机器人已经注册并登录，请勿重复扫码");
        }
        LoginStatus.Status status = iLinkClient.getLoginStatus().getStatus();
        if (status == LoginStatus.Status.WAITING || status == LoginStatus.Status.SCANNED) {
            throw new IllegalStateException("微信机器人正在等待扫码或确认，请勿重复注册");
        }

        final String qrCodeContent;
        try {
            qrCodeContent = iLinkClient.executeLogin();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("微信机器人扫码注册发起失败", exception);
        }
        if (StringUtils.isBlank(qrCodeContent)) {
            iLinkClient.cancelLogin();
            throw new IllegalStateException("微信机器人扫码注册失败，二维码内容为空");
        }
        iLinkClient.getLoginFuture().whenComplete((loginContext, throwable) -> {
            if (throwable != null) {
                log.error("微信机器人扫码注册失败", throwable);
                return;
            }
            log.info("微信机器人注册并登录成功，iLink SDK开始轮询全部用户消息");
        });
        return qrCodeContent;
    }

    /**
     * 获取当前微信机器人的登录状态。
     *
     * @return 登录状态
     */
    @Override
    public LoginStatus getLoginStatus() {
        return iLinkClient.getLoginStatus();
    }

    /**
     * 取消未完成的登录并关闭全局微信客户端。
     *
     * <p>底层SDK关闭后不可复用，因此该操作主要用于主动下线或应用关闭。</p>
     */
    @Override
    public void logout() {
        if (closed) {
            log.info("微信客户端已经关闭，无需重复退出");
            return;
        }
        try {
            iLinkClient.cancelLogin();
            iLinkClient.clearAllContexts();
            iLinkClient.close();
            iLinkClient.getLoginStatus().reset();
            closed = true;
            log.info("微信客户端已关闭");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("微信客户端关闭失败", exception);
        }
    }
}
