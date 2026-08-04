package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每日热点邮件推送工具。
 *
 * <p>执行流程：{@link WebSearchTool} 联网搜索热点 → {@link OpenAiChatModel} 生成摘要 → 邮件发送</p>
 */
@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    /** 联网搜索工具，负责获取热点原始数据 */
    private final WebSearchTool webSearchTool;

    /** 文本摘要模型，将搜索原始结果提炼为人读的精简摘要 */
    @Qualifier("textAssistantModel")
    private final OpenAiChatModel textAssistantModel;

    /** Spring 邮件发送器 */
    private final JavaMailSender mailSender;

    /** 用于读取邮件服务的配置信息 */
    private final Environment environment;

    /**
     * 搜索今日热点、生成摘要并发邮件给指定收件人。
     *
     * <p>三步串联：联网搜索 → 模型摘要 → HTML邮件发送。</p>
     *
     * @param toEmail 收件邮箱地址，由大模型从用户输入中提取
     * @return 操作结果提示
     */
    @Tool(value = "获取当天热点新闻，生成摘要并发送到指定电子邮箱。"
            + "本工具会直接发送邮件，不用于只查看热点新闻。toEmail为收件邮箱，必填；"
            + "用户未提供邮箱地址时不要调用。")
    public String sendDailyHotToEmail(String toEmail) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 开始执行，toEmail={}", toEmail);

        try {
            // 第一步：联网搜索今日热点
            String searchResult = webSearchTool.searchWeb("今日热点");

            // 第二步：大模型提炼摘要
            String dateStr = LocalDate.now().format(DATE_FORMATTER);
            String summary = summarizeHotTopics(searchResult, dateStr);

            // 第三步：发送 HTML 邮件
            sendHtmlEmail(toEmail, dateStr + " 今日热点", summary);

            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 执行成功，总耗时={}ms", totalDuration);
            return "今日热点已发送至 " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 执行失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "获取今日热点失败，请稍后重试。";
        }
    }

    /**
     * 调用大模型将联网搜索原始结果提炼为每日热点摘要。
     *
     * @param searchResult 联网搜索返回的原始数据
     * @param dateStr      当天日期，用于标题展示
     * @return 精炼后的热点摘要文本
     */
    private String summarizeHotTopics(String searchResult, String dateStr) {
        List<ChatMessage> messages = List.of(
                new SystemMessage("你是一个专业的新闻编辑，擅长从原始素材中提炼要点。"),
                new UserMessage("""
                        请根据以下联网搜索结果，整理 %s 的每日热点摘要。
                        要求：
                        1. 按重要性排序，列出前 5-10 条热点
                        2. 每条热点用一句话概括核心内容
                        3. 语言简洁明了，适合快速阅读
                        4. 末尾注明数据来源为联网搜索

                        搜索结果：
                        %s
                        """.formatted(dateStr, searchResult))
        );
        return textAssistantModel.chat(messages).aiMessage().text();
    }

    /**
     * 构建 HTML 邮件并发送至指定邮箱。
     *
     * @param to      收件邮箱地址
     * @param subject 邮件主题
     * @param content 邮件正文（纯文本，内部会转为 HTML 段落）
     * @throws RuntimeException 邮件服务未配置或发送失败时抛出
     */
    private void sendHtmlEmail(String to, String subject, String content) {
        // 校验邮件服务是否已配置
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置，MAIL_HOST={}，MAIL_USERNAME={}", mailHost, mailUsername);
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);

            // 构建包含热点的 HTML 邮件正文
            String html = "<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                    + "<h3>" + subject + "</h3>"
                    + "<div style='line-height: 1.8;'>"
                    + content.replace("\n", "<br>")
                    + "</div></body></html>";
            helper.setText(html, true);

            mailSender.send(message);
            log.debug("[DailyHotTool] sendHtmlEmail 完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}, subject={}", to, subject, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }
}
