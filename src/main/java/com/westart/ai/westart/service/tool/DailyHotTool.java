package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service("dailyHotTool")
@RequiredArgsConstructor
public class DailyHotTool {

    private static final Logger log = LoggerFactory.getLogger(DailyHotTool.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private final WebSearchTool webSearchService;

    @Qualifier("textAssistantModel")
    private final OpenAiChatModel textAssistantModel;

    private final JavaMailSender mailSender;
    private final Environment environment;

    private static final List<String> FAILURE_INDICATORS = List.of(
            "联网搜索没有返回可用结果", "联网搜索失败", "联网搜索暂时不可用");

    @Tool(value = "当用户需要获取每日热点新闻摘要并发送到指定邮箱时调用该工具。" +
            "toEmail表示接收热点摘要的邮箱地址（必填），" +
            "如果用户没有提供邮箱地址，则不调用该方法，并提示用户提供邮箱地址")
    public String sendDailyHotToEmail(String toEmail) {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        log.info("搜索今日热点");
        String searchResult = webSearchService.searchWeb("今日热点");
        if (isSearchFailed(searchResult)) {
            String msg = "获取今日热点失败，请稍后重试。";
            log.warn(msg);
            return msg;
        }
        log.info("联网搜索完成，开始用大模型生成摘要");
        String summary = summarizeHotTopics(searchResult, dateStr);
        log.info("摘要生成完成，开始发送邮件至 {}", toEmail);
        sendHtmlEmail(toEmail, dateStr + " 今日热点", summary);
        log.info("邮件发送完成");
        return "今日热点已发送至 " + toEmail;
    }

    private boolean isSearchFailed(String result) {
        if (result == null) return true;
        return FAILURE_INDICATORS.stream().anyMatch(result::contains);
    }

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

    private void sendHtmlEmail(String to, String subject, String content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(environment.getProperty("spring.mail.username"));
            helper.setTo(to);
            helper.setSubject(subject);
            String html = "<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                    + "<h3>" + subject + "</h3>"
                    + "<div style='line-height: 1.8;'>"
                    + content.replace("\n", "<br>")
                    + "</div></body></html>";
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("邮件发送失败", e);
        }
    }
}
