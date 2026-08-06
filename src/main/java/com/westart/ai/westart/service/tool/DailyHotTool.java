package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    private final JavaMailSender mailSender;
    private final Environment environment;

    @Tool(value = "发送电子邮件。toEmail为收件邮箱，必填；subject为邮件主题；content为邮件正文内容，"
            + "由调用方根据用户需求生成。本工具只负责发送邮件，不检索或生成内容，"
            + "内容必须在调用前由大模型生成好并传入。用户未提供邮箱地址时不要调用。")
    public String sendEmail(
            @P("收件人邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容，支持纯文本或HTML")             String content) {
        log.info("[DailyHotTool] sendEmail 开始执行，toEmail={}", toEmail);

        if (toEmail == null || toEmail.isBlank()) {
            throw new RuntimeException("邮件发送失败：收件邮箱为空");
        }
        if (!isValidEmail(toEmail)) {
            throw new RuntimeException("邮件发送失败：邮箱格式不正确：" + toEmail);
        }
        if (subject == null || subject.isBlank()) {
            subject = "无主题";
        }
        if (content == null || content.isBlank()) {
            throw new RuntimeException("邮件发送失败：邮件正文为空");
        }

        sendHtmlEmail(toEmail, subject, content);

        log.info("[DailyHotTool] sendEmail 发送成功，toEmail={}", toEmail);
        return "邮件已发送至 " + toEmail;
    }

    private boolean isValidEmail(String email) {
        try {
            InternetAddress address = new InternetAddress(email);
            address.validate();
            return true;
        } catch (Exception e) {
            return false;
        }
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
