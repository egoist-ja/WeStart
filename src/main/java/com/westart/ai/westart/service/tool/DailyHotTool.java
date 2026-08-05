package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 邮件发送工具，支持 HTML/纯文本两种格式，支持 Base64 附件。
 */
@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    private final JavaMailSender mailSender;
    private final Environment environment;

    @Tool(value = "发送HTML或纯文本邮件。isHtml为true时发送HTML格式，false时发送纯文本。所有参数必填。")
    public String sendEmail(
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String content,
            @P("是否HTML格式，true为HTML，false为纯文本") boolean isHtml) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 开始执行，toEmail={}, isHtml={}", toEmail, isHtml);

        try {
            sendMail(toEmail, subject, content, isHtml);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 执行成功，总耗时={}ms", totalDuration);
            return "邮件已发送至 " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 执行失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "邮件发送失败，请稍后重试。";
        }
    }

    @Tool(value = "发送携带附件的邮件（HTML或纯文本）。attachmentBase64为文件Base64编码，attachmentName为文件名含扩展名。所有参数必填。")
    public String sendEmailWithAttachment(
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String content,
            @P("是否HTML格式") boolean isHtml,
            @P("附件的Base64编码数据") String attachmentBase64,
            @P("附件文件名，含扩展名如 report.pdf") String attachmentName) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 带附件发送，toEmail={}, isHtml={}, attach={}", toEmail, isHtml, attachmentName);

        try {
            sendMailWithAttachment(toEmail, subject, content, isHtml, attachmentBase64, attachmentName);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 带附件发送成功，总耗时={}ms", totalDuration);
            return "带附件的邮件已发送至  " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 带附件发送失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "邮件发送失败，请稍后重试。";
        }
    }

    private void sendMail(String to, String subject, String content, boolean isHtml) {
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置");
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, isHtml, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            if (isHtml) {
                helper.setText("<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                        + "<h3>" + subject + "</h3>"
                        + "<div style='line-height: 1.8;'>"
                        + content.replace("\n", "<br>")
                        + "</div></body></html>", true);
            } else {
                helper.setText(content);
            }
            mailSender.send(message);
            log.debug("[DailyHotTool] sendMail 完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}", to, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }

    private void sendMailWithAttachment(
            String to, String subject, String content, boolean isHtml,
            String attachmentBase64, String attachmentName) {
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置");
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }
        try {
            byte[] fileData = Base64.getDecoder().decode(attachmentBase64);
            DataSource dataSource = new ByteArrayDataSource(fileData, "application/octet-stream");

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            if (isHtml) {
                helper.setText("<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                        + "<h3>" + subject + "</h3>"
                        + "<div style='line-height: 1.8;'>"
                        + content.replace("\n", "<br>")
                        + "</div></body></html>", true);
            } else {
                helper.setText(content);
            }
            helper.addAttachment(attachmentName, dataSource);
            mailSender.send(message);
            log.debug("[DailyHotTool] 带附件发送完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}", to, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }
}
