package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    private final JavaMailSender mailSender;
    private final Environment environment;

    @Tool(value = "发送电子邮件。toEmail为收件邮箱，必填；subject为邮件主题；content为邮件正文内容，"
            + "由调用方根据用户需求生成。attachmentPath为图片附件的本地绝对路径，可选，如 /path/to/image.png。"
            + "本工具只负责发送邮件，不检索或生成内容，"
            + "内容必须在调用前由大模型生成好并传入。用户未提供邮箱地址时不要调用。")
    public String sendEmail(
            @P("收件人邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容，支持纯文本或HTML") String content,
            @P("图片附件的本地绝对路径，可选，如 D:/images/photo.png") String attachmentPath) {
        log.info("[DailyHotTool] sendEmail 开始执行，toEmail={}，attachmentPath={}", toEmail, attachmentPath);

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

        sendHtmlEmail(toEmail, subject, content, attachmentPath);

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

    private void sendHtmlEmail(String to, String subject, String content, String attachmentPath) {
        log.debug("[DailyHotTool] sendHtmlEmail 开始，to={}，subject={}", to, subject);

        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置，MAIL_HOST={}，MAIL_USERNAME={}", mailHost, mailUsername);
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                doSendEmail(to, subject, content, attachmentPath, mailUsername);
                return;
            } catch (Exception e) {
                lastException = e;
                if (isRetryableException(e) && attempt < 2) {
                    long delayMs = 3000L + (long) (Math.random() * 1000);
                    log.warn("邮件发送第{}次尝试失败，{}ms后重试：{}", attempt, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    break;
                }
            }
        }

        if (lastException instanceof MessagingException) {
            log.error("[DailyHotTool] 邮件发送最终失败，to={}, subject={}", to, subject, lastException);
            throw new RuntimeException("邮件发送失败，请稍后重试。", lastException);
        } else {
            throw new RuntimeException("邮件发送失败，请稍后重试。", lastException);
        }
    }

    private void doSendEmail(String to, String subject, String content,
                              String attachmentPath, String mailUsername) throws MessagingException {
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

        if (attachmentPath != null && !attachmentPath.isBlank()) {
            java.io.File file = new java.io.File(attachmentPath);
            if (!file.exists()) {
                log.error("邮件附件不存在，attachmentPath={}", attachmentPath);
                throw new RuntimeException("邮件发送失败：附件文件不存在：" + attachmentPath);
            }
            helper.addAttachment(file.getName(), new FileSystemResource(file));
            log.info("[DailyHotTool] 添加附件，fileName={}", file.getName());
        }

        mailSender.send(message);
        log.debug("[DailyHotTool] sendHtmlEmail 完成");
    }

    private boolean isRetryableException(Exception e) {
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException) return true;
        if (e instanceof UnknownHostException) return true;
        if (e instanceof MailSendException) {
            Throwable cause = e.getCause();
            return cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException;
        }
        if (e instanceof MessagingException) {
            Throwable cause = e.getCause();
            return cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException;
        }
        return false;
    }
}
