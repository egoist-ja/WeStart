package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 邮件发送工具，只负责将给定的内容以 HTML 格式发送到指定邮箱。
 *
 * <p>邮件主题和正文内容由大模型根据用户需求生成后传入。</p>
 */
@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    /** Spring 邮件发送器 */
    private final JavaMailSender mailSender;

    /** 用于读取邮件服务的配置信息 */
    private final Environment environment;

    /**
     * 发送 HTML 格式邮件到指定邮箱。
     *
     * <p>三个参数均由大模型根据用户对话自动填充，本工具不自行生成内容。</p>
     *
     * @param toEmail 收件邮箱地址
     * @param subject 邮件主题，作为 HTML 页面的标题
     * @param content 邮件正文（纯文本），内部会按换行转为 HTML 段落
     * @return 操作结果提示
     */
    @Tool(value = "发送HTML邮件到指定邮箱。subject为邮件主题，content为邮件正文，toEmail为收件邮箱，均为必填。"
            + "邮件内容由你根据用户需求自行组织，不要编造信息。")
    public String sendEmail(
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容，应为纯文本") String content) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 开始执行，toEmail={}, subject={}", toEmail, subject);

        try {
            sendHtmlEmail(toEmail, subject, content);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 执行成功，总耗时={}ms", totalDuration);
            return "邮件已发送至 " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 执行失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "邮件发送失败，请稍后重试。";
        }
    }

    /**
     * 构建 HTML 邮件并通过 Spring Mail 发送。
     *
     * <p>纯文本 content 中的换行符会被转为 {@code <br>}，嵌套在预设样式中。</p>
     *
     * @param to      收件邮箱
     * @param subject 邮件主题
     * @param content 邮件正文（纯文本）
     * @throws RuntimeException 邮件服务未配置或 SMTP 发送失败
     */
    private void sendHtmlEmail(String to, String subject, String content) {
        // 校验 SMTP 配置是否存在
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置，MAIL_HOST={}，MAIL_USERNAME={}", mailHost, mailUsername);
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }

        try {
            // 构建 MIME 邮件（true = multipart，支持内嵌资源）
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);

            // 将纯文本拼为 HTML（换行 → <br>，套字体和间距样式）
            String html = "<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                    + "<h3>" + subject + "</h3>"
                    + "<div style='line-height: 1.8;'>"
                    + content.replace("\n", "<br>")
                    + "</div></body></html>";
            helper.setText(html, true);  // true = HTML 模式，非纯文本

            mailSender.send(message);
            log.debug("[DailyHotTool] sendHtmlEmail 完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}, subject={}", to, subject, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }
}
