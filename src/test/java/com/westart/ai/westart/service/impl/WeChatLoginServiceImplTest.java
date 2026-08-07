package com.westart.ai.westart.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ConnectFailedException;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.westart.ai.westart.wechat.infra.ILinkClientFactory;
import com.westart.ai.westart.wechat.repository.WeChatLoginStateRepository;
import com.westart.ai.westart.wechat.service.UserThreadService;
import com.westart.ai.westart.wechat.service.impl.ILinkClientSessionRegistry;
import com.westart.ai.westart.wechat.service.impl.WeChatLoginServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatLoginServiceImplTest {

    private static final String QR_CODE_CONTENT = "qrcode-content";

    @Mock
    private ILinkClientFactory clientFactory;
    @Mock
    private ILinkClientSessionRegistry sessionRegistry;
    @Mock
    private UserThreadService userThreadService;
    @Mock
    private WeChatLoginStateRepository loginStateRepository;
    @Mock
    private ILinkClient client;

    private CompletableFuture<LoginContext> loginFuture;
    private ListAppender<ILoggingEvent> logAppender;
    private WeChatLoginServiceImpl loginService;

    @BeforeEach
    void setUp() {
        loginFuture = new CompletableFuture<>();
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.<Consumer<Throwable>>any()))
                .thenReturn(client);
        when(client.executeLogin()).thenReturn(QR_CODE_CONTENT);
        when(client.getLoginFuture()).thenReturn(loginFuture);
        when(sessionRegistry.closeAndRemove(anyString())).thenReturn(true);
        loginService = new WeChatLoginServiceImpl(
                clientFactory,
                sessionRegistry,
                userThreadService,
                loginStateRepository);

        Logger logger = (Logger) LoggerFactory.getLogger(
                WeChatLoginServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                WeChatLoginServiceImpl.class);
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void shouldSilentlyCloseSessionWhenQrCodeExpires() {
        String sessionId = loginService.createLogin().sessionId();

        loginFuture.completeExceptionally(new CompletionException(
                new ConnectFailedException("qrcode expired")));

        assertThat(logAppender.list).isEmpty();
        verify(sessionRegistry).closeAndRemove(sessionId);
    }

    @Test
    void shouldSilentlyCloseSessionWhenLoginIsCancelled() {
        String sessionId = loginService.createLogin().sessionId();

        loginFuture.completeExceptionally(new CancellationException(
                "login cancelled"));

        assertThat(logAppender.list).isEmpty();
        verify(sessionRegistry).closeAndRemove(sessionId);
    }

    @Test
    void shouldWarnWhenLoginTimesOut() {
        String sessionId = loginService.createLogin().sessionId();

        loginFuture.completeExceptionally(new CompletionException(
                new ConnectFailedException("login timeout")));

        assertThat(logAppender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("微信扫码登录超时")
                            .contains(sessionId);
                });
        verify(sessionRegistry).closeAndRemove(sessionId);
    }

    @Test
    void shouldLogUnexpectedLoginFailureAsError() {
        String sessionId = loginService.createLogin().sessionId();
        IllegalStateException failure = new IllegalStateException(
                "unexpected failure");

        loginFuture.completeExceptionally(failure);

        assertThat(logAppender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("微信扫码登录失败")
                            .contains(sessionId);
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
        verify(sessionRegistry).closeAndRemove(sessionId);
    }
}
