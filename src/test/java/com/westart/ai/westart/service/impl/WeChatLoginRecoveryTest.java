package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ConnectFailedException;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.westart.ai.westart.DTO.ILinkClientSession;
import com.westart.ai.westart.config.ILinkClientFactory;
import com.westart.ai.westart.repository.WeChatLoginStateRepository;
import com.westart.ai.westart.service.UserThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatLoginRecoveryTest {

    private static final String USER_ID = "wechat-user-id";
    private static final String BOT_ID = "wechat-bot-id";

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

    private WeChatLoginServiceImpl loginService;

    @BeforeEach
    void setUp() {
        loginService = new WeChatLoginServiceImpl(
                clientFactory,
                sessionRegistry,
                userThreadService,
                loginStateRepository);
    }

    @Test
    void shouldPersistLoginContextAfterQrCodeLoginSucceeds() {
        LoginContext loginContext = loginContext();
        CompletableFuture<LoginContext> loginFuture = new CompletableFuture<>();
        AtomicReference<ILinkClientSession> registeredSession = new AtomicReference<>();
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.<Consumer<Throwable>>any()))
                .thenReturn(client);
        when(client.executeLogin()).thenReturn("qrcode-content");
        when(client.getLoginFuture()).thenReturn(loginFuture);
        doAnswer(invocation -> {
            registeredSession.set(invocation.getArgument(0));
            return null;
        }).when(sessionRegistry).register(org.mockito.ArgumentMatchers.any());
        when(sessionRegistry.find(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(registeredSession.get()));

        String sessionId = loginService.createLogin().sessionId();
        loginFuture.complete(loginContext);

        verify(loginStateRepository).save(loginContext);
        verify(userThreadService).startSession(sessionId);
        verify(sessionRegistry).registerUser(USER_ID, sessionId);
    }

    @Test
    void shouldCloseSessionWhenActivationFailsAfterQrCodeLogin() {
        LoginContext loginContext = loginContext();
        CompletableFuture<LoginContext> loginFuture = new CompletableFuture<>();
        AtomicReference<ILinkClientSession> registeredSession = new AtomicReference<>();
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.<Consumer<Throwable>>any()))
                .thenReturn(client);
        when(client.executeLogin()).thenReturn("qrcode-content");
        when(client.getLoginFuture()).thenReturn(loginFuture);
        doAnswer(invocation -> {
            registeredSession.set(invocation.getArgument(0));
            return null;
        }).when(sessionRegistry).register(org.mockito.ArgumentMatchers.any());
        when(sessionRegistry.find(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(registeredSession.get()));
        doThrow(new IllegalStateException("executor unavailable"))
                .when(userThreadService)
                .startSession(anyString());

        String sessionId = loginService.createLogin().sessionId();
        loginFuture.complete(loginContext);

        verify(userThreadService).stopSession(sessionId);
        verify(sessionRegistry).closeAndRemove(sessionId);
    }

    @Test
    void shouldRestorePersistedLoginContextWhenApplicationIsReady() {
        LoginContext loginContext = loginContext();
        when(loginStateRepository.findAll()).thenReturn(List.of(loginContext));
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.same(loginContext),
                org.mockito.ArgumentMatchers.<Consumer<Throwable>>any()))
                .thenReturn(client);

        loginService.restoreLoginSessions();

        ArgumentCaptor<ILinkClientSession> sessionCaptor =
                ArgumentCaptor.forClass(ILinkClientSession.class);
        verify(sessionRegistry).register(sessionCaptor.capture());
        ILinkClientSession restoredSession = sessionCaptor.getValue();
        assertThat(restoredSession.client()).isSameAs(client);
        verify(userThreadService).startSession(restoredSession.sessionId());
        verify(sessionRegistry).registerUser(USER_ID, restoredSession.sessionId());
    }

    @Test
    void shouldDeletePersistedLoginStateWhenUserLogsOut() {
        String sessionId = "login-session-id";
        LoginContext loginContext = loginContext();
        ILinkClientSession session = new ILinkClientSession(sessionId, client);
        when(client.getLoginContext()).thenReturn(loginContext);
        when(sessionRegistry.find(sessionId)).thenReturn(Optional.of(session));
        when(sessionRegistry.closeAndRemove(sessionId)).thenReturn(true);

        loginService.logout(sessionId);

        verify(loginStateRepository).deleteByUserId(USER_ID);
        verify(userThreadService).stopSession(sessionId);
        verify(sessionRegistry).closeAndRemove(sessionId);
    }

    @Test
    void shouldClearLoginStateWhenHeartbeatReportsUnauthorized() {
        LoginContext loginContext = loginContext();
        AtomicReference<ILinkClientSession> registeredSession = new AtomicReference<>();
        ArgumentCaptor<Consumer<Throwable>> heartbeatHandlerCaptor =
                consumerCaptor();
        when(loginStateRepository.findAll()).thenReturn(List.of(loginContext));
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.same(loginContext),
                heartbeatHandlerCaptor.capture()))
                .thenReturn(client);
        doAnswer(invocation -> {
            registeredSession.set(invocation.getArgument(0));
            return null;
        }).when(sessionRegistry).register(org.mockito.ArgumentMatchers.any());
        when(sessionRegistry.find(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(registeredSession.get()));
        when(client.getLoginContext()).thenReturn(loginContext);
        when(sessionRegistry.closeAndRemove(anyString())).thenReturn(true);
        loginService.restoreLoginSessions();

        heartbeatHandlerCaptor.getValue().accept(new ConnectFailedException(
                "POST request failed after retries",
                new IOException("POST failed code=401")));

        String sessionId = registeredSession.get().sessionId();
        verify(userThreadService).stopSession(sessionId);
        verify(sessionRegistry).closeAndRemove(sessionId);
        verify(loginStateRepository).deleteByUserId(USER_ID);
    }

    @Test
    void shouldRetainLoginStateWhenHeartbeatFailureIsTemporary() {
        LoginContext loginContext = loginContext();
        ArgumentCaptor<Consumer<Throwable>> heartbeatHandlerCaptor =
                consumerCaptor();
        when(loginStateRepository.findAll()).thenReturn(List.of(loginContext));
        when(clientFactory.createClient(
                anyString(),
                org.mockito.ArgumentMatchers.same(loginContext),
                heartbeatHandlerCaptor.capture()))
                .thenReturn(client);
        loginService.restoreLoginSessions();

        heartbeatHandlerCaptor.getValue().accept(new ConnectFailedException(
                "POST request failed after retries",
                new IOException("connection timed out")));

        verify(loginStateRepository, never()).deleteByUserId(USER_ID);
        verify(sessionRegistry, never()).closeAndRemove(anyString());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Consumer<Throwable>> consumerCaptor() {
        return ArgumentCaptor.forClass(Consumer.class);
    }

    private LoginContext loginContext() {
        return new LoginContext(
                "bot-token",
                USER_ID,
                BOT_ID,
                "https://ilink.example.com");
    }
}
