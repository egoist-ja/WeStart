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

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private final WebSearchTool webSearchTool;

    @Qualifier("textAssistantModel")
    private final OpenAiChatModel textAssistantModel;

    private final JavaMailSender mailSender;
    private final Environment environment;

    private static final List<String> FAILURE_INDICATORS = List.of(
            "联网搜索没有返回可用结果", "联网搜索失败", "联网搜索暂时不可用",
            "联网搜索工具尚未配置");

    @Tool(value = "获取当天热点新闻，生成摘要并发送到指定电子邮箱。"
            + "本工具会直接发送邮件，不用于只查看热点新闻。toEmail为收件邮箱，必填；"
            + "用户未提供邮箱地址时不要调用。")
    public String sendDailyHotToEmail(String toEmail) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] sendDailyHotToEmail 开始执行，toEmail={}", toEmail);

        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        log.info("[DailyHotTool] 开始搜索今日热点，dateStr={}", dateStr);

        long searchStartTime = System.currentTimeMillis();
        String searchResult = webSearchTool.searchWeb("今日热点");
        long searchDuration = System.currentTimeMillis() - searchStartTime;
        log.info("[DailyHotTool] 联网搜索完成，耗时={}ms，resultLength={} chars", searchDuration,
                searchResult != null ? searchResult.length() : 0);

        if (isSearchFailed(searchResult)) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("[DailyHotTool] 搜索失败，耗时={}ms", duration);
            throw new RuntimeException("联网搜索未返回可用结果");
        }

        log.info("[DailyHotTool] 开始用大模型生成摘要");
        long summaryStartTime = System.currentTimeMillis();
        String summary = summarizeHotTopics(searchResult, dateStr);
        long summaryDuration = System.currentTimeMillis() - summaryStartTime;
        log.info("[DailyHotTool] 摘要生成完成，耗时={}ms，summaryLength={} chars", summaryDuration,
                summary != null ? summary.length() : 0);

        log.info("[DailyHotTool] 开始发送邮件，toEmail={}", toEmail);
        long emailStartTime = System.currentTimeMillis();
        sendHtmlEmail(toEmail, dateStr + " 今日热点", summary);
        long emailDuration = System.currentTimeMillis() - emailStartTime;
        log.info("[DailyHotTool] 邮件发送完成，耗时={}ms", emailDuration);

        long totalDuration = System.currentTimeMillis() - startTime;
        log.info("[DailyHotTool] sendDailyHotToEmail 执行成功，总耗时={}ms", totalDuration);
        return "今日热点已发送至 " + toEmail;
    }

    private boolean isSearchFailed(String result) {
        if (result == null) return true;
        return FAILURE_INDICATORS.stream().anyMatch(result::contains);
    }

    private String summarizeHotTopics(String searchResult, String dateStr) {
        log.debug("[DailyHotTool] summarizeHotTopics 开始，dateStr={}", dateStr);
        
        List<ChatMessage> messages = List.of(
                new SystemMessage("你是一个专业的新闻编辑，擅长从原始素材中提炼要点。"),
                new UserMessage("""
                        请根据以下联网搜索结果，整理 %s 的每日热点摘要。
                        要求：
                        1. 按重要性排序，列出前 5-10 条热点，用户感兴趣的领域的热点优先
                        2. 每条热点用一句话概括核心内容
                        3. 语言简洁明了，适合快速阅读
                        4. 末尾注明数据来源为联网搜索

                        搜索结果：
                        %s
                        """.formatted(dateStr, searchResult))
        );
        
        String result = textAssistantModel.chat(messages).aiMessage().text();
        log.debug("[DailyHotTool] summarizeHotTopics 完成，resultLength={} chars", 
                result != null ? result.length() : 0);
        return result;
    }

    private void sendHtmlEmail(String to, String subject, String content) {
        log.debug("[DailyHotTool] sendHtmlEmail 开始，to={}, subject={}", to, subject);

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
