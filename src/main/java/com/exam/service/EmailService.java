package com.exam.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1200L;
    
    @Autowired
    private JavaMailSender mailSender;
    
    @Value("${spring.mail.username}")
    private String fromEmail;
    
    @Value("${app.base.url:http://localhost:8080}")
    private String baseUrl;
    
    /**
     * Send verification email to user
     */
    public boolean sendVerificationEmail(String toEmail, String fullName, String verificationToken) {
        String safeName = fullName == null || fullName.isBlank() ? "User" : fullName.trim();
        String encodedToken = URLEncoder.encode(verificationToken, StandardCharsets.UTF_8);
        String verificationPageUrl = baseUrl + "/verify?token=" + encodedToken;
        String textBody = "Dear " + safeName + ",\n\n"
                + "Thank you for registering with the Adaptive Examination System.\n\n"
                + "Please click the link below to verify your email address:\n\n"
                + verificationPageUrl + "\n\n"
            + "Important: Verify your email before logging in to your account.\n\n"
                + "For your security, this link should be used as soon as possible.\n\n"
                + "If you did not create an account, please ignore this email.\n\n"
                + "Best regards,\n"
                + "Adaptive Examination System Team";

        String htmlBody = buildVerificationEmailHtml(safeName, verificationPageUrl);
        return sendWithRetry(toEmail, "Verify Your Account - Adaptive Examination System", textBody, htmlBody, "verification");
    }
    
    /**
     * Send password reset email
     */
    @Async("emailTaskExecutor")
    public void sendPasswordResetEmail(String toEmail, String fullName, String resetToken) {
        String safeName = fullName == null || fullName.isBlank() ? "User" : fullName.trim();
        String resetUrl = baseUrl + "/reset-password?token=" + resetToken;
        String textBody = "Dear " + safeName + ",\n\n"
                + "We received a request to reset your password.\n\n"
                + "Click the link below to reset your password:\n\n"
                + resetUrl + "\n\n"
                + "This link will expire in 1 hour.\n\n"
                + "If you did not request a password reset, please ignore this email.\n\n"
                + "Best regards,\n"
                + "Adaptive Examination System Team";

        sendWithRetry(toEmail, "Password Reset Request - Adaptive Examination System", textBody, null, "password reset");
    }

    private boolean sendWithRetry(String toEmail, String subject, String textBody, String htmlBody, String emailType) {
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                // Multipart must be enabled when setting both plain-text and HTML alternatives.
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(toEmail);
                helper.setSubject(subject);
                if (htmlBody != null && !htmlBody.isBlank()) {
                    helper.setText(textBody, htmlBody);
                } else {
                    helper.setText(textBody, false);
                }
                mailSender.send(message);
                logger.info("{} email sent to {} on attempt {}", emailType, toEmail, attempt);
                return true;
            } catch (Exception exception) {
                if (htmlBody != null && !htmlBody.isBlank()) {
                    try {
                        MimeMessage fallbackMessage = mailSender.createMimeMessage();
                        MimeMessageHelper fallbackHelper = new MimeMessageHelper(fallbackMessage, false, "UTF-8");
                        fallbackHelper.setFrom(fromEmail);
                        fallbackHelper.setTo(toEmail);
                        fallbackHelper.setSubject(subject);
                        fallbackHelper.setText(textBody, false);
                        mailSender.send(fallbackMessage);
                        logger.info("{} email sent to {} on attempt {} using plain-text fallback", emailType, toEmail, attempt);
                        return true;
                    } catch (Exception fallbackException) {
                        logger.warn("Plain-text fallback failed on attempt {} for {} email to {}", attempt, emailType, toEmail, fallbackException);
                    }
                }

                if (attempt >= MAX_SEND_ATTEMPTS) {
                    logger.error("Failed to send {} email to {} after {} attempts", emailType, toEmail, attempt, exception);
                    return false;
                }

                logger.warn("Attempt {} failed sending {} email to {}. Retrying...", attempt, emailType, toEmail, exception);
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted while retrying {} email for {}", emailType, toEmail, interruptedException);
                    return false;
                }
            }
        }
        return false;
    }

    private String buildVerificationEmailHtml(String fullName,
                                              String verificationPageUrl) {
        return """
                <!DOCTYPE html>
                <html lang=\"en\">
                <head>
                    <meta charset=\"UTF-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <title>Email Verification</title>
                </head>
                <body style=\"margin:0;padding:0;background:#f3f3f3;font-family:Segoe UI,Arial,sans-serif;color:#1f1f1f;\">
                    <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" style=\"padding:28px 14px;\">
                        <tr>
                            <td align=\"center\">
                                <table role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:640px;background:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 14px 40px rgba(0,0,0,0.12);\">
                                    <tr>
                                        <td style=\"padding:0;background:linear-gradient(120deg,#7a1022 0%%,#6a0f1f 56%%,#8a1f35 100%%);\">
                                            <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\">
                                                <tr>
                                                    <td style=\"padding:22px 28px;color:#ffffff;\">
                                                        <div style=\"font-size:12px;letter-spacing:1.2px;text-transform:uppercase;opacity:0.84;\">Emilio Aguinaldo College</div>
                                                        <div style=\"font-size:24px;font-weight:700;margin-top:6px;\">Adaptive Examination System</div>
                                                        <div style=\"font-size:14px;margin-top:8px;opacity:0.92;\">Email Verification</div>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style=\"padding:28px;\">
                                            <p style=\"margin:0 0 14px;font-size:16px;\">Hi <strong>%s</strong>,</p>
                                            <p style=\"margin:0 0 20px;font-size:15px;line-height:1.6;color:#343434;\">
                                                Verify your account by clicking the button below.
                                            </p>

                                            <p style=\"margin:0 0 18px;padding:12px 14px;border-radius:10px;background:#fff4e8;border:1px solid #ffd8a8;color:#8a4b00;font-size:14px;line-height:1.55;\">
                                                Important: Please verify your email first before trying to log in.
                                            </p>

                                            <div style=\"margin:0 0 18px;text-align:center;\">
                                                <a href=\"%s\" style=\"display:inline-block;padding:12px 20px;border-radius:10px;background:#7a1022;color:#fff8ef;text-decoration:none;font-weight:700;border:1px solid #d4a32a;\">Verify Email Address</a>
                                            </div>

                                            <p style=\"margin:0;font-size:12px;line-height:1.6;color:#767676;\">
                                                If you did not create an account, you can safely ignore this email.
                                            </p>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style=\"padding:14px 28px;background:#f7f1e7;color:#6d6d6d;font-size:12px;line-height:1.6;border-top:1px solid #ead9c2;\">
                                            Adaptive Examination System • Emilio Aguinaldo College
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """.formatted(fullName, verificationPageUrl);
    }

}
