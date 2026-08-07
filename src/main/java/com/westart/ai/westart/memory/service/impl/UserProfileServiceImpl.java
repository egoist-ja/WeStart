package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.dto.MessageDTO;
import com.westart.ai.westart.memory.entity.UserProfile;
import com.westart.ai.westart.memory.repository.UserProfileRepository;
import com.westart.ai.westart.memory.service.UserProfileService;
import com.westart.ai.westart.memory.service.ai.MemoryAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.westart.ai.westart.memory.service.ChatHistoryService.ROLE_USER;

/**
 * 用户画像功能实现，封装画像总结、存储和上下文构建流程。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final String EMPTY_PROFILE_CONTENT = "暂无已保存的用户长期画像";

    private final MemoryAssistant memoryAssistant;
    private final ObjectMapper objectMapper;
    private final UserProfileRepository userProfileRepository;

    /**
     * 根据细筛结果中的用户消息生成完整画像并持久化。
     *
     * 输入为空、没有用户消息或模型返回空画像时，不修改已有画像。
     *
     * @param messages 本批细筛后的业务消息
     */
    @Override
    public void updateProfile(List<MessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String wechatUserId = resolveWechatUserId(messages);
        List<MessageDTO> userMessages = messages.stream()
                .filter(message -> ROLE_USER.equals(message.role()))
                .toList();
        if (userMessages.isEmpty()) {
            log.info("细筛结果中没有用户消息，跳过画像更新，wechatUserId={}", wechatUserId);
            return;
        }

        List<String> profileContents = summarizeProfile(wechatUserId, userMessages);
        if (profileContents.isEmpty()) {
            log.info("模型未生成有效用户画像，保持已有画像不变，wechatUserId={}", wechatUserId);
            return;
        }

        UserProfile userProfile = new UserProfile();
        userProfile.setWechatUserId(wechatUserId);
        userProfile.setProfileContent(profileContents.stream()
                .map(content -> "- " + content)
                .collect(Collectors.joining(System.lineSeparator())));

        int affectedRows = userProfileRepository.upsert(userProfile);
        log.info(
                "用户画像更新完成，wechatUserId={}，profileCount={}，affectedRows={}",
                wechatUserId,
                profileContents.size(),
                affectedRows);
    }

    /**
     * 查询用户画像并组装为带固定边界的模型上下文。
     *
     * @param wechatUserId 微信用户ID
     * @return 带固定边界的用户画像上下文
     */
    @Override
    public String buildProfileContext(String wechatUserId) {
        String validWechatUserId = requireWechatUserId(wechatUserId);
        UserProfile userProfile = userProfileRepository.selectByUserId(validWechatUserId);
        String profileContent = userProfile == null || StringUtils.isBlank(userProfile.getProfileContent())
                ? EMPTY_PROFILE_CONTENT
                : userProfile.getProfileContent().trim();
        return "<user_memory>\n" + profileContent + "\n</user_memory>";
    }

    /**
     * 结合已有画像和本批用户消息生成当前完整画像集合。
     *
     * @param wechatUserId 微信用户ID
     * @param messages 本批用户消息
     * @return 当前完整画像集合
     */
    private List<String> summarizeProfile(
            String wechatUserId,
            List<MessageDTO> messages) {
        UserProfile existingProfile = userProfileRepository.selectByUserId(wechatUserId);
        List<String> existingProfileContents = existingProfile == null
                || StringUtils.isBlank(existingProfile.getProfileContent())
                ? List.of()
                : List.of(existingProfile.getProfileContent().trim());

        MemoryAssistant.ProfileResult profileResult;
        try {
            profileResult = memoryAssistant.summarizeUserProfile(
                    toAnalysisJson(messages),
                    toJson(existingProfileContents, "已有用户画像"));
        } catch (RuntimeException exception) {
            log.error(
                    "用户画像总结失败，wechatUserId={}，messageCount={}",
                    wechatUserId,
                    messages.size(),
                    exception);
            throw new IllegalStateException("用户画像总结失败", exception);
        }

        List<String> profileContents = profileResult == null
                ? List.of()
                : profileResult.memories();
        if (profileContents == null || profileContents.isEmpty()) {
            return List.of();
        }
        return profileContents.stream()
                .filter(content -> !StringUtils.isBlank(content))
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 校验同一批消息均属于同一微信用户。
     */
    private String resolveWechatUserId(List<MessageDTO> messages) {
        String wechatUserId = null;
        for (MessageDTO message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("用户画像消息列表不能包含空元素");
            }
            String currentWechatUserId = requireWechatUserId(message.wechatUserId());
            if (wechatUserId == null) {
                wechatUserId = currentWechatUserId;
            } else if (!wechatUserId.equals(currentWechatUserId)) {
                throw new IllegalArgumentException("用户画像消息不能包含多个微信用户");
            }
        }
        return wechatUserId;
    }

    /**
     * 只向画像模型提供用户消息的分析字段，不传递微信用户ID。
     *
     * @param messages 本批用户消息
     * @return 画像模型需要的消息JSON
     */
    private String toAnalysisJson(List<MessageDTO> messages) {
        List<Map<String, Object>> analysisMessages = messages.stream()
                .map(message -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("messageId", message.messageId());
                    fields.put("role", message.role());
                    fields.put("content", message.content());
                    fields.put("createdAt", message.createdAt());
                    return fields;
                })
                .toList();
        return toJson(analysisMessages, "用户画像消息");
    }

    /**
     * 序列化模型输入，避免在日志中输出原始画像或聊天内容。
     *
     * @param value 待序列化数据
     * @param dataName 数据名称
     * @return JSON字符串
     */
    private String toJson(Object value, String dataName) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            log.error("{}序列化失败", dataName, exception);
            throw new IllegalStateException(dataName + "序列化失败", exception);
        }
    }

    /**
     * 校验微信用户ID。
     *
     * @param wechatUserId 微信用户ID
     * @return 有效的微信用户ID
     */
    private static String requireWechatUserId(String wechatUserId) {
        if (StringUtils.isBlank(wechatUserId)) {
            throw new IllegalArgumentException("微信用户ID不能为空");
        }
        return wechatUserId;
    }
}
